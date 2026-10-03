/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Attacks people around you with a spear. Each attack is a short burst: face the target, (for a charge attack) hold the
 * spear up for as long as it needs to be ready, then rush at the target at a very high speed, and stop completely for a
 * moment afterwards.
 * <p>
 * The damage of a charge attack comes from how fast you move along your look direction, which is why the rush is fast.
 */
public class SpearAura extends Module {
    public enum AttackMode {
        /** Hold the spear and rush into the target. The damage grows with the speed of the rush. */
        Charge,
        /** Rush until the target is in reach, then jab it. */
        Jab
    }

    private enum Phase {
        Idle,
        Windup,
        Dash,
        Pause
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDash = settings.createGroup("Dash");
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    // General

    private final Setting<AttackMode> attackMode = sgGeneral.add(new EnumSetting.Builder<AttackMode>()
        .name("attack-mode")
        .description("Charge: hold the spear and rush into the target, the damage grows with speed. Jab: rush until in reach, then stab.")
        .defaultValue(AttackMode.Charge)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switches to a spear in your hotbar when there is a target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities to attack.")
        .onlyAttackable()
        .defaultValue(EntityTypes.PLAYER)
        .build()
    );

    private final Setting<SortPriority> priority = sgGeneral.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("Which target to pick when there are several.")
        .defaultValue(SortPriority.LowestDistance)
        .build()
    );

    private final Setting<Double> triggerRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Targets closer than this start an attack, in blocks.")
        .defaultValue(14)
        .min(2)
        .sliderRange(4, 40)
        .build()
    );

    private final Setting<Boolean> requireLineOfSight = sgGeneral.add(new BoolSetting.Builder()
        .name("line-of-sight")
        .description("Only attacks targets you can see. Rushing through walls is not possible anyway.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> attackNeutral = sgGeneral.add(new BoolSetting.Builder()
        .name("attack-neutral")
        .description("Also attacks mobs that are neutral and not hostile.")
        .defaultValue(false)
        .build()
    );

    // Dash

    private final Setting<Double> dashSpeed = sgDash.add(new DoubleSetting.Builder()
        .name("dash-speed")
        .description("Speed of the rush in blocks per tick (20 ticks per second). A vanilla server sets you back above about 10.")
        .defaultValue(3)
        .min(0.2)
        .max(9)
        .sliderRange(0.5, 8)
        .build()
    );

    private final Setting<Integer> maxDashTicks = sgDash.add(new IntSetting.Builder()
        .name("max-dash-ticks")
        .description("The longest a rush may take.")
        .defaultValue(14)
        .range(2, 60)
        .sliderRange(4, 30)
        .build()
    );

    private final Setting<Boolean> followHeight = sgDash.add(new BoolSetting.Builder()
        .name("follow-height")
        .description("Also rushes up or down towards the target. Otherwise only sideways.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> overshootTicks = sgDash.add(new IntSetting.Builder()
        .name("overshoot-ticks")
        .description("Ticks to keep rushing after the target is in reach, so the hit lands at full speed.")
        .defaultValue(2)
        .range(0, 10)
        .sliderRange(0, 6)
        .build()
    );

    // Timing

    private final Setting<Integer> pauseTicks = sgTiming.add(new IntSetting.Builder()
        .name("pause-ticks")
        .description("How long you stand still after an attack.")
        .defaultValue(12)
        .range(0, 100)
        .sliderRange(0, 40)
        .build()
    );

    private final Setting<Boolean> holdInAir = sgTiming.add(new BoolSetting.Builder()
        .name("hold-in-air")
        .description("Stays in place while paused, instead of falling.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> extraWindup = sgTiming.add(new IntSetting.Builder()
        .name("extra-windup")
        .description("Ticks to hold the spear after it is ready, before rushing (charge attack).")
        .defaultValue(0)
        .range(0, 20)
        .sliderRange(0, 10)
        .visible(() -> attackMode.get() == AttackMode.Charge)
        .build()
    );

    private final List<Entity> candidates = new ArrayList<>();

    private Phase phase = Phase.Idle;
    private int phaseTicks, afterReachTicks;
    private Entity target, displayTarget;
    private boolean usingKey;

    public SpearAura() {
        super(Categories.Combat, "spear-aura", "Attacks people around you with a spear: a very fast rush at the moment of the attack, then a stop.");
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
        phase = Phase.Idle;
        phaseTicks = 0;
        afterReachTicks = 0;
        target = null;
        releaseUse();
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

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate() || !mc.player.isAlive() || mc.player.isSpectator() || mc.player.isFallFlying()
            || mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            reset();
            return;
        }

        // Make sure we have a spear in hand once there is something to attack
        if (phase == Phase.Idle && !holdingSpear()) {
            if (!autoSwitch.get() || findTarget() == null) return;

            FindItemResult spear = InvUtils.findInHotbar(stack -> stack.is(ItemTags.SPEARS));
            if (!spear.found()) return;
            InvUtils.swap(spear.slot(), false);
        }

        if (!holdingSpear()) {
            reset();
            return;
        }

        switch (phase) {
            case Idle -> idle();
            case Windup -> windup();
            case Dash -> dash();
            case Pause -> pause();
        }
    }

    private void idle() {
        target = findTarget();
        if (target == null) return;

        displayTarget = target;

        phaseTicks = 0;
        afterReachTicks = 0;

        if (attackMode.get() == AttackMode.Charge) {
            phase = Phase.Windup;
            holdUse();
        } else {
            phase = Phase.Dash;
        }
    }

    private void windup() {
        if (!targetStillValid()) {
            endAttack(false);
            return;
        }

        face(target);
        holdUse();
        phaseTicks++;

        KineticWeapon kinetic = mc.player.getMainHandItem().get(DataComponents.KINETIC_WEAPON);
        int needed = (kinetic != null ? kinetic.delayTicks() : 0) + extraWindup.get();

        if (mc.player.isUsingItem() && mc.player.getTicksUsingItem() >= needed) {
            phase = Phase.Dash;
            phaseTicks = 0;
            afterReachTicks = 0;
        } else if (phaseTicks > needed + 30) {
            // The spear never started charging (for example a menu was open), try again
            endAttack(false);
        }
    }

    private void dash() {
        if (!targetStillValid()) {
            endAttack(false);
            return;
        }

        face(target);

        Vec3 eyes = mc.player.getEyePosition();
        Vec3 aim = bodyCenter(target);
        Vec3 direction = aim.subtract(eyes);
        double distance = direction.length();

        if (distance < 1e-3) {
            endAttack(true);
            return;
        }

        direction = followHeight.get() ? direction.scale(1 / distance) : new Vec3(direction.x, 0, direction.z).normalize();

        // Never rush further than the target when it is close, otherwise the first tick already passes it
        double speed = Math.min(dashSpeed.get(), Math.max(distance, 0.5) + overshootTicks.get() * dashSpeed.get());
        Vec3 motion = direction.scale(speed);

        mc.player.setDeltaMovement(followHeight.get() ? motion : new Vec3(motion.x, mc.player.getDeltaMovement().y, motion.z));
        phaseTicks++;

        boolean inReach = distanceToHitbox(target) <= reach() + 0.2;

        if (attackMode.get() == AttackMode.Jab) {
            if (inReach) {
                jab();
                endAttack(true);
                return;
            }
        } else if (inReach) {
            afterReachTicks++;
            if (afterReachTicks > overshootTicks.get()) {
                endAttack(true);
                return;
            }
        }

        if (phaseTicks >= maxDashTicks.get()) endAttack(true);
    }

    private void pause() {
        phaseTicks++;

        Vec3 v = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(0, holdInAir.get() ? 0 : Math.min(v.y, 0), 0);

        if (phaseTicks >= pauseTicks.get()) {
            phase = Phase.Idle;
            phaseTicks = 0;
            target = null;
        }
    }

    /** Ends the current attack and starts the pause. */
    private void endAttack(boolean attacked) {
        releaseUse();
        target = null;

        if (!attacked || pauseTicks.get() == 0) {
            phase = Phase.Idle;
            phaseTicks = 0;
            return;
        }

        phase = Phase.Pause;
        phaseTicks = 0;

        Vec3 v = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(0, holdInAir.get() ? 0 : Math.min(v.y, 0), 0);
    }

    private void jab() {
        ItemStack stack = mc.player.getMainHandItem();
        PiercingWeapon piercing = stack.get(DataComponents.PIERCING_WEAPON);
        if (piercing == null) return;

        mc.gameMode.piercingAttack(piercing);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    // Targets

    private Entity findTarget() {
        TargetUtils.getList(candidates, this::isTargetable, priority.get(), 1);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private boolean isTargetable(Entity entity) {
        if (entity == mc.player || entity == mc.getCameraEntity()) return false;
        if (!entity.isAlive() || (entity instanceof LivingEntity living && living.isDeadOrDying())) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (distanceToHitbox(entity) > triggerRange.get()) return false;

        if (entity instanceof Player player) {
            if (player.isCreative() || player.isSpectator()) return false;
            if (!Friends.get().shouldAttack(player)) return false;
            if (player instanceof FakePlayerEntity fake && fake.noHit) return false;
        }

        if (entity instanceof net.minecraft.world.entity.NeutralMob && !attackNeutral.get()) return false;

        return !requireLineOfSight.get() || meteordevelopment.meteorclient.utils.player.PlayerUtils.canSeeEntity(entity);
    }

    private boolean targetStillValid() {
        return target != null && target.isAlive() && !target.isRemoved() && distanceToHitbox(target) <= triggerRange.get() * 1.5;
    }

    // Helpers

    private boolean holdingSpear() {
        return mc.player.getMainHandItem().is(ItemTags.SPEARS);
    }

    private void face(Entity entity) {
        Vec3 eyes = mc.player.getEyePosition();
        Vec3 aim = bodyCenter(entity);

        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;

        double horizontal = Math.sqrt(dx * dx + dz * dz);

        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, horizontal)), -89, 89));
    }

    private Vec3 bodyCenter(Entity entity) {
        return new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ());
    }

    /** Distance from the eyes to the closest point of the hitbox. */
    private double distanceToHitbox(Entity entity) {
        AABB box = entity.getBoundingBox();
        Vec3 eyes = mc.player.getEyePosition();

        double dx = eyes.x - Mth.clamp(eyes.x, box.minX, box.maxX);
        double dy = eyes.y - Mth.clamp(eyes.y, box.minY, box.maxY);
        double dz = eyes.z - Mth.clamp(eyes.z, box.minZ, box.maxZ);

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** How far the spear reaches. */
    private double reach() {
        AttackRange range = mc.player.getAttackRangeWith(mc.player.getMainHandItem());
        return range != null ? range.maxReach() : 3;
    }

    /** The target of the current attack, also while paused after it. */
    public Entity getTarget() {
        return phase == Phase.Idle ? null : displayTarget;
    }

    @Override
    public String getInfoString() {
        return target != null ? EntityUtils.getName(target) : phase == Phase.Pause ? phase.name() : null;
    }
}
