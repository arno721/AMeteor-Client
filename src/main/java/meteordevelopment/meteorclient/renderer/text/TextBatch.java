/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer.text;

import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the quads of text that is drawn with {@link Font}s. Glyphs can be on several textures, so there is one mesh
 * per texture, and {@link #flush()} draws them all.
 */
public final class TextBatch {
    private final Map<GlyphPage, MeshBuilder> active = new IdentityHashMap<>();
    private final List<MeshBuilder> pool = new ArrayList<>();
    private double alpha = 1;

    public void setAlpha(double alpha) {
        this.alpha = alpha;

        for (MeshBuilder mesh : active.values()) mesh.alpha = alpha;
        for (MeshBuilder mesh : pool) mesh.alpha = alpha;
    }

    /** The mesh for the quads that use this page, started when it is first asked for. */
    MeshBuilder mesh(GlyphPage page) {
        MeshBuilder mesh = active.get(page);
        if (mesh != null) return mesh;

        mesh = pool.isEmpty() ? new MeshBuilder(MeteorRenderPipelines.UI_TEXT) : pool.removeLast();
        mesh.alpha = alpha;
        mesh.begin();
        active.put(page, mesh);

        return mesh;
    }

    public boolean isEmpty() {
        return active.isEmpty();
    }

    /** Draws everything that was added and starts over. */
    public void flush() {
        if (active.isEmpty()) return;

        try {
            for (Map.Entry<GlyphPage, MeshBuilder> entry : active.entrySet()) {
                GlyphPage page = entry.getKey();
                MeshBuilder mesh = entry.getValue();

                mesh.end();
                page.upload();

                MeshRenderer.begin()
                    .attachments(Minecraft.getInstance().gameRenderer.mainRenderTarget())
                    .pipeline(MeteorRenderPipelines.UI_TEXT)
                    .mesh(mesh)
                    .sampler("u_Texture", page.texture.getTextureView(), page.texture.getSampler())
                    .end();
            }
        } finally {
            clear();
        }
    }

    /** Throws away what was added without drawing it. */
    public void clear() {
        for (MeshBuilder mesh : active.values()) {
            if (mesh.isBuilding()) mesh.end();
            pool.add(mesh);
        }

        active.clear();
    }
}
