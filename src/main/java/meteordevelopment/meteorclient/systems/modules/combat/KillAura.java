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
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class KillAura extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgAttack = settings.createGroup("Attack");
    private final SettingGroup sgMultiAttack = settings.createGroup("Multi Attack");
    private final SettingGroup sgDelay = settings.createGroup("Delay");

    // 一般

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
        .filter(FILTER::contains)
        .visible(() -> attackWhenHolding.get() == AttackItems.Weapons)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switches to a matching weapon in your hotbar when attacking.")
        .defaultValue(false)
        .build()
    );

    private final Setting<RotationMode> rotation = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("rotate")
        .description("When to rotate towards the target.")
        .defaultValue(RotationMode.Always)
        .build()
    );

    private final Setting<Boolean> attackBabies = sgGeneral.add(new BoolSetting.Builder()
        .name("attack-babies")
        .description("Whether to attack baby variants of entities.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> attackNeutral = sgGeneral.add(new BoolSetting.Builder()
        .name("attack-neutral")
        .description("Whether to attack neutral mobs.")
        .defaultValue(false)
        .build()
    );

    // 攻擊

    private final Setting<Set<EntityType<?>>> entities = sgAttack.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities the primary target can be picked from.")
        .onlyAttackable()
        .defaultValue(EntityTypes.PLAYER)
        .build()
    );

    private final Setting<SortPriority> priority = sgAttack.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("How the primary target is chosen.")
        .defaultValue(SortPriority.ClosestAngle)
        .build()
    );

    private final Setting<Double> range = sgAttack.add(new DoubleSetting.Builder()
        .name("range")
        .description("The maximum range to attack entities at.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> wallsRange = sgAttack.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("The maximum range to attack entities at through walls.")
        .defaultValue(3.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    // 多重攻擊

    private final Setting<Set<EntityType<?>>> lowHealthEntities = sgMultiAttack.add(new EntityTypeListSetting.Builder()
        .name("low-health-entities")
        .description("Extra entities to attack when they are low on health.")
        .onlyAttackable()
        .build()
    );

    private final Setting<Double> lowHealthThreshold = sgMultiAttack.add(new DoubleSetting.Builder()
        .name("low-health-threshold")
        .description("Entities below this health are added to the low health attacks.")
        .defaultValue(5.0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> lowHealthMaxTargets = sgMultiAttack.add(new IntSetting.Builder()
        .name("low-health-max-targets")
        .description("The maximum number of extra low health entities to attack.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Set<EntityType<?>>> otherEntities = sgMultiAttack.add(new EntityTypeListSetting.Builder()
        .name("other-entities")
        .description("Other extra entities to attack.")
        .onlyAttackable()
        .build()
    );

    private final Setting<Integer> otherMaxTargets = sgMultiAttack.add(new IntSetting.Builder()
        .name("other-max-targets")
        .description("The maximum number of other extra entities to attack.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    // 延遲

    private final Setting<Boolean> tpsSync = sgDelay.add(new BoolSetting.Builder()
        .name("TPS-sync")
        .description("Scales the custom hit delay with the server TPS.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> customDelay = sgDelay.add(new BoolSetting.Builder()
        .name("custom-delay")
        .description("Uses a custom hit delay instead of the vanilla attack cooldown.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> hitDelay = sgDelay.add(new IntSetting.Builder()
        .name("hit-delay")
        .description("The custom hit delay in ticks.")
        .defaultValue(11)
        .min(0)
        .sliderMax(60)
        .visible(customDelay::get)
        .build()
    );

    private final Setting<Boolean> randomCooldownDelay = sgDelay.add(new BoolSetting.Builder()
        .name("random-cooldown")
        .description("Waits a random extra time after the vanilla cooldown is ready. Not used with custom delay.")
        .defaultValue(false)
        .visible(() -> !customDelay.get())
        .build()
    );

    private final Setting<Integer> randomDelayMin = sgDelay.add(new IntSetting.Builder()
        .name("random-delay-min")
        .description("The minimum extra random delay in milliseconds.")
        .defaultValue(0)
        .min(0)
        .sliderMax(1000)
        .visible(() -> randomCooldownDelay.get() && !customDelay.get())
        .build()
    );

    private final Setting<Integer> randomDelayMax = sgDelay.add(new IntSetting.Builder()
        .name("random-delay-max")
        .description("The maximum extra random delay in milliseconds.")
        .defaultValue(100)
        .min(0)
        .sliderMax(1000)
        .visible(() -> randomCooldownDelay.get() && !customDelay.get())
        .build()
    );

    private static final Set<Item> FILTER = Set.of(
        Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.DIAMOND_PICKAXE,
        Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE, Items.MACE,
        Items.DIAMOND_SPEAR, Items.TRIDENT
    );

    private static final int HIT_ROTATION_PRIORITY = -100;

    private final List<Entity> targets = new ArrayList<>();
    private int hitTimer;
    private long randomDelayEndNanos;
    private boolean randomDelayWaiting;
    private boolean wasPathing;
    public boolean attacking;

    public KillAura() {
        super(Categories.Combat, "kill-aura", "Attacks specified entities around you.");
    }

    @Override
    public void onActivate() {
        attacking = false;
        hitTimer = 0;
        randomDelayWaiting = false;
        randomDelayEndNanos = 0;
        wasPathing = false;
    }

    @Override
    public void onDeactivate() {
        targets.clear();
        stopAttacking();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!mc.player.isAlive() || PlayerUtils.getGameMode() == GameType.SPECTATOR) {
            stopAttacking();
            return;
        }
        if (TickRate.INSTANCE.getTimeSinceLastTick() >= 1f) {
            stopAttacking();
            return;
        }
        if (Modules.get().get(CrystalAura.class).isActive() && Modules.get().get(CrystalAura.class).kaTimer > 0) {
            stopAttacking();
            return;
        }

        selectTargets();
        if (targets.isEmpty()) {
            stopAttacking();
            return;
        }

        Entity primary = targets.getFirst();

        if (autoSwitch.get()) {
            FindItemResult weaponResult = new FindItemResult(mc.player.getInventory().getSelectedSlot(), -1);
            if (attackWhenHolding.get() == AttackItems.Weapons) weaponResult = InvUtils.find(this::acceptableWeapon, 0, 8);
            if (weaponResult.found()) InvUtils.swap(weaponResult.slot(), false);
        }

        if (!acceptableWeapon(mc.player.getMainHandItem())) {
            stopAttacking();
            return;
        }

        attacking = true;
        if (PathManagers.get().isPathing() && !wasPathing) {
            PathManagers.get().pause();
            wasPathing = true;
        }

        if (delayCheck()) {
            for (Entity target : new ArrayList<>(targets)) attack(target);
            randomDelayWaiting = false;
        } else if (rotation.get() == RotationMode.Always) {
            // Keep aiming at the primary target while waiting for the next hit
            Rotations.rotate(Rotations.getYaw(primary), Rotations.getPitch(primary, Target.Body));
        }
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (event.packet instanceof ServerboundSetCarriedItemPacket) hitTimer = 0;
    }

    private void stopAttacking() {
        if (!attacking) return;
        attacking = false;
        randomDelayWaiting = false;
        if (wasPathing) {
            PathManagers.get().resume();
            wasPathing = false;
        }
    }

    private void selectTargets() {
        targets.clear();

        List<Entity> primaryCandidates = new ArrayList<>();
        TargetUtils.getList(primaryCandidates, entity -> entityCheck(entity, entities.get()), priority.get(), 1);
        if (primaryCandidates.isEmpty()) return;

        Entity primary = primaryCandidates.getFirst();
        targets.add(primary);

        addExtraTargets(lowHealthMaxTargets.get(), lowHealthEntities.get(), true);
        addExtraTargets(otherMaxTargets.get(), otherEntities.get(), false);
    }

    /** Adds up to {@code max} more targets, skipping the ones that were already picked. */
    private void addExtraTargets(int max, Set<EntityType<?>> types, boolean lowHealthOnly) {
        if (max <= 0) return;

        // Ask for enough candidates that the already picked targets cannot use up all of the slots
        List<Entity> candidates = new ArrayList<>();
        TargetUtils.getList(candidates, entity -> entityCheck(entity, types) && (!lowHealthOnly || isLowHealth(entity)), priority.get(), max + targets.size());

        int added = 0;
        for (Entity entity : candidates) {
            if (targets.contains(entity)) continue;

            targets.add(entity);
            if (++added >= max) break;
        }
    }

    private boolean isLowHealth(Entity entity) {
        return entity instanceof LivingEntity living && living.getHealth() < lowHealthThreshold.get();
    }

    private boolean entityCheck(Entity entity, Set<EntityType<?>> allowedEntities) {
        if (entity.equals(mc.player) || entity.equals(mc.getCameraEntity())) return false;
        if ((entity instanceof LivingEntity livingEntity && livingEntity.isDeadOrDying()) || !entity.isAlive()) return false;
        if (!allowedEntities.contains(entity.getType())) return false;

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

        return true;
    }

    private boolean delayCheck() {
        if (customDelay.get()) {
            randomDelayWaiting = false;
            float delay = hitDelay.get();
            if (tpsSync.get()) {
                // No TPS data yet (or an absurd value) means the server is treated as running at 20 TPS
                float tps = TickRate.INSTANCE.getTickRate();
                if (Float.isNaN(tps) || tps <= 0) tps = 20;
                delay /= Mth.clamp(tps, 1, 20) / 20;
            }
            if (hitTimer < delay) {
                hitTimer++;
                return false;
            }
            return true;
        }

        if (mc.player.getAttackStrengthScale(0.0f) < 1.0f) {
            randomDelayWaiting = false;
            return false;
        }

        if (!randomCooldownDelay.get()) return true;

        if (!randomDelayWaiting) {
            int min = Math.min(randomDelayMin.get(), randomDelayMax.get());
            int max = Math.max(randomDelayMin.get(), randomDelayMax.get());
            int extraDelay = min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
            randomDelayEndNanos = System.nanoTime() + extraDelay * 1_000_000L;
            randomDelayWaiting = true;
        }

        return System.nanoTime() >= randomDelayEndNanos;
    }

    private void attack(Entity target) {
        if (rotation.get() == RotationMode.None) {
            hit(target);
            return;
        }

        // Every target gets its own rotation, and the hit is sent right after that rotation (in queue order)
        Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target, Target.Body), HIT_ROTATION_PRIORITY, () -> hit(target));
    }

    private void hit(Entity target) {
        if (target.isRemoved() || mc.player == null || mc.gameMode == null) return;

        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
        hitTimer = 0;
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

    public Entity getTarget() {
        return targets.isEmpty() ? null : targets.getFirst();
    }

    @Override
    public String getInfoString() {
        return !targets.isEmpty() ? EntityUtils.getName(getTarget()) : null;
    }

    public enum AttackItems {
        Weapons,
        All
    }

    public enum RotationMode {
        Always,
        OnHit,
        None
    }
}
