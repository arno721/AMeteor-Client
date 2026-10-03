/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer.text;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.utils.render.color.Color;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The glyphs of a {@link FontSet} at one pixel height. Glyphs are turned into pixels the first time they are needed
 * (a few thousand Chinese characters would not fit on one texture, and most are never used), and put on pages that
 * are sent to the GPU when something new was added.
 * <p>
 * Wide characters (Chinese, Japanese, full width forms) come from the fallback font, everything else from the font the
 * player picked. When that font has no glyph for a character, the other one is tried.
 */
public class Font {
    /** Empty space around every glyph, so linear filtering does not pick up pixels of the glyph next to it. */
    private static final int PADDING = 2;

    /**
     * The thin strokes of a calligraphy font only cover part of a pixel at text sizes and there is no hinting, so
     * light text on a dark background looks faint. This curve makes partly covered pixels stronger.
     */
    private static final byte[] COVERAGE = new byte[256];

    static {
        for (int i = 0; i < 256; i++) COVERAGE[i] = (byte) Math.min(255, Math.round(255 * Math.pow(i / 255.0, 0.72)));
    }

    private final FontSet set;
    private final int height;
    private final float scale, fallbackScale;
    private final float ascent;

    private final Int2ObjectOpenHashMap<Glyph> glyphs = new Int2ObjectOpenHashMap<>();
    private final List<GlyphPage> pages = new ArrayList<>();
    private final Glyph blank;

    public Font(FontSet set, int height) {
        this.set = set;
        this.height = height;

        scale = STBTruetype.stbtt_ScaleForPixelHeight(set.primary.info, height);
        fallbackScale = set.fallback != null ? STBTruetype.stbtt_ScaleForPixelHeight(set.fallback.info, height) : 0;
        ascent = set.primary.ascent;
        blank = new Glyph(null, 0, 0, 0, 0, 0, 0, 0, 0, height * 0.3f);

        // The letters that nearly every text has, so the first screen does not stutter
        for (int cp = 32; cp < 127; cp++) glyph(cp);
        for (int cp = 160; cp < 256; cp++) glyph(cp);
    }

    public double getWidth(String string, int length) {
        double width = 0;
        length = Math.min(length, string.length());

        for (int i = 0; i < length; ) {
            int cp = string.codePointAt(i);
            i += Character.charCount(cp);

            width += glyph(cp).xAdvance;
        }

        return width;
    }

    public int getHeight() {
        return height;
    }

    public double render(TextBatch batch, String string, double x, double y, Color color, double scale) {
        return render(batch, string, x, y, color, scale, false);
    }

    /**
     * @param snap puts every glyph on a whole pixel. With a font made for exactly the size that is drawn (scale 1) the
     *             glyph pixels then land on screen pixels one to one, which is as sharp as text can be.
     */
    public double render(TextBatch batch, String string, double x, double y, Color color, double scale, boolean snap) {
        y += ascent * this.scale * scale;
        double baseline = snap ? Math.round(y) : y;

        int length = string.length();

        for (int i = 0; i < length; ) {
            int cp = string.codePointAt(i);
            i += Character.charCount(cp);

            Glyph c = glyph(cp);

            if (c.page != null) {
                MeshBuilder mesh = batch.mesh(c.page);
                mesh.ensureQuadCapacity();

                double left = snap ? Math.round(x + c.x0 * scale) : x + c.x0 * scale;
                double right = left + (c.x1 - c.x0) * scale;
                double top = baseline + c.y0 * scale;
                double bottom = baseline + c.y1 * scale;

                mesh.quad(
                    mesh.vec2(left, top).vec2(c.u0, c.v0).color(color).next(),
                    mesh.vec2(left, bottom).vec2(c.u0, c.v1).color(color).next(),
                    mesh.vec2(right, bottom).vec2(c.u1, c.v1).color(color).next(),
                    mesh.vec2(right, top).vec2(c.u1, c.v0).color(color).next()
                );
            }

            x += c.xAdvance * scale;
        }

        return x;
    }

    public void destroy() {
        for (GlyphPage page : pages) page.destroy();

        pages.clear();
        glyphs.clear();
    }

    // Glyphs

    private Glyph glyph(int codePoint) {
        Glyph glyph = glyphs.get(codePoint);
        if (glyph != null) return glyph;

        glyph = create(codePoint);
        glyphs.put(codePoint, glyph);
        return glyph;
    }

    private Glyph create(int codePoint) {
        FontSet.File first = set.primary, second = set.fallback;
        float firstScale = scale, secondScale = fallbackScale;

        if (FontSet.isWide(codePoint) && second != null) {
            first = set.fallback;
            second = set.primary;
            firstScale = fallbackScale;
            secondScale = scale;
        }

        if (first.hasGlyph(codePoint)) return rasterize(first, firstScale, codePoint);
        if (second != null && second.hasGlyph(codePoint)) return rasterize(second, secondScale, codePoint);

        // Tabs, line breaks and characters that no font has are drawn as a gap
        return codePoint == ' ' ? blank : glyph(' ');
    }

    private Glyph rasterize(FontSet.File file, float fileScale, int codePoint) {
        int index = STBTruetype.stbtt_FindGlyphIndex(file.info, codePoint);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer advance = stack.mallocInt(1), bearing = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphHMetrics(file.info, index, advance, bearing);
            float xAdvance = advance.get(0) * fileScale;

            IntBuffer x0 = stack.mallocInt(1), y0 = stack.mallocInt(1), x1 = stack.mallocInt(1), y1 = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphBitmapBox(file.info, index, fileScale, fileScale, x0, y0, x1, y1);

            int width = x1.get(0) - x0.get(0), glyphHeight = y1.get(0) - y0.get(0);
            if (width <= 0 || glyphHeight <= 0) return new Glyph(null, 0, 0, 0, 0, 0, 0, 0, 0, xAdvance);

            int[] position = null;
            GlyphPage page = pages.isEmpty() ? null : pages.getLast();

            if (page != null) position = page.allocate(width + PADDING * 2, glyphHeight + PADDING * 2);

            if (position == null) {
                page = new GlyphPage();
                pages.add(page);
                position = page.allocate(width + PADDING * 2, glyphHeight + PADDING * 2);

                if (position == null) return new Glyph(null, 0, 0, 0, 0, 0, 0, 0, 0, xAdvance);
            }

            int px = position[0] + PADDING, py = position[1] + PADDING;

            // Draws straight into the page, the rows are GlyphPage.SIZE bytes apart
            int offset = py * GlyphPage.SIZE + px;
            ByteBuffer target = page.bitmap.slice(offset, page.bitmap.capacity() - offset);
            STBTruetype.stbtt_MakeGlyphBitmap(file.info, target, width, glyphHeight, GlyphPage.SIZE, fileScale, fileScale, index);

            for (int row = 0; row < glyphHeight; row++) {
                int start = (py + row) * GlyphPage.SIZE + px;

                for (int column = 0; column < width; column++) {
                    int at = start + column;
                    page.bitmap.put(at, COVERAGE[page.bitmap.get(at) & 0xFF]);
                }
            }

            page.markDirty();

            float inverse = 1f / GlyphPage.SIZE;

            return new Glyph(page,
                x0.get(0), y0.get(0), x1.get(0), y1.get(0),
                px * inverse, py * inverse, (px + width) * inverse, (py + glyphHeight) * inverse,
                xAdvance);
        }
    }

    private record Glyph(GlyphPage page, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float xAdvance) {
    }
}
