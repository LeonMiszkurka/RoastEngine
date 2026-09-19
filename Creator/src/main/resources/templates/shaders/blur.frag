#version 330 core
// RoastShaders - one direction of a 9-tap Gaussian; the engine alternates directions.

in vec2 vUv;
uniform sampler2D uImage;
uniform vec2 uDirection;   // texel step, already scaled by the engine
out vec4 FragColor;

const float WEIGHTS[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);

void main() {
    vec3 sum = texture(uImage, vUv).rgb * WEIGHTS[0];
    for (int i = 1; i < 5; i++) {
        vec2 offset = uDirection * float(i);
        sum += texture(uImage, vUv + offset).rgb * WEIGHTS[i];
        sum += texture(uImage, vUv - offset).rgb * WEIGHTS[i];
    }
    FragColor = vec4(sum, 1.0);
}
