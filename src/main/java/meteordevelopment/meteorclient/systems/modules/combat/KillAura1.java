/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerManager;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A second Kill Aura. It scores the targets instead of only sorting them, keeps hitting the same target until it is
 * gone, can turn smoothly and only hits when the line of sight really crosses the target, and waits for the attack
 * cooldown (and, if wanted, for a critical hit).
 */
public class KillAura1 extends Module {
    public enum TargetMode {
        /** One target at a time. */
        Single,
        /** Every hit goes to the next target in range. */
        Switch,
        /** Several targets are hit at once. */
        Multi
    }

    public enum Priority {
        Distance,
        Health,
        Angle,
        /** A mix of distance, health and how far the player has to turn. */
        Smart
    }

    public enum Aim {
        /** Never turns. */
        None,
        /** Turns for the hit only. */
        OnHit,
        /** Turns instantly and keeps looking at the target. */
        Always,
        /** Turns at a limited speed, and hits when the line of sight is on the target. */
        Smooth
    }

    public enum AimPoint {
        /** The nearest point of the hitbox, the least turning. */
        Closest,
        Eyes,
        Body,
        Feet
    }

    public enum Crits {
        Off,
        /** Waits a few ticks for a critical hit when the player is in the air. */
        Prefer,
        /** Only hits when it is a critical hit. */
        Only
    }

    public enum AttackItems {
        Weapons,
        All
    }

    private static final Set<Item> WEAPON_FILTER = Set.of(
        Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.DIAMOND_PICKAXE,
        Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE, Items.MACE,
        Items.DIAMOND_SPEAR, Items.TRIDENT
    );

    private static final int HIT_ROTATION_PRIORITY = -100;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgAim = settings.createGroup("Aim");
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // General

    private final Setting<TargetMode> targetMode = sgGeneral.add(new EnumSetting.Builder<TargetMode>()
        .name("target-mode")
        .description("Single hits one target, Switch hits the next target every time and Multi hits several at once.")
        .defaultValue(TargetMode.Single)
        .build()
    );

    private final Setting<Integer> maxTargets = sgGeneral.add(new IntSetting.Builder()
        .name("max-targets")
        .description("How many targets Switch and Multi can use.")
        .defaultValue(3)
        .range(1, 10)
        .sliderRange(1, 6)
        .visible(() -> targetMode.get() != TargetMode.Single)
        .build()
    );

    private final Setting<AttackItems> attackWhenHolding = sgGeneral.add(new EnumSetting.Builder<AttackItems>()
        .name("attack-when-holding")
        .description("Only attacks when holding the selected kind of item.")
        .defaultValue(AttackItems.Weapons)
        .build()
    );

    private final Setting<List<Item>> weapons = sgGeneral.add(new ItemListSetting.Builder()
        .name("selected-weapon-types")
        .description("The weapon types that are allowed to attack.")
        .defaultValue(Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.TRIDENT)
        .filter(WEAPON_FILTER::contains)
        .visible(() -> attackWhenHolding.get() == AttackItems.Weapons)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switches to the best allowed weapon in your hotbar (sword, spear, axe, mace, trident, then the rest).")
        .defaultValue(false)
        .build()
    );

    // Targeting

    private final Setting<Set<EntityType<?>>> entities = sgTargeting.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("The entities that can be attacked.")
        .onlyAttackable()
        .defaultValue(EntityTypes.PLAYER)
        .build()
    );

    private final Setting<Priority> priority = sgTargeting.add(new EnumSetting.Builder<Priority>()
        .name("priority")
        .description("How the best target is chosen.")
        .defaultValue(Priority.Smart)
        .build()
    );

    private final Setting<Boolean> sticky = sgTargeting.add(new BoolSetting.Builder()
        .name("sticky")
        .description("Keeps the current target until it is dead or out of reach, instead of switching to a better one.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> range = sgTargeting.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far away the hitbox of a target can be, measured from your eyes.")
        .defaultValue(3.0)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> wallsRange = sgTargeting.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("How far away targets can be when they are behind blocks.")
        .defaultValue(2.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> fov = sgTargeting.add(new DoubleSetting.Builder()
        .name("fov")
        .description("Only targets inside this angle in front of you can be chosen. 360 is all around.")
        .defaultValue(360)
        .range(30, 360)
        .sliderRange(30, 360)
        .build()
    );

    private final Setting<Boolean> attackBabies = sgTargeting.add(new BoolSetting.Builder()
        .name("attack-babies")
        .description("Whether to attack baby variants of entities.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> attackNeutral = sgTargeting.add(new BoolSetting.Builder()
        .name("attack-neutral")
        .description("Whether to attack neutral mobs.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreTamed = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-tamed")
        .description("Does not attack your own pets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreTeammates = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-teammates")
        .description("Does not attack players that are in your scoreboard team.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreInvisible = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-invisible")
        .description("Does not attack invisible entities.")
        .defaultValue(false)
        .build()
    );

    // Aim

    private final Setting<Aim> aim = sgAim.add(new EnumSetting.Builder<Aim>()
        .name("rotate")
        .description("When and how to turn towards the target.")
        .defaultValue(Aim.Smooth)
        .build()
    );

    private final Setting<Double> smoothSpeed = sgAim.add(new DoubleSetting.Builder()
        .name("smooth-speed")
        .description("The most the aim turns in one tick, in degrees.")
        .defaultValue(50)
        .range(5, 180)
        .sliderRange(10, 120)
        .visible(() -> aim.get() == Aim.Smooth)
        .build()
    );

    private final Setting<AimPoint> aimPoint = sgAim.add(new EnumSetting.Builder<AimPoint>()
        .name("aim-point")
        .description("Which part of the target to aim at.")
        .defaultValue(AimPoint.Closest)
        .visible(() -> aim.get() != Aim.None)
        .build()
    );

    private final Setting<Double> predict = sgAim.add(new DoubleSetting.Builder()
        .name("predict")
        .description("Aims ahead of a moving target, in ticks of its movement.")
        .defaultValue(0)
        .range(0, 3)
        .sliderRange(0, 3)
        .visible(() -> aim.get() != Aim.None)
        .build()
    );

    private final Setting<Boolean> requireHit = sgAim.add(new BoolSetting.Builder()
        .name("require-hit")
        .description("Only hits when a line from your eyes in the direction you aim really crosses the target within range.")
        .defaultValue(true)
        .visible(() -> aim.get() != Aim.None)
        .build()
    );

    // Timing

    private final Setting<Double> cooldown = sgTiming.add(new DoubleSetting.Builder()
        .name("cooldown")
        .description("How much of the attack cooldown has to be done before hitting. 1 is a full strength hit.")
        .defaultValue(1)
        .range(0.1, 1)
        .sliderRange(0.5, 1)
        .build()
    );

    private final Setting<Boolean> tpsSync = sgTiming.add(new BoolSetting.Builder()
        .name("tps-sync")
        .description("Waits longer between hits when the server runs slower than 20 TPS.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> randomDelay = sgTiming.add(new BoolSetting.Builder()
        .name("random-delay")
        .description("Adds a small random wait after the cooldown is done.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> randomDelayMin = sgTiming.add(new IntSetting.Builder()
        .name("random-delay-min")
        .description("The shortest extra wait, in milliseconds.")
        .defaultValue(0)
        .min(0)
        .sliderMax(200)
        .visible(randomDelay::get)
        .build()
    );

    private final Setting<Integer> randomDelayMax = sgTiming.add(new IntSetting.Builder()
        .name("random-delay-max")
        .description("The longest extra wait, in milliseconds.")
        .defaultValue(60)
        .min(0)
        .sliderMax(200)
        .visible(randomDelay::get)
        .build()
    );

    private final Setting<Crits> crits = sgTiming.add(new EnumSetting.Builder<Crits>()
        .name("critical-hits")
        .description("Off ignores them, Prefer waits a moment for one while you are in the air, Only never hits without one.")
        .defaultValue(Crits.Off)
        .build()
    );

    private final Setting<Integer> critWait = sgTiming.add(new IntSetting.Builder()
        .name("critical-wait-ticks")
        .description("How many ticks Prefer waits for a critical hit before it hits anyway.")
        .defaultValue(10)
        .range(1, 30)
        .sliderRange(1, 20)
        .visible(() -> crits.get() == Crits.Prefer)
        .build()
    );

    // Safety

    private final Setting<Boolean> pauseOnEat = sgSafety.add(new BoolSetting.Builder()
        .name("pause-on-use")
        .description("Does not attack while you are using an item, like eating.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnMine = sgSafety.add(new BoolSetting.Builder()
        .name("pause-on-mine")
        .description("Does not attack while you are breaking a block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseInScreens = sgSafety.add(new BoolSetting.Builder()
        .name("pause-in-screens")
        .description("Does not attack while a screen is open.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pausePathing = sgSafety.add(new BoolSetting.Builder()
        .name("pause-pathing")
        .description("Pauses Baritone while attacking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableOnDeath = sgSafety.add(new BoolSetting.Builder()
        .name("disable-on-death")
        .description("Turns the module off when you die.")
        .defaultValue(false)
        .build()
    );

    // State

    private final List<Entity> targets = new ArrayList<>();
    private Entity lastTarget;
    private int switchIndex;
    private float ticksSinceHit;
    private int critTicks;
    private long randomDelayEndNanos;
    private boolean randomDelayWaiting;
    private boolean wasPathing;
    private boolean smoothValid;
    private float smoothYaw, smoothPitch;

    /** True while there is something to attack. Other modules (like highway builder) wait for it. */
    public boolean attacking;

    public KillAura1() {
        super(Categories.Combat, "killaura1", "A second Kill Aura: scored targets, smooth aim that only hits when it really crosses the target, cooldown and critical timing.");
    }

    @Override
    public void onActivate() {
        attacking = false;
        targets.clear();
        lastTarget = null;
        switchIndex = 0;
        // The first hit does not have to wait, the cooldown has been running all along
        ticksSinceHit = 100;
        critTicks = 0;
        randomDelayWaiting = false;
        wasPathing = false;
        smoothValid = false;
    }

    @Override
    public void onDeactivate() {
        targets.clear();
        lastTarget = null;
        stopAttacking();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!mc.player.isAlive()) {
            if (disableOnDeath.get()) toggle();
            stopAttacking();
            return;
        }

        ticksSinceHit++;

        if (!canRun()) {
            stopAttacking();
            return;
        }

        selectTargets();

        if (targets.isEmpty()) {
            stopAttacking();
            return;
        }

        if (autoSwitch.get()) switchToWeapon();

        if (!acceptableWeapon(mc.player.getMainHandItem())) {
            stopAttacking();
            return;
        }

        attacking = true;

        if (pausePathing.get() && PathManagers.get().isPathing() && !wasPathing) {
            PathManagers.get().pause();
            wasPathing = true;
        }

        Entity aimTarget = targetMode.get() == TargetMode.Switch ? targets.get(switchIndex % targets.size()) : targets.getFirst();
        boolean ready = ready();
        boolean smooth = aim.get() == Aim.Smooth && targetMode.get() != TargetMode.Multi;

        List<Entity> hits = !ready ? List.of()
            : targetMode.get() == TargetMode.Multi ? List.copyOf(targets)
            : List.of(aimTarget);

        switch (aim.get()) {
            case None -> {
                for (Entity target : hits) hit(target);
            }
            case OnHit -> {
                for (Entity target : hits) turnAndHit(target);
            }
            case Always, Smooth -> {
                if (hits.isEmpty()) {
                    // Keep looking at the target while the cooldown runs
                    if (smooth) smoothStep(aimTarget, false);
                    else turn(aimTarget);
                } else {
                    for (Entity target : hits) {
                        if (smooth) smoothStep(target, true);
                        else turnAndHit(target);
                    }
                }
            }
        }
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        // Changing the slot starts the attack cooldown again
        if (event.packet instanceof ServerboundSetCarriedItemPacket) ticksSinceHit = 0;
    }

    private boolean canRun() {
        if (PlayerUtils.getGameMode() == GameType.SPECTATOR) return false;
        if (TickRate.INSTANCE.getTimeSinceLastTick() >= 1f) return false;
        if (pauseInScreens.get() && mc.gui.screen() != null) return false;
        if (pauseOnEat.get() && mc.player.isUsingItem()) return false;
        if (pauseOnMine.get() && mc.gameMode != null && mc.gameMode.isDestroying()) return false;

        CrystalAura crystalAura = Modules.get().get(CrystalAura.class);
        return !(crystalAura.isActive() && crystalAura.kaTimer > 0);
    }

    private void stopAttacking() {
        smoothValid = false;
        critTicks = 0;

        if (!attacking) return;

        attacking = false;
        randomDelayWaiting = false;

        if (wasPathing) {
            PathManagers.get().resume();
            wasPathing = false;
        }
    }

    // Targets

    private record Scored(Entity entity, double score) {
    }

    private void selectTargets() {
        targets.clear();

        List<Scored> candidates = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) consider(entity, candidates);
        FakePlayerManager.forEach(fakePlayer -> consider(fakePlayer, candidates));

        candidates.sort(Comparator.comparingDouble(Scored::score));

        int wanted = targetMode.get() == TargetMode.Single ? 1 : maxTargets.get();

        // Keeps the target that was chosen before, as long as it is still a good one
        if (sticky.get() && lastTarget != null) {
            for (Scored scored : candidates) {
                if (scored.entity == lastTarget) {
                    targets.add(lastTarget);
                    break;
                }
            }
        }

        for (Scored scored : candidates) {
            if (targets.size() >= wanted) break;
            if (!targets.contains(scored.entity)) targets.add(scored.entity);
        }

        if (!targets.isEmpty()) lastTarget = targets.getFirst();
        else lastTarget = null;
    }

    private void consider(Entity entity, List<Scored> out) {
        if (!valid(entity)) return;

        double distance = Math.sqrt(PlayerUtils.squaredDistanceTo(entity));
        double angle = angleTo(entity);

        if (fov.get() < 360 && angle > fov.get() / 2) return;

        double health = entity instanceof LivingEntity living ? living.getHealth() + living.getAbsorptionAmount() : 20;

        double score = switch (priority.get()) {
            case Distance -> distance;
            case Health -> health;
            case Angle -> angle;
            // Close, weak and in front of you are the best targets
            case Smart -> distance * 10 + angle * 0.4 + health * 1.2;
        };

        out.add(new Scored(entity, score));
    }

    private boolean valid(Entity entity) {
        if (entity == null || entity.equals(mc.player) || entity.equals(mc.getCameraEntity())) return false;
        if ((entity instanceof LivingEntity living && living.isDeadOrDying()) || !entity.isAlive()) return false;
        if (!entities.get().contains(entity.getType())) return false;

        // Like vanilla, reach is the distance from the eyes to the closest point of the hitbox
        AABB hitbox = entity.getBoundingBox();
        Vec3 eyes = mc.player.getEyePosition();
        double dx = eyes.x - Mth.clamp(eyes.x, hitbox.minX, hitbox.maxX);
        double dy = eyes.y - Mth.clamp(eyes.y, hitbox.minY, hitbox.maxY);
        double dz = eyes.z - Mth.clamp(eyes.z, hitbox.minZ, hitbox.maxZ);
        if (dx * dx + dy * dy + dz * dz > range.get() * range.get()) return false;

        if (!PlayerUtils.canSeeEntity(entity) && !PlayerUtils.isWithin(entity, wallsRange.get())) return false;

        if (entity instanceof Player player) {
            if (player.isCreative()) return false;
            if (!Friends.get().shouldAttack(player)) return false;
            if (player instanceof FakePlayerEntity fakePlayer && fakePlayer.noHit) return false;
        }

        if (entity instanceof AgeableMob ageable && ageable.isBaby() && !attackBabies.get()) return false;
        if (entity instanceof NeutralMob && !attackNeutral.get()) return false;
        if (ignoreTamed.get() && entity instanceof TamableAnimal pet && pet.isOwnedBy(mc.player)) return false;
        if (ignoreTeammates.get() && mc.player.isAlliedTo(entity)) return false;
        if (ignoreInvisible.get() && entity.isInvisible()) return false;

        return true;
    }

    /** How far the player would have to turn to look at the entity, in degrees. */
    private double angleTo(Entity entity) {
        Vec3 point = aimAt(entity, AimPoint.Body, 0);
        double yaw = Mth.wrapDegrees(Rotations.getYaw(point) - mc.player.getYRot());
        double pitch = Rotations.getPitch(point) - mc.player.getXRot();

        return Math.sqrt(yaw * yaw + pitch * pitch);
    }

    // Timing

    private boolean ready() {
        if (mc.player.getAttackStrengthScale(0.0f) < cooldown.get()) {
            randomDelayWaiting = false;
            return false;
        }

        // The ticks the attack really needs, longer when the server is slow
        float delay = mc.player.getCurrentItemAttackStrengthDelay() * cooldown.get().floatValue();

        if (tpsSync.get()) {
            float tps = TickRate.INSTANCE.getTickRate();
            if (Float.isNaN(tps) || tps <= 0) tps = 20;
            delay /= Mth.clamp(tps, 1, 20) / 20;
        }

        if (ticksSinceHit < delay) return false;

        if (!criticalOk()) return false;

        if (!randomDelay.get()) return true;

        if (!randomDelayWaiting) {
            int min = Math.min(randomDelayMin.get(), randomDelayMax.get());
            int max = Math.max(randomDelayMin.get(), randomDelayMax.get());
            int extra = min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);

            randomDelayEndNanos = System.nanoTime() + extra * 1_000_000L;
            randomDelayWaiting = true;
        }

        return System.nanoTime() >= randomDelayEndNanos;
    }

    /** The conditions vanilla has for a critical hit. */
    private boolean isCritical() {
        return mc.player.fallDistance > 0
            && !mc.player.onGround()
            && !mc.player.onClimbable()
            && !mc.player.isInWater()
            && !mc.player.hasEffect(MobEffects.BLINDNESS)
            && !mc.player.isPassenger()
            && !mc.player.isSprinting();
    }

    private boolean criticalOk() {
        if (crits.get() == Crits.Off || isCritical()) return true;
        if (crits.get() == Crits.Only) return false;

        // Prefer: only worth waiting when the player is in the air and a critical position is coming
        if (mc.player.onGround()) {
            critTicks = 0;
            return true;
        }

        return ++critTicks > critWait.get();
    }

    // Aim

    private Vec3 aimAt(Entity target, AimPoint point, double predictTicks) {
        AABB box = target.getBoundingBox();
        Vec3 eyes = mc.player.getEyePosition();

        Vec3 position = switch (point) {
            case Eyes -> new Vec3(target.getX(), target.getEyeY(), target.getZ());
            case Body -> new Vec3(target.getX(), target.getY() + target.getBbHeight() / 2, target.getZ());
            case Feet -> new Vec3(target.getX(), target.getY() + 0.1, target.getZ());
            case Closest -> {
                // The nearest point of a slightly smaller hitbox, so the line does not just touch the edge
                AABB inner = box.deflate(Math.min(0.1, Math.min(box.getXsize(), box.getZsize()) / 4));
                Vec3 nearest = new Vec3(
                    Mth.clamp(eyes.x, inner.minX, inner.maxX),
                    Mth.clamp(eyes.y, inner.minY, inner.maxY),
                    Mth.clamp(eyes.z, inner.minZ, inner.maxZ)
                );

                // When the eyes are inside the hitbox every direction hits it
                yield nearest.distanceToSqr(eyes) < 1e-6 ? box.getCenter() : nearest;
            }
        };

        if (predictTicks > 0) {
            // The movement of the last tick, entities do not tell their velocity themselves
            position = position.add((target.getX() - target.xo) * predictTicks, (target.getY() - target.yo) * predictTicks, (target.getZ() - target.zo) * predictTicks);
        }

        return position;
    }

    private Vec3 aimPosition(Entity target) {
        return aimAt(target, aimPoint.get(), predict.get());
    }

    /** Whether a line from the eyes in this direction crosses the hitbox of the target within range. */
    private boolean lineHits(Entity target, double yaw, double pitch) {
        Vec3 eyes = mc.player.getEyePosition();
        Vec3 end = eyes.add(Vec3.directionFromRotation((float) pitch, (float) yaw).scale(range.get()));
        AABB box = target.getBoundingBox().inflate(target.getPickRadius());

        return box.contains(eyes) || box.clip(eyes, end).isPresent();
    }

    private void turn(Entity target) {
        Vec3 point = aimPosition(target);
        Rotations.rotate(Rotations.getYaw(point), Rotations.getPitch(point), HIT_ROTATION_PRIORITY);
    }

    private void turnAndHit(Entity target) {
        Vec3 point = aimPosition(target);
        double yaw = Rotations.getYaw(point), pitch = Rotations.getPitch(point);

        if (requireHit.get() && !lineHits(target, yaw, pitch)) {
            // The point is inside range but the line misses (a very thin hitbox), still turn towards it
            Rotations.rotate(yaw, pitch, HIT_ROTATION_PRIORITY);
            return;
        }

        // The hit is sent right after the rotation, in the order the rotations were asked for
        Rotations.rotate(yaw, pitch, HIT_ROTATION_PRIORITY, () -> hit(target));
    }

    /** Turns a limited amount towards the target. When {@code hit} is set, hits once the line crosses the target. */
    private void smoothStep(Entity target, boolean hit) {
        if (!smoothValid) {
            smoothYaw = mc.player.getYRot();
            smoothPitch = mc.player.getXRot();
            smoothValid = true;
        }

        Vec3 point = aimPosition(target);
        double wantedYaw = Rotations.getYaw(point), wantedPitch = Rotations.getPitch(point);

        float deltaYaw = Mth.wrapDegrees((float) wantedYaw - smoothYaw);
        float deltaPitch = (float) wantedPitch - smoothPitch;
        float speed = smoothSpeed.get().floatValue();

        // Slows down when close, so it settles on the target instead of swinging past it
        float distance = (float) Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        float step = Math.min(distance, Math.max(speed * Math.min(1, distance / 25f), 1.5f));
        float factor = distance < 1e-4f ? 0 : step / distance;

        smoothYaw += deltaYaw * factor;
        smoothPitch = Mth.clamp(smoothPitch + deltaPitch * factor, -90, 90);

        boolean onTarget = !requireHit.get() || lineHits(target, smoothYaw, smoothPitch);

        if (hit && onTarget) Rotations.rotate(smoothYaw, smoothPitch, HIT_ROTATION_PRIORITY, () -> hit(target));
        else Rotations.rotate(smoothYaw, smoothPitch, HIT_ROTATION_PRIORITY);
    }

    // Attacking

    private void hit(Entity target) {
        if (target.isRemoved() || mc.player == null || mc.gameMode == null) return;

        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);

        ticksSinceHit = 0;
        critTicks = 0;
        randomDelayWaiting = false;

        if (targetMode.get() == TargetMode.Switch) switchIndex++;
    }

    private void switchToWeapon() {
        if (attackWhenHolding.get() != AttackItems.Weapons) return;

        int best = -1, bestRank = Integer.MAX_VALUE;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!acceptableWeapon(stack)) continue;

            int rank = weaponRank(stack);

            if (rank < bestRank) {
                bestRank = rank;
                best = slot;
            }
        }

        // The weapon in the hand already is the best one when nothing ranks better
        if (best >= 0 && best != mc.player.getInventory().getSelectedSlot()
            && bestRank < (acceptableWeapon(mc.player.getMainHandItem()) ? weaponRank(mc.player.getMainHandItem()) : Integer.MAX_VALUE)) {
            InvUtils.swap(best, false);
        }
    }

    /** Lower is better. */
    private static int weaponRank(ItemStack stack) {
        if (stack.is(ItemTags.SWORDS)) return 0;
        if (stack.is(ItemTags.SPEARS)) return 1;
        if (stack.is(ItemTags.AXES)) return 2;
        if (stack.getItem() instanceof MaceItem) return 3;
        if (stack.getItem() instanceof TridentItem) return 4;
        if (stack.is(ItemTags.PICKAXES)) return 5;
        return 6;
    }

    private boolean acceptableWeapon(ItemStack stack) {
        if (attackWhenHolding.get() == AttackItems.All) return true;
        if (weapons.get().contains(Items.DIAMOND_SWORD) && stack.is(ItemTags.SWORDS)) return true;
        if (weapons.get().contains(Items.DIAMOND_AXE) && stack.is(ItemTags.AXES)) return true;
        if (weapons.get().contains(Items.DIAMOND_PICKAXE) && stack.is(ItemTags.PICKAXES)) return true;
        if (weapons.get().contains(Items.DIAMOND_SHOVEL) && stack.is(ItemTags.SHOVELS)) return true;
        if (weapons.get().contains(Items.DIAMOND_HOE) && stack.is(ItemTags.HOES)) return true;
        if (weapons.get().contains(Items.MACE) && stack.getItem() instanceof MaceItem) return true;
        if (weapons.get().contains(Items.DIAMOND_SPEAR) && stack.is(ItemTags.SPEARS)) return true;
        return weapons.get().contains(Items.TRIDENT) && stack.getItem() instanceof TridentItem;
    }

    /** The target that is being hit now, or null. */
    public Entity getTarget() {
        return targets.isEmpty() ? null : targets.getFirst();
    }

    @Override
    public String getInfoString() {
        return !targets.isEmpty() ? EntityUtils.getName(getTarget()) : null;
    }
}
