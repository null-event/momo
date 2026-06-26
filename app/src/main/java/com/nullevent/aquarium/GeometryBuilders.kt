package com.nullevent.aquarium

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural mesh generation. Every function returns a [MeshData] holding an
 * interleaved [FloatBuffer] (position.xyz, normal.xyz, color.rgb = 9 floats per
 * vertex) plus an [IntBuffer] of triangle indices. No external model assets.
 */
object GeometryBuilders {

    const val FLOATS_PER_VERTEX = 9
    const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

    /** Result of a build: a vertex buffer, an index buffer, and the index count. */
    class MeshData(
        val vertices: FloatBuffer,
        val indices: IntBuffer,
        val indexCount: Int
    )

    /** Number of distinct fish templates available. The renderer picks 4..6. */
    const val FISH_TYPE_COUNT = 6

    val FISH_TYPE_NAMES = arrayOf(
        "Minnow", "Trout", "Bass", "Catfish", "Barracuda", "Perch"
    )

    // =================================================================
    // Mesh accumulation helper (smooth normals via face accumulation).
    // =================================================================

    private class Builder {
        val px = ArrayList<Float>(); val py = ArrayList<Float>(); val pz = ArrayList<Float>()
        val nx = ArrayList<Float>(); val ny = ArrayList<Float>(); val nz = ArrayList<Float>()
        val cr = ArrayList<Float>(); val cg = ArrayList<Float>(); val cb = ArrayList<Float>()
        val idx = ArrayList<Int>()

        fun vertex(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float): Int {
            px.add(x); py.add(y); pz.add(z)
            nx.add(0f); ny.add(0f); nz.add(0f)
            cr.add(r); cg.add(g); cb.add(b)
            return px.size - 1
        }

        fun tri(a: Int, b: Int, c: Int) {
            // Accumulate the face normal into each vertex for smooth shading.
            val ux = px[b] - px[a]; val uy = py[b] - py[a]; val uz = pz[b] - pz[a]
            val vx = px[c] - px[a]; val vy = py[c] - py[a]; val vz = pz[c] - pz[a]
            val fx = uy * vz - uz * vy
            val fy = uz * vx - ux * vz
            val fz = ux * vy - uy * vx
            for (i in intArrayOf(a, b, c)) {
                nx[i] = nx[i] + fx; ny[i] = ny[i] + fy; nz[i] = nz[i] + fz
            }
            idx.add(a); idx.add(b); idx.add(c)
        }

        fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }

        fun build(): MeshData {
            val n = px.size
            val vbuf = ByteBuffer.allocateDirect(n * STRIDE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until n) {
                var lx = nx[i]; var ly = ny[i]; var lz = nz[i]
                val len = MathUtils.length(lx, ly, lz)
                if (len > 1e-6f) { lx /= len; ly /= len; lz /= len } else { ly = 1f }
                vbuf.put(px[i]); vbuf.put(py[i]); vbuf.put(pz[i])
                vbuf.put(lx); vbuf.put(ly); vbuf.put(lz)
                vbuf.put(cr[i]); vbuf.put(cg[i]); vbuf.put(cb[i])
            }
            vbuf.position(0)
            val ibuf = ByteBuffer.allocateDirect(idx.size * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer()
            for (v in idx) ibuf.put(v)
            ibuf.position(0)
            return MeshData(vbuf, ibuf, idx.size)
        }
    }

    // Cheap deterministic value noise from sums of sines (no texture needed).
    private fun noise2(x: Float, z: Float): Float {
        return (sin(x * 0.7f + z * 0.3f) * 0.5f +
                sin(x * 0.23f - z * 0.51f) * 0.3f +
                sin(x * 1.31f + z * 0.91f) * 0.2f)
    }

    // =================================================================
    // Water plane: subdivided grid spanning the view (>= 64 x 16).
    // =================================================================

    fun buildWaterPlane(
        width: Float = 120f, depthNear: Float = -2f, depthFar: Float = -60f,
        segX: Int = 96, segZ: Int = 24
    ): MeshData {
        val b = Builder()
        val color = floatArrayOf(0.2f, 0.4f, 0.5f)
        val grid = Array(segZ + 1) { IntArray(segX + 1) }
        for (zi in 0..segZ) {
            val tz = zi.toFloat() / segZ
            val z = depthNear + (depthFar - depthNear) * tz
            for (xi in 0..segX) {
                val tx = xi.toFloat() / segX
                val x = -width / 2f + width * tx
                grid[zi][xi] = b.vertex(x, 0f, z, color[0], color[1], color[2])
            }
        }
        for (zi in 0 until segZ) {
            for (xi in 0 until segX) {
                b.quad(grid[zi][xi], grid[zi][xi + 1], grid[zi + 1][xi + 1], grid[zi + 1][xi])
            }
        }
        return b.build()
    }

    // =================================================================
    // Aquarium bed: uneven dirt/rock floor with vertex colors.
    // =================================================================

    fun buildAquariumBed(
        minX: Float = -45f, maxX: Float = 45f,
        minZ: Float = -55f, maxZ: Float = -1f,
        segX: Int = 40, segZ: Int = 36,
        rnd: Random = Random(7)
    ): MeshData {
        val b = Builder()
        val grid = Array(segZ + 1) { IntArray(segX + 1) }
        for (zi in 0..segZ) {
            val tz = zi.toFloat() / segZ
            val z = minZ + (maxZ - minZ) * tz
            for (xi in 0..segX) {
                val tx = xi.toFloat() / segX
                val x = minX + (maxX - minX) * tx
                // Base bed depth dips lower toward the center/back.
                var y = -13f + noise2(x * 0.25f, z * 0.25f) * 1.8f
                y += noise2(x * 0.9f + 10f, z * 0.8f) * 0.6f
                // Rocky lumps.
                val rock = noise2(x * 0.5f + 31f, z * 0.5f - 12f)
                val isRock = rock > 0.55f
                if (isRock) y += (rock - 0.55f) * 6f
                // Dirt (brown) blended with rock (gray) and a touch of moss.
                val dirt = floatArrayOf(0.30f, 0.22f, 0.12f)
                val stone = floatArrayOf(0.34f, 0.34f, 0.37f)
                val t = if (isRock) 0.8f else (0.2f + 0.2f * noise2(x, z))
                val moss = max(0f, noise2(x * 0.3f, z * 0.4f + 5f)) * 0.15f
                val r = dirt[0] * (1 - t) + stone[0] * t
                val g = dirt[1] * (1 - t) + stone[1] * t + moss
                val bl = dirt[2] * (1 - t) + stone[2] * t
                grid[zi][xi] = b.vertex(x, y, z, r, g, bl)
            }
        }
        for (zi in 0 until segZ) {
            for (xi in 0 until segX) {
                b.quad(grid[zi][xi], grid[zi + 1][xi], grid[zi + 1][xi + 1], grid[zi][xi + 1])
            }
        }
        return b.build()
    }

    // =================================================================
    // Tree with exposed roots. Trunk rises above y = 0; roots splay below
    // with gaps wide enough for fish to swim through.
    // =================================================================

    fun buildTreeWithRoots(rootCount: Int = 7, rnd: Random = Random(42)): MeshData {
        val b = Builder()
        val barkHi = floatArrayOf(0.34f, 0.24f, 0.15f)
        val barkLo = floatArrayOf(0.20f, 0.14f, 0.09f)

        // Trunk: tapered tube from y=-1 up to y=4 (mostly silhouette above water).
        run {
            val pts = ArrayList<FloatArray>()
            val radii = ArrayList<Float>()
            val steps = 6
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val y = -1f + t * 5f
                val wobble = sin(t * 3f) * 0.15f
                pts.add(floatArrayOf(wobble, y, 0f))
                radii.add(0.9f * (1f - t * 0.6f))
            }
            addTube(b, pts, radii, barkLo, barkHi, segments = 7)
        }

        // Roots: each starts near the trunk base (y≈-0.5) and curves out & down.
        for (r in 0 until rootCount) {
            val ang = (r.toFloat() / rootCount) * (2f * PI.toFloat()) + rnd.nextFloat() * 0.4f
            val reach = 3.5f + rnd.nextFloat() * 3.0f
            val drop = 4f + rnd.nextFloat() * 3f
            val pts = ArrayList<FloatArray>()
            val radii = ArrayList<Float>()
            val steps = 7
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val rr = reach * t
                val curve = sin(t * 2.2f) * 0.8f
                val x = cos(ang) * rr + cos(ang + 1.2f) * curve
                val z = sin(ang) * rr + sin(ang + 1.2f) * curve
                val y = -0.3f - drop * (t * t)
                pts.add(floatArrayOf(x, y, z))
                radii.add(0.35f * (1f - t * 0.7f) + 0.04f)
            }
            addTube(b, pts, radii, barkLo, barkHi, segments = 6)
        }
        return b.build()
    }

    /** Builds a tapered tube of ring cross-sections following a polyline. */
    private fun addTube(
        b: Builder, pts: List<FloatArray>, radii: List<Float>,
        colLo: FloatArray, colHi: FloatArray, segments: Int
    ) {
        val rings = ArrayList<IntArray>()
        val up = floatArrayOf(0f, 1f, 0f)
        for (i in pts.indices) {
            // Tangent along the path.
            val a = pts[max(0, i - 1)]
            val c = pts[minOf(pts.size - 1, i + 1)]
            var tx = c[0] - a[0]; var ty = c[1] - a[1]; var tz = c[2] - a[2]
            val tl = MathUtils.length(tx, ty, tz).coerceAtLeast(1e-4f)
            tx /= tl; ty /= tl; tz /= tl
            // Build an orthonormal frame around the tangent.
            var nxx = up[1] * tz - up[2] * ty
            var nxy = up[2] * tx - up[0] * tz
            var nxz = up[0] * ty - up[1] * tx
            var nl = MathUtils.length(nxx, nxy, nxz)
            if (nl < 1e-3f) { nxx = 1f; nxy = 0f; nxz = 0f; nl = 1f }
            nxx /= nl; nxy /= nl; nxz /= nl
            val bxx = ty * nxz - tz * nxy
            val bxy = tz * nxx - tx * nxz
            val bxz = tx * nxy - ty * nxx
            val tFrac = i.toFloat() / (pts.size - 1)
            val cr = colLo[0] * (1 - tFrac) + colHi[0] * tFrac
            val cg = colLo[1] * (1 - tFrac) + colHi[1] * tFrac
            val cb = colLo[2] * (1 - tFrac) + colHi[2] * tFrac
            val ring = IntArray(segments)
            for (s in 0 until segments) {
                val a2 = (s.toFloat() / segments) * 2f * PI.toFloat()
                val ca = cos(a2); val sa = sin(a2)
                val rad = radii[i]
                val ox = (nxx * ca + bxx * sa) * rad
                val oy = (nxy * ca + bxy * sa) * rad
                val oz = (nxz * ca + bxz * sa) * rad
                ring[s] = b.vertex(pts[i][0] + ox, pts[i][1] + oy, pts[i][2] + oz, cr, cg, cb)
            }
            rings.add(ring)
        }
        for (i in 0 until rings.size - 1) {
            val r0 = rings[i]; val r1 = rings[i + 1]
            for (s in 0 until segments) {
                val s2 = (s + 1) % segments
                b.quad(r0[s], r1[s], r1[s2], r0[s2])
            }
        }
    }

    // =================================================================
    // Seaweed / low-poly plant blade (instanced). Points up from y=0 base.
    // =================================================================

    fun buildSeaweed(blades: Int = 5, height: Float = 2.4f, rnd: Random = Random(3)): MeshData {
        val b = Builder()
        val baseCol = floatArrayOf(0.10f, 0.40f, 0.18f)
        val tipCol = floatArrayOf(0.25f, 0.62f, 0.30f)
        for (bl in 0 until blades) {
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            val offx = cos(ang) * (0.1f + rnd.nextFloat() * 0.3f)
            val offz = sin(ang) * (0.1f + rnd.nextFloat() * 0.3f)
            val w = 0.12f + rnd.nextFloat() * 0.08f
            val h = height * (0.6f + rnd.nextFloat() * 0.6f)
            val segs = 5
            var prevL = -1; var prevR = -1
            for (i in 0..segs) {
                val t = i.toFloat() / segs
                val y = t * h
                val cur = w * (1f - t * 0.7f)
                val cr = baseCol[0] * (1 - t) + tipCol[0] * t
                val cg = baseCol[1] * (1 - t) + tipCol[1] * t
                val cb = baseCol[2] * (1 - t) + tipCol[2] * t
                val bend = sin(t * 2f) * 0.2f
                val l = b.vertex(offx - cur + bend, y, offz, cr, cg, cb)
                val r = b.vertex(offx + cur + bend, y, offz, cr, cg, cb)
                if (i > 0) b.quad(prevL, prevR, r, l)
                prevL = l; prevR = r
            }
        }
        return b.build()
    }

    // =================================================================
    // Tiny icosphere-ish blob for food particles.
    // =================================================================

    fun buildFoodSphere(radius: Float = 1f): MeshData {
        val b = Builder()
        val rings = 5; val sectors = 6
        val grid = Array(rings + 1) { IntArray(sectors + 1) }
        for (ri in 0..rings) {
            val phi = PI.toFloat() * ri / rings
            for (si in 0..sectors) {
                val theta = 2f * PI.toFloat() * si / sectors
                val x = sin(phi) * cos(theta)
                val y = cos(phi)
                val z = sin(phi) * sin(theta)
                grid[ri][si] = b.vertex(x * radius, y * radius, z * radius, 1f, 1f, 1f)
            }
        }
        for (ri in 0 until rings) for (si in 0 until sectors) {
            b.quad(grid[ri][si], grid[ri + 1][si], grid[ri + 1][si + 1], grid[ri][si + 1])
        }
        return b.build()
    }

    // =================================================================
    // Fish. One low-poly mesh per type, body pointing along +Z.
    // =================================================================

    private class FishProfile(
        val length: Float, val maxHeight: Float, val maxWidth: Float,
        val tailHeight: Float, val noseSharp: Float,
        val belly: FloatArray, val back: FloatArray
    )

    private fun fishProfile(type: Int): FishProfile = when (type % FISH_TYPE_COUNT) {
        0 -> FishProfile(0.9f, 0.16f, 0.10f, 0.22f, 0.7f, // Minnow: small, slim
            floatArrayOf(0.80f, 0.82f, 0.85f), floatArrayOf(0.45f, 0.55f, 0.62f))
        1 -> FishProfile(1.4f, 0.30f, 0.16f, 0.34f, 0.5f, // Trout
            floatArrayOf(0.85f, 0.80f, 0.70f), floatArrayOf(0.40f, 0.46f, 0.36f))
        2 -> FishProfile(1.3f, 0.40f, 0.22f, 0.34f, 0.4f, // Bass: stocky, tall
            floatArrayOf(0.78f, 0.82f, 0.60f), floatArrayOf(0.22f, 0.40f, 0.26f))
        3 -> FishProfile(1.6f, 0.26f, 0.30f, 0.30f, 0.2f, // Catfish: flat, wide
            floatArrayOf(0.62f, 0.58f, 0.48f), floatArrayOf(0.28f, 0.26f, 0.22f))
        4 -> FishProfile(2.2f, 0.22f, 0.14f, 0.30f, 0.95f, // Barracuda: long, pointed
            floatArrayOf(0.86f, 0.88f, 0.90f), floatArrayOf(0.40f, 0.45f, 0.50f))
        else -> FishProfile(1.1f, 0.42f, 0.18f, 0.36f, 0.45f, // Perch: tall, round
            floatArrayOf(0.86f, 0.78f, 0.50f), floatArrayOf(0.30f, 0.42f, 0.30f))
    }

    fun buildFish(type: Int): MeshData {
        val p = fishProfile(type)
        val b = Builder()
        // Body as a chain of elliptical rings from tail (-z) to nose (+z).
        val rings = 9
        val seg = 8
        val ringIdx = Array(rings) { IntArray(seg) }
        val halfLen = p.length / 2f
        for (i in 0 until rings) {
            val t = i.toFloat() / (rings - 1)            // 0 at tail, 1 at nose
            val z = -halfLen + p.length * t
            // Fusiform profile: thin at both ends, fat near the front-middle.
            val girth = sin(PI.toFloat() * t.coerceIn(0f, 1f))
            val nose = Math.pow(t.toDouble(), p.noseSharp.toDouble()).toFloat()
            val w = p.maxWidth * girth * (0.4f + 0.6f * nose)
            val h = p.maxHeight * girth * (0.5f + 0.5f * nose)
            for (s in 0 until seg) {
                val a = (s.toFloat() / seg) * 2f * PI.toFloat()
                val ox = cos(a) * w
                val oy = sin(a) * h
                // Belly (oy<0) lighter, back (oy>0) darker — countershading.
                val mix = (oy / (h + 1e-4f)) * 0.5f + 0.5f
                val cr = p.belly[0] * (1 - mix) + p.back[0] * mix
                val cg = p.belly[1] * (1 - mix) + p.back[1] * mix
                val cb = p.belly[2] * (1 - mix) + p.back[2] * mix
                ringIdx[i][s] = b.vertex(ox, oy, z, cr, cg, cb)
            }
        }
        for (i in 0 until rings - 1) for (s in 0 until seg) {
            val s2 = (s + 1) % seg
            b.quad(ringIdx[i][s], ringIdx[i][s2], ringIdx[i + 1][s2], ringIdx[i + 1][s])
        }
        // Tail fin: a flat vertical fan behind the last ring.
        run {
            val tz = -halfLen
            val c = b.vertex(0f, 0f, tz, p.back[0], p.back[1], p.back[2])
            val topO = b.vertex(0f, p.tailHeight, tz - p.length * 0.35f, p.back[0], p.back[1], p.back[2])
            val botO = b.vertex(0f, -p.tailHeight, tz - p.length * 0.35f, p.back[0], p.back[1], p.back[2])
            val mid = b.vertex(0f, 0f, tz - p.length * 0.22f, p.belly[0], p.belly[1], p.belly[2])
            b.tri(c, topO, mid); b.tri(c, mid, botO)
        }
        // Dorsal fin: a small flat triangle on the back.
        run {
            val z0 = -halfLen + p.length * 0.45f
            val z1 = -halfLen + p.length * 0.70f
            val base0 = b.vertex(0f, p.maxHeight * 0.5f, z0, p.back[0], p.back[1], p.back[2])
            val base1 = b.vertex(0f, p.maxHeight * 0.5f, z1, p.back[0], p.back[1], p.back[2])
            val tip = b.vertex(0f, p.maxHeight + p.tailHeight * 0.5f, (z0 + z1) / 2f, p.back[0], p.back[1], p.back[2])
            b.tri(base0, tip, base1)
        }
        return b.build()
    }
}
