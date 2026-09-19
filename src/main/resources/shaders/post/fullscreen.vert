#version 330 core

// A single triangle that covers the screen, generated from the vertex index - no vertex data.
out vec2 vUv;

void main() {
    vec2 position = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vUv = position;
    gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
}
