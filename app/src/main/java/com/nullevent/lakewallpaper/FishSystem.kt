package com.nullevent.lakewallpaper

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * CPU-side Boids simulation for the underwater fish school plus food state.
 *
 * Fish state lives in two flat [FloatArray]s (positions, velocities) and is
 * stepped every frame. Per-instance render data (offset, eat-flash scale,
 * color) is packed into [instanceBuffer] for `glDrawArraysInstanced`.
 *
 * Instance layout (7 floats / fish): [ox, oy, oz, scale, cr, cg, cb].
 */
class FishSystem(val count: Int = 80) {

    companion object {
        const val INSTANCE_FLOATS = 7

        // Boids weights (per the scene spec).
        private const val SEEK_W = 2.0f
        private const val SEPARATION_W = 1.5f
        private const val ALIGNMENT_W = 0.5f
        private const val COHESION_W = 0.3f

        // Underwater bounds.
        private const val MIN_X = -40f; private const val MAX_X = 40f
        private const val MIN_Y = -15f; private const val MAX_Y = -0.5f
        private const val MIN_Z = -50f; private const val MAX_Z = -2f

        private const val MAX_SPEED = 6.0f
        private const val MIN_SPEED = 1.2f
        private const val NEIGHBOR_RADIUS = 4.0f
        private const val SEPARATION_RADIUS = 1.6f
        private const val FOOD_EAT_RADIUS = 0.3f
        private const val FOOD_SENSE_RADIUS = 30f
    }

    val positions = FloatArray(count * 3)
    val velocities = FloatArray(count * 3)
    private val colors = FloatArray(count * 3)
    private val eatFlash = FloatArray(count)   // decays 1 -> 0, scales the fish briefly

    private val instanceData = FloatArray(count * INSTANCE_FLOATS)
    val instanceBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(count * INSTANCE_FLOATS * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    // Food items: parallel arrays of active food positions.
    private val foodX = ArrayList<Float>()
    private val foodY = ArrayList<Float>()
    private val foodZ = ArrayList<Float>()

    // Simple deterministic RNG (avoids Math.random churn on the GL thread).
    private var seed = 0x9E3779B9.toInt()
    private fun rnd(): Float {
        seed = seed * 1664525 + 1013904223
        return ((seed ushr 8) and 0xFFFFFF) / 16777216f
    }

    init {
        for (i in 0 until count) {
            positions[i * 3] = (rnd() - 0.5f) * 60f
            positions[i * 3 + 1] = MIN_Y + rnd() * (MAX_Y - MIN_Y)
            positions[i * 3 + 2] = MIN_Z + rnd() * (MAX_Z - MIN_Z)
            velocities[i * 3] = (rnd() - 0.5f) * 2f
            velocities[i * 3 + 1] = (rnd() - 0.5f) * 1f
            velocities[i * 3 + 2] = (rnd() - 0.5f) * 2f
            // Subtle per-fish color variation around a silvery-blue.
            val t = rnd()
            colors[i * 3] = 0.6f + t * 0.3f
            colors[i * 3 + 1] = 0.7f + t * 0.2f
            colors[i * 3 + 2] = 0.8f + (1f - t) * 0.2f
        }
    }

    /** Spawns a food pellet at a world point (clamped into the underwater volume). */
    fun spawnFood(x: Float, y: Float, z: Float) {
        foodX.add(x.coerceIn(MIN_X, MAX_X))
        foodY.add(y.coerceIn(MIN_Y, MAX_Y))
        foodZ.add(z.coerceIn(MIN_Z, MAX_Z))
    }

    /** Advances the simulation by dt seconds and repacks the instance buffer. */
    fun update(dt: Float) {
        val step = dt.coerceIn(0f, 0.05f)  // clamp to avoid blow-ups after pauses

        for (i in 0 until count) {
            val ix = i * 3
            val px = positions[ix]; val py = positions[ix + 1]; val pz = positions[ix + 2]

            var sepX = 0f; var sepY = 0f; var sepZ = 0f
            var aliX = 0f; var aliY = 0f; var aliZ = 0f
            var cohX = 0f; var cohY = 0f; var cohZ = 0f
            var neighbors = 0

            for (j in 0 until count) {
                if (j == i) continue
                val jx = j * 3
                val dx = px - positions[jx]
                val dy = py - positions[jx + 1]
                val dz = pz - positions[jx + 2]
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 < NEIGHBOR_RADIUS * NEIGHBOR_RADIUS && d2 > 1e-4f) {
                    neighbors++
                    aliX += velocities[jx]; aliY += velocities[jx + 1]; aliZ += velocities[jx + 2]
                    cohX += positions[jx]; cohY += positions[jx + 1]; cohZ += positions[jx + 2]
                    if (d2 < SEPARATION_RADIUS * SEPARATION_RADIUS) {
                        val inv = 1f / sqrt(d2)
                        sepX += dx * inv; sepY += dy * inv; sepZ += dz * inv
                    }
                }
            }

            var ax = 0f; var ay = 0f; var az = 0f

            if (neighbors > 0) {
                val invN = 1f / neighbors
                // Alignment: steer toward average neighbor velocity.
                ax += (aliX * invN) * ALIGNMENT_W
                ay += (aliY * invN) * ALIGNMENT_W
                az += (aliZ * invN) * ALIGNMENT_W
                // Cohesion: steer toward neighbor center of mass.
                ax += ((cohX * invN) - px) * COHESION_W * 0.1f
                ay += ((cohY * invN) - py) * COHESION_W * 0.1f
                az += ((cohZ * invN) - pz) * COHESION_W * 0.1f
            }
            // Separation.
            ax += sepX * SEPARATION_W
            ay += sepY * SEPARATION_W
            az += sepZ * SEPARATION_W

            // Seek nearest food (prioritized via highest weight).
            val nearest = nearestFood(px, py, pz)
            if (nearest >= 0) {
                val fdx = foodX[nearest] - px
                val fdy = foodY[nearest] - py
                val fdz = foodZ[nearest] - pz
                val fd = sqrt(fdx * fdx + fdy * fdy + fdz * fdz)
                if (fd > 1e-4f) {
                    val inv = 1f / fd
                    ax += fdx * inv * SEEK_W
                    ay += fdy * inv * SEEK_W
                    az += fdz * inv * SEEK_W
                }
                // Eat check.
                if (fd < FOOD_EAT_RADIUS) {
                    eatFlash[i] = 1.0f
                    removeFood(nearest)
                }
            }

            // Soft boundary steering keeps fish in the volume.
            if (px < MIN_X + 3f) ax += (MIN_X + 3f - px) * 0.6f
            if (px > MAX_X - 3f) ax -= (px - (MAX_X - 3f)) * 0.6f
            if (py < MIN_Y + 2f) ay += (MIN_Y + 2f - py) * 0.8f
            if (py > MAX_Y - 1f) ay -= (py - (MAX_Y - 1f)) * 0.8f
            if (pz < MIN_Z + 3f) az += (MIN_Z + 3f - pz) * 0.6f
            if (pz > MAX_Z - 3f) az -= (pz - (MAX_Z - 3f)) * 0.6f

            // Integrate velocity.
            var vx = velocities[ix] + ax * step
            var vy = velocities[ix + 1] + ay * step
            var vz = velocities[ix + 2] + az * step

            // Clamp speed.
            val sp = sqrt(vx * vx + vy * vy + vz * vz)
            if (sp > MAX_SPEED) {
                val s = MAX_SPEED / sp; vx *= s; vy *= s; vz *= s
            } else if (sp < MIN_SPEED && sp > 1e-4f) {
                val s = MIN_SPEED / sp; vx *= s; vy *= s; vz *= s
            }

            velocities[ix] = vx; velocities[ix + 1] = vy; velocities[ix + 2] = vz

            // Integrate position with hard clamp to bounds.
            positions[ix] = (px + vx * step).coerceIn(MIN_X, MAX_X)
            positions[ix + 1] = (py + vy * step).coerceIn(MIN_Y, MAX_Y)
            positions[ix + 2] = (pz + vz * step).coerceIn(MIN_Z, MAX_Z)

            if (eatFlash[i] > 0f) {
                eatFlash[i] = (eatFlash[i] - step * 2.5f).coerceAtLeast(0f)
            }
        }

        packInstances()
    }

    private fun nearestFood(px: Float, py: Float, pz: Float): Int {
        var best = -1
        var bestD = FOOD_SENSE_RADIUS * FOOD_SENSE_RADIUS
        for (k in foodX.indices) {
            val dx = foodX[k] - px; val dy = foodY[k] - py; val dz = foodZ[k] - pz
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 < bestD) { bestD = d2; best = k }
        }
        return best
    }

    private fun removeFood(index: Int) {
        // Swap-remove to keep it O(1).
        val last = foodX.size - 1
        foodX[index] = foodX[last]; foodY[index] = foodY[last]; foodZ[index] = foodZ[last]
        foodX.removeAt(last); foodY.removeAt(last); foodZ.removeAt(last)
    }

    private fun packInstances() {
        for (i in 0 until count) {
            val o = i * INSTANCE_FLOATS
            instanceData[o] = positions[i * 3]
            instanceData[o + 1] = positions[i * 3 + 1]
            instanceData[o + 2] = positions[i * 3 + 2]
            // Brief scale flash when a fish eats.
            instanceData[o + 3] = 1.0f + eatFlash[i] * 0.8f
            // Tint brighter while flashing.
            val flash = eatFlash[i]
            instanceData[o + 4] = colors[i * 3] + flash * 0.4f
            instanceData[o + 5] = colors[i * 3 + 1] + flash * 0.4f
            instanceData[o + 6] = colors[i * 3 + 2] + flash * 0.2f
        }
        instanceBuffer.position(0)
        instanceBuffer.put(instanceData)
        instanceBuffer.position(0)
    }
}
