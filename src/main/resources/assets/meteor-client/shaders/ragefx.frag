#version 330 core

// Effects of the Crossbow Ragebot, drawn on quads in the world. Every effect is worked out per pixel from the position on
// the quad, so the lines are as thin and the glow as soft as wanted at any distance. The result is added to the picture.
//
// v_Uv = position on the quad, from -1 to 1 (for ribbons: distance along the ribbon, and from -1 to 1 across it)
// v_P1 = effect, progress, a, b
// v_P2 = time in seconds, intensity, glow, speed
//
// Effects: 0 = reticle (a = hit chance), 1 = ground radar (a = hit chance), 2 = beam (uv.y is the height from 0 to 1),
//          3 = ribbon (a = fraction along the path, b = 1 when light flows along it, progress = how visible it is),
//          4 = burst (progress = age from 0 to 1, a = 1 for a hit)

in vec2 v_Uv;
in vec4 v_P1;
in vec4 v_P2;
in vec4 v_ColorA;
in vec4 v_ColorB;

out vec4 color;

const float PI = 3.14159265;
const float TAU = 6.28318531;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

mat2 rot(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c, -s, s, c);
}

// 1 on the line, 0 away from it, d = distance to the line, w = half width
float stroke(float d, float w, float px) {
    return 1.0 - smoothstep(w - px, w + px, d);
}

float glowAt(float d, float k) {
    return exp(-max(d, 0.0) * k);
}

// Angle from the top, clockwise, from 0 to TAU
float angleFromTop(vec2 p) {
    return mod(atan(p.x, p.y), TAU);
}

// Dashes round a circle
float dashes(vec2 p, float n, float duty, float phase) {
    float s = fract((atan(p.y, p.x) + phase) / TAU * n);
    return smoothstep(0.0, 0.05, s) * (1.0 - smoothstep(duty, duty + 0.05, s));
}

vec3 reticle(vec2 p, float t, float lock, float chance, vec3 A, vec3 B, float px, float glow, float speed) {
    float r = length(p);
    float ease = 1.0 - pow(1.0 - clamp(lock, 0.0, 1.0), 3.0);
    float R = mix(0.99, 0.64, ease);

    // The outer ring closes in on the target when it is locked
    float ring0 = stroke(abs(r - R), 0.012, px);
    float ring1 = stroke(abs(r - (R - 0.085)), 0.02, px) * dashes(p, 28.0, 0.5, t * 0.9 * speed);
    float ring2 = stroke(abs(r - (R - 0.17)), 0.008, px) * dashes(p, 6.0, 0.35, -t * 1.7 * speed);

    // Corners
    vec2 q = rot(ease * PI * 0.5 * 0.0) * p;
    float S = R * 0.86;
    float box = abs(max(abs(q.x), abs(q.y)) - S);
    float arm = step(S * 0.55, min(abs(q.x), abs(q.y)));
    float corners = stroke(box, 0.022, px) * arm;

    // Ticks on the four sides and the centre
    float ticks = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 u = rot(float(i) * PI * 0.5) * p;
        ticks += stroke(abs(u.x), 0.012, px) * step(R * 0.42, u.y) * step(u.y, R * 0.62);
    }

    float centre = stroke(r, 0.028 + 0.012 * sin(t * 4.0 * speed), px);
    float cross = stroke(min(abs(p.x), abs(p.y)), 0.008, px) * step(0.07, r) * step(r, 0.16);

    // The hit chance as an arc round the outside
    float track = stroke(abs(r - (R + 0.07)), 0.01, px) * 0.25;
    float arc = stroke(abs(r - (R + 0.07)), 0.022, px) * step(angleFromTop(p), clamp(chance, 0.0, 1.0) * TAU);

    // A pulse that leaves the ring
    float wave = fract(t * 0.6 * speed);
    float pulse = stroke(abs(r - (R + wave * 0.28)), 0.01, px) * (1.0 - wave) * (0.4 + 0.6 * ease);

    float halo = glowAt(abs(r - R), 9.0) * 0.18 + glowAt(r, 6.0) * 0.1 * ease;

    vec3 c = A * (corners + ring1 + arc) + B * (ring0 + ring2 + ticks + centre + cross + track + pulse) + A * halo * glow;
    return c;
}

vec3 radar(vec2 p, float t, float chance, vec3 A, vec3 B, float px, float glow, float speed) {
    float r = length(p);
    if (r > 1.0) return vec3(0.0);

    float edge = 1.0 - smoothstep(0.93, 1.0, r);
    float fill = (1.0 - smoothstep(0.0, 1.0, r)) * 0.07;

    float outer = stroke(abs(r - 0.94), 0.012, px);
    float mid = stroke(abs(r - 0.7), 0.014, px) * dashes(p, 40.0, 0.5, -t * 0.8 * speed);
    float inner = stroke(abs(r - 0.42), 0.008, px);

    // Degree marks, longer every sixth one
    float a = atan(p.y, p.x);
    float mark = fract(a / TAU * 72.0);
    float longMark = fract(a / TAU * 12.0);
    float tick = step(0.9, r) * step(r, 0.94 + 0.0) * stroke(abs(mark - 0.5) * 0.08, 0.003, px * 0.2) +
        step(0.86, r) * step(r, 0.94) * stroke(abs(longMark - 0.5) * 0.3, 0.006, px * 0.3);

    // The sweep
    float sweepAngle = mod(a - t * 1.6 * speed, TAU);
    float sweep = exp(-sweepAngle * 2.6) * step(r, 0.92) * 0.55;
    float sweepLine = stroke(min(sweepAngle, TAU - sweepAngle) * r, 0.008, px) * step(r, 0.92);

    // The wave
    float wave = fract(t * 0.45 * speed);
    float pulse = stroke(abs(r - wave * 0.94), 0.012, px) * (1.0 - wave);

    float track = stroke(abs(r - 0.84), 0.008, px) * 0.25;
    float arc = stroke(abs(r - 0.84), 0.02, px) * step(angleFromTop(p), clamp(chance, 0.0, 1.0) * TAU);

    vec3 c = A * (fill + sweep * 0.9 + sweepLine * 0.7 + arc) + B * (outer + mid + inner + tick * 0.7 + track + pulse * 0.8);
    c += A * glowAt(abs(r - 0.94), 12.0) * 0.14 * glow;
    return c * edge;
}

vec3 beam(vec2 uv, float t, vec3 A, vec3 B, float glow, float speed) {
    float x = abs(uv.x);
    float h = clamp(uv.y, 0.0, 1.0);

    float body = (1.0 - x * x * x) * pow(1.0 - h, 1.4);
    float core = exp(-x * 5.0) * pow(1.0 - h, 2.0);
    float scan = 0.55 + 0.45 * sin(uv.y * 70.0 - t * 4.0 * speed);
    float grain = 0.85 + 0.15 * hash(floor(uv * vec2(24.0, 90.0)) + floor(t * 12.0));

    vec3 tint = mix(A, B, h);
    return tint * (body * 0.22 * scan * grain + core * 0.55 * glow);
}

vec3 ribbon(vec2 uv, float t, float visible, float along, float flow, vec3 A, vec3 B, float px, float glow, float speed) {
    float d = abs(uv.y);
    vec3 tint = mix(A, B, clamp(along, 0.0, 1.0));

    float core = stroke(d, 0.14, px * 1.5);
    float halo = glowAt(d, 2.4) * 0.34 * glow;
    float tight = glowAt(d, 7.0) * 0.5;

    // Packets of light that run along it
    float packets = 0.0;
    if (flow > 0.5) {
        float s = fract(uv.x * 0.22 - t * 1.25 * speed);
        packets = smoothstep(0.0, 0.015, s) * (1.0 - smoothstep(0.0, 0.2, s)) * exp(-d * 2.4);

        float s2 = fract(uv.x * 0.22 - t * 1.25 * speed + 0.5);
        packets += 0.6 * smoothstep(0.0, 0.015, s2) * (1.0 - smoothstep(0.0, 0.12, s2)) * exp(-d * 2.4);
    }

    float taper = 1.0 - smoothstep(0.78, 1.0, d);
    vec3 c = tint * (core * 0.9 + tight + halo) + vec3(1.0) * packets * 0.9;
    return c * taper * visible;
}

vec3 burst(vec2 p, float age, float hit, vec3 A, vec3 B, float px, float glow) {
    float r = length(p);
    float e = 1.0 - pow(1.0 - clamp(age, 0.0, 1.0), 3.0);
    float fade = (1.0 - age) * (1.0 - age);

    float R = e * (0.55 + 0.4 * hit);
    float w = 0.05 * (1.0 - age) + 0.01;

    float ring = stroke(abs(r - R), w, px) * fade;
    float ring2 = stroke(abs(r - R * 0.62), w * 0.6, px) * fade * 0.6;
    float halo = glowAt(abs(r - R), 10.0) * 0.35 * fade * glow;

    // Rays
    float a = atan(p.y, p.x) + age * 1.4;
    float n = mix(8.0, 16.0, hit);
    float sector = abs(fract(a / TAU * n) - 0.5);
    float rays = stroke(sector * r * 2.0, 0.012 + 0.02 * (1.0 - age), px) * step(R * 0.45, r) * step(r, R * 1.15) * fade * hit;

    float flash = glowAt(r, 7.0) * pow(1.0 - age, 4.0) * mix(0.3, 1.0, hit);

    vec3 c = A * (ring + rays + halo) + B * (ring2 + flash * 1.4);
    return c;
}

void main() {
    int effect = int(v_P1.x + 0.5);
    float t = v_P2.x;
    float intensity = v_P2.y;
    float glow = v_P2.z;
    float speed = v_P2.w;
    float px = max(length(fwidth(v_Uv)), 1e-4) * 1.2;

    vec3 A = v_ColorA.rgb;
    vec3 B = v_ColorB.rgb;
    vec3 c = vec3(0.0);

    if (effect == 0) c = reticle(v_Uv, t, v_P1.y, v_P1.z, A, B, px, glow, speed);
    else if (effect == 1) c = radar(v_Uv, t, v_P1.z, A, B, px, glow, speed);
    else if (effect == 2) c = beam(v_Uv, t, A, B, glow, speed);
    else if (effect == 3) c = ribbon(v_Uv, t, v_P1.y, v_P1.z, v_P1.w, A, B, max(fwidth(v_Uv.y), 1e-4) * 1.2, glow, speed);
    else c = burst(v_Uv, v_P1.y, v_P1.z, A, B, px, glow);

    c *= intensity * v_ColorA.a;

    // The colour with its strongest channel as the alpha, so the blend adds exactly c
    float m = clamp(max(c.r, max(c.g, c.b)), 0.0, 1.0);
    if (m < 0.003) discard;

    color = vec4(c / max(m, 1e-4), m);
}
