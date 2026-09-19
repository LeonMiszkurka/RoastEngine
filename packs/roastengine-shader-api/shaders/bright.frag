#version 330 core
// RoastShaders - bright pass: keeps only what should glow.

in vec2 vUv;
uniform sampler2D uScene;
uniform float uThreshold;
out vec4 FragColor;

void main() {
    // Five-tap downsample: averaging neighbours stops single bright pixels flickering.
    vec2 texel = 1.0 / vec2(textureSize(uScene, 0));
    vec3 color = texture(uScene, vUv).rgb * 0.5
               + texture(uScene, vUv + texel * vec2( 1.0,  1.0)).rgb * 0.125
               + texture(uScene, vUv + texel * vec2(-1.0,  1.0)).rgb * 0.125
               + texture(uScene, vUv + texel * vec2( 1.0, -1.0)).rgb * 0.125
               + texture(uScene, vUv + texel * vec2(-1.0, -1.0)).rgb * 0.125;

    // Soft knee: a smooth ramp around the threshold instead of a hard cut.
    float brightness = max(color.r, max(color.g, color.b));
    float knee = 0.35;
    float soft = clamp(brightness - uThreshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-4);
    float contribution = max(soft, brightness - uThreshold) / max(brightness, 1e-4);
    FragColor = vec4(color * contribution, 1.0);
}
