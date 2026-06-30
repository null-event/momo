package com.nullevent.lakewallpaper

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CPU-side mesh data. Vertices are interleaved as
 * `[ px, py, pz,  nx, ny, nz,  cr, cg, cb ]` (9 floats / [STRIDE_FLOATS]).
 *
 * If [indexBuffer] is non-null the mesh is drawn with `glDrawElements`;
 * otherwise it is a triangle soup drawn with `glDrawArrays` (used by the
 * instanced fish and bird meshes).
 */
class Mesh(
    val vertexBuffer: FloatBuffer,
    val vertexCount: Int,
    val indexBuffer: IntBuffer?,
    val indexCount: Int
)

/**
 * Procedural geometry generation. Everything here is built from code — no
 * external models, textures, or assets are referenced.
 */
object GeometryBuilders {

    const val STRIDE_FLOATS = 9
    const val STRIDE_BYTES = STRIDE_FLOATS * 4

    // ------------------------------------------------------------------
    // Small accumulator used by all builders.
    // ------------------------------------------------------------------
    private class MeshData {
        val verts = ArrayList<Float>(4096)
        val indices = ArrayList<Int>(4096)

        fun vertex(
            px: Float, py: Float, pz: Float,
            nx: Float, ny: Float, nz: Float,
            cr: Float, cg: Float, cb: Float
        ): Int {
            val idx = verts.size / STRIDE_FLOATS
            verts.add(px); verts.add(py); verts.add(pz)
            verts.add(nx); verts.add(ny); verts.add(nz)
            verts.add(cr); verts.add(cg); verts.add(cb)
            return idx
        }

        fun tri(a: Int, b: Int, c: Int) {
            indices.add(a); indices.add(b); indices.add(c)
        }

        /** Adds a flat-shaded triangle directly (soup mode), returns nothing. */
        fun flatTri(
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float,
            cr: Float, cg: Float, cb: Float
        ) {
            val ux = bx - ax; val uy = by - ay; val uz = bz - az
            val vx = cx - ax; val vy = cy - ay; val vz = cz - az
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-6f) { nx /= len; ny /= len; nz /= len }
            val a = vertex(ax, ay, az, nx, ny, nz, cr, cg, cb)
            val b = vertex(bx, by, bz, nx, ny, nz, cr, cg, cb)
            val c = vertex(cx, cy, cz, nx, ny, nz, cr, cg, cb)
            tri(a, b, c)
        }

        fun toIndexedMesh(): Mesh {
            val vb = floatBuffer(verts)
            val ib = intBuffer(indices)
            return Mesh(vb, verts.size / STRIDE_FLOATS, ib, indices.size)
        }

        fun toSoupMesh(): Mesh {
            val vb = floatBuffer(verts)
            return Mesh(vb, verts.size / STRIDE_FLOATS, null, 0)
        }
    }

    private fun floatBuffer(list: List<Float>): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(list.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        for (f in list) fb.put(f)
        fb.position(0)
        return fb
    }

    private fun intBuffer(list: List<Int>): IntBuffer {
        val bb = ByteBuffer.allocateDirect(list.size * 4).order(ByteOrder.nativeOrder())
        val ib = bb.asIntBuffer()
        for (i in list) ib.put(i)
        ib.position(0)
        return ib
    }

    // Deterministic pseudo-random so geometry is stable across rebuilds.
    private fun hashNoise(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return ((h and 0x7fffffff) % 1000) / 1000f
    }

    // ==================================================================
    // 1. Tree with exposed roots (indexed)
    // ==================================================================
    fun buildTreeWithRoots(): Mesh {
        val m = MeshData()
        val trunkColor = floatArrayOf(0.36f, 0.25f, 0.15f)
        val rootColor = floatArrayOf(0.30f, 0.21f, 0.13f)
        val leafColor = floatArrayOf(0.15f, 0.42f, 0.18f)

        // Trunk: a tapered hexagonal column from y=-0.5 to y=3.5
        addCylinder(m, 0f, -0.5f, 0f, 0.35f, 0.22f, 4.0f, 6, trunkColor)

        // Branches: a few angled cylinders near the top.
        val branchDirs = arrayOf(
            floatArrayOf(0.7f, 0.7f, 0.1f),
            floatArrayOf(-0.6f, 0.7f, 0.3f),
            floatArrayOf(0.1f, 0.8f, -0.7f)
        )
        for (d in branchDirs) {
            addTaperedLimb(m, 0f, 3.2f, 0f, d[0], d[1], d[2], 1.6f, 0.14f, 0.06f, trunkColor)
        }

        // Canopy: a cluster of leaf cones.
        addCone(m, 0f, 4.4f, 0f, 1.4f, 1.6f, 7, leafColor)
        addCone(m, 0.6f, 4.0f, 0.3f, 1.0f, 1.2f, 6, leafColor)
        addCone(m, -0.7f, 3.9f, -0.2f, 0.9f, 1.1f, 6, leafColor)

        // Exposed roots fanning out and downward below the waterline (y < 0).
        val rootDirs = 6
        for (i in 0 until rootDirs) {
            val ang = (i / rootDirs.toFloat()) * (2f * Math.PI.toFloat())
            val dx = cos(ang)
            val dz = sin(ang)
            addTaperedLimb(
                m,
                dx * 0.25f, -0.4f, dz * 0.25f,
                dx, -0.9f, dz,
                2.6f, 0.12f, 0.03f, rootColor
            )
        }
        return m.toIndexedMesh()
    }

    /** Tapered hexagon/N-gon column from (cx,baseY,cz) upward. */
    private fun addCylinder(
        m: MeshData, cx: Float, baseY: Float, cz: Float,
        rBottom: Float, rTop: Float, height: Float, sides: Int, color: FloatArray
    ) {
        val topY = baseY + height
        val ringBottom = IntArray(sides)
        val ringTop = IntArray(sides)
        for (i in 0 until sides) {
            val a = (i / sides.toFloat()) * (2f * Math.PI.toFloat())
            val nx = cos(a); val nz = sin(a)
            ringBottom[i] = m.vertex(cx + nx * rBottom, baseY, cz + nz * rBottom, nx, 0f, nz, color[0], color[1], color[2])
            ringTop[i] = m.vertex(cx + nx * rTop, topY, cz + nz * rTop, nx, 0f, nz, color[0], color[1], color[2])
        }
        for (i in 0 until sides) {
            val j = (i + 1) % sides
            m.tri(ringBottom[i], ringBottom[j], ringTop[i])
            m.tri(ringTop[i], ringBottom[j], ringTop[j])
        }
    }

    /** A short tapered limb (branch or root) from origin toward a direction. */
    private fun addTaperedLimb(
        m: MeshData, ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        length: Float, rBase: Float, rTip: Float, color: FloatArray
    ) {
        // Normalize direction.
        var nx = dx; var ny = dy; var nz = dz
        val dl = sqrt(nx * nx + ny * ny + nz * nz)
        if (dl > 1e-6f) { nx /= dl; ny /= dl; nz /= dl }
        val tipX = ox + nx * length
        val tipY = oy + ny * length
        val tipZ = oz + nz * length

        // Build an orthonormal basis perpendicular to the limb axis.
        val up = if (kotlin.math.abs(ny) < 0.9f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
        var sx = up[1] * nz - up[2] * ny
        var sy = up[2] * nx - up[0] * nz
        var sz = up[0] * ny - up[1] * nx
        val sl = sqrt(sx * sx + sy * sy + sz * sz)
        if (sl > 1e-6f) { sx /= sl; sy /= sl; sz /= sl }
        val tx = ny * sz - nz * sy
        val ty = nz * sx - nx * sz
        val tz = nx * sy - ny * sx

        val sides = 5
        val baseRing = IntArray(sides)
        val tipRing = IntArray(sides)
        for (i in 0 until sides) {
            val a = (i / sides.toFloat()) * (2f * Math.PI.toFloat())
            val ca = cos(a); val sa = sin(a)
            val offx = sx * ca + tx * sa
            val offy = sy * ca + ty * sa
            val offz = sz * ca + tz * sa
            baseRing[i] = m.vertex(
                ox + offx * rBase, oy + offy * rBase, oz + offz * rBase,
                offx, offy, offz, color[0], color[1], color[2]
            )
            tipRing[i] = m.vertex(
                tipX + offx * rTip, tipY + offy * rTip, tipZ + offz * rTip,
                offx, offy, offz, color[0], color[1], color[2]
            )
        }
        for (i in 0 until sides) {
            val j = (i + 1) % sides
            m.tri(baseRing[i], baseRing[j], tipRing[i])
            m.tri(tipRing[i], baseRing[j], tipRing[j])
        }
    }

    private fun addCone(
        m: MeshData, cx: Float, baseY: Float, cz: Float,
        radius: Float, height: Float, sides: Int, color: FloatArray
    ) {
        val apex = m.vertex(cx, baseY + height, cz, 0f, 1f, 0f, color[0], color[1], color[2])
        val ring = IntArray(sides)
        for (i in 0 until sides) {
            val a = (i / sides.toFloat()) * (2f * Math.PI.toFloat())
            val nx = cos(a); val nz = sin(a)
            ring[i] = m.vertex(cx + nx * radius, baseY, cz + nz * radius, nx, 0.4f, nz, color[0], color[1], color[2])
        }
        for (i in 0 until sides) {
            val j = (i + 1) % sides
            m.tri(apex, ring[i], ring[j])
        }
    }

    // ==================================================================
    // 2. Fish (triangle soup, points along +Z) — for instanced drawing
    // ==================================================================
    fun buildFish(): Mesh {
        val m = MeshData()
        val body = floatArrayOf(0.70f, 0.72f, 0.78f)
        val belly = floatArrayOf(0.85f, 0.86f, 0.90f)

        // Diamond body: nose at +Z, tail joint at -Z.
        val nose = floatArrayOf(0f, 0f, 0.55f)
        val tail = floatArrayOf(0f, 0f, -0.35f)
        val top = floatArrayOf(0f, 0.18f, 0.05f)
        val bot = floatArrayOf(0f, -0.18f, 0.05f)
        val left = floatArrayOf(-0.16f, 0f, 0.0f)
        val right = floatArrayOf(0.16f, 0f, 0.0f)

        // Eight faces of the elongated octahedron.
        m.flatTri(nose[0], nose[1], nose[2], top[0], top[1], top[2], right[0], right[1], right[2], body[0], body[1], body[2])
        m.flatTri(nose[0], nose[1], nose[2], right[0], right[1], right[2], bot[0], bot[1], bot[2], belly[0], belly[1], belly[2])
        m.flatTri(nose[0], nose[1], nose[2], bot[0], bot[1], bot[2], left[0], left[1], left[2], belly[0], belly[1], belly[2])
        m.flatTri(nose[0], nose[1], nose[2], left[0], left[1], left[2], top[0], top[1], top[2], body[0], body[1], body[2])
        m.flatTri(tail[0], tail[1], tail[2], right[0], right[1], right[2], top[0], top[1], top[2], body[0], body[1], body[2])
        m.flatTri(tail[0], tail[1], tail[2], bot[0], bot[1], bot[2], right[0], right[1], right[2], belly[0], belly[1], belly[2])
        m.flatTri(tail[0], tail[1], tail[2], left[0], left[1], left[2], bot[0], bot[1], bot[2], belly[0], belly[1], belly[2])
        m.flatTri(tail[0], tail[1], tail[2], top[0], top[1], top[2], left[0], left[1], left[2], body[0], body[1], body[2])

        // Tail fin: a flat triangle behind the joint.
        m.flatTri(tail[0], tail[1], tail[2], 0f, 0.22f, -0.55f, 0f, -0.22f, -0.55f, body[0], body[1], body[2])
        m.flatTri(tail[0], tail[1], tail[2], 0f, -0.22f, -0.55f, 0f, 0.22f, -0.55f, body[0], body[1], body[2])

        // Dorsal fin.
        m.flatTri(top[0], top[1], top[2], 0f, 0.34f, -0.1f, 0f, 0.16f, -0.25f, body[0], body[1], body[2])
        return m.toSoupMesh()
    }

    // ==================================================================
    // 3. Bird (triangle soup, points along +X, wings along Z)
    // ==================================================================
    fun buildBird(): Mesh {
        val m = MeshData()
        val c = floatArrayOf(0.12f, 0.12f, 0.14f)

        val nose = floatArrayOf(0.40f, 0f, 0f)
        val tail = floatArrayOf(-0.30f, 0f, 0f)
        val wingL = floatArrayOf(0f, 0f, 0.55f)
        val wingR = floatArrayOf(0f, 0f, -0.55f)
        val crest = floatArrayOf(0.02f, 0.06f, 0f)

        // Flat body/wings (two faces each so they show from both sides).
        m.flatTri(nose[0], nose[1], nose[2], wingL[0], wingL[1], wingL[2], tail[0], tail[1], tail[2], c[0], c[1], c[2])
        m.flatTri(nose[0], nose[1], nose[2], tail[0], tail[1], tail[2], wingL[0], wingL[1], wingL[2], c[0], c[1], c[2])
        m.flatTri(nose[0], nose[1], nose[2], tail[0], tail[1], tail[2], wingR[0], wingR[1], wingR[2], c[0], c[1], c[2])
        m.flatTri(nose[0], nose[1], nose[2], wingR[0], wingR[1], wingR[2], tail[0], tail[1], tail[2], c[0], c[1], c[2])
        // Tiny raised crest gives a hint of body volume.
        m.flatTri(nose[0], nose[1], nose[2], crest[0], crest[1], crest[2], tail[0], tail[1], tail[2], c[0], c[1], c[2])
        return m.toSoupMesh()
    }

    // ==================================================================
    // 4. Water plane (indexed grid, >= 64 x 16 segments)
    // ==================================================================
    fun buildWaterPlane(): Mesh {
        val m = MeshData()
        val segX = 96
        val segZ = 24
        val width = 160f      // spans well past the visible width
        val depth = 70f
        val color = floatArrayOf(0.10f, 0.35f, 0.55f)

        for (iz in 0..segZ) {
            for (ix in 0..segX) {
                val x = (ix / segX.toFloat() - 0.5f) * width
                val z = -(iz / segZ.toFloat()) * depth   // extend forward (-Z)
                m.vertex(x, 0f, z, 0f, 1f, 0f, color[0], color[1], color[2])
            }
        }
        val rowStride = segX + 1
        for (iz in 0 until segZ) {
            for (ix in 0 until segX) {
                val a = iz * rowStride + ix
                val b = a + 1
                val c = a + rowStride
                val d = c + 1
                m.tri(a, c, b)
                m.tri(b, c, d)
            }
        }
        return m.toIndexedMesh()
    }

    // ==================================================================
    // 5. Lakebed (uneven ground below the waterline)
    // ==================================================================
    fun buildLakebed(): Mesh {
        val m = MeshData()
        val segX = 48
        val segZ = 24
        val width = 160f
        val depth = 70f
        val baseY = -13f

        for (iz in 0..segZ) {
            for (ix in 0..segX) {
                val u = ix / segX.toFloat()
                val v = iz / segZ.toFloat()
                val x = (u - 0.5f) * width
                val z = -v * depth
                // Layered value noise for gentle rolling dirt and rocks.
                val n = hashNoise(ix, iz) * 0.6f +
                        hashNoise(ix / 3, iz / 3) * 0.9f +
                        hashNoise(ix / 7, iz / 7) * 1.4f
                val y = baseY + n * 2.4f
                // Color: darker dirt in valleys, lighter rocks on peaks.
                val rock = (n - 1.0f).coerceIn(0f, 1f)
                val cr = 0.20f + rock * 0.18f
                val cg = 0.16f + rock * 0.12f
                val cb = 0.10f + rock * 0.10f
                m.vertex(x, y, z, 0f, 1f, 0f, cr, cg, cb)
            }
        }
        val rowStride = segX + 1
        // Triangulate and recompute smooth-ish normals afterwards.
        for (iz in 0 until segZ) {
            for (ix in 0 until segX) {
                val a = iz * rowStride + ix
                val b = a + 1
                val c = a + rowStride
                val d = c + 1
                m.tri(a, c, b)
                m.tri(b, c, d)
            }
        }
        recomputeNormals(m)
        return m.toIndexedMesh()
    }

    /** Recomputes per-vertex normals by averaging face normals (indexed mesh). */
    private fun recomputeNormals(m: MeshData) {
        val vcount = m.verts.size / STRIDE_FLOATS
        val nx = FloatArray(vcount); val ny = FloatArray(vcount); val nz = FloatArray(vcount)
        var i = 0
        while (i < m.indices.size) {
            val ia = m.indices[i]; val ib = m.indices[i + 1]; val ic = m.indices[i + 2]
            val ax = m.verts[ia * STRIDE_FLOATS]; val ay = m.verts[ia * STRIDE_FLOATS + 1]; val az = m.verts[ia * STRIDE_FLOATS + 2]
            val bx = m.verts[ib * STRIDE_FLOATS]; val by = m.verts[ib * STRIDE_FLOATS + 1]; val bz = m.verts[ib * STRIDE_FLOATS + 2]
            val cx = m.verts[ic * STRIDE_FLOATS]; val cy = m.verts[ic * STRIDE_FLOATS + 1]; val cz = m.verts[ic * STRIDE_FLOATS + 2]
            val ux = bx - ax; val uy = by - ay; val uz = bz - az
            val vx = cx - ax; val vy = cy - ay; val vz = cz - az
            val fnx = uy * vz - uz * vy
            val fny = uz * vx - ux * vz
            val fnz = ux * vy - uy * vx
            nx[ia] += fnx; ny[ia] += fny; nz[ia] += fnz
            nx[ib] += fnx; ny[ib] += fny; nz[ib] += fnz
            nx[ic] += fnx; ny[ic] += fny; nz[ic] += fnz
            i += 3
        }
        for (v in 0 until vcount) {
            val len = sqrt(nx[v] * nx[v] + ny[v] * ny[v] + nz[v] * nz[v])
            val base = v * STRIDE_FLOATS
            if (len > 1e-6f) {
                m.verts[base + 3] = nx[v] / len
                m.verts[base + 4] = ny[v] / len
                m.verts[base + 5] = nz[v] / len
            }
        }
    }

    // ==================================================================
    // 6. Forest backdrop (several simple cone-trees placed far back)
    // ==================================================================
    fun buildForestBackdrop(): Mesh {
        val m = MeshData()
        val trunkColor = floatArrayOf(0.22f, 0.16f, 0.10f)
        val count = 60
        for (i in 0 until count) {
            val rx = hashNoise(i, 11)
            val rz = hashNoise(i, 29)
            val rs = hashNoise(i, 53)
            val x = (rx - 0.5f) * 280f
            val z = -150f - rz * 70f
            val scale = 3.5f + rs * 5.0f
            // Darker, bluer greens for atmospheric distance.
            val tint = 0.10f + hashNoise(i, 7) * 0.12f
            val leafColor = floatArrayOf(0.08f + tint * 0.3f, 0.28f + tint, 0.20f + tint * 0.4f)
            addCylinder(m, x, 0f, z, 0.4f * scale * 0.2f, 0.25f * scale * 0.2f, scale * 0.4f, 5, trunkColor)
            addCone(m, x, scale * 0.35f, z, scale * 0.35f, scale, 6, leafColor)
            addCone(m, x, scale * 0.65f, z, scale * 0.26f, scale * 0.7f, 6, leafColor)
        }
        return m.toIndexedMesh()
    }
}
