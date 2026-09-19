#version 330 core

layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec3 aShade;
layout (location = 2) in vec2 aUv;
layout (location = 3) in vec4 aJoints;   // bone indices, stored as floats
layout (location = 4) in vec4 aWeights;  // influence of each bone, summing to 1

const int MAX_BONES = 180;

uniform mat4 uProjection;
uniform mat4 uView;
uniform mat4 uModel;
uniform mat4 uBones[MAX_BONES];

out vec3 vColor;
out vec3 vWorldPos;
out vec2 vUv;

void main() {
    // Linear blend skinning: sum the bone transforms weighted per vertex.
    mat4 skin = uBones[int(aJoints.x)] * aWeights.x
              + uBones[int(aJoints.y)] * aWeights.y
              + uBones[int(aJoints.z)] * aWeights.z
              + uBones[int(aJoints.w)] * aWeights.w;

    vec4 world = uModel * skin * vec4(aPosition, 1.0);
    vWorldPos = world.xyz;
    vColor = aShade;
    vUv = aUv;
    gl_Position = uProjection * uView * world;
}
