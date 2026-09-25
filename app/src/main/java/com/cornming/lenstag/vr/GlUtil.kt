package com.cornming.lenstag.vr

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** 編譯 shader、建立貼圖這類 OpenGL 例行工作。 */
internal object GlUtil {

    /** 編譯並連結一組 shader；失敗就丟例外（由呼叫端接住、退回相容模式）。 */
    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("shader 連結失敗：$log")
        }
        // 連結完 shader 物件就用不到了
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("shader 編譯失敗：$log")
        }
        return shader
    }

    /** 建立一張貼圖，線性取樣、邊緣夾住（非 2 的次方尺寸在 GLES2 只能這樣用）。 */
    fun createTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun floatBuffer(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
}

/** 蓋滿目前 viewport 的四邊形，uv 左下角 (0,0)、右上角 (1,1)。 */
internal class FullScreenQuad {
    private val positions = GlUtil.floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uvs = GlUtil.floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    fun draw(program: Int) {
        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aUv = GLES20.glGetAttribLocation(program, "aUv")
        positions.position(0)
        uvs.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uvs)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aUv)
    }
}

internal const val VERTEX_SHADER = """
attribute vec2 aPosition;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

/**
 * 相機畫面：從相機的外部貼圖取色。先依 FILL_CENTER 裁切，必要時轉 180 度，
 * 最後套 SurfaceTexture 給的貼圖矩陣（處理相機緩衝區本身的翻轉）。
 * 「#extension」必須是第一個非註解的指令，所以放最前面。
 */
internal const val CAMERA_FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform samplerExternalOES uCamera;
uniform mat4 uTexMatrix;
uniform vec2 uCropScale;
uniform float uFlip;
void main() {
    vec2 p = (vUv - 0.5) * uCropScale + 0.5;
    p = mix(p, vec2(1.0) - p, uFlip);
    vec2 t = (uTexMatrix * vec4(p, 0.0, 1.0)).xy;
    gl_FragColor = texture2D(uCamera, t);
}
"""

/** 疊加層：Bitmap 的原點在左上、GL 在左下，所以 v 要上下翻。 */
internal const val OVERLAY_FRAGMENT_SHADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform sampler2D uOverlay;
void main() {
    gl_FragColor = texture2D(uOverlay, vec2(vUv.x, 1.0 - vUv.y));
}
"""

/** 變形校正：跟 VrMath.distortSourceUv 同一套數學，改這裡要一起改那裡（那邊有測試）。 */
internal const val DISTORT_FRAGMENT_SHADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform sampler2D uEye;
uniform vec2 uLensCenter;
uniform float uAspect;
uniform float uK1;
uniform float uK2;
void main() {
    vec2 scale = vec2(uAspect, 1.0) * 2.0;
    vec2 d = (vUv - uLensCenter) * scale;
    float r2 = dot(d, d);
    float f = 1.0 + uK1 * r2 + uK2 * r2 * r2;
    vec2 src = d * f / scale + 0.5;
    if (src.x < 0.0 || src.x > 1.0 || src.y < 0.0 || src.y > 1.0) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    } else {
        gl_FragColor = texture2D(uEye, src);
    }
}
"""
