package com.nullevent.aquarium

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * CPU-side fish simulation: a simplified Boids flock that schools by species,
 * seeks food, and respects the underwater bounding volume. Produces per-type
 * instance buffers consumed by [LakeRenderer] via glDrawArraysInstanced.
 *
 * Per-instance layout (10 floats): pos.xyz, yaw, scale, color.rgb, phase, flash.
 */
class FishSystem(
    var varietyCount: Int,          // number of distinct species in the scene
    var countPerVariety: Int,       // fish per species
    var speedScale: Float,          // global swim-speed multiplier (settings)
    seed: Long = 1234L
) {
    companion object {
        const val INSTANCE_FLOATS = 10
        const val MAX_FOOD = 24

        // Steering weights (per spec).
        const val SEEK_W = 2.0f
        const val SEPARATION_W = 1.5f
        const val ALIGNMENT_W = 0.5f
        const val COHESION_W = 0.3f

        // Bounds of the underwater volume.
        const val MIN_X = -40f; const val MAX_X = 40f
        const val MIN_Y = -15f; const val MAX_Y = -0.5f
        const val MIN_Z = -50f; const val MAX_Z = -2f

        const val NEIGHBOR_R = 4.0f
        const val SEP_R = 1.6f
        const val FOOD_SENSE_R = 22f
        const val FOOD_RADIUS = 0.3f
    }

    private val rnd = Random(seed)

    /** Mutable per-fish state. */
    private class Fish(
        var type: Int,
        var x: Float, var y: Float, var z: Float,
        var vx: Float, var vy: Float, var vz: Float,
        var phase: Float, var scale: Float, var maxSpeed: Float
    ) {
        var flash = 0f   // eat-flash, decays to 0
        var yaw = 0f
    }

    class Food(
        var x: Float, var y: Float, var z: Float,
        val r: Float, val g: Float, val b: Float
    ) { var age = 0f }

    private val fish = ArrayList<Fish>()
    val food = ArrayList<Food>()

    // Which species (template indices) are active this run; chosen at build.
    var activeTypes: IntArray = IntArray(0)
        private set

    // Reusable instance buffers (sized to worst case to avoid per-frame GC).
    private var instanceBuffer: FloatBuffer = alloc(0)
    private val foodBuffer: FloatBuffer = alloc(MAX_FOOD)

    private var scatterTimer = 8f

    private fun alloc(maxInstances: Int): FloatBuffer =
        ByteBuffer.allocateDirect(maxOf(1, maxInstances) * INSTANCE_FLOATS * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** (Re)create the flock from the current settings. Call on the GL thread. */
    fun rebuild() {
        fish.clear()
        val vc = varietyCount.coerceIn(1, GeometryBuilders.FISH_TYPE_COUNT)
        // Pick `vc` distinct species templates.
        val pool = (0 until GeometryBuilders.FISH_TYPE_COUNT).shuffled(java.util.Random(rnd.nextLong()))
        activeTypes = pool.take(vc).toIntArray()

        for (ti in activeTypes.indices) {
            val type = activeTypes[ti]
            // Give each school a starting region so they read as a group.
            val cx = randRange(MIN_X + 6f, MAX_X - 6f)
            val cy = randRange(MIN_Y + 2f, MAX_Y - 2f)
            val cz = randRange(MIN_Z + 6f, MAX_Z - 6f)
            val baseScale = 0.8f + rnd.nextFloat() * 0.6f
            val maxSpeed = 2.2f + rnd.nextFloat() * 1.6f
            for (k in 0 until countPerVariety) {
                val f = Fish(
                    type,
                    (cx + randRange(-3f, 3f)).coerceIn(MIN_X, MAX_X),
                    (cy + randRange(-2f, 2f)).coerceIn(MIN_Y, MAX_Y),
                    (cz + randRange(-3f, 3f)).coerceIn(MIN_Z, MAX_Z),
                    randRange(-1f, 1f), randRange(-0.3f, 0.3f), randRange(-1f, 1f),
                    rnd.nextFloat() * 6.28f,
                    baseScale * (0.85f + rnd.nextFloat() * 0.3f),
                    maxSpeed
                )
                fish.add(f)
            }
        }
        instanceBuffer = alloc(fish.size)
    }

    private fun randRange(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    val fishCount: Int get() = fish.size

    // -----------------------------------------------------------------
    // Food
    // -----------------------------------------------------------------

    fun spawnFood(x: Float, y: Float, z: Float) {
        if (food.size >= MAX_FOOD) food.removeAt(0)
        // Bright, water-visible colors.
        val palette = arrayOf(
            floatArrayOf(1.0f, 0.55f, 0.1f), floatArrayOf(1.0f, 0.85f, 0.2f),
            floatArrayOf(0.4f, 1.0f, 0.5f), floatArrayOf(1.0f, 0.3f, 0.5f),
            floatArrayOf(0.6f, 0.8f, 1.0f)
        )
        val c = palette[rnd.nextInt(palette.size)]
        food.add(Food(
            x.coerceIn(MIN_X, MAX_X),
            y.coerceIn(MIN_Y, MAX_Y),
            z.coerceIn(MIN_Z, MAX_Z),
            c[0], c[1], c[2]
        ))
    }

    /** Spawn food along a world ray at a random depth z in [-30, -5]. */
    fun spawnFoodOnRay(originX: Float, originY: Float, originZ: Float,
                       dirX: Float, dirY: Float, dirZ: Float) {
        val targetZ = -5f - rnd.nextFloat() * 25f   // randomized per call: [-30,-5]
        if (kotlin.math.abs(dirZ) < 1e-4f) { spawnFood(originX, originY, targetZ); return }
        val t = (targetZ - originZ) / dirZ
        if (t <= 0f) { spawnFood(originX, originY, targetZ); return }
        spawnFood(originX + dirX * t, originY + dirY * t, targetZ)
    }

    // -----------------------------------------------------------------
    // Simulation step
    // -----------------------------------------------------------------

    fun update(dt: Float) {
        val step = dt.coerceIn(0f, 0.05f)

        // Age out food.
        val foodIt = food.iterator()
        while (foodIt.hasNext()) {
            val fd = foodIt.next()
            fd.age += step
            // Food slowly sinks.
            fd.y = (fd.y - 0.15f * step).coerceAtLeast(MIN_Y)
            if (fd.age > 40f) foodIt.remove()
        }

        // Occasionally scatter a random school (disruption), then they regroup.
        scatterTimer -= step
        var scatterType = -1
        if (scatterTimer <= 0f) {
            scatterTimer = 6f + rnd.nextFloat() * 8f
            if (activeTypes.isNotEmpty()) scatterType = activeTypes[rnd.nextInt(activeTypes.size)]
        }

        val n = fish.size
        for (i in 0 until n) {
            val f = fish[i]
            var sepX = 0f; var sepY = 0f; var sepZ = 0f
            var aliX = 0f; var aliY = 0f; var aliZ = 0f
            var cohX = 0f; var cohY = 0f; var cohZ = 0f
            var cohN = 0; var aliN = 0
            var avoidX = 0f; var avoidY = 0f; var avoidZ = 0f  // cross-school avoidance

            for (j in 0 until n) {
                if (j == i) continue
                val o = fish[j]
                val dx = f.x - o.x; val dy = f.y - o.y; val dz = f.z - o.z
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 > NEIGHBOR_R * NEIGHBOR_R) continue
                val d = sqrt(d2).coerceAtLeast(1e-3f)
                if (o.type == f.type) {
                    if (d < SEP_R) { sepX += dx / d; sepY += dy / d; sepZ += dz / d }
                    aliX += o.vx; aliY += o.vy; aliZ += o.vz; aliN++
                    cohX += o.x; cohY += o.y; cohZ += o.z; cohN++
                } else {
                    // Different species nudge each other apart — schools split & rejoin.
                    if (d < SEP_R * 1.4f) { avoidX += dx / d; avoidY += dy / d; avoidZ += dz / d }
                }
            }

            var ax = 0f; var ay = 0f; var az = 0f
            ax += sepX * SEPARATION_W; ay += sepY * SEPARATION_W; az += sepZ * SEPARATION_W
            ax += avoidX * SEPARATION_W; ay += avoidY * SEPARATION_W; az += avoidZ * SEPARATION_W
            if (aliN > 0) {
                ax += (aliX / aliN - f.vx) * ALIGNMENT_W
                ay += (aliY / aliN - f.vy) * ALIGNMENT_W
                az += (aliZ / aliN - f.vz) * ALIGNMENT_W
            }
            if (cohN > 0) {
                ax += (cohX / cohN - f.x) * COHESION_W * 0.2f
                ay += (cohY / cohN - f.y) * COHESION_W * 0.2f
                az += (cohZ / cohN - f.z) * COHESION_W * 0.2f
            }

            // Seek nearest food (highest priority).
            var bestD2 = FOOD_SENSE_R * FOOD_SENSE_R
            var target: Food? = null
            for (fd in food) {
                val dx = fd.x - f.x; val dy = fd.y - f.y; val dz = fd.z - f.z
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 < bestD2) { bestD2 = d2; target = fd }
            }
            if (target != null) {
                val dx = target.x - f.x; val dy = target.y - f.y; val dz = target.z - f.z
                val d = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-3f)
                ax += dx / d * SEEK_W; ay += dy / d * SEEK_W; az += dz / d * SEEK_W
            }

            // Scatter impulse to the chosen school this tick.
            if (scatterType == f.type) {
                ax += randRange(-1f, 1f) * 4f
                ay += randRange(-0.5f, 0.5f) * 4f
                az += randRange(-1f, 1f) * 4f
            }

            // Soft steering away from bounds.
            ax += boundForce(f.x, MIN_X, MAX_X)
            ay += boundForce(f.y, MIN_Y, MAX_Y)
            az += boundForce(f.z, MIN_Z, MAX_Z)

            // Integrate velocity, clamp to species speed.
            f.vx += ax * step; f.vy += ay * step; f.vz += az * step
            val maxS = f.maxSpeed * speedScale
            val sp = sqrt(f.vx * f.vx + f.vy * f.vy + f.vz * f.vz)
            if (sp > maxS && sp > 1e-4f) {
                val s = maxS / sp; f.vx *= s; f.vy *= s; f.vz *= s
            } else if (sp < maxS * 0.3f && sp > 1e-4f) {
                val s = (maxS * 0.3f) / sp; f.vx *= s; f.vy *= s; f.vz *= s
            }

            // Integrate position, hard-clamp to bounds.
            f.x = (f.x + f.vx * step).coerceIn(MIN_X, MAX_X)
            f.y = (f.y + f.vy * step).coerceIn(MIN_Y, MAX_Y)
            f.z = (f.z + f.vz * step).coerceIn(MIN_Z, MAX_Z)

            f.yaw = atan2(f.vx, f.vz)
            f.phase += step * (2f + sp)
            if (f.flash > 0f) f.flash = (f.flash - step * 2f).coerceAtLeast(0f)
        }

        // Eating: first fish into a food radius consumes it.
        val it = food.iterator()
        while (it.hasNext()) {
            val fd = it.next()
            for (f in fish) {
                val dx = fd.x - f.x; val dy = fd.y - f.y; val dz = fd.z - f.z
                if (dx * dx + dy * dy + dz * dz < FOOD_RADIUS * FOOD_RADIUS + f.scale * f.scale) {
                    f.flash = 1f
                    it.remove()
                    break
                }
            }
        }
    }

    private fun boundForce(v: Float, lo: Float, hi: Float): Float {
        val margin = 4f
        return when {
            v < lo + margin -> (lo + margin - v) * 1.5f
            v > hi - margin -> (hi - margin - v) * 1.5f
            else -> 0f
        }
    }

    // -----------------------------------------------------------------
    // Instance data for rendering
    // -----------------------------------------------------------------

    /** Fills the shared instance buffer with all fish of [type]; returns count. */
    fun fillInstancesForType(type: Int): Int {
        instanceBuffer.position(0)
        var count = 0
        for (f in fish) {
            if (f.type != type) continue
            instanceBuffer.put(f.x); instanceBuffer.put(f.y); instanceBuffer.put(f.z)
            instanceBuffer.put(f.yaw); instanceBuffer.put(f.scale)
            // Slight per-fish hue jitter via phase-derived tint, kept near white.
            val tint = 0.85f + 0.15f * kotlin.math.sin(f.phase)
            instanceBuffer.put(tint); instanceBuffer.put(1f); instanceBuffer.put(tint)
            instanceBuffer.put(f.phase); instanceBuffer.put(f.flash)
            count++
        }
        instanceBuffer.position(0)
        return count
    }

    fun instanceData(): FloatBuffer = instanceBuffer

    /** Fills the food instance buffer; returns count. */
    fun fillFoodInstances(): Int {
        foodBuffer.position(0)
        var count = 0
        for (fd in food) {
            if (count >= MAX_FOOD) break
            foodBuffer.put(fd.x); foodBuffer.put(fd.y); foodBuffer.put(fd.z)
            foodBuffer.put(0f); foodBuffer.put(FOOD_RADIUS)
            foodBuffer.put(fd.r); foodBuffer.put(fd.g); foodBuffer.put(fd.b)
            foodBuffer.put(0f); foodBuffer.put(0f)
            count++
        }
        foodBuffer.position(0)
        return count
    }

    fun foodData(): FloatBuffer = foodBuffer
}
