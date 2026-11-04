package app.livecomposer.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 画一个任意四边形（顶点在 NDC 中给出），纹理坐标经过 texMatrix 变换。
 * 片元输出 = 纹理颜色 × uAlpha；纹理按“预乘 Alpha”处理，配合 glBlendFunc(ONE, ONE_MINUS_SRC_ALPHA)。
 */
class QuadRenderer(private val external: Boolean) {
    private val program: Int
    private val aPos: Int
    private val aUv: Int
    private val uTexMatrix: Int
    private val uAlpha: Int
    private val pos: FloatBuffer = floatBuffer(8)
    private val uv: FloatBuffer = floatBuffer(8).apply {
        // 与 pos 的顶点顺序对应：左下、右下、左上、右上
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0)
    }

    init {
        val vs = """
            attribute vec4 aPos;
            attribute vec4 aUv;
            uniform mat4 uTexMatrix;
            varying vec2 vUv;
            void main() {
                gl_Position = aPos;
                vUv = (uTexMatrix * aUv).xy;
            }
        """.trimIndent()
        val fs = if (external) """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uTex;
            uniform float uAlpha;
            void main() { gl_FragColor = texture2D(uTex, vUv) * uAlpha; }
        """.trimIndent() else """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform float uAlpha;
            void main() { gl_FragColor = texture2D(uTex, vUv) * uAlpha; }
        """.trimIndent()
        program = createProgram(vs, fs)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
    }

    /** corners: 4 个顶点 (x,y)×4，顺序 左下、右下、左上、右上（指图像自身的方向）。 */
    fun draw(texture: Int, texMatrix: FloatArray, corners: FloatArray, alpha: Float) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, texture)
        pos.position(0); pos.put(corners); pos.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(aPos)
        uv.position(0)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1f(uAlpha, alpha)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    fun release() = GLES20.glDeleteProgram(program)

    companion object {
        val IDENTITY = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

        /** 2D 纹理（Bitmap 上传 / FBO）在 GL 中 v 轴朝上：Bitmap 第一行在 v=0，需要翻转。 */
        val FLIP_V = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

        fun floatBuffer(n: Int): FloatBuffer =
            ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                throw IllegalStateException("shader compile failed: $log")
            }
            return s
        }

        fun createProgram(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("link failed: ${GLES20.glGetProgramInfoLog(p)}")
            return p
        }

        fun createTexture(external: Boolean): Int {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            val target = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
            GLES20.glBindTexture(target, t[0])
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return t[0]
        }

        fun checkError(where: String) {
            val e = GLES20.glGetError()
            if (e != GLES20.GL_NO_ERROR) Log.w("GL", "$where: glError 0x${Integer.toHexString(e)}")
        }
    }
}
