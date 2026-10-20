package com.topognss.app.gnss

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dueño único de la conexión con el receptor (vive en el Application, sobrevive a la navegación).
 *
 * - [state]: último estado agregado (UI: barra de estado, replanteo…).
 * - [epochs]: una emisión por cada GGA con posición (relevamiento / promediado).
 * - [rawLines]: últimas tramas recibidas, para la consola de diagnóstico.
 *
 * Reconexión automática con backoff mientras el usuario no pida desconectar.
 */
class GnssManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aggregator = NmeaAggregator()
    private val lock = Any()

    private val _state = MutableStateFlow(GnssState())
    val state: StateFlow<GnssState> = _state.asStateFlow()

    private val _epochs = MutableSharedFlow<Epoch>(extraBufferCapacity = 64)
    val epochs: SharedFlow<Epoch> = _epochs.asSharedFlow()

    private val _rawLines = MutableStateFlow<List<String>>(emptyList())
    val rawLines: StateFlow<List<String>> = _rawLines.asStateFlow()
    private val rawBuffer = ArrayDeque<String>()
    private var lastRawPublish = 0L

    @Volatile private var transport: GnssTransport? = null
    private var job: Job? = null

    fun connect(t: GnssTransport) {
        disconnect()
        transport = t
        job = scope.launch {
            var backoff = 1000L
            while (isActive && transport === t) {
                publishConnection(ConnectionStatus.CONNECTING, t.label)
                try {
                    t.run(
                        onConnected = {
                            backoff = 1000L
                            publishConnection(ConnectionStatus.CONNECTED, t.label)
                        },
                        onLine = ::onLine,
                    )
                } catch (e: Exception) {
                    if (transport !== t) break // desconexión pedida por el usuario
                    publishConnection(ConnectionStatus.ERROR, t.label, e.message ?: e.javaClass.simpleName)
                }
                t.closeAttempt() // cierra el intento fallido; close() es sólo para la desconexión del usuario
                if (transport !== t) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(8000L)
            }
        }
    }

    fun disconnect() {
        val t = transport
        transport = null
        t?.close()      // primero cerrar el socket: destraba el read() bloqueante
        job?.cancel()
        job = null
        publishConnection(ConnectionStatus.DISCONNECTED, "")
    }

    private fun publishConnection(status: ConnectionStatus, label: String, error: String? = null) {
        synchronized(lock) {
            aggregator.setConnection(status, label, error)
            _state.value = aggregator.state
        }
    }

    /** Se invoca desde el hilo lector del transporte. */
    private fun onLine(line: String) {
        val epoch: Epoch?
        synchronized(lock) {
            epoch = aggregator.accept(line)
            _state.value = aggregator.state
            rawBuffer.addLast(line)
            while (rawBuffer.size > 60) rawBuffer.removeFirst()
            val now = System.currentTimeMillis()
            if (now - lastRawPublish > 300) { // limitar recomposiciones de la consola
                lastRawPublish = now
                _rawLines.value = rawBuffer.toList()
            }
        }
        if (epoch != null) _epochs.tryEmit(epoch)
    }
}
