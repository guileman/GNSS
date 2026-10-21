package com.topognss.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.topognss.app.geo.CrsType
import com.topognss.app.geo.ProjectCrs
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val crsType: String = CrsType.UTM.name,
    val zone: Int = 21,
    val south: Boolean = true,
    val originLat: Double = 0.0,
    val originLon: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Nomenclatura catastral del predio (formato libre: varía por provincia/país). Se guarda
     * como un único string compuesto (p. ej. "Circ. II - Secc. B - Mz. 14 - Parc. 7") más sus
     * partes sueltas para completar formularios o planillas que las requieran por separado.
     */
    val catastroCircunscripcion: String = "",
    val catastroSeccion: String = "",
    val catastroManzanaOChacra: String = "",
    val catastroParcela: String = "",
    val catastroPartida: String = "",
)

/** Nomenclatura catastral armada a partir de las partes del proyecto, lista para mostrar/exportar. */
fun ProjectEntity.nomenclaturaCatastral(): String {
    val parts = listOfNotNull(
        catastroCircunscripcion.takeIf { it.isNotBlank() }?.let { "Circ. $it" },
        catastroSeccion.takeIf { it.isNotBlank() }?.let { "Secc. $it" },
        catastroManzanaOChacra.takeIf { it.isNotBlank() }?.let { "Mz./Ch. $it" },
        catastroParcela.takeIf { it.isNotBlank() }?.let { "Parc. $it" },
        catastroPartida.takeIf { it.isNotBlank() }?.let { "Partida $it" },
    )
    return parts.joinToString(" - ")
}

/** Extensiones (fuera de la entidad para que Room no intente persistirlas). */
fun ProjectEntity.crs(): ProjectCrs = ProjectCrs(
    type = runCatching { CrsType.valueOf(crsType) }.getOrDefault(CrsType.UTM),
    zone = zone, south = south, originLat = originLat, originLon = originLon,
)

/** Origen del punto: relevado con GNSS, generado por COGO, cargado para replantear, o replanteado. */
enum class PointKind(val label: String) {
    SURVEY("Relevado"),
    COGO("COGO"),
    STAKE("A replantear"),
    STAKED("Replanteado"),
}

@Entity(
    tableName = "points",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("projectId"), Index(value = ["projectId", "name"], unique = true)],
)
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val code: String = "",
    val kind: String = PointKind.SURVEY.name,
    /** Geográficas WGS84/ITRF (o el marco de la base del RS2). */
    val lat: Double,
    val lon: Double,
    /** Planas en el sistema del proyecto. */
    val easting: Double,
    val northing: Double,
    /** Cota (altura ortométrica, H) usada en cálculos. */
    val elevation: Double?,
    /** Altura elipsoidal h (si se midió con GNSS). */
    val ellipsoidalHeight: Double? = null,
    val hrms: Double? = null,
    val vrms: Double? = null,
    val pdop: Double? = null,
    val satellites: Int? = null,
    val solution: String? = null,
    val epochs: Int = 0,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

fun PointEntity.pointKind(): PointKind = runCatching { PointKind.valueOf(kind) }.getOrDefault(PointKind.SURVEY)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: Long): ProjectEntity?

    @Insert
    suspend fun insert(p: ProjectEntity): Long

    @Update
    suspend fun update(p: ProjectEntity)

    @Delete
    suspend fun delete(p: ProjectEntity)
}

@Dao
interface PointDao {
    @Query("SELECT * FROM points WHERE projectId = :projectId ORDER BY createdAt ASC, id ASC")
    fun observeByProject(projectId: Long): Flow<List<PointEntity>>

    @Query("SELECT * FROM points WHERE projectId = :projectId ORDER BY createdAt ASC, id ASC")
    suspend fun listByProject(projectId: Long): List<PointEntity>

    @Query("SELECT * FROM points WHERE id = :id")
    suspend fun get(id: Long): PointEntity?

    @Query("SELECT * FROM points WHERE id = :id")
    fun observe(id: Long): Flow<PointEntity?>

    @Query("SELECT name FROM points WHERE projectId = :projectId")
    suspend fun names(projectId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(p: PointEntity): Long

    @Update
    suspend fun update(p: PointEntity)

    @Delete
    suspend fun delete(p: PointEntity)
}

/** Agrega las columnas de nomenclatura catastral al proyecto. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE projects ADD COLUMN catastroCircunscripcion TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE projects ADD COLUMN catastroSeccion TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE projects ADD COLUMN catastroManzanaOChacra TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE projects ADD COLUMN catastroParcela TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE projects ADD COLUMN catastroPartida TEXT NOT NULL DEFAULT ''")
    }
}

@Database(entities = [ProjectEntity::class, PointEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun pointDao(): PointDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "topognss.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
