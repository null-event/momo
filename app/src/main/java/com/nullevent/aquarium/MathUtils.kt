package com.nullevent.aquarium

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Self-contained 4x4 matrix and 3-vector math helpers.
 *
 * All matrices are 16-element [FloatArray]s in COLUMN-MAJOR order, matching the
 * memory layout expected by OpenGL ES. Element (row, col) lives at index
 * `col * 4 + row`. No external math libraries (GLM / JOML / etc.) are used.
 */
object MathUtils {

    private const val DEG2RAD = (Math.PI / 180.0).toFloat()

    // ---------------------------------------------------------------------
    // Matrix construction
    // ---------------------------------------------------------------------

    fun identity(m: FloatArray) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
    }

    fun newIdentity(): FloatArray {
        val m = FloatArray(16)
        identity(m)
        return m
    }

    /** Builds a right-handed perspective projection (column-major). */
    fun perspectiveM(m: FloatArray, fovyDeg: Float, aspect: Float, near: Float, far: Float) {
        val f = (1.0 / tan((fovyDeg * DEG2RAD / 2f).toDouble())).toFloat()
        val rangeInv = 1f / (near - far)
        for (i in 0 until 16) m[i] = 0f
        m[0] = f / aspect
        m[5] = f
        m[10] = (near + far) * rangeInv
        m[11] = -1f
        m[14] = 2f * near * far * rangeInv
    }

    /** Builds a right-handed view matrix (column-major). */
    fun lookAtM(
        m: FloatArray,
        eyeX: Float, eyeY: Float, eyeZ: Float,
        centerX: Float, centerY: Float, centerZ: Float,
        upX: Float, upY: Float, upZ: Float
    ) {
        var fx = centerX - eyeX
        var fy = centerY - eyeY
        var fz = centerZ - eyeZ
        val rlf = 1f / length(fx, fy, fz)
        fx *= rlf; fy *= rlf; fz *= rlf

        var sx = fy * upZ - fz * upY
        var sy = fz * upX - fx * upZ
        var sz = fx * upY - fy * upX
        val rls = 1f / length(sx, sy, sz)
        sx *= rls; sy *= rls; sz *= rls

        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx

        m[0] = sx;  m[4] = sy;  m[8] = sz;   m[12] = -(sx * eyeX + sy * eyeY + sz * eyeZ)
        m[1] = ux;  m[5] = uy;  m[9] = uz;   m[13] = -(ux * eyeX + uy * eyeY + uz * eyeZ)
        m[2] = -fx; m[6] = -fy; m[10] = -fz; m[14] = (fx * eyeX + fy * eyeY + fz * eyeZ)
        m[3] = 0f;  m[7] = 0f;  m[11] = 0f;  m[15] = 1f
    }

    // ---------------------------------------------------------------------
    // Matrix arithmetic
    // ---------------------------------------------------------------------

    /** result = lhs * rhs. result must not alias lhs or rhs. */
    fun multiplyMM(result: FloatArray, lhs: FloatArray, rhs: FloatArray) {
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += lhs[k * 4 + row] * rhs[col * 4 + k]
                }
                result[col * 4 + row] = sum
            }
        }
    }

    /** result(4) = m(4x4) * v(4). result must not alias v. */
    fun multiplyMV(result: FloatArray, m: FloatArray, v: FloatArray) {
        for (row in 0 until 4) {
            result[row] =
                m[row] * v[0] +
                m[4 + row] * v[1] +
                m[8 + row] * v[2] +
                m[12 + row] * v[3]
        }
    }

    /** In-place: m = m * translation(x,y,z). */
    fun translateM(m: FloatArray, x: Float, y: Float, z: Float) {
        for (i in 0 until 4) {
            m[12 + i] += m[i] * x + m[4 + i] * y + m[8 + i] * z
        }
    }

    /** In-place: m = m * scale(x,y,z). */
    fun scaleM(m: FloatArray, x: Float, y: Float, z: Float) {
        for (i in 0 until 4) {
            m[i] *= x
            m[4 + i] *= y
            m[8 + i] *= z
        }
    }

    /** Writes a rotation matrix about an arbitrary axis into rm (column-major). */
    fun setRotateM(rm: FloatArray, angleDeg: Float, ax: Float, ay: Float, az: Float) {
        val a = angleDeg * DEG2RAD
        val s = sin(a)
        val c = cos(a)
        var x = ax; var y = ay; var z = az
        val len = length(x, y, z)
        if (len != 1f && len != 0f) {
            val inv = 1f / len
            x *= inv; y *= inv; z *= inv
        }
        val nc = 1f - c
        val xy = x * y; val yz = y * z; val zx = z * x
        val xs = x * s; val ys = y * s; val zs = z * s

        rm[0] = x * x * nc + c
        rm[1] = xy * nc + zs
        rm[2] = zx * nc - ys
        rm[3] = 0f
        rm[4] = xy * nc - zs
        rm[5] = y * y * nc + c
        rm[6] = yz * nc + xs
        rm[7] = 0f
        rm[8] = zx * nc + ys
        rm[9] = yz * nc - xs
        rm[10] = z * z * nc + c
        rm[11] = 0f
        rm[12] = 0f; rm[13] = 0f; rm[14] = 0f; rm[15] = 1f
    }

    private val tmpRot = FloatArray(16)
    private val tmpMul = FloatArray(16)

    /** In-place: m = m * rotation(angle, axis). Not thread-safe; call from GL thread. */
    fun rotateM(m: FloatArray, angleDeg: Float, x: Float, y: Float, z: Float) {
        setRotateM(tmpRot, angleDeg, x, y, z)
        multiplyMM(tmpMul, m, tmpRot)
        System.arraycopy(tmpMul, 0, m, 0, 16)
    }

    /**
     * General 4x4 matrix inverse. Returns false (and leaves [inv] untouched)
     * for a singular matrix. Standard cofactor expansion.
     */
    fun invertM(inv: FloatArray, src: FloatArray): Boolean {
        val m = src
        val t = FloatArray(16)
        t[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] +
                m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        t[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] -
                m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        t[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] +
                m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        t[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] -
                m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        t[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] -
                m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        t[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] +
                m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        t[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] -
                m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        t[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] +
                m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        t[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] +
                m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        t[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] -
                m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        t[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] +
                m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        t[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] -
                m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        t[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] -
                m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        t[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] +
                m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        t[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] -
                m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        t[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] +
                m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]

        var det = m[0] * t[0] + m[1] * t[4] + m[2] * t[8] + m[3] * t[12]
        if (det == 0f) return false
        det = 1f / det
        for (i in 0 until 16) inv[i] = t[i] * det
        return true
    }

    // ---------------------------------------------------------------------
    // Vector helpers
    // ---------------------------------------------------------------------

    fun length(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    fun normalize(v: FloatArray) {
        val len = length(v[0], v[1], v[2])
        if (len > 1e-6f) {
            val inv = 1f / len
            v[0] *= inv; v[1] *= inv; v[2] *= inv
        }
    }

    fun cross(out: FloatArray, a: FloatArray, b: FloatArray) {
        out[0] = a[1] * b[2] - a[2] * b[1]
        out[1] = a[2] * b[0] - a[0] * b[2]
        out[2] = a[0] * b[1] - a[1] * b[0]
    }

    fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
