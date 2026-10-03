/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer.text;

import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.io.IOException;

public class CustomTextRenderer implements TextRenderer {
    public static final Color SHADOW_COLOR = new Color(60, 60, 60, 180);

    private final TextBatch batch = new TextBatch();

    public final FontFace fontFace;
    private final FontSet fontSet;

    private final Font[] fonts;
    private Font font;

    private boolean building;
    private boolean scaleOnly;
    private double fontScale = 1;
    private double scale = 1;

    /**
     * @param fontFace the font for letters, numbers and symbols
     * @param fallback the font for Chinese, Japanese and the characters the first font does not have, can be null
     */
    public CustomTextRenderer(FontFace fontFace, FontFace fallback) throws IOException {
        this.fontFace = fontFace;
        this.fontSet = new FontSet(fontFace, fallback);

        fonts = new Font[5];
        for (int i = 0; i < fonts.length; i++) {
            fonts[i] = new Font(fontSet, (int) Math.round(27 * ((i * 0.5) + 1)));
        }
    }

    /** The font files, for code that makes its own fonts of other heights. */
    public FontSet getFontSet() {
        return fontSet;
    }

    /** Whether the fonts can draw every character of the text. */
    public boolean canRender(String text) {
        return fontSet.canRender(text);
    }

    @Override
    public void setAlpha(double a) {
        batch.setAlpha(a);
    }

    @Override
    public void begin(GuiGraphicsExtractor graphics, double scale, boolean scaleOnly, boolean big) {
        if (building) throw new RuntimeException("CustomTextRenderer.begin() called twice");

        if (big) {
            this.font = fonts[fonts.length - 1];
        } else {
            double scaleA = Math.floor(scale * 10) / 10;

            int scaleI;
            if (scaleA >= 3) scaleI = 5;
            else if (scaleA >= 2.5) scaleI = 4;
            else if (scaleA >= 2) scaleI = 3;
            else if (scaleA >= 1.5) scaleI = 2;
            else scaleI = 1;

            font = fonts[scaleI - 1];
        }

        this.building = true;
        this.scaleOnly = scaleOnly;

        this.fontScale = font.getHeight() / 27.0;
        this.scale = 1 + (scale - fontScale) / fontScale;
    }

    @Override
    public double getWidth(String text, int length, boolean shadow) {
        if (text.isEmpty()) return 0;

        Font font = building ? this.font : fonts[0];
        return (font.getWidth(text, length) + (shadow ? 1 : 0)) * scale / 1.5;
    }

    @Override
    public double getHeight(boolean shadow) {
        Font font = building ? this.font : fonts[0];
        return (font.getHeight() + 1 + (shadow ? 1 : 0)) * scale / 1.5;
    }

    @Override
    public double render(String text, double x, double y, Color color, boolean shadow) {
        if (!building) throw new RuntimeException("CustomTextRenderer.render() called without calling begin()");

        double width;
        if (shadow) {
            int preShadowA = SHADOW_COLOR.a;
            SHADOW_COLOR.a = (int) (color.a / 255.0 * preShadowA);

            width = font.render(batch, text, x + fontScale * scale / 1.5, y + fontScale * scale / 1.5, SHADOW_COLOR, scale / 1.5);
            font.render(batch, text, x, y, color, scale / 1.5);

            SHADOW_COLOR.a = preShadowA;
        } else {
            width = font.render(batch, text, x, y, color, scale / 1.5);
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
            scale = 1;
        }
    }

    public void destroy() {
        batch.clear();
        for (Font font : this.fonts) font.destroy();
    }
}
