/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.CrossbowItemAccessor;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.gui.utils.Anim;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Fires crossbows at targets by itself: picks the hotbar crossbow that is loaded, works out the angle with a
 * simulation of the arrow (gravity, drag, where the target will be, your own movement), turns to it and shoots.
 * While nothing is loaded it charges the next crossbow, so with several crossbows in the hotbar it keeps shooting.
 */
public class CrossbowRagebot extends Module {
    private static final int ROTATION_PRIORITY = -50;
    /** The speed of an arrow shot with a crossbow, in blocks per tick. */
    private static final double ARROW_SPEED = 3.15;
    private static final double FIREWORK_SPEED = 1.6;
    /** A rocket that is loaded counts as a hit when it passes this close to the target: its explosion reaches 5 blocks. */
    private static final double FIREWORK_BLAST = 2.0;
    /** Ticks to wait before a slot is looked at again after it was shot, until the server has unloaded it. */
    private static final int SHOT_LIMBO_TICKS = 5;

    /**
     * The random spread the server gives every shot: each part of the direction is moved by a triangular random number
     * of at most 0.0172275 (the divergence of a crossbow is 1). These are fixed samples of that distribution, used to
     * work out how likely a shot is to hit. The real numbers are made on the server when the arrow is made and cannot
     * be known before. The last three numbers of a sample (from -1 to 1) move the target by part of how unsure the
     * prediction is.
     */
    private static final double[][] SPREAD = new double[48][6];

    static {
        Random random = new Random(20240607);

        for (double[] sample : SPREAD) {
            for (int i = 0; i < 3; i++) sample[i] = 0.0172275 * (random.nextDouble() - random.nextDouble());
            for (int i = 3; i < 6; i++) sample[i] = random.nextDouble() * 2 - 1;
        }
    }

    public enum AimPoint {
        Body,
        Head,
        Feet
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgRapid = settings.createGroup("Rapid Fire");
    private final SettingGroup sgBallistics = settings.createGroup("Ballistics");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switches between the crossbows in your hotbar: to a loaded one to shoot, to an empty one to load it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> offhandMode = sgGeneral.add(new BoolSetting.Builder()
        .name("offhand-mode")
        .description("Uses the crossbow in your offhand: it is loaded and shot from there, and your main hand is left alone.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoLoad = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-load")
        .description("Charges empty crossbows by itself. Needs arrows.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> preload = sgGeneral.add(new BoolSetting.Builder()
        .name("preload")
        .description("Also loads crossbows while there is no target, so the first shot is instant.")
        .defaultValue(true)
        .visible(autoLoad::get)
        .build()
    );

    private final Setting<Integer> fireDelay = sgGeneral.add(new IntSetting.Builder()
        .name("fire-delay")
        .description("The least number of ticks between two shots.")
        .defaultValue(1)
        .range(1, 20)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> rapidFire = sgRapid.add(new BoolSetting.Builder()
        .name("rapid-fire")
        .description("Shoots loaded crossbows quickly, the way Bow Spam did: the use key stays down from the start of the charge, loaded crossbows from the inventory are used and the shots do not wait for the crossbow to show as empty.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> rapidDelay = sgRapid.add(new IntSetting.Builder()
        .name("rapid-delay")
        .description("The least number of ticks between two shots of the rapid fire.")
        .defaultValue(1)
        .range(1, 20)
        .sliderRange(1, 10)
        .visible(rapidFire::get)
        .build()
    );

    private final Setting<Double> noTargetHold = sgRapid.add(new DoubleSetting.Builder()
        .name("no-target-hold")
        .description("When no target is left, the use key stays down for this many seconds before it is let go, so a target that steps out of the range and comes back at once does not interrupt the rapid fire. 0 lets go at once.")
        .defaultValue(2)
        .min(0)
        .sliderRange(0, 10)
        .visible(rapidFire::get)
        .build()
    );

    private final Setting<Boolean> searchInventory = sgRapid.add(new BoolSetting.Builder()
        .name("search-inventory")
        .description("Also takes loaded crossbows from the inventory into the hotbar for the rapid fire.")
        .defaultValue(true)
        .visible(rapidFire::get)
        .build()
    );

    private final Setting<Boolean> holdRightClick = sgRapid.add(new BoolSetting.Builder()
        .name("when-holding-right-click")
        .description("The rapid fire and the bow spam only work while you hold the right mouse button yourself.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> bowSpam = sgRapid.add(new BoolSetting.Builder()
        .name("bow-spam")
        .description("Spams a bow in your hand: charges it for the ticks below and lets go, again and again.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> bowCharge = sgRapid.add(new IntSetting.Builder()
        .name("bow-charge")
        .description("How long a bow is charged before it is let go, in ticks.")
        .defaultValue(5)
        .range(4, 20)
        .sliderRange(4, 20)
        .visible(bowSpam::get)
        .build()
    );

    private final Setting<Boolean> protectTool = sgGeneral.add(new BoolSetting.Builder()
        .name("protect-tool")
        .description("Does not use a crossbow that is nearly broken, so it is not lost.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minDurability = sgGeneral.add(new IntSetting.Builder()
        .name("min-durability")
        .description("A crossbow with this percent of its durability left or less is left alone.")
        .defaultValue(5)
        .range(0, 99)
        .sliderRange(0, 50)
        .visible(protectTool::get)
        .build()
    );

    private final Setting<Integer> keepArrows = sgGeneral.add(new IntSetting.Builder()
        .name("keep-arrows")
        .description("Stops loading when this many arrows or less are left in the inventory, so there is always some for other things.")
        .defaultValue(0)
        .range(0, 256)
        .sliderRange(0, 64)
        .build()
    );

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-use")
        .description("Does nothing while you are using another item, like eating.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseInScreens = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-in-screens")
        .description("Does nothing while a screen is open.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pausePathing = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-pathing")
        .description("Pauses Baritone while there is a target.")
        .defaultValue(true)
        .build()
    );

    // Targeting

    private final Setting<Set<EntityType<?>>> entities = sgTargeting.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("The entities that can be shot at.")
        .onlyAttackable()
        .defaultValue(EntityTypes.PLAYER)
        .build()
    );

    private final Setting<TargetMode> targetMode = sgTargeting.add(new EnumSetting.Builder<TargetMode>()
        .name("target-mode")
        .description("Single keeps shooting at the one the priority picks. Switch goes round all the targets in range, one shot each, because a mob that was just hit cannot be hurt again for a few ticks.")
        .defaultValue(TargetMode.Single)
        .build()
    );

    private final Setting<Boolean> playersFirst = sgTargeting.add(new BoolSetting.Builder()
        .name("players-first")
        .description("Players are shot at before any other kind of target. The others only get a turn when no player can be hit.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> switchLimit = sgTargeting.add(new IntSetting.Builder()
        .name("switch-limit")
        .description("The most targets the switch mode goes round.")
        .defaultValue(1024)
        .range(2, 100000)
        .sliderRange(16, 2048)
        .visible(() -> targetMode.get() == TargetMode.Switch)
        .build()
    );

    private final Setting<SortPriority> priority = sgTargeting.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("How the target is chosen.")
        .defaultValue(SortPriority.ClosestAngle)
        .build()
    );

    private final Setting<Double> range = sgTargeting.add(new DoubleSetting.Builder()
        .name("range")
        .description("The farthest a target can be.")
        .defaultValue(40)
        .range(1, 120)
        .sliderRange(5, 100)
        .build()
    );

    private final Setting<Double> minRange = sgTargeting.add(new DoubleSetting.Builder()
        .name("min-range")
        .description("The closest a target can be. Very close targets are better hit with a sword.")
        .defaultValue(2)
        .range(0, 20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<AimPoint> aimPoint = sgTargeting.add(new EnumSetting.Builder<AimPoint>()
        .name("aim-point")
        .description("Which part of the target to aim at.")
        .defaultValue(AimPoint.Body)
        .build()
    );

    private final Setting<Boolean> visibleOnly = sgTargeting.add(new BoolSetting.Builder()
        .name("visible-only")
        .description("Only targets that you can see (the arrow path is always checked for blocks).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> attackBabies = sgTargeting.add(new BoolSetting.Builder()
        .name("attack-babies")
        .description("Whether to shoot baby variants of entities.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> attackNeutral = sgTargeting.add(new BoolSetting.Builder()
        .name("attack-neutral")
        .description("Whether to shoot neutral mobs.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> skipClosedShulkers = sgTargeting.add(new BoolSetting.Builder()
        .name("skip-closed-shulkers")
        .description("Does not shoot at a shulker while its shell is closed, it takes no damage then.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreInvisible = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-invisible")
        .description("Does not shoot at invisible entities.")
        .defaultValue(false)
        .build()
    );

    // Ballistics

    private final Setting<Boolean> predictMovement = sgBallistics.add(new BoolSetting.Builder()
        .name("predict-movement")
        .description("Aims where a moving target will be when the arrow gets there.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> predictTurns = sgBallistics.add(new BoolSetting.Builder()
        .name("predict-turns")
        .description("A target that turns, like a circling phantom, is followed along its curve instead of a straight line.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> predictOwnStep = sgBallistics.add(new BoolSetting.Builder()
        .name("predict-own-step")
        .description("The arrow is made where you are after this tick's step. Aims from there, which matters a lot when you move fast.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> latencyCompensation = sgBallistics.add(new BoolSetting.Builder()
        .name("latency-compensation")
        .description("Aims further ahead by your ping: the server only makes the arrow after your shot arrives, and the target keeps moving.")
        .defaultValue(true)
        .visible(predictMovement::get)
        .build()
    );

    private final Setting<Double> leadOffset = sgBallistics.add(new DoubleSetting.Builder()
        .name("lead-offset")
        .description("Aims this many ticks further ahead (or behind with a negative number). Use it to tune the aim for a server.")
        .defaultValue(0)
        .range(-5, 10)
        .sliderRange(-3, 6)
        .visible(predictMovement::get)
        .build()
    );

    private final Setting<Integer> minHitChance = sgBallistics.add(new IntSetting.Builder()
        .name("min-hit-chance")
        .description("Only shoots when this many percent of simulated shots (with the random spread of the crossbow) would hit. Lower targets and a moving or far away target lower the chance.")
        .defaultValue(60)
        .range(0, 100)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Boolean> autoCalibrate = sgBallistics.add(new BoolSetting.Builder()
        .name("auto-calibrate")
        .description("Watches where your arrows land compared to the target, and moves the aim ahead or back to fix a lead that is always too short or too long.")
        .defaultValue(true)
        .visible(predictMovement::get)
        .build()
    );

    private final Setting<Boolean> debug = sgBallistics.add(new BoolSetting.Builder()
        .name("debug")
        .description("Writes a very detailed log of every shot to meteor-client/crossbow-ragebot.log, and a csv with a row per shot next to it: the angles it should and did shoot, your movement, the movement of the target, the prediction, the spread the server gave the arrow, and where the arrow really went.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> trace = sgBallistics.add(new BoolSetting.Builder()
        .name("debug-trace")
        .description("Also writes a line to the log every tick while there is a target: its positions, speed and the aim. The log gets big fast.")
        .defaultValue(false)
        .visible(debug::get)
        .build()
    );

    private final Setting<Boolean> chatSummary = sgBallistics.add(new BoolSetting.Builder()
        .name("chat-summary")
        .description("Also writes a short line in the chat for every shot and where its arrow landed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> highArc = sgBallistics.add(new BoolSetting.Builder()
        .name("high-arc")
        .description("When the straight shot is blocked by a block, tries a high lob that goes over it. The flight is longer, so a moving target is harder to hit and the hit chance is lower.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxFlightTicks = sgBallistics.add(new IntSetting.Builder()
        .name("max-flight-ticks")
        .description("Does not shoot when the arrow needs longer than this to arrive.")
        .defaultValue(60)
        .range(5, 120)
        .sliderRange(10, 100)
        .build()
    );

    private final Setting<Double> accuracy = sgBallistics.add(new DoubleSetting.Builder()
        .name("accuracy")
        .description("How close the calculated arrow has to land to the aim point before shooting, in blocks.")
        .defaultValue(0.35)
        .range(0.05, 1.5)
        .sliderRange(0.1, 1)
        .build()
    );

    // Render

    private final Setting<Boolean> renderPath = sgRender.add(new BoolSetting.Builder()
        .name("render-path")
        .description("Draws the path the arrow will take and where it lands.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderPrediction = sgRender.add(new BoolSetting.Builder()
        .name("render-prediction")
        .description("Draws where the target is expected to be when the arrow arrives.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> shaderFx = sgRender.add(new BoolSetting.Builder()
        .name("shader-effects")
        .description("Draws the effects with a shader: sharp, glowing and animated. When off (or when the shader cannot be used) simple lines are drawn.")
        .defaultValue(true)
        .build()
    );

    private final Setting<FxColorMode> fxColorMode = sgRender.add(new EnumSetting.Builder<FxColorMode>()
        .name("effect-colors")
        .description("Chance: red when the shot is unlikely to hit and green when it is likely. Custom: the two colors below. Rainbow: slowly through all colors.")
        .defaultValue(FxColorMode.Chance)
        .visible(shaderFx::get)
        .build()
    );

    private final Setting<SettingColor> fxPrimary = sgRender.add(new ColorSetting.Builder()
        .name("primary-color")
        .description("The main color of the effects.")
        .defaultValue(new SettingColor(80, 200, 255, 255))
        .visible(() -> shaderFx.get() && fxColorMode.get() == FxColorMode.Custom)
        .build()
    );

    private final Setting<SettingColor> fxSecondary = sgRender.add(new ColorSetting.Builder()
        .name("secondary-color")
        .description("The second color: thin lines, the end of the path and the highlights.")
        .defaultValue(new SettingColor(190, 120, 255, 255))
        .visible(() -> shaderFx.get() && fxColorMode.get() == FxColorMode.Custom)
        .build()
    );

    private final Setting<Double> fxIntensity = sgRender.add(new DoubleSetting.Builder()
        .name("intensity")
        .description("How bright the effects are.")
        .defaultValue(1)
        .range(0.1, 3)
        .sliderRange(0.2, 2.5)
        .visible(shaderFx::get)
        .build()
    );

    private final Setting<Double> fxGlow = sgRender.add(new DoubleSetting.Builder()
        .name("glow")
        .description("How strong the soft glow round the lines is.")
        .defaultValue(1)
        .range(0, 3)
        .sliderRange(0, 2)
        .visible(shaderFx::get)
        .build()
    );

    private final Setting<Double> fxSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("animation-speed")
        .description("How fast the effects move.")
        .defaultValue(1)
        .range(0.1, 4)
        .sliderRange(0.2, 3)
        .visible(shaderFx::get)
        .build()
    );

    private final Setting<Double> fxScale = sgRender.add(new DoubleSetting.Builder()
        .name("scale")
        .description("The size of the effects.")
        .defaultValue(1)
        .range(0.3, 3)
        .sliderRange(0.5, 2)
        .visible(shaderFx::get)
        .build()
    );

    private final Setting<Boolean> fxThroughWalls = sgRender.add(new BoolSetting.Builder()
        .name("through-walls")
        .description("Draws the effects through blocks. When off, blocks hide them.")
        .defaultValue(true)
        .visible(shaderFx::get)
        .build()
    );

    private final Setting<Boolean> fxReticle = sgRender.add(new BoolSetting.Builder()
        .name("reticle")
        .description("A reticle on the target that closes in when it locks on, with the hit chance as an arc round it.")
        .defaultValue(true)
        .visible(shaderFx::get)
        .build()
    );

    private final Setting<Boolean> fxRibbon = sgRender.add(new BoolSetting.Builder()
        .name("path-ribbon")
        .description("A ribbon of light with moving pulses along the path of the shot, and on the arrows on their way.")
        .defaultValue(true)
        .visible(shaderFx::get)
        .build()
    );

    private final Setting<Boolean> fxBursts = sgRender.add(new BoolSetting.Builder()
        .name("impact-bursts")
        .description("A shock wave with rays where an arrow hits, a small ring where it misses.")
        .defaultValue(true)
        .visible(shaderFx::get)
        .build()
    );

    private final Setting<Boolean> fxGround = sgRender.add(new BoolSetting.Builder()
        .name("ground-radar")
        .description("A radar on the ground under the target, with a sweep, a wave and the hit chance round it.")
        .defaultValue(false)
        .visible(shaderFx::get)
        .build()
    );

    private final Setting<Boolean> fxBeam = sgRender.add(new BoolSetting.Builder()
        .name("light-beam")
        .description("A beam of light, like a hologram, over the target.")
        .defaultValue(false)
        .visible(shaderFx::get)
        .build()
    );

    private final Setting<Boolean> fxTrails = sgRender.add(new BoolSetting.Builder()
        .name("fading-trails")
        .description("The path of arrows that are done stays and fades away slowly.")
        .defaultValue(false)
        .visible(shaderFx::get)
        .build()
    );

    private final Setting<SettingColor> pathColor = sgRender.add(new ColorSetting.Builder()
        .name("path-color")
        .description("The color of the path.")
        .defaultValue(new SettingColor(255, 120, 120, 200))
        .visible(renderPath::get)
        .build()
    );

    private final Setting<SettingColor> impactColor = sgRender.add(new ColorSetting.Builder()
        .name("impact-color")
        .description("The color of the box where the arrow lands.")
        .defaultValue(new SettingColor(255, 70, 70, 80))
        .visible(renderPath::get)
        .build()
    );

    // State

    private record Impact(Vec3 point, double ticks, boolean blocked, List<Vec3> path) {
    }

    /** Everything the solver worked out on the way, for the log. */
    public enum TargetMode {
        Single,
        Switch
    }

    private record Detail(Vec3 base, Vec3 aimAt, Vec3 predicted, Vec3 start, Vec3 own, Vec3 initialVelocity,
                          int iterations, double residual, double pingTicks, double offsetTicks, double calibrationTicks,
                          double margin, double speed, double horizontal, double ground) {
    }

    private record Solution(float yaw, float pitch, Impact impact, AABB predictedBox, Vec3 velocity, double leadTicks, double hitChance, Detail detail) {
    }

    /** A shot that was fired and waits for its arrow to show up. */
    private static final class Shot {
        final int index, targetId, tick;
        final Solution solution;
        /** The projectile flies like a firework rocket. */
        boolean firework;
        /** The turn that was really sent right before the shot. */
        float sentYaw, sentPitch;
        /** What is written at the time of the shot. The rest is added when the arrow has landed. */
        String block = "";
        final Map<String, String> csv = new LinkedHashMap<>();

        Shot(int index, int targetId, int tick, Solution solution) {
            this.index = index;
            this.targetId = targetId;
            this.tick = tick;
            this.solution = solution;
        }
    }

    /** The path of one of our arrows and of the target next to it, tick by tick. */
    private static final class Track {
        final Shot shot;
        final List<Vec3> arrow = new ArrayList<>();
        final List<Vec3> target = new ArrayList<>();
        int started;
        int arrowId = -1;
        /** The arrow was removed from the world (it hit something) instead of timing out. */
        boolean vanished;
        /** What is known about the arrow at the moment it showed up. */
        String spawn = "";

        Track(Shot shot, int started) {
            this.shot = shot;
            this.started = started;
        }
    }

    /** A position the server told us about, and the tick we got it in. */
    private record Update(int tick, Vec3 position) {
    }

    /** The last few position updates of every entity. The server only sends them every 2 or 3 ticks. */
    private final Map<Integer, ArrayDeque<Update>> history = new HashMap<>();
    /** Ticks the client may need to show a crossbow as loaded after it was let go. */
    private static final int LOADING_LIMBO_TICKS = 4;

    /** The slot number that stands for the offhand. */
    private static final int OFFHAND_SLOT = 40;
    private final int[] shotAt = new int[OFFHAND_SLOT + 1];
    private int releasedAt = -1000;
    private int lastShotTarget = -1;
    /** Whether the shot is good enough. The switch mode shoots at a low chance too, as long as the arrow can get there. */
    private boolean shotWorthTaking() {
        if (solution == null) return false;

        return solution.hitChance >= minHitChance.get() / 100.0 || (targetMode.get() == TargetMode.Switch && !solution.impact.blocked);
    }

    /** The target the switch mode is on, whether an arrow was shot at it, and since when it is on it. */
    private int switchCurrent = -1, switchSince;
    private boolean switchFired;
    private static final int SWITCH_PATIENCE_TICKS = 100;
    /** The use key is kept down until this tick (Bow Spam mode). */
    private int holdUntil = -1;
    /** How many valid targets there are in range right now. */
    private int candidateCount;
    /** The last tick there was a target in range, for the hold without a target. */
    private int lastTargetTick = -1000;
    private int lobBudget;
    /** The click of this module is being made (see {@link #aimForUse}). */
    private boolean firing;
    /** What is loaded now flies like a firework rocket: straight, without gravity or drag. */
    private boolean fireworkPhysics;
    /** How many ticks a firework rocket flies at least, from its flight duration. */
    private int fireworkTicks = 20;
    private static final int LOBS_PER_TICK = 6;
    /** A lob goes nearly straight up and comes down about five seconds later. */
    private static final int LOB_MAX_TICKS = 160;
    /** Horizontal speed (blocks per tick) up to which a target counts as standing still, the lob is for those only. */
    private static final double LOB_MAX_TARGET_SPEED = 0.03;
    private final List<Shot> pendingShots = new ArrayList<>();
    private final java.util.Set<Integer> knownArrows = new java.util.HashSet<>();
    private final java.util.Set<Integer> otherArrows = new java.util.HashSet<>();
    private final Map<Integer, Track> tracks = new HashMap<>();
    /** How many ticks the lead was too long (negative) or too short (positive), learned from where arrows landed. */
    private double calibration;
    private String lastLoadEvent = "";
    private Entity target;
    private Solution solution;
    private int tickCounter;
    private int sinceShot;
    private boolean pressedByUs;
    private boolean wasPathing;

    public CrossbowRagebot() {
        super(Categories.Combat, "crossbow-ragebot", "Shoots crossbows at targets by itself: loads them, works out the arrow path and fires.");
    }

    @Override
    public void onActivate() {
        history.clear();
        target = null;
        solution = null;
        tickCounter = 0;
        sinceShot = 100;
        pressedByUs = false;
        wasPathing = false;
        pendingShots.clear();
        trails.clear();
        markers.clear();
        flights.clear();
        hitIds.clear();
        knownArrows.clear();
        otherArrows.clear();
        tracks.clear();
        calibration = 0;
        shotCounter = 0;
        lastState = "";
        lastLoadEvent = "";
        statShots = statMeasured = statHits = 0;
        statAlong = statAbsAlong = statSide = statAbsSide = statUp = statAbsUp = 0;

        for (int i = 0; i < shotAt.length; i++) shotAt[i] = -1000;
        releasedAt = -1000;
        lastTargetTick = -1000;
        lastShotTarget = -1;
        switchCurrent = -1;
        switchFired = false;
    }

    @Override
    public void onDeactivate() {
        closeLog();
        holdUntil = -1;
        releaseKey();
        if (bowKeyDown) {
            mc.options.keyUse.setDown(false);
            bowKeyDown = false;
        }
        history.clear();
        target = null;
        solution = null;

        if (wasPathing) {
            PathManagers.get().resume();
            wasPathing = false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        tickCounter++;
        sinceShot++;
        target = null;
        solution = null;

        // Where the player looks before this module turns anything
        viewYaw = mc.player.getYRot();
        viewPitch = mc.player.getXRot();

        String paused = pausedBecause();

        if (paused != null) {
            state("paused:" + paused, "PAUSED", paused);
            holdUntil = -1;
            releaseKey();
            return;
        }

        // A bow in the hand: the bow spam does it, the crossbow logic waits
        if (bowTick()) return;

        trackPositions();
        watchArrows();
        updateFlights();

        boolean spam = rapid();
        int[] crossbows = hotbarCrossbows();
        if (crossbows.length == 0 && !(spam && searchInventory.get())) {
            state("nocrossbow", "IDLE", "no usable crossbow in the hotbar (none, or all are nearly broken)");
            releaseKey();
            return;
        }

        int held = offhandMode.get() ? OFFHAND_SLOT : mc.player.getInventory().getSelectedSlot();
        int loaded = findSlot(crossbows, held, true);

        // The speed depends on what is loaded, firework rockets are slower than arrows
        fireworkPhysics = loaded >= 0 ? isFireworkLoaded(stackAt(loaded)) : false;
        if (loaded >= 0 && fireworkPhysics) fireworkTicks = fireworkTicksOf(stackAt(loaded));
        double speed = loaded >= 0 ? speedOf(stackAt(loaded)) : fireworkPhysics ? FIREWORK_SPEED : ARROW_SPEED;
        chooseTarget(speed);

        // Bow Spam mode: the key stays down for as long as there is something left to shoot at
        if (candidateCount > 0) lastTargetTick = tickCounter;
        holdUntil = spam && holdWanted() ? tickCounter + 1 : -1;

        try {
            logDecision(loaded);
            logTrace();
        } catch (RuntimeException e) {
            logProblem(e);
        }

        if (target != null && pausePathing.get() && PathManagers.get().isPathing() && !wasPathing) {
            PathManagers.get().pause();
            wasPathing = true;
        } else if (target == null && wasPathing) {
            PathManagers.get().resume();
            wasPathing = false;
        }

        if (target != null && loaded >= 0 && shotWorthTaking()) {
            shoot(loaded, held);
            return;
        }

        // Out of loaded crossbows in the hotbar: bring a loaded one from the inventory, Bow Spam style
        if (target != null && solution != null && loaded < 0 && spam && searchInventory.get() && !offhandMode.get()
            && shotWorthTaking() && pullLoadedCrossbow()) {
            Rotations.rotate(solution.yaw, solution.pitch, ROTATION_PRIORITY);
            return;
        }

        // Nothing to shoot with, or nothing to shoot at: keep looking at the target and load the next crossbow
        if (solution != null) Rotations.rotate(solution.yaw, solution.pitch, ROTATION_PRIORITY);

        if (autoLoad.get() && (target != null || preload.get())) load(crossbows, held);
        else releaseKey();
    }

    /** Why nothing is done right now, or null when it can work. */
    private String pausedBecause() {
        if (!mc.player.isAlive()) return "the player is dead";
        if (PlayerUtils.getGameMode() == GameType.SPECTATOR) return "spectator mode";
        if (pauseInScreens.get() && mc.gui.screen() != null) return "a screen is open";

        // Charging a crossbow is using an item too
        if (pauseOnUse.get() && mc.player.isUsingItem() && !(mc.player.getUseItem().getItem() instanceof CrossbowItem)) return "using another item";

        return null;
    }

    // Crossbows

    /** Whether the use key is to stay down: there is a target, or the last one left a moment ago. It is never let go in between. */
    private boolean holdWanted() {
        if (candidateCount > 0) return true;

        return noTargetHold.get() > 0 && tickCounter - lastTargetTick <= Math.round(noTargetHold.get() * 20);
    }

    /** Whether the rapid fire is on now: it is set, and the right mouse button is held if that is wanted. */
    private boolean rapid() {
        return rapidFire.get() && physicalUseHeld();
    }

    /** Whether the player holds the use button, not the module. Always true when the setting for it is off. */
    private boolean physicalUseHeld() {
        return !holdRightClick.get() || Input.isPressed(mc.options.keyUse);
    }

    private boolean bowKeyDown;

    /** The bow spam. Returns true when a bow is in the hand, then the crossbow logic leaves it alone. */
    private boolean bowTick() {
        boolean bow = mc.player.getMainHandItem().is(Items.BOW) || mc.player.getOffhandItem().is(Items.BOW);

        if (!bowSpam.get() || !bow) {
            if (bowKeyDown) {
                mc.options.keyUse.setDown(false);
                bowKeyDown = false;
            }

            return false;
        }

        if (!mc.player.getAbilities().instabuild && !InvUtils.find(stack -> stack.getItem() instanceof ArrowItem).found()) return true;

        if (!physicalUseHeld()) {
            if (bowKeyDown) {
                mc.options.keyUse.setDown(false);
                bowKeyDown = false;
            }

            return true;
        }

        if (mc.player.getTicksUsingItem() >= bowCharge.get()) {
            mc.gameMode.releaseUsingItem(mc.player);
        } else {
            mc.options.keyUse.setDown(true);
            bowKeyDown = true;
        }

        return true;
    }

    /** Moves a loaded crossbow from the inventory into the hotbar. True when something was moved. */
    private boolean pullLoadedCrossbow() {
        FindItemResult crossbow = InvUtils.find(stack -> stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack) && usable(stack), 9, 35);
        if (!crossbow.found()) return false;

        FindItemResult spot = InvUtils.find(stack -> stack.isEmpty() || stack.is(Items.CROSSBOW) || stack.is(Items.ARROW), 0, 8);
        if (!spot.found()) return false;

        event("SWAP", "loaded crossbow from inventory slot %d to hotbar slot %d".formatted(crossbow.slot(), spot.slot()));
        InvUtils.quickSwap().fromId(spot.slot()).to(crossbow.slot());
        return true;
    }

    private InteractionHand hand() {
        return offhandMode.get() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    private ItemStack handStack() {
        return mc.player.getItemInHand(hand());
    }

    private ItemStack stackAt(int slot) {
        return slot == OFFHAND_SLOT ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(slot);
    }

    private int[] hotbarCrossbows() {
        if (offhandMode.get()) {
            ItemStack stack = mc.player.getOffhandItem();
            return stack.getItem() instanceof CrossbowItem && usable(stack) ? new int[] {OFFHAND_SLOT} : new int[0];
        }

        int count = 0;
        int[] slots = new int[9];

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.getItem() instanceof CrossbowItem && usable(stack)) slots[count++] = slot;
        }

        return java.util.Arrays.copyOf(slots, count);
    }

    private boolean isLoaded(int slot) {
        ItemStack stack = stackAt(slot);
        return stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack) && (rapid() || tickCounter - shotAt[slot] > SHOT_LIMBO_TICKS);
    }

    private boolean isInLimbo(int slot) {
        return tickCounter - shotAt[slot] <= SHOT_LIMBO_TICKS;
    }

    /** The first crossbow that is loaded (or empty), the one in the hand first. Returns -1 when there is none. */
    private int findSlot(int[] crossbows, int held, boolean loaded) {
        boolean heldIsCrossbow = false;
        for (int slot : crossbows) heldIsCrossbow |= slot == held;

        if (heldIsCrossbow && matches(held, loaded)) return held;

        for (int slot : crossbows) {
            if (matches(slot, loaded)) return slot;
        }

        return -1;
    }

    private boolean matches(int slot, boolean loaded) {
        if (loaded) return isLoaded(slot);
        return !isInLimbo(slot) && !CrossbowItem.isCharged(stackAt(slot));
    }

    private static boolean isFireworkLoaded(ItemStack crossbow) {
        ChargedProjectiles projectiles = crossbow.get(DataComponents.CHARGED_PROJECTILES);
        if (projectiles == null || projectiles.isEmpty()) return false;

        for (ItemStack stack : projectiles.itemCopies()) {
            if (stack.getItem() instanceof FireworkRocketItem) return true;
        }

        return false;
    }

    /** A rocket flies for 10 ticks per step of flight duration (and one more), plus a random 0 to 11. This is the least. */
    private static int fireworkTicksOf(ItemStack crossbow) {
        ChargedProjectiles projectiles = crossbow.get(DataComponents.CHARGED_PROJECTILES);
        if (projectiles == null) return 20;

        for (ItemStack stack : projectiles.itemCopies()) {
            if (!(stack.getItem() instanceof FireworkRocketItem)) continue;

            var fireworks = stack.get(DataComponents.FIREWORKS);
            return 10 * (1 + (fireworks != null ? fireworks.flightDuration() : 1));
        }

        return 20;
    }

    private static double speedOf(ItemStack crossbow) {
        ChargedProjectiles projectiles = crossbow.get(DataComponents.CHARGED_PROJECTILES);
        return projectiles == null || projectiles.isEmpty() ? ARROW_SPEED : CrossbowItemAccessor.meteor$getSpeed(projectiles);
    }

    private boolean hasAmmo() {
        return mc.player.getAbilities().instabuild || arrowCount() > keepArrows.get();
    }

    private int arrowCount() {
        int count = 0;

        for (int slot = 0; slot < mc.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.getItem() instanceof ArrowItem) count += stack.getCount();
        }

        return count;
    }

    /** False for a crossbow that is nearly broken, when the protection is on. */
    private boolean usable(ItemStack stack) {
        if (!protectTool.get() || !stack.isDamageableItem()) return true;

        int max = stack.getMaxDamage();
        return max <= 0 || (max - stack.getDamageValue()) * 100.0 / max > minDurability.get();
    }

    private void shoot(int loaded, int held) {
        if (loaded != held) {
            if (!autoSwitch.get()) return;

            releaseKey();
            event("SWAP", "slot %d -> %d to shoot with the loaded crossbow".formatted(held, loaded));
            InvUtils.swap(loaded, false);
        }

        if (sinceShot < (rapid() ? Math.max(1, rapidDelay.get()) : fireDelay.get())) {
            // Keep the aim on the target until the next shot is allowed
            Rotations.rotate(solution.yaw, solution.pitch, ROTATION_PRIORITY);
            return;
        }

        int slot = loaded;

        // The turn is sent first and the shot right after it, so the arrow flies in the direction that was worked out
        Rotations.rotate(solution.yaw, solution.pitch, ROTATION_PRIORITY, () -> fire(slot));
    }

    private void fire(int slot) {
        if (mc.player == null || mc.gameMode == null) return;

        ItemStack stack = handStack();
        if (!(stack.getItem() instanceof CrossbowItem) || !CrossbowItem.isCharged(stack)) return;

        // The turn that was just sent: while this runs it is the rotation of the player
        Shot shot = null;

        if (solution != null && target != null) {
            shot = new Shot(++shotCounter, target.getId(), tickCounter, solution);
            lastShotTarget = target.getId();
            // Only a shot with a good enough chance counts, the others are extra
            if (target.getId() == switchCurrent && solution.hitChance >= minHitChance.get() / 100.0) switchFired = true;
            lastShotChance = solution.hitChance;
            shot.firework = fireworkPhysics;
            shot.sentYaw = mc.player.getYRot();
            shot.sentPitch = mc.player.getXRot();

            // Before the shot, the crossbow still holds its projectile
            try {
                if (log() != null || chatSummary.get()) describeFire(shot, slot, target, stack);
            } catch (RuntimeException e) {
                logProblem(e);
            }
        }

        // Bow Spam's way: switch to the crossbow just for the click and back, it does not wait for the crossbow to be charged again
        boolean spamStyle = rapid() && !offhandMode.get();

        firing = true;

        try {
            if (spamStyle) InvUtils.swap(slot, true);
            mc.gameMode.useItem(mc.player, hand());
            if (spamStyle) InvUtils.swapBack();
        } finally {
            firing = false;
        }
        mc.player.swing(hand());

        // The server takes a moment to tell that it is empty. Using it again now would start charging it by accident.
        shotAt[slot] = tickCounter;
        sinceShot = 0;

        if (shot != null) pendingShots.add(shot);
    }

    private void load(int[] crossbows, int held) {
        if (!hasAmmo()) {
            releaseKey();
            return;
        }

        int empty = findSlot(crossbows, held, false);
        if (empty < 0) {
            releaseKey();
            return;
        }

        if (empty != held) {
            if (!autoSwitch.get()) {
                releaseKey();
                return;
            }

            InvUtils.swap(empty, false);
        }

        ItemStack stack = handStack();
        if (!(stack.getItem() instanceof CrossbowItem)) return;

        if (mc.player.isUsingItem() && mc.player.getUseItem().getItem() instanceof CrossbowItem) {
            boolean charged = mc.player.getTicksUsingItem() >= CrossbowItem.getChargeDuration(stack, mc.player);

            // Bow Spam mode: keep holding after the charge is full and let the shots come, until no target is left
            if (charged && rapid() && holdWanted()) {
                loadEvent("hold:" + empty, "HOLD", "slot %d is charged, keeping the key down while %d targets are left (Bow Spam mode)".formatted(mc.player.getInventory().getSelectedSlot(), candidateCount));
                holdUntil = tickCounter + 1;
                press();
            } else if (charged) {
                holdUntil = -1;
                // Letting go finishes the loading. The key has to be up too, or the next click would shoot it.
                loadEvent("loaded:" + empty, "LOADED", "slot %d is charged after %d ticks".formatted(mc.player.getInventory().getSelectedSlot(), mc.player.getTicksUsingItem()));
                mc.gameMode.releaseUsingItem(mc.player);
                releaseKey();
                releasedAt = tickCounter;
            } else {
                press();
            }
        } else if (tickCounter - releasedAt <= LOADING_LIMBO_TICKS) {
            // The server has the crossbow loaded already, the client only shows it a moment later. Clicking now would shoot it
            // in the direction you look, before this module has turned.
            releaseKey();
        } else {
            loadEvent("charge:" + empty, "CHARGE", "charging slot %d (%d ticks needed)".formatted(empty, CrossbowItem.getChargeDuration(stack, mc.player)));
            // The use key would click the main hand first, so the offhand is started directly
            if (offhandMode.get()) mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            press();
        }
    }

    /** A line about loading, once per change. It has its own memory so it does not mix with the other lines. */
    private void loadEvent(String key, String kind, String text) {
        if (key.equals(lastLoadEvent)) return;

        lastLoadEvent = key;
        event(kind, text);
    }

    private void press() {
        mc.options.keyUse.setDown(true);
        pressedByUs = true;
    }

    private void releaseKey() {
        // With Bow Spam the key stays down from the start of the charge, that is what keeps the shots coming
        if (tickCounter <= holdUntil && rapid()) return;
        if (!pressedByUs) return;

        mc.options.keyUse.setDown(false);
        pressedByUs = false;
    }

    // Targets

    private void trackPositions() {
        Set<Integer> seen = new HashSet<>();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!entities.get().contains(entity.getType())) continue;

            ArrayDeque<Update> updates = history.computeIfAbsent(entity.getId(), id -> new ArrayDeque<>());
            Vec3 position = serverPosition(entity);

            // Only a new position counts. In between there is nothing new, the entity was not told to move.
            if (updates.isEmpty() || !updates.getLast().position.equals(position)) {
                updates.addLast(new Update(tickCounter, position));
                if (updates.size() > 4) updates.removeFirst();
            }

            seen.add(entity.getId());
        }

        history.keySet().retainAll(seen);
    }

    /** How the entity moved per tick, from the last few ticks. */
    private Vec3 velocityOf(Entity entity) {
        ArrayDeque<Update> updates = history.get(entity.getId());
        if (updates == null || updates.size() < 2) return Vec3.ZERO;

        Update last = updates.getLast();

        // Nothing new for a while means it stopped
        if (tickCounter - last.tick > 8) return Vec3.ZERO;

        // From update to update: the distance between them over the ticks that passed. A step of the last tick alone
        // would be 0 one time and the whole distance the next, because the updates come every 2 or 3 ticks.
        Update[] all = updates.toArray(new Update[0]);
        Update from = all[Math.max(0, all.length - 3)];
        double ticks = last.tick - from.tick;
        if (ticks <= 0) return Vec3.ZERO;

        Vec3 velocity = last.position.subtract(from.position).scale(1.0 / ticks);

        // A target that turns: the average over the last updates points the way it went a while ago, so the newest step is
        // used, turned a little further to the way it points now
        double omega = predictTurns.get() ? turnRateOf(entity) : 0;

        if (Math.abs(omega) > 0.02 && all.length >= 3) {
            Update before = all[all.length - 2];
            double dt = last.tick - before.tick;

            if (dt > 0) {
                Vec3 newest = last.position.subtract(before.position).scale(1 / dt);
                double turn = omega * dt / 2;
                double c = Math.cos(turn), s = Math.sin(turn);
                velocity = new Vec3(newest.x * c - newest.z * s, velocity.y, newest.x * s + newest.z * c);
            }
        }

        // Standing on the ground the height only wobbles
        return entity.onGround() ? new Vec3(velocity.x, 0, velocity.z) : velocity;
    }

    /**
     * Where the entity is now, as well as can be known: the last position the server sent, moved on by the ticks that
     * passed since then.
     */
    private Vec3 knownPosition(Entity entity) {
        Vec3 position = serverPosition(entity);
        if (!predictMovement.get()) return position;

        ArrayDeque<Update> updates = history.get(entity.getId());
        if (updates == null || updates.isEmpty()) return position;

        int stale = tickCounter - updates.getLast().tick;
        if (stale <= 0 || stale > 6) return position;

        return position.add(velocityOf(entity).scale(stale));
    }

    /**
     * Where the server last said the entity is. What is on the screen is smoothed towards that point over a few ticks, so
     * it is always behind.
     */
    private static Vec3 serverPosition(Entity entity) {
        var interpolation = entity.getInterpolation();
        return interpolation != null && interpolation.hasActiveInterpolation() ? interpolation.position() : entity.position();
    }

    /** How many ticks ahead of what is known the target is when the arrow is made on the server. */
    private double latencyTicks() {
        double latency = latencyCompensation.get() ? PlayerUtils.getPing() / 50.0 : 0;
        return Math.max(0, latency + leadOffset.get() + (autoCalibrate.get() ? calibration : 0));
    }

    private void chooseTarget(double speed) {
        skipReasons.setLength(0);
        candidateCount = 0;
        lobBudget = LOBS_PER_TICK;

        List<Entity> candidates = new ArrayList<>();
        boolean switching = targetMode.get() == TargetMode.Switch;
        TargetUtils.getList(candidates, this::valid, priority.get(), switching ? switchLimit.get() : 5);
        candidateCount = candidates.size();

        if (switching) {
            // A fixed order (by id), starting after the one that was shot last, so every target gets its turn
            candidates.sort(java.util.Comparator.comparingInt(Entity::getId));

            int first = 0;
            while (first < candidates.size() && candidates.get(first).getId() <= lastShotTarget) first++;

            if (first > 0 && first < candidates.size()) java.util.Collections.rotate(candidates, -first);
        }

        // Players before the rest, each group keeps its order (a stable sort)
        if (playersFirst.get()) candidates.sort(java.util.Comparator.comparingInt(e -> e instanceof Player ? 0 : 1));

        if (candidates.isEmpty()) {
            skipReasons.append("no entity of the chosen kinds in range, in view and not a friend");
            return;
        }

        double needed = minHitChance.get() / 100.0;
        Entity bestEntity = null;
        Solution best = null;

        // Switch mode stays on its target until an arrow with a good enough chance was shot at it. Until then it keeps shooting
        // at the prediction it has, even if the chance is low, so the rapid fire is never interrupted. If the target cannot be
        // shot at at all, or after a long time, the turn goes on.
        if (switching && switchCurrent != -1 && !switchFired && tickCounter - switchSince <= SWITCH_PATIENCE_TICKS) {
            for (Entity candidate : candidates) {
                if (candidate.getId() != switchCurrent) continue;

                Solution solved = solve(candidate, speed, false);

                if (solved != null && !solved.impact.blocked) {
                    target = candidate;
                    solution = solved;
                    return;
                }

                break;
            }
        }

        for (Entity candidate : candidates) {
            Solution solved = solve(candidate, speed, false);

            // The direct shot is blocked or has no solution: try the high lob (only a few per tick, it costs more)
            if (highArc.get() && !fireworkPhysics && lobBudget > 0 && (solved == null || solved.impact.blocked)) {
                lobBudget--;
                String directFailure = solveFailure;
                Solution lob = solve(candidate, speed, true);

                if (lob != null && !lob.impact.blocked) {
                    solved = lob;
                } else {
                    solveFailure = directFailure;
                }
            }

            if (solved == null) {
                skipReasons.append(describe(candidate)).append(": ").append(solveFailure).append("; ");
                continue;
            }

            if (solved.impact.blocked) {
                skipReasons.append(describe(candidate)).append(": the arrow path is blocked at ").append(vec(solved.impact.point)).append("; ");
                continue;
            }

            // The first one that is good enough (in the order of the priority) is taken
            if (solved.hitChance >= needed) {
                target = candidate;
                solution = solved;
                if (switching) noteSwitchTarget(candidate);
                return;
            }

            if (best == null || solved.hitChance > best.hitChance) {
                best = solved;
                bestEntity = candidate;
            }
        }

        // None is good enough yet: keep looking at the best one and wait for its chance to go up
        target = bestEntity != null ? bestEntity : candidates.getFirst();
        solution = best;
        if (switching) noteSwitchTarget(target);
    }

    /** The switch mode keeps this target until an arrow was shot at it. */
    private void noteSwitchTarget(Entity chosen) {
        if (chosen.getId() == switchCurrent) return;

        switchCurrent = chosen.getId();
        switchFired = false;
        switchSince = tickCounter;
    }

    private boolean valid(Entity entity) {
        if (entity == null || entity == mc.player || entity == mc.getCameraEntity()) return false;
        if ((entity instanceof LivingEntity living && living.isDeadOrDying()) || !entity.isAlive()) return false;
        if (!entities.get().contains(entity.getType())) return false;

        double distance = Math.sqrt(PlayerUtils.squaredDistanceTo(entity));
        if (distance > range.get() || distance < minRange.get()) return false;

        if (visibleOnly.get() && !PlayerUtils.canSeeEntity(entity)) return false;

        if (entity instanceof Player player) {
            if (player.isCreative()) return false;
            if (!Friends.get().shouldAttack(player)) return false;
        }

        if (entity instanceof AgeableMob ageable && ageable.isBaby() && !attackBabies.get()) return false;
        if (entity instanceof NeutralMob && !attackNeutral.get()) return false;
        if (skipClosedShulkers.get() && entity instanceof Shulker shulker && shulker.getRawPeekAmount() == 0) return false;

        return !(ignoreInvisible.get() && entity.isInvisible());
    }

    // Ballistics

    private Vec3 basePoint(Entity entity) {
        Vec3 position = knownPosition(entity);

        return switch (aimPoint.get()) {
            case Head -> position.add(0, entity.getEyeHeight(), 0);
            case Feet -> position.add(0, 0.2, 0);
            case Body -> position.add(0, entity.getBbHeight() / 2, 0);
        };
    }

    /** The height of the point where the aim point would be when standing on whatever is below the target. */
    private double groundLevel(Entity entity, Vec3 base) {
        BlockHitResult hit = mc.level.clip(new ClipContext(base, base.subtract(0, 16, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) return base.y - 1000;

        return hit.getLocation().y + (base.y - knownPosition(entity).y);
    }

    /** Where the entity will be after this many ticks. */
    private Vec3 predicted(Entity entity, Vec3 base, Vec3 velocity, double ticks, double ground) {
        if (!predictMovement.get()) return base;

        double t = Math.min(ticks, 80);
        double x = base.x + velocity.x * t, z = base.z + velocity.z * t;

        // A target that turns (a phantom circling, a player running a curve) goes along an arc, not a straight line. The turn
        // slows down with time, because a turn does not go on for ever.
        double omega = predictTurns.get() ? turnRateOf(entity) : 0;

        if (Math.abs(omega) > 0.01 && Math.hypot(velocity.x, velocity.z) > 0.1) {
            double vx = velocity.x, vz = velocity.z, w = omega;
            x = base.x;
            z = base.z;
            int steps = (int) t;

            for (int i = 0; i < steps; i++) {
                x += vx;
                z += vz;

                double c = Math.cos(w), s = Math.sin(w);
                double nx = vx * c - vz * s;
                vz = vx * s + vz * c;
                vx = nx;
                w *= 0.96;
            }

            x += vx * (t - steps);
            z += vz * (t - steps);
        }

        // Running into a wall stops it, it does not walk through
        if (Math.hypot(x - base.x, z - base.z) > 0.6) {
            Vec3 end = new Vec3(x, base.y, z);
            BlockHitResult wall = mc.level.clip(new ClipContext(base, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));

            if (wall.getType() != HitResult.Type.MISS) {
                Vec3 back = end.subtract(base).normalize().scale(0.35);
                x = wall.getLocation().x - back.x;
                z = wall.getLocation().z - back.z;
            }
        }

        double y = base.y;

        if (isFlyer(entity)) {
            // Something that flies has no gravity, it keeps the climb or the dive it has
            y = Math.max(ground, base.y + velocity.y * t);
        } else if (!entity.onGround() && !entity.isInWater()) {
            // In the air it moves like a player: the speed changes by gravity and drag every tick, until it lands
            double vy = velocity.y;
            int steps = (int) t;

            for (int i = 0; i < steps; i++) {
                y += vy;
                vy = (vy - 0.08) * 0.98;

                if (y < ground) {
                    y = ground;
                    vy = 0;
                }
            }

            y = Math.max(ground, y + vy * (t - steps));
        }

        return new Vec3(x, y, z);
    }

    /** Whether the target flies by itself, so that gravity does not pull on it. */
    private boolean isFlyer(Entity entity) {
        if (entity.isNoGravity()) return true;

        switch (entity.getType().toShortString()) {
            case "phantom", "ghast", "happy_ghast", "blaze", "vex", "bat", "ender_dragon", "bee", "allay", "parrot", "wither" -> {
                return true;
            }
            default -> {
            }
        }

        if (entity instanceof Player player && (player.getAbilities().flying || player.isFallFlying())) return true;

        // Anything in the air whose height hardly changes does not fall
        if (!entity.onGround() && !entity.isInWater()) {
            ArrayDeque<Update> updates = history.get(entity.getId());

            if (updates != null && updates.size() >= 3) {
                Update[] u = updates.toArray(new Update[0]);
                int n = u.length;
                boolean flat = true;

                for (int i = n - 1; i >= n - 2; i--) {
                    double dt = u[i].tick - u[i - 1].tick;
                    if (dt <= 0 || Math.abs((u[i].position.y - u[i - 1].position.y) / dt) > 0.035) flat = false;
                }

                return flat;
            }
        }

        return false;
    }

    /** How fast the horizontal direction of the target turns, in radians per tick (positive is counter clockwise), or 0. */
    private double turnRateOf(Entity entity) {
        ArrayDeque<Update> updates = history.get(entity.getId());
        if (updates == null || updates.size() < 3) return 0;

        Update[] u = updates.toArray(new Update[0]);
        int n = u.length;

        double dta = u[n - 2].tick - u[n - 3].tick, dtb = u[n - 1].tick - u[n - 2].tick;
        if (dta <= 0 || dtb <= 0 || tickCounter - u[n - 1].tick > 6) return 0;

        Vec3 va = u[n - 2].position.subtract(u[n - 3].position).scale(1 / dta);
        Vec3 vb = u[n - 1].position.subtract(u[n - 2].position).scale(1 / dtb);

        if (Math.hypot(va.x, va.z) < 0.12 || Math.hypot(vb.x, vb.z) < 0.12) return 0;

        double change = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(vb.z, vb.x) - Math.atan2(va.z, va.x)));
        double omega = Math.toRadians(change) / ((dta + dtb) / 2.0);

        return Mth.clamp(omega, -0.3, 0.3);
    }

    /**
     * Works out the turn that makes the arrow land on the (predicted) target. The aim is moved by how far the simulated
     * arrow misses, a few times, until it lands where it should.
     */
    /** How far the player moved in the last tick, which is about how far it moves in this one. */
    private Vec3 ownStep() {
        if (!predictOwnStep.get()) return Vec3.ZERO;

        return mc.player.position().subtract(mc.player.xo, mc.player.yo, mc.player.zo);
    }

    /** The yaw towards a point, next to the one the player has now (so it does not turn the long way round). */
    private float aimYaw(Vec3 from, Vec3 to) {
        float current = mc.player.getYRot();
        return current + Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90f - current);
    }

    private float aimPitch(Vec3 from, Vec3 to) {
        float current = mc.player.getXRot();
        double horizontal = Math.hypot(to.x - from.x, to.z - from.z);
        return current + Mth.wrapDegrees((float) -Math.toDegrees(Math.atan2(to.y - from.y, horizontal)) - current);
    }

    /**
     * The pitch that makes an arrow land at the given height after the given horizontal distance. There are two: the flat
     * one and the lob. Null when there is none (out of reach) or when both are the same.
     */
    private Float pitchFor(Vec3 start, float yaw, double horizontal, double targetY, double speed, Vec3 own, boolean lob, int limit) {
        double previousPitch = 0, previousError = 0;
        boolean hasPrevious = false;
        double flat = Double.NaN, high = Double.NaN;

        // From looking down to looking up: the height at that distance goes up to the best angle and then down again
        for (double pitch = 85; pitch >= -89; pitch -= 2) {
            Impact impact = simulate(start, Vec3.directionFromRotation((float) pitch, yaw).scale(speed).add(own), horizontal, limit, false);

            if (impact == null) {
                hasPrevious = false;
                continue;
            }

            double error = impact.point.y - targetY;

            if (hasPrevious && (previousError < 0) != (error < 0)) {
                double root = refinePitch(start, yaw, horizontal, targetY, speed, own, previousPitch, pitch, limit);

                if (Double.isNaN(flat)) flat = root;
                high = root;
            }

            previousPitch = pitch;
            previousError = error;
            hasPrevious = true;
        }

        if (!lob) return Double.isNaN(flat) ? null : (float) flat;
        if (Double.isNaN(high) || Double.isNaN(flat) || flat - high < 3) return null;

        return (float) high;
    }

    private double refinePitch(Vec3 start, float yaw, double horizontal, double targetY, double speed, Vec3 own, double a, double b, int limit) {
        Impact first = simulate(start, Vec3.directionFromRotation((float) a, yaw).scale(speed).add(own), horizontal, limit, false);
        boolean firstBelow = first != null && first.point.y < targetY;

        for (int i = 0; i < 24; i++) {
            double middle = (a + b) / 2;
            Impact impact = simulate(start, Vec3.directionFromRotation((float) middle, yaw).scale(speed).add(own), horizontal, limit, false);

            if (impact == null) break;

            if ((impact.point.y < targetY) == firstBelow) a = middle;
            else b = middle;
        }

        return (a + b) / 2;
    }

    private Solution solve(Entity entity, double speed, boolean lob) {
        solveFailure = "";
        int limit = lob ? LOB_MAX_TICKS : fireworkPhysics ? Math.min(maxFlightTicks.get(), fireworkTicks) : maxFlightTicks.get();

        // The server makes the arrow where the player is after this tick's step, which is not yet done at this point of the
        // tick: the position is where the player was after the last step. Measured: the arrow starts one step further.
        Vec3 eyes = mc.player.getEyePosition().add(ownStep());
        // An arrow is made a little below the eyes
        Vec3 start = eyes.subtract(0, 0.1, 0);

        // A projectile does not take over the movement of the shooter (measured)
        Vec3 own = Vec3.ZERO;

        Vec3 velocity = velocityOf(entity);

        if (lob && Math.hypot(velocity.x, velocity.z) > LOB_MAX_TARGET_SPEED) {
            solveFailure = "the lob takes about five seconds, the target moves";
            return null;
        }

        Vec3 base = basePoint(entity);
        double ground = groundLevel(entity, base);

        double pingTicks = latencyCompensation.get() ? PlayerUtils.getPing() / 50.0 : 0;
        double offsetTicks = leadOffset.get();
        double calibrationTicks = autoCalibrate.get() ? calibration : 0;
        double latency = Math.max(0, pingTicks + offsetTicks + calibrationTicks);

        double ticks = start.distanceTo(base) / speed;
        Vec3 point = predicted(entity, base, velocity, latency + ticks, ground);
        Vec3 aimAt = point;

        float yaw = 0, pitch = 0;
        Impact impact = null;
        int iterations = 0;

        for (int i = 0; i < 8; i++) {
            iterations = i + 1;

            // Known position, plus the time until the arrow is made on the server, plus the time the arrow flies
            point = predicted(entity, base, velocity, latency + ticks, ground);

            double horizontal = Math.hypot(point.x - start.x, point.z - start.z);

            if (horizontal < 0.4) {
                solveFailure = "too close sideways (%.2f blocks), the arrow would have to go straight up or down".formatted(horizontal);
                return null;
            }

            yaw = aimYaw(start, aimAt);
            pitch = Mth.clamp(aimPitch(start, aimAt), -90, 90);

            if (lob) {
                Float lobPitch = pitchFor(start, yaw, horizontal, point.y, speed, own, true, limit);

                if (lobPitch == null) {
                    solveFailure = "there is no high lob that reaches %.1f blocks far".formatted(horizontal);
                    return null;
                }

                pitch = lobPitch;
            }

            Vec3 initial = Vec3.directionFromRotation(pitch, yaw).scale(speed).add(own);
            impact = simulate(start, initial, horizontal, limit, false);

            if (impact == null) {
                solveFailure = "the arrow does not get %.1f blocks far within %d ticks (too far, or aimed too steep)".formatted(horizontal, limit);
                return null;
            }

            double previousTicks = ticks;
            ticks = impact.ticks;

            if (lob) {
                // The pitch is exact for the point, only the point moves with the flight time
                if (Math.abs(previousTicks - ticks) < 0.02) break;
                continue;
            }

            Vec3 miss = point.subtract(impact.point);

            if (miss.length() < 0.05) break;

            aimAt = aimAt.add(miss);
        }

        if (lob) {
            // The last pass had the point of the last flight time
            point = predicted(entity, base, velocity, latency + ticks, ground);
            double horizontalNow = Math.hypot(point.x - start.x, point.z - start.z);
            Float lobPitch = pitchFor(start, yaw, horizontalNow, point.y, speed, own, true, limit);

            if (lobPitch == null) {
                solveFailure = "there is no high lob that reaches %.1f blocks far".formatted(horizontalNow);
                return null;
            }

            pitch = lobPitch;
            aimAt = point;
        }

        // One more time with the blocks, to know if the arrow gets there
        Vec3 initial = Vec3.directionFromRotation(pitch, yaw).scale(speed).add(own);
        double horizontal = Math.hypot(point.x - start.x, point.z - start.z);
        impact = simulate(start, initial, horizontal, limit, true);

        if (impact == null) {
            solveFailure = "the arrow does not reach the target within %d ticks".formatted(limit);
            return null;
        }

        double residual = point.subtract(impact.point).length();

        if (residual > accuracy.get()) {
            solveFailure = "the aim does not settle: the arrow still misses the predicted point by %.2f blocks (limit %.2f)".formatted(residual, accuracy.get());
            return null;
        }

        // The hitbox where the target is expected to be, for the render and the hit chance
        AABB box = entity.getBoundingBox().move(knownPosition(entity).subtract(entity.position())).move(point.subtract(base));

        double lead = latency + impact.ticks;
        double margin = predictionMargin(entity, velocity, lead);
        double chance = hitChance(start, own, yaw, pitch, speed, horizontal, box, margin, limit);

        Detail detail = new Detail(base, aimAt, point, start, own, initial, iterations, residual, pingTicks, offsetTicks, calibrationTicks, margin, speed, horizontal, ground);

        return new Solution(yaw, pitch, impact, box, velocity, lead, chance, detail);
    }

    /**
     * Flies an arrow until it is as far away (sideways) as the target. Gravity and drag are those of an arrow: it moves,
     * then it slows down by 1%, then it drops by 0.05.
     */
    private Impact simulate(Vec3 start, Vec3 initialVelocity, double horizontalDistance, int maxTicks, boolean checkBlocks) {
        Vec3 position = start;
        Vec3 velocity = initialVelocity;
        double previousDistance = 0;
        List<Vec3> path = checkBlocks ? new ArrayList<>() : null;
        if (path != null) path.add(start);

        for (int tick = 1; tick <= maxTicks; tick++) {
            Vec3 next = position.add(velocity);
            double distance = Math.hypot(next.x - start.x, next.z - start.z);

            if (distance >= horizontalDistance) {
                double fraction = distance == previousDistance ? 0 : Mth.clamp((horizontalDistance - previousDistance) / (distance - previousDistance), 0, 1);
                Vec3 point = position.lerp(next, fraction);

                boolean blocked = checkBlocks && hitsBlock(position, point);
                if (path != null) path.add(point);

                return new Impact(point, tick - 1 + fraction, blocked, path);
            }

            if (checkBlocks && hitsBlock(position, next)) {
                if (path != null) path.add(next);
                return new Impact(next, tick, true, path);
            }

            if (path != null) path.add(next);

            previousDistance = distance;
            position = next;
            if (!fireworkPhysics) velocity = velocity.scale(0.99).add(0, -0.05, 0);
        }

        return null;
    }

    private boolean hitsBlock(Vec3 from, Vec3 to) {
        return mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() != HitResult.Type.MISS;
    }

    /**
     * How likely the shot is to hit. The shot is flown many times with the random spread a crossbow adds, and counted how
     * often it lands inside the hitbox (made bigger by the size of the arrow). The hitbox is also moved by a random part
     * of the margin, how far off the prediction can be, so a target that is hard to predict has a lower chance.
     */
    private double hitChance(Vec3 start, Vec3 own, float yaw, float pitch, double speed, double horizontal, AABB box, double margin, int limit) {
        Vec3 direction = Vec3.directionFromRotation(pitch, yaw);
        AABB target = fireworkPhysics ? box.inflate(Math.max(0.3, FIREWORK_BLAST)) : box.inflate(0.3, 0.25, 0.3);
        int hits = 0;

        for (double[] spread : SPREAD) {
            Vec3 initial = direction.add(spread[0], spread[1], spread[2]).scale(speed).add(own);
            Impact impact = simulate(start, initial, horizontal, limit, false);

            if (impact == null) continue;

            AABB moved = target.move(spread[3] * margin, spread[4] * margin * 0.3, spread[5] * margin);
            if (moved.contains(impact.point)) hits++;
        }

        return hits / (double) SPREAD.length;
    }

    /** How far off the prediction can be: a target that changes its speed is harder to predict, and so is a long flight. */
    private double predictionMargin(Entity entity, Vec3 velocity, double ticks) {
        // Half a tick of uncertainty in the position (ping and packets do not arrive evenly), plus the speed changing
        double acceleration = acceleration(entity);
        return Math.min(2.0, 0.5 * acceleration * ticks * ticks + 0.5 * Math.hypot(velocity.x, velocity.z));
    }

    // Watching the arrows

    private void watchArrows() {
        // The shots whose arrow never showed up
        for (var iterator = pendingShots.iterator(); iterator.hasNext(); ) {
            Shot shot = iterator.next();

            if (tickCounter - shot.tick > 60) {
                iterator.remove();
                finishUnmatched(shot, "No arrow of ours showed up within 60 ticks of this shot.");
            }
        }

        // Arrows that were already there (stuck in blocks, flying from before) are never taken for the arrow of a shot
        java.util.Set<Integer> present = new java.util.HashSet<>();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Projectile arrow) || !(arrow instanceof AbstractArrow || arrow instanceof FireworkRocketEntity)) continue;

            if (arrow.getOwner() != mc.player) {
                if (!pendingShots.isEmpty() && otherArrows.add(arrow.getId())) {
                    event("ARROW", "arrow #%d ignored, its owner is %s (%s)".formatted(arrow.getId(), arrow.getOwner(), vec(arrow.position())));
                }
                continue;
            }

            present.add(arrow.getId());
            boolean fresh = knownArrows.add(arrow.getId());

            Track track = tracks.get(arrow.getId());

            if (track == null) {
                if (!fresh || pendingShots.isEmpty()) continue;

                // An arrow that does not fly the way the shot was aimed is not ours (shot by hand, or by another module)
                Solution sol = pendingShots.getFirst().solution;
                Vec3 flight = arrow.getDeltaMovement();
                if (flight.lengthSqr() < 1e-6 || Vec3.directionFromRotation(sol.pitch(), sol.yaw()).dot(flight.normalize()) < Math.cos(Math.toRadians(10))) {
                    event("ARROW", "own arrow #%d ignored, it flies %s but shot #%d was aimed yaw %.1f pitch %.1f".formatted(arrow.getId(), vec(flight), pendingShots.getFirst().index, sol.yaw(), sol.pitch()));
                    continue;
                }

                track = new Track(pendingShots.removeFirst(), tickCounter);
                tracks.put(arrow.getId(), track);
                track.arrowId = arrow.getId();

                try {
                    if (log() != null) track.spawn = describeSpawn(track, arrow);
                } catch (RuntimeException e) {
                    logProblem(e);
                }
            }

            Entity aimedAt = mc.level.getEntity(track.shot.targetId);
            track.arrow.add(arrow.position());
            track.target.add(aimedAt != null ? knownPosition(aimedAt).add(0, track.shot.solution.predictedBox.getYsize() / 2, 0) : null);
        }

        knownArrows.retainAll(present);

        for (var iterator = tracks.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            Track track = entry.getValue();
            Entity arrow = mc.level.getEntity(entry.getKey());

            // Finished when the arrow is gone (it hit something or left), or when it has flown for too long
            track.vanished = arrow == null || arrow.isRemoved();

            if (track.vanished || tickCounter - track.started > 150) {
                evaluateSafely(track, null);
                iterator.remove();
            }
        }
    }

    /** Compares where the arrow went with where the target was, learns from it, and writes it all to the log. */
    private void evaluate(Track track, String interruption) {
        Shot shot = track.shot;
        Solution sol = shot.solution;
        Detail d = sol.detail;
        int n = Math.min(track.arrow.size(), track.target.size());
        double width = sol.predictedBox.getXsize(), height = sol.predictedBox.getYsize();

        // The closest the arrow came to the target, tick by tick (both move in a straight line within a tick)
        double bestDistance = Double.MAX_VALUE;
        Vec3 bestError = null, bestArrow = null, bestTarget = null;
        int bestIndex = -1, hitIndex = -1;

        for (int i = 1; i < n; i++) {
            Vec3 a0 = track.arrow.get(i - 1), a1 = track.arrow.get(i), t0 = track.target.get(i - 1), t1 = track.target.get(i);
            if (t0 == null || t1 == null) continue;

            Vec3 d0 = a0.subtract(t0);
            Vec3 dv = a1.subtract(a0).subtract(t1.subtract(t0));
            double dd = dv.lengthSqr();
            double fraction = dd == 0 ? 0 : Mth.clamp(-d0.dot(dv) / dd, 0, 1);
            Vec3 error = d0.add(dv.scale(fraction));

            if (error.length() < bestDistance) {
                bestDistance = error.length();
                bestError = error;
                bestArrow = a0.add(a1.subtract(a0).scale(fraction));
                bestTarget = t0.add(t1.subtract(t0).scale(fraction));
                bestIndex = i;
            }

            // Went through the hitbox (made a little bigger for the size of the arrow) at that tick
            if (hitIndex < 0) {
                AABB box = new AABB(t1.x - width / 2, t1.y - height / 2, t1.z - width / 2, t1.x + width / 2, t1.y + height / 2, t1.z + width / 2).inflate(0.25);
                if (box.contains(a1) || box.clip(a0, a1).isPresent()) hitIndex = i;
            }
        }

        // The arrow is removed on the tick it hits, so the last position seen is still up to one flight step before the target
        boolean hitOnRemoval = false;

        if (hitIndex < 0 && track.vanished && n >= 2 && track.target.get(n - 1) != null) {
            Vec3 a1 = track.arrow.get(n - 1);
            Vec3 step = a1.subtract(track.arrow.get(n - 2));
            Vec3 next = a1.add(afterTick(step, shot.firework));
            Vec3 t = track.target.get(n - 1);
            AABB box = new AABB(t.x - width / 2, t.y - height / 2, t.z - width / 2, t.x + width / 2, t.y + height / 2, t.z + width / 2).inflate(0.25);

            if (box.contains(next) || box.clip(a1, next).isPresent()) {
                hitOnRemoval = true;
                hitIndex = n;
            }
        }

        // When the arrow vanished short of the target: what stopped it, and where it would have gone otherwise
        String stoppedBy = "";
        double extrapMiss = -1;
        boolean extrapHit = false;

        if (hitIndex < 0 && track.vanished && n >= 2 && track.target.get(n - 1) != null) {
            Vec3 a1 = track.arrow.get(n - 1);
            Vec3 step = a1.subtract(track.arrow.get(n - 2));
            Vec3 next = a1.add(afterTick(step, shot.firework));
            Vec3 t = track.target.get(n - 1);
            AABB targetBox = new AABB(t.x - width / 2, t.y - height / 2, t.z - width / 2, t.x + width / 2, t.y + height / 2, t.z + width / 2).inflate(0.25);

            if (mc.level.clip(new ClipContext(a1, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() != HitResult.Type.MISS) {
                stoppedBy = "a block at " + vec(a1);
            } else {
                for (Entity other : mc.level.entitiesForRendering()) {
                    if (other == mc.player || other.getId() == shot.targetId || !(other instanceof LivingEntity)) continue;

                    AABB otherBox = other.getBoundingBox().inflate(0.4);
                    if (otherBox.contains(next) || otherBox.clip(a1, next).isPresent()) {
                        stoppedBy = describe(other) + " at " + vec(other.position());
                        break;
                    }
                }
            }

            // Carry on flying without anything in the way and see how close it gets to the target
            Vec3 pos = a1, vel = step;
            extrapMiss = Double.MAX_VALUE;

            for (int i = 0; i < 80; i++) {
                Vec3 end = pos.add(vel);
                if (targetBox.contains(end) || targetBox.clip(pos, end).isPresent()) extrapHit = true;
                extrapMiss = Math.min(extrapMiss, end.distanceTo(t));
                pos = end;
                vel = afterTick(vel, shot.firework);
            }
        }

        double calibrationBefore = calibration;
        double along = 0, side = 0, up = 0, speed = 0;

        if (bestError != null) {
            Vec3 velocity = sol.velocity;
            speed = Math.hypot(velocity.x, velocity.z);
            up = bestError.y;

            // Split the miss into ahead/behind the target (along its movement) and sideways
            if (speed > 0.05) {
                double ux = velocity.x / speed, uz = velocity.z / speed;
                along = bestError.x * ux + bestError.z * uz;
                side = bestError.x * -uz + bestError.z * ux;
            } else {
                side = Math.hypot(bestError.x, bestError.z);
            }

            statShots++;
            if (hitIndex >= 0) statHits++;
            statMeasured++;
            statAlong += along;
            statAbsAlong += Math.abs(along);
            statSide += side;
            statAbsSide += Math.abs(side);
            statUp += up;
            statAbsUp += Math.abs(up);

            // The sideways part is mostly the random spread, the part along the movement is the lead being wrong. Only a
            // clear case of a moving target teaches something.
            if (autoCalibrate.get() && speed > 0.12 && bestDistance < 3 && Math.abs(side) < 1.2) {
                double ticksOff = Mth.clamp(along / speed, -4, 4);
                calibration = Mth.clamp(calibration - 0.3 * ticksOff, -6, 8);
            }
        }

        String verdict = interruption != null ? "INTERRUPTED" : bestError == null ? "NO_DATA" : hitOnRemoval ? "HIT_ON_REMOVAL" : hitIndex >= 0 ? "HIT_BOX" : !stoppedBy.isEmpty() && extrapHit ? "BLOCKED_WOULD_HIT" : "MISS";

        if (chatSummary.get() && bestError != null) {
            info("Shot #%d: %s, closest %.2f (%.2f %s the target, %.2f to the side, %.2f %s)", shot.index, verdict, bestDistance,
                Math.abs(along), along > 0 ? "ahead of" : "behind", Math.abs(side), Math.abs(up), up > 0 ? "above" : "below");
        }

        addFx(track, verdict, bestArrow, bestTarget);

        CrossbowRagebotLog l = log();
        if (l == null) return;

        StringBuilder b = new StringBuilder(shot.block);
        b.append(track.spawn);

        title(b, "RESULT");
        row(b, "verdict", verdict + (hitOnRemoval ? "   (the arrow vanished and its next step was inside the hitbox of the target: it hit)" : hitIndex >= 0 ? "   (the arrow went through the hitbox of the target at follow tick " + hitIndex + ")" : ""));

        if (interruption != null) row(b, "ended", interruption);
        if (!stoppedBy.isEmpty()) row(b, "arrow stopped by", stoppedBy);
        if (extrapMiss >= 0) row(b, "flight carried on", extrapHit ? "it would have gone through the hitbox of the target" : "it would have passed the target at " + f(extrapMiss) + " blocks");
        row(b, "arrow followed for", "%d ticks (first seen %d ticks after the shot)".formatted(n, track.started - shot.tick));

        if (bestError != null) {
            double delay = track.started - shot.tick;

            row(b, "closest approach", "%s blocks at follow tick %d (%d ticks after the shot)".formatted(f(bestDistance), bestIndex, (int) (bestIndex + delay)));
            row(b, "  arrow then", vec(bestArrow));
            row(b, "  target then", vec(bestTarget));
            row(b, "  arrow minus target", vec(bestError));
            row(b, "  ahead (+) / behind (-) the target", f(along) + (speed > 0.05 ? "   = " + f(along / speed) + " ticks of its movement" : "   (target is standing still)"));
            row(b, "  to the side (+ left, - right)", f(side));
            row(b, "  above (+) / below (-)", f(up));
            row(b, "prediction error", "predicted target point minus where the target really was: " + vec(d.predicted.subtract(bestTarget))
                + "  = " + f(d.predicted.subtract(bestTarget).length()));
            row(b, "arrow error", "arrow minus the predicted target point: " + vec(bestArrow.subtract(d.predicted))
                + "  = " + f(bestArrow.subtract(d.predicted).length()) + "   (mostly the random spread)");

            if (speed > 0.05) {
                row(b, "suggested lead change", f(-along / speed) + " ticks (positive = aim further ahead)");
            }
        }

        row(b, "auto calibration", "%s ticks before, %s ticks now".formatted(f(calibrationBefore), f(calibration)));
        b.append(THIN).append('\n');
        b.append(String.format("    %3s %5s %5s | %-32s | %-32s | %-26s | %s%n", "#", "tick", "+shot", "arrow", "target", "arrow minus target", "distance"));

        for (int i = 0; i < n && i < 160; i++) {
            Vec3 a = track.arrow.get(i), t = track.target.get(i);
            String marks = (i == bestIndex ? " <== closest" : "") + (i == hitIndex ? " <== in the hitbox" : "");

            b.append(String.format("    %3d %5d %5d | %-32s | %-32s | %-26s | %s%s%n", i, track.started + i, track.started + i - shot.tick,
                compact(a), compact(t), t == null ? "-" : compact(a.subtract(t)), t == null ? "-" : f(a.subtract(t).length()), marks));
        }

        b.append(THIN).append('\n');
        b.append("  running: ").append(summary().replace("\n", "\n           ").stripTrailing()).append('\n');
        b.append(BAR).append('\n');

        l.write(b.toString());

        Map<String, String> csv = shot.csv;
        csv.put("calibration_after", f(calibration));
        csv.put("verdict", verdict);
        csv.put("stopped_by", stoppedBy);
        csv.put("extrapolated_miss", extrapMiss >= 0 ? f(extrapMiss) : "");
        csv.put("arrow_ticks_alive", String.valueOf(n));

        if (bestError != null) {
            csv.put("closest", f(bestDistance));
            csv.put("miss_along", f(along));
            csv.put("miss_side", f(side));
            csv.put("miss_up", f(up));
            csv.put("closest_tick", String.valueOf(bestIndex));
        }

        l.row(csv);
    }

    /** The velocity of a projectile after one more tick: an arrow slows down and drops, a firework rocket flies on. */
    private static Vec3 afterTick(Vec3 velocity, boolean firework) {
        return firework ? velocity : new Vec3(velocity.x * 0.99, velocity.y * 0.99 - 0.05, velocity.z * 0.99);
    }

    private static String compact(Vec3 v) {
        return v == null ? "-" : String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", v.x, v.y, v.z);
    }



    // The log

    private static final String[] COLUMNS = {
        "shot", "time", "tick", "world", "target_type", "target_name", "target_id", "distance", "horizontal_distance",
        "ping_ms", "ping_ticks", "lead_offset", "calibration_before", "calibration_after", "latency_ticks", "flight_ticks", "lead_total",
        "hit_chance", "margin", "view_yaw", "view_pitch", "intended_yaw", "intended_pitch", "sent_yaw", "sent_pitch",
        "player_speed", "player_heading", "player_vy", "player_on_ground", "player_sprinting",
        "target_speed", "target_heading", "target_vy", "target_accel", "target_stale", "target_closing", "target_crossing",
        "predicted_x", "predicted_y", "predicted_z", "impact_x", "impact_y", "impact_z", "iterations", "residual",
        "arrow_seen_after_ticks", "arrow_yaw", "arrow_pitch", "arrow_speed", "spread_yaw_deg", "spread_pitch_deg", "spread_angle_deg",
        "noise_x", "noise_y", "noise_z", "noise_within_limit",
        "closest", "miss_along", "miss_side", "miss_up", "closest_tick", "verdict", "arrow_ticks_alive"
    };

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String BAR = "=".repeat(112), THIN = "-".repeat(112);
    /** The most the random spread of a shot can move one part of the direction. */
    private static final double MAX_NOISE = 0.0172275;

    private CrossbowRagebotLog log;
    private boolean logFailed;
    private int shotCounter;
    private float viewYaw, viewPitch;
    private String lastState = "";
    private final StringBuilder skipReasons = new StringBuilder();
    private String solveFailure = "";
    private int statShots, statMeasured, statHits;
    private double statAlong, statAbsAlong, statSide, statAbsSide, statUp, statAbsUp;

    /** The log, opened when it is first needed. Null when it is turned off. */
    private CrossbowRagebotLog log() {
        if (!debug.get() || logFailed) return null;

        if (log == null) {
            try {
                log = new CrossbowRagebotLog(MeteorClient.FOLDER, COLUMNS);
                writeHeader();
                info("Writing the log to (highlight)%s(default).", log.textFile.getAbsolutePath());
            } catch (IOException e) {
                logFailed = true;
                error("Could not open the log file: %s", e.getMessage());
            }
        }

        return log;
    }

    private void closeLog() {
        if (log != null) {
            // Shots whose arrow is still on the way
            for (Track track : tracks.values()) evaluateSafely(track, "INTERRUPTED (module turned off)");
            for (Shot shot : pendingShots) finishUnmatched(shot, "INTERRUPTED (module turned off)");

            log.write("\n" + BAR + "\nSESSION END   " + LocalDateTime.now() + "\n" + summary() + BAR + "\n");
            log.close();
            log = null;
        }

        logFailed = false;
    }

    private void writeHeader() {
        StringBuilder b = new StringBuilder();

        b.append('\n').append("#".repeat(112)).append('\n');
        b.append("# CROSSBOW RAGEBOT LOG   ").append(LocalDateTime.now()).append('\n');
        b.append("# Minecraft ").append(SharedConstants.getCurrentVersion().name())
            .append("   world: ").append(Utils.getWorldName())
            .append("   player: ").append(mc.getUser().getName()).append('\n');
        b.append("# ping ").append(PlayerUtils.getPing()).append(" ms   tps ").append(f(TickRate.INSTANCE.getTickRate())).append('\n');
        b.append("# Every angle is in degrees (yaw 0 = south/+Z, 90 = west/-X, 180 = north/-Z, -90 = east/+X, pitch negative = up).\n");
        b.append("# Positions are in blocks, speeds in blocks per tick (20 ticks = 1 second). \"along\" is ahead (+) or behind (-) the\n");
        b.append("# target in the direction it moves, \"side\" is to its left (+) or right (-), \"up\" is above (+) or below (-) it.\n");
        b.append("# Settings:\n");

        for (SettingGroup group : settings) {
            for (Setting<?> setting : group) b.append("#   ").append(setting.name).append(" = ").append(setting.get()).append('\n');
        }

        b.append("#".repeat(112)).append('\n');
        log.write(b.toString());
    }

    private void event(String kind, String text) {
        CrossbowRagebotLog l = log();
        if (l == null) return;

        l.write("[" + TIME.format(LocalTime.now()) + "] tick " + String.format("%-6d", tickCounter) + " " + String.format("%-8s", kind) + " " + text);
    }

    /** Writes a line when the situation changed, not every tick. */
    private void state(String key, String kind, String text) {
        if (key.equals(lastState)) return;

        lastState = key;
        event(kind, text);
    }

    private String describe(Entity entity) {
        return "%s#%d at %.1f blocks".formatted(EntityUtils.getName(entity), entity.getId(), Math.sqrt(PlayerUtils.squaredDistanceTo(entity)));
    }

    /** Says in the log why nothing is shot right now, when that changes. */
    private void logDecision(int loaded) {
        if (log() == null) return;

        String reasons = skipReasons.isEmpty() ? "" : "  [" + skipReasons.toString().strip() + "]";

        if (target == null) {
            state("none", "IDLE", "no target" + reasons);
        } else if (solution == null) {
            state("nosolution:" + target.getId(), "NO-AIM", describe(target) + " cannot be hit" + reasons);
        } else if (solution.hitChance < minHitChance.get() / 100.0) {
            state("wait:" + target.getId(), "WAIT", "%s hit chance %.0f%% is below the minimum %d%%, margin %.2f, lead %.1f ticks".formatted(
                describe(target), solution.hitChance * 100, minHitChance.get(), solution.detail.margin, solution.leadTicks));
        } else if (loaded < 0) {
            state("load:" + target.getId(), "LOAD", describe(target) + " can be hit (chance %.0f%%), waiting for a loaded crossbow".formatted(solution.hitChance * 100));
        } else {
            state("ready:" + target.getId(), "READY", describe(target) + " can be hit (chance %.0f%%), shooting".formatted(solution.hitChance * 100));
        }
    }

    /** One line per tick about the aim, when the trace is on. */
    private void logTrace() {
        if (!trace.get() || target == null || log() == null) return;

        StringBuilder b = new StringBuilder();
        Vec3 velocity = velocityOf(target);
        ArrayDeque<Update> updates = history.get(target.getId());
        int stale = updates == null || updates.isEmpty() ? -1 : tickCounter - updates.getLast().tick;

        b.append("target#").append(target.getId())
            .append(" screen ").append(vec(target.position()))
            .append(" server ").append(vec(serverPosition(target)))
            .append(" known ").append(vec(knownPosition(target)))
            .append(" stale ").append(stale)
            .append(" v ").append(vec(velocity));

        if (solution != null) {
            b.append(" | predicted ").append(vec(solution.detail.predicted))
                .append(" aim yaw ").append(f(Mth.wrapDegrees(solution.yaw))).append(" pitch ").append(f(solution.pitch))
                .append(" flight ").append(f1(solution.impact.ticks)).append(" lead ").append(f1(solution.leadTicks))
                .append(" chance ").append(f1(solution.hitChance * 100)).append('%');
        } else {
            b.append(" | no solution");
        }

        event("TRACE", b.toString());
    }

    // The text of a shot

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String f1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String vec(Vec3 v) {
        return v == null ? "-" : String.format(Locale.ROOT, "(%.4f, %.4f, %.4f)", v.x, v.y, v.z);
    }

    private static double yawOf(Vec3 v) {
        return Math.toDegrees(Math.atan2(-v.x, v.z));
    }

    private static double pitchOf(Vec3 v) {
        return -Math.toDegrees(Math.atan2(v.y, Math.hypot(v.x, v.z)));
    }

    private static String compass(double yaw) {
        String[] names = {"S(+Z)", "SW", "W(-X)", "NW", "N(-Z)", "NE", "E(+X)", "SE"};
        return names[Math.floorMod((int) Math.round(Mth.wrapDegrees(yaw) / 45.0), 8)];
    }

    private static String heading(Vec3 v) {
        if (Math.hypot(v.x, v.z) < 1e-3) return "none (not moving sideways)";

        double yaw = Mth.wrapDegrees(yawOf(v));
        return "%.1f deg  %s".formatted(yaw, compass(yaw));
    }

    private static void row(StringBuilder b, String label, String value) {
        b.append("    ").append(String.format("%-34s", label)).append(": ").append(value).append('\n');
    }

    private static void title(StringBuilder b, String text) {
        b.append("  [").append(text).append("]\n");
    }

    /** The speed the entity changes its horizontal movement with, in blocks per tick per tick. */
    private double acceleration(Entity entity) {
        ArrayDeque<Update> updates = history.get(entity.getId());
        if (updates == null || updates.size() < 3) return 0;

        Update[] u = updates.toArray(new Update[0]);
        int n = u.length;

        Vec3 newest = u[n - 1].position.subtract(u[n - 2].position).scale(1.0 / (u[n - 1].tick - u[n - 2].tick));
        Vec3 oldest = u[n - 2].position.subtract(u[n - 3].position).scale(1.0 / (u[n - 2].tick - u[n - 3].tick));

        return Math.hypot(newest.x - oldest.x, newest.z - oldest.z) / Math.max(1, (u[n - 1].tick - u[n - 3].tick) / 2.0);
    }

    /** Everything about the moment of the shot: the player, the target, the prediction and the angles. */
    private void describeFire(Shot shot, int slot, Entity entity, ItemStack crossbow) {
        Solution sol = shot.solution;
        Detail d = sol.detail;

        Vec3 position = mc.player.position(), eyes = d.start.add(0, 0.1, 0), motion = mc.player.getDeltaMovement();
        double horizontalSpeed = Math.hypot(motion.x, motion.z);
        int ping = PlayerUtils.getPing();

        Vec3 velocity = sol.velocity;
        double targetSpeed = Math.hypot(velocity.x, velocity.z);
        double accel = acceleration(entity);

        Vec3 toAim = d.base.subtract(eyes);
        double losHorizontal = Math.hypot(toAim.x, toAim.z);
        Vec3 los = losHorizontal < 1e-6 ? Vec3.ZERO : new Vec3(toAim.x / losHorizontal, 0, toAim.z / losHorizontal);
        double closing = velocity.x * los.x + velocity.z * los.z;
        double crossing = velocity.x * -los.z + velocity.z * los.x;
        double losYaw = Mth.wrapDegrees(yawOf(toAim)), losPitch = pitchOf(toAim);

        double intendedYaw = Mth.wrapDegrees(sol.yaw), intendedPitch = sol.pitch;
        double sentYaw = Mth.wrapDegrees(shot.sentYaw), sentPitch = shot.sentPitch;

        ArrayDeque<Update> updates = history.get(entity.getId());
        int stale = updates == null || updates.isEmpty() ? -1 : tickCounter - updates.getLast().tick;
        double distance = toAim.length();

        StringBuilder b = new StringBuilder();
        b.append('\n').append(BAR).append('\n');
        b.append("SHOT #").append(shot.index).append("   ").append(TIME.format(LocalTime.now())).append("   tick ").append(tickCounter)
            .append("   world ").append(Utils.getWorldName()).append('\n');
        b.append(THIN).append('\n');

        title(b, "PLAYER");
        row(b, "position", vec(position));
        row(b, "eyes (after this tick's step)", vec(eyes));
        row(b, "view at the start of the tick", "yaw %s  pitch %s".formatted(f(Mth.wrapDegrees(viewYaw)), f(viewPitch)));
        row(b, "motion (blocks per tick)", vec(motion));
        row(b, "horizontal speed", "%s per tick = %s per second".formatted(f(horizontalSpeed), f1(horizontalSpeed * 20)));
        row(b, "vertical speed", f(motion.y) + " per tick");
        row(b, "moving towards", heading(motion));
        row(b, "moving direction vs the view", horizontalSpeed < 1e-3 ? "-" : "%s deg (0 = forward, 90 = to the left, -90 = to the right, 180 = backwards)".formatted(f1(Mth.wrapDegrees(yawOf(motion) - viewYaw))));
        row(b, "flags", "onGround=%s sprinting=%s sneaking=%s swimming=%s inWater=%s fallDistance=%s".formatted(
            mc.player.onGround(), mc.player.isSprinting(), mc.player.isShiftKeyDown(), mc.player.isSwimming(), mc.player.isInWater(), f(mc.player.fallDistance)));
        row(b, "ping / server tps", "%d ms = %s ticks / %s".formatted(ping, f(ping / 50.0), f(TickRate.INSTANCE.getTickRate())));
        row(b, "crossbow", "slot %d  %s  holds %s".formatted(slot, crossbow.getHoverName().getString(), describeProjectiles(crossbow)));
        row(b, "arrow speed used", f(d.speed) + " blocks per tick");
        row(b, "own motion added to the arrow", vec(d.own));

        title(b, "TARGET");
        row(b, "entity", "%s  (%s)  id %d  health %s".formatted(EntityUtils.getName(entity), entity.getType().getDescriptionId(), entity.getId(),
            entity instanceof LivingEntity living ? f1(living.getHealth()) : "-"));
        row(b, "hitbox", "%s wide, %s high, eyes at %s".formatted(f(entity.getBbWidth()), f(entity.getBbHeight()), f(entity.getEyeHeight())));
        row(b, "position on the screen", vec(entity.position()) + "   (smoothed, a few ticks behind)");
        row(b, "position from the server", vec(serverPosition(entity)) + "   (the last update)");
        row(b, "position known now", vec(knownPosition(entity)) + "   (last update + velocity * " + stale + " stale ticks)");

        if (updates != null) {
            for (Update update : updates) row(b, "  update " + (update.tick - tickCounter) + " ticks", vec(update.position));
        }

        row(b, "velocity", "%s per tick = %s per second".formatted(vec(velocity), f1(targetSpeed * 20)));
        row(b, "moving towards", heading(velocity));
        row(b, "acceleration (horizontal)", f(accel) + " per tick per tick");
        row(b, "flags", "onGround=%s inWater=%s".formatted(entity.onGround(), entity.isInWater()));
        row(b, "distance eyes to aim point", "%s   (horizontal %s)".formatted(f(distance), f(losHorizontal)));
        row(b, "bearing to the aim point", "yaw %s  (%s deg from the view)".formatted(f(losYaw), f1(Mth.wrapDegrees(losYaw - viewYaw))));
        row(b, "closing speed", f(closing) + " per tick   (+ moving away from you, - coming closer)");
        row(b, "crossing speed", f(crossing) + " per tick   (across your line of sight)");

        title(b, "PREDICTION");
        row(b, "aim point mode", aimPoint.get().toString());
        row(b, "base point", vec(d.base) + "   (known position + height of the aim point)");
        row(b, "ground below the target", f(d.ground));
        row(b, "lead: ping", f(d.pingTicks) + " ticks");
        row(b, "lead: lead offset setting", f(d.offsetTicks) + " ticks");
        row(b, "lead: auto calibration", f(d.calibrationTicks) + " ticks");
        row(b, "lead: arrow flight (simulated)", f(sol.impact.ticks) + " ticks");
        row(b, "lead: total", f(sol.leadTicks) + " ticks");
        row(b, "predicted target point", vec(d.predicted));
        row(b, "  movement added", vec(d.predicted.subtract(d.base)));
        row(b, "aim point after the correction", vec(d.aimAt));
        row(b, "  gravity correction", vec(d.aimAt.subtract(d.predicted)));
        row(b, "solver", "%d iterations, the simulated arrow misses the predicted point by %s".formatted(d.iterations, f(d.residual)));
        row(b, "prediction margin", f(d.margin) + " blocks (how unsure the prediction is)");
        row(b, "hit chance", "%s%%   (48 simulated shots with the crossbow spread, minimum %d%%)".formatted(f1(sol.hitChance * 100), minHitChance.get()));
        row(b, "expected spread at this distance", "about %s blocks (one standard deviation, sideways and up/down)".formatted(f(distance * 0.0172275 / Math.sqrt(6))));

        title(b, "ANGLES");
        row(b, "line of sight to the base point", "yaw %s  pitch %s".formatted(f(losYaw), f(losPitch)));
        row(b, "SHOULD shoot (intended)", "yaw %s  pitch %s".formatted(f(intendedYaw), f(intendedPitch)));
        row(b, "  unwrapped yaw", f(sol.yaw));
        row(b, "  turn from the view", "yaw %s  pitch %s".formatted(f(Mth.wrapDegrees(intendedYaw - viewYaw)), f(intendedPitch - viewPitch)));
        row(b, "  lead (movement), yaw", f(Mth.wrapDegrees(intendedYaw - losYaw)));
        row(b, "  gravity, pitch above the line of sight", f(losPitch - intendedPitch));
        row(b, "DID shoot (sent just before)", "yaw %s  pitch %s".formatted(f(sentYaw), f(sentPitch)));
        row(b, "  sent minus intended", "yaw %s  pitch %s".formatted(f(Mth.wrapDegrees(sentYaw - intendedYaw)), f(sentPitch - intendedPitch)));
        row(b, "arrow start (below the eyes)", vec(d.start));
        row(b, "arrow initial velocity", "%s  = %s per tick".formatted(vec(d.initialVelocity), f(d.initialVelocity.length())));
        row(b, "predicted impact", "%s after %s ticks, blocked by a block: %s".formatted(vec(sol.impact.point), f(sol.impact.ticks), sol.impact.blocked));
        row(b, "  distance to the predicted target", f(d.predicted.subtract(sol.impact.point).length()));

        shot.block = b.toString();

        Map<String, String> csv = shot.csv;
        csv.put("shot", String.valueOf(shot.index));
        csv.put("time", LocalDateTime.now().toString());
        csv.put("tick", String.valueOf(tickCounter));
        csv.put("world", Utils.getWorldName());
        csv.put("target_type", entity.getType().getDescriptionId());
        csv.put("target_name", EntityUtils.getName(entity));
        csv.put("target_id", String.valueOf(entity.getId()));
        csv.put("distance", f(distance));
        csv.put("horizontal_distance", f(losHorizontal));
        csv.put("ping_ms", String.valueOf(ping));
        csv.put("ping_ticks", f(d.pingTicks));
        csv.put("lead_offset", f(d.offsetTicks));
        csv.put("calibration_before", f(d.calibrationTicks));
        csv.put("latency_ticks", f(d.pingTicks + d.offsetTicks + d.calibrationTicks));
        csv.put("flight_ticks", f(sol.impact.ticks));
        csv.put("lead_total", f(sol.leadTicks));
        csv.put("hit_chance", f(sol.hitChance));
        csv.put("margin", f(d.margin));
        csv.put("view_yaw", f(Mth.wrapDegrees(viewYaw)));
        csv.put("view_pitch", f(viewPitch));
        csv.put("intended_yaw", f(intendedYaw));
        csv.put("intended_pitch", f(intendedPitch));
        csv.put("sent_yaw", f(sentYaw));
        csv.put("sent_pitch", f(sentPitch));
        csv.put("player_speed", f(horizontalSpeed));
        csv.put("player_heading", horizontalSpeed < 1e-3 ? "" : f1(Mth.wrapDegrees(yawOf(motion))));
        csv.put("player_vy", f(motion.y));
        csv.put("player_on_ground", String.valueOf(mc.player.onGround()));
        csv.put("player_sprinting", String.valueOf(mc.player.isSprinting()));
        csv.put("target_speed", f(targetSpeed));
        csv.put("target_heading", targetSpeed < 1e-3 ? "" : f1(Mth.wrapDegrees(yawOf(velocity))));
        csv.put("target_vy", f(velocity.y));
        csv.put("target_accel", f(accel));
        csv.put("target_stale", String.valueOf(stale));
        csv.put("target_closing", f(closing));
        csv.put("target_crossing", f(crossing));
        csv.put("predicted_x", f(d.predicted.x));
        csv.put("predicted_y", f(d.predicted.y));
        csv.put("predicted_z", f(d.predicted.z));
        csv.put("impact_x", f(sol.impact.point.x));
        csv.put("impact_y", f(sol.impact.point.y));
        csv.put("impact_z", f(sol.impact.point.z));
        csv.put("iterations", String.valueOf(d.iterations));
        csv.put("residual", f(d.residual));

        if (chatSummary.get()) {
            info("Shot #%d at %s: %.1f blocks, chance %.0f%%, aim yaw %.2f pitch %.2f", shot.index, EntityUtils.getName(entity), distance, sol.hitChance * 100, intendedYaw, intendedPitch);
        }
    }

    private static String describeProjectiles(ItemStack crossbow) {
        ChargedProjectiles projectiles = crossbow.get(DataComponents.CHARGED_PROJECTILES);
        if (projectiles == null || projectiles.isEmpty()) return "nothing";

        StringBuilder b = new StringBuilder();
        for (ItemStack stack : projectiles.itemCopies()) {
            if (!b.isEmpty()) b.append(", ");
            b.append(stack.getHoverName().getString());
        }

        return b.toString();
    }

    /** What is known about the arrow when it first shows up, in particular the spread the server gave it. */
    private String describeSpawn(Track track, Projectile arrow) {
        Shot shot = track.shot;
        Solution sol = shot.solution;
        Detail d = sol.detail;

        // The arrow may have flown a tick already, take that back: it moves, slows down by 1%, then drops by 0.05
        Vec3 velocity = arrow.getDeltaMovement();
        if (!shot.firework) {
            for (int i = 0; i < arrow.tickCount; i++) velocity = velocity.add(0, 0.05, 0).scale(1 / 0.99);
        }

        // What the server made: (direction + random noise) * speed, plus the movement of the shooter
        Vec3 aimed = velocity.subtract(d.own);
        double power = aimed.length();
        Vec3 direction = power < 1e-6 ? Vec3.ZERO : aimed.scale(1 / power);
        Vec3 intended = Vec3.directionFromRotation(sol.pitch, sol.yaw);
        Vec3 noise = aimed.scale(1.0 / d.speed).subtract(intended);

        double actualYaw = Mth.wrapDegrees(yawOf(aimed)), actualPitch = pitchOf(aimed);
        double deltaYaw = Mth.wrapDegrees(actualYaw - Mth.wrapDegrees(sol.yaw));
        double deltaPitch = actualPitch - sol.pitch;
        double angle = Math.toDegrees(Math.acos(Mth.clamp(direction.dot(intended), -1, 1)));
        boolean within = Math.abs(noise.x) <= MAX_NOISE * 1.1 && Math.abs(noise.y) <= MAX_NOISE * 1.1 && Math.abs(noise.z) <= MAX_NOISE * 1.1;
        int delay = tickCounter - shot.tick;

        StringBuilder b = new StringBuilder();
        title(b, "ARROW SPAWN (the arrow showed up on the screen)");
        row(b, "shown after", "%d ticks   (ping says %s ticks, so about the time there and back)".formatted(delay, f(d.pingTicks)));
        row(b, "position", vec(arrow.position()) + "   (arrow start was worked out as " + vec(d.start) + ")");
        row(b, "velocity as received", vec(arrow.getDeltaMovement()) + "   (ticks flown already: " + arrow.tickCount + ")");
        row(b, "velocity at the start", vec(velocity));
        row(b, "minus the shooter's motion", vec(aimed) + "  = %s per tick".formatted(f(power)));
        row(b, "ACTUAL arrow direction", "yaw %s  pitch %s".formatted(f(actualYaw), f(actualPitch)));
        row(b, "  intended direction", "yaw %s  pitch %s".formatted(f(Mth.wrapDegrees(sol.yaw)), f(sol.pitch)));
        row(b, "  actual minus intended", "yaw %s  pitch %s  total angle %s deg".formatted(f(deltaYaw), f(deltaPitch), f(angle)));
        row(b, "random noise of the server", "x %s  y %s  z %s   (at most +-%s each)".formatted(f(noise.x), f(noise.y), f(noise.z), f(MAX_NOISE)));
        row(b, "noise looks like the crossbow spread", within
            ? "yes"
            : "NO: more than the spread can explain. The shooter's motion, the arrow speed or the aim was not what was assumed.");

        Map<String, String> csv = shot.csv;
        csv.put("arrow_seen_after_ticks", String.valueOf(delay));
        csv.put("arrow_yaw", f(actualYaw));
        csv.put("arrow_pitch", f(actualPitch));
        csv.put("arrow_speed", f(power));
        csv.put("spread_yaw_deg", f(deltaYaw));
        csv.put("spread_pitch_deg", f(deltaPitch));
        csv.put("spread_angle_deg", f(angle));
        csv.put("noise_x", f(noise.x));
        csv.put("noise_y", f(noise.y));
        csv.put("noise_z", f(noise.z));
        csv.put("noise_within_limit", String.valueOf(within));

        return b.toString();
    }

    /** Whatever goes wrong while writing the log must not stop the module: the log is turned off and that is said once. */
    private void logProblem(RuntimeException e) {
        MeteorClient.LOG.warn("The crossbow ragebot log failed", e);

        if (logFailed) return;

        logFailed = true;
        error("The debug log failed and was turned off: %s", e);
    }

    private void evaluateSafely(Track track, String interruption) {
        try {
            evaluate(track, interruption);
        } catch (RuntimeException e) {
            logProblem(e);
        }
    }

    private void finishUnmatched(Shot shot, String why) {
        CrossbowRagebotLog l = log();
        if (l == null) return;

        shot.csv.put("verdict", "NO_ARROW");
        l.write(shot.block + "\n  [RESULT]\n    " + why + "\n" + BAR + "\n");
        l.row(shot.csv);
    }

    private String summary() {
        if (statShots == 0) return "no arrow was followed to its end\n";

        StringBuilder b = new StringBuilder();
        b.append("arrows followed: ").append(statShots).append("   that went through the hitbox: ").append(statHits)
            .append(" (").append(f1(100.0 * statHits / statShots)).append("%)\n");

        if (statMeasured > 0) {
            b.append("average miss (all ").append(statMeasured).append(" measured arrows)   along ").append(f(statAlong / statMeasured))
                .append(" (absolute ").append(f(statAbsAlong / statMeasured)).append(")   side ").append(f(statSide / statMeasured))
                .append(" (absolute ").append(f(statAbsSide / statMeasured)).append(")   up ").append(f(statUp / statMeasured))
                .append(" (absolute ").append(f(statAbsUp / statMeasured)).append(")\n");
        }

        b.append("auto calibration is now ").append(f(calibration)).append(" ticks\n");
        return b.toString();
    }

    // Render

    private static final long FX_START = System.nanoTime();

    private final Color fx1 = new Color(), fx2 = new Color(), fx3 = new Color();
    private final List<FxTrail> trails = new ArrayList<>();
    private final List<FxMarker> markers = new ArrayList<>();

    /** The path of an arrow that is done, it fades away. */
    private record FxTrail(List<Vec3> points, double time, boolean hit) {
    }

    /** A flash where an arrow hit or missed. */
    private record FxMarker(Vec3 at, double time, boolean hit) {
    }

    private static double fxNow() {
        return (System.nanoTime() - FX_START) / 1e9;
    }

    /** Hue (only the fraction counts), saturation and value, written to out. */
    private static Color hsv(double hue, double saturation, double value, int alpha, Color out) {
        double h6 = (hue - Math.floor(hue)) * 6;
        int sector = (int) h6;
        double f = h6 - sector;
        double p = value * (1 - saturation), q = value * (1 - saturation * f), t = value * (1 - saturation * (1 - f));

        double red, green, blue;

        switch (sector) {
            case 0 -> { red = value; green = t; blue = p; }
            case 1 -> { red = q; green = value; blue = p; }
            case 2 -> { red = p; green = value; blue = t; }
            case 3 -> { red = p; green = q; blue = value; }
            case 4 -> { red = t; green = p; blue = value; }
            default -> { red = value; green = p; blue = q; }
        }

        return out.set((int) (red * 255), (int) (green * 255), (int) (blue * 255), Mth.clamp(alpha, 0, 255));
    }

    /** Red when the shot is hopeless, over yellow, to green when it is sure. */
    private static Color chanceColor(double chance, int alpha, Color out) {
        double c = Mth.clamp(chance, 0, 1);
        int red = c < 0.5 ? 255 : (int) (255 * (1 - (c - 0.5) * 2));
        int green = c < 0.5 ? (int) (255 * c * 2) : 255;
        return out.set(red, green, 70, Mth.clamp(alpha, 0, 255));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        double now = fxNow();
        double chance = solution != null ? solution.hitChance : 0;
        boolean shader = shaderFx.get() && CrossbowRageFx.isAvailable();

        if (shader) {
            try {
                renderShaderFx(event, now, chance);
            } catch (Throwable t) {
                fx.abort();
                CrossbowRageFx.markBroken();
                MeteorClient.LOG.warn("Drawing the Crossbow Ragebot shader effects failed, using lines.", t);
                shader = false;
            }
        }

        Renderer3D r = event.renderer;

        // Without the shader: lines
        if (!shader) {
            drawTrails(r, now);
            drawMarkers(r, now);
            drawFlights(r, now);

            if (target != null && !target.isRemoved() && solution != null) drawLockOn(r, now, chance);
        }

        if (!renderPath.get() || solution == null || solution.impact.path == null) return;

        List<Vec3> path = solution.impact.path;
        Color line = shader ? fxA : chanceColor(chance, 210, fx1);

        if (!shader || !fxRibbon.get()) {
            for (int i = 1; i < path.size(); i++) {
                Vec3 a = path.get(i - 1), b = path.get(i);
                r.line(a.x, a.y, a.z, b.x, b.y, b.z, line);
            }
        }

        if (renderPrediction.get()) {
            AABB box = solution.predictedBox;
            Color sides = shader ? fxSide.set(fxA.r, fxA.g, fxA.b, 50) : chanceColor(chance, 60, fx2);
            r.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, sides, line, ShapeMode.Both, 0);

            if (!shader) {
                drawBrackets(r, box.inflate(0.12), chanceColor(chance, 255, fx3));

                // A line from where the target is to where it will be
                if (target != null && !target.isRemoved()) {
                    AABB now3 = target.getBoundingBox();
                    chanceColor(chance, 40, fx2);
                    chanceColor(chance, 230, fx3);
                    r.line((now3.minX + now3.maxX) / 2, (now3.minY + now3.maxY) / 2, (now3.minZ + now3.maxZ) / 2,
                        (box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2, fx2, fx3);
                }
            }
        }

        Vec3 end = solution.impact.point;
        Color endSides = shader ? fxSide.set(fxA.r, fxA.g, fxA.b, 70) : chanceColor(chance, 80, fx2);
        r.box(end.x - 0.25, end.y - 0.25, end.z - 0.25, end.x + 0.25, end.y + 0.25, end.z + 0.25, endSides, line, ShapeMode.Both, 0);
    }

    // The effects with the shader

    private final CrossbowRageFx fx = new CrossbowRageFx();
    private final Color fxA = new Color(), fxB = new Color(), fxSide = new Color(), fxHitA = new Color(), fxHitB = new Color();
    private int lockId = -1;
    private double lockTime;

    public enum FxColorMode {
        Chance,
        Custom,
        Rainbow
    }

    /** The two colors of the effects for a hit chance. */
    private void fxColors(double chance, double now, Color outA, Color outB) {
        switch (fxColorMode.get()) {
            case Chance -> {
                chanceColor(chance, 255, outA);
                outB.set(outA.r + (255 - outA.r) * 55 / 100, outA.g + (255 - outA.g) * 55 / 100, outA.b + (255 - outA.b) * 55 / 100, 255);
            }
            case Custom -> {
                outA.set(fxPrimary.get());
                outB.set(fxSecondary.get());
                outA.a = outB.a = 255;
            }
            case Rainbow -> {
                hsv(now * 0.1 * fxSpeed.get(), 0.75, 1, 255, outA);
                hsv(now * 0.1 * fxSpeed.get() + 0.35, 0.6, 1, 255, outB);
            }
        }
    }

    private void renderShaderFx(Render3DEvent event, double now, double chance) {
        double scale = fxScale.get();
        Vec3 camera = new Vec3(event.offsetX, event.offsetY, event.offsetZ);

        fx.begin(camera, now, fxIntensity.get(), fxGlow.get(), fxSpeed.get());
        fxColors(chance, now, fxA, fxB);

        if (target != null && !target.isRemoved() && solution != null) {
            if (target.getId() != lockId) {
                lockId = target.getId();
                lockTime = now;
            }

            double lock = Mth.clamp((now - lockTime) / 0.6, 0, 1);
            Vec3 centre = target.getBoundingBox().getCenter();
            double ground = solution.detail.ground();

            if (fxReticle.get()) {
                double size = (Math.max(target.getBbWidth(), target.getBbHeight()) * 0.5 + 0.6) * scale;
                fx.billboard(centre, size, CrossbowRageFx.RETICLE, lock, chance, 0, fxA, fxB, 1);
            }

            if (fxGround.get()) {
                double size = Math.max(target.getBbWidth(), 0.7) * 2.4 * scale;
                fx.flat(new Vec3(centre.x, ground + 0.03, centre.z), size, CrossbowRageFx.RADAR, 0, chance, 0, fxA, fxB, 1);
            }

            if (fxBeam.get()) {
                fx.beam(new Vec3(centre.x, ground, centre.z), 0.45 * scale, 2.8 * scale, fxA, fxB, 1);
            }
        } else {
            lockId = -1;
        }

        if (fxRibbon.get()) {
            if (renderPath.get() && solution != null && solution.impact.path != null) {
                fx.ribbon(solution.impact.path, 0.06 * scale, 1, true, fxA, fxB, 1);

                // Where it lands
                fx.billboard(solution.impact.point, 0.5 * scale, CrossbowRageFx.BURST, 0.35, 0, 0, fxA, fxB, 0.7);
            }

            // The arrows on their way, all of them
            for (FxFlight flight : flights.values()) {
                if (flight.points.size() < 2) continue;

                fxColors(flight.chance, now, fxHitA, fxHitB);
                fx.ribbon(flight.points, 0.09 * scale, 1, false, fxHitA, fxHitB, 1);

                Vec3 head = flight.points.getLast();
                fx.billboard(head, 0.28 * scale, CrossbowRageFx.BURST, 0.25, 0, 0, fxHitB, fxHitA, 0.9);
            }
        }

        if (fxTrails.get()) {
            for (int i = trails.size() - 1; i >= 0; i--) {
                FxTrail trail = trails.get(i);
                double age = now - trail.time;

                if (age > 2.5) {
                    trails.remove(i);
                    continue;
                }

                if (fxColorMode.get() == FxColorMode.Chance) {
                    fxColors(trail.hit ? 1 : 0.05, now, fxHitA, fxHitB);
                } else {
                    fxHitA.set(fxA);
                    fxHitB.set(fxB);
                }

                fx.ribbon(trail.points, 0.05 * scale, 1 - age / 2.5, false, fxHitA, fxHitB, 1);
            }
        }

        // Bursts also go away by themselves when the shader is off
        for (int i = markers.size() - 1; i >= 0; i--) {
            FxMarker marker = markers.get(i);
            double age = now - marker.time;

            if (age > 1.0) {
                markers.remove(i);
                continue;
            }

            if (!fxBursts.get()) continue;

            if (fxColorMode.get() == FxColorMode.Chance) {
                fxColors(marker.hit ? 1 : 0.05, now, fxHitA, fxHitB);
            } else {
                fxHitA.set(fxA);
                fxHitB.set(fxB);
            }

            double size = (marker.hit ? 2.6 : 1.4) * scale;
            fx.billboard(marker.at, size, CrossbowRageFx.BURST, age, marker.hit ? 1 : 0, 0, fxHitA, fxHitB, 1);
        }

        fx.render(event.matrices, !fxThroughWalls.get());
    }

    /** A ring made of dashes, in the plane of u and v (unit vectors) round the centre. */
    private static void ring(Renderer3D r, double cx, double cy, double cz, double ux, double uy, double uz, double vx, double vy, double vz,
                             double radius, double phase, int segments, Color color) {
        for (int i = 0; i < segments; i++) {
            if ((i / 2) % 2 == 1) continue;

            double a0 = phase + i * Math.PI * 2 / segments, a1 = phase + (i + 1) * Math.PI * 2 / segments;
            double c0 = Math.cos(a0) * radius, s0 = Math.sin(a0) * radius, c1 = Math.cos(a1) * radius, s1 = Math.sin(a1) * radius;

            r.line(cx + ux * c0 + vx * s0, cy + uy * c0 + vy * s0, cz + uz * c0 + vz * s0,
                cx + ux * c1 + vx * s1, cy + uy * c1 + vy * s1, cz + uz * c1 + vz * s1, color);
        }
    }

    /** Three rings that turn round the target like a gyroscope, and breathe. */
    private void drawLockOn(Renderer3D r, double now, double chance) {
        AABB box = target.getBoundingBox();
        double cx = (box.minX + box.maxX) / 2, cy = (box.minY + box.maxY) / 2, cz = (box.minZ + box.maxZ) / 2;
        double radius = Math.max(target.getBbWidth(), target.getBbHeight()) * 0.5 + 0.45 + 0.07 * Math.sin(now * 6);
        Color color = chanceColor(chance, 235, fx1);

        double spin = now * 2.4;
        double turn = now * 1.1;

        ring(r, cx, cy, cz, 1, 0, 0, 0, 0, 1, radius, spin, 36, color);
        ring(r, cx, cy, cz, Math.cos(turn), 0, Math.sin(turn), 0, 1, 0, radius * 1.08, -spin * 0.8, 36, color);
        ring(r, cx, cy, cz, Math.cos(turn + Math.PI / 2), 0, Math.sin(turn + Math.PI / 2), 0, 1, 0, radius * 0.92, spin * 1.3, 36, color);
    }

    /** Corners at the eight corners of a box. */
    private void drawBrackets(Renderer3D r, AABB box, Color color) {
        double lx = Math.min(0.3, (box.maxX - box.minX) * 0.3), ly = Math.min(0.3, (box.maxY - box.minY) * 0.3), lz = Math.min(0.3, (box.maxZ - box.minZ) * 0.3);

        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    double x = xi == 0 ? box.minX : box.maxX, y = yi == 0 ? box.minY : box.maxY, z = zi == 0 ? box.minZ : box.maxZ;
                    double dx = xi == 0 ? lx : -lx, dy = yi == 0 ? ly : -ly, dz = zi == 0 ? lz : -lz;

                    r.line(x, y, z, x + dx, y, z, color);
                    r.line(x, y, z, x, y + dy, z, color);
                    r.line(x, y, z, x, y, z + dz, color);
                }
            }
        }
    }

    /** Rings on the ground under the target, a wave that spreads from it and a beam of light up. */
    private void drawGroundRing(Renderer3D r, double now, double chance) {
        AABB box = target.getBoundingBox();
        double cx = (box.minX + box.maxX) / 2, cz = (box.minZ + box.maxZ) / 2;
        double y = solution.detail.ground() + 0.03;
        double size = Math.max(target.getBbWidth(), 0.6);

        Color color = chanceColor(chance, 220, fx1);
        ring(r, cx, y, cz, 1, 0, 0, 0, 0, 1, size * 1.1, now * 1.8, 40, color);
        ring(r, cx, y, cz, 1, 0, 0, 0, 0, 1, size * 1.5, -now * 1.2, 48, color);

        // The wave
        double wave = (now * 0.8) % 1.0;
        chanceColor(chance, (int) (200 * (1 - wave)), fx2);
        ring(r, cx, y, cz, 1, 0, 0, 0, 0, 1, size * (0.5 + wave * 2.5), 0, 64, fx2);

        // The beam: two crossed strips that fade out upwards
        double h = 2.2 + 0.3 * Math.sin(now * 3);
        chanceColor(chance, 70, fx2);
        chanceColor(chance, 0, fx3);
        double w = size * 0.55;
        r.quad(cx - w, y, cz, cx - w, y + h, cz, cx + w, y + h, cz, cx + w, y, cz, fx2, fx3, fx3, fx2);
        r.quad(cx, y, cz - w, cx, y + h, cz - w, cx, y + h, cz + w, cx, y, cz + w, fx2, fx3, fx3, fx2);
    }

    /** The arrows that are on their way, with a bright head. */
    private void drawFlights(Renderer3D r, double now) {
        for (FxFlight flight : flights.values()) {
            List<Vec3> points = flight.points;
            if (points.size() < 2) continue;

            chanceColor(flight.chance, 210, fx1);

            for (int i = 1; i < points.size(); i++) {
                Vec3 a = points.get(i - 1), b = points.get(i);
                r.line(a.x, a.y, a.z, b.x, b.y, b.z, fx1);
            }

            Vec3 head = points.get(points.size() - 1);
            double size = 0.12 + 0.03 * Math.sin(now * 20);
            fx2.set(255, 255, 255, 240);
            r.box(head.x - size, head.y - size, head.z - size, head.x + size, head.y + size, head.z + size, fx2, fx2, ShapeMode.Both, 0);
        }
    }

    /** The paths of arrows that are done, they fade away in a couple of seconds. */
    private void drawTrails(Renderer3D r, double now) {
        for (int i = trails.size() - 1; i >= 0; i--) {
            FxTrail trail = trails.get(i);
            double age = now - trail.time;

            if (age > 2.5) {
                trails.remove(i);
                continue;
            }

            int alpha = (int) (200 * (1 - age / 2.5));
            if (trail.hit) fx1.set(90, 255, 140, alpha);
            else fx1.set(255, 120, 90, alpha);

            for (int p = 1; p < trail.points.size(); p++) {
                Vec3 a = trail.points.get(p - 1), b = trail.points.get(p);
                r.line(a.x, a.y, a.z, b.x, b.y, b.z, fx1);
            }
        }
    }

    /** A ring that spreads from where the arrow hit (with sparks) or missed. */
    private void drawMarkers(Renderer3D r, double now) {
        for (int i = markers.size() - 1; i >= 0; i--) {
            FxMarker marker = markers.get(i);
            double age = now - marker.time;

            if (age > 1.0) {
                markers.remove(i);
                continue;
            }

            double t = Anim.easeOutCubic(age);
            double radius = (marker.hit ? 0.3 + t * 2.2 : 0.2 + t * 1.0);
            int alpha = (int) (255 * (1 - age));

            if (marker.hit) fx1.set(90, 255, 140, alpha);
            else fx1.set(255, 110, 90, alpha);

            Vec3 p = marker.at;
            ring(r, p.x, p.y, p.z, 1, 0, 0, 0, 0, 1, radius, age * 4, 40, fx1);
            ring(r, p.x, p.y, p.z, 1, 0, 0, 0, 1, 0, radius * 0.8, 0, 32, fx1);
            ring(r, p.x, p.y, p.z, 0, 0, 1, 0, 1, 0, radius * 0.8, 0, 32, fx1);

            if (marker.hit) {
                // Sparks
                for (int s = 0; s < 12; s++) {
                    double a = s * Math.PI * 2 / 12 + 0.4, b = (s % 3 - 1) * 0.5;
                    double x = Math.cos(a) * Math.cos(b), y = Math.sin(b), z = Math.sin(a) * Math.cos(b);
                    r.line(p.x + x * radius * 0.6, p.y + y * radius * 0.6, p.z + z * radius * 0.6, p.x + x * radius * 1.3, p.y + y * radius * 1.3, p.z + z * radius * 1.3, fx1);
                }
            }
        }
    }

    /** Called when an arrow is done: keeps its path and puts a flash where it ended. */
    /** The path of one of our arrows or rockets that is in the air, for the effects. */
    private static final class FxFlight {
        final List<Vec3> points = new ArrayList<>();
        double chance;
        boolean firework;
    }

    private final Map<Integer, FxFlight> flights = new HashMap<>();
    /** Arrows that were found to have hit, by id. */
    private final Set<Integer> hitIds = new HashSet<>();
    private double lastShotChance = 0.5;

    /** Follows every projectile of ours that flies, also those no shot was matched to, and keeps the path when it is gone. */
    private void updateFlights() {
        if (mc.level == null || mc.player == null) return;

        Set<Integer> present = new HashSet<>();
        double now = fxNow();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Projectile projectile) || !(projectile instanceof AbstractArrow || projectile instanceof FireworkRocketEntity)) continue;
            if (projectile.getOwner() != mc.player) continue;

            // An arrow that sticks in a block does not move
            if (projectile.getDeltaMovement().lengthSqr() < 0.01) continue;

            present.add(projectile.getId());
            FxFlight flight = flights.get(projectile.getId());

            if (flight == null) {
                flight = new FxFlight();
                Track track = tracks.get(projectile.getId());
                flight.chance = track != null ? track.shot.solution.hitChance : lastShotChance;
                flight.firework = projectile instanceof FireworkRocketEntity;
                flights.put(projectile.getId(), flight);
            }

            Vec3 position = projectile.position();
            if (flight.points.isEmpty() || flight.points.getLast().distanceToSqr(position) > 1e-4) flight.points.add(position);
        }

        // Gone (it hit something or stuck): the path stays for a moment
        for (var iterator = flights.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (present.contains(entry.getKey())) continue;

            FxFlight flight = entry.getValue();
            iterator.remove();

            if (flight.points.size() >= 2) {
                trails.add(new FxTrail(flight.points, now, hitIds.remove(entry.getKey())));
                while (trails.size() > 80) trails.removeFirst();
            } else {
                hitIds.remove(entry.getKey());
            }
        }
    }

    private void addFx(Track track, String verdict, Vec3 arrowEnd, Vec3 targetThen) {
        if (track.arrow.size() < 2) return;

        boolean hit = verdict.startsWith("HIT");
        double now = fxNow();

        if (hit && track.arrowId >= 0) hitIds.add(track.arrowId);

        {
            Vec3 at = hit && targetThen != null ? targetThen : arrowEnd != null ? arrowEnd : track.arrow.getLast();
            markers.add(new FxMarker(at, now, hit));
            while (markers.size() > 40) markers.removeFirst();
        }
    }

    /**
     * The turn the use packet has to carry, when this module has an aim for the crossbow that is about to be used, or null.
     * The packet takes the rotation of the player at the moment it is made, so a click that does not come from this module
     * (the game for a held key, Bow Spam) would otherwise shoot wherever the player looks.
     */
    public float[] aimForUse(Player player, InteractionHand hand) {
        if (!isActive() || player != mc.player || hand != hand() || solution == null || target == null) return null;
        if (!firing && !(player.getItemInHand(hand).getItem() instanceof CrossbowItem && CrossbowItem.isCharged(player.getItemInHand(hand)))) return null;

        return new float[] {solution.yaw, solution.pitch};
    }

    /** The entity that is shot at, or null. */
    public Entity getTarget() {
        return target;
    }

    public enum Phase {
        Shooting, Charging, Loaded, Waiting, Searching, Idle
    }

    /** What the module is doing, for the Dynamic Island. */
    public Phase getPhase() {
        if (mc.player == null) return Phase.Idle;
        if (sinceShot <= 3 && shotCounter > 0) return Phase.Shooting;
        if (chargeProgress() >= 0) return Phase.Charging;
        if (target != null && solution != null && !shotWorthTaking()) return Phase.Waiting;
        if (loadedCount() > 0) return Phase.Loaded;
        return target != null ? Phase.Searching : Phase.Idle;
    }

    /** How far the crossbow in use is charged, from 0 to 1, or -1 when none is. */
    public double chargeProgress() {
        if (mc.player == null || !mc.player.isUsingItem()) return -1;

        ItemStack stack = mc.player.getUseItem();
        if (!(stack.getItem() instanceof CrossbowItem)) return -1;

        return Mth.clamp(mc.player.getTicksUsingItem() / (double) Math.max(1, CrossbowItem.getChargeDuration(stack, mc.player)), 0, 1);
    }

    /** The chance that the shot at the target hits, from 0 to 1, or -1. */
    public double getHitChance() {
        return solution != null ? solution.hitChance : -1;
    }

    public int getShotCount() {
        return shotCounter;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public boolean isSwitching() {
        return targetMode.get() == TargetMode.Switch;
    }

    /** Arrows that went through the hitbox of the target, of those followed to the end. */
    public int getHitCount() {
        return statHits;
    }

    public int getFollowedCount() {
        return statShots;
    }

    public int getArrowAmount() {
        return mc.player != null ? arrowCount() : 0;
    }

    /** Crossbows that are loaded, in the hotbar and the inventory. */
    public int loadedCount() {
        if (mc.player == null) return 0;

        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) count++;
        }

        if (offhandMode.get() && mc.player.getOffhandItem().getItem() instanceof CrossbowItem && CrossbowItem.isCharged(mc.player.getOffhandItem())) count++;

        return count;
    }

    /** Durability left of the crossbow in use (the held one, else the first in the hotbar), in percent, or -1. */
    public double getDurabilityPercent() {
        if (mc.player == null) return -1;

        ItemStack stack = handStack();

        if (!(stack.getItem() instanceof CrossbowItem) && !offhandMode.get()) {
            stack = ItemStack.EMPTY;

            for (int slot = 0; slot < 9; slot++) {
                ItemStack candidate = mc.player.getInventory().getItem(slot);

                if (candidate.getItem() instanceof CrossbowItem) {
                    stack = candidate;
                    break;
                }
            }
        }

        if (stack.isEmpty() || !stack.isDamageableItem() || stack.getMaxDamage() <= 0) return -1;
        return 100.0 * (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage();
    }

    @Override
    public String getInfoString() {
        if (target == null) return null;
        return solution != null ? "%s %.0f%%".formatted(EntityUtils.getName(target), solution.hitChance * 100) : EntityUtils.getName(target);
    }
}
