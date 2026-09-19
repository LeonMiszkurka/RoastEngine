#version 330 core

// Used only for images (mod icons). Shapes and text go through ui.frag, which declares no
// sampler at all - a sampler bound on every UI draw makes some drivers complain.
in vec4 vColor;
in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uTexture;

void main() {
    FragColor = vColor * texture(uTexture, vUv);
}
