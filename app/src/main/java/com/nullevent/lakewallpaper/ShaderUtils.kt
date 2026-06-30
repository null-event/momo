package com.nullevent.lakewallpaper

import android.opengl.GLES30
import android.util.Log

/**
 * Shader compilation/linking helpers plus every GLSL source string used by the
 * wallpaper. All shaders target OpenGL ES 3.0 (`#version 300 es`).
 *
 * Common attribute locations (shared across programs):
 *   layout 0 = a_position (vec3)
 *   layout 1 = a_normal   (vec3)
 *   layout 2 = a_color    (vec3)
 *   layout 3+ = per-instance data (program specific)
 */
object ShaderUtils {

    private const val TAG = "LakeShaders"

    fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            Log.e(TAG, "glCreateShader failed for type $type")
            return 0
        }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile error:\n" + GLES30.glGetShaderInfoLog(shader))
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vs == 0 || fs == 0) return 0

        val program = GLES30.glCreateProgram()
        if (program == 0) return 0
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link error:\n" + GLES30.glGetProgramInfoLog(program))
            GLES30.glDeleteProgram(program)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return 0
        }
        // Shaders are no longer needed once linked.
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }

    // =====================================================================
    // SKY  (fullscreen triangle, over-under vertical gradient)
    // =====================================================================
    val SKY_VS = """
        #version 300 es
        layout(location = 0) in vec3 a_position;
        out vec2 v_ndc;
        void main() {
            v_ndc = a_position.xy;
            gl_Position = vec4(a_position.xy, 0.999999, 1.0);
        }
    """.trimIndent()

    val SKY_FS = """
        #version 300 es
        precision mediump float;
        in vec2 v_ndc;
        out vec4 fragColor;
        void main() {
            float t = v_ndc.y;            // -1 (bottom) .. +1 (top)
            vec3 col;
            if (t >= 0.0) {
                vec3 horizon = vec3(0.72, 0.82, 0.88);
                vec3 sky     = vec3(0.25, 0.50, 0.85);
                col = mix(horizon, sky, clamp(t, 0.0, 1.0));
            } else {
                vec3 shallow = vec3(0.10, 0.34, 0.38);
                vec3 deep    = vec3(0.02, 0.10, 0.16);
                col = mix(shallow, deep, clamp(-t, 0.0, 1.0));
            }
            fragColor = vec4(col, 1.0);
        }
    """.trimIndent()

    // =====================================================================
    // TERRAIN / ROOTS / FOREST  (vertex colors + underwater murk)
    // =====================================================================
    val TERRAIN_VS = """
        #version 300 es
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec3 a_normal;
        layout(location = 2) in vec3 a_color;
        uniform mat4 u_mvp;
        uniform mat4 u_model;
        out vec3 v_normal;
        out vec3 v_color;
        out float v_worldY;
        void main() {
            vec4 world = u_model * vec4(a_position, 1.0);
            v_worldY = world.y;
            v_normal = mat3(u_model) * a_normal;
            v_color = a_color;
            gl_Position = u_mvp * vec4(a_position, 1.0);
        }
    """.trimIndent()

    val TERRAIN_FS = """
        #version 300 es
        precision mediump float;
        in vec3 v_normal;
        in vec3 v_color;
        in float v_worldY;
        uniform vec3 u_lightDir;
        out vec4 fragColor;
        void main() {
            vec3 n = normalize(v_normal);
            float diff = max(dot(n, normalize(-u_lightDir)), 0.0);
            float light = 0.35 + 0.65 * diff;
            vec3 col = v_color * light;
            // Underwater murk: fade toward a green-blue with depth below y = 0.
            if (v_worldY < 0.0) {
                float depth = clamp(-v_worldY / 14.0, 0.0, 1.0);
                vec3 murk = vec3(0.05, 0.20, 0.24);
                col = mix(col * vec3(0.55, 0.75, 0.80), murk, depth * 0.85);
            }
            fragColor = vec4(col, 1.0);
        }
    """.trimIndent()

    // =====================================================================
    // FISH  (instanced, Phong, per-instance color + eat flash scale)
    // =====================================================================
    val FISH_VS = """
        #version 300 es
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec3 a_normal;
        layout(location = 2) in vec3 a_color;
        layout(location = 3) in vec3 a_iOffset;
        layout(location = 4) in float a_iScale;
        layout(location = 5) in vec3 a_iColor;
        uniform mat4 u_viewProj;
        uniform float u_time;
        out vec3 v_normal;
        out vec3 v_color;
        out float v_worldY;
        void main() {
            // Gentle swim wiggle along the body (local Z is forward).
            float wiggle = sin(u_time * 4.0 + a_iOffset.x + a_position.z * 2.0) * 0.06;
            vec3 local = a_position;
            local.x += wiggle * (0.5 - a_position.z);
            local *= a_iScale;
            vec3 world = local + a_iOffset;
            v_worldY = world.y;
            v_normal = a_normal;
            v_color = a_color * a_iColor;
            gl_Position = u_viewProj * vec4(world, 1.0);
        }
    """.trimIndent()

    val FISH_FS = """
        #version 300 es
        precision mediump float;
        in vec3 v_normal;
        in vec3 v_color;
        in float v_worldY;
        uniform vec3 u_lightDir;
        out vec4 fragColor;
        void main() {
            vec3 n = normalize(v_normal);
            vec3 l = normalize(-u_lightDir);
            float diff = max(dot(n, l), 0.0);
            vec3 viewDir = vec3(0.0, 0.0, 1.0);
            vec3 h = normalize(l + viewDir);
            float spec = pow(max(dot(n, h), 0.0), 24.0);
            vec3 col = v_color * (0.30 + 0.70 * diff) + vec3(spec) * 0.4;
            // Murk with depth.
            float depth = clamp(-v_worldY / 14.0, 0.0, 1.0);
            col = mix(col, vec3(0.05, 0.20, 0.24), depth * 0.6);
            fragColor = vec4(col, 1.0);
        }
    """.trimIndent()

    // =====================================================================
    // WATER  (wave displacement + reflection/refraction + foam)
    // =====================================================================
    val WATER_VS = """
        #version 300 es
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec3 a_normal;
        layout(location = 2) in vec3 a_color;
        uniform mat4 u_mvp;
        uniform float u_time;
        out vec3 v_normal;
        out float v_height;
        out vec3 v_worldPos;

        float waveHeight(vec2 p, float t) {
            float h = 0.0;
            h += sin(p.x * 0.6 + t * 1.3) * 0.18;
            h += cos(p.y * 0.9 - t * 1.7) * 0.10;
            h += sin((p.x + p.y) * 0.4 + t * 0.9) * 0.08;
            return h;
        }

        void main() {
            vec3 pos = a_position;
            float t = u_time;
            pos.y = waveHeight(pos.xz, t);

            // Recompute normal from finite differences of the wave field.
            float e = 0.25;
            float hL = waveHeight(pos.xz - vec2(e, 0.0), t);
            float hR = waveHeight(pos.xz + vec2(e, 0.0), t);
            float hD = waveHeight(pos.xz - vec2(0.0, e), t);
            float hU = waveHeight(pos.xz + vec2(0.0, e), t);
            vec3 n = normalize(vec3(hL - hR, 2.0 * e, hD - hU));

            v_normal = n;
            v_height = pos.y;
            v_worldPos = pos;
            gl_Position = u_mvp * vec4(pos, 1.0);
        }
    """.trimIndent()

    val WATER_FS = """
        #version 300 es
        precision mediump float;
        in vec3 v_normal;
        in float v_height;
        in vec3 v_worldPos;
        uniform vec3 u_lightDir;
        uniform float u_time;
        out vec4 fragColor;
        void main() {
            vec3 n = normalize(v_normal);
            vec3 viewDir = normalize(vec3(0.0, 0.3, 1.0));

            // Reflection color approximates the sky gradient.
            float upness = clamp(n.y, 0.0, 1.0);
            vec3 reflectColor = mix(vec3(0.30, 0.55, 0.85), vec3(0.72, 0.82, 0.88), 1.0 - upness);

            // Refraction color: darkened murky underwater tint.
            vec3 refractColor = vec3(0.04, 0.18, 0.22);

            // Fresnel-ish blend: more reflection at grazing angles.
            float fresnel = pow(1.0 - max(dot(n, viewDir), 0.0), 3.0);
            fresnel = clamp(0.15 + fresnel * 0.85, 0.0, 1.0);
            vec3 col = mix(refractColor, reflectColor, fresnel);

            // Specular sun glint.
            vec3 l = normalize(-u_lightDir);
            vec3 h = normalize(l + viewDir);
            float spec = pow(max(dot(n, h), 0.0), 80.0);
            col += vec3(spec) * 0.6;

            // Foam near wave crests.
            float foam = smoothstep(0.20, 0.30, v_height);
            col = mix(col, vec3(0.92, 0.96, 1.0), foam * 0.7);

            float alpha = 0.78 + foam * 0.2;
            fragColor = vec4(col, alpha);
        }
    """.trimIndent()

    // =====================================================================
    // BIRD  (instanced, unlit, wing-flap animation)
    // =====================================================================
    val BIRD_VS = """
        #version 300 es
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec3 a_normal;
        layout(location = 2) in vec3 a_color;
        layout(location = 3) in vec3 a_iOffset;
        layout(location = 4) in float a_iYaw;
        layout(location = 5) in float a_iPhase;
        uniform mat4 u_viewProj;
        uniform float u_time;
        out vec3 v_color;
        void main() {
            vec3 local = a_position;
            // Wing flap: vertices farther along local Z (the wings) move in Y.
            float flap = sin(u_time * 9.0 + a_iPhase) * 0.5;
            local.y += abs(local.z) * flap;

            // Yaw rotation so the bird points along its travel direction.
            float c = cos(a_iYaw);
            float s = sin(a_iYaw);
            vec3 rotated = vec3(
                local.x * c - local.z * s,
                local.y,
                local.x * s + local.z * c
            );
            vec3 world = rotated + a_iOffset;
            v_color = a_color;
            gl_Position = u_viewProj * vec4(world, 1.0);
        }
    """.trimIndent()

    val BIRD_FS = """
        #version 300 es
        precision mediump float;
        in vec3 v_color;
        out vec4 fragColor;
        void main() {
            fragColor = vec4(v_color, 1.0);
        }
    """.trimIndent()
}
