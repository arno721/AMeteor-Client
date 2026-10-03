/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render.island;

import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Text placed on whole screen pixels, in sizes given in design units. The vanilla font is drawn at a whole number of
 * screen pixels per font pixel, so it stays sharp and does not shimmer when things move. Text is collected and drawn
 * by {@link #flush()}, after the shapes, and can be cut to a rectangle (for scrolling titles and lyric highlights).
 */
public final class PixelText {
    public enum Align {
        LEFT,
        CENTER,
        RIGHT
    }

    private record Item(String text, double size, int x, double cy, Align align, int argb, int[] clip, boolean shadow) {
    }

    private final List<Item> items = new ArrayList<>();
    private boolean shadow;
    private final Color color = new Color();
    private GuiGraphicsExtractor graphics;
    private int guiScale = 1;
    private double u = 1;

    public void begin(GuiGraphicsExtractor graphics, int guiScale, double u) {
        this.graphics = graphics;
        this.guiScale = guiScale;
        this.u = u;
        shadow = false;
        items.clear();
    }

    /** Size of one vanilla font pixel in screen pixels. */
    public int step(double sizeUnits) {
        return Math.max(1, (int) Math.round(sizeUnits * u / 9));
    }

    /** Height of a line in screen pixels. */
    public double lineHeight(double sizeUnits) {
        return TextRenderer.get() instanceof VanillaTextRenderer ? 8 * step(sizeUnits) : sizeUnits * u;
    }

    /** Width in screen pixels, without the space after the last letter. */
    public double width(String text, double sizeUnits) {
        if (text == null || text.isEmpty()) return 0;

        TextRenderer tr = TextRenderer.get();
        if (tr instanceof VanillaTextRenderer) return Math.max(0, mc.font.width(text) - 1) * step(sizeUnits);

        tr.begin(graphics, sizeUnits * u / 18.0, true, false);

        try {
            return tr.getWidth(text);
        } finally {
            tr.end();
        }
    }

    /** Shortens the text until it fits. */
    public String fit(String text, double sizeUnits, double maxPixels) {
        if (text == null || text.isEmpty() || width(text, sizeUnits) <= maxPixels) return text == null ? "" : text;

        int end = text.length();
        while (end > 1 && width(text.substring(0, end) + "..", sizeUnits) > maxPixels) end--;

        return text.substring(0, end).stripTrailing() + "..";
    }

    /** Draws the text added from now on with a shadow, for text without a background behind it. */
    public void setShadow(boolean shadow) {
        this.shadow = shadow;
    }

    public void add(String text, double sizeUnits, int x, double centerY, Align align, int argb) {
        add(text, sizeUnits, x, centerY, align, argb, null);
    }

    /** {@code clip} is {x0, y0, x1, y1} in screen pixels, or null. */
    public void add(String text, double sizeUnits, int x, double centerY, Align align, int argb, int[] clip) {
        if (text == null || text.isEmpty() || (argb >>> 24) < 8) return;
        if (clip != null && (clip[2] <= clip[0] || clip[3] <= clip[1])) return;

        items.add(new Item(text, sizeUnits, x, centerY, align, argb, clip, shadow));
    }

    public void flush() {
        if (items.isEmpty() || graphics == null) return;

        TextRenderer tr = TextRenderer.get();
        boolean vanilla = tr instanceof VanillaTextRenderer;
        GuiGraphicsExtractor g = graphics;

        g.nextStratum();
        g.pose().pushMatrix();
        g.pose().scale(1.0f / guiScale);

        try {
            for (Item item : items) {
                if (item.clip != null) g.enableScissor(item.clip[0], item.clip[1], item.clip[2], item.clip[3]);

                try {
                    draw(g, tr, vanilla, item);
                } finally {
                    if (item.clip != null) g.disableScissor();
                }
            }
        } finally {
            g.pose().popMatrix();
            g.nextStratum();
            items.clear();
        }
    }

    private void draw(GuiGraphicsExtractor g, TextRenderer tr, boolean vanilla, Item item) {
        if (vanilla) {
            int step = step(item.size);
            int width = Math.max(0, mc.font.width(item.text) - 1) * step;
            int x = switch (item.align) {
                case LEFT -> item.x;
                case CENTER -> item.x - width / 2;
                case RIGHT -> item.x - width;
            };

            double middle = hasWideGlyphs(item.text) ? 4 : 3.5;
            int y = (int) Math.round(item.cy - middle * step);

            g.pose().pushMatrix();
            g.pose().translate(x, y);
            g.pose().scale(step, step);
            g.text(mc.font, item.text, 0, 0, item.argb, item.shadow);
            g.pose().popMatrix();
            return;
        }

        tr.begin(g, item.size * u / 18.0);

        try {
            double width = Math.round(tr.getWidth(item.text));
            double x = switch (item.align) {
                case LEFT -> item.x;
                case CENTER -> item.x - Math.round(width / 2);
                case RIGHT -> item.x - width;
            };

            color.set((item.argb >> 16) & 0xFF, (item.argb >> 8) & 0xFF, item.argb & 0xFF, item.argb >>> 24);
            tr.render(item.text, x, Math.round(item.cy - tr.getHeight() / 2), color, item.shadow);
        } finally {
            tr.end();
        }
    }

    public static boolean hasWideGlyphs(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x2E80) return true;
        }

        return false;
    }
}
