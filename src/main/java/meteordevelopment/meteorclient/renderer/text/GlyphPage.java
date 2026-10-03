/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer.text;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.FilterMode;
import meteordevelopment.meteorclient.renderer.Texture;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

/**
 * One texture full of glyphs. Glyphs are added row by row as they are first needed, and the page is sent to the GPU
 * again before it is drawn, when something new was added.
 */
final class GlyphPage {
    static final int SIZE = 1024;

    final Texture texture = new Texture(SIZE, SIZE, GpuFormat.R8_UNORM, FilterMode.LINEAR, FilterMode.LINEAR);
    final ByteBuffer bitmap = BufferUtils.createByteBuffer(SIZE * SIZE);

    private boolean dirty = true;
    private int cursorX, cursorY, rowHeight;

    /** Reserves a rectangle. Returns {x, y}, or null when the page is full. */
    int[] allocate(int width, int height) {
        if (width > SIZE || height > SIZE) return null;

        if (cursorX + width > SIZE) {
            cursorX = 0;
            cursorY += rowHeight;
            rowHeight = 0;
        }

        if (cursorY + height > SIZE) return null;

        int[] position = {cursorX, cursorY};

        cursorX += width;
        rowHeight = Math.max(rowHeight, height);

        return position;
    }

    void markDirty() {
        dirty = true;
    }

    /** Sends the page to the GPU if glyphs were added since the last time. */
    void upload() {
        if (!dirty) return;

        texture.upload(bitmap);
        dirty = false;
    }

    void destroy() {
        texture.close();
    }
}
