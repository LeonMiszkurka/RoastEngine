#version 330 core
// RoastShaders - final image.
//
// Ambient occlusion from the depth buffer, bloom, filmic (ACES) tonemapping, split-tone colour
// grading, vignette and film grain. When the player is drunk the image wobbles and the colour
// channels drift apart.

in vec2 vUv;

uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform sampler2D uDepth;
uniform vec2  uResolution;
uniform float uNear;
uniform float uFar;
uniform float uTime;
uniform mat4  uInverseProjection;
uniform float uDrunk;          // 0..1, supplied by the game

// Tuning, from pack.json
uniform float exposure;
uniform float bloomStrength;
uniform float aoStrength;
uniform float aoRadius;
uniform float saturation;
uniform float contrast;
uniform float warmth;
uniform float vignette;
uniform float grain;

out vec4 FragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

// View-space position of a pixel, rebuilt from the depth buffer.
vec3 viewPosition(vec2 uv) {
    float depth = texture(uDepth, uv).r;
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = uInverseProjection * clip;
    return view.xyz / view.w;
}

// Screen-space ambient occlusion: how much nearby geometry hides this point from open sky.
float ambientOcclusion(vec2 uv) {
    if (texture(uDepth, uv).r >= 0.9999) {
        return 1.0; // sky
    }
    vec3 position = viewPosition(uv);
    vec3 normal = normalize(cross(dFdx(position), dFdy(position)));

    const int SAMPLES = 14;
    float radiusPixels = aoRadius / max(-position.z, 0.2) * uResolution.y * 0.6;
    radiusPixels = clamp(radiusPixels, 2.0, 90.0);
    float spin = hash(uv * uResolution) * 6.2831853;

    float occlusion = 0.0;
    for (int i = 0; i < SAMPLES; i++) {
        // Golden-angle spiral, rotated per pixel to trade banding for fine noise.
        float angle = float(i) * 2.3999632 + spin;
        float radius = sqrt((float(i) + 0.5) / float(SAMPLES)) * radiusPixels;
        vec2 offset = vec2(cos(angle), sin(angle)) * radius / uResolution;

        vec3 samplePosition = viewPosition(uv + offset);
        vec3 toSample = samplePosition - position;
        float distance = length(toSample);
        if (distance < 1e-4) {
            continue;
        }
        float facing = max(dot(normal, toSample / distance) - 0.08, 0.0);
        float falloff = 1.0 - smoothstep(aoRadius * 0.5, aoRadius * 2.0, distance);
        occlusion += facing * falloff;
    }
    return clamp(1.0 - aoStrength * occlusion / float(SAMPLES), 0.0, 1.0);
}

vec3 aces(vec3 x) {
    const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

void main() {
    vec2 uv = vUv;

    // Drunk: the world sways and the colour channels drift.
    if (uDrunk > 0.0) {
        uv += vec2(sin(uv.y * 9.0 + uTime * 1.9), cos(uv.x * 8.0 + uTime * 1.5)) * 0.005 * uDrunk;
    }
    // A whisper of lens fringing when sober; it grows as the player drinks.
    float aberration = 0.0004 + 0.009 * uDrunk;
    vec3 color = vec3(
        texture(uScene, uv + vec2(aberration, 0.0)).r,
        texture(uScene, uv).g,
        texture(uScene, uv - vec2(aberration, 0.0)).b);

    // Materials are authored as display colours: work in linear light, go back at the end.
    color = pow(max(color, 0.0), vec3(2.2));
    color *= ambientOcclusion(uv);
    color += pow(max(texture(uBloom, uv).rgb, 0.0), vec3(2.2)) * bloomStrength;

    color = aces(color * exposure * 1.6);
    color = pow(color, vec3(1.0 / 2.2));

    // Grading: cool shadows, warm highlights, a touch more contrast and colour.
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    vec3 shadowTint = vec3(0.92, 0.97, 1.08);
    vec3 highlightTint = vec3(1.08, 1.0, 0.9);
    color *= mix(vec3(1.0), mix(shadowTint, highlightTint, smoothstep(0.15, 0.75, luma)), warmth);
    color = mix(vec3(luma), color, saturation);
    color = (color - 0.5) * contrast + 0.5;

    // Vignette and grain.
    vec2 centred = vUv - 0.5;
    color *= 1.0 - vignette * dot(centred, centred) * 1.6;
    color += (hash(vUv * uResolution + fract(uTime) * 91.0) - 0.5) * grain;

    FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
