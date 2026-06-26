package com.nullevent.aquarium

import android.opengl.GLES30
import android.util.Log

/**
 * GLSL ES 3.00 shader sources (embedded as Kotlin strings) plus compile / link
 * helpers. No external assets — every program used by the aquarium lives here.
 *
 * Shared attribute layout for static meshes:
 *   location 0 = a_pos    (vec3)
 *   location 1 = a_normal (vec3)
 *   location 2 = a_color  (vec3)
 *
 * Per-instance attributes (fish):
 *   location 3 = i_pos   (vec3)
 *   location 4 = i_yaw   (float)
 *   location 5 = i_scale (float)
 *   location 6 = i_color (vec3)
 *   location 7 = i_phase (float)
 *   location 8 = i_flash (float)
 *
 * Per-instance attributes (plants / seaweed):
 *   location 3 = i_pos   (vec3)
 *   location 4 = i_yaw   (float)
 *   location 5 = i_scale (float)
 *   location 6 = i_phase (float)
 */
object ShaderUtils {

    private const val TAG = "Aquarium"

    // -----------------------------------------------------------------
    // Compilation helpers
    // -----------------------------------------------------------------

    fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log\n--- source ---\n$src")
        }
        return shader
    }

    fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("Program link failed: $log")
        }
        // Shaders may be flagged for deletion once linked.
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        Log.d(TAG, "Linked program $program")
        return program
    }

    // -----------------------------------------------------------------
    // Common GLSL chunk: underwater fog / murkiness (applied below y = 0).
    // -----------------------------------------------------------------

    private val FOG_CHUNK = """
        // Murkiness increases with view distance and with depth below the surface.
        vec3 applyUnderwaterFog(vec3 color, float viewDist, float worldY,
                                vec3 waterTint, float density) {
            float depth = max(0.0, -worldY);
            float fog = 1.0 - exp(-viewDist * density * (1.0 + depth * 0.04));
            fog = clamp(fog, 0.0, 1.0);
            // Above the surface keep colors crisp; below, sink toward the tint.
            float underwater = step(worldY, 0.0);
            return mix(color, waterTint, fog * underwater);
        }
    """

    // -----------------------------------------------------------------
    // Background / sky (fullscreen reconstruction via inverse view-proj)
    // -----------------------------------------------------------------

    val SKY_VS = """#version 300 es
        precision highp float;
        // Fullscreen triangle generated from gl_VertexID — no vertex buffer.
        out vec2 v_ndc;
        void main() {
            vec2 p = vec2((gl_VertexID == 2) ? 3.0 : -1.0,
                          (gl_VertexID == 1) ? 3.0 : -1.0);
            v_ndc = p;
            gl_Position = vec4(p, 0.999999, 1.0);
        }
    """

    val SKY_FS = """#version 300 es
        precision highp float;
        in vec2 v_ndc;
        out vec4 fragColor;
        uniform mat4 u_invViewProj;
        uniform vec3 u_camPos;
        uniform vec3 u_skyTop;
        uniform vec3 u_skyHorizon;
        uniform vec3 u_waterTint;
        uniform float u_time;
        void main() {
            // Reconstruct a world-space ray direction for this fragment.
            vec4 nearH = u_invViewProj * vec4(v_ndc, -1.0, 1.0);
            vec4 farH  = u_invViewProj * vec4(v_ndc,  1.0, 1.0);
            vec3 nearP = nearH.xyz / nearH.w;
            vec3 farP  = farH.xyz / farH.w;
            vec3 dir = normalize(farP - nearP);

            vec3 col;
            if (dir.y >= 0.0) {
                // Sky: gradient from horizon blue up to a deeper sky blue.
                float t = pow(clamp(dir.y, 0.0, 1.0), 0.55);
                col = mix(u_skyHorizon, u_skyTop, t);
            } else {
                // Below the horizon line we see into the murk: darken with depth.
                float t = clamp(-dir.y, 0.0, 1.0);
                vec3 deep = u_waterTint * 0.35;
                col = mix(u_waterTint, deep, t);
                // Faint caustic shimmer where the view skims the surface.
                float shimmer = 0.04 * (1.0 - t) *
                    sin(dir.x * 30.0 + u_time * 1.3) * sin(dir.z * 24.0 - u_time);
                col += shimmer;
            }
            fragColor = vec4(col, 1.0);
        }
    """

    // -----------------------------------------------------------------
    // Terrain / roots / plants share a vertex-colored, fogged program.
    // -----------------------------------------------------------------

    val TERRAIN_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 a_pos;
        layout(location = 1) in vec3 a_normal;
        layout(location = 2) in vec3 a_color;
        uniform mat4 u_mvp;
        uniform mat4 u_model;
        out vec3 v_normal;
        out vec3 v_color;
        out vec3 v_world;
        void main() {
            vec4 world = u_model * vec4(a_pos, 1.0);
            v_world = world.xyz;
            v_normal = mat3(u_model) * a_normal;
            v_color = a_color;
            gl_Position = u_mvp * vec4(a_pos, 1.0);
        }
    """

    val TERRAIN_FS = """#version 300 es
        precision highp float;
        in vec3 v_normal;
        in vec3 v_color;
        in vec3 v_world;
        out vec4 fragColor;
        uniform vec3 u_lightDir;
        uniform vec3 u_camPos;
        uniform vec3 u_waterTint;
        uniform float u_fogDensity;
        uniform float u_time;
        $FOG_CHUNK
        void main() {
            vec3 N = normalize(v_normal);
            vec3 L = normalize(-u_lightDir);
            float diff = max(dot(N, L), 0.0);
            float ambient = 0.35;
            vec3 lit = v_color * (ambient + diff * 0.8);

            // Animated caustic light pooling on the lakebed.
            float caustic = 0.5 + 0.5 * sin(v_world.x * 1.6 + u_time) *
                                  sin(v_world.z * 1.3 - u_time * 0.7);
            lit += v_color * caustic * 0.12;

            float viewDist = length(v_world - u_camPos);
            vec3 col = applyUnderwaterFog(lit, viewDist, v_world.y, u_waterTint, u_fogDensity);
            fragColor = vec4(col, 1.0);
        }
    """

    // -----------------------------------------------------------------
    // Instanced plants / seaweed: sway in the vertex shader.
    // -----------------------------------------------------------------

    val PLANT_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 a_pos;
        layout(location = 1) in vec3 a_normal;
        layout(location = 2) in vec3 a_color;
        layout(location = 3) in vec3 i_pos;
        layout(location = 4) in float i_yaw;
        layout(location = 5) in float i_scale;
        layout(location = 6) in float i_phase;
        uniform mat4 u_viewProj;
        uniform float u_time;
        uniform float u_sway;
        out vec3 v_normal;
        out vec3 v_color;
        out vec3 v_world;
        mat3 rotY(float a){ float c=cos(a), s=sin(a); return mat3(c,0.0,-s, 0.0,1.0,0.0, s,0.0,c); }
        void main() {
            vec3 local = a_pos * i_scale;
            // Taller parts of the plant sway more (a_pos.y is height above base).
            float h = max(a_pos.y, 0.0);
            float swayAmt = u_sway * h * 0.25;
            local.x += sin(u_time * 1.1 + i_phase + h) * swayAmt;
            local.z += cos(u_time * 0.9 + i_phase + h * 0.7) * swayAmt;
            vec3 world = rotY(i_yaw) * local + i_pos;
            v_world = world;
            v_normal = rotY(i_yaw) * a_normal;
            v_color = a_color;
            gl_Position = u_viewProj * vec4(world, 1.0);
        }
    """

    // Plants reuse TERRAIN_FS for shading/fog.

    // -----------------------------------------------------------------
    // Fish: instanced, Phong-lit, tail wiggle, eat-flash.
    // -----------------------------------------------------------------

    val FISH_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 a_pos;
        layout(location = 1) in vec3 a_normal;
        layout(location = 2) in vec3 a_color;
        layout(location = 3) in vec3 i_pos;
        layout(location = 4) in float i_yaw;
        layout(location = 5) in float i_scale;
        layout(location = 6) in vec3 i_color;
        layout(location = 7) in float i_phase;
        layout(location = 8) in float i_flash;
        uniform mat4 u_viewProj;
        uniform float u_time;
        uniform float u_wiggle;
        out vec3 v_normal;
        out vec3 v_color;
        out vec3 v_world;
        out float v_flash;
        mat3 rotY(float a){ float c=cos(a), s=sin(a); return mat3(c,0.0,-s, 0.0,1.0,0.0, s,0.0,c); }
        void main() {
            // Fish mesh points along +Z; tail is toward -Z.
            float tail = clamp(-a_pos.z, 0.0, 2.0);
            float wig = sin(a_pos.z * 2.2 + u_time * u_wiggle + i_phase) * tail * 0.12;
            // Eat-flash briefly inflates the fish.
            float pop = 1.0 + i_flash * 0.35;
            vec3 local = a_pos * i_scale * pop;
            local.x += wig * i_scale;
            vec3 world = rotY(i_yaw) * local + i_pos;
            v_world = world;
            v_normal = rotY(i_yaw) * a_normal;
            v_color = a_color * i_color;
            v_flash = i_flash;
            gl_Position = u_viewProj * vec4(world, 1.0);
        }
    """

    val FISH_FS = """#version 300 es
        precision highp float;
        in vec3 v_normal;
        in vec3 v_color;
        in vec3 v_world;
        in float v_flash;
        out vec4 fragColor;
        uniform vec3 u_lightDir;
        uniform vec3 u_camPos;
        uniform vec3 u_waterTint;
        uniform float u_fogDensity;
        uniform float u_time;
        $FOG_CHUNK
        void main() {
            vec3 N = normalize(v_normal);
            vec3 V = normalize(u_camPos - v_world);
            vec3 L = normalize(-u_lightDir);
            float diff = max(dot(N, L), 0.0);
            vec3 H = normalize(L + V);
            float spec = pow(max(dot(N, H), 0.0), 24.0);
            vec3 lit = v_color * (0.4 + diff * 0.75) + vec3(0.9) * spec * 0.35;
            lit = mix(lit, vec3(1.0), clamp(v_flash, 0.0, 1.0));

            float viewDist = length(v_world - u_camPos);
            vec3 col = applyUnderwaterFog(lit, viewDist, v_world.y, u_waterTint, u_fogDensity);
            fragColor = vec4(col, 1.0);
        }
    """

    // -----------------------------------------------------------------
    // Food particles: tiny instanced glowing spheres.
    // -----------------------------------------------------------------

    val FOOD_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 a_pos;
        layout(location = 1) in vec3 a_normal;
        layout(location = 2) in vec3 a_color;
        layout(location = 3) in vec3 i_pos;
        layout(location = 4) in float i_yaw;
        layout(location = 5) in float i_scale;
        layout(location = 6) in vec3 i_color;
        layout(location = 7) in float i_phase;
        layout(location = 8) in float i_flash;
        uniform mat4 u_viewProj;
        out vec3 v_normal;
        out vec3 v_color;
        void main() {
            vec3 world = a_pos * i_scale + i_pos;
            v_normal = a_normal;
            v_color = i_color;
            gl_Position = u_viewProj * vec4(world, 1.0);
        }
    """

    val FOOD_FS = """#version 300 es
        precision highp float;
        in vec3 v_normal;
        in vec3 v_color;
        out vec4 fragColor;
        uniform vec3 u_lightDir;
        void main() {
            float diff = max(dot(normalize(v_normal), normalize(-u_lightDir)), 0.0);
            vec3 col = v_color * (0.6 + diff * 0.6);
            fragColor = vec4(col, 1.0);
        }
    """

    // -----------------------------------------------------------------
    // Water surface: sine/cosine displacement + analytic normals.
    // -----------------------------------------------------------------

    val WATER_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 a_pos;     // a_pos.y unused (flat grid)
        layout(location = 1) in vec3 a_normal;   // unused; recomputed here
        layout(location = 2) in vec3 a_color;    // unused
        uniform mat4 u_viewProj;
        uniform float u_time;
        uniform float u_waveSpeed;
        uniform float u_choppiness;
        out vec3 v_world;
        out vec3 v_normal;
        out float v_height;

        // Sum of a few directional sine waves. Returns height; gradient via
        // analytic partial derivatives for correct lighting.
        float waveHeight(vec2 p, out vec2 grad) {
            float t = u_time * u_waveSpeed;
            float h = 0.0;
            grad = vec2(0.0);
            // (amplitude, freq, dirx, dirz, speed)
            const int N = 4;
            vec2 dirs[4] = vec2[4](vec2(1.0,0.2), vec2(-0.4,1.0), vec2(0.7,-0.7), vec2(0.1,1.0));
            float amps[4] = float[4](0.16, 0.10, 0.07, 0.05);
            float freqs[4] = float[4](0.6, 1.1, 1.7, 2.6);
            for (int i = 0; i < N; i++) {
                vec2 d = normalize(dirs[i]);
                float f = freqs[i];
                float a = amps[i] * u_choppiness;
                float phase = dot(d, p) * f + t * (1.0 + float(i) * 0.3);
                h += a * sin(phase);
                grad += d * (a * f * cos(phase));
            }
            return h;
        }

        void main() {
            vec2 grad;
            float h = waveHeight(a_pos.xz, grad);
            vec3 world = vec3(a_pos.x, h, a_pos.z);
            v_world = world;
            v_height = h;
            // Normal from gradient of the height field.
            v_normal = normalize(vec3(-grad.x, 1.0, -grad.y));
            gl_Position = u_viewProj * vec4(world, 1.0);
        }
    """

    val WATER_FS = """#version 300 es
        precision highp float;
        in vec3 v_world;
        in vec3 v_normal;
        in float v_height;
        out vec4 fragColor;
        uniform vec3 u_camPos;
        uniform vec3 u_lightDir;
        uniform vec3 u_skyHorizon;
        uniform vec3 u_waterTint;
        uniform float u_time;
        void main() {
            vec3 N = normalize(v_normal);
            vec3 V = normalize(u_camPos - v_world);
            float fresnel = pow(1.0 - max(dot(N, V), 0.0), 3.0);
            fresnel = clamp(fresnel + 0.05, 0.0, 1.0);

            // Reflection samples the sky tone; refraction looks into the murk.
            vec3 reflection = u_skyHorizon;
            vec3 refraction = u_waterTint * 0.5; // darkened underwater area
            vec3 col = mix(refraction, reflection, fresnel);

            // Specular glint from the sun.
            vec3 L = normalize(-u_lightDir);
            vec3 H = normalize(L + V);
            float spec = pow(max(dot(N, H), 0.0), 80.0);
            col += vec3(1.0) * spec * 0.6;

            // Foam line near wave crests.
            float foam = smoothstep(0.10, 0.16, v_height);
            col = mix(col, vec3(0.95, 0.98, 1.0), foam * 0.7);

            // Semi-transparent so the underwater scene shows through.
            float alpha = mix(0.55, 0.9, fresnel);
            fragColor = vec4(col, alpha);
        }
    """
}
