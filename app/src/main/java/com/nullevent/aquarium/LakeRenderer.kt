package com.nullevent.aquarium

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * The scene renderer. Builds all GPU resources on surface creation, runs the
 * fish/plant simulation, and draws (in order) the sky backdrop, the underwater
 * terrain / roots / plants, the instanced fish, food, and finally the
 * semi-transparent water surface. Total draw calls stay well under 30.
 *
 * Context loss is handled by rebuilding everything in [onSurfaceCreated];
 * [release] frees GL objects when the engine is torn down.
 */
class LakeRenderer : GLSurfaceView.Renderer {

    @Volatile var settings: AquariumSettings = AquariumSettings()
        private set
    @Volatile private var settingsDirty = true

    // Tilt (accelerometer) inputs, written from the sensor thread.
    @Volatile private var accelX = 0f
    @Volatile private var accelY = 9.8f
    @Volatile private var accelZ = 0f

    // Smoothed camera angles (degrees).
    private var yawDeg = 0f
    private var pitchDeg = 0f

    // Pending touch (screen px) queued for ray-pick on the GL thread.
    @Volatile private var pendingTouch = false
    @Volatile private var touchX = 0f
    @Volatile private var touchY = 0f

    private var viewportW = 1
    private var viewportH = 1

    private var lastTimeNs = 0L
    private var elapsed = 0f
    private var autoFoodTimer = 35f

    // Matrices.
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val invViewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    private val eye = floatArrayOf(0f, 0.01f, 3f)
    private val lightDir = floatArrayOf(-0.3f, -1.0f, -0.25f)

    // Systems.
    private val fishSystem = FishSystem(4, 12, 1f)
    private val plantSystem = PlantSystem()

    // GL programs.
    private var skyProg = 0
    private var terrainProg = 0
    private var plantProg = 0
    private var fishProg = 0
    private var foodProg = 0
    private var waterProg = 0

    // Meshes.
    private lateinit var bedMesh: GpuMesh
    private lateinit var treeMesh: GpuMesh
    private lateinit var seaweedMesh: GpuMesh
    private lateinit var waterMesh: GpuMesh
    private lateinit var foodMesh: GpuMesh
    private val fishMeshes = HashMap<Int, GpuMesh>()

    private var fishInstanceVbo = 0
    private var plantInstanceVbo = 0
    private var foodInstanceVbo = 0
    private var emptyVao = 0

    private val trackedBuffers = ArrayList<Int>()
    private val trackedVaos = ArrayList<Int>()
    private val trackedProgs = ArrayList<Int>()

    // -----------------------------------------------------------------
    // External hooks (called from the service / UI thread)
    // -----------------------------------------------------------------

    fun setTilt(x: Float, y: Float, z: Float) { accelX = x; accelY = y; accelZ = z }

    fun queueTouch(x: Float, y: Float) { touchX = x; touchY = y; pendingTouch = true }

    fun applySettings(s: AquariumSettings) { settings = s; settingsDirty = true }

    // -----------------------------------------------------------------
    // GL lifecycle
    // -----------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A fresh context: discard any stale ids and rebuild from scratch.
        trackedBuffers.clear(); trackedVaos.clear(); trackedProgs.clear()
        fishMeshes.clear()

        GLES30.glClearColor(0.5f, 0.7f, 0.85f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        // Back-face culling is intentionally left OFF: the procedural meshes mix
        // winding orders and the fullscreen sky triangle would otherwise vanish.
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        skyProg = track(ShaderUtils.buildProgram(ShaderUtils.SKY_VS, ShaderUtils.SKY_FS))
        terrainProg = track(ShaderUtils.buildProgram(ShaderUtils.TERRAIN_VS, ShaderUtils.TERRAIN_FS))
        plantProg = track(ShaderUtils.buildProgram(ShaderUtils.PLANT_VS, ShaderUtils.TERRAIN_FS))
        fishProg = track(ShaderUtils.buildProgram(ShaderUtils.FISH_VS, ShaderUtils.FISH_FS))
        foodProg = track(ShaderUtils.buildProgram(ShaderUtils.FOOD_VS, ShaderUtils.FOOD_FS))
        waterProg = track(ShaderUtils.buildProgram(ShaderUtils.WATER_VS, ShaderUtils.WATER_FS))

        bedMesh = uploadStatic(GeometryBuilders.buildAquariumBed())
        treeMesh = uploadStatic(GeometryBuilders.buildTreeWithRoots())
        seaweedMesh = uploadStatic(GeometryBuilders.buildSeaweed())
        waterMesh = uploadStatic(GeometryBuilders.buildWaterPlane())
        foodMesh = uploadStatic(GeometryBuilders.buildFoodSphere())

        // Instance VBOs (dynamic, re-uploaded each frame).
        fishInstanceVbo = genBuffer()
        plantInstanceVbo = genBuffer()
        foodInstanceVbo = genBuffer()

        // Empty VAO for the attribute-less fullscreen sky triangle.
        val v = IntArray(1); GLES30.glGenVertexArrays(1, v, 0)
        emptyVao = v[0]; trackedVaos.add(emptyVao)

        rebuildScene()
        lastTimeNs = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewportW, viewportH)
        val aspect = viewportW.toFloat() / viewportH.toFloat()
        MathUtils.perspectiveM(proj, settings.fovDeg, aspect, 0.05f, 80f)
    }

    private fun rebuildScene() {
        val s = settings
        fishSystem.varietyCount = s.varietyCount
        fishSystem.countPerVariety = s.countPerVariety
        fishSystem.speedScale = s.fishSpeed
        fishSystem.rebuild()

        plantSystem.seaweedCount = s.seaweedCount
        plantSystem.treeCount = s.treeCount
        plantSystem.rootsScale = s.rootsScale
        plantSystem.swayStrength = s.plantSway
        plantSystem.rebuild()

        // Build / fetch a mesh per active species.
        for (t in fishSystem.activeTypes) {
            if (!fishMeshes.containsKey(t)) {
                val m = uploadStatic(GeometryBuilders.buildFish(t))
                m.configureFishInstancing(fishInstanceVbo)
                fishMeshes[t] = m
            }
        }
        seaweedMesh.configurePlantInstancing(plantInstanceVbo)
        foodMesh.configureFishInstancing(foodInstanceVbo)

        // Field-of-view may have changed via settings; refresh the projection.
        val aspect = viewportW.toFloat() / viewportH.toFloat()
        MathUtils.perspectiveM(proj, settings.fovDeg, aspect, 0.05f, 80f)
        settingsDirty = false
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        var dt = (now - lastTimeNs) / 1_000_000_000f
        lastTimeNs = now
        if (dt > 0.1f) dt = 0.1f
        elapsed += dt

        if (settingsDirty) rebuildScene()

        updateCamera(dt)
        autoFeed(dt)
        if (pendingTouch) { handleTouch(); pendingTouch = false }

        fishSystem.update(dt)
        plantSystem.update(dt)

        val s = settings
        GLES30.glClearColor(s.waterR * 0.5f, s.waterG * 0.6f, s.waterB * 0.7f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        drawSky(s)
        drawTerrainAndRoots(s)
        drawSeaweed(s)
        drawFish(s)
        drawFood(s)
        drawWater(s)
    }

    // -----------------------------------------------------------------
    // Camera, feeding, picking
    // -----------------------------------------------------------------

    private fun updateCamera(dt: Float) {
        // Map gravity vector to medium-angle yaw/pitch and smooth it.
        val targetYaw = (accelX / 9.8f * 40f).coerceIn(-40f, 40f)
        val targetPitch = (accelZ / 9.8f * 15f).coerceIn(-15f, 15f)
        val k = (dt * 4f).coerceIn(0f, 1f)
        yawDeg += (targetYaw - yawDeg) * k
        pitchDeg += (targetPitch - pitchDeg) * k

        val basePitch = -14f                      // look slightly down: waterline near top
        val yaw = Math.toRadians(yawDeg.toDouble()).toFloat()
        val pitch = Math.toRadians((basePitch + pitchDeg).toDouble()).toFloat()
        val fx = cos(pitch) * sin(yaw)
        val fy = sin(pitch)
        val fz = -cos(pitch) * cos(yaw)
        MathUtils.lookAtM(
            view, eye[0], eye[1], eye[2],
            eye[0] + fx, eye[1] + fy, eye[2] + fz,
            0f, 1f, 0f
        )
        MathUtils.multiplyMM(viewProj, proj, view)
        MathUtils.invertM(invViewProj, viewProj)
    }

    private fun autoFeed(dt: Float) {
        autoFoodTimer -= dt
        if (autoFoodTimer <= 0f) {
            autoFoodTimer = 30f + (elapsed * 7919f % 30f) // 30..60s, pseudo-random
            // Random screen point -> ray -> random depth.
            val nx = ((elapsed * 13.0f) % 1.7f) - 0.85f
            val ny = ((elapsed * 7.0f) % 1.4f) - 0.7f
            spawnFoodFromNdc(nx, ny)
        }
    }

    private fun handleTouch() {
        val nx = 2f * touchX / viewportW - 1f
        val ny = 1f - 2f * touchY / viewportH
        spawnFoodFromNdc(nx, ny)
    }

    private fun spawnFoodFromNdc(nx: Float, ny: Float) {
        val nearP = FloatArray(4); val farP = FloatArray(4)
        MathUtils.multiplyMV(nearP, invViewProj, floatArrayOf(nx, ny, -1f, 1f))
        MathUtils.multiplyMV(farP, invViewProj, floatArrayOf(nx, ny, 1f, 1f))
        if (nearP[3] == 0f || farP[3] == 0f) return
        for (i in 0 until 3) { nearP[i] /= nearP[3]; farP[i] /= farP[3] }
        var dx = farP[0] - nearP[0]; var dy = farP[1] - nearP[1]; var dz = farP[2] - nearP[2]
        val len = MathUtils.length(dx, dy, dz).coerceAtLeast(1e-4f)
        dx /= len; dy /= len; dz /= len
        fishSystem.spawnFoodOnRay(eye[0], eye[1], eye[2], dx, dy, dz)
    }

    // -----------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------

    private fun drawSky(s: AquariumSettings) {
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(skyProg)
        uMat(skyProg, "u_invViewProj", invViewProj)
        uVec3(skyProg, "u_camPos", eye[0], eye[1], eye[2])
        uVec3(skyProg, "u_skyTop", s.skyTopR, s.skyTopG, s.skyTopB)
        uVec3(skyProg, "u_skyHorizon", s.skyHorR, s.skyHorG, s.skyHorB)
        uVec3(skyProg, "u_waterTint", s.waterR, s.waterG, s.waterB)
        uF(skyProg, "u_time", elapsed)
        GLES30.glBindVertexArray(emptyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
    }

    private fun drawTerrainAndRoots(s: AquariumSettings) {
        GLES30.glUseProgram(terrainProg)
        uVec3(terrainProg, "u_lightDir", lightDir[0], lightDir[1], lightDir[2])
        uVec3(terrainProg, "u_camPos", eye[0], eye[1], eye[2])
        uVec3(terrainProg, "u_waterTint", s.waterR, s.waterG, s.waterB)
        uF(terrainProg, "u_fogDensity", s.fogDensity)
        uF(terrainProg, "u_time", elapsed)

        // Bed (model = identity).
        MathUtils.identity(model)
        uMat(terrainProg, "u_model", model)
        uMat(terrainProg, "u_mvp", viewProj)
        bedMesh.draw()

        // Trees with exposed roots.
        for (t in plantSystem.trees) {
            MathUtils.identity(model)
            MathUtils.translateM(model, t.x, 0f, t.z)
            MathUtils.rotateM(model, Math.toDegrees(t.yaw.toDouble()).toFloat(), 0f, 1f, 0f)
            MathUtils.scaleM(model, t.scale, t.scale, t.scale)
            MathUtils.multiplyMM(mvp, viewProj, model)
            uMat(terrainProg, "u_model", model)
            uMat(terrainProg, "u_mvp", mvp)
            treeMesh.draw()
        }
    }

    private fun drawSeaweed(s: AquariumSettings) {
        val count = plantSystem.seaweedCountInstances()
        if (count <= 0) return
        uploadInstances(plantInstanceVbo, plantSystem.seaweedData(), count, PlantSystem.PLANT_FLOATS)
        GLES30.glUseProgram(plantProg)
        // PLANT_VS uniforms.
        uMat(plantProg, "u_viewProj", viewProj)
        uF(plantProg, "u_time", elapsed)
        uF(plantProg, "u_sway", s.plantSway)
        // TERRAIN_FS uniforms.
        uVec3(plantProg, "u_lightDir", lightDir[0], lightDir[1], lightDir[2])
        uVec3(plantProg, "u_camPos", eye[0], eye[1], eye[2])
        uVec3(plantProg, "u_waterTint", s.waterR, s.waterG, s.waterB)
        uF(plantProg, "u_fogDensity", s.fogDensity)
        seaweedMesh.drawInstanced(count)
    }

    private fun drawFish(s: AquariumSettings) {
        GLES30.glUseProgram(fishProg)
        uMat(fishProg, "u_viewProj", viewProj)
        uF(fishProg, "u_time", elapsed)
        uF(fishProg, "u_wiggle", 6f * s.fishSpeed)
        uVec3(fishProg, "u_lightDir", lightDir[0], lightDir[1], lightDir[2])
        uVec3(fishProg, "u_camPos", eye[0], eye[1], eye[2])
        uVec3(fishProg, "u_waterTint", s.waterR, s.waterG, s.waterB)
        uF(fishProg, "u_fogDensity", s.fogDensity)
        for (t in fishSystem.activeTypes) {
            val mesh = fishMeshes[t] ?: continue
            val count = fishSystem.fillInstancesForType(t)
            if (count == 0) continue
            uploadInstances(fishInstanceVbo, fishSystem.instanceData(), count, FishSystem.INSTANCE_FLOATS)
            mesh.drawInstanced(count)
        }
    }

    private fun drawFood(s: AquariumSettings) {
        val count = fishSystem.fillFoodInstances()
        if (count <= 0) return
        uploadInstances(foodInstanceVbo, fishSystem.foodData(), count, FishSystem.INSTANCE_FLOATS)
        GLES30.glUseProgram(foodProg)
        uMat(foodProg, "u_viewProj", viewProj)
        uVec3(foodProg, "u_lightDir", lightDir[0], lightDir[1], lightDir[2])
        foodMesh.drawInstanced(count)
    }

    private fun drawWater(s: AquariumSettings) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(waterProg)
        uMat(waterProg, "u_viewProj", viewProj)
        uF(waterProg, "u_time", elapsed)
        uF(waterProg, "u_waveSpeed", s.waveSpeed)
        uF(waterProg, "u_choppiness", s.choppiness)
        uVec3(waterProg, "u_camPos", eye[0], eye[1], eye[2])
        uVec3(waterProg, "u_lightDir", lightDir[0], lightDir[1], lightDir[2])
        uVec3(waterProg, "u_skyHorizon", s.skyHorR, s.skyHorG, s.skyHorB)
        uVec3(waterProg, "u_waterTint", s.waterR, s.waterG, s.waterB)
        waterMesh.draw()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    // -----------------------------------------------------------------
    // GL helpers / resource management
    // -----------------------------------------------------------------

    private fun track(p: Int): Int { trackedProgs.add(p); return p }

    private fun genBuffer(): Int {
        val b = IntArray(1); GLES30.glGenBuffers(1, b, 0)
        trackedBuffers.add(b[0]); return b[0]
    }

    private fun uploadStatic(data: GeometryBuilders.MeshData): GpuMesh {
        val vao = IntArray(1); GLES30.glGenVertexArrays(1, vao, 0)
        val vbo = IntArray(1); GLES30.glGenBuffers(1, vbo, 0)
        val ibo = IntArray(1); GLES30.glGenBuffers(1, ibo, 0)
        trackedVaos.add(vao[0]); trackedBuffers.add(vbo[0]); trackedBuffers.add(ibo[0])

        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        val vBytes = data.vertices.capacity() * 4
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vBytes, data.vertices, GLES30.GL_STATIC_DRAW)
        val stride = GeometryBuilders.STRIDE_BYTES
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glEnableVertexAttribArray(2)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        val iBytes = data.indices.capacity() * 4
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, iBytes, data.indices, GLES30.GL_STATIC_DRAW)

        GLES30.glBindVertexArray(0)
        return GpuMesh(vao[0], data.indexCount)
    }

    private fun uploadInstances(vbo: Int, buffer: java.nio.FloatBuffer, count: Int, floatsPer: Int) {
        buffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * floatsPer * 4, buffer, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun uMat(prog: Int, name: String, m: FloatArray) {
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, name), 1, false, m, 0)
    }
    private fun uVec3(prog: Int, name: String, x: Float, y: Float, z: Float) {
        GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, name), x, y, z)
    }
    private fun uF(prog: Int, name: String, v: Float) {
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, name), v)
    }

    /** Frees all tracked GL objects. Call on the GL thread before teardown. */
    fun release() {
        if (trackedBuffers.isNotEmpty()) {
            val b = trackedBuffers.toIntArray(); GLES30.glDeleteBuffers(b.size, b, 0)
        }
        if (trackedVaos.isNotEmpty()) {
            val v = trackedVaos.toIntArray(); GLES30.glDeleteVertexArrays(v.size, v, 0)
        }
        for (p in trackedProgs) GLES30.glDeleteProgram(p)
        trackedBuffers.clear(); trackedVaos.clear(); trackedProgs.clear(); fishMeshes.clear()
    }

    /**
     * A VAO + element count. Instancing helpers attach a divisor-1 instance VBO
     * to this mesh's VAO so draws need only bind the VAO.
     */
    inner class GpuMesh(val vao: Int, val indexCount: Int) {

        fun draw() {
            GLES30.glBindVertexArray(vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
            GLES30.glBindVertexArray(0)
        }

        fun drawInstanced(count: Int) {
            GLES30.glBindVertexArray(vao)
            GLES30.glDrawElementsInstanced(
                GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0, count
            )
            GLES30.glBindVertexArray(0)
        }

        /** Fish/food instance attribs: pos(3),yaw(1),scale(1),color(3),phase(1),flash(1). */
        fun configureFishInstancing(instanceVbo: Int) {
            val stride = FishSystem.INSTANCE_FLOATS * 4
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
            attrib(3, 3, stride, 0)
            attrib(4, 1, stride, 12)
            attrib(5, 1, stride, 16)
            attrib(6, 3, stride, 20)
            attrib(7, 1, stride, 32)
            attrib(8, 1, stride, 36)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindVertexArray(0)
        }

        /** Plant instance attribs: pos(3),yaw(1),scale(1),phase(1). */
        fun configurePlantInstancing(instanceVbo: Int) {
            val stride = PlantSystem.PLANT_FLOATS * 4
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
            attrib(3, 3, stride, 0)
            attrib(4, 1, stride, 12)
            attrib(5, 1, stride, 16)
            attrib(6, 1, stride, 20)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindVertexArray(0)
        }

        private fun attrib(loc: Int, size: Int, stride: Int, offset: Int) {
            GLES30.glVertexAttribPointer(loc, size, GLES30.GL_FLOAT, false, stride, offset)
            GLES30.glEnableVertexAttribArray(loc)
            GLES30.glVertexAttribDivisor(loc, 1)
        }
    }
}
