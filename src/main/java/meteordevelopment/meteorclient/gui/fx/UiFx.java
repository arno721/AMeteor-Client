/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.fx;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.Anim;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Over the top animations for the screens of the client: windows that fly in, a moving aurora behind them, floating sparks,
 * a trail behind the mouse, bursts and rings when something is clicked, rainbow borders and shining headers.
 * <p>
 * Everything is drawn with the normal GUI renderer, so it is only quads, triangles and the circle texture. How much of it
 * is drawn is set by {@link Level}, and {@link Level#Off} draws nothing.
 */
public final class UiFx {
    public enum Level {
        Off(0),
        Subtle(0.35),
        Normal(0.7),
        Insane(1);

        public final double strength;

        Level(double strength) {
            this.strength = strength;
        }
    }

    private static final Random RANDOM = new Random();

    // Colors that are reused while drawing, one for each corner of a quad
    private static final Color C1 = new Color(), C2 = new Color(), C3 = new Color(), C4 = new Color();

    private static final List<Particle> PARTICLES = new ArrayList<>();
    private static final List<Ring> RINGS = new ArrayList<>();

    private static double time, age, ambientDebt, trailDebt;
    private static double lastX = Double.NaN, lastY;
    private static int windowCounter;

    private UiFx() {
    }

    public static double strength() {
        return Config.get().uiEffects.get().strength;
    }

    public static boolean enabled() {
        return strength() > 0;
    }

    /** A screen of the client opens: the intro starts again. */
    public static void onScreenOpen() {
        age = 0;
        windowCounter = 0;
        lastX = Double.NaN;

        if (!enabled()) return;

        // A wave goes out from the middle
        RINGS.add(new Ring(Utils.getWindowWidth() / 2.0, Utils.getWindowHeight() / 2.0, 0.9, Math.hypot(Utils.getWindowWidth(), Utils.getWindowHeight()) * 0.6, 0.12, 6 * strength() + 2, 0.55));
    }

    public static int nextWindowIndex() {
        return windowCounter++;
    }

    // Windows

    /**
     * Where a window is while it flies in. The offset is written to {@code out}. Every window comes from another side, one
     * after the other, and shoots a little past its place before it settles.
     */
    public static boolean intro(int index, double x, double y, double width, double height, double[] out) {
        double s = strength();
        if (s <= 0 || index < 0) return false;

        double duration = 0.55 + 0.5 * s;
        double t = (age - index * (0.07 + 0.05 * s)) / duration;
        if (t >= 1) return false;

        t = Mth.clamp(t, 0, 1);
        double e = s > 0.9 ? elastic(t) : Anim.easeOutBack(t);
        double reach = 0.35 + 0.65 * s;
        double screenWidth = Utils.getWindowWidth(), screenHeight = Utils.getWindowHeight();

        out[0] = out[1] = 0;

        switch (index % 4) {
            case 0 -> out[0] = -(x + width + 40) * (1 - e) * reach;
            case 1 -> out[1] = -(y + height + 40) * (1 - e) * reach;
            case 2 -> out[0] = (screenWidth - x + 40) * (1 - e) * reach;
            default -> out[1] = (screenHeight - y + 40) * (1 - e) * reach;
        }

        return true;
    }

    /** A bounce that overshoots a few times, from 0 to 1. */
    private static double elastic(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;

        return Math.pow(2, -9 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1;
    }

    /** A border that runs round the window in all colors, with a glow that breathes. */
    public static void windowBorder(GuiRenderer r, double x, double y, double w, double h, double scale) {
        double s = strength();
        if (s <= 0) return;

        double thickness = Math.max(1, scale * (1 + s));
        double pulse = 0.65 + 0.35 * Math.sin(time * 2.2);
        int alpha = (int) (230 * s);

        // Glow: wide frames that get fainter outwards
        for (int i = 3; i >= 1; i--) {
            double grow = thickness * i * 2.2;
            int a = (int) (26 * s * pulse / i * 2);

            hsv(time * 0.12, 0.8, 1, a, C1);
            frame(r, x - grow, y - grow, w + grow * 2, h + grow * 2, thickness * 2.2, C1);
        }

        double hue = time * 0.18;

        // Top and bottom run one way, the sides the other, the hue goes along the border
        hsv(hue, 0.85, 1, alpha, C1);
        hsv(hue + 0.25, 0.85, 1, alpha, C2);
        hsv(hue + 0.5, 0.85, 1, alpha, C3);
        hsv(hue + 0.75, 0.85, 1, alpha, C4);

        r.quad(x - thickness, y - thickness, w + thickness * 2, thickness, C1, C2, C2, C1);
        r.quad(x + w, y, thickness, h, C2, C2, C3, C3);
        r.quad(x - thickness, y + h, w + thickness * 2, thickness, C4, C3, C3, C4);
        r.quad(x - thickness, y, thickness, h, C1, C1, C4, C4);
    }

    /** A moving rainbow and a band of light that runs over a header. */
    public static void headerShine(GuiRenderer r, double x, double y, double w, double h) {
        double s = strength();
        if (s <= 0) return;

        int alpha = (int) (95 * s);
        double hue = time * 0.1;

        hsv(hue, 0.9, 1, alpha, C1);
        hsv(hue + 0.2, 0.9, 1, alpha, C2);
        hsv(hue + 0.4, 0.9, 1, alpha, C3);
        hsv(hue + 0.6, 0.9, 1, alpha, C4);
        r.quad(x, y, w, h, C1, C2, C3, C4);

        // The band of light
        double bandWidth = w * 0.25;
        double position = ((time * 0.45) % 1.6) * (w + bandWidth) - bandWidth;
        shine(r, x, y, w, h, position, bandWidth, (int) (110 * s));
    }

    /** A band of light on the area, from {@code position} (relative to x) on, clipped to the area. */
    private static void shine(GuiRenderer r, double x, double y, double w, double h, double position, double bandWidth, int alpha) {
        double left = Math.max(0, position), middle = position + bandWidth / 2, right = Math.min(w, position + bandWidth);
        if (right <= left) return;

        C1.set(255, 255, 255, 0);
        C2.set(255, 255, 255, alpha);

        if (middle > left) {
            double edge = Math.min(middle, w);
            double fromFraction = (left - position) / (bandWidth / 2);
            double toFraction = (edge - position) / (bandWidth / 2);
            C3.set(255, 255, 255, (int) (alpha * fromFraction));
            C4.set(255, 255, 255, (int) (alpha * toFraction));
            r.quad(x + left, y, edge - left, h, C3, C4, C4, C3);
        }

        if (right > middle) {
            double start = Math.max(middle, left);
            double fromFraction = 1 - (start - middle) / (bandWidth / 2);
            double toFraction = 1 - (right - middle) / (bandWidth / 2);
            C3.set(255, 255, 255, (int) (alpha * fromFraction));
            C4.set(255, 255, 255, (int) (alpha * toFraction));
            r.quad(x + start, y, right - start, h, C3, C4, C4, C3);
        }
    }

    // Module buttons

    /** A band of light that sweeps over a module the mouse is on. */
    public static void moduleShimmer(GuiRenderer r, double x, double y, double w, double h, double hover) {
        double s = strength();
        if (s <= 0 || hover <= 0) return;

        double bandWidth = w * 0.45;
        double position = ((time * 1.1) % 1.5) * (w + bandWidth) - bandWidth;
        shine(r, x, y, w, h, position, bandWidth, (int) (120 * s * hover));
    }

    /** The bar of an active module: it runs through all colors, and a glow sits next to it. */
    public static void activeBar(GuiRenderer r, double x, double y, double width, double height, double scale) {
        double s = strength();
        if (s <= 0) return;

        double hue = time * 0.3 + y * 0.004;
        hsv(hue, 0.85, 1, 255, C1);
        hsv(hue + 0.18, 0.85, 1, 255, C2);
        r.quad(x, y, width, height, C1, C1, C2, C2);

        // The glow
        double glow = scale * 14 * s;
        hsv(hue + 0.09, 0.85, 1, (int) (70 * s * (0.7 + 0.3 * Math.sin(time * 4 + y * 0.05))), C3);
        C4.set(255, 255, 255, 0);
        r.quad(x + width, y, glow, height, C3, C4, C4, C3);
    }

    // Particles, rings and the background

    /** Sparks and a ring from a point, for a click on something that matters. */
    public static void burst(double x, double y, Color accent, double power) {
        double s = strength();
        if (s <= 0) return;

        int count = (int) (26 * s * power);

        for (int i = 0; i < count; i++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2;
            double speed = (120 + RANDOM.nextDouble() * 420) * (0.6 + 0.4 * s);
            Particle p = new Particle(x, y, Math.cos(angle) * speed, Math.sin(angle) * speed, 0.5 + RANDOM.nextDouble() * 0.8, (4 + RANDOM.nextDouble() * 9) * (0.7 + 0.3 * s));

            p.gravity = 260;
            p.drag = 2.2;
            p.hue = accent == null ? RANDOM.nextDouble() : hueOf(accent) + (RANDOM.nextDouble() - 0.5) * 0.18;
            PARTICLES.add(p);
        }

        RINGS.add(new Ring(x, y, 0.55, 70 + 90 * s * power, 0.1, 4 * s + 1, accent == null ? Double.NaN : hueOf(accent)));
    }

    public static void click(double x, double y) {
        burst(x, y, null, 0.6);
    }

    /** Aurora, vignette and the floating sparks, behind the windows. */
    public static void background(GuiRenderer r, double mouseX, double mouseY, double dt, double fade) {
        double s = strength();
        if (s <= 0) return;

        time += dt;
        age += dt;

        double w = Utils.getWindowWidth(), h = Utils.getWindowHeight();

        // The aurora: bands of color that sway slowly
        for (int i = 0; i < 3; i++) {
            double hue = time * 0.04 + i * 0.31;
            double centre = h * (0.22 + 0.28 * i) + Math.sin(time * 0.5 + i * 2.1) * h * 0.09;
            double half = h * 0.2;
            int alpha = (int) (42 * s * fade * (0.7 + 0.3 * Math.sin(time * 0.8 + i)));

            hsv(hue, 0.7, 1, 0, C1);
            hsv(hue, 0.7, 1, alpha, C2);
            r.quad(0, centre - half, w, half, C1, C1, C2, C2);
            r.quad(0, centre, w, half, C2, C2, C1, C1);
        }

        // The vignette
        C1.set(0, 0, 0, (int) (110 * s * fade));
        C2.set(0, 0, 0, 0);
        double edge = Math.min(w, h) * 0.22;
        r.quad(0, 0, w, edge, C1, C1, C2, C2);
        r.quad(0, h - edge, w, edge, C2, C2, C1, C1);
        r.quad(0, 0, edge, h, C1, C2, C2, C1);
        r.quad(w - edge, 0, edge, h, C2, C1, C1, C2);

        // Sparks rise from below
        ambientDebt += dt * 22 * s;

        while (ambientDebt >= 1) {
            ambientDebt--;

            Particle p = new Particle(RANDOM.nextDouble() * w, h + 10, (RANDOM.nextDouble() - 0.5) * 30, -(40 + RANDOM.nextDouble() * 90) * (0.6 + 0.4 * s), 4 + RANDOM.nextDouble() * 4, 3 + RANDOM.nextDouble() * 7);
            p.hue = RANDOM.nextDouble();
            p.sway = 10 + RANDOM.nextDouble() * 30;
            p.phase = RANDOM.nextDouble() * 6.28;
            p.ambient = true;
            PARTICLES.add(p);
        }

        drawParticles(r, dt, true, fade);
    }

    /** The trail behind the mouse, bursts and rings, over the windows. */
    public static void foreground(GuiRenderer r, double mouseX, double mouseY, double dt, double fade) {
        double s = strength();
        if (s <= 0) return;

        if (!Double.isNaN(lastX)) {
            double moved = Math.hypot(mouseX - lastX, mouseY - lastY);
            trailDebt += moved * 0.35 * s;

            while (trailDebt >= 1) {
                trailDebt--;

                Particle p = new Particle(mouseX + (RANDOM.nextDouble() - 0.5) * 8, mouseY + (RANDOM.nextDouble() - 0.5) * 8,
                    (RANDOM.nextDouble() - 0.5) * 60, (RANDOM.nextDouble() - 0.5) * 60 + 20, 0.4 + RANDOM.nextDouble() * 0.5, 3 + RANDOM.nextDouble() * 6);
                p.hue = time * 0.5 + RANDOM.nextDouble() * 0.12;
                p.drag = 1.5;
                PARTICLES.add(p);
            }
        }

        lastX = mouseX;
        lastY = mouseY;

        drawParticles(r, dt, false, fade);

        for (int i = RINGS.size() - 1; i >= 0; i--) {
            Ring ring = RINGS.get(i);
            ring.age += dt;

            if (ring.age >= ring.life) {
                RINGS.remove(i);
                continue;
            }

            double t = ring.age / ring.life;
            double radius = ring.radius * Anim.easeOutCubic(t);
            double thickness = Math.max(1, ring.thickness * (1 - t) * 3);
            int alpha = (int) (200 * (1 - t) * (1 - t) * Math.min(1, s * 1.2));

            double hue = Double.isNaN(ring.hue) ? time * 0.4 : ring.hue;
            hsv(hue, 0.8, 1, alpha, C1);
            hsv(hue + 0.3, 0.8, 1, alpha, C2);

            drawRing(r, ring.x, ring.y, radius, thickness, C1, C2);
        }
    }

    private static void drawParticles(GuiRenderer r, double dt, boolean ambientLayer, double fade) {
        double s = strength();

        for (int i = PARTICLES.size() - 1; i >= 0; i--) {
            Particle p = PARTICLES.get(i);

            // Each layer moves its own particles, once per frame
            if (p.ambient != ambientLayer) continue;

            p.age += dt;
            double lifeFraction = p.age / p.life;

            if (lifeFraction >= 1 || p.y < -40 || p.y > Utils.getWindowHeight() + 60) {
                PARTICLES.remove(i);
                continue;
            }

            p.vy += p.gravity * dt;
            double damping = Math.max(0, 1 - p.drag * dt);
            p.vx *= damping;
            p.vy *= damping;
            p.x += (p.vx + (p.sway > 0 ? Math.sin(time * 1.5 + p.phase) * p.sway : 0)) * dt;
            p.y += p.vy * dt;

            // Fades in fast and out slowly, and the size shrinks at the end
            double alphaFraction = Math.min(1, lifeFraction * 8) * (1 - lifeFraction);
            double size = p.size * (1 - lifeFraction * 0.6) * (p.ambient ? 1 : (0.8 + 0.2 * s));
            int alpha = (int) (Mth.clamp(alphaFraction * (p.ambient ? 150 : 255) * fade, 0, 255));
            if (alpha <= 2) continue;

            // A soft halo around a bright core
            hsv(p.hue + lifeFraction * 0.2, 0.75, 1, alpha / 4, C1);
            r.rotatedQuad(p.x - size * 1.8, p.y - size * 1.8, size * 3.6, size * 3.6, 0, GuiRenderer.CIRCLE, C1);
            hsv(p.hue + lifeFraction * 0.2, 0.35, 1, alpha, C2);
            r.rotatedQuad(p.x - size / 2, p.y - size / 2, size, size, 0, GuiRenderer.CIRCLE, C2);
        }
    }

    // Shapes

    /** Four quads that make a frame, {@code thickness} wide, inside the given rectangle. */
    private static void frame(GuiRenderer r, double x, double y, double w, double h, double thickness, Color color) {
        r.quad(x, y, w, thickness, color);
        r.quad(x, y + h - thickness, w, thickness, color);
        r.quad(x, y + thickness, thickness, h - thickness * 2, color);
        r.quad(x + w - thickness, y + thickness, thickness, h - thickness * 2, color);
    }

    private static void drawRing(GuiRenderer r, double cx, double cy, double radius, double thickness, Color a, Color b) {
        int segments = (int) Mth.clamp(radius / 6, 16, 72);
        double inner = Math.max(0, radius - thickness / 2), outer = radius + thickness / 2;

        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2 / segments, a1 = (i + 1) * Math.PI * 2 / segments;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);

            // The color changes round the ring
            Color color = (i * 2 / segments) % 2 == 0 ? a : b;
            C3.set(color);
            double mix = 0.5 + 0.5 * Math.sin(a0 * 2 + time * 3);
            Anim.lerp(a, b, mix, C3);

            r.triangle(cx + c0 * inner, cy + s0 * inner, cx + c0 * outer, cy + s0 * outer, cx + c1 * outer, cy + s1 * outer, C3);
            r.triangle(cx + c0 * inner, cy + s0 * inner, cx + c1 * outer, cy + s1 * outer, cx + c1 * inner, cy + s1 * inner, C3);
        }
    }

    // Colors

    /** Hue (any number, only the fraction counts), saturation, value, written to {@code out}. */
    public static Color hsv(double hue, double saturation, double value, int alpha, Color out) {
        hue = hue - Math.floor(hue);
        double h6 = hue * 6;
        int sector = (int) h6;
        double f = h6 - sector;
        double p = value * (1 - saturation), q = value * (1 - saturation * f), t = value * (1 - saturation * (1 - f));

        double red, green, blue;

        switch (sector) {
            case 0 -> { red = value; green = t; blue = p; }
            case 1 -> { red = q; green = value; blue = p; }
            case 2 -> { red = p; green = value; blue = t; }
            case 3 -> { red = p; green = q; blue = value; }
            case 4 -> { red = t; green = p; blue = value; }
            default -> { red = value; green = p; blue = q; }
        }

        return out.set((int) (red * 255), (int) (green * 255), (int) (blue * 255), Mth.clamp(alpha, 0, 255));
    }

    private static double hueOf(Color c) {
        float[] hsb = java.awt.Color.RGBtoHSB(c.r, c.g, c.b, null);
        return hsb[0];
    }

    // Things that fly around

    private static final class Particle {
        double x, y, vx, vy, age, life, size;
        double gravity, drag, hue, sway, phase;
        boolean ambient;

        Particle(double x, double y, double vx, double vy, double life, double size) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.life = life;
            this.size = size;
        }
    }

    private static final class Ring {
        final double x, y, life, radius, thickness, hue;
        double age;

        Ring(double x, double y, double life, double radius, double unused, double thickness, double hue) {
            this.x = x;
            this.y = y;
            this.life = life;
            this.radius = radius;
            this.thickness = thickness;
            this.hue = hue;
        }
    }
}
