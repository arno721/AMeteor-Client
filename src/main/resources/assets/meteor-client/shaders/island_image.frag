#version 330 core

// An image cut to a rounded box with a smooth edge, for album covers.
// v_Box = half width, half height, corner radius, rotation in radians (only makes sense for circles)

in vec2 v_TexCoord;
in vec2 v_Local;
in vec4 v_Box;
in vec4 v_Color;

out vec4 color;

uniform sampler2D u_Texture;

float roundBox(vec2 p, vec2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

void main() {
    float d = roundBox(v_Local, v_Box.xy, v_Box.z);
    float px = max(length(vec2(dFdx(d), dFdy(d))), 0.0001);
    float coverage = clamp(0.5 - d / px, 0.0, 1.0);

    float c = cos(v_Box.w), s = sin(v_Box.w);
    vec2 uv = v_TexCoord - 0.5;
    uv = vec2(c * uv.x - s * uv.y, s * uv.x + c * uv.y) + 0.5;
    uv = clamp(uv, vec2(0.002), vec2(0.998));

    vec4 texel = texture(u_Texture, uv);
    float a = texel.a * v_Color.a * coverage;
    if (a <= 0.002) discard;

    color = vec4(texel.rgb * v_Color.rgb, a);
}
