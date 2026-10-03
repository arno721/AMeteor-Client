/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.utils;

import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.Mth;

/**
 * Shared easing helpers for GUI animations. Progress values are expected to be in the 0..1 range.
 */
public final class Anim {
    /** Scratch colors for {@link #lerp}. Rendering is single threaded and quads copy the color immediately. */
    public static final Color SCRATCH_A = new Color();
    public static final Color SCRATCH_B = new Color();

    private Anim() {}

    public static double easeOutCubic(double t) {
        t = Mth.clamp(t, 0, 1);
        double u = 1 - t;
        return 1 - u * u * u;
    }

    public static double easeInCubic(double t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * t;
    }

    public static double easeInOutCubic(double t) {
        t = Mth.clamp(t, 0, 1);
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    /** Slight overshoot, good for things that "pop" into place. */
    public static double easeOutBack(double t) {
        t = Mth.clamp(t, 0, 1);
        double c1 = 1.70158;
        double c3 = c1 + 1;
        double u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }

    /** Moves {@code progress} towards 1 (active) or 0 (inactive) at {@code speed} units per second. */
    public static double step(double progress, boolean active, double delta, double speed) {
        return Mth.clamp(progress + (active ? 1 : -1) * delta * speed, 0, 1);
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Linear interpolation between two colors written into {@code out}. */
    public static Color lerp(Color a, Color b, double t, Color out) {
        t = Mth.clamp(t, 0, 1);
        return out.set(
            (int) Math.round(a.r + (b.r - a.r) * t),
            (int) Math.round(a.g + (b.g - a.g) * t),
            (int) Math.round(a.b + (b.b - a.b) * t),
            (int) Math.round(a.a + (b.a - a.a) * t)
        );
    }
}
