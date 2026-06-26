package com.nullevent.aquarium

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Owns the static placement and animation parameters for roots/trees and the
 * instanced seaweed field. The actual sway is computed in [ShaderUtils.PLANT_VS]
 * using a per-instance phase; this class supplies the instance data and the
 * tree transforms, and exposes the sway strength driven by user settings.
 *
 * Plant instance layout (6 floats): pos.xyz, yaw, scale, phase.
 */
class PlantSystem(
    var seaweedCount: Int = 90,
    var treeCount: Int = 2,
    var rootsScale: Float = 1.0f,   // settings: "roots area / size"
    var swayStrength: Float = 1.0f, // settings: how strongly plants sway
    seed: Long = 99L
) {
    companion object { const val PLANT_FLOATS = 6 }

    private val rnd = Random(seed)

    /** Each tree: x, z, yaw(rad), scale. y is anchored at the waterline. */
    class TreeXform(val x: Float, val z: Float, val yaw: Float, val scale: Float)

    val trees = ArrayList<TreeXform>()
    private var seaweedBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var seaweedInstances = 0

    fun rebuild() {
        trees.clear()
        val tc = treeCount.coerceIn(0, 4)
        for (i in 0 until tc) {
            // Spread trees across the back/sides so roots frame the scene.
            val x = (if (tc == 1) 0f else -18f + 36f * (i.toFloat() / (tc - 1).coerceAtLeast(1)))
            val z = -8f - rnd.nextFloat() * 14f
            trees.add(TreeXform(x, z, rnd.nextFloat() * 6.28f, (0.9f + rnd.nextFloat() * 0.5f) * rootsScale))
        }

        val count = seaweedCount.coerceIn(0, 400)
        val buf = ByteBuffer.allocateDirect(maxOf(1, count) * PLANT_FLOATS * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until count) {
            val x = -40f + rnd.nextFloat() * 80f
            val z = -50f + rnd.nextFloat() * 46f
            // Anchor roughly on the bed surface; matches buildAquariumBed depth.
            val y = -13.2f + sceneNoise(x, z)
            buf.put(x); buf.put(y); buf.put(z)
            buf.put(rnd.nextFloat() * 6.28f)
            buf.put(0.6f + rnd.nextFloat() * 1.1f)
            buf.put(rnd.nextFloat() * 6.28f)
        }
        buf.position(0)
        seaweedBuffer = buf
        seaweedInstances = count
    }

    private fun sceneNoise(x: Float, z: Float): Float =
        sin(x * 0.25f) * 0.9f + cos(z * 0.25f) * 0.9f

    fun seaweedData(): FloatBuffer = seaweedBuffer
    fun seaweedCountInstances(): Int = seaweedInstances

    /** Animation is shader-driven; nothing per-frame needed on the CPU, but the
     *  hook is here so callers can advance any future CPU-side plant state. */
    fun update(dt: Float) { /* no-op: sway handled in the vertex shader */ }
}
