/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * Flight that stays close to vanilla movement: speed is capped, speed changes are smoothed, the vertical movement
 * follows vanilla-like physics, and it turns itself off when the server keeps correcting your position.
 * <p>
 * There is no guarantee that any anticheat accepts it. What works depends on the server's version and configuration.
 */
public class VulcanFly extends Module {
    public enum Mode {
        /**
         * No physics at all: every tick you move by an exact step that is sent to the server as a normal position
         * packet. Speed, vertical speed and the hover are fully controlled by the settings.
         */
        Packet,
        /** Regular creative-style flying through the player abilities, like the Flight module's Abilities mode. */
        Vanilla,
        /** Hover with a slow, constant sink rate. */
        Glide,
        /** Hover by re-applying a vanilla jump impulse whenever the fall gets fast, like a bouncing hover. */
        Pulse
    }

    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;
    private static final double JUMP_VELOCITY = 0.42;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHorizontal = settings.createGroup("Horizontal");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // General

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("How you fly. Packet moves in exact steps, Vanilla uses the regular creative-style flying.")
        .defaultValue(Mode.Packet)
        .onChanged(m -> {
            if (isActive() && Utils.canUpdate()) applyMode();
        })
        .build()
    );

    private final Setting<Double> vanillaSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vanilla-speed")
        .description("The flying speed of the Vanilla mode. The creative default is 0.05, which is about 0.5 blocks per tick sideways. 0.1 doubled that to about 1 block per tick.")
        .defaultValue(0.05)
        .min(0.01)
        .sliderRange(0.02, 0.5)
        .visible(() -> mode.get() == Mode.Vanilla)
        .build()
    );

    private final Setting<Double> packetSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("packet-speed")
        .description("Blocks moved sideways per tick. Walking is about 0.22 and sprinting about 0.28.")
        .defaultValue(0.15)
        .min(0.01)
        .sliderRange(0.02, 0.6)
        .visible(() -> mode.get() == Mode.Packet)
        .build()
    );

    private final Setting<Double> packetVerticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("packet-vertical-speed")
        .description("Blocks moved up or down per tick while holding jump or sneak.")
        .defaultValue(0.06)
        .min(0.005)
        .sliderRange(0.01, 0.4)
        .visible(() -> mode.get() == Mode.Packet)
        .build()
    );

    private final Setting<Boolean> lockHorizontal = sgGeneral.add(new BoolSetting.Builder()
        .name("vertical-lock")
        .description("While you hold jump or sneak in the air, you cannot move in any other direction.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> glideSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("glide-speed")
        .description("How fast you sink while gliding, in blocks per tick. Vanilla falling is much faster than this.")
        .defaultValue(0.035)
        .min(0)
        .sliderRange(0, 0.2)
        .visible(() -> mode.get() == Mode.Glide)
        .build()
    );

    private final Setting<Double> pulseThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("pulse-threshold")
        .description("The downward speed at which the next jump impulse is applied. Lower values bob less.")
        .defaultValue(0.25)
        .min(0.05)
        .sliderRange(0.1, 0.6)
        .visible(() -> mode.get() == Mode.Pulse)
        .build()
    );

    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("How fast you move up or down while holding jump or sneak, in blocks per tick.")
        .defaultValue(0.12)
        .min(0)
        .sliderRange(0, 0.5)
        .build()
    );

    private final Setting<Double> verticalAcceleration = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-acceleration")
        .description("How quickly the vertical speed changes each tick. Lower is smoother.")
        .defaultValue(0.06)
        .min(0.005)
        .sliderRange(0.01, 0.5)
        .build()
    );

    // Horizontal

    private final Setting<Double> horizontalSpeed = sgHorizontal.add(new DoubleSetting.Builder()
        .name("horizontal-speed")
        .description("The top horizontal speed in blocks per tick. Vanilla sprinting is about 0.28.")
        .defaultValue(0.26)
        .min(0)
        .sliderRange(0.05, 1)
        .build()
    );

    private final Setting<Double> acceleration = sgHorizontal.add(new DoubleSetting.Builder()
        .name("acceleration")
        .description("How quickly you speed up each tick. Lower is smoother.")
        .defaultValue(0.06)
        .min(0.005)
        .sliderRange(0.01, 0.5)
        .build()
    );

    private final Setting<Double> deceleration = sgHorizontal.add(new DoubleSetting.Builder()
        .name("deceleration")
        .description("How much horizontal speed is kept each tick once you stop pressing a key.")
        .defaultValue(0.6)
        .range(0, 0.98)
        .sliderRange(0, 0.95)
        .build()
    );

    // Safety

    private final Setting<Boolean> antiKick = sgSafety.add(new BoolSetting.Builder()
        .name("anti-kick")
        .description("Sends a position slightly lower than yours now and then, and returns to the real position a moment later, so the server's \"floating too long\" check resets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> antiKickInterval = sgSafety.add(new IntSetting.Builder()
        .name("anti-kick-interval")
        .description("Ticks between the downward packets. The server kicks after 80 ticks of floating.")
        .defaultValue(30)
        .range(5, 70)
        .sliderRange(10, 70)
        .visible(antiKick::get)
        .build()
    );

    private final Setting<Double> antiKickDrop = sgSafety.add(new DoubleSetting.Builder()
        .name("anti-kick-drop")
        .description("How far down the packet moves you, in blocks. It has to be more than 0.03125 to count as falling.")
        .defaultValue(0.04)
        .min(0.032)
        .sliderRange(0.032, 0.2)
        .visible(antiKick::get)
        .build()
    );

    private final Setting<Integer> antiKickReturnDelay = sgSafety.add(new IntSetting.Builder()
        .name("anti-kick-return-delay")
        .description("Ticks to wait before sending the real position again.")
        .defaultValue(2)
        .range(1, 20)
        .sliderRange(1, 10)
        .visible(antiKick::get)
        .build()
    );

    private final Setting<Boolean> disableOnSetback = sgSafety.add(new BoolSetting.Builder()
        .name("disable-on-setback")
        .description("Turns the module off when the server keeps moving you back, instead of piling up flags.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxSetbacks = sgSafety.add(new IntSetting.Builder()
        .name("max-setbacks")
        .description("How many setbacks within the time window turn the module off.")
        .defaultValue(3)
        .range(1, 20)
        .sliderRange(1, 10)
        .visible(disableOnSetback::get)
        .build()
    );

    private final Setting<Integer> setbackWindow = sgSafety.add(new IntSetting.Builder()
        .name("setback-window")
        .description("The time window for counting setbacks, in ticks.")
        .defaultValue(100)
        .range(20, 600)
        .sliderRange(20, 300)
        .visible(disableOnSetback::get)
        .build()
    );

    private final Setting<Boolean> onlyInAir = sgSafety.add(new BoolSetting.Builder()
        .name("only-in-air")
        .description("Does nothing while you are standing on the ground, so taking off looks like a normal jump.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> diagnostics = sgSafety.add(new BoolSetting.Builder()
        .name("diagnostics")
        .description("Records your last movement and writes it to meteor-client/vulcan-fly-debug.log whenever the server sets you back or kicks you, together with the kick reason.")
        .defaultValue(true)
        .build()
    );

    private static final int HISTORY = 60;

    // Ring buffer of the last movement ticks, used by the diagnostics
    private final double[] histDy = new double[HISTORY];
    private final double[] histHorizontal = new double[HISTORY];
    private final double[] histY = new double[HISTORY];
    private final boolean[] histGround = new boolean[HISTORY];
    private final int[] histTick = new int[HISTORY];
    private int histIndex, histCount;
    private double lastX, lastY, lastZ;
    private boolean hasLast;

    private boolean abilitiesApplied;
    private int ticks;
    private int sinceDip;
    private int returnLeft;
    private final int[] setbackTicks = new int[20];
    private int setbackCount;

    public VulcanFly() {
        super(Categories.Movement, "vulcan-fly", "Flight that stays close to vanilla movement to be less noticeable to anticheats. Not guaranteed to work.");
    }

    @Override
    public void onActivate() {
        ticks = 0;
        sinceDip = 0;
        returnLeft = 0;
        setbackCount = 0;
        histIndex = 0;
        histCount = 0;
        hasLast = false;
        abilitiesApplied = false;

        if (Utils.canUpdate()) applyMode();
    }

    @Override
    public void onDeactivate() {
        // Do not leave a position hanging half way through an anti kick dip
        if (returnLeft > 0 && Utils.canUpdate()) sendPosition(mc.player.getY());
        returnLeft = 0;

        if (Utils.canUpdate()) abilitiesOff();
    }

    /** Vanilla mode flies through the player abilities, the other modes must not leave them on. */
    private void applyMode() {
        if (mode.get() == Mode.Vanilla) {
            if (mc.player.isSpectator()) return;

            mc.player.getAbilities().flying = true;
            if (!mc.player.getAbilities().instabuild) mc.player.getAbilities().mayfly = true;
            abilitiesApplied = true;
        } else {
            abilitiesOff();
        }
    }

    private void abilitiesOff() {
        if (!abilitiesApplied) return;
        abilitiesApplied = false;

        if (mc.player.isSpectator()) return;

        mc.player.getAbilities().flying = false;
        mc.player.getAbilities().setFlyingSpeed(0.05f);
        if (!mc.player.getAbilities().instabuild) mc.player.getAbilities().mayfly = false;
    }

    /** Whether horizontal movement is blocked right now because you are moving up or down. Used by the input mixin. */
    public boolean blockHorizontal() {
        if (!isActive() || !lockHorizontal.get() || !Utils.canUpdate()) return false;
        if (mc.player.onGround() || mc.player.isSpectator() || mc.player.isFallFlying()) return false;

        return mc.options.keyJump.isDown() || mc.options.keyShift.isDown();
    }

    /** Packet mode drives the movement itself, so the normal movement input is dropped. Used by the input mixin. */
    public boolean overridesInput() {
        return isActive() && mode.get() == Mode.Packet && Utils.canUpdate() && !mc.player.isSpectator() && !mc.player.isFallFlying();
    }

    private void sendPosition(double y) {
        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(mc.player.getX(), y, mc.player.getZ(), false, mc.player.horizontalCollision));
    }

    /** Sends a slightly lower position now and then, and the real one again a few ticks later. */
    private void antiKickTick() {
        if (!antiKick.get()) {
            sinceDip = 0;
            returnLeft = 0;
            return;
        }

        if (returnLeft > 0) {
            if (--returnLeft == 0) sendPosition(mc.player.getY());
            return;
        }

        if (mc.player.onGround()) {
            sinceDip = 0;
            return;
        }

        if (++sinceDip >= antiKickInterval.get()) {
            // Right above a block, the lowered position would be inside it and the server sets you back. The server
            // also does not count that as floating, so there is nothing to reset.
            double room = antiKickDrop.get() + 0.0625;
            if (!mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(0, -room, 0))) return;

            sinceDip = 0;
            sendPosition(mc.player.getY() - antiKickDrop.get());
            returnLeft = antiKickReturnDelay.get();
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (diagnostics.get() && ticks > 0) dumpHistory("LEFT THE GAME while active (tick %d). The kick reason, if there was one, is on the disconnect screen.".formatted(ticks));
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!diagnostics.get() || !(event.screen instanceof DisconnectedScreen screen)) return;

        String reason = "unknown";

        try {
            for (var field : DisconnectedScreen.class.getDeclaredFields()) {
                if (field.getType() != DisconnectionDetails.class) continue;

                field.setAccessible(true);
                reason = ((DisconnectionDetails) field.get(screen)).reason().getString();
                break;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            reason = "could not be read: " + e;
        }

        dumpHistory("DISCONNECT SCREEN: " + reason);
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (!Utils.canUpdate() || mc.player.isFallFlying() || mc.player.isSpectator()) return;
        if (mc.player.isInWater() || mc.player.isInLava() || mc.player.onClimbable()) return;

        ticks++;

        antiKickTick();

        boolean locked = blockHorizontal();

        if (mode.get() == Mode.Packet) {
            packetMove();
            return;
        }

        if (mode.get() == Mode.Vanilla) {
            // Keep the momentum from carrying you sideways while moving up or down
            if (locked) {
                Vec3 v = mc.player.getDeltaMovement();
                mc.player.setDeltaMovement(0, v.y, 0);
            }
            return;
        }

        if (onlyInAir.get() && mc.player.onGround() && !mc.options.keyJump.isDown()) return;

        Vec3 velocity = mc.player.getDeltaMovement();
        double vy = verticalMotion(velocity.y);
        double[] horizontal = locked ? new double[]{0, 0} : horizontalMotion(velocity);

        mc.player.setDeltaMovement(horizontal[0], vy, horizontal[1]);
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (!Utils.canUpdate()) return;

        if (mode.get() == Mode.Vanilla && !mc.player.isSpectator()) {
            mc.player.getAbilities().setFlyingSpeed(vanillaSpeed.get().floatValue());
            mc.player.getAbilities().flying = true;
            if (!mc.player.getAbilities().instabuild) mc.player.getAbilities().mayfly = true;
            abilitiesApplied = true;
        }

        if (!diagnostics.get()) return;

        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();

        if (hasLast) {
            histDy[histIndex] = y - lastY;
            histHorizontal[histIndex] = Math.sqrt((x - lastX) * (x - lastX) + (z - lastZ) * (z - lastZ));
            histY[histIndex] = y;
            histGround[histIndex] = mc.player.onGround();
            histTick[histIndex] = ticks;

            histIndex = (histIndex + 1) % HISTORY;
            if (histCount < HISTORY) histCount++;
        }

        lastX = x;
        lastY = y;
        lastZ = z;
        hasLast = true;
    }

    /** Writes the recent movement to the log file, with a header describing what happened. */
    private void dumpHistory(String header) {
        StringBuilder out = new StringBuilder();
        out.append("=== ").append(LocalDateTime.now()).append("  ").append(header).append('\n');
        out.append("mode=").append(mode.get())
            .append(" glide-speed=").append(glideSpeed.get())
            .append(" horizontal-speed=").append(horizontalSpeed.get())
            .append(" packet-speed=").append(packetSpeed.get())
            .append(" vanilla-speed=").append(vanillaSpeed.get())
            .append(" vertical-speed=").append(verticalSpeed.get())
            .append(" anti-kick=").append(antiKick.get()).append('\n');
        out.append("tick  dy        horizontal  y          onGround\n");

        for (int i = 0; i < histCount; i++) {
            int index = (histIndex - histCount + i + HISTORY * 2) % HISTORY;
            out.append(String.format("%-5d %-9.4f %-11.4f %-10.3f %s%n", histTick[index], histDy[index], histHorizontal[index], histY[index], histGround[index]));
        }

        out.append('\n');

        try {
            Path file = MeteorClient.FOLDER.toPath().resolve("vulcan-fly-debug.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, out.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            MeteorClient.LOG.warn("Could not write the Vulcan Fly diagnostics.", e);
        }

        MeteorClient.LOG.info("{}", header);
    }

    /** Moves by an exact step this tick. Collisions still apply, and vanilla sends the resulting position. */
    private void packetMove() {
        boolean up = mc.options.keyJump.isDown();
        boolean down = mc.options.keyShift.isDown();

        double vy = up == down ? 0 : (up ? packetVerticalSpeed.get() : -packetVerticalSpeed.get());

        double dx = 0, dz = 0;

        // Moving up or down blocks sideways movement when the vertical lock is on
        if (!(lockHorizontal.get() && up != down)) {
            double forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
            double strafe = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);

            if (forward != 0 || strafe != 0) {
                double yaw = Math.toRadians(mc.player.getYRot());
                double sin = Math.sin(yaw), cos = Math.cos(yaw);

                double x = -sin * forward + cos * strafe;
                double z = cos * forward + sin * strafe;
                double length = Math.sqrt(x * x + z * z);

                dx = x / length * packetSpeed.get();
                dz = z / length * packetSpeed.get();
            }
        }

        mc.player.setDeltaMovement(dx, vy, dz);
    }

    private double verticalMotion(double current) {
        boolean up = mc.options.keyJump.isDown();
        boolean down = mc.options.keyShift.isDown();

        if (up != down) {
            double target = up ? verticalSpeed.get() : -verticalSpeed.get();
            return approach(current, target, verticalAcceleration.get());
        }

        return switch (mode.get()) {
            case Glide, Vanilla, Packet -> approach(current, -glideSpeed.get(), verticalAcceleration.get());
            case Pulse -> {
                // Follow vanilla physics, and jump again whenever the fall gets too fast
                double next = (current - GRAVITY) * DRAG;
                yield next < -pulseThreshold.get() ? JUMP_VELOCITY : next;
            }
        };
    }

    private double[] horizontalMotion(Vec3 velocity) {
        double forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double strafe = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);

        if (forward == 0 && strafe == 0) {
            double keep = deceleration.get();
            return new double[]{velocity.x * keep, velocity.z * keep};
        }

        double yaw = Math.toRadians(mc.player.getYRot());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);

        double dx = -sin * forward + cos * strafe;
        double dz = cos * forward + sin * strafe;

        double length = Math.sqrt(dx * dx + dz * dz);
        dx /= length;
        dz /= length;

        double top = horizontalSpeed.get();
        double accel = acceleration.get();

        return new double[]{approach(velocity.x, dx * top, accel), approach(velocity.z, dz * top, accel)};
    }

    private static double approach(double current, double target, double step) {
        if (current < target) return Math.min(current + step, target);
        return Math.max(current - step, target);
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        // Keep the server from switching flying off again, and only take over the values we do not control
        if (mode.get() == Mode.Vanilla && event.packet instanceof ClientboundPlayerAbilitiesPacket packet && Utils.canUpdate()) {
            event.cancel();

            mc.player.getAbilities().invulnerable = packet.isInvulnerable();
            mc.player.getAbilities().instabuild = packet.canInstabuild();
            mc.player.getAbilities().setWalkingSpeed(packet.getWalkingSpeed());
            return;
        }

        if (diagnostics.get() && event.packet instanceof ClientboundDisconnectPacket disconnect) {
            dumpHistory("KICKED: " + disconnect.reason().getString());
        }

        if (diagnostics.get() && event.packet instanceof ClientboundPlayerPositionPacket && ticks >= 20 && Utils.canUpdate()) {
            dumpHistory("SETBACK at tick %d (client y=%.3f, speed %.3f/tick)".formatted(ticks, mc.player.getY(), mc.player.getDeltaMovement().horizontalDistance()));
            info("Setback at tick %d, details in vulcan-fly-debug.log.", ticks);
        }

        if (!disableOnSetback.get() || !(event.packet instanceof ClientboundPlayerPositionPacket)) return;

        // Ignore the teleports the server sends when joining or changing worlds
        if (ticks < 20) return;

        int limit = Math.min(maxSetbacks.get(), setbackTicks.length);

        // Keep only the setbacks that are still inside the time window
        int kept = 0;
        for (int i = 0; i < setbackCount; i++) {
            if (ticks - setbackTicks[i] <= setbackWindow.get()) setbackTicks[kept++] = setbackTicks[i];
        }
        setbackCount = kept;

        if (setbackCount < setbackTicks.length) setbackTicks[setbackCount++] = ticks;

        if (setbackCount >= limit) {
            warning("The server keeps correcting your position, turning off.");
            toggle();
        }
    }

    @Override
    public String getInfoString() {
        return mode.get().name();
    }
}
