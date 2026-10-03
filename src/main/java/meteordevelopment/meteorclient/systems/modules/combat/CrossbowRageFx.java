/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.renderer.ExtendedRenderPipelineBuilder;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The shader effects of the Crossbow Ragebot: a reticle round the target, a radar on the ground, a beam of light, ribbons for
 * the path of the arrow and the bursts where it lands. Each is one quad (or a strip of them) and the shader ragefx.frag
 * draws the effect on it, so it is sharp and glows at any distance. Everything is added to the picture.
 * <p>
 * The shader is compiled the first time it is needed. If that fails, {@link #isAvailable()} returns false for good and the
 * module draws its lines instead.
 */
public final class CrossbowRageFx {
    public static final int RETICLE = 0;
    public static final int RADAR = 1;
    public static final int BEAM = 2;
    public static final int RIBBON = 3;
    public static final int BURST = 4;

    private static final VertexFormat FORMAT = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("Uv", GpuFormat.RG32_FLOAT)
        .addAttribute("P1", GpuFormat.RGBA32_FLOAT)
        .addAttribute("P2", GpuFormat.RGBA32_FLOAT)
        .addAttribute("ColorA", GpuFormat.RGBA8_UNORM)
        .addAttribute("ColorB", GpuFormat.RGBA8_UNORM)
        .build();

    private static RenderPipeline pipeline(String name, boolean depth) {
        return new ExtendedRenderPipelineBuilder(RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("MeshData", UniformType.UNIFORM_BUFFER).build())
            .buildSnippet())
            .withLocation(MeteorClient.identifier("pipeline/" + name))
            .withVertexBinding(0, FORMAT).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withVertexShader(MeteorClient.identifier("shaders/ragefx.vert"))
            .withFragmentShader(MeteorClient.identifier("shaders/ragefx.frag"))
            .withDepthStencilState(depth ? new DepthStencilState(DepthStencilState.DEFAULT.depthTest(), false) : new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ONE)))
            .withCull(false)
            .build();
    }

    private static final RenderPipeline PIPELINE = pipeline("ragefx", false);
    private static final RenderPipeline PIPELINE_DEPTH = pipeline("ragefx_depth", true);

    private enum State {
        UNKNOWN,
        READY,
        BROKEN
    }

    private static State state = State.UNKNOWN;

    private final MeshBuilder mesh = new MeshBuilder(FORMAT, PrimitiveTopology.TRIANGLES);
    private final Color ca = new Color(), cb = new Color();

    private double time, intensity = 1, glow = 1, speed = 1;
    private double cameraX, cameraY, cameraZ;
    private boolean building;

    /** Compiles the shader if needed. Returns false when it cannot be used. */
    public static boolean isAvailable() {
        if (state == State.UNKNOWN) {
            try {
                state = compile(PIPELINE) && compile(PIPELINE_DEPTH) ? State.READY : State.BROKEN;
            } catch (Throwable t) {
                state = State.BROKEN;
            }

            if (state == State.BROKEN) MeteorClient.LOG.warn("Crossbow Ragebot shader is not available, using lines.");
        }

        return state == State.READY;
    }

    private static boolean compile(RenderPipeline pipeline) {
        CompiledRenderPipeline compiled = RenderSystem.getDevice().precompilePipeline(pipeline, (identifier, _) -> {
            var resource = Minecraft.getInstance().getResourceManager().getResource(identifier).orElseThrow();

            try (InputStream in = resource.open()) {
                return IOUtils.toString(in, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        return compiled != null && compiled.isValid();
    }

    public static void markBroken() {
        state = State.BROKEN;
    }

    /** Called after shaders are reloaded, which throws away compiled pipelines. */
    public static void onShadersReloaded() {
        if (state == State.READY) state = State.UNKNOWN;
    }

    // Frame

    public void begin(Vec3 camera, double time, double intensity, double glow, double speed) {
        mesh.begin();
        building = true;

        this.cameraX = camera.x;
        this.cameraY = camera.y;
        this.cameraZ = camera.z;
        this.time = time;
        this.intensity = intensity;
        this.glow = glow;
        this.speed = speed;
    }

    public void render(PoseStack matrices, boolean depth) {
        building = false;

        if (mesh.getIndicesCount() == 0) {
            mesh.end();
            return;
        }

        MeshRenderer.begin()
            .attachments(mc.gameRenderer.mainRenderTarget())
            .pipeline(depth ? PIPELINE_DEPTH : PIPELINE)
            .mesh(mesh, matrices)
            .end();
    }

    /** Throws away what was added, for when drawing failed half way. */
    public void abort() {
        building = false;
        if (mesh.isBuilding()) mesh.end();
    }

    public boolean isBuilding() {
        return building;
    }

    // Shapes

    private int vertex(double x, double y, double z, double u, double v, int effect, double progress, double a, double b) {
        mesh.vec3(x, y, z).vec2(u, v).vec2(effect, progress).vec2(a, b).vec2(time, intensity).vec2(glow, speed).color(ca).color(cb);
        return mesh.next();
    }

    private void colors(Color a, Color b, double opacity) {
        int alpha = (int) Math.max(0, Math.min(255, 255 * opacity));
        ca.set(a.r, a.g, a.b, alpha);
        cb.set(b.r, b.g, b.b, alpha);
    }

    /** A square that turns to face the camera. */
    public void billboard(Vec3 centre, double half, int effect, double progress, double a, double b, Color colorA, Color colorB, double opacity) {
        double fx = centre.x - cameraX, fy = centre.y - cameraY, fz = centre.z - cameraZ;
        double length = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (length < 1e-4) return;

        fx /= length;
        fy /= length;
        fz /= length;

        // right = up x forward, up = forward x right
        double rx = fz, ry = 0, rz = -fx;
        double rl = Math.sqrt(rx * rx + rz * rz);

        if (rl < 1e-4) {
            rx = 1;
            rz = 0;
        } else {
            rx /= rl;
            rz /= rl;
        }

        double ux = fy * rz - fz * ry, uy = fz * rx - fx * rz, uz = fx * ry - fy * rx;

        colors(colorA, colorB, opacity);
        mesh.ensureCapacity(4, 6);

        int i0 = vertex(centre.x - rx * half - ux * half, centre.y - ry * half - uy * half, centre.z - rz * half - uz * half, -1, -1, effect, progress, a, b);
        int i1 = vertex(centre.x + rx * half - ux * half, centre.y + ry * half - uy * half, centre.z + rz * half - uz * half, 1, -1, effect, progress, a, b);
        int i2 = vertex(centre.x + rx * half + ux * half, centre.y + ry * half + uy * half, centre.z + rz * half + uz * half, 1, 1, effect, progress, a, b);
        int i3 = vertex(centre.x - rx * half + ux * half, centre.y - ry * half + uy * half, centre.z - rz * half + uz * half, -1, 1, effect, progress, a, b);
        mesh.quad(i0, i1, i2, i3);
    }

    /** A square lying flat. */
    public void flat(Vec3 centre, double half, int effect, double progress, double a, double b, Color colorA, Color colorB, double opacity) {
        colors(colorA, colorB, opacity);
        mesh.ensureCapacity(4, 6);

        int i0 = vertex(centre.x - half, centre.y, centre.z - half, -1, -1, effect, progress, a, b);
        int i1 = vertex(centre.x + half, centre.y, centre.z - half, 1, -1, effect, progress, a, b);
        int i2 = vertex(centre.x + half, centre.y, centre.z + half, 1, 1, effect, progress, a, b);
        int i3 = vertex(centre.x - half, centre.y, centre.z + half, -1, 1, effect, progress, a, b);
        mesh.quad(i0, i1, i2, i3);
    }

    /** Two crossed vertical strips from the base up. */
    public void beam(Vec3 base, double halfWidth, double height, Color colorA, Color colorB, double opacity) {
        colors(colorA, colorB, opacity);

        for (int strip = 0; strip < 2; strip++) {
            double dx = strip == 0 ? halfWidth : 0, dz = strip == 0 ? 0 : halfWidth;

            mesh.ensureCapacity(4, 6);
            int i0 = vertex(base.x - dx, base.y, base.z - dz, -1, 0, BEAM, 0, 0, 0);
            int i1 = vertex(base.x + dx, base.y, base.z + dz, 1, 0, BEAM, 0, 0, 0);
            int i2 = vertex(base.x + dx, base.y + height, base.z + dz, 1, 1, BEAM, 0, 0, 0);
            int i3 = vertex(base.x - dx, base.y + height, base.z - dz, -1, 1, BEAM, 0, 0, 0);
            mesh.quad(i0, i1, i2, i3);
        }
    }

    /** A strip along a path that turns to face the camera. {@code visible} is how strong it is, from 0 to 1. */
    public void ribbon(List<Vec3> points, double halfWidth, double visible, boolean flow, Color colorA, Color colorB, double opacity) {
        if (points.size() < 2) return;

        double total = 0;
        for (int i = 1; i < points.size(); i++) total += points.get(i).distanceTo(points.get(i - 1));
        if (total < 1e-3) return;

        colors(colorA, colorB, opacity);
        double travelled = 0;

        for (int i = 1; i < points.size(); i++) {
            Vec3 a = points.get(i - 1), b = points.get(i);
            double length = a.distanceTo(b);
            if (length < 1e-4) continue;

            double dx = (b.x - a.x) / length, dy = (b.y - a.y) / length, dz = (b.z - a.z) / length;
            double mx = cameraX - (a.x + b.x) / 2, my = cameraY - (a.y + b.y) / 2, mz = cameraZ - (a.z + b.z) / 2;

            // side = direction x towards the camera
            double sx = dy * mz - dz * my, sy = dz * mx - dx * mz, sz = dx * my - dy * mx;
            double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);

            if (sl < 1e-6) {
                travelled += length;
                continue;
            }

            sx = sx / sl * halfWidth;
            sy = sy / sl * halfWidth;
            sz = sz / sl * halfWidth;

            double f0 = travelled / total, f1 = (travelled + length) / total;
            double flowFlag = flow ? 1 : 0;

            mesh.ensureCapacity(4, 6);
            int i0 = vertex(a.x - sx, a.y - sy, a.z - sz, travelled, -1, RIBBON, visible, f0, flowFlag);
            int i1 = vertex(b.x - sx, b.y - sy, b.z - sz, travelled + length, -1, RIBBON, visible, f1, flowFlag);
            int i2 = vertex(b.x + sx, b.y + sy, b.z + sz, travelled + length, 1, RIBBON, visible, f1, flowFlag);
            int i3 = vertex(a.x + sx, a.y + sy, a.z + sz, travelled, 1, RIBBON, visible, f0, flowFlag);
            mesh.quad(i0, i1, i2, i3);

            travelled += length;
        }
    }
}
