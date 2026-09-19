#version 330 core

in vec3 vColor;
in vec3 vWorldPos;
in vec2 vUv;

uniform int   uGrid;         // 1 = draw procedural grid lines (ground platform)
uniform int   uHighlight;    // 1 = editor selection outline (drawn as wireframe)
uniform vec3  uSkyColor;     // used for distance fog so the platform fades into the sky
uniform vec3  uCameraPos;
uniform float uFogDistance;
/**
 * Fragments inside this sphere are discarded, hiding the player's own head without slicing
 * through the shoulders and arms the way a horizontal cut does. Radius 0 disables it.
 */
uniform vec3  uHeadCenter;
uniform float uHeadRadius;
uniform sampler2D uTexture;
uniform int uUseTexture;   // 1 = multiply the baked shading by the model's texture
uniform float uAlpha;      // material opacity: glass and other see-through surfaces
uniform vec3  uEmissive;   // light the surface gives off by itself (neon, screens)
/** How much emission shows: full in the HDR shader pipeline, toned down without it. */
uniform float uEmissiveStrength;

out vec4 FragColor;

// Anti-aliased grid line intensity for a given cell size (0 = no line, 1 = on line).
float gridLine(vec2 coord, float cellSize) {
    vec2 c = coord / cellSize;
    vec2 g = abs(fract(c - 0.5) - 0.5) / fwidth(c);
    return 1.0 - min(min(g.x, g.y), 1.0);
}

void main() {
    if (uHeadRadius > 0.0 && distance(vWorldPos, uHeadCenter) < uHeadRadius) {
        discard;
    }

    vec3 color = vColor;

    if (uUseTexture == 1) {
        vec4 sampled = texture(uTexture, vUv);
        if (sampled.a < 0.35) {
            discard;   // cut-out transparency (foliage, decals)
        }
        color *= sampled.rgb;
    }

    color += uEmissive * uEmissiveStrength;

    if (uHighlight == 1) {
        // Flat accent colour, no fog, so the selection stays visible at any distance.
        FragColor = vec4(0.90, 0.58, 0.22, 1.0);
        return;
    }

    if (uGrid == 1) {
        color = mix(color, color * 0.70, gridLine(vWorldPos.xz, 1.0));
        color = mix(color, color * 0.40, gridLine(vWorldPos.xz, 10.0));
    }

    float fog = clamp(length(vWorldPos - uCameraPos) / uFogDistance, 0.0, 1.0);
    fog *= 1.0 - clamp(max(uEmissive.r, max(uEmissive.g, uEmissive.b)), 0.0, 0.7);
    FragColor = vec4(mix(color, uSkyColor, fog * fog), uAlpha <= 0.0 ? 1.0 : uAlpha);
}
