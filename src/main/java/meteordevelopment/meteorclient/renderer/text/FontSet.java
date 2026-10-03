/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer.text;

import meteordevelopment.meteorclient.MeteorClient;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * The font files behind the custom text renderer: the font the player picked (Latin letters, numbers, symbols) and a
 * fallback font for Chinese, Japanese and the other wide characters. Both are read once and shared by every
 * {@link Font}, which only keeps the glyphs of one pixel height.
 */
public final class FontSet {
    /** One loaded font file. The data has to stay alive as long as stb_truetype uses it. */
    static final class File {
        final ByteBuffer data;
        final STBTTFontinfo info;
        final int ascent;

        File(ByteBuffer data) {
            this.data = data;
            this.info = STBTTFontinfo.create();

            if (!STBTruetype.stbtt_InitFont(info, data)) throw new IllegalArgumentException("Not a usable TrueType font");

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer ascent = stack.mallocInt(1);
                STBTruetype.stbtt_GetFontVMetrics(info, ascent, null, null);
                this.ascent = ascent.get(0);
            }
        }

        boolean hasGlyph(int codePoint) {
            return STBTruetype.stbtt_FindGlyphIndex(info, codePoint) != 0;
        }
    }

    final File primary;
    /** Used for wide characters, and for anything the primary font does not have. Null when there is none. */
    final File fallback;

    public FontSet(FontFace primaryFace, FontFace fallbackFace) throws IOException {
        primary = new File(primaryFace.readToDirectByteBuffer());

        File fallbackFile = null;

        // When the player picked the fallback font itself there is nothing to fall back to
        if (fallbackFace != null && !fallbackFace.info.equals(primaryFace.info)) {
            try {
                fallbackFile = new File(fallbackFace.readToDirectByteBuffer());
            } catch (IOException | RuntimeException e) {
                MeteorClient.LOG.warn("Could not load the fallback font {}, wide characters will be missing.", fallbackFace, e);
            }
        }

        fallback = fallbackFile;
    }

    /** Whether the characters are drawn from the fallback font first: Chinese, Japanese and full width forms. */
    static boolean isWide(int codePoint) {
        return codePoint >= 0x2E80;
    }

    /** Whether at least one of the fonts has a glyph for the character. */
    public boolean canRender(int codePoint) {
        if (codePoint == ' ' || codePoint == '\n' || codePoint == '\t' || Character.isISOControl(codePoint)) return true;
        return primary.hasGlyph(codePoint) || fallback != null && fallback.hasGlyph(codePoint);
    }

    /** Whether the fonts can draw every character of the text. */
    public boolean canRender(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);

            if (!canRender(cp)) return false;
        }

        return true;
    }
}
