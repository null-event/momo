package com.nullevent.lakewallpaper

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * The scene renderer: builds GPU resources, runs the per-frame draw loop in the
 * required order (sky -> underwater -> water surface -> birds), drives the fish
 * and bird systems, and releases all resources on context loss / destroy.
 */
class LakeRenderer : GLSurfaceView.Renderer {

    // --- GPU mesh handles ------------------------------------------------
    private class Gpu(
        val vao: Int,
        val vbo: Int,
        val ebo: Int,           // 0 if none
        val instanceVbo: Int,   // 0 if not instanced
        val vertexCount: Int,
        val indexCount: Int
    )

    // Programs.
    private var skyProgram = 0
    private var terrainProgram = 0
    private var fishProgram = 0
    private var waterProgram = 0
    private var birdProgram = 0

    // Meshes.
    private var skyMesh: Gpu? = null
    private var forestMesh: Gpu? = null
    private var lakebedMesh: Gpu? = null
    private var treeMesh: Gpu? = null
    private var waterMesh: Gpu? = null
    private var fishMesh: Gpu? = null
    private var birdMesh: Gpu? = null

    // Simulation systems.
    private val fishSystem = FishSystem(80)
    private val birdSystem = BirdSystem(22)

    // Matrices.
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val invViewProj = FloatArray(16)

    private val lightDir = floatArrayOf(-0.4f, -0.8f, -0.45f)

    // Timing.
    private var startTimeNs = 0L
    private var lastFrameNs = 0L
    private var time = 0f

    // Viewport.
    private var viewportW = 1
    private var viewportH = 1

    // Sensor-driven camera offsets (radians), set from the service thread.
    @Volatile var pitch = 0f   // clamped +/- 8 deg by the service
    @Volatile var yaw = 0f     // clamped +/- 12 deg by the service

    // Touch RNG for randomized food depth.
    private var touchSeed = 0x51ED270B

    // Fullscreen triangle (NDC) for the sky.
    private val skyTri = floatArrayOf(
        // pos(3)            normal(3)        color(3)  -- normal/color unused
        -1f, -1f, 0f,   0f, 0f, 1f,   0f, 0f, 0f,
        3f, -1f, 0f,   0f, 0f, 1f,   0f, 0f, 0f,
        -1f, 3f, 0f,   0f, 0f, 1f,   0f, 0f, 0f
    )

    // ====================================================================
    // Renderer lifecycle
    // ====================================================================
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.5f, 0.7f, 0.85f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        // Face culling is left disabled: procedural meshes are not guaranteed to
        // have consistent winding, so culling would punch holes in the geometry.
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // Build programs.
        skyProgram = ShaderUtils.createProgram(ShaderUtils.SKY_VS, ShaderUtils.SKY_FS)
        terrainProgram = ShaderUtils.createProgram(ShaderUtils.TERRAIN_VS, ShaderUtils.TERRAIN_FS)
        fishProgram = ShaderUtils.createProgram(ShaderUtils.FISH_VS, ShaderUtils.FISH_FS)
        waterProgram = ShaderUtils.createProgram(ShaderUtils.WATER_VS, ShaderUtils.WATER_FS)
        birdProgram = ShaderUtils.createProgram(ShaderUtils.BIRD_VS, ShaderUtils.BIRD_FS)

        // Build meshes (context may have been lost; rebuild everything fresh).
        skyMesh = uploadSky()
        forestMesh = uploadIndexed(GeometryBuilders.buildForestBackdrop())
        lakebedMesh = uploadIndexed(GeometryBuilders.buildLakebed())
        treeMesh = uploadIndexed(GeometryBuilders.buildTreeWithRoots())
        waterMesh = uploadIndexed(GeometryBuilders.buildWaterPlane())
        fishMesh = uploadInstanced(GeometryBuilders.buildFish(), FishSystem.INSTANCE_FLOATS, instanceLayoutFish())
        birdMesh = uploadInstanced(GeometryBuilders.buildBird(), BirdSystem.INSTANCE_FLOATS, instanceLayoutBird())

        startTimeNs = System.nanoTime()
        lastFrameNs = startTimeNs
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = if (width > 0) width else 1
        viewportH = if (height > 0) height else 1
        GLES30.glViewport(0, 0, viewportW, viewportH)
        val aspect = viewportW.toFloat() / viewportH.toFloat()
        // ~72 deg vertical FOV gives a wide horizontal field; far clip 300.
        MathUtils.perspectiveM(projection, 72f, aspect, 0.1f, 300f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        time = (now - startTimeNs) / 1_000_000_000f
        var dt = (now - lastFrameNs) / 1_000_000_000f
        lastFrameNs = now
        if (dt > 0.1f) dt = 0.1f

        updateCamera()
        fishSystem.update(dt)
        birdSystem.update(time)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        drawSky()
        drawForest()
        drawLakebed()
        drawTree()
        drawFish()
        drawWater()
        drawBirds()
    }

    // ====================================================================
    // Camera
    // ====================================================================
    private fun updateCamera() {
        val cp = cos(pitch); val sp = sin(pitch)
        val cyw = cos(yaw); val syw = sin(yaw)
        val fx = -cp * syw
        val fy = sp
        val fz = -cp * cyw
        val eyeX = 0f; val eyeY = 0.3f; val eyeZ = 0f
        MathUtils.lookAtM(
            view,
            eyeX, eyeY, eyeZ,
            eyeX + fx, eyeY + fy, eyeZ + fz,
            0f, 1f, 0f
        )
        MathUtils.multiplyMM(viewProj, projection, view)
        MathUtils.invertM(invViewProj, viewProj)
    }

    // ====================================================================
    // Draw passes
    // ====================================================================
    private fun drawSky() {
        val m = skyMesh ?: return
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(skyProgram)
        GLES30.glBindVertexArray(m.vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, m.vertexCount)
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
    }

    private fun drawTerrainMesh(m: Gpu) {
        MathUtils.multiplyMM(mvp, viewProj, model)
        val uMvp = GLES30.glGetUniformLocation(terrainProgram, "u_mvp")
        val uModel = GLES30.glGetUniformLocation(terrainProgram, "u_model")
        val uLight = GLES30.glGetUniformLocation(terrainProgram, "u_lightDir")
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES30.glUniform3f(uLight, lightDir[0], lightDir[1], lightDir[2])
        GLES30.glBindVertexArray(m.vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun drawForest() {
        val m = forestMesh ?: return
        GLES30.glUseProgram(terrainProgram)
        MathUtils.identity(model)
        drawTerrainMesh(m)
    }

    private fun drawLakebed() {
        val m = lakebedMesh ?: return
        GLES30.glUseProgram(terrainProgram)
        MathUtils.identity(model)
        drawTerrainMesh(m)
    }

    private fun drawTree() {
        val m = treeMesh ?: return
        GLES30.glUseProgram(terrainProgram)
        MathUtils.identity(model)
        // Place the rooted tree to one side, near the bank.
        MathUtils.translateM(model, -6f, 0f, -10f)
        drawTerrainMesh(m)
    }

    private fun drawFish() {
        val m = fishMesh ?: return
        GLES30.glUseProgram(fishProgram)
        // Upload fresh instance data.
        uploadInstanceData(m.instanceVbo, fishSystem.instanceBuffer, fishSystem.count * FishSystem.INSTANCE_FLOATS)
        val uVp = GLES30.glGetUniformLocation(fishProgram, "u_viewProj")
        val uTime = GLES30.glGetUniformLocation(fishProgram, "u_time")
        val uLight = GLES30.glGetUniformLocation(fishProgram, "u_lightDir")
        GLES30.glUniformMatrix4fv(uVp, 1, false, viewProj, 0)
        GLES30.glUniform1f(uTime, time)
        GLES30.glUniform3f(uLight, lightDir[0], lightDir[1], lightDir[2])
        GLES30.glBindVertexArray(m.vao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, m.vertexCount, fishSystem.count)
        GLES30.glBindVertexArray(0)
    }

    private fun drawWater() {
        val m = waterMesh ?: return
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)   // test against scene but don't occlude
        GLES30.glUseProgram(waterProgram)
        MathUtils.identity(model)
        MathUtils.multiplyMM(mvp, viewProj, model)
        val uMvp = GLES30.glGetUniformLocation(waterProgram, "u_mvp")
        val uTime = GLES30.glGetUniformLocation(waterProgram, "u_time")
        val uLight = GLES30.glGetUniformLocation(waterProgram, "u_lightDir")
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(uTime, time)
        GLES30.glUniform3f(uLight, lightDir[0], lightDir[1], lightDir[2])
        GLES30.glBindVertexArray(m.vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawBirds() {
        val m = birdMesh ?: return
        // Birds render last with no depth testing (always above the scene).
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(birdProgram)
        uploadInstanceData(m.instanceVbo, birdSystem.instanceBuffer, birdSystem.count * BirdSystem.INSTANCE_FLOATS)
        val uVp = GLES30.glGetUniformLocation(birdProgram, "u_viewProj")
        val uTime = GLES30.glGetUniformLocation(birdProgram, "u_time")
        GLES30.glUniformMatrix4fv(uVp, 1, false, viewProj, 0)
        GLES30.glUniform1f(uTime, time)
        GLES30.glBindVertexArray(m.vao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, m.vertexCount, birdSystem.count)
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    // ====================================================================
    // Touch -> world ray -> food spawn (runs on the GL thread)
    // ====================================================================
    fun onTouchWorld(screenX: Float, screenY: Float) {
        // NDC.
        val nx = 2f * screenX / viewportW - 1f
        val ny = 1f - 2f * screenY / viewportH

        val nearP = floatArrayOf(nx, ny, -1f, 1f)
        val farP = floatArrayOf(nx, ny, 1f, 1f)
        val nearW = FloatArray(4)
        val farW = FloatArray(4)
        MathUtils.multiplyMV(nearW, invViewProj, nearP)
        MathUtils.multiplyMV(farW, invViewProj, farP)
        if (nearW[3] == 0f || farW[3] == 0f) return
        for (i in 0 until 3) { nearW[i] /= nearW[3]; farW[i] /= farW[3] }

        val ox = nearW[0]; val oy = nearW[1]; val oz = nearW[2]
        var dx = farW[0] - ox; var dy = farW[1] - oy; var dz = farW[2] - oz
        val dl = MathUtils.length(dx, dy, dz)
        if (dl < 1e-6f) return
        dx /= dl; dy /= dl; dz /= dl

        // Randomized target depth between z = -5 and z = -30.
        touchSeed = touchSeed * 1664525 + 1013904223
        val r = ((touchSeed ushr 8) and 0xFFFFFF) / 16777216f
        val zRand = -5f - r * 25f

        if (kotlin.math.abs(dz) < 1e-5f) return
        val t = (zRand - oz) / dz
        if (t <= 0f) return
        val px = ox + dx * t
        val py = oy + dy * t
        val pz = oz + dz * t
        fishSystem.spawnFood(px, py, pz)
    }

    // ====================================================================
    // Resource creation helpers
    // ====================================================================
    private fun uploadSky(): Gpu {
        val fb = ByteBuffer.allocateDirect(skyTri.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(skyTri); fb.position(0)

        val vao = genVao()
        val vbo = genBuffer()
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, skyTri.size * 4, fb, GLES30.GL_STATIC_DRAW)
        setBaseAttribs()
        GLES30.glBindVertexArray(0)
        return Gpu(vao, vbo, 0, 0, 3, 0)
    }

    private fun uploadIndexed(mesh: Mesh): Gpu {
        val vao = genVao()
        val vbo = genBuffer()
        val ebo = genBuffer()
        GLES30.glBindVertexArray(vao)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            mesh.vertexCount * GeometryBuilders.STRIDE_BYTES,
            mesh.vertexBuffer, GLES30.GL_STATIC_DRAW
        )
        setBaseAttribs()

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            mesh.indexCount * 4, mesh.indexBuffer, GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindVertexArray(0)
        return Gpu(vao, vbo, ebo, 0, mesh.vertexCount, mesh.indexCount)
    }

    private fun uploadInstanced(mesh: Mesh, instanceFloats: Int, layout: () -> Unit): Gpu {
        val vao = genVao()
        val vbo = genBuffer()
        val instVbo = genBuffer()
        GLES30.glBindVertexArray(vao)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            mesh.vertexCount * GeometryBuilders.STRIDE_BYTES,
            mesh.vertexBuffer, GLES30.GL_STATIC_DRAW
        )
        setBaseAttribs()

        // Instance buffer: allocated now, filled per-frame.
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 4096 * instanceFloats, null, GLES30.GL_DYNAMIC_DRAW)
        layout()
        GLES30.glBindVertexArray(0)
        return Gpu(vao, vbo, 0, instVbo, mesh.vertexCount, 0)
    }

    private fun uploadInstanceData(instanceVbo: Int, buffer: FloatBuffer, floatCount: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        buffer.position(0)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, floatCount * 4, buffer)
    }

    /** Sets attributes 0/1/2 (pos, normal, color) for the currently bound VBO. */
    private fun setBaseAttribs() {
        val stride = GeometryBuilders.STRIDE_BYTES
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24)
    }

    private fun instanceLayoutFish(): () -> Unit = {
        val stride = FishSystem.INSTANCE_FLOATS * 4  // 28
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glVertexAttribDivisor(3, 1)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glVertexAttribDivisor(4, 1)
        GLES30.glEnableVertexAttribArray(5)
        GLES30.glVertexAttribPointer(5, 3, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glVertexAttribDivisor(5, 1)
    }

    private fun instanceLayoutBird(): () -> Unit = {
        val stride = BirdSystem.INSTANCE_FLOATS * 4  // 20
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glVertexAttribDivisor(3, 1)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glVertexAttribDivisor(4, 1)
        GLES30.glEnableVertexAttribArray(5)
        GLES30.glVertexAttribPointer(5, 1, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glVertexAttribDivisor(5, 1)
    }

    private fun genVao(): Int {
        val a = IntArray(1)
        GLES30.glGenVertexArrays(1, a, 0)
        return a[0]
    }

    private fun genBuffer(): Int {
        val a = IntArray(1)
        GLES30.glGenBuffers(1, a, 0)
        return a[0]
    }

    // ====================================================================
    // Cleanup
    // ====================================================================
    /** Releases all GL objects. Safe to call when the context is being torn down. */
    fun release() {
        val meshes = listOf(skyMesh, forestMesh, lakebedMesh, treeMesh, waterMesh, fishMesh, birdMesh)
        for (m in meshes) {
            m ?: continue
            if (m.vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(m.vao), 0)
            if (m.vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.vbo), 0)
            if (m.ebo != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.ebo), 0)
            if (m.instanceVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.instanceVbo), 0)
        }
        skyMesh = null; forestMesh = null; lakebedMesh = null; treeMesh = null
        waterMesh = null; fishMesh = null; birdMesh = null

        for (p in intArrayOf(skyProgram, terrainProgram, fishProgram, waterProgram, birdProgram)) {
            if (p != 0) GLES30.glDeleteProgram(p)
        }
        skyProgram = 0; terrainProgram = 0; fishProgram = 0; waterProgram = 0; birdProgram = 0
    }
}
