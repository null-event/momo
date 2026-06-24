package com.nullevent.lakewallpaper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.PI

/**
 * Live wallpaper entry point. Each engine owns a [GLSurfaceView] (via the
 * classic holder-override trick), an OpenGL ES 3.0 context, a [LakeRenderer],
 * and an accelerometer listener that drives gyroscopic camera parallax.
 *
 * Rendering pauses in [Engine.onVisibilityChanged] when hidden and resumes when
 * visible; all sensors and GL resources are released in [Engine.onDestroy].
 */
class LakeWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = LakeEngine()

    inner class LakeEngine : Engine(), SensorEventListener {

        private lateinit var glView: WallpaperGLSurfaceView
        private val renderer = LakeRenderer()

        private var sensorManager: SensorManager? = null
        private var accelerometer: Sensor? = null

        // Low-pass filtered accelerometer for smooth parallax.
        private var filteredX = 0f
        private var filteredY = 0f

        private val maxPitchRad = (8.0 * PI / 180.0).toFloat()
        private val maxYawRad = (12.0 * PI / 180.0).toFloat()

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)

            glView = WallpaperGLSurfaceView(this@LakeWallpaperService)
            glView.setEGLContextClientVersion(3)
            glView.preserveEGLContextOnPause = true
            glView.setRenderer(renderer)
            glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                glView.onResume()
                accelerometer?.let {
                    sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
            } else {
                sensorManager?.unregisterListener(this)
                glView.onPause()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_POINTER_DOWN) {
                val x = event.x
                val y = event.y
                // Hand the touch to the renderer on the GL thread.
                glView.queueEvent { renderer.onTouchWorld(x, y) }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            glView.onPause()
        }

        override fun onDestroy() {
            super.onDestroy()
            sensorManager?.unregisterListener(this)
            sensorManager = null
            accelerometer = null
            // Release GL resources on the GL thread, then tear the view down.
            glView.queueEvent { renderer.release() }
            glView.onDestroyView()
        }

        // ---- SensorEventListener ----
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            // event.values: x (left/right tilt), y (up/down tilt), z (face).
            val alpha = 0.12f
            filteredX += (event.values[0] - filteredX) * alpha
            filteredY += (event.values[1] - filteredY) * alpha

            // Map tilt (-9.8..9.8) to clamped small camera angles.
            val yaw = (-filteredX / 9.81f) * maxYawRad
            val pitch = ((filteredY - 4.0f) / 9.81f) * maxPitchRad
            renderer.yaw = yaw.coerceIn(-maxYawRad, maxYawRad)
            renderer.pitch = pitch.coerceIn(-maxPitchRad, maxPitchRad)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }

        /**
         * GLSurfaceView whose holder is redirected to the wallpaper engine's
         * surface. This is the standard way to host GL inside a wallpaper.
         */
        inner class WallpaperGLSurfaceView(context: Context) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder = surfaceHolder

            fun onDestroyView() {
                super.onDetachedFromWindow()
            }
        }
    }
}
