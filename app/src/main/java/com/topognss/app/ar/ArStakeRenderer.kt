package com.topognss.app.ar

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.topognss.app.geo.LocalGeodesy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Dibuja la cámara y, encima, el mojón del punto a replantear en su posición real (OpenGL ES 2.0,
 * sin motores 3D externos). También publica la posición en pantalla del mojón para el overlay.
 */
class ArStakeRenderer(private val context: Context, private val controller: ArStakeController) : GLSurfaceView.Renderer {

    private val background = BackgroundRenderer()
    private val shapes = ShapeRenderer()

    private var width = 1
    private var height = 1
    private var viewportChanged = false
    private var textureSession: Any? = null

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)

    private var frameCount = 0L
    private var lastPublish = 0L
    private var floorY: Float? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
        shapes.create()
        textureSession = null
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(w, 1)
        height = max(h, 1)
        GLES20.glViewport(0, 0, width, height)
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = controller.session ?: return
        if (textureSession !== session) {
            session.setCameraTextureName(background.textureId)
            textureSession = session
        }
        if (viewportChanged) {
            session.setDisplayGeometry(displayRotation(), width, height)
            viewportChanged = false
        }
        val frame: Frame = try {
            session.update()
        } catch (e: Exception) {
            return
        }
        background.draw(frame)

        val camera = frame.camera
        val now = System.currentTimeMillis()
        if (camera.trackingState != TrackingState.TRACKING) {
            publish(now, force = false) {
                it.copy(trackingOk = false, trackingMessage = trackingText(camera), headingDeg = null, target = null)
            }
            return
        }

        val pose = camera.displayOrientedPose
        controller.poses.add(ArPose(now, pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble()))
        camera.getProjectionMatrix(proj, 0, 0.05f, 500f)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        if (frameCount++ % 15 == 0L) floorY = findFloor(frame, pose.ty())

        val st = controller.aligner.status
        val tr = st.transform?.takeIf { st.ready }

        // Rumbo: hacia donde mira la cámara; con el teléfono casi horizontal, el borde superior de la pantalla.
        val heading = tr?.let {
            val z = pose.zAxis
            val fx = -z[0].toDouble()
            val fz = -z[2].toDouble()
            if (hypot(fx, fz) > 0.5) arHeadingDeg(it, fx, fz)
            else pose.yAxis.let { y -> arHeadingDeg(it, y[0].toDouble(), y[2].toDouble()) }
        }

        var screen: ScreenPoint? = null
        val target = controller.target
        val origin = controller.origin
        if (tr != null && target != null && origin != null) {
            val d = LocalGeodesy.delta(origin.first, origin.second, target.lat, target.lon)
            val (lx, ly) = tr.toLocal(d.dEast, d.dNorth)
            val tx = lx.toFloat()
            val tz = (-ly).toFloat()
            val camY = pose.ty()
            val ground = floorY ?: (camY - DEFAULT_PHONE_HEIGHT)
            // Pendiente: se corrige con el desnivel GNSS entre el terreno actual y la cota del punto.
            val dh = target.elevation?.let { te -> controller.groundElevationNow?.let { g -> te - g } }
                ?.takeIf { abs(it) < 30 } ?: 0.0
            val ty = ground + dh.toFloat()
            val dist = hypot(tx - pose.tx(), tz - pose.tz())

            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(true)
            drawPath(pose.tx(), ground, pose.tz(), tx, ty, tz)
            drawStake(tx, ty, tz, dist)
            screen = project(tx, ty + STAKE_HEIGHT * stakeScale(dist), tz)
        }

        publish(now, force = false) {
            it.copy(
                trackingOk = true, trackingMessage = null, alignment = st, headingDeg = heading,
                target = screen, groundDetected = floorY != null,
            )
        }
    }

    private inline fun publish(now: Long, force: Boolean, crossinline f: (ArUiState) -> ArUiState) {
        if (!force && now - lastPublish < 66) return
        lastPublish = now
        controller.updateUi { f(it) }
    }

    /** Altura del piso: el plano horizontal más grande bajo el teléfono. */
    private fun findFloor(frame: Frame, camY: Float): Float? {
        val session = controller.session ?: return null
        var best: Plane? = null
        var bestArea = 0f
        for (p in session.getAllTrackables(Plane::class.java)) {
            if (p.trackingState != TrackingState.TRACKING || p.subsumedBy != null) continue
            if (p.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            val y = p.centerPose.ty()
            if (y > camY - 0.5f || y < camY - 3f) continue
            val area = p.extentX * p.extentZ
            if (area > bestArea) { bestArea = area; best = p }
        }
        return best?.centerPose?.ty()
    }

    /** Lejos el mojón se agranda para que siga siendo visible (tamaño angular mínimo). */
    private fun stakeScale(dist: Float) = max(1f, dist / 25f)

    private fun drawStake(x: Float, y: Float, z: Float, dist: Float) {
        val k = stakeScale(dist)
        // Anillo en el piso (marca el punto exacto)
        shapes.draw(shapes.ring, at(x, y + 0.01f, z, 0.35f * k, 1f, 0.35f * k), viewProj, COLOR_RING)
        // Estaca
        shapes.draw(shapes.cylinder, at(x, y, z, 0.05f * k, STAKE_HEIGHT * k, 0.05f * k), viewProj, COLOR_STAKE)
        // Franja blanca y cabeza roja
        shapes.draw(shapes.cylinder, at(x, y + 0.55f * k, z, 0.055f * k, 0.12f * k, 0.055f * k), viewProj, COLOR_WHITE)
        shapes.draw(shapes.cylinder, at(x, y + STAKE_HEIGHT * k, z, 0.09f * k, 0.08f * k, 0.09f * k), viewProj, COLOR_HEAD)
        // Baliza alta semitransparente para ubicarlo de lejos
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        shapes.draw(shapes.cylinder, at(x, y + STAKE_HEIGHT * k, z, 0.02f * k, 3f * k, 0.02f * k), viewProj, COLOR_BEACON)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Flechas en el piso desde el operador hacia el mojón (cada 1,5 m, hasta 30 m). */
    private fun drawPath(cx: Float, cy: Float, cz: Float, tx: Float, ty: Float, tz: Float) {
        val dx = tx - cx
        val dz = tz - cz
        val dist = hypot(dx, dz)
        if (dist < 2f) return
        val angle = Math.toDegrees(atan2(-dz, dx).toDouble()).toFloat()
        var s = 1.5f
        while (s < min(dist - 0.8f, 30f)) {
            val f = s / dist
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, cx + dx * f, cy + (ty - cy) * f + 0.02f, cz + dz * f)
            Matrix.rotateM(model, 0, angle, 0f, 1f, 0f)
            Matrix.scaleM(model, 0, 0.6f, 1f, 0.6f)
            shapes.draw(shapes.chevron, model, viewProj, COLOR_PATH)
            s += 1.5f
        }
    }

    private fun at(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float): FloatArray {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        Matrix.scaleM(model, 0, sx, sy, sz)
        return model
    }

    /** Proyección a píxeles de pantalla y dirección al borde cuando queda fuera del encuadre. */
    private fun project(x: Float, y: Float, z: Float): ScreenPoint {
        val p = floatArrayOf(x, y, z, 1f)
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, viewProj, 0, p, 0)
        val v = FloatArray(4)
        Matrix.multiplyMV(v, 0, view, 0, p, 0)
        val behind = clip[3] <= 0f
        val nx = if (!behind) clip[0] / clip[3] else 0f
        val ny = if (!behind) clip[1] / clip[3] else 0f
        val onScreen = !behind && abs(nx) <= 0.95f && abs(ny) <= 0.95f
        // En el marco de la vista: x derecha, y arriba, −z adelante.
        var angle = Math.toDegrees(atan2(v[0].toDouble(), v[1].toDouble())).toFloat()
        if (behind && abs(v[0]) < 0.25f * abs(v[2])) angle = 180f
        else if (behind) angle = if (v[0] > 0) 90f else -90f
        else if (!onScreen) angle = Math.toDegrees(atan2(nx.toDouble(), ny.toDouble())).toFloat()
        return ScreenPoint(
            x = (nx + 1f) / 2f * width,
            y = (1f - ny) / 2f * height,
            onScreen = onScreen, edgeAngleDeg = angle, behind = behind,
        )
    }

    private fun trackingText(camera: Camera): String = when (camera.trackingFailureReason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Poca luz: el tracking AR necesita ver el entorno"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Mové el teléfono más despacio"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Apuntá a una zona con más textura (piso, pasto, objetos)"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Cámara en uso por otra app"
        else -> "Buscando el entorno: mové el teléfono lentamente"
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = runCatching {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }.getOrDefault(Surface.ROTATION_0)

    companion object {
        const val STAKE_HEIGHT = 1.0f
        /** Altura típica del teléfono en la mano si ARCore aún no detectó el piso. */
        const val DEFAULT_PHONE_HEIGHT = 1.4f
        val COLOR_STAKE = floatArrayOf(1.0f, 0.55f, 0.0f, 1f)
        val COLOR_WHITE = floatArrayOf(0.95f, 0.95f, 0.95f, 1f)
        val COLOR_HEAD = floatArrayOf(0.9f, 0.1f, 0.1f, 1f)
        val COLOR_RING = floatArrayOf(0.15f, 0.85f, 0.3f, 1f)
        val COLOR_BEACON = floatArrayOf(1.0f, 0.55f, 0.0f, 0.45f)
        val COLOR_PATH = floatArrayOf(0.2f, 0.7f, 1.0f, 1f)
    }
}

// ---------------------------------------------------------------------------------------------
// GL auxiliares
// ---------------------------------------------------------------------------------------------

private fun compile(type: Int, src: String): Int {
    val s = GLES20.glCreateShader(type)
    GLES20.glShaderSource(s, src)
    GLES20.glCompileShader(s)
    val ok = IntArray(1)
    GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
    if (ok[0] == 0) {
        val log = GLES20.glGetShaderInfoLog(s)
        GLES20.glDeleteShader(s)
        throw IllegalStateException("Shader: $log")
    }
    return s
}

private fun program(vs: String, fs: String): Int {
    val p = GLES20.glCreateProgram()
    GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
    GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
    GLES20.glLinkProgram(p)
    return p
}

private fun floatBuffer(data: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(data); position(0)
    }

/** Imagen de la cámara de fondo (textura externa OES que llena ARCore). */
private class BackgroundRenderer {
    var textureId = -1
        private set
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTex = 0
    private val quad = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTex = floatBuffer(FloatArray(8))

    fun create() {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        textureId = t[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        program = program(
            """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() { gl_FragColor = texture2D(u_Texture, v_TexCoord); }
            """.trimIndent(),
        )
        aPos = GLES20.glGetAttribLocation(program, "a_Position")
        aTex = GLES20.glGetAttribLocation(program, "a_TexCoord")
        uTex = GLES20.glGetUniformLocation(program, "u_Texture")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quad.position(0); quadTex.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                Coordinates2d.TEXTURE_NORMALIZED, quadTex,
            )
        }
        if (frame.timestamp == 0L) return
        quad.position(0); quadTex.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glDepthMask(true)
    }
}

/** Mallas simples (posición + normal) con color plano e iluminación difusa. */
private class ShapeRenderer {
    class Mesh(val buffer: FloatBuffer, val count: Int)

    lateinit var cylinder: Mesh
    lateinit var ring: Mesh
    lateinit var chevron: Mesh
    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0
    private val mvp = FloatArray(16)

    fun create() {
        program = program(
            """
            uniform mat4 u_Mvp;
            uniform mat4 u_Model;
            attribute vec3 a_Position;
            attribute vec3 a_Normal;
            varying float v_Light;
            void main() {
                gl_Position = u_Mvp * vec4(a_Position, 1.0);
                vec3 n = normalize((u_Model * vec4(a_Normal, 0.0)).xyz);
                v_Light = 0.55 + 0.45 * max(dot(n, normalize(vec3(0.4, 0.8, 0.3))), 0.0);
            }
            """.trimIndent(),
            """
            precision mediump float;
            uniform vec4 u_Color;
            varying float v_Light;
            void main() { gl_FragColor = vec4(u_Color.rgb * v_Light, u_Color.a); }
            """.trimIndent(),
        )
        aPos = GLES20.glGetAttribLocation(program, "a_Position")
        aNormal = GLES20.glGetAttribLocation(program, "a_Normal")
        uMvp = GLES20.glGetUniformLocation(program, "u_Mvp")
        uModel = GLES20.glGetUniformLocation(program, "u_Model")
        uColor = GLES20.glGetUniformLocation(program, "u_Color")
        cylinder = cylinderMesh(24)
        ring = ringMesh(32, 0.75f)
        chevron = chevronMesh()
    }

    fun draw(mesh: Mesh, model: FloatArray, viewProj: FloatArray, color: FloatArray) {
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform4fv(uColor, 1, color, 0)
        mesh.buffer.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, mesh.buffer)
        mesh.buffer.position(3)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 24, mesh.buffer)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.count)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)
    }

    private fun mesh(v: List<Float>) = Mesh(floatBuffer(v.toFloatArray()), v.size / 6)

    /** Cilindro de radio 1 y altura 1 (y = 0..1) con tapa superior. */
    private fun cylinderMesh(seg: Int): Mesh {
        val v = ArrayList<Float>()
        fun vert(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) { v += listOf(x, y, z, nx, ny, nz) }
        for (i in 0 until seg) {
            val a0 = (2 * Math.PI * i / seg)
            val a1 = (2 * Math.PI * (i + 1) / seg)
            val x0 = cos(a0).toFloat(); val z0 = sin(a0).toFloat()
            val x1 = cos(a1).toFloat(); val z1 = sin(a1).toFloat()
            vert(x0, 0f, z0, x0, 0f, z0); vert(x1, 0f, z1, x1, 0f, z1); vert(x1, 1f, z1, x1, 0f, z1)
            vert(x0, 0f, z0, x0, 0f, z0); vert(x1, 1f, z1, x1, 0f, z1); vert(x0, 1f, z0, x0, 0f, z0)
            vert(0f, 1f, 0f, 0f, 1f, 0f); vert(x1, 1f, z1, 0f, 1f, 0f); vert(x0, 1f, z0, 0f, 1f, 0f)
        }
        return mesh(v)
    }

    /** Corona plana (radio exterior 1, interior [inner]) en el plano y = 0. */
    private fun ringMesh(seg: Int, inner: Float): Mesh {
        val v = ArrayList<Float>()
        fun vert(x: Float, z: Float) { v += listOf(x, 0f, z, 0f, 1f, 0f) }
        for (i in 0 until seg) {
            val a0 = (2 * Math.PI * i / seg)
            val a1 = (2 * Math.PI * (i + 1) / seg)
            val c0 = cos(a0).toFloat(); val s0 = sin(a0).toFloat()
            val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()
            vert(c0, s0); vert(c1, s1); vert(c1 * inner, s1 * inner)
            vert(c0, s0); vert(c1 * inner, s1 * inner); vert(c0 * inner, s0 * inner)
        }
        return mesh(v)
    }

    /** Punta de flecha plana apuntando a +X. */
    private fun chevronMesh(): Mesh {
        val v = ArrayList<Float>()
        fun vert(x: Float, z: Float) { v += listOf(x, 0f, z, 0f, 1f, 0f) }
        vert(0.3f, 0f); vert(-0.2f, 0.28f); vert(-0.05f, 0f)
        vert(0.3f, 0f); vert(-0.05f, 0f); vert(-0.2f, -0.28f)
        return mesh(v)
    }
}
