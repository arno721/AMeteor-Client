/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render.island;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.renderer.ExtendedRenderPipelineBuilder;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.Texture;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.Minecraft;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Draws images cut to a rounded box (or a circle) with a smooth edge, used for album covers. Images are queued while
 * drawing and drawn together with {@link #flush()}, so they can go between the shapes and the text.
 */
public final class IslandImage {
    public static final VertexFormat FORMAT = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RG32_FLOAT)
        .addAttribute("Texture", GpuFormat.RG32_FLOAT)
        .addAttribute("Local", GpuFormat.RG32_FLOAT)
        .addAttribute("Box", GpuFormat.RGBA32_FLOAT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .build();

    private static final RenderPipeline PIPELINE = new ExtendedRenderPipelineBuilder(RenderPipeline.builder()
        .withBindGroupLayout(BindGroupLayout.builder().withUniform("MeshData", UniformType.UNIFORM_BUFFER).build())
        .buildSnippet())
        .withLocation(MeteorClient.identifier("pipeline/island_image"))
        .withVertexBinding(0, FORMAT).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/island_image.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/island_image.frag"))
        .withBindGroupLayout(BindGroupLayout.builder().withSampler("u_Texture").build())
        .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withCull(false)
        .build();

    // Created after the format and the pipeline above, which it needs
    public static final IslandImage INSTANCE = new IslandImage();

    private enum State {
        UNKNOWN,
        READY,
        BROKEN
    }

    private static State state = State.UNKNOWN;

    private record Item(Texture texture, double cx, double cy, double halfW, double halfH, double radius, double angle, int argb) {
    }

    private final List<Item> queue = new ArrayList<>();
    private final MeshBuilder mesh = new MeshBuilder(FORMAT, PrimitiveTopology.TRIANGLES);
    private final Color color = new Color();

    private IslandImage() {
    }

    public static boolean isAvailable() {
        if (state == State.UNKNOWN) {
            try {
                CompiledRenderPipeline compiled = RenderSystem.getDevice().precompilePipeline(PIPELINE, (identifier, _) -> {
                    var resource = Minecraft.getInstance().getResourceManager().getResource(identifier).orElseThrow();

                    try (InputStream in = resource.open()) {
                        return IOUtils.toString(in, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                state = compiled != null && compiled.isValid() ? State.READY : State.BROKEN;
            } catch (Throwable t) {
                state = State.BROKEN;
            }

            if (state == State.BROKEN) MeteorClient.LOG.warn("Island image shader is not available, album covers are hidden.");
        }

        return state == State.READY;
    }

    public static void onShadersReloaded() {
        if (state == State.READY) state = State.UNKNOWN;
    }

    /** Queues an image. {@code angle} turns it around its center, which only looks right for circles. */
    public void add(Texture texture, double cx, double cy, double halfW, double halfH, double radius, double angle, int argb) {
        if (texture == null || (argb >>> 24) == 0 || halfW <= 0 || halfH <= 0) return;
        queue.add(new Item(texture, cx, cy, halfW, halfH, radius, angle, argb));
    }

    public void clear() {
        queue.clear();
    }

    /** Draws the queued images. */
    public void flush() {
        if (queue.isEmpty()) return;

        try {
            if (!isAvailable()) return;

            for (Item item : queue) draw(item);
        } catch (RuntimeException e) {
            state = State.BROKEN;
            if (mesh.isBuilding()) mesh.end();
            MeteorClient.LOG.warn("Island image drawing failed, album covers are hidden.", e);
        } finally {
            queue.clear();
        }
    }

    private void draw(Item item) {
        color.set((item.argb >> 16) & 0xFF, (item.argb >> 8) & 0xFF, item.argb & 0xFF, item.argb >>> 24);

        // One pixel more around the shape for the soft edge
        double mx = item.halfW + 1, my = item.halfH + 1;
        double ux = mx / item.halfW * 0.5, uy = my / item.halfH * 0.5;

        mesh.begin();
        mesh.ensureCapacity(4, 6);

        int i0 = vertex(item, -mx, -my, 0.5 - ux, 0.5 - uy);
        int i1 = vertex(item, -mx, my, 0.5 - ux, 0.5 + uy);
        int i2 = vertex(item, mx, my, 0.5 + ux, 0.5 + uy);
        int i3 = vertex(item, mx, -my, 0.5 + ux, 0.5 - uy);
        mesh.quad(i0, i1, i2, i3);

        MeshRenderer.begin()
            .attachments(mc.gameRenderer.mainRenderTarget())
            .pipeline(PIPELINE)
            .mesh(mesh)
            .sampler("u_Texture", item.texture.getTextureView(), item.texture.getSampler())
            .end();
    }

    private int vertex(Item item, double lx, double ly, double u, double v) {
        mesh.vec2(item.cx + lx, item.cy + ly);
        mesh.vec2(u, v);
        mesh.vec2(lx, ly);
        mesh.vec2(item.halfW, item.halfH).vec2(item.radius, item.angle);
        mesh.color(color);
        return mesh.next();
    }
}
