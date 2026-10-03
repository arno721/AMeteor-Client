/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.i18n.LanguageManager;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * Flies to a set of coordinates with an elytra: takes off, climbs above the terrain on the route, cruises on a
 * straight line (steering around anything in the way), boosts with fireworks and glides down to land at the target.
 */
public class ElytraNavigator extends Module {
    private enum State {
        TakeOff,
        Climb,
        Cruise,
        Descend,
        Land
    }

    private static final double TICKS_PER_SECOND = 20;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAltitude = settings.createGroup("Altitude");
    private final SettingGroup sgNavigation = settings.createGroup("Navigation");
    private final SettingGroup sgFireworks = settings.createGroup("Fireworks");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgDisplay = settings.createGroup("Display");

    // General

    private final Setting<BlockPos> target = sgGeneral.add(new BlockPosSetting.Builder()
        .name("target")
        .description("The coordinates to fly to. Y is only used as the height to glide down to.")
        .defaultValue(new BlockPos(0, 64, 0))
        .build()
    );

    private final Setting<Double> arrivalRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("arrival-radius")
        .description("How close to the target (horizontally) counts as arrived.")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 50)
        .build()
    );

    private final Setting<Boolean> autoTakeOff = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-take-off")
        .description("Jumps and opens the elytra automatically when you are on the ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableOnArrival = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-arrival")
        .description("Turns the module off once you have landed at the target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowNether = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-nether")
        .description("Allows the module to run in the Nether. Climbing is limited by the roof there.")
        .defaultValue(false)
        .build()
    );

    // Altitude

    private final Setting<Boolean> autoAltitude = sgAltitude.add(new BoolSetting.Builder()
        .name("auto-altitude")
        .description("Picks a cruise altitude above the highest terrain found on the route.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> cruiseAltitude = sgAltitude.add(new IntSetting.Builder()
        .name("cruise-altitude")
        .description("The fixed altitude to cruise at.")
        .defaultValue(200)
        .range(-64, 1000)
        .sliderRange(0, 400)
        .visible(() -> !autoAltitude.get())
        .build()
    );

    private final Setting<Integer> minAltitude = sgAltitude.add(new IntSetting.Builder()
        .name("min-altitude")
        .description("The lowest altitude to cruise at in auto mode.")
        .defaultValue(100)
        .range(-64, 1000)
        .sliderRange(0, 400)
        .visible(autoAltitude::get)
        .build()
    );

    private final Setting<Integer> clearance = sgAltitude.add(new IntSetting.Builder()
        .name("clearance")
        .description("How many blocks to stay above the highest terrain on the route.")
        .defaultValue(24)
        .range(4, 128)
        .sliderRange(8, 64)
        .visible(autoAltitude::get)
        .build()
    );

    private final Setting<Integer> maxAltitude = sgAltitude.add(new IntSetting.Builder()
        .name("max-altitude")
        .description("The highest altitude the module is allowed to fly at.")
        .defaultValue(316)
        .range(0, 1000)
        .sliderRange(100, 400)
        .build()
    );

    private final Setting<Integer> climbPitch = sgAltitude.add(new IntSetting.Builder()
        .name("climb-pitch")
        .description("How steeply to look up while climbing, in degrees.")
        .defaultValue(55)
        .range(20, 80)
        .sliderRange(20, 80)
        .build()
    );

    // Navigation

    private final Setting<Integer> scanWidth = sgNavigation.add(new IntSetting.Builder()
        .name("scan-width")
        .description("How many blocks to each side of the route are checked for terrain.")
        .defaultValue(6)
        .range(0, 32)
        .sliderRange(0, 16)
        .build()
    );

    private final Setting<Integer> scanInterval = sgNavigation.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("Ticks between route scans. New chunks can raise the cruise altitude.")
        .defaultValue(20)
        .range(5, 100)
        .sliderRange(5, 60)
        .build()
    );

    private final Setting<Boolean> avoidance = sgNavigation.add(new BoolSetting.Builder()
        .name("obstacle-avoidance")
        .description("Looks ahead for blocks in the way and steers up or around them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> lookAheadTicks = sgNavigation.add(new IntSetting.Builder()
        .name("look-ahead")
        .description("How many ticks of travel to check for obstacles.")
        .defaultValue(25)
        .range(5, 80)
        .sliderRange(10, 50)
        .visible(avoidance::get)
        .build()
    );

    private final Setting<Integer> maxDivePitch = sgNavigation.add(new IntSetting.Builder()
        .name("max-dive-pitch")
        .description("The steepest downward angle used while cruising and gliding down, in degrees.")
        .defaultValue(35)
        .range(5, 60)
        .sliderRange(10, 50)
        .build()
    );

    private final Setting<Integer> maxTurnRate = sgNavigation.add(new IntSetting.Builder()
        .name("max-turn-rate")
        .description("The most the view can turn sideways per tick, in degrees. Lower looks smoother and avoids sudden speed loss.")
        .defaultValue(25)
        .range(2, 180)
        .sliderRange(5, 90)
        .build()
    );

    private final Setting<Integer> maxPitchRate = sgNavigation.add(new IntSetting.Builder()
        .name("max-pitch-rate")
        .description("The most the view can tilt up or down per tick, in degrees.")
        .defaultValue(10)
        .range(1, 90)
        .sliderRange(2, 45)
        .build()
    );

    private final Setting<Boolean> efficientCruise = sgNavigation.add(new BoolSetting.Builder()
        .name("efficient-cruise")
        .description("Cruises in a dive and climb pattern that trades height for speed, instead of holding one height and relying on fireworks. Fireworks are only used when the speed runs out at the bottom.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> altitudeBand = sgNavigation.add(new IntSetting.Builder()
        .name("altitude-band")
        .description("How far above the cruise altitude the climb part may reach, in blocks. A taller band saves more fireworks.")
        .defaultValue(16)
        .range(4, 80)
        .sliderRange(6, 48)
        .visible(efficientCruise::get)
        .build()
    );

    private final Setting<Integer> glideDivePitch = sgNavigation.add(new IntSetting.Builder()
        .name("glide-dive-pitch")
        .description("Downward angle of the dive part, in degrees.")
        .defaultValue(30)
        .range(10, 60)
        .sliderRange(15, 50)
        .visible(efficientCruise::get)
        .build()
    );

    private final Setting<Integer> glideClimbPitch = sgNavigation.add(new IntSetting.Builder()
        .name("glide-climb-pitch")
        .description("Upward angle of the climb part, in degrees.")
        .defaultValue(30)
        .range(10, 60)
        .sliderRange(15, 50)
        .visible(efficientCruise::get)
        .build()
    );

    private final Setting<Double> minGlideSpeed = sgNavigation.add(new DoubleSetting.Builder()
        .name("min-glide-speed")
        .description("The climb ends and a dive starts when the speed drops below this, in blocks per second.")
        .defaultValue(22)
        .min(8)
        .sliderRange(10, 40)
        .visible(efficientCruise::get)
        .build()
    );

    private final Setting<Double> maxGlideSpeed = sgNavigation.add(new DoubleSetting.Builder()
        .name("max-glide-speed")
        .description("The dive ends and a climb starts when the speed reaches this, in blocks per second.")
        .defaultValue(38)
        .min(12)
        .sliderRange(20, 70)
        .visible(efficientCruise::get)
        .build()
    );

    private final Setting<Double> glideRatio = sgNavigation.add(new DoubleSetting.Builder()
        .name("glide-ratio")
        .description("Blocks travelled horizontally per block of height lost. Gliding down starts at this ratio.")
        .defaultValue(10)
        .min(2)
        .sliderRange(4, 20)
        .build()
    );

    private final Setting<Integer> flareHeight = sgNavigation.add(new IntSetting.Builder()
        .name("flare-height")
        .description("Height above the ground to start slowing down for the landing.")
        .defaultValue(14)
        .range(4, 60)
        .sliderRange(6, 30)
        .build()
    );

    // Fireworks

    private final Setting<Boolean> useFireworks = sgFireworks.add(new BoolSetting.Builder()
        .name("use-fireworks")
        .description("Uses fireworks from your hotbar or offhand to keep your speed up.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> boostBelowSpeed = sgFireworks.add(new DoubleSetting.Builder()
        .name("boost-below-speed")
        .description("Uses a firework when your speed drops below this, in blocks per second.")
        .defaultValue(28)
        .min(1)
        .sliderRange(10, 60)
        .visible(useFireworks::get)
        .build()
    );

    private final Setting<Integer> fireworkCooldown = sgFireworks.add(new IntSetting.Builder()
        .name("firework-cooldown")
        .description("Minimum ticks between two fireworks. It is never shorter than a rocket burns (25, 35 or 45 ticks for duration 1, 2 or 3), so rockets do not overlap and get wasted.")
        .defaultValue(25)
        .range(1, 200)
        .sliderRange(5, 100)
        .visible(useFireworks::get)
        .build()
    );

    private final Setting<Boolean> boostOnDescent = sgFireworks.add(new BoolSetting.Builder()
        .name("boost-on-descent")
        .description("Keeps using fireworks while gliding down to the target. Normally gravity is enough.")
        .defaultValue(false)
        .visible(useFireworks::get)
        .build()
    );

    private final Setting<Integer> fireworkReserve = sgFireworks.add(new IntSetting.Builder()
        .name("reserve")
        .description("Fireworks to keep unused until the module is within the final approach.")
        .defaultValue(0)
        .range(0, 64)
        .sliderRange(0, 16)
        .visible(useFireworks::get)
        .build()
    );

    // Safety

    private final Setting<Integer> minDurability = sgSafety.add(new IntSetting.Builder()
        .name("min-durability")
        .description("Lands immediately when the elytra has this much durability or less left.")
        .defaultValue(10)
        .range(1, 200)
        .sliderRange(1, 60)
        .build()
    );

    // Display

    private final Setting<Boolean> progressBar = sgDisplay.add(new BoolSetting.Builder()
        .name("progress-bar")
        .description("Shows a progress bar with the remaining distance and estimated time on screen.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> barWidth = sgDisplay.add(new IntSetting.Builder()
        .name("bar-width")
        .description("Width of the progress bar in pixels.")
        .defaultValue(200)
        .range(60, 600)
        .sliderRange(100, 400)
        .visible(progressBar::get)
        .build()
    );

    private final Setting<Integer> barY = sgDisplay.add(new IntSetting.Builder()
        .name("bar-y")
        .description("Distance of the progress bar from the top of the screen.")
        .defaultValue(36)
        .range(0, 2000)
        .sliderRange(0, 200)
        .visible(progressBar::get)
        .build()
    );

    private final Setting<SettingColor> barColor = sgDisplay.add(new ColorSetting.Builder()
        .name("bar-color")
        .description("Color of the filled part of the progress bar.")
        .defaultValue(new SettingColor(90, 200, 255, 255))
        .visible(progressBar::get)
        .build()
    );

    private final Setting<Boolean> barText = sgDisplay.add(new BoolSetting.Builder()
        .name("bar-text")
        .description("Shows the percentage, remaining distance and ETA above the bar.")
        .defaultValue(true)
        .visible(progressBar::get)
        .build()
    );

    private final Setting<Boolean> showEstimate = sgDisplay.add(new BoolSetting.Builder()
        .name("show-estimate")
        .description("Shows how many fireworks the rest of the flight is expected to need, and warns when the elytra will not last.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> estimateMargin = sgDisplay.add(new IntSetting.Builder()
        .name("estimate-margin")
        .description("Extra percent added to the expected firework use, because wind-free simulation cannot know about detours.")
        .defaultValue(15)
        .range(0, 100)
        .sliderRange(0, 50)
        .visible(showEstimate::get)
        .build()
    );

    private final Setting<Boolean> notifyOnArrival = sgDisplay.add(new BoolSetting.Builder()
        .name("windows-notification")
        .description("Shows a Windows notification when you have landed at the target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyOnlyUnfocused = sgDisplay.add(new BoolSetting.Builder()
        .name("notify-only-unfocused")
        .description("Only sends the notification when the Minecraft window is not focused.")
        .defaultValue(false)
        .visible(notifyOnArrival::get)
        .build()
    );

    private State state = State.TakeOff;
    private double cruiseY;
    private int scanTimer, fireworkTimer, takeOffTimer;
    private int avoidSide = 1;
    private boolean glideClimbing, needBoost, launchAiming;
    private int rocketsUsed;
    private double initialEstimate = -1, startX, startY, startZ, peakCruiseY;
    private int climbsAfterCruise;
    private boolean sawCruise;
    private boolean emergency, warnedNoFireworks, wasFlying, notified;
    private double startDistance = -1, remainingDistance;

    // ETA: distance to the target over the last 10 seconds of flight gives the real closing speed.
    private static final int ETA_WINDOW_TICKS = 10 * 20;
    private final double[] distanceSamples = new double[ETA_WINDOW_TICKS + 1];
    private int sampleIndex, sampleCount;
    private double etaSeconds = -1;

    /** The result of simulating the rest of the flight. */
    private record Estimate(double rockets, double ticks, boolean reached) {
    }

    private Estimate estimate;
    private int estimateTimer, estimateSignature, flownTicks;

    public ElytraNavigator() {
        super(Categories.Movement, "elytra-navigator", "Flies to coordinates with an elytra: climbs, routes around obstacles and boosts with fireworks.");
    }

    @Override
    public void onActivate() {
        state = State.TakeOff;
        cruiseY = Double.NEGATIVE_INFINITY;
        scanTimer = 0;
        fireworkTimer = 0;
        takeOffTimer = 0;
        avoidSide = 1;
        glideClimbing = true;
        needBoost = false;
        launchAiming = false;
        rocketsUsed = 0;
        initialEstimate = -1;
        climbsAfterCruise = 0;
        sawCruise = false;
        peakCruiseY = Double.NEGATIVE_INFINITY;
        if (Utils.canUpdate()) {
            startX = mc.player.getX();
            startY = mc.player.getY();
            startZ = mc.player.getZ();
        }
        emergency = false;
        warnedNoFireworks = false;
        wasFlying = false;
        notified = false;
        startDistance = -1;
        remainingDistance = 0;
        sampleIndex = 0;
        sampleCount = 0;
        etaSeconds = -1;
        estimate = null;
        estimateTimer = 0;
        estimateSignature = 0;
        flownTicks = 0;

        if (!Utils.canUpdate()) return;

        remainingDistance = startDistance = horizontalDistanceToTarget();
        updateEta(remainingDistance, false);

        if (!isDimensionAllowed()) {
            error("This dimension is not supported. Use the Overworld or the End (or enable allow-nether).");
            toggle();
            return;
        }

        if (!hasUsableElytra()) {
            error("You need to wear an elytra.");
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        if (flownTicks > 20 && Utils.canUpdate()) writeFlightLog();
    }

    /** Appends one block per flight to meteor-client/elytra-navigator-log.txt: what was predicted and what happened. */
    private void writeFlightLog() {
        double flown = Math.sqrt((mc.player.getX() - startX) * (mc.player.getX() - startX) + (mc.player.getZ() - startZ) * (mc.player.getZ() - startZ));
        double raw = initialEstimate;

        String text = ("=== %s%n"
            + "target %d %d %d, start %.0f %.0f %.0f, end %.0f %.0f %.0f%n"
            + "straight distance flown: %.0f blocks, flight time: %.1f s%n"
            + "fireworks: predicted %s (%s with margin), actually used %d, rocket duration %d%n"
            + "highest cruise altitude: %.0f, climbs after first reaching cruise altitude: %d%n"
            + "efficient-cruise=%s band=%d dive=%d climb=%d speeds=%.0f..%.0f boost-below=%.0f cooldown=%d climb-pitch=%d%n%n").formatted(
            LocalDateTime.now(),
            target.get().getX(), target.get().getY(), target.get().getZ(), startX, startY, startZ, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
            flown, flownTicks / TICKS_PER_SECOND,
            raw < 0 ? "n/a" : "%.1f".formatted(raw),
            raw < 0 ? "n/a" : String.valueOf((int) Math.ceil(raw * (1 + estimateMargin.get() / 100.0))),
            rocketsUsed, rocketFlightDuration(),
            peakCruiseY, climbsAfterCruise,
            efficientCruise.get(), altitudeBand.get(), glideDivePitch.get(), glideClimbPitch.get(), minGlideSpeed.get(), maxGlideSpeed.get(),
            boostBelowSpeed.get(), fireworkCooldown.get(), climbPitch.get());

        try {
            Path file = MeteorClient.FOLDER.toPath().resolve("elytra-navigator-log.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            MeteorClient.LOG.warn("Could not write the Elytra Navigator log.", e);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate()) return;

        if (!isDimensionAllowed()) {
            error("Changed to an unsupported dimension, disabling.");
            toggle();
            return;
        }

        boolean flying = mc.player.isFallFlying();

        remainingDistance = horizontalDistanceToTarget();
        updateEta(remainingDistance, flying);

        if (estimateTimer-- <= 0) {
            estimateTimer = 20;
            refreshEstimate(flying);
        }

        if (!flying) {
            if (wasFlying && state == State.Land) {
                if (mc.player.onGround()) onLanded();
                wasFlying = false;
                return;
            }

            wasFlying = false;
            takeOff();
            return;
        }

        wasFlying = true;
        fly();
    }

    private void onLanded() {
        if (!notified) {
            notified = true;

            String text = emergency ? "Landed early, the elytra is almost broken." : "Arrived at the target.";
            info(text);

            meteordevelopment.meteorclient.systems.modules.Modules.get().get(meteordevelopment.meteorclient.systems.modules.render.DynamicIsland.class)
                .notify(LanguageManager.translate("module.elytra-navigator.name", "Elytra Navigator"), emergency
                    ? LanguageManager.translate("module.elytra-navigator.notify.emergency", text)
                    : LanguageManager.translate("module.elytra-navigator.notify.arrived", text), emergency ? 0xFFFBBF24 : 0xFF4ADE80, 4,
                    emergency ? meteordevelopment.meteorclient.systems.modules.render.DynamicIsland.Icon.WARNING : meteordevelopment.meteorclient.systems.modules.render.DynamicIsland.Icon.FLAG);

            if (notifyOnArrival.get()) {
                String message = emergency
                    ? LanguageManager.translate("module.elytra-navigator.notify.emergency", text)
                    : LanguageManager.translate("module.elytra-navigator.notify.arrived", text);
                notifyWindows(LanguageManager.translate("module.elytra-navigator.notify.title", "Elytra Navigator"), message);
            }
        }

        if (disableOnArrival.get()) toggle();
    }

    // Take off

    private void takeOff() {
        if (!autoTakeOff.get()) return;

        if (!hasUsableElytra()) {
            error("Your elytra is gone or broken, disabling.");
            toggle();
            return;
        }

        state = State.TakeOff;

        // Face the target and look up before the elytra opens. Turning only after takeoff would aim the first
        // fireworks sideways or into the ground, and those are the ones that get wasted.
        double aimX = target.get().getX() + 0.5 - mc.player.getX();
        double aimZ = target.get().getZ() + 0.5 - mc.player.getZ();
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-aimX, aimZ)));
        mc.player.setXRot((float) -climbPitch.get());

        if (mc.player.onGround()) {
            takeOffTimer = 0;
            mc.player.jumpFromGround();
            return;
        }

        takeOffTimer++;

        // Open the elytra while falling, retrying every few ticks until the server accepts it.
        if (mc.player.getDeltaMovement().y < 0 && takeOffTimer % 4 == 0) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
    }

    // Flying

    private void fly() {
        Vec3 pos = mc.player.position();
        double tx = target.get().getX() + 0.5;
        double tz = target.get().getZ() + 0.5;
        double dx = tx - pos.x;
        double dz = tz - pos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        Vec3 velocity = mc.player.getDeltaMovement();
        double speedPerTick = velocity.length();
        double speed = speedPerTick * TICKS_PER_SECOND;

        // Safety: land if the elytra is about to break
        if (!emergency && durabilityLeft() <= minDurability.get()) {
            emergency = true;
            warning("Elytra durability is low, landing.");
        }

        if (scanTimer-- <= 0) {
            scanTimer = scanInterval.get();
            updateCruiseAltitude(pos, dist);
        }

        State before = state;
        updateState(pos, dist);
        if (state == State.Cruise) sawCruise = true;
        if (state == State.Climb && before != State.Climb && sawCruise) climbsAfterCruise++;
        peakCruiseY = Math.max(peakCruiseY, cruiseY);

        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = switch (state) {
            case Climb -> climbPitch(speed, aboveGround(pos));
            case Cruise -> cruisePitch(pos, speed);
            case Descend -> descendPitch(pos, dist);
            case Land, TakeOff -> landPitch(pos, dist, speedPerTick);
        };

        // Keep heading with the current direction while landing in an emergency, there is no point in turning.
        if (emergency) yaw = mc.player.getYRot();

        if (avoidance.get() && state != State.Land) {
            double[] steered = avoid(pos, yaw, pitch, speedPerTick, dist);
            yaw = steered[0];
            pitch = steered[1];
        }

        // Turn towards the wanted direction at a limited rate instead of snapping, this avoids chattering between
        // climbing and diving (which bleeds speed) and looks a lot more natural
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();
        float turn = maxTurnRate.get();
        float tilt = maxPitchRate.get();

        float newYaw = currentYaw + Mth.clamp(Mth.wrapDegrees((float) yaw - currentYaw), -turn, turn);
        float newPitch = currentPitch + Mth.clamp((float) Mth.clamp(pitch, -89, 89) - currentPitch, -tilt, tilt);

        mc.player.setYRot(newYaw);
        mc.player.setXRot(newPitch);

        flownTicks++;
        launchAiming = state == State.Climb && aboveGround(pos) < 10 && mc.player.getXRot() > -climbPitch.get() + 12;

        if (useFireworks.get()) boost(speed, dist);
    }

    private void updateState(Vec3 pos, double dist) {
        if (emergency || dist <= arrivalRadius.get()) {
            state = State.Land;
            return;
        }

        if (state == State.Land) return;

        double heightAbove = pos.y - target.get().getY();

        // Once gliding down, the cone is a bit wider so small deviations do not flip back to climbing
        double cone = state == State.Descend ? glideRatio.get() * 1.3 : glideRatio.get();
        boolean inGlideCone = heightAbove > 0 && dist <= heightAbove * cone + arrivalRadius.get();

        if (inGlideCone) {
            state = State.Descend;
        } else if (state == State.Climb ? pos.y < cruiseY - 1 : pos.y < cruiseY - 6) {
            // Different thresholds for starting and ending a climb keep the state from flickering at the cruise altitude
            state = State.Climb;
        } else {
            state = State.Cruise;
        }
    }

    // Altitude

    private void updateCruiseAltitude(Vec3 pos, double dist) {
        double max = Math.min(maxAltitude.get(), mc.level.getMaxY() - 2);
        double wanted;

        if (autoAltitude.get()) {
            double highest = highestTerrainOnRoute(pos, dist);
            wanted = Math.max(minAltitude.get(), highest + clearance.get());
        } else {
            wanted = cruiseAltitude.get();
        }

        wanted = Math.min(wanted, max);

        // Only ever raise the cruise altitude, so we do not drop into terrain that was just scanned past.
        if (state == State.TakeOff || wanted > cruiseY) cruiseY = wanted;
        cruiseY = Math.min(cruiseY, max);
    }

    /** Highest heightmap value in the loaded part of the corridor between the player and the target. */
    private double highestTerrainOnRoute(Vec3 pos, double dist) {
        double tx = target.get().getX() + 0.5;
        double tz = target.get().getZ() + 0.5;

        double highest = mc.level.getMinY();
        int width = scanWidth.get();

        double total = Math.max(dist, 1);
        double dirX = (tx - pos.x) / total;
        double dirZ = (tz - pos.z) / total;

        // Chunks further away than the render distance are not loaded, so there is nothing to see there
        double length = Math.min(total, (mc.options.getEffectiveRenderDistance() + 2) * 16.0);

        // Perpendicular direction used to widen the corridor
        double perpX = -dirZ;
        double perpZ = dirX;

        int laneStep = Math.max(1, width / 2);

        for (double d = 0; d <= length; d += 2) {
            for (int w = -width; w <= width; w += laneStep) {
                int x = Mth.floor(pos.x + dirX * d + perpX * w);
                int z = Mth.floor(pos.z + dirZ * d + perpZ * w);

                if (mc.level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;

                highest = Math.max(highest, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
            }
        }

        return highest;
    }

    // Pitch controllers (positive pitch looks down)

    private double aboveGround(Vec3 pos) {
        return pos.y - mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(pos.x), Mth.floor(pos.z));
    }

    private double climbPitch(double speed, double aboveGround) {
        // Right after takeoff there is no room to dive, launch upwards
        if (aboveGround < 10) return -climbPitch.get();

        // Do not stall while climbing: dive a little to build speed back up when we are too slow
        if (speed < 10) return 12;
        return -climbPitch.get();
    }

    private double cruisePitch(Vec3 pos, double speed) {
        if (!efficientCruise.get()) {
            needBoost = true;

            double error = cruiseY - pos.y;
            return Mth.clamp(-error * 0.5, -25, maxDivePitch.get());
        }

        // Fly in a dive and climb pattern between the cruise altitude (the floor) and the top of the band.
        // The dive builds up speed from height, the climb turns that speed back into height.
        double low = cruiseY;
        double high = Math.min(cruiseY + altitudeBand.get(), Math.min(maxAltitude.get(), mc.level.getMaxY() - 2));
        double y = pos.y;

        if (y >= high) glideClimbing = false;
        else if (y <= low) glideClimbing = true;
        else if (glideClimbing && speed < minGlideSpeed.get()) glideClimbing = false;
        else if (!glideClimbing && speed >= maxGlideSpeed.get()) glideClimbing = true;

        // Fireworks are only worth it at the bottom when there is no height left to dive from
        boolean atFloor = y <= low + 3;
        needBoost = atFloor && speed < boostBelowSpeed.get();

        if (glideClimbing) {
            // No speed and no room to dive: stay level until a firework gives us speed again
            if (speed < minGlideSpeed.get() && atFloor) return 0;
            return -glideClimbPitch.get();
        }

        return Math.min(glideDivePitch.get(), maxDivePitch.get());
    }

    private double descendPitch(Vec3 pos, double dist) {
        double drop = pos.y - target.get().getY();
        double angle = Math.toDegrees(Math.atan2(drop, Math.max(dist, 1)));
        return Mth.clamp(angle, 5, maxDivePitch.get());
    }

    private double landPitch(Vec3 pos, double dist, double speedPerTick) {
        double groundY = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(pos.x), Mth.floor(pos.z));
        double aboveGround = pos.y - groundY;

        // Still high up: keep gliding down towards the target
        if (aboveGround > flareHeight.get() * 2) {
            return emergency ? 25 : descendPitch(pos, dist);
        }

        // Flare: pitch up to bleed off speed right before touching down
        if (aboveGround <= flareHeight.get()) {
            if (speedPerTick > 0.8) return -40;
            if (speedPerTick > 0.5) return -10;
            return 8;
        }

        return 15;
    }

    // Obstacle avoidance

    private static final double[][] CANDIDATES = {
        {0, 0}, {0, -15}, {20, 0}, {-20, 0}, {0, -30}, {40, 0}, {-40, 0}, {40, -15}, {-40, -15}, {70, 0}, {-70, 0}, {0, -50}
    };

    private double[] avoid(Vec3 pos, double yaw, double pitch, double speedPerTick, double dist) {
        // Stop looking before the target area, otherwise the ground we are about to land on counts as an obstacle
        double usable = dist - arrivalRadius.get();
        if (usable < 8) return new double[]{yaw, pitch};

        double lookAhead = Math.min(Math.max(24, speedPerTick * lookAheadTicks.get()), usable);

        if (isClear(pos, yaw, pitch, lookAhead)) return new double[]{yaw, pitch};

        // Prefer the side we steered to last time so we do not oscillate in front of a wall
        for (double[] c : CANDIDATES) {
            double yawOffset = c[0] * avoidSide;
            if (isClear(pos, yaw + yawOffset, pitch + c[1], lookAhead)) {
                if (yawOffset != 0) avoidSide = yawOffset > 0 ? 1 : -1;
                if (c[1] < 0 && c[0] == 0) cruiseY = Math.max(cruiseY, pos.y + 12);
                return new double[]{yaw + yawOffset, pitch + c[1]};
            }
        }

        // Boxed in: go straight up and raise the cruise altitude
        cruiseY = Math.max(cruiseY, Math.min(pos.y + 24, mc.level.getMaxY() - 2));
        return new double[]{yaw, -60};
    }

    /** Checks the centre line and both wings for blocks along the given direction. */
    private boolean isClear(Vec3 pos, double yaw, double pitch, double length) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(Mth.clamp(pitch, -89, 89));

        double cosPitch = Math.cos(pitchRad);
        Vec3 dir = new Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
        Vec3 wing = new Vec3(Math.cos(yawRad), 0, Math.sin(yawRad)).scale(0.8);

        Vec3 start = mc.player.getEyePosition();

        for (int i = -1; i <= 1; i++) {
            Vec3 from = start.add(wing.scale(i));
            Vec3 to = from.add(dir.scale(length));

            ClipContext context = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
            if (mc.level.clip(context).getType() != HitResult.Type.MISS) return false;
        }

        return true;
    }

    // Fireworks

    private void boost(double speed, double dist) {
        if (fireworkTimer > 0) {
            fireworkTimer--;
            return;
        }

        if (speed >= boostBelowSpeed.get()) return;
        if (launchAiming) return;
        if (state == State.Land) return;
        if (state == State.Descend && !boostOnDescent.get()) return;

        // While cruising in the dive and climb pattern, diving is the free way to get speed back
        if (state == State.Cruise && efficientCruise.get() && !needBoost) return;

        FindItemResult rocket = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);

        if (!rocket.found()) {
            if (!warnedNoFireworks) {
                warning("No fireworks in your hotbar, gliding only.");
                warnedNoFireworks = true;
            }
            return;
        }

        // Hold back the reserve until we are close to the target
        boolean finalApproach = dist < arrivalRadius.get() + 64;
        if (!finalApproach && countFireworks() <= fireworkReserve.get()) return;

        warnedNoFireworks = false;
        fireworkTimer = rocketCooldown();
        rocketsUsed++;

        if (rocket.isOffhand()) {
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            mc.player.swing(InteractionHand.OFF_HAND);
        } else {
            InvUtils.swap(rocket.slot(), true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            InvUtils.swapBack();
        }
    }

    private int countFireworks() {
        int total = 0;

        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.FIREWORK_ROCKET)) total += stack.getCount();
        }

        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.is(Items.FIREWORK_ROCKET)) total += offhand.getCount();

        return total;
    }

    // Helpers

    private boolean isDimensionAllowed() {
        var dimension = mc.level.dimension();
        return dimension == Level.OVERWORLD || dimension == Level.END || (dimension == Level.NETHER && allowNether.get());
    }

    private boolean hasUsableElytra() {
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        return chest.is(Items.ELYTRA) && chest.getMaxDamage() - chest.getDamageValue() > 1;
    }

    private int durabilityLeft() {
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA)) return Integer.MAX_VALUE;
        return chest.getMaxDamage() - chest.getDamageValue();
    }

    /**
     * Keeps a stable estimate of the remaining flight time. The speed used is how fast the distance to the target
     * actually shrank over the last 10 seconds, which also accounts for climbing and detours around obstacles. Until
     * there is enough data it is blended with the speed the module normally boosts to. The displayed time counts down
     * every tick and is only gently pulled towards the new estimate, so it does not jump around.
     */
    private void updateEta(double dist, boolean flying) {
        if (flying) {
            distanceSamples[sampleIndex] = dist;
            sampleIndex = (sampleIndex + 1) % distanceSamples.length;
            if (sampleCount < distanceSamples.length) sampleCount++;
        }

        double estimate = dist / estimateSpeed();

        if (!flying || etaSeconds < 0) {
            etaSeconds = estimate;
        } else {
            etaSeconds -= 1 / TICKS_PER_SECOND;
            etaSeconds += (estimate - etaSeconds) * 0.03;
        }

        if (dist <= arrivalRadius.get()) etaSeconds = 0;
        etaSeconds = Math.max(etaSeconds, 0);
    }

    private double estimateSpeed() {
        double prior = Math.max(useFireworks.get() ? boostBelowSpeed.get() : 28, 1);
        if (sampleCount < 2) return prior;

        int length = distanceSamples.length;
        double newest = distanceSamples[(sampleIndex - 1 + length) % length];
        double oldest = distanceSamples[(sampleIndex - sampleCount + length) % length];

        double elapsed = (sampleCount - 1) / TICKS_PER_SECOND;
        double closing = Math.max((oldest - newest) / elapsed, 0);

        // Trust the measurement more the longer we have been flying
        double weight = Mth.clamp(elapsed / 10, 0, 1);
        return Math.max(weight * closing + (1 - weight) * prior, 3);
    }

    /** The cruise altitude the module would pick right now. */
    private double wantedCruise(Vec3 pos, double dist) {
        double max = Math.min(maxAltitude.get(), mc.level.getMaxY() - 2);
        double wanted = autoAltitude.get() ? Math.max(minAltitude.get(), highestTerrainOnRoute(pos, dist) + clearance.get()) : cruiseAltitude.get();
        return Math.min(wanted, max);
    }

    /** Ticks to wait between rockets: the setting, but never less than a rocket burns, so they do not overlap. */
    private int rocketCooldown() {
        // 26.2: a rocket pushes for lifetime + 1 ticks, lifetime = 10 * (1 + duration) + nextInt(6) + nextInt(7)
        return Math.max(fireworkCooldown.get(), 10 * (1 + rocketFlightDuration()) + 7);
    }

    /** Duration of the firework rockets we would use, 1 to 3. */
    private int rocketFlightDuration() {
        ItemStack stack = mc.player.getOffhandItem();
        if (!stack.is(Items.FIREWORK_ROCKET)) {
            stack = ItemStack.EMPTY;

            for (int i = 0; i < 9; i++) {
                ItemStack candidate = mc.player.getInventory().getItem(i);

                if (candidate.is(Items.FIREWORK_ROCKET)) {
                    stack = candidate;
                    break;
                }
            }
        }

        Fireworks fireworks = stack.isEmpty() ? null : stack.get(DataComponents.FIREWORKS);
        return fireworks != null ? Math.max(fireworks.flightDuration(), 1) : 1;
    }

    /**
     * Simulates the rest of the flight tick by tick with the real elytra and firework physics and the same decisions
     * the module makes (climb, dive and climb cruise, glide down, fireworks), and counts the fireworks it used.
     * It flies straight to the target at a fixed cruise altitude, so detours and terrain are not part of it.
     */
    private Estimate simulate(boolean flying) {
        double dist = horizontalDistanceToTarget();
        double radius = arrivalRadius.get();
        if (dist <= radius) return new Estimate(0, 0, true);

        Vec3 pos = mc.player.position();
        double ux = (target.get().getX() + 0.5 - pos.x) / dist;
        double uz = (target.get().getZ() + 0.5 - pos.z) / dist;

        Vec3 velocity = flying ? mc.player.getDeltaMovement() : Vec3.ZERO;

        double y = flying ? pos.y : pos.y + 1.25;
        double vh = velocity.x * ux + velocity.z * uz; // speed towards the target, blocks per tick
        double vy = velocity.y;
        double pitch = flying ? mc.player.getXRot() : -climbPitch.get();
        double remaining = dist;

        double cruise = cruiseY > -1e8 ? cruiseY : wantedCruise(pos, dist);
        double high = Math.min(cruise + altitudeBand.get(), Math.min(maxAltitude.get(), mc.level.getMaxY() - 2));
        double targetY = target.get().getY();
        double groundY = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(pos.x), Mth.floor(pos.z));
        int burn = rocketCooldown();

        boolean efficient = efficientCruise.get();
        boolean climbingGlide = !flying || glideClimbing;
        State st = flying ? state : State.Climb;
        if (st == State.TakeOff || st == State.Land) st = State.Climb;

        // Average lifetime of a rocket in ticks: 10 * (1 + duration) plus two small random parts
        int lifetime = (int) Math.round(10 * (1 + rocketFlightDuration()) + 6.5); // average ticks of thrust
        int[] timers = new int[24];
        int active = 0;
        int cooldown = flying ? fireworkTimer : 0;
        double rockets = 0;

        double boostBelow = boostBelowSpeed.get() / TICKS_PER_SECOND;
        double maxTilt = maxPitchRate.get();
        int maxTicks = 20 * 60 * 30;
        int tick = 0;

        for (; tick < maxTicks && remaining > radius; tick++) {
            double speed = Math.sqrt(vh * vh + vy * vy);
            double speedBs = speed * TICKS_PER_SECOND;

            // State, like updateState()
            double heightAbove = y - targetY;
            double cone = st == State.Descend ? glideRatio.get() * 1.3 : glideRatio.get();
            boolean inCone = heightAbove > 0 && remaining <= heightAbove * cone + radius;

            if (inCone) st = State.Descend;
            else if (st == State.Climb ? y < cruise - 1 : y < cruise - 6) st = State.Climb;
            else st = State.Cruise;

            // Pitch, like the controllers above (positive looks down)
            boolean needBoostNow = true;
            double wanted;

            switch (st) {
                case Climb -> wanted = y - groundY < 10 || speedBs >= 10 ? -climbPitch.get() : 12;
                case Descend -> wanted = Mth.clamp(Math.toDegrees(Math.atan2(heightAbove, Math.max(remaining, 1))), 5, maxDivePitch.get());
                default -> {
                    if (!efficient) {
                        wanted = Mth.clamp(-(cruise - y) * 0.5, -25, maxDivePitch.get());
                    } else {
                        if (y >= high) climbingGlide = false;
                        else if (y <= cruise) climbingGlide = true;
                        else if (climbingGlide && speedBs < minGlideSpeed.get()) climbingGlide = false;
                        else if (!climbingGlide && speedBs >= maxGlideSpeed.get()) climbingGlide = true;

                        boolean atFloor = y <= cruise + 3;
                        needBoostNow = atFloor && speedBs < boostBelowSpeed.get();

                        if (climbingGlide) wanted = speedBs < minGlideSpeed.get() && atFloor ? 0 : -glideClimbPitch.get();
                        else wanted = Math.min(glideDivePitch.get(), maxDivePitch.get());
                    }
                }
            }

            pitch += Mth.clamp(Mth.clamp(wanted, -89, 89) - pitch, -maxTilt, maxTilt);

            // Fireworks, like boost()
            if (cooldown > 0) {
                cooldown--;
            } else if (useFireworks.get() && speed < boostBelow && active < timers.length
                && !(st == State.Climb && y - groundY < 10 && pitch > -climbPitch.get() + 12)
                && (st != State.Descend || boostOnDescent.get())
                && (st != State.Cruise || !efficient || needBoostNow)) {
                timers[active++] = lifetime;
                rockets++;
                cooldown = burn;
            }

            double rad = Math.toRadians(pitch);
            double lookH = Math.cos(rad);  // horizontal part of the look direction
            double lookY = -Math.sin(rad);

            // Every rocket pushes towards 1.5 blocks per tick along the look direction
            for (int i = 0; i < active; i++) {
                vh += lookH * 0.1 + (lookH * 1.5 - vh) * 0.5;
                vy += lookY * 0.1 + (lookY * 1.5 - vy) * 0.5;

                if (--timers[i] <= 0) {
                    timers[i--] = timers[--active];
                }
            }

            // Elytra flight physics
            double f2 = lookH * lookH;
            vy += 0.08 * (-1 + f2 * 0.75);

            if (vy < 0 && lookH > 0) {
                double d = vy * -0.1 * f2;
                vh += d;
                vy += d;
            }

            if (pitch < 0 && lookH > 0) {
                double d = Math.abs(vh) * -Math.sin(rad) * 0.04;
                vh -= d;
                vy += d * 3.2;
            }

            vh *= 0.99;
            vy *= 0.98;

            y += vy;
            remaining -= vh;
        }

        return new Estimate(rockets, tick, remaining <= radius);
    }

    /** Everything the estimate depends on. The estimate is only calculated again when one of these changes. */
    private int estimateSignature() {
        return java.util.Objects.hash(
            target.get(), arrivalRadius.get(), autoAltitude.get(), cruiseAltitude.get(), minAltitude.get(), clearance.get(),
            maxAltitude.get(), climbPitch.get(), efficientCruise.get(), altitudeBand.get(), glideDivePitch.get(),
            glideClimbPitch.get(), minGlideSpeed.get(), maxGlideSpeed.get(), glideRatio.get(), maxDivePitch.get(),
            maxPitchRate.get(), useFireworks.get(), boostBelowSpeed.get(), fireworkCooldown.get(), boostOnDescent.get(),
            rocketFlightDuration()
        );
    }

    /**
     * Calculates the fireworks for the whole route once, from the take off spot to the target, and keeps that number
     * while flying. It is only calculated again if the target or a relevant setting changes. Then the fireworks
     * already used plus the simulation of the rest become the new total.
     */
    private void refreshEstimate(boolean flying) {
        if (!showEstimate.get()) {
            estimate = null;
            return;
        }

        int signature = estimateSignature();
        if (estimate != null && signature == estimateSignature) return;

        estimateSignature = signature;

        Estimate rest = simulate(flying);
        estimate = new Estimate(rocketsUsed + rest.rockets(), flownTicks + rest.ticks(), rest.reached());
        if (initialEstimate < 0 && flownTicks == 0 && rocketsUsed == 0) initialEstimate = estimate.rockets();
    }

    /** Fireworks the module can actually use: the hotbar and the offhand. */
    private int countUsableFireworks() {
        int total = 0;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.FIREWORK_ROCKET)) total += stack.getCount();
        }

        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.is(Items.FIREWORK_ROCKET)) total += offhand.getCount();

        return total;
    }

    private double horizontalDistanceToTarget() {
        double dx = target.get().getX() + 0.5 - mc.player.getX();
        double dz = target.get().getZ() + 0.5 - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // Progress bar

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!progressBar.get() || !Utils.canUpdate() || startDistance < 0) return;

        GuiGraphicsExtractor graphics = event.graphics;

        double progress = startDistance <= 1 ? 1 : Mth.clamp(1 - remainingDistance / startDistance, 0, 1);
        if (state == State.Land && remainingDistance <= arrivalRadius.get()) progress = 1;

        int width = barWidth.get();
        int height = 5;
        int x = event.screenWidth / 2 - width / 2;
        int y = barY.get();

        if (barText.get()) {
            String eta = formatTime(etaSeconds);
            String text = "%d%%  %dm  %s %s".formatted(Math.round(progress * 100), Math.round(remainingDistance), LanguageManager.translate("module.elytra-navigator.eta", "ETA"), eta);

            graphics.text(mc.font, text, event.screenWidth / 2 - mc.font.width(text) / 2, y - mc.font.lineHeight - 3, 0xFFFFFFFF);
        }

        // Border, background, fill
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF000000);
        graphics.fill(x, y, x + width, y + height, 0xFF3A3A3A);
        graphics.fill(x, y, x + (int) Math.round(width * progress), y + height, barColor.get().getPacked());

        drawEstimate(graphics, event.screenWidth / 2, y + height + 4);
    }

    private void drawEstimate(GuiGraphicsExtractor graphics, int centerX, int y) {
        Estimate result = estimate;
        if (!showEstimate.get() || result == null) return;

        int line = mc.font.lineHeight + 2;

        // Fireworks: the total for the whole route, what was used so far, and what is in the hotbar and offhand
        if (useFireworks.get()) {
            int total = (int) Math.ceil(result.rockets() * (1 + estimateMargin.get() / 100.0));
            int stillNeeded = Math.max(0, total - rocketsUsed);
            int have = countUsableFireworks();
            boolean enough = have >= stillNeeded;

            String text = LanguageManager.translate("module.elytra-navigator.fireworks", "Fireworks: total %d, used %d, have %d").formatted(total, rocketsUsed, have);
            if (!result.reached()) text += " +";

            graphics.text(mc.font, text, centerX - mc.font.width(text) / 2, y, enough ? 0xFF8CFF8C : 0xFFFF5555);
            y += line;
        }

        // Elytra: one durability point is lost for every 20 ticks of flying. Only shown when it will not last.
        // Unbreaking is not counted, the real wear can only be lower than this.
        double ticksLeft = Math.max(result.ticks() - flownTicks, 0);
        int needed = (int) Math.ceil(ticksLeft / 20.0) + 1;
        int usable = durabilityLeft() - minDurability.get();

        if (usable < needed && durabilityLeft() != Integer.MAX_VALUE) {
            String text = LanguageManager.translate("module.elytra-navigator.durability-low", "Elytra durability too low: needs about %d, %d usable").formatted(needed, Math.max(usable, 0));
            graphics.text(mc.font, text, centerX - mc.font.width(text) / 2, y, 0xFFFF5555);
        }
    }

    private static String formatTime(double seconds) {
        int total = (int) Math.min(seconds, 99 * 3600 + 59 * 60 + 59);
        int h = total / 3600;
        int m = total % 3600 / 60;
        int s = total % 60;
        return h > 0 ? "%d:%02d:%02d".formatted(h, m, s) : "%d:%02d".formatted(m, s);
    }

    // Windows notification

    private void notifyWindows(String title, String message) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        if (notifyOnlyUnfocused.get() && mc.isWindowActive()) return;

        MeteorExecutor.execute(() -> {
            try {
                if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
                    MeteorClient.LOG.warn("Windows notifications are not available (headless or no system tray).");
                    return;
                }

                SystemTray tray = SystemTray.getSystemTray();
                TrayIcon icon = new TrayIcon(loadIcon(), "AMeteor Client");
                icon.setImageAutoSize(true);

                tray.add(icon);
                icon.displayMessage(title, message, TrayIcon.MessageType.INFO);

                // Keep the icon around long enough for the toast to be shown, then clean it up.
                Thread.sleep(10_000);
                tray.remove(icon);
            } catch (Throwable e) {
                MeteorClient.LOG.warn("Failed to show the Windows notification.", e);
            }
        });
    }

    private static Image loadIcon() {
        try (InputStream stream = ElytraNavigator.class.getResourceAsStream("/assets/meteor-client/icon.png")) {
            if (stream != null) {
                Image image = ImageIO.read(stream);
                if (image != null) return image;
            }
        } catch (Exception ignored) {
        }

        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    /** Whether a flight is under way. Used by the Dynamic Island. */
    public boolean isNavigating() {
        return isActive() && startDistance >= 0 && Utils.canUpdate() && mc.player.isFallFlying();
    }

    public double getProgress() {
        if (startDistance <= 1) return 1;
        if (state == State.Land && remainingDistance <= arrivalRadius.get()) return 1;
        return Mth.clamp(1 - remainingDistance / startDistance, 0, 1);
    }

    public double getRemainingDistance() {
        return remainingDistance;
    }

    public double getEtaSeconds() {
        return etaSeconds;
    }

    public int getRocketsUsed() {
        return rocketsUsed;
    }

    @Override
    public String getInfoString() {
        if (!Utils.canUpdate() || !mc.player.isFallFlying()) return null;

        BlockPos t = target.get();
        double dx = t.getX() + 0.5 - mc.player.getX();
        double dz = t.getZ() + 0.5 - mc.player.getZ();
        return "%s %.0fm".formatted(state, Math.sqrt(dx * dx + dz * dz));
    }
}
