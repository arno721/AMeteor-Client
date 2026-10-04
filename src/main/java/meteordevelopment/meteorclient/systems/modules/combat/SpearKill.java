/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.NoFall;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lunges at the target with a velocity boost while a spear is charged, so the spear hits harder. This is the Lunge mode of the
 * SpearKill module of Trouser Streak (by etianl). The Blink mode of that module (delaying and bursting the movement packets) is
 * not part of it. For testing on your own server.
 */
public class SpearKill extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAuto = settings.createGroup("Auto");
    private final SettingGroup sgLunge = settings.createGroup("Lunge");

    private final Setting<Boolean> noFallWhileCharging = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-no-fall")
        .description("Turns No Fall off while the spear is charged, so a quick lunge downward is not stopped by it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-range")
        .description("How far in blocks an entity can still be targeted.")
        .defaultValue(256)
        .min(0)
        .sliderRange(0, 512)
        .build()
    );

    public enum TargetListMode {
        Whitelist,
        Blacklist
    }

    private final Setting<TargetListMode> targetListMode = sgGeneral.add(new EnumSetting.Builder<TargetListMode>()
        .name("target-list-mode")
        .description("Whitelist: only these entities are targeted. Blacklist: these entities are not targeted.")
        .defaultValue(TargetListMode.Blacklist)
        .build()
    );

    private final Setting<Set<EntityType<?>>> targetEntities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("target-entities")
        .description("The entities of the list.")
        .onlyAttackable()
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Does not lunge at friends.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoTrigger = sgAuto.add(new BoolSetting.Builder()
        .name("auto-trigger")
        .description("Charges the spear by itself when a target is around, and lunges. Without it you charge the spear yourself and it lunges at what you look at.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgAuto.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switches to a spear in your hotbar when there is a target.")
        .defaultValue(true)
        .visible(autoTrigger::get)
        .build()
    );

    private final Setting<Double> triggerRange = sgAuto.add(new DoubleSetting.Builder()
        .name("trigger-range")
        .description("Targets closer than this start a lunge, in blocks.")
        .defaultValue(14)
        .min(2)
        .sliderRange(4, 60)
        .visible(autoTrigger::get)
        .build()
    );

    public enum Movement {
        /** The speed is given to the player directly. */
        Velocity,
        /** The player flies with an elytra, steered at the target, at the speed that is set. */
        Elytra
    }

    private final Setting<Movement> movement = sgLunge.add(new EnumSetting.Builder<Movement>()
        .name("movement")
        .description("Velocity: the speed is given to you directly. Elytra: you fly with an elytra (it takes off by itself) steered at the target, at the elytra speeds below. Needs an elytra.")
        .defaultValue(Movement.Velocity)
        .build()
    );

    private final Setting<Double> elytraApproachSpeed = sgLunge.add(new DoubleSetting.Builder()
        .name("elytra-approach-speed")
        .description("Elytra: how fast you fly to the place above the target, in blocks per tick (20 ticks per second).")
        .defaultValue(2.5)
        .min(0.1)
        .sliderRange(0.5, 10)
        .visible(() -> movement.get() == Movement.Elytra)
        .build()
    );

    private final Setting<Double> elytraStrikeSpeed = sgLunge.add(new DoubleSetting.Builder()
        .name("elytra-strike-speed")
        .description("Elytra: how fast you go down on the target and back up above it, and the speed of the direct lunge, in blocks per tick.")
        .defaultValue(2.5)
        .min(0.1)
        .sliderRange(0.5, 10)
        .visible(() -> movement.get() == Movement.Elytra)
        .build()
    );

    private final Setting<Integer> elytraMinDurability = sgLunge.add(new IntSetting.Builder()
        .name("elytra-min-durability")
        .description("Elytra: an elytra with this many uses left or fewer is not used any more, so it does not break.")
        .defaultValue(10)
        .range(1, 200)
        .sliderRange(2, 50)
        .visible(() -> movement.get() == Movement.Elytra)
        .build()
    );

    private final Setting<Boolean> elytraSwap = sgLunge.add(new BoolSetting.Builder()
        .name("elytra-swap")
        .description("Elytra: puts on another elytra from your inventory that has more durability left, also in the air. Without one the lunge goes on with the velocity instead.")
        .defaultValue(true)
        .visible(() -> movement.get() == Movement.Elytra)
        .build()
    );

    public enum LungeDirection {
        DirectionBased,
        FromAbove,
        AutoFromAboveFirst
    }

    private final Setting<LungeDirection> lungeDirection = sgLunge.add(new EnumSetting.Builder<LungeDirection>()
        .name("lunge-direction")
        .description("DirectionBased: straight at the target. FromAbove: first above the target, then down on it. AutoFromAboveFirst: from above, unless that place or the way down is not free.")
        .defaultValue(LungeDirection.AutoFromAboveFirst)
        .build()
    );

    private final Setting<Double> aboveHeight = sgLunge.add(new DoubleSetting.Builder()
        .name("above-height")
        .description("Blocks above the center of the target.")
        .defaultValue(10)
        .min(5)
        .sliderRange(5, 50)
        .visible(() -> lungeDirection.get() != LungeDirection.DirectionBased)
        .build()
    );

    private final Setting<Double> aboveTriggerDistance = sgLunge.add(new DoubleSetting.Builder()
        .name("above-trigger-distance")
        .description("Within this distance of the place above the target, the lunge turns towards the target.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 10)
        .visible(() -> lungeDirection.get() != LungeDirection.DirectionBased)
        .build()
    );

    private final Setting<Boolean> validateAround = sgLunge.add(new BoolSetting.Builder()
        .name("validate-around")
        .description("Also checks that the area round the place above the target is free, with the trigger distance as the radius.")
        .defaultValue(true)
        .visible(() -> lungeDirection.get() == LungeDirection.AutoFromAboveFirst)
        .build()
    );

    private final Setting<Double> approachSpeed = sgLunge.add(new DoubleSetting.Builder()
        .name("approach-speed")
        .description("The first speed: how fast you fly to the place above the target, in blocks per tick.")
        .defaultValue(3)
        .min(0)
        .sliderRange(1, 10)
        .visible(() -> lungeDirection.get() != LungeDirection.DirectionBased)
        .build()
    );

    private final Setting<Double> strikeSpeed = sgLunge.add(new DoubleSetting.Builder()
        .name("strike-speed")
        .description("The second speed: how fast you go down on the target and back up above it, again and again, in blocks per tick.")
        .defaultValue(3)
        .min(0)
        .sliderRange(1, 10)
        .visible(() -> lungeDirection.get() != LungeDirection.DirectionBased)
        .build()
    );

    private final Setting<Integer> strikes = sgLunge.add(new IntSetting.Builder()
        .name("strikes")
        .description("How many times you go down on the target and back up above it before the lunge ends.")
        .defaultValue(3)
        .range(1, 20)
        .sliderRange(1, 10)
        .visible(() -> lungeDirection.get() != LungeDirection.DirectionBased)
        .build()
    );

    private final Setting<Double> lungeSpeed = sgLunge.add(new DoubleSetting.Builder()
        .name("speed")
        .description("The speed given to the player, in blocks per tick, towards the target. Used for the DirectionBased lunge, and when there is no free way above the target.")
        .defaultValue(5)
        .min(0)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> stopOnTarget = sgLunge.add(new BoolSetting.Builder()
        .name("stop-on-target")
        .description("Stops the lunge when you reach the target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> stopDistance = sgLunge.add(new DoubleSetting.Builder()
        .name("stop-distance")
        .description("The distance between your hitbox and the hitbox of the target to stop at.")
        .defaultValue(2)
        .min(0)
        .sliderRange(0, 10)
        .visible(stopOnTarget::get)
        .build()
    );

    private final Setting<Integer> delayPercent = sgLunge.add(new IntSetting.Builder()
        .name("delay-percent")
        .description("How much of the time until the spear is ready to wait before the lunge starts.")
        .defaultValue(100)
        .min(0)
        .sliderRange(0, 100)
        .build()
    );

    private enum Stage {
        Approach,
        Dive,
        Return
    }

    private Entity target;
    private boolean charging, firstPhase, wasNoFallEnabled, noFallToggled, usingKey, directOnly;
    private Stage stage = Stage.Approach;
    private int strikesDone, takeOffTimer, swapCooldown;
    private boolean alignedDive;
    private Vec3 abovePos;

    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final Map<Vec3, Boolean> positionCache = new HashMap<>();

    public SpearKill() {
        super(Categories.Combat, "spear-kill", "Lunges at the target with a velocity boost while a spear is charged. For testing on your own server.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        target = null;
        charging = false;
        resetAttack();
        releaseUse();
        positionCache.clear();

        if (noFallToggled && wasNoFallEnabled) Modules.get().get(NoFall.class).toggle();
        noFallToggled = false;
        wasNoFallEnabled = false;
    }

    private boolean isUsingSpear() {
        return mc.player != null && mc.player.getUseItem().getItem().toString().toLowerCase().contains("spear");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        charging = isUsingSpear();
        positionCache.clear();
        guardElytra();

        if (noFallWhileCharging.get()) {
            if (charging && !noFallToggled) {
                wasNoFallEnabled = Modules.get().get(NoFall.class).isActive();

                if (wasNoFallEnabled) {
                    Modules.get().get(NoFall.class).toggle();
                    noFallToggled = true;
                }
            } else if (!charging && noFallToggled) {
                if (wasNoFallEnabled) Modules.get().get(NoFall.class).toggle();
                noFallToggled = false;
            }
        }

        if (autoTrigger.get() && !charging) autoCharge();
        else if (!autoTrigger.get()) releaseUse();

        if (!charging) {
            resetAttack();
            return;
        }

        if (target == null) target = autoTrigger.get() ? findTargetAround() : findTarget();

        if (target != null && !target.isAlive()) {
            if (stopOnTarget.get()) {
                mc.player.setDeltaMovement(0, 0, 0);
                mc.player.setSprinting(false);
            }

            target = null;
            resetAttack();
        }

        if (target == null || !(target instanceof LivingEntity) || !isValidTarget(target)) {
            // Nothing to lunge at any more: let go of the spear
            if (autoTrigger.get()) releaseUse();
            return;
        }

        lunge();
    }

    private void resetAttack() {
        firstPhase = false;
        abovePos = null;
        stage = Stage.Approach;
        strikesDone = 0;
        directOnly = false;
        alignedDive = false;
    }

    private void holdUse() {
        mc.options.keyUse.setDown(true);
        usingKey = true;
    }

    private void releaseUse() {
        if (usingKey) {
            mc.options.keyUse.setDown(false);
            usingKey = false;
        }
    }

    /** Charges the spear when there is a target around: switches to it, then holds the use key. */
    private void autoCharge() {
        Entity around = findTargetAround();

        if (around == null) {
            releaseUse();
            target = null;
            return;
        }

        if (!mc.player.getMainHandItem().is(ItemTags.SPEARS)) {
            if (!autoSwitch.get()) return;

            FindItemResult spear = InvUtils.findInHotbar(stack -> stack.is(ItemTags.SPEARS));
            if (!spear.found()) return;
            InvUtils.swap(spear.slot(), false);
        }

        target = around;
        holdUse();
    }

    private void lunge() {
        int readyTicks = mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND
            ? readyTicks(mc.player.getMainHandItem().getItem())
            : readyTicks(mc.player.getOffhandItem().getItem());

        faceTarget(target);

        if (mc.player.getTicksUsingItem() <= readyTicks) return;

        Vec3 playerPos = mc.player.position();
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        boolean fromAbove = lungeDirection.get() != LungeDirection.DirectionBased;

        // How far the spear reaches: it only hits what is between the minimum and the maximum reach, along the line of sight
        AttackRange range = mc.player.getAttackRangeWith(mc.player.getMainHandItem());
        double minReach = range != null ? range.minReach() : 2, maxReach = range != null ? range.maxReach() : 4.5;
        double gap = distanceToHitbox(target);

        // On the way back up the player is still in reach of the target, it only counts when going down
        if (stage != Stage.Return && mc.player.getBoundingBox().inflate(stopDistance.get()).intersects(target.getBoundingBox())) {
            strikesDone++;

            if (!fromAbove || directOnly || strikesDone >= strikes.get()) {
                if (stopOnTarget.get()) {
                    target = null;
                    mc.player.setDeltaMovement(0, 0, 0);
                    mc.player.setSprinting(false);
                }

                resetAttack();
                return;
            }

            stage = Stage.Return;
        }

        Vec3 direction;
        double speed;

        if (!fromAbove || directOnly) {
            direction = targetCenter.subtract(playerPos).normalize();
            speed = directSpeed();
        } else {
            Vec3 above = new Vec3(targetCenter.x, targetCenter.y + aboveHeight.get(), targetCenter.z);

            if (stage == Stage.Approach) {
                boolean pathValid = lungeDirection.get() == LungeDirection.FromAbove || isPathFromAboveValid(above, target);

                if (!pathValid) {
                    directOnly = true;
                    enterDive();
                } else if (playerPos.distanceTo(above) < aboveTriggerDistance.get()) {
                    enterDive();
                }
            } else if (stage == Stage.Return && playerPos.distanceTo(above) < aboveTriggerDistance.get()) {
                enterDive();
            }

            if (directOnly) {
                direction = targetCenter.subtract(playerPos).normalize();
                speed = directSpeed();
            } else if (stage == Stage.Dive) {
                direction = targetCenter.subtract(playerPos).normalize();
                speed = strikeSpeedNow();
            } else {
                direction = above.subtract(playerPos).normalize();
                speed = stage == Stage.Approach ? approachSpeedNow() : strikeSpeedNow();
            }
        }

        // Going down on the target: the steps are lined up with the stretch the spear reaches
        if (stage == Stage.Dive || !fromAbove || directOnly) speed = divingSpeed(speed, gap, minReach, maxReach);

        if (useElytra()) {
            // Flying: first take off, then steer the elytra towards the aim and fly at the speed that is set
            if (!mc.player.isFallFlying()) {
                takeOff();
                return;
            }

            takeOffTimer = 0;
            double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
            mc.player.setXRot((float) Mth.clamp(-Math.toDegrees(Math.atan2(direction.y, horizontal)), -89, 89));
            mc.player.setDeltaMovement(mc.player.getLookAngle().scale(speed));
            return;
        }

        mc.player.setSprinting(true);
        mc.player.setDeltaMovement(direction.scale(speed));
    }

    private void enterDive() {
        stage = Stage.Dive;
        alignedDive = false;
    }

    /**
     * The speed for a step towards the target. The spear only hits what is between its minimum and maximum reach (2 to 4.5
     * blocks), and the game checks once a tick. With steps longer than that stretch, a step can carry you from in front of it to
     * behind it without a single check landing on the target, and that is when the spear does not hit. So the first step is made
     * shorter, once, so that the full steps after it end in the middle of the stretch.
     */
    private double divingSpeed(double base, double gap, double minReach, double maxReach) {
        double middle = (minReach + maxReach) / 2;

        if (!alignedDive && gap - middle > base) {
            alignedDive = true;
            double first = (gap - middle) % base;
            return first < 0.3 ? base : first;
        }

        // Close already: a step that would go through the whole stretch is shortened so that it ends inside it
        if (gap > maxReach && gap - base < minReach + 0.2) return Mth.clamp(gap - middle, Math.min(1.0, base), base);

        return base;
    }

    /** Distance from the eyes to the closest point of the hitbox of the entity. */
    private double distanceToHitbox(Entity entity) {
        AABB box = entity.getBoundingBox();
        Vec3 eyes = mc.player.getEyePosition();

        double dx = eyes.x - Mth.clamp(eyes.x, box.minX, box.maxX);
        double dy = eyes.y - Mth.clamp(eyes.y, box.minY, box.maxY);
        double dz = eyes.z - Mth.clamp(eyes.z, box.minZ, box.maxZ);

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Whether the elytra is used: it is worn and has more than the minimum of uses left, else the velocity does the lunge. */
    private boolean useElytra() {
        if (movement.get() != Movement.Elytra) return false;

        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        return chest.is(Items.ELYTRA) && usesLeft(chest) > elytraMinDurability.get();
    }

    private static int usesLeft(ItemStack stack) {
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    /** Swaps a nearly broken elytra for the one in the inventory with the most uses left. */
    private void guardElytra() {
        if (swapCooldown > 0) swapCooldown--;
        if (movement.get() != Movement.Elytra || !elytraSwap.get() || swapCooldown > 0) return;

        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA) || usesLeft(chest) > elytraMinDurability.get()) return;

        int best = -1, bestLeft = elytraMinDurability.get();

        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);

            if (stack.is(Items.ELYTRA) && usesLeft(stack) > bestLeft) {
                best = slot;
                bestLeft = usesLeft(stack);
            }
        }

        if (best < 0) return;

        InvUtils.move().from(best).toArmor(EquipmentSlot.CHEST.getIndex());
        swapCooldown = 10;
        info("The elytra had %d uses left, put on another one.", usesLeft(chest));
    }

    private double approachSpeedNow() {
        return useElytra() ? elytraApproachSpeed.get() : approachSpeed.get();
    }

    private double strikeSpeedNow() {
        return useElytra() ? elytraStrikeSpeed.get() : strikeSpeed.get();
    }

    private double directSpeed() {
        return useElytra() ? elytraStrikeSpeed.get() : lungeSpeed.get();
    }

    /** Jumps and opens the elytra: while falling, the server is asked every few ticks until it accepts. */
    private void takeOff() {
        if (mc.player.onGround()) {
            takeOffTimer = 0;
            mc.player.jumpFromGround();
            return;
        }

        takeOffTimer++;

        if (mc.player.getDeltaMovement().y < 0 && takeOffTimer % 4 == 0) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
    }

    private boolean isPathFromAboveValid(Vec3 above, Entity target) {
        if (mc.level == null || above == null) return false;

        Vec3 targetCenter = target.getBoundingBox().getCenter();
        if (blocked(above)) return false;

        if (validateAround.get()) {
            double distance = aboveTriggerDistance.get();
            int radius = (int) distance;

            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        Vec3 test = above.add(x, y, z);
                        if (test.distanceTo(above) <= distance && blocked(test)) return false;
                    }
                }
            }
        }

        int steps = Math.max(10, (int) (above.distanceTo(targetCenter) * 2.5));

        for (int i = 1; i < steps; i++) {
            if (blocked(above.lerp(targetCenter, i / (double) steps))) return false;
        }

        return true;
    }

    /** Whether the player cannot be at the position: outside the world, in an unloaded chunk, in something that hurts or in a block. */
    private boolean blocked(Vec3 pos) {
        if (mc.level == null) return true;
        if (Mth.clamp(pos.y, mc.level.getMinY(), mc.level.getMaxY() - 1) != pos.y) return true;

        BlockPos floored = BlockPos.containing(pos);
        if (mc.level.getChunkSource().getChunkNow(floored.getX() >> 4, floored.getZ() >> 4) == null) return true;

        Boolean cached = positionCache.get(pos);
        if (cached != null) return cached;

        Entity player = mc.player;
        AABB box = player.getBoundingBox().move(pos.subtract(player.position()));

        mutablePos.set(floored);

        for (int x = -1; x <= 1; x++) {
            mutablePos.setX(floored.getX() + x);

            for (int y = -1; y <= 1; y++) {
                mutablePos.setY(floored.getY() + y);

                for (int z = -1; z <= 1; z++) {
                    mutablePos.setZ(floored.getZ() + z);
                    BlockState state = mc.level.getBlockState(mutablePos);

                    if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.MAGMA_BLOCK)
                        || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)) {
                        positionCache.put(pos, true);
                        return true;
                    }
                }
            }
        }

        for (Entity entity : mc.level.getEntities(player, box)) {
            if (entity.canBeCollidedWith(player)) {
                positionCache.put(pos, true);
                return true;
            }
        }

        boolean collides = mc.level.getBlockCollisions(player, box).iterator().hasNext();
        positionCache.put(pos, collides);
        return collides;
    }

    private void faceTarget(Entity entity) {
        Vec3 eyes = mc.player.getEyePosition();
        AABB box = entity.getBoundingBox();
        double centerY = box.getCenter().y;
        double heightDifference = centerY - eyes.y;
        double boxHeight = box.maxY - box.minY;

        // Aim lower on a target above and higher on a target below, the more the further it is
        double aimY = centerY;

        if (Math.abs(heightDifference) >= 1.0) {
            double offset = Math.min(Math.abs(heightDifference) / 5.0, 0.4);
            aimY = heightDifference > 0 ? centerY - boxHeight * offset : centerY + boxHeight * offset;
        }

        Vec3 toTarget = new Vec3(box.getCenter().x, aimY, box.getCenter().z).subtract(eyes).normalize();
        float yaw = (float) (Math.toDegrees(Math.atan2(toTarget.z, toTarget.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.asin(toTarget.y));

        mc.player.setYRot(yaw);
        mc.player.setYHeadRot(yaw);
        mc.player.setXRot(pitch);
    }

    private int readyTicks(Item item) {
        String name = item.toString().toLowerCase();
        int value = 14;

        if (name.contains("wooden")) value = 14;
        else if (name.contains("stone") || name.contains("golden")) value = 13;
        else if (name.contains("copper")) value = 12;
        else if (name.contains("iron")) value = 11;
        else if (name.contains("diamond")) value = 9;
        else if (name.contains("netherite")) value = 7;

        return Math.round(value * (delayPercent.get() / 100.0f));
    }

    private Entity findTarget() {
        if (mc.hitResult instanceof EntityHitResult hit && isValidTarget(hit.getEntity())) return hit.getEntity();

        double range = maxRange.get();
        Vec3 eyes = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0f);

        HitResult blockHit = mc.level.clip(new ClipContext(eyes, eyes.add(look.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        double rayLength = blockHit.getType() == HitResult.Type.MISS ? range : eyes.distanceTo(blockHit.getLocation());

        List<Entity> candidates = mc.level.getEntities(mc.player, mc.player.getBoundingBox().expandTowards(look.scale(rayLength)),
            e -> e instanceof LivingEntity && e.isAlive() && e != mc.player);

        candidates.sort(Comparator.comparingDouble(e -> eyes.distanceToSqr(e.getBoundingBox().getCenter())));

        for (Entity entity : candidates) {
            if (eyes.distanceTo(entity.getBoundingBox().getCenter()) > range) break;
            if (!isValidTarget(entity) || !canSee(entity)) continue;

            // Only what is right in the line of sight
            if (look.dot(entity.getBoundingBox().getCenter().subtract(eyes).normalize()) > 0.999) return entity;
        }

        return null;
    }

    /** The closest target within the trigger range, for the auto trigger. */
    private Entity findTargetAround() {
        Vec3 eyes = mc.player.getEyePosition();
        double range = Math.min(triggerRange.get(), maxRange.get());
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || !(entity instanceof LivingEntity living) || !living.isAlive() || !isValidTarget(entity)) continue;

            double distance = eyes.distanceTo(entity.getBoundingBox().getCenter());
            if (distance > range || distance >= bestDistance || !canSee(entity)) continue;

            best = entity;
            bestDistance = distance;
        }

        return best;
    }

    private boolean canSee(Entity entity) {
        Vec3 eyes = mc.player.getEyePosition();
        Vec3 center = entity.getBoundingBox().getCenter();
        HitResult result = mc.level.clip(new ClipContext(eyes, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));

        return result.getType() == HitResult.Type.MISS || eyes.distanceTo(result.getLocation()) >= eyes.distanceTo(center) - 0.5;
    }

    private boolean isValidTarget(Entity entity) {
        if (entity == null) return false;
        if (entity instanceof Player player && ignoreFriends.get() && Friends.get().isFriend(player)) return false;

        boolean inList = targetEntities.get().contains(entity.getType());

        if (targetListMode.get() == TargetListMode.Whitelist) return inList || targetEntities.get().isEmpty();
        return !inList;
    }
}
