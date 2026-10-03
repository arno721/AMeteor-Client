/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura1;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Draws an animated marker around the entity Kill Aura is currently attacking.
 */
public class TargetESP extends Module {
    public enum Style {
        Halo,
        Orbit,
        Helix,
        Corners,
        Diamond,
        Pulse,
        Reticle,
        Box
    }

    public enum ColorMode {
        Custom,
        Gradient,
        Rainbow,
        Health
    }

    private static final double TAU = Math.PI * 2;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColor = settings.createGroup("Color");
    private final SettingGroup sgStyle = settings.createGroup("Style");

    // General

    private final Setting<Style> style = sgGeneral.add(new EnumSetting.Builder<Style>()
        .name("style")
        .description("How the target is marked.")
        .defaultValue(Style.Halo)
        .build()
    );

    private final Setting<Boolean> onlyWhileAttacking = sgGeneral.add(new BoolSetting.Builder()
        .name("only-while-attacking")
        .description("Only shows the marker while Kill Aura is actually attacking, not just while it has a target in range.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> throughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls")
        .description("Renders the marker through blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Animation speed.")
        .defaultValue(1)
        .min(0.1)
        .sliderRange(0.2, 4)
        .build()
    );

    private final Setting<Double> size = sgGeneral.add(new DoubleSetting.Builder()
        .name("size")
        .description("Size multiplier of the marker.")
        .defaultValue(1)
        .min(0.2)
        .sliderRange(0.5, 2)
        .build()
    );

    private final Setting<Double> fadeSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("fade-speed")
        .description("How quickly the marker fades in and out when the target changes.")
        .defaultValue(6)
        .min(0.5)
        .sliderRange(1, 15)
        .build()
    );

    // Color

    private final Setting<ColorMode> colorMode = sgColor.add(new EnumSetting.Builder<ColorMode>()
        .name("color-mode")
        .description("How the marker is colored.")
        .defaultValue(ColorMode.Gradient)
        .build()
    );

    private final Setting<SettingColor> color = sgColor.add(new ColorSetting.Builder()
        .name("color")
        .description("The main color.")
        .defaultValue(new SettingColor(120, 90, 255, 255))
        .visible(() -> colorMode.get() == ColorMode.Custom || colorMode.get() == ColorMode.Gradient)
        .build()
    );

    private final Setting<SettingColor> secondColor = sgColor.add(new ColorSetting.Builder()
        .name("second-color")
        .description("The color the gradient fades to.")
        .defaultValue(new SettingColor(70, 220, 255, 255))
        .visible(() -> colorMode.get() == ColorMode.Gradient)
        .build()
    );

    private final Setting<Double> rainbowSpread = sgColor.add(new DoubleSetting.Builder()
        .name("rainbow-spread")
        .description("How much of the color wheel is shown at once.")
        .defaultValue(1)
        .min(0.1)
        .sliderRange(0.2, 2)
        .visible(() -> colorMode.get() == ColorMode.Rainbow)
        .build()
    );

    private final Setting<Integer> opacity = sgColor.add(new IntSetting.Builder()
        .name("opacity")
        .description("Overall opacity of the marker.")
        .defaultValue(255)
        .range(10, 255)
        .sliderRange(30, 255)
        .build()
    );

    private final Setting<Integer> fillOpacity = sgColor.add(new IntSetting.Builder()
        .name("fill-opacity")
        .description("Opacity of the translucent fill used by the styles that have one.")
        .defaultValue(90)
        .range(0, 255)
        .sliderRange(0, 255)
        .build()
    );

    // Style

    private final Setting<Integer> segments = sgStyle.add(new IntSetting.Builder()
        .name("smoothness")
        .description("Number of segments used for round shapes. Higher is smoother.")
        .defaultValue(48)
        .range(12, 128)
        .sliderRange(16, 96)
        .build()
    );

    private final Setting<Integer> orbs = sgStyle.add(new IntSetting.Builder()
        .name("orbs")
        .description("Number of orbs for the Orbit style.")
        .defaultValue(3)
        .range(1, 8)
        .sliderRange(1, 6)
        .visible(() -> style.get() == Style.Orbit)
        .build()
    );

    private final Setting<Integer> trailLength = sgStyle.add(new IntSetting.Builder()
        .name("trail-length")
        .description("Length of the orb trails.")
        .defaultValue(14)
        .range(2, 40)
        .sliderRange(4, 30)
        .visible(() -> style.get() == Style.Orbit)
        .build()
    );

    private final Setting<Boolean> glow = sgStyle.add(new BoolSetting.Builder()
        .name("glow")
        .description("Adds a soft glow to orbs and the diamond.")
        .defaultValue(true)
        .visible(() -> style.get() == Style.Orbit || style.get() == Style.Diamond || style.get() == Style.Reticle)
        .build()
    );

    private final Setting<Boolean> doubleRing = sgStyle.add(new BoolSetting.Builder()
        .name("double-ring")
        .description("Uses a second ring moving the opposite way.")
        .defaultValue(true)
        .visible(() -> style.get() == Style.Halo)
        .build()
    );

    private final Setting<Integer> ripples = sgStyle.add(new IntSetting.Builder()
        .name("ripples")
        .description("Number of ripples for the Pulse style.")
        .defaultValue(3)
        .range(1, 6)
        .sliderRange(1, 5)
        .visible(() -> style.get() == Style.Pulse)
        .build()
    );

    private final Setting<Integer> strands = sgStyle.add(new IntSetting.Builder()
        .name("strands")
        .description("Number of strands for the Helix style.")
        .defaultValue(2)
        .range(1, 5)
        .sliderRange(1, 4)
        .visible(() -> style.get() == Style.Helix)
        .build()
    );

    private KillAura killAura;
    private KillAura1 killAura1;

    private final Color a = new Color();
    private final Color b = new Color();
    private final Color c = new Color();
    private final Color d = new Color();
    private final Color g = new Color();

    private Entity current;
    private double fade;
    private double phase;
    private long lastNanos;

    // Remembered while fading out, so the marker does not snap away if the target dies or leaves
    private double lastX, lastY, lastZ, lastWidth, lastHeight, lastHealth;

    public TargetESP() {
        super(Categories.Render, "target-esp", "Marks the entity Kill Aura is attacking with an animated effect.");
    }

    @Override
    public void onActivate() {
        current = null;
        fade = 0;
        lastNanos = System.nanoTime();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        long now = System.nanoTime();
        double dt = Mth.clamp((now - lastNanos) / 1e9, 0, 0.1);
        lastNanos = now;

        if (killAura == null) {
            killAura = Modules.get().get(KillAura.class);
            killAura1 = Modules.get().get(KillAura1.class);
        }

        Entity target = killAura1.isActive() && (!onlyWhileAttacking.get() || killAura1.attacking) ? killAura1.getTarget() : null;
        if (target == null) target = killAura.isActive() && (!onlyWhileAttacking.get() || killAura.attacking) ? killAura.getTarget() : null;

        if (target != null && target.isAlive()) {
            if (target != current) {
                // New target: fade in again from a low value so the switch is visible
                fade = current == null ? 0 : Math.min(fade, 0.25);
                current = target;
            }

            fade = Math.min(1, fade + dt * fadeSpeed.get());
        } else {
            fade = Math.max(0, fade - dt * fadeSpeed.get());
            if (fade == 0) current = null;
        }

        if (current == null) return;

        phase += dt * speed.get();

        if (current.isAlive() && !current.isRemoved()) {
            lastX = Mth.lerp(event.tickDelta, current.xOld, current.getX());
            lastY = Mth.lerp(event.tickDelta, current.yOld, current.getY());
            lastZ = Mth.lerp(event.tickDelta, current.zOld, current.getZ());
            lastWidth = current.getBbWidth();
            lastHeight = current.getBbHeight();
            lastHealth = current instanceof LivingEntity living ? Mth.clamp(living.getHealth() / Math.max(living.getMaxHealth(), 1), 0, 1) : 1;
        }

        Renderer3D r = throughWalls.get() ? event.renderer : event.depthRenderer;
        double e = ease(fade);
        double scale = size.get() * (0.75 + 0.25 * e);
        double width = Math.max(lastWidth, 0.3) * scale;
        double height = Math.max(lastHeight, 0.3) * scale;

        switch (style.get()) {
            case Halo -> halo(r, e, width, height);
            case Orbit -> orbit(r, e, width, height);
            case Helix -> helix(r, e, width, height);
            case Corners -> corners(r, e, width, height);
            case Diamond -> diamond(r, e, width, height);
            case Pulse -> pulse(r, e, width);
            case Reticle -> reticle(r, e, width, height);
            case Box -> box(r, e, width, height);
        }
    }

    // Styles

    /** Glowing rings that glide up and down the body, leaving a fading ribbon behind them. */
    private void halo(Renderer3D r, double e, double width, double height) {
        double radius = width * 0.85 + 0.15;
        int rings = doubleRing.get() ? 2 : 1;

        for (int ring = 0; ring < rings; ring++) {
            double t = phase * 1.4 + ring * Math.PI;
            double y = lastY + height * (0.5 + 0.5 * Math.sin(t));
            double direction = Math.cos(t) >= 0 ? -1 : 1;
            double ribbon = height * 0.28 * direction;

            double rot = phase * (ring == 0 ? 1 : -1);
            int n = segments.get();

            for (int i = 0; i < n; i++) {
                double a0 = rot + TAU * i / n;
                double a1 = rot + TAU * (i + 1) / n;
                double x0 = lastX + Math.cos(a0) * radius, z0 = lastZ + Math.sin(a0) * radius;
                double x1 = lastX + Math.cos(a1) * radius, z1 = lastZ + Math.sin(a1) * radius;

                double t0 = (double) i / n + ring * 0.5, t1 = (double) (i + 1) / n + ring * 0.5;
                double fillAlpha = fillOpacity.get() / 255.0;

                colorAt(a, t0, alphaFor(e, 1));
                colorAt(b, t1, alphaFor(e, 1));
                colorAt(c, t0, alphaFor(e, fillAlpha));
                colorAt(d, t1, alphaFor(e, fillAlpha));

                // Ribbon fading away from the bright edge (same color, alpha 0 at the far side)
                g.set(c);
                g.a = 0;
                Color far0 = g;
                r.quad(x0, y, z0, x0, y + ribbon, z0, x1, y + ribbon, z1, x1, y, z1, far0, far0, d, c);

                // Bright leading edge
                r.line(x0, y, z0, x1, y, z1, a, b);
            }
        }
    }

    /** Glowing orbs circling the target with comet trails. */
    private void orbit(Renderer3D r, double e, double width, double height) {
        double radius = width * 0.9 + 0.35;
        int count = orbs.get();
        int trail = trailLength.get();
        Vec3 cam = mc.gameRenderer.mainCamera().position();

        for (int i = 0; i < count; i++) {
            double offset = TAU * i / count;
            double prevX = 0, prevY = 0, prevZ = 0;

            for (int k = 0; k <= trail; k++) {
                // Walk back along the orbit to get the trail positions
                double back = k * 0.085;
                double angle = phase * 2.2 + offset - back;
                double h = 0.5 + 0.5 * Math.sin(phase * 1.5 + offset * 1.7 - back * 0.6);

                double x = lastX + Math.cos(angle) * radius;
                double y = lastY + height * (0.08 + 0.84 * h);
                double z = lastZ + Math.sin(angle) * radius;

                double life = 1 - (double) k / trail;

                if (k > 0) {
                    colorAt(a, (double) i / count + life * 0.3, alphaFor(e, life * life));
                    colorAt(b, (double) i / count + (life + 1.0 / trail) * 0.3, alphaFor(e, (life + 1.0 / trail) * (life + 1.0 / trail)));
                    r.line(prevX, prevY, prevZ, x, y, z, b, a);

                    if (glow.get()) {
                        colorAt(a, (double) i / count + life * 0.3, alphaFor(e, life * 0.5));
                        disc(r, x, y, z, 0.05 + 0.07 * life, cam, 10, a, withAlpha(b.set(a), 0));
                    }
                }

                prevX = x;
                prevY = y;
                prevZ = z;
            }

            // Head
            double angle = phase * 2.2 + offset;
            double h = 0.5 + 0.5 * Math.sin(phase * 1.5 + offset * 1.7);
            double hx = lastX + Math.cos(angle) * radius;
            double hy = lastY + height * (0.08 + 0.84 * h);
            double hz = lastZ + Math.sin(angle) * radius;

            colorAt(a, (double) i / count, alphaFor(e, 1));
            disc(r, hx, hy, hz, 0.09, cam, 14, a, a);

            if (glow.get()) {
                colorAt(a, (double) i / count, alphaFor(e, 0.65));
                disc(r, hx, hy, hz, 0.3, cam, 16, a, withAlpha(b.set(a), 0));
            }
        }
    }

    /** Spiral strands winding up the body. */
    private void helix(Renderer3D r, double e, double width, double height) {
        double radius = width * 0.8 + 0.2;
        int steps = segments.get() * 2;
        int count = strands.get();
        double turns = 2.5;

        for (int s = 0; s < count; s++) {
            double offset = TAU * s / count;
            double px = 0, py = 0, pz = 0;
            double pAlpha = 0;

            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                double angle = phase * 2 + offset + t * turns * TAU;

                double x = lastX + Math.cos(angle) * radius;
                double y = lastY + t * height;
                double z = lastZ + Math.sin(angle) * radius;

                // Fade out at both ends
                double taper = Math.sin(t * Math.PI);
                taper = Math.pow(taper, 0.6);

                if (i > 0) {
                    colorAt(a, t + phase * 0.1, alphaFor(e, taper));
                    colorAt(b, t - 1.0 / steps + phase * 0.1, alphaFor(e, pAlpha));
                    r.line(px, py, pz, x, y, z, b, a);

                    // Second, slightly offset line makes the strand look thicker
                    r.line(px, py + 0.012, pz, x, y + 0.012, z, b, a);
                }

                px = x;
                py = y;
                pz = z;
                pAlpha = taper;
            }
        }
    }

    /** Corner brackets around the hitbox that breathe in and out. */
    private void corners(Renderer3D r, double e, double width, double height) {
        double breathe = 1 + 0.05 * Math.sin(phase * 3);
        double hw = (width / 2 + 0.12) * breathe;
        double hh = height * 0.5;
        double cy = lastY + hh;
        double ext = hh * breathe + 0.08;

        double x1 = lastX - hw, x2 = lastX + hw;
        double z1 = lastZ - hw, z2 = lastZ + hw;
        double y1 = cy - ext, y2 = cy + ext;

        double len = Math.min(0.32, Math.min(hw, ext) * 0.6);

        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    double x = xi == 0 ? x1 : x2, y = yi == 0 ? y1 : y2, z = zi == 0 ? z1 : z2;
                    double dx = xi == 0 ? len : -len, dy = yi == 0 ? len : -len, dz = zi == 0 ? len : -len;

                    colorAt(a, yi == 0 ? 0 : 0.5, alphaFor(e, 1));
                    colorAt(b, yi == 0 ? 0 : 0.5, alphaFor(e, 0.15));
                    colorAt(c, yi == 0 ? 0.25 : 0.75, alphaFor(e, 0.15));

                    r.line(x, y, z, x + dx, y, z, a, b);
                    r.line(x, y, z, x, y, z + dz, a, b);
                    r.line(x, y, z, x, y + dy, z, a, c);
                }
            }
        }

        // Very faint fill so the box reads as a volume
        double fill = fillOpacity.get() / 255.0 * 0.35;
        if (fill > 0) {
            colorAt(a, 0, alphaFor(e, fill));
            colorAt(b, 0.5, alphaFor(e, 0));
            wall(r, x1, z1, x2, z1, y1, y2, a, b);
            wall(r, x2, z1, x2, z2, y1, y2, a, b);
            wall(r, x2, z2, x1, z2, y1, y2, a, b);
            wall(r, x1, z2, x1, z1, y1, y2, a, b);
        }
    }

    /** A spinning crystal hovering above the target's head. */
    private void diamond(Renderer3D r, double e, double width, double height) {
        double bob = Math.sin(phase * 2.2) * 0.07;
        double cx = lastX;
        double cy = lastY + height + 0.55 * size.get() + bob;
        double cz = lastZ;

        double h = 0.27 * size.get();
        double w = 0.17 * size.get();
        double spin = phase * 2.4;
        Vec3 cam = mc.gameRenderer.mainCamera().position();

        double[] ex = new double[4], ez = new double[4];
        for (int i = 0; i < 4; i++) {
            double ang = spin + i * Math.PI / 2;
            ex[i] = cx + Math.cos(ang) * w;
            ez[i] = cz + Math.sin(ang) * w;
        }

        MeshBuilder tri = r.triangles;

        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;

            // Alternate the shading of the faces so the rotation is visible
            double shade = 0.55 + 0.45 * Math.sin(spin + i * Math.PI / 2 + 0.7);
            double faceAlpha = alphaFor(e, 0.35 + 0.55 * Math.max(shade, 0));

            colorAt(a, 0.15 + i * 0.06, faceAlpha);
            colorAt(b, 0.85 - i * 0.06, alphaFor(e, 0.3));

            // Upper face (bright tip, darker on the equator)
            tri.ensureTriCapacity();
            tri.triangle(
                tri.vec3(cx, cy + h, cz).color(a).next(),
                tri.vec3(ex[i], cy, ez[i]).color(b).next(),
                tri.vec3(ex[j], cy, ez[j]).color(b).next()
            );

            // Lower face
            colorAt(c, 0.5 + i * 0.05, alphaFor(e, 0.25 + 0.4 * Math.max(shade, 0)));
            tri.ensureTriCapacity();
            tri.triangle(
                tri.vec3(cx, cy - h * 1.15, cz).color(c).next(),
                tri.vec3(ex[i], cy, ez[i]).color(b).next(),
                tri.vec3(ex[j], cy, ez[j]).color(b).next()
            );
        }

        // Edges
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            colorAt(a, 0.1, alphaFor(e, 1));
            colorAt(b, 0.6, alphaFor(e, 1));

            r.line(ex[i], cy, ez[i], ex[j], cy, ez[j], b, b);
            r.line(cx, cy + h, cz, ex[i], cy, ez[i], a, b);
            r.line(cx, cy - h * 1.15, cz, ex[i], cy, ez[i], a, b);
        }

        if (glow.get()) {
            colorAt(a, 0.3, alphaFor(e, 0.4));
            disc(r, cx, cy, cz, 0.42 * size.get(), cam, 20, a, withAlpha(b.set(a), 0));
        }

        // Soft marker dropping down to the target
        colorAt(a, 0.5, alphaFor(e, 0.5));
        colorAt(b, 0.5, 0);
        r.line(cx, cy - h * 1.15, cz, cx, lastY + height + 0.05, cz, a, b);
    }

    /** Ripples spreading outwards on the ground. */
    private void pulse(Renderer3D r, double e, double width) {
        int count = ripples.get();
        double max = width * 1.6 + 0.9;
        double y = lastY + 0.03;
        int n = segments.get();

        for (int k = 0; k < count; k++) {
            double age = (phase * 0.45 + (double) k / count) % 1;
            double radius = 0.15 + age * max;
            double alpha = Math.pow(1 - age, 1.6) * Math.min(age * 6, 1);
            double band = 0.05 + 0.1 * (1 - age);

            for (int i = 0; i < n; i++) {
                double a0 = TAU * i / n;
                double a1 = TAU * (i + 1) / n;

                double ix0 = lastX + Math.cos(a0) * (radius - band), iz0 = lastZ + Math.sin(a0) * (radius - band);
                double ox0 = lastX + Math.cos(a0) * (radius + band), oz0 = lastZ + Math.sin(a0) * (radius + band);
                double ix1 = lastX + Math.cos(a1) * (radius - band), iz1 = lastZ + Math.sin(a1) * (radius - band);
                double ox1 = lastX + Math.cos(a1) * (radius + band), oz1 = lastZ + Math.sin(a1) * (radius + band);

                colorAt(a, (double) i / n + age, alphaFor(e, alpha));
                colorAt(b, (double) (i + 1) / n + age, alphaFor(e, alpha));

                // Soft band: transparent on the inside, bright on the outside
                c.set(a);
                c.a = 0;
                r.quad(ix0, y, iz0, ox0, y, oz0, ox1, y, oz1, ix1, y, iz1, a, b, c, c);

                r.line(ox0, y, oz0, ox1, y, oz1, a, b);
            }
        }
    }

    /** A rotating lock-on reticle facing the camera. */
    private void reticle(Renderer3D r, double e, double width, double height) {
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        double cx = lastX, cy = lastY + height * 0.55, cz = lastZ;

        // Basis of the plane facing the camera
        double fx = cx - cam.x, fy = cy - cam.y, fz = cz - cam.z;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1e-4) return;
        fx /= fl;
        fy /= fl;
        fz /= fl;

        double rx = fz, rz = -fx; // cross(forward, up)
        double rl = Math.sqrt(rx * rx + rz * rz);
        if (rl < 1e-4) return;
        rx /= rl;
        rz /= rl;

        // up = cross(right, forward)
        double ux = -fy * rz;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy;

        double radius = Math.max(width, height * 0.7) * 0.75 + 0.2;
        double thick = radius * 0.09;
        int arcs = 4;
        int per = Math.max(8, segments.get() / 4);
        double spin = phase * 1.6;

        // Outer segmented ring
        for (int s = 0; s < arcs; s++) {
            double start = spin + TAU * s / arcs;
            double span = TAU / arcs * 0.62;

            for (int i = 0; i < per; i++) {
                double t0 = start + span * i / per;
                double t1 = start + span * (i + 1) / per;

                double c0 = Math.cos(t0), s0 = Math.sin(t0), c1 = Math.cos(t1), s1 = Math.sin(t1);
                double inner = radius, outer = radius + thick;

                colorAt(a, (double) s / arcs + (double) i / per * 0.2, alphaFor(e, 1));
                colorAt(b, (double) s / arcs + (double) (i + 1) / per * 0.2, alphaFor(e, 1));

                r.quad(
                    cx + (rx * c0 + ux * s0) * inner, cy + (uy * s0) * inner, cz + (rz * c0 + uz * s0) * inner,
                    cx + (rx * c0 + ux * s0) * outer, cy + (uy * s0) * outer, cz + (rz * c0 + uz * s0) * outer,
                    cx + (rx * c1 + ux * s1) * outer, cy + (uy * s1) * outer, cz + (rz * c1 + uz * s1) * outer,
                    cx + (rx * c1 + ux * s1) * inner, cy + (uy * s1) * inner, cz + (rz * c1 + uz * s1) * inner,
                    a, b, b, a
                );
            }
        }

        // Inner ticks pointing at the center, pulsing
        double pulse = 0.5 + 0.5 * Math.sin(phase * 3.2);
        for (int s = 0; s < 4; s++) {
            double ang = -spin * 0.7 + s * Math.PI / 2;
            double ca = Math.cos(ang), sa = Math.sin(ang);
            double from = radius * (0.62 - 0.08 * pulse), to = radius * (0.42 - 0.08 * pulse);

            colorAt(a, 0.5 + s * 0.1, alphaFor(e, 1));
            colorAt(b, 0.5 + s * 0.1, alphaFor(e, 0.2));

            r.line(
                cx + (rx * ca + ux * sa) * from, cy + (uy * sa) * from, cz + (rz * ca + uz * sa) * from,
                cx + (rx * ca + ux * sa) * to, cy + (uy * sa) * to, cz + (rz * ca + uz * sa) * to,
                a, b
            );
        }

        // Center dot with glow
        colorAt(a, 0.5, alphaFor(e, 1));
        disc(r, cx, cy, cz, radius * 0.07, cam, 12, a, a);

        if (glow.get()) {
            colorAt(a, 0.5, alphaFor(e, 0.35));
            disc(r, cx, cy, cz, radius * 0.6, cam, 24, a, withAlpha(b.set(a), 0));
        }
    }

    /** A gradient box that is brightest at the feet. */
    private void box(Renderer3D r, double e, double width, double height) {
        double breathe = 1 + 0.025 * Math.sin(phase * 2.5);
        double hw = (width / 2 + 0.05) * breathe;
        double x1 = lastX - hw, x2 = lastX + hw;
        double z1 = lastZ - hw, z2 = lastZ + hw;
        double y1 = lastY, y2 = lastY + height * breathe;

        double fill = fillOpacity.get() / 255.0;

        colorAt(a, 0.0 + phase * 0.05, alphaFor(e, fill));
        colorAt(b, 1.0 + phase * 0.05, alphaFor(e, fill * 0.15));

        wall(r, x1, z1, x2, z1, y1, y2, a, b);
        wall(r, x2, z1, x2, z2, y1, y2, a, b);
        wall(r, x2, z2, x1, z2, y1, y2, a, b);
        wall(r, x1, z2, x1, z1, y1, y2, a, b);

        // Base plate and outline
        colorAt(c, 0.0, alphaFor(e, fill * 0.9));
        r.quad(x1, y1, z1, x1, y1, z2, x2, y1, z2, x2, y1, z1, c);

        colorAt(a, 0.0, alphaFor(e, 1));
        colorAt(b, 1.0, alphaFor(e, 0.6));
        double[][] corners = {{x1, z1}, {x2, z1}, {x2, z2}, {x1, z2}};

        for (int i = 0; i < 4; i++) {
            double[] p = corners[i], q = corners[(i + 1) % 4];
            r.line(p[0], y1, p[1], q[0], y1, q[1], a, a);
            r.line(p[0], y2, p[1], q[0], y2, q[1], b, b);
            r.line(p[0], y1, p[1], p[0], y2, p[1], a, b);
        }
    }

    // Drawing helpers

    /** A camera facing disc with a radial gradient from {@code center} to {@code edge}. */
    private void disc(Renderer3D r, double x, double y, double z, double radius, Vec3 cam, int sides, Color center, Color edge) {
        double fx = x - cam.x, fy = y - cam.y, fz = z - cam.z;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1e-4) return;
        fx /= fl;
        fy /= fl;
        fz /= fl;

        double rx = fz, rz = -fx;
        double rl = Math.sqrt(rx * rx + rz * rz);
        if (rl < 1e-4) return;
        rx /= rl;
        rz /= rl;

        double ux = -fy * rz;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy;

        MeshBuilder tri = r.triangles;
        tri.ensureCapacity(sides + 1, sides * 3);

        int centerIndex = tri.vec3(x, y, z).color(center).next();
        int first = -1, prev = -1;

        for (int i = 0; i < sides; i++) {
            double ang = TAU * i / sides;
            double ca = Math.cos(ang) * radius, sa = Math.sin(ang) * radius;

            int index = tri.vec3(x + rx * ca + ux * sa, y + uy * sa, z + rz * ca + uz * sa).color(edge).next();
            if (first == -1) first = index;
            if (prev != -1) tri.triangle(centerIndex, prev, index);
            prev = index;
        }

        tri.triangle(centerIndex, prev, first);
    }

    /** A vertical wall between two points with a gradient from {@code bottom} to {@code top}. */
    private void wall(Renderer3D r, double x0, double z0, double x1, double z1, double yBottom, double yTop, Color bottom, Color top) {
        r.quad(x0, yBottom, z0, x0, yTop, z0, x1, yTop, z1, x1, yBottom, z1, top, top, bottom, bottom);
    }

    // Colors

    /** Sets {@code out} to the marker color at position {@code t} (any value, it wraps) with the given 0..1 alpha. */
    private void colorAt(Color out, double t, double alpha) {
        t = t - Math.floor(t);

        switch (colorMode.get()) {
            case Custom -> out.set(color.get());
            case Gradient -> {
                // Ping-pong so the gradient has no seam when it wraps around a ring
                double p = t < 0.5 ? t * 2 : (1 - t) * 2;
                p = p * p * (3 - 2 * p);

                SettingColor from = color.get(), to = secondColor.get();
                out.set(
                    (int) Mth.lerp(p, from.r, to.r),
                    (int) Mth.lerp(p, from.g, to.g),
                    (int) Mth.lerp(p, from.b, to.b),
                    255
                );
            }
            case Rainbow -> {
                double hue = ((t * rainbowSpread.get() + phase * 0.15) % 1 + 1) % 1;
                int packed = java.awt.Color.HSBtoRGB((float) hue, 0.75f, 1f);
                out.set((packed >> 16) & 0xFF, (packed >> 8) & 0xFF, packed & 0xFF, 255);
            }
            case Health -> {
                double h = Mth.clamp(lastHealth, 0, 1);
                // Red -> yellow -> green
                int red = (int) (255 * Math.min(1, (1 - h) * 2));
                int green = (int) (255 * Math.min(1, h * 2));
                out.set(red, green, 60, 255);
            }
        }

        out.a = (int) Mth.clamp(255 * alpha * opacity.get() / 255.0, 0, 255);
    }

    private double alphaFor(double fade, double value) {
        return Mth.clamp(fade * value, 0, 1);
    }

    private Color withAlpha(Color color, int alpha) {
        color.a = alpha;
        return color;
    }

    private static double ease(double t) {
        return t * t * (3 - 2 * t);
    }

    @Override
    public String getInfoString() {
        return style.get().name();
    }
}
