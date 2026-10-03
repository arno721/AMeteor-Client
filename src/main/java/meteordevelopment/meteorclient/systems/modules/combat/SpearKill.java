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
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
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

    public enum LungeDirection {
        DirectionBased,
        FromAbove,
        AutoFromAboveFirst
    }

    private final Setting<LungeDirection> lungeDirection = sgLunge.add(new EnumSetting.Builder<LungeDirection>()
        .name("lunge-direction")
        .description("DirectionBased: straight at the target. FromAbove: first above the target, then down on it. AutoFromAboveFirst: from above, unless that place or the way down is not free.")
        .defaultValue(LungeDirection.DirectionBased)
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

    private final Setting<Double> lungeSpeed = sgLunge.add(new DoubleSetting.Builder()
        .name("speed")
        .description("The speed given to the player, in blocks per tick, towards the target.")
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

    private Entity target;
    private boolean charging, firstPhase, wasNoFallEnabled, noFallToggled;
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
        firstPhase = false;
        abovePos = null;
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

        if (!charging) {
            target = null;
            firstPhase = false;
            abovePos = null;
            return;
        }

        if (target == null) target = findTarget();

        if (target != null && !target.isAlive()) {
            if (stopOnTarget.get()) {
                mc.player.setDeltaMovement(0, 0, 0);
                mc.player.setSprinting(false);
            }

            target = null;
            firstPhase = false;
            abovePos = null;
        }

        if (target == null || !(target instanceof LivingEntity) || !isValidTarget(target)) return;

        lunge();
    }

    private void lunge() {
        int readyTicks = mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND
            ? readyTicks(mc.player.getMainHandItem().getItem())
            : readyTicks(mc.player.getOffhandItem().getItem());

        faceTarget(target);

        if (mc.player.getTicksUsingItem() <= readyTicks) return;

        AABB playerBox = mc.player.getBoundingBox().inflate(stopDistance.get());

        if (playerBox.intersects(target.getBoundingBox())) {
            if (stopOnTarget.get()) {
                target = null;
                mc.player.setDeltaMovement(0, 0, 0);
                mc.player.setSprinting(false);
            }

            firstPhase = false;
            abovePos = null;
            return;
        }

        Vec3 playerPos = mc.player.position();
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        Vec3 direction;

        switch (lungeDirection.get()) {
            case FromAbove, AutoFromAboveFirst -> {
                if (!firstPhase || abovePos == null) {
                    abovePos = new Vec3(targetCenter.x, targetCenter.y + aboveHeight.get(), targetCenter.z);
                    firstPhase = true;
                }

                boolean pathValid = lungeDirection.get() == LungeDirection.FromAbove || isPathFromAboveValid(abovePos, target);

                if (!pathValid || playerPos.distanceTo(abovePos) < aboveTriggerDistance.get()) {
                    direction = targetCenter.subtract(playerPos).normalize();
                    firstPhase = false;
                } else {
                    direction = abovePos.subtract(playerPos).normalize();
                }
            }
            default -> direction = targetCenter.subtract(playerPos).normalize();
        }

        mc.player.setSprinting(true);
        mc.player.setDeltaMovement(direction.scale(lungeSpeed.get()));
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
