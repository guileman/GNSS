package com.topognss.app.data

import android.content.Context
import android.content.SharedPreferences
import com.topognss.app.cogo.P3
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

class TopoRepository(private val db: AppDatabase) {
    private val projects = db.projectDao()
    private val points = db.pointDao()

    fun observeProjects(): Flow<List<ProjectEntity>> = projects.observeAll()
    fun observeProject(id: Long): Flow<ProjectEntity?> = projects.observe(id)
    suspend fun project(id: Long) = projects.get(id)
    suspend fun createProject(p: ProjectEntity): Long = projects.insert(p)
    suspend fun updateProject(p: ProjectEntity) = projects.update(p)
    suspend fun deleteProject(p: ProjectEntity) = projects.delete(p)

    fun observePoints(projectId: Long): Flow<List<PointEntity>> = points.observeByProject(projectId)
    fun observePoint(id: Long): Flow<PointEntity?> = points.observe(id)
    suspend fun points(projectId: Long) = points.listByProject(projectId)
    suspend fun point(id: Long) = points.get(id)
    suspend fun deletePoint(p: PointEntity) = points.delete(p)
    suspend fun updatePoint(p: PointEntity) = points.update(p)

    /** Guarda (o borra, con null) la ruta de la foto georreferenciada de un punto. */
    suspend fun setPhoto(p: PointEntity, path: String?) = points.update(p.copy(photoPath = path))

    /**
     * Próximo nombre libre con prefijo: P001, P002… Busca el mayor sufijo numérico existente.
     * Si el usuario escribió "B7", el siguiente será "B8" (respeta el ancho de dígitos).
     */
    suspend fun nextName(projectId: Long, prefix: String, digits: Int = 3): String {
        val regex = Regex("^" + Regex.escape(prefix) + "(\\d+)$")
        val max = points.names(projectId).mapNotNull { regex.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return prefix + (max + 1).toString().padStart(digits, '0')
    }

    suspend fun nameExists(projectId: Long, name: String) = points.names(projectId).any { it.equals(name, ignoreCase = false) }

    /** Inserta un punto medido (geográficas) calculando sus planas en el CRS del proyecto. */
    suspend fun insertGeographic(project: ProjectEntity, base: PointEntity): Long {
        val xy = project.crs().projection.forward(base.lat, base.lon)
        return points.insert(base.copy(projectId = project.id, easting = xy.east, northing = xy.north))
    }

    /** Inserta un punto definido en planas (COGO / carga manual) calculando sus geográficas. */
    suspend fun insertPlane(
        project: ProjectEntity, name: String, code: String, p: P3, kind: PointKind, note: String = "",
    ): Long {
        val ll = project.crs().projection.inverse(p.e, p.n)
        return points.insert(
            PointEntity(
                projectId = project.id, name = name, code = code, kind = kind.name,
                lat = ll.lat, lon = ll.lon, easting = p.e, northing = p.n, elevation = p.z, note = note,
            )
        )
    }

    /** Recalcula E/N de todos los puntos medidos si se cambia el CRS del proyecto. */
    suspend fun reprojectAll(project: ProjectEntity) {
        val proj = project.crs().projection
        for (pt in points.listByProject(project.id)) {
            val xy = proj.forward(pt.lat, pt.lon)
            points.update(pt.copy(easting = xy.east, northing = xy.north))
        }
    }

    companion object {
        fun toCsv(project: ProjectEntity, list: List<PointEntity>): String {
            val sb = StringBuilder()
            sb.append("# Proyecto: ${project.name} · CRS: ${project.crs().describe()}\n")
            sb.append("Nombre,Este,Norte,Cota,Codigo,Tipo,Latitud,Longitud,AltElipsoidal,Solucion,HRMS,VRMS,PDOP,Sats,Epocas,Nota\n")
            fun d(v: Double?, dec: Int) = v?.let { String.format(Locale.US, "%.${dec}f", it) } ?: ""
            for (p in list) {
                sb.append(listOf(
                    p.name.csv(), d(p.easting, 4), d(p.northing, 4), d(p.elevation, 4), p.code.csv(), p.kind,
                    d(p.lat, 9), d(p.lon, 9), d(p.ellipsoidalHeight, 4), p.solution ?: "",
                    d(p.hrms, 4), d(p.vrms, 4), d(p.pdop, 2), p.satellites?.toString() ?: "", p.epochs.toString(), p.note.csv(),
                ).joinToString(",")).append('\n')
            }
            return sb.toString()
        }

        private fun String.csv() = if (contains(',') || contains('"') || contains('\n')) "\"" + replace("\"", "\"\"") + "\"" else this
    }
}

/** Preferencias simples (sin DataStore para mantener las dependencias al mínimo). */
class AppPrefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("topognss", Context.MODE_PRIVATE)

    private val _activeProjectId = MutableStateFlow(sp.getLong(K_PROJECT, -1L))
    val activeProjectId: StateFlow<Long> = _activeProjectId

    fun setActiveProject(id: Long) {
        sp.edit().putLong(K_PROJECT, id).apply()
        _activeProjectId.value = id
    }

    /** Tema de la interfaz: SYSTEM / LIGHT / DARK (nombres de [com.topognss.app.ui.theme.ThemeMode]). */
    private val _themeMode = MutableStateFlow(sp.getString("theme", "SYSTEM") ?: "SYSTEM")
    val themeMode: StateFlow<String> = _themeMode
    fun setThemeMode(mode: String) {
        sp.edit().putString("theme", mode).apply()
        _themeMode.value = mode
    }

    /** Capa de mapa base (nombres de [com.topognss.app.ui.map.MapLayer]). */
    private val _mapLayer = MutableStateFlow(sp.getString("mapLayer", "STREETS") ?: "STREETS")
    val mapLayer: StateFlow<String> = _mapLayer
    fun setMapLayer(layer: String) {
        sp.edit().putString("mapLayer", layer).apply()
        _mapLayer.value = layer
    }

    var lastHost: String
        get() = sp.getString("host", "192.168.42.1") ?: "192.168.42.1"
        set(v) = sp.edit().putString("host", v).apply()
    var lastPort: Int
        get() = sp.getInt("port", 9001)
        set(v) = sp.edit().putInt("port", v).apply()
    var lastMode: String
        get() = sp.getString("mode", "BLUETOOTH") ?: "BLUETOOTH"
        set(v) = sp.edit().putString("mode", v).apply()
    var lastBtAddress: String?
        get() = sp.getString("bt", null)
        set(v) = sp.edit().putString("bt", v).apply()

    var surveyEpochs: Int
        get() = sp.getInt("epochs", 5)
        set(v) = sp.edit().putInt("epochs", v).apply()
    var surveyMaxHrms: Float
        get() = sp.getFloat("maxHrms", 0.02f)
        set(v) = sp.edit().putFloat("maxHrms", v).apply()
    var surveyMaxVrms: Float
        get() = sp.getFloat("maxVrms", 0.04f)
        set(v) = sp.edit().putFloat("maxVrms", v).apply()
    var surveyRequireFix: Boolean
        get() = sp.getBoolean("reqFix", true)
        set(v) = sp.edit().putBoolean("reqFix", v).apply()
    var surveyPrefix: String
        get() = sp.getString("prefix", "P") ?: "P"
        set(v) = sp.edit().putString("prefix", v).apply()
    /** Altura de jalón/antena ya considerada en el receptor? Si no, se resta aquí (m). */
    var antennaHeight: Float
        get() = sp.getFloat("antH", 0f)
        set(v) = sp.edit().putFloat("antH", v).apply()

    var stakeTolerance: Float
        get() = sp.getFloat("stakeTol", 0.10f)
        set(v) = sp.edit().putFloat("stakeTol", v).apply()
    var stakeSound: Boolean
        get() = sp.getBoolean("stakeSound", true)
        set(v) = sp.edit().putBoolean("stakeSound", v).apply()

    companion object { private const val K_PROJECT = "activeProject" }
}

