#version 330 core

// Signed distance shapes for the Dynamic Island: one rounded box, or two rounded boxes melted together.
// Everything is in screen pixels, so the edges get exactly one pixel of anti-aliasing at any size.
//
// v_Box    = half width, half height, corner radius, mode
// v_Box2   = offset of the second box from the first, half width, half height (no second box when the width is 0)
// v_Box3   = the same for a third box, v_Params3 = corner radius and melt distance of the third box
// v_Params = corner radius of the second box, melt distance, size, top bias
//
// Modes: 0 = fill with a vertical gradient and a border, 1 = fill with a horizontal gradient,
//        2 = shadow (size is the blur sigma), 3 = glow (size is how far it reaches),
//        4 = arc with round ends (half width = radius, half height = half the thickness, corner radius = sweep,
//            top bias = start angle, all in radians)

in vec2 v_Local;
in vec4 v_Box;
in vec4 v_Box2;
in vec4 v_Params;
in vec4 v_Box3;
in vec4 v_Params3;
in vec4 v_ColorA;
in vec4 v_ColorB;
in vec4 v_ColorC;

out vec4 color;

float roundBox(vec2 p, vec2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

// Direction the edge of a rounded box faces at the closest point
vec2 roundBoxNormal(vec2 p, vec2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + radius;
    vec2 n = q.x > 0.0 && q.y > 0.0 ? normalize(q) : (q.x > q.y ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
    return n * vec2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
}

// Smooth minimum, joins the two shapes with a soft neck instead of a sharp crease
float smoothMin(float a, float b, float k) {
    if (k <= 0.0) return min(a, b);
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * k * 0.25;
}

float arcDistance(vec2 p, float radius, float halfThickness, float start, float sweep) {
    if (sweep >= 6.2831) return abs(length(p) - radius) - halfThickness;

    float angle = mod(atan(p.y, p.x) - start, 6.28318531);
    if (angle <= sweep) return abs(length(p) - radius) - halfThickness;

    vec2 a = radius * vec2(cos(start), sin(start));
    vec2 b = radius * vec2(cos(start + sweep), sin(start + sweep));
    return min(length(p - a), length(p - b)) - halfThickness;
}

float erfApprox(float x) {
    float x2 = x * x;
    float a = 0.147;
    float e = 1.0 - exp(-x2 * (1.2732395 + a * x2) / (1.0 + a * x2));
    return sign(x) * sqrt(max(e, 0.0));
}

// Interleaved gradient noise, hides the banding of dark gradients and soft shadows
float noise(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void main() {
    int mode = int(v_Box.w + 0.5);
    bool second = v_Box2.z > 0.0;

    float d1 = mode == 4
        ? arcDistance(v_Local, v_Box.x, v_Box.y, v_Params.w, v_Box.z)
        : roundBox(v_Local, v_Box.xy, v_Box.z);
    float d = d1;
    float d2 = 1e6;

    if (second) {
        d2 = roundBox(v_Local - v_Box2.xy, v_Box2.zw, v_Params.x);

        // Only melt where the two edges face different ways (a neck or a notch). Where they lie on top of each
        // other, melting would push the edge outwards.
        vec2 n1 = roundBoxNormal(v_Local, v_Box.xy, v_Box.z);
        vec2 n2 = roundBoxNormal(v_Local - v_Box2.xy, v_Box2.zw, v_Params.x);
        float facing = clamp((1.0 - dot(n1, n2)) * 1.5, 0.0, 1.0);

        d = smoothMin(d1, d2, v_Params.y * facing);
    }

    float d3 = 1e6;
    bool third = v_Box3.z > 0.0;

    if (third) {
        d3 = roundBox(v_Local - v_Box3.xy, v_Box3.zw, v_Params3.x);

        vec2 m1 = roundBoxNormal(v_Local, v_Box.xy, v_Box.z);
        vec2 m3 = roundBoxNormal(v_Local - v_Box3.xy, v_Box3.zw, v_Params3.x);
        float facing3 = clamp((1.0 - dot(m1, m3)) * 1.5, 0.0, 1.0);

        d = smoothMin(d, d3, v_Params3.y * facing3);
    }

    // Width of one pixel in distance units, so the edge is always one pixel wide
    float px = max(length(vec2(dFdx(d), dFdy(d))), 0.0001);
    float dither = noise(gl_FragCoord.xy) - 0.5;

    vec4 result;

    if (mode == 4) {
        float coverage = clamp(0.5 - d / px, 0.0, 1.0);
        result = vec4(v_ColorA.rgb + dither / 255.0, v_ColorA.a * coverage);
    } else if (mode <= 1) {
        float coverage = clamp(0.5 - d / px, 0.0, 1.0);

        float t = mode == 0
            ? clamp(v_Local.y / (2.0 * v_Box.y) + 0.5, 0.0, 1.0)
            : clamp(v_Local.x / (2.0 * v_Box.x) + 0.5, 0.0, 1.0);

        result = mix(v_ColorA, v_ColorB, t);

        // Border along the inside of the edge, brighter at the top like light catching it
        float borderWidth = v_Params.z;

        if (mode == 0 && borderWidth > 0.0 && v_ColorC.a > 0.0) {
            // The border follows whichever box is closest, so each pill has its own bright top
            float centerY = v_Local.y;
            float halfHeight = v_Box.y;

            if (second && d2 < d1 && (!third || d2 <= d3)) {
                centerY = v_Local.y - v_Box2.y;
                halfHeight = v_Box2.w;
            } else if (third && d3 < d1) {
                centerY = v_Local.y - v_Box3.y;
                halfHeight = v_Box3.w;
            }

            float top = clamp(-centerY / max(halfHeight, 1.0), 0.0, 1.0);
            float bias = mix(1.0, 0.15 + 0.85 * top, v_Params.w);

            float band = clamp(0.5 - d / px, 0.0, 1.0) - clamp(0.5 - (d + borderWidth) / px, 0.0, 1.0);
            float b = band * v_ColorC.a * bias;

            vec3 rgb = mix(result.rgb, v_ColorC.rgb, b / max(result.a + b * (1.0 - result.a), 0.0001));
            result = vec4(rgb, result.a + b * (1.0 - result.a));
        }

        result.rgb += dither / 255.0;
        result.a *= coverage;
    } else if (mode == 2) {
        float sigma = max(v_Params.z, 0.5);
        float a = 0.5 - 0.5 * erfApprox(d / (sigma * 1.41421356));

        result = vec4(v_ColorA.rgb, v_ColorA.a * a + dither / 255.0);
    } else {
        float reach = max(v_Params.z, 1.0);
        float t = clamp(d / reach, 0.0, 1.0);
        float a = (exp(-4.0 * t) - 0.01831564) / 0.98168436;

        result = vec4(v_ColorA.rgb, v_ColorA.a * a * a + dither / 255.0);
    }

    if (result.a <= 0.002) discard;

    color = vec4(result.rgb, clamp(result.a, 0.0, 1.0));
}
