package com.topognss.app.ui.plumb

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topognss.app.sensors.PlumbReading
import com.topognss.app.sensors.PlumbSensor
import com.topognss.app.ui.theme.FixGreen
import com.topognss.app.ui.theme.FloatYellow
import com.topognss.app.ui.theme.SingleRed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

/** Tolerancia "a plomo" por defecto: 0,3° ≈ 5 mm por metro de jalón. */
private const val TOLERANCE_DEG = 0.3f

class PlumbViewModel(app: Application) : AndroidViewModel(app) {
    private val sensor = PlumbSensor(app)
    val available = sensor.isAvailable
    private val _reading = MutableStateFlow(PlumbReading(0f, 0f, 0f, 0f))
    val reading: StateFlow<PlumbReading> = _reading

    fun start() {
        if (!available) return
        viewModelScope.launch {
            sensor.readings().collect { _reading.value = it }
        }
    }
}

@Composable
fun PlumbScreen(vm: PlumbViewModel = viewModel()) {
    DisposableEffect(Unit) {
        vm.start()
        onDispose { }
    }
    val reading by vm.reading.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Plomada virtual", style = MaterialTheme.typography.titleLarge)
        Text(
            "Apoyá el teléfono contra el jalón, en vertical, con la pantalla hacia vos.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        if (!vm.available) {
            Text(
                "Este equipo no tiene acelerómetro disponible.",
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        val inside = reading.tiltDeg <= TOLERANCE_DEG
        val near = reading.tiltDeg <= TOLERANCE_DEG * 4
        val color by animateColorAsState(
            when {
                inside -> FixGreen
                near -> FloatYellow
                else -> SingleRed
            },
            label = "plumbColor",
        )

        BubbleLevel(reading, color, Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 24.dp))

        Column(Modifier.padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "%.2f°".format(reading.tiltDeg),
                style = MaterialTheme.typography.displayMedium,
                color = color,
            )
            Text(
                "%.1f mm cada metro de jalón".format(reading.mmPerMeter),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                if (inside) "A plomo" else "Corregí: " +
                    (if (reading.tiltRightDeg > 0) "derecha" else "izquierda") + " / " +
                    (if (reading.tiltForwardDeg > 0) "adelante" else "atrás"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Nivel de burbuja: el círculo central se desplaza según la inclinación medida. */
@Composable
private fun BubbleLevel(reading: PlumbReading, color: Color, modifier: Modifier = Modifier) {
    val maxDeg = 10f // rango visual del nivel: ±10° a los bordes del círculo exterior
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.CircleShape)) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = min(size.width, size.height) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Anillos de referencia.
            for (frac in listOf(0.33f, 0.66f, 1f)) {
                drawCircle(Color.Gray.copy(alpha = 0.3f), radius = radius * frac, center = center, style = Stroke(width = 1.5f))
            }
            // Cruz central.
            drawLine(Color.Gray.copy(alpha = 0.4f), Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), strokeWidth = 1.5f)
            drawLine(Color.Gray.copy(alpha = 0.4f), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), strokeWidth = 1.5f)

            // Burbuja: x = derecha/izquierda, y = -adelante/atrás (pantalla: arriba = atrás alejándose).
            val fx = (reading.tiltRightDeg / maxDeg).coerceIn(-1f, 1f)
            val fy = (-reading.tiltForwardDeg / maxDeg).coerceIn(-1f, 1f)
            val bubbleRadius = radius * 0.16f
            val travel = radius - bubbleRadius
            val bubbleCenter = Offset(center.x + fx * travel, center.y + fy * travel)
            drawCircle(color.copy(alpha = 0.25f), radius = bubbleRadius * 1.6f, center = bubbleCenter)
            drawCircle(color, radius = bubbleRadius, center = bubbleCenter)

            // Marca de tolerancia (círculo interior "a plomo").
            val toleranceRadius = radius * (TOLERANCE_DEG / maxDeg)
            drawCircle(FixGreen.copy(alpha = 0.5f), radius = toleranceRadius, center = center, style = Stroke(width = 3f))
        }
    }
}
