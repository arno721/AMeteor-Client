/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.music;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.FilterMode;
import meteordevelopment.meteorclient.renderer.Texture;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * The cover of the current track as a texture, plus colors picked from it. Has to be updated on the render thread.
 */
public final class Artwork {
    public static final Artwork INSTANCE = new Artwork();

    private static final int MAX_SIZE = 256;

    private Texture texture;
    /** Old covers stay alive for a moment, a fading card can still be drawing them. */
    private final java.util.ArrayDeque<Texture> retired = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Long> retiredAt = new java.util.ArrayDeque<>();
    private int version = -1;
    private int accent = 0xFF8C78FF, average = 0xFF303036;
    private boolean hasColors;

    private Artwork() {
    }

    /** The cover, or null when the track has none. */
    public Texture getTexture() {
        return texture;
    }

    /** A bright, saturated color from the cover, good for accents. */
    public int getAccent() {
        return accent;
    }

    /** The average color of the cover. */
    public int getAverage() {
        return average;
    }

    public boolean hasColors() {
        return hasColors;
    }

    /** Picks up a new cover from the media bridge. Call once per frame from the render thread. */
    public void update() {
        long now = System.nanoTime();
        while (!retiredAt.isEmpty() && now - retiredAt.peekFirst() > 3_000_000_000L) {
            retiredAt.pollFirst();
            retired.pollFirst().close();
        }

        MediaBridge bridge = MediaBridge.INSTANCE;
        int newVersion = bridge.getArtworkVersion();
        if (newVersion == version) return;

        version = newVersion;
        byte[] data = bridge.getArtwork();
        retire();

        hasColors = false;
        if (data == null || data.length == 0) return;

        try {
            load(data);
        } catch (RuntimeException e) {
            texture = null;
        }
    }

    public void clear() {
        retire();
        hasColors = false;
        version = -1;
    }

    private void retire() {
        if (texture == null) return;

        retired.addLast(texture);
        retiredAt.addLast(System.nanoTime());
        texture = null;
    }

    private void load(byte[] data) {
        ByteBuffer encoded = BufferUtils.createByteBuffer(data.length).put(data).flip();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), comp = stack.mallocInt(1);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, w, h, comp, 4);
            if (pixels == null) return;

            try {
                int width = w.get(0), height = h.get(0);

                // Square crop from the middle, covers from video sites are often 16:9
                int side = Math.min(width, height);
                int x0 = (width - side) / 2, y0 = (height - side) / 2;
                int size = Math.min(side, MAX_SIZE);

                ByteBuffer scaled = BufferUtils.createByteBuffer(size * size * 4);
                downscale(pixels, width, x0, y0, side, scaled, size);

                texture = new Texture(size, size, GpuFormat.RGBA8_UNORM, FilterMode.LINEAR, FilterMode.LINEAR);
                texture.upload(scaled);

                pickColors(scaled, size);
            } finally {
                STBImage.stbi_image_free(pixels);
            }
        }
    }

    /** Averages every block of source pixels into one target pixel, which looks smooth when shrinking a lot. */
    private static void downscale(ByteBuffer src, int srcWidth, int x0, int y0, int side, ByteBuffer dst, int size) {
        double scale = side / (double) size;

        for (int ty = 0; ty < size; ty++) {
            int sy0 = y0 + (int) (ty * scale), sy1 = Math.max(sy0 + 1, y0 + (int) ((ty + 1) * scale));

            for (int tx = 0; tx < size; tx++) {
                int sx0 = x0 + (int) (tx * scale), sx1 = Math.max(sx0 + 1, x0 + (int) ((tx + 1) * scale));
                long r = 0, g = 0, b = 0, a = 0, n = 0;

                for (int sy = sy0; sy < sy1; sy++) {
                    for (int sx = sx0; sx < sx1; sx++) {
                        int i = (sy * srcWidth + sx) * 4;
                        r += src.get(i) & 0xFF;
                        g += src.get(i + 1) & 0xFF;
                        b += src.get(i + 2) & 0xFF;
                        a += src.get(i + 3) & 0xFF;
                        n++;
                    }
                }

                int o = (ty * size + tx) * 4;
                dst.put(o, (byte) (r / n));
                dst.put(o + 1, (byte) (g / n));
                dst.put(o + 2, (byte) (b / n));
                dst.put(o + 3, (byte) (a / n));
            }
        }
    }

    /** The accent is the average of the colorful pixels, made a little brighter, so it reads well on dark glass. */
    private void pickColors(ByteBuffer pixels, int size) {
        double ar = 0, ag = 0, ab = 0, n = 0;
        double vr = 0, vg = 0, vb = 0, weight = 0;

        for (int i = 0; i < size * size; i += 3) {
            int r = pixels.get(i * 4) & 0xFF, g = pixels.get(i * 4 + 1) & 0xFF, b = pixels.get(i * 4 + 2) & 0xFF;

            ar += r;
            ag += g;
            ab += b;
            n++;

            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            double saturation = max == 0 ? 0 : (max - min) / (double) max;
            double value = max / 255.0;
            double w = Math.pow(saturation, 2) * value * (value > 0.18 ? 1 : 0.1);

            vr += r * w;
            vg += g * w;
            vb += b * w;
            weight += w;
        }

        if (n == 0) return;

        average = 0xFF000000 | (int) (ar / n) << 16 | (int) (ag / n) << 8 | (int) (ab / n);

        float[] hsv;
        if (weight > n * 0.01) hsv = toHsv(vr / weight, vg / weight, vb / weight);
        else hsv = toHsv(ar / n, ag / n, ab / n);

        // Colorless covers get a soft grey-white accent instead of a muddy one
        float saturation = weight > n * 0.01 ? Math.max(hsv[1], 0.45f) : hsv[1] * 0.4f;
        float value = Math.max(hsv[2], 0.85f);

        accent = 0xFF000000 | java.awt.Color.HSBtoRGB(hsv[0], Math.min(saturation, 0.85f), Math.min(value, 1f)) & 0xFFFFFF;
        hasColors = true;
    }

    private static float[] toHsv(double r, double g, double b) {
        return java.awt.Color.RGBtoHSB((int) Math.round(r), (int) Math.round(g), (int) Math.round(b), null);
    }
}
