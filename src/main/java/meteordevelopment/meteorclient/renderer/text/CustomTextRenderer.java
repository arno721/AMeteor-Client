/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer.text;

import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class CustomTextRenderer implements TextRenderer {
    public static final Color SHADOW_COLOR = new Color(60, 60, 60, 180);

    /** Text of scale 1 is this many pixels high. */
    private static final double PIXELS_PER_SCALE = 18;
    private static final int MIN_PIXELS = 6, MAX_PIXELS = 400;
    /** How many sizes are kept. Sizes that were not used for a while are thrown away. */
    private static final int MAX_SIZES = 16;

    private final TextBatch batch = new TextBatch();

    public final FontFace fontFace;
    private final FontSet fontSet;

    /**
     * One font for every size in pixels that is drawn. Every glyph is made for exactly the size it is drawn at and put
     * on a whole pixel, scaling a glyph made for another size makes text blurry.
     */
    private final LinkedHashMap<Integer, Font> fonts = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Font> eldest) {
            if (size() <= MAX_SIZES || building) return false;

            eldest.getValue().destroy();
            return true;
        }
    };

    private Font font;
    private int pixels = 18;

    private boolean building;
    private boolean scaleOnly;

    /**
     * @param fontFace the font for letters, numbers and symbols
     * @param fallback the font for Chinese, Japanese and the characters the first font does not have, can be null
     */
    public CustomTextRenderer(FontFace fontFace, FontFace fallback) throws IOException {
        this.fontFace = fontFace;
        this.fontSet = new FontSet(fontFace, fallback);

        fontFor((int) PIXELS_PER_SCALE);
    }

    /** The font files, for code that makes its own fonts of other heights. */
    public FontSet getFontSet() {
        return fontSet;
    }

    /** Whether the fonts can draw every character of the text. */
    public boolean canRender(String text) {
        return fontSet.canRender(text);
    }

    private Font fontFor(int pixels) {
        Font font = fonts.get(pixels);

        if (font == null) {
            font = new Font(fontSet, pixels);
            fonts.put(pixels, font);
        }

        return font;
    }

    @Override
    public void setAlpha(double a) {
        batch.setAlpha(a);
    }

    @Override
    public void begin(GuiGraphicsExtractor graphics, double scale, boolean scaleOnly, boolean big) {
        if (building) throw new RuntimeException("CustomTextRenderer.begin() called twice");

        this.pixels = (int) Math.max(MIN_PIXELS, Math.min(MAX_PIXELS, Math.round(PIXELS_PER_SCALE * scale)));
        this.font = fontFor(pixels);

        this.building = true;
        this.scaleOnly = scaleOnly;
    }

    @Override
    public double getWidth(String text, int length, boolean shadow) {
        if (text.isEmpty()) return 0;

        Font font = building ? this.font : fontFor((int) PIXELS_PER_SCALE);
        int pixels = building ? this.pixels : (int) PIXELS_PER_SCALE;

        return font.getWidth(text, length) + (shadow ? pixels / 27.0 : 0);
    }

    @Override
    public double getHeight(boolean shadow) {
        int pixels = building ? this.pixels : (int) PIXELS_PER_SCALE;

        return pixels + pixels / 27.0 * (shadow ? 2 : 1);
    }

    @Override
    public double render(String text, double x, double y, Color color, boolean shadow) {
        if (!building) throw new RuntimeException("CustomTextRenderer.render() called without calling begin()");

        double width;
        if (shadow) {
            int preShadowA = SHADOW_COLOR.a;
            SHADOW_COLOR.a = (int) (color.a / 255.0 * preShadowA);

            double offset = Math.max(1, Math.round(pixels / 27.0));
            width = font.render(batch, text, x + offset, y + offset, SHADOW_COLOR, 1, true);
            font.render(batch, text, x, y, color, 1, true);

            SHADOW_COLOR.a = preShadowA;
        } else {
            width = font.render(batch, text, x, y, color, 1, true);
        }

        return width;
    }

    @Override
    public boolean isBuilding() {
        return building;
    }

    @Override
    public void end() {
        if (!building) throw new RuntimeException("CustomTextRenderer.end() called without calling begin()");

        try {
            if (!scaleOnly) batch.flush();
        } finally {
            building = false;
        }
    }

    public void destroy() {
        batch.clear();

        for (Font font : fonts.values()) font.destroy();
        fonts.clear();
    }
}
