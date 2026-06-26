package com.nullevent.aquarium

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder

/**
 * Immutable snapshot of all user-controllable settings, read from
 * [SharedPreferences]. Defaults give a pleasing teal lake out of the box.
 */
data class AquariumSettings(
    val fovDeg: Float = 75f,
    // Fish.
    val varietyCount: Int = 4,
    val countPerVariety: Int = 12,
    val fishSpeed: Float = 1.0f,
    // Plants / roots.
    val seaweedCount: Int = 90,
    val treeCount: Int = 2,
    val rootsScale: Float = 1.0f,
    val plantSway: Float = 1.0f,
    // Water surface.
    val waveSpeed: Float = 1.0f,
    val choppiness: Float = 1.0f,
    val fogDensity: Float = 0.03f,
    // Water / lighting color.
    val waterR: Float = 0.10f, val waterG: Float = 0.34f, val waterB: Float = 0.42f,
    val skyTopR: Float = 0.18f, val skyTopG: Float = 0.42f, val skyTopB: Float = 0.82f,
    val skyHorR: Float = 0.58f, val skyHorG: Float = 0.74f, val skyHorB: Float = 0.86f
) {
    companion object {
        const val PREFS = "aquarium_settings"

        private fun waterPreset(key: String): FloatArray = when (key) {
            "blue" -> floatArrayOf(0.08f, 0.24f, 0.50f)
            "green" -> floatArrayOf(0.10f, 0.34f, 0.20f)
            "murky" -> floatArrayOf(0.16f, 0.20f, 0.12f)
            "tropical" -> floatArrayOf(0.06f, 0.46f, 0.50f)
            else -> floatArrayOf(0.10f, 0.34f, 0.42f) // teal
        }

        fun from(p: SharedPreferences): AquariumSettings {
            val water = waterPreset(p.getString("pref_water_color", "teal") ?: "teal")
            fun pct(key: String, def: Int) = p.getInt(key, def) / 50f // 0..2 around 1.0
            return AquariumSettings(
                varietyCount = (p.getString("pref_variety_count", "4") ?: "4").toIntOrNull() ?: 4,
                countPerVariety = (p.getString("pref_count_per_variety", "12") ?: "12").toIntOrNull() ?: 12,
                fishSpeed = pct("pref_fish_speed", 50).coerceIn(0.2f, 2.5f),
                seaweedCount = (p.getString("pref_seaweed_count", "90") ?: "90").toIntOrNull() ?: 90,
                treeCount = (p.getString("pref_tree_count", "2") ?: "2").toIntOrNull() ?: 2,
                rootsScale = pct("pref_roots_scale", 50).coerceIn(0.4f, 2.0f),
                plantSway = pct("pref_plant_sway", 50).coerceIn(0f, 2.5f),
                waveSpeed = pct("pref_wave_speed", 50).coerceIn(0f, 3f),
                choppiness = pct("pref_choppiness", 50).coerceIn(0.2f, 2.5f),
                fogDensity = (p.getInt("pref_murkiness", 50) / 1000f).coerceIn(0.005f, 0.12f),
                waterR = water[0], waterG = water[1], waterB = water[2]
            )
        }
    }
}

/**
 * The live wallpaper. Hosts a [GLSurfaceView] driven through the wallpaper's
 * [SurfaceHolder], wires the accelerometer for gyroscopic parallax, and pauses
 * rendering when not visible. All GL resources are released on destroy.
 */
class AquariumWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = AquariumEngine()

    inner class AquariumEngine : Engine(), SensorEventListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private var glView: WallpaperGLView? = null
        private val renderer = LakeRenderer()
        private lateinit var sensorManager: SensorManager
        private var accelerometer: Sensor? = null
        private lateinit var prefs: SharedPreferences
        private var visible = false

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)

            prefs = getSharedPreferences(AquariumSettings.PREFS, Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(this)
            renderer.applySettings(AquariumSettings.from(prefs))

            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            glView = WallpaperGLView().apply {
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                glView?.onResume()
                accelerometer?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
            } else {
                sensorManager.unregisterListener(this)
                glView?.onPause()   // pauses the GL render thread — saves battery
            }
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            if (event != null && event.action == MotionEvent.ACTION_DOWN) {
                renderer.queueTouch(event.x, event.y)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            super.onDestroy()
            sensorManager.unregisterListener(this)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            glView?.queueEvent { renderer.release() }
            glView?.onDestroyWallpaper()
            glView = null
        }

        // --- Sensors ---
        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                renderer.setTilt(event.values[0], event.values[1], event.values[2])
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        // --- Settings ---
        override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
            renderer.applySettings(AquariumSettings.from(prefs))
        }

        /**
         * A [GLSurfaceView] subclass that renders into the wallpaper's surface
         * instead of a View hierarchy by overriding [getHolder].
         */
        inner class WallpaperGLView : GLSurfaceView(this@AquariumWallpaperService) {
            override fun getHolder(): SurfaceHolder = surfaceHolder
            fun onDestroyWallpaper() {
                super.onDetachedFromWindow()
            }
        }
    }
}
