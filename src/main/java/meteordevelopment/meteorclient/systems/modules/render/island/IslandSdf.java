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
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.Minecraft;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Draws rounded boxes with a signed distance shader, so the edges, glows and shadows are smooth at any size. Each
 * shape is a single quad and the shader works out the shape per pixel. Two boxes can be melted together, which is how
 * the island splits into two pills.
 * <p>
 * The shader is compiled the first time it is needed. If that fails (old drivers, a backend that does not like it),
 * {@link #isAvailable()} returns false for good and the island falls back to drawing triangles.
 */
public final class IslandSdf {
    public static final int FILL = 0;
    public static final int FILL_HORIZONTAL = 1;
    public static final int SHADOW = 2;
    public static final int GLOW = 3;
    public static final int ARC = 4;

    public static final VertexFormat FORMAT = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RG32_FLOAT)
        .addAttribute("Local", GpuFormat.RG32_FLOAT)
        .addAttribute("Box", GpuFormat.RGBA32_FLOAT)
        .addAttribute("Box2", GpuFormat.RGBA32_FLOAT)
        .addAttribute("Params", GpuFormat.RGBA32_FLOAT)
        .addAttribute("Box3", GpuFormat.RGBA32_FLOAT)
        .addAttribute("Params3", GpuFormat.RGBA32_FLOAT)
        .addAttribute("ColorA", GpuFormat.RGBA8_UNORM)
        .addAttribute("ColorB", GpuFormat.RGBA8_UNORM)
        .addAttribute("ColorC", GpuFormat.RGBA8_UNORM)
        .build();

    private static final RenderPipeline PIPELINE = new ExtendedRenderPipelineBuilder(RenderPipeline.builder()
        .withBindGroupLayout(BindGroupLayout.builder().withUniform("MeshData", UniformType.UNIFORM_BUFFER).build())
        .buildSnippet())
        .withLocation(MeteorClient.identifier("pipeline/island_sdf"))
        .withVertexBinding(0, FORMAT).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/island_sdf.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/island_sdf.frag"))
        .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withCull(false)
        .build();

    private enum State {
        UNKNOWN,
        READY,
        BROKEN
    }

    private static State state = State.UNKNOWN;

    private final MeshBuilder mesh = new MeshBuilder(FORMAT, PrimitiveTopology.TRIANGLES);
    private final Color colorA = new Color(), colorB = new Color(), colorC = new Color();
    private boolean building;

    /** The third box of the shape that is being added, offset from the first. A width of 0 means there is none. */
    private double ox3, oy3, halfW3, halfH3, radius3, melt3;

    /** Compiles the shader if needed. Returns false when it cannot be used. */
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

            if (state == State.BROKEN) MeteorClient.LOG.warn("Dynamic Island shader is not available, using the fallback renderer.");
        }

        return state == State.READY;
    }

    /** Stops using the shader for good, for example when drawing with it failed. */
    public static void markBroken() {
        state = State.BROKEN;
    }

    /** Called after shaders are reloaded, which throws away compiled pipelines. */
    public static void onShadersReloaded() {
        if (state == State.READY) state = State.UNKNOWN;
    }

    public void begin() {
        mesh.begin();
        building = true;
    }

    /**
     * A rounded box, centered on (cx, cy).
     *
     * @param size   border width for {@link #FILL}, blur sigma for {@link #SHADOW}, reach for {@link #GLOW}
     * @param bias   how much brighter the border is at the top, from 0 to 1
     */
    public void box(double cx, double cy, double halfW, double halfH, double radius, int mode, double size, double bias, int a, int b, int c) {
        pair(cx, cy, halfW, halfH, radius, 0, 0, 0, 0, 0, 0, mode, size, bias, a, b, c);
    }

    /**
     * Two rounded boxes melted together. The second box is placed at (ox, oy) from the center of the first, and
     * {@code melt} is the distance over which they blend. A second box with no width is left out.
     */
    public void pair(double cx, double cy, double halfW, double halfH, double radius,
                     double ox, double oy, double halfW2, double halfH2, double radius2, double melt,
                     int mode, double size, double bias, int a, int b, int c) {
        trio(cx, cy, halfW, halfH, radius, ox, oy, halfW2, halfH2, radius2, melt, 0, 0, 0, 0, 0, 0, mode, size, bias, a, b, c);
    }

    /**
     * Three rounded boxes melted together: the island in the middle and a small pill on each side. The second and the
     * third box are placed relative to the center of the first, each with its own melt distance. Boxes with no width
     * are left out.
     */
    public void trio(double cx, double cy, double halfW, double halfH, double radius,
                     double ox, double oy, double halfW2, double halfH2, double radius2, double melt,
                     double ox3, double oy3, double halfW3, double halfH3, double radius3, double melt3,
                     int mode, double size, double bias, int a, int b, int c) {
        if (!building || ((a | b) >>> 24) == 0 && (mode != FILL || (c >>> 24) == 0)) return;
        if (halfW <= 0 || halfH <= 0) return;

        // How far outside the shape something can be drawn
        double margin = switch (mode) {
            case SHADOW -> size * 3.2 + 2;
            case GLOW -> size + 2;
            default -> 2;
        } + Math.max(Math.max(melt, melt3), 0) * 0.25;

        double x0 = -halfW, x1 = halfW, y0 = -halfH, y1 = halfH;

        if (halfW2 > 0) {
            x0 = Math.min(x0, ox - halfW2);
            x1 = Math.max(x1, ox + halfW2);
            y0 = Math.min(y0, oy - halfH2);
            y1 = Math.max(y1, oy + halfH2);
        }

        if (halfW3 > 0) {
            x0 = Math.min(x0, ox3 - halfW3);
            x1 = Math.max(x1, ox3 + halfW3);
            y0 = Math.min(y0, oy3 - halfH3);
            y1 = Math.max(y1, oy3 + halfH3);
        }

        this.ox3 = ox3;
        this.oy3 = oy3;
        this.halfW3 = halfW3;
        this.halfH3 = halfH3;
        this.radius3 = radius3;
        this.melt3 = melt3;

        x0 -= margin;
        y0 -= margin;
        x1 += margin;
        y1 += margin;

        set(colorA, a);
        set(colorB, b);
        set(colorC, c);

        mesh.ensureCapacity(4, 6);

        int i0 = vertex(cx, cy, x0, y0, halfW, halfH, radius, mode, ox, oy, halfW2, halfH2, radius2, melt, size, bias);
        int i1 = vertex(cx, cy, x0, y1, halfW, halfH, radius, mode, ox, oy, halfW2, halfH2, radius2, melt, size, bias);
        int i2 = vertex(cx, cy, x1, y1, halfW, halfH, radius, mode, ox, oy, halfW2, halfH2, radius2, melt, size, bias);
        int i3 = vertex(cx, cy, x1, y0, halfW, halfH, radius, mode, ox, oy, halfW2, halfH2, radius2, melt, size, bias);

        mesh.quad(i0, i1, i2, i3);

        this.halfW3 = 0;
    }

    /**
     * A curved band with round ends, centered on the circle. Angles are in radians, 0 points right and positive turns
     * clockwise on screen. A sweep of a full turn or more draws a whole ring.
     */
    public void arc(double cx, double cy, double radius, double thickness, double start, double sweep, int argb) {
        if (!building || (argb >>> 24) == 0 || sweep <= 0.0001 || radius <= 0) return;

        double half = thickness / 2;
        double extent = radius + half + 2;

        set(colorA, argb);
        set(colorB, argb);
        set(colorC, 0);

        mesh.ensureCapacity(4, 6);

        int i0 = vertex(cx, cy, -extent, -extent, radius, half, sweep, ARC, 0, 0, 0, 0, 0, 0, 0, start);
        int i1 = vertex(cx, cy, -extent, extent, radius, half, sweep, ARC, 0, 0, 0, 0, 0, 0, 0, start);
        int i2 = vertex(cx, cy, extent, extent, radius, half, sweep, ARC, 0, 0, 0, 0, 0, 0, 0, start);
        int i3 = vertex(cx, cy, extent, -extent, radius, half, sweep, ARC, 0, 0, 0, 0, 0, 0, 0, start);

        mesh.quad(i0, i1, i2, i3);
    }

    private int vertex(double cx, double cy, double lx, double ly, double halfW, double halfH, double radius, int mode,
                       double ox, double oy, double halfW2, double halfH2, double radius2, double melt, double size, double bias) {
        mesh.vec2(cx + lx, cy + ly);
        mesh.vec2(lx, ly);
        mesh.vec2(halfW, halfH).vec2(radius, mode);
        mesh.vec2(ox, oy).vec2(halfW2, halfH2);
        mesh.vec2(radius2, melt).vec2(size, bias);
        mesh.vec2(ox3, oy3).vec2(halfW3, halfH3);
        mesh.vec2(radius3, melt3).vec2(0, 0);
        mesh.color(colorA).color(colorB).color(colorC);

        return mesh.next();
    }

    /** Draws what was added since {@link #begin()}. */
    public void render() {
        building = false;

        if (mesh.getIndicesCount() == 0) {
            mesh.end();
            return;
        }

        MeshRenderer.begin()
            .attachments(mc.gameRenderer.mainRenderTarget())
            .pipeline(PIPELINE)
            .mesh(mesh)
            .end();
    }

    /** Throws away what was added, for when drawing failed half way. */
    public void abort() {
        building = false;
        if (mesh.isBuilding()) mesh.end();
    }

    private static void set(Color color, int argb) {
        color.set((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, argb >>> 24);
    }
}
