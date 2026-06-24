package com.nullevent.lakewallpaper

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Birds flying above the waterline on looping parametric paths.
 *
 * Per-instance render data is packed into [instanceBuffer] for
 * `glDrawArraysInstanced`.
 *
 * Instance layout (5 floats / bird): [ox, oy, oz, yaw, phase].
 */
class BirdSystem(val count: Int = 22) {

    companion object {
        const val INSTANCE_FLOATS = 5
    }

    private val radius = FloatArray(count)
    private val speed = FloatArray(count)
    private val offset = FloatArray(count)
    private val baseHeight = FloatArray(count)
    private val phase = FloatArray(count)
    private val centerX = FloatArray(count)
    private val centerZ = FloatArray(count)

    private val instanceData = FloatArray(count * INSTANCE_FLOATS)
    val instanceBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(count * INSTANCE_FLOATS * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private var seed = 0x1234567
    private fun rnd(): Float {
        seed = seed * 1664525 + 1013904223
        return ((seed ushr 8) and 0xFFFFFF) / 16777216f
    }

    init {
        for (i in 0 until count) {
            radius[i] = 25f + rnd() * 45f
            speed[i] = 0.12f + rnd() * 0.22f
            offset[i] = rnd() * 6.283f
            baseHeight[i] = 6f + rnd() * 18f       // all comfortably above y = 2
            phase[i] = rnd() * 6.283f
            centerX[i] = (rnd() - 0.5f) * 40f
            centerZ[i] = -60f - rnd() * 80f
        }
    }

    /** Updates positions for the given absolute time (seconds) and repacks. */
    fun update(time: Float) {
        for (i in 0 until count) {
            val t = time * speed[i] + offset[i]
            val x = centerX[i] + sin(t) * radius[i]
            val z = centerZ[i] + cos(t) * radius[i]
            val y = baseHeight[i] + sin(time * 2f + offset[i]) * 0.5f

            // Heading from path tangent: derivative of (sin t, cos t).
            val dx = cos(t) * radius[i]
            val dz = -sin(t) * radius[i]
            val yaw = atan2(dz, dx)

            val o = i * INSTANCE_FLOATS
            instanceData[o] = x
            instanceData[o + 1] = y
            instanceData[o + 2] = z
            instanceData[o + 3] = yaw
            instanceData[o + 4] = phase[i]
        }
        instanceBuffer.position(0)
        instanceBuffer.put(instanceData)
        instanceBuffer.position(0)
    }
}
