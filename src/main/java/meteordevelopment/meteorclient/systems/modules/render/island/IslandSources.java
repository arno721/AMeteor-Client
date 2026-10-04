/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render.island;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.meteor.ModuleMessageEvent;
import meteordevelopment.meteorclient.events.meteor.ModuleToggledEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.pathing.IPathManager;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AnchorAura;
import meteordevelopment.meteorclient.systems.modules.combat.BedAura;
import meteordevelopment.meteorclient.systems.modules.combat.BowAimbot;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.systems.modules.combat.CrossbowRagebot;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura1;
import meteordevelopment.meteorclient.systems.modules.combat.SpearAura;
import meteordevelopment.meteorclient.systems.modules.misc.Notebot;
import meteordevelopment.meteorclient.systems.modules.movement.Blink;
import meteordevelopment.meteorclient.systems.modules.movement.ElytraNavigator;
import meteordevelopment.meteorclient.systems.modules.render.DynamicIsland;
import meteordevelopment.meteorclient.systems.modules.render.DynamicIsland.Icon;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.i18n.LanguageManager;
import meteordevelopment.meteorclient.utils.notebot.song.Song;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The activities and events the Dynamic Island knows about out of the box: combat, movement and pathing, utilities,
 * and notices for things that happen. Each one can be turned off in the settings.
 */
public class IslandSources {
    public static final int GREEN = 0xFF4ADE80;
    public static final int RED = 0xFFF87171;
    public static final int AMBER = 0xFFFBBF24;
    public static final int BLUE = 0xFF60A5FA;
    public static final int SKY = 0xFF38BDF8;
    public static final int VIOLET = 0xFFA78BFA;
    public static final int PINK = 0xFFF472B6;
    public static final int MINT = 0xFF34D399;
    public static final int ORANGE = 0xFFFB923C;

    private static final EquipmentSlot[] WATCHED_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };

    private final DynamicIsland island;

    // Combat

    private final Setting<Boolean> lowHealth;
    private final Setting<Double> lowHealthThreshold;
    private final Setting<Boolean> auraTargets;
    private final Setting<Boolean> crossbowCard;
    private final Setting<Boolean> totemPops;

    // Movement

    private final Setting<Boolean> elytra;
    private final Setting<Boolean> pathing;
    private final Setting<Boolean> freecam;
    private final Setting<Boolean> blink;
    private final Setting<Boolean> timer;

    // Utility

    private final Setting<Boolean> eating;
    private final Setting<Boolean> notebot;
    private final Setting<Boolean> durability;
    private final Setting<Integer> durabilityThreshold;
    private final Setting<Boolean> serverLag;
    private final Setting<Double> serverLagSeconds;

    // Events

    private final Setting<Boolean> moduleToggles;
    private final Setting<Boolean> moduleMessages;
    private final Setting<Boolean> setbacks;
    private final Setting<Boolean> dimensionChange;
    private final Setting<Boolean> deathPosition;
    private final Setting<Boolean> chatMentions;
    private final Setting<Boolean> notices;
    private final Setting<Double> cardSeconds;

    // State

    private final AtomicInteger pendingPops = new AtomicInteger();
    private final AtomicInteger pendingSetbacks = new AtomicInteger();
    private int popStreak, setbackStreak;
    private long lastPopNanos, lastSetbackNanos;
    private long joinNanos = System.nanoTime(), dimensionNanos = System.nanoTime();

    private ResourceKey<Level> lastDimension;
    private boolean wasDead;
    private BlockPos deathPos;
    private String deathDimension = "";

    private final boolean[] durabilityWarned = new boolean[WATCHED_SLOTS.length];
    private final ItemStack[] durabilityItem = new ItemStack[WATCHED_SLOTS.length];

    private int burstCount;
    private long lastToggleNanos;
    private final List<String> burstNames = new ArrayList<>();

    private BlockPos pathGoal;
    private double pathStartDistance;

    private KillAura killAura;
    private KillAura1 killAura1;
    private CrossbowRagebot crossbowRagebot;
    private SpearAura spearAura;
    private CrystalAura crystalAura;
    private AnchorAura anchorAura;
    private BedAura bedAura;
    private BowAimbot bowAimbot;
    private ElytraNavigator elytraNavigator;
    private Freecam freecamModule;
    private Blink blinkModule;
    private Timer timerModule;
    private Notebot notebotModule;

    public IslandSources(DynamicIsland island, SettingGroup sgCombat, SettingGroup sgMovement, SettingGroup sgUtility, SettingGroup sgEvents) {
        this.island = island;

        lowHealth = sgCombat.add(new BoolSetting.Builder()
            .name("low-health")
            .description("Shows a warning when your health is low.")
            .defaultValue(true)
            .build()
        );

        lowHealthThreshold = sgCombat.add(new DoubleSetting.Builder()
            .name("low-health-threshold")
            .description("Health at or below which the warning shows, in health points.")
            .defaultValue(6)
            .min(1)
            .sliderRange(2, 12)
            .visible(lowHealth::get)
            .build()
        );

        auraTargets = sgCombat.add(new BoolSetting.Builder()
            .name("aura-targets")
            .description("Shows the target of Kill Aura, Spear Aura, Crystal Aura, Anchor Aura, Bed Aura and Bow Aimbot.")
            .defaultValue(true)
            .build()
        );

        crossbowCard = sgCombat.add(new BoolSetting.Builder()
            .name("crossbow-ragebot")
            .description("Shows what the Crossbow Ragebot is doing: the target, the hit chance, the charge, the arrows and the crossbow durability left, and how many arrows hit.")
            .defaultValue(true)
            .build()
        );

        totemPops = sgCombat.add(new BoolSetting.Builder()
            .name("totem-pops")
            .description("Shows a notice when one of your totems pops, with how many are left.")
            .defaultValue(true)
            .build()
        );

        elytra = sgMovement.add(new BoolSetting.Builder()
            .name("elytra-navigator")
            .description("Shows the progress of the Elytra Navigator.")
            .defaultValue(true)
            .build()
        );

        pathing = sgMovement.add(new BoolSetting.Builder()
            .name("pathing")
            .description("Shows where Baritone is going, how far it is and how long it takes.")
            .defaultValue(true)
            .build()
        );

        freecam = sgMovement.add(new BoolSetting.Builder()
            .name("freecam")
            .description("Shows how far the camera is from you while Freecam is on.")
            .defaultValue(true)
            .build()
        );

        blink = sgMovement.add(new BoolSetting.Builder()
            .name("blink")
            .description("Shows how many packets Blink is holding back and for how long.")
            .defaultValue(true)
            .build()
        );

        timer = sgMovement.add(new BoolSetting.Builder()
            .name("timer")
            .description("Shows the game speed while Timer is on.")
            .defaultValue(true)
            .build()
        );

        eating = sgUtility.add(new BoolSetting.Builder()
            .name("eating")
            .description("Shows what you are eating or drinking and how far along it is.")
            .defaultValue(true)
            .build()
        );

        notebot = sgUtility.add(new BoolSetting.Builder()
            .name("notebot")
            .description("Shows the song Notebot is playing and its progress.")
            .defaultValue(true)
            .build()
        );

        durability = sgUtility.add(new BoolSetting.Builder()
            .name("durability")
            .description("Shows a notice when your armor, elytra or held items are about to break.")
            .defaultValue(true)
            .build()
        );

        durabilityThreshold = sgUtility.add(new IntSetting.Builder()
            .name("durability-threshold")
            .description("Durability left, in percent, at which the notice shows.")
            .defaultValue(10)
            .range(1, 50)
            .sliderRange(2, 30)
            .visible(durability::get)
            .build()
        );

        serverLag = sgUtility.add(new BoolSetting.Builder()
            .name("server-lag")
            .description("Shows a warning when the server stops responding.")
            .defaultValue(true)
            .build()
        );

        serverLagSeconds = sgUtility.add(new DoubleSetting.Builder()
            .name("server-lag-seconds")
            .description("How long the server has to be silent before the warning shows.")
            .defaultValue(1.5)
            .min(0.5)
            .sliderRange(0.5, 5)
            .visible(serverLag::get)
            .build()
        );

        moduleToggles = sgEvents.add(new BoolSetting.Builder()
            .name("module-toggles")
            .description("Shows a card when a module is turned on or off. Many at once become one card.")
            .defaultValue(true)
            .build()
        );

        moduleMessages = sgEvents.add(new BoolSetting.Builder()
            .name("module-messages")
            .description("Shows the warnings and errors that modules send to the chat.")
            .defaultValue(true)
            .build()
        );

        setbacks = sgEvents.add(new BoolSetting.Builder()
            .name("setbacks")
            .description("Shows a notice when the server moves you back.")
            .defaultValue(true)
            .build()
        );

        dimensionChange = sgEvents.add(new BoolSetting.Builder()
            .name("dimension-change")
            .description("Shows a notice when you enter another dimension.")
            .defaultValue(true)
            .build()
        );

        deathPosition = sgEvents.add(new BoolSetting.Builder()
            .name("death-position")
            .description("Shows where you died after you respawn.")
            .defaultValue(true)
            .build()
        );

        chatMentions = sgEvents.add(new BoolSetting.Builder()
            .name("chat-mentions")
            .description("Shows a notice when someone writes your name in the chat.")
            .defaultValue(true)
            .build()
        );

        notices = sgEvents.add(new BoolSetting.Builder()
            .name("notices")
            .description("Shows notices that other modules send, for example the Elytra Navigator arriving.")
            .defaultValue(true)
            .build()
        );

        cardSeconds = sgEvents.add(new DoubleSetting.Builder()
            .name("card-duration")
            .description("How long notices stay, in seconds.")
            .defaultValue(2.2)
            .min(0.5)
            .sliderRange(1, 6)
            .build()
        );
    }

    public boolean externalNotices() {
        return notices.get();
    }

    public double cardSeconds() {
        return cardSeconds.get();
    }

    public void reset() {
        pendingPops.set(0);
        pendingSetbacks.set(0);
        popStreak = setbackStreak = 0;
        joinNanos = dimensionNanos = System.nanoTime();
        lastDimension = null;
        wasDead = false;
        deathPos = null;
        burstCount = 0;
        burstNames.clear();
        pathGoal = null;

        for (int i = 0; i < WATCHED_SLOTS.length; i++) {
            durabilityWarned[i] = false;
            durabilityItem[i] = null;
        }
    }

    // Activities, asked once per tick

    /** Adds a card for every activity that is going on. {@code next} hands out a card to fill. */
    public void collect(List<IslandCard> out, java.util.function.Supplier<IslandCard> next) {
        add(out, next, this::fillLowHealth);
        add(out, next, this::fillServerLag);
        add(out, next, this::fillCrossbow);
        add(out, next, this::fillAuraTarget);
        add(out, next, this::fillEating);
        add(out, next, this::fillElytra);
        add(out, next, this::fillPathing);
        add(out, next, this::fillNotebot);
        add(out, next, this::fillBlink);
        add(out, next, this::fillFreecam);
        add(out, next, this::fillTimer);
    }

    private static void add(List<IslandCard> out, java.util.function.Supplier<IslandCard> next, IslandSource source) {
        IslandCard card = next.get();

        try {
            if (source.fillIslandCard(card)) out.add(card);
        } catch (RuntimeException ignored) {
            // A module in a strange state should not break the island
        }
    }

    private boolean fillLowHealth(IslandCard c) {
        if (!lowHealth.get() || !mc.player.isAlive()) return false;

        double health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (health > lowHealthThreshold.get()) return false;

        double max = Math.max(mc.player.getMaxHealth(), 1);

        c.reset("low-health", IslandSource.CRITICAL);
        c.icon = Icon.HEART;
        c.accent = RED;
        c.pulse = true;
        c.title = tr("low-health", "Low health");
        c.subtitle = "%.1f / %.0f HP".formatted(health, max);
        c.value = "%.1f".formatted(health);
        c.compactValue = "%.1f HP".formatted(health);
        c.chip = "%.0f".formatted(health);
        c.progress = Mth.clamp(health / max, 0, 1);
        return true;
    }

    private boolean fillServerLag(IslandCard c) {
        if (!serverLag.get() || seconds(joinNanos) < 5 || mc.hasSingleplayerServer() && mc.isPaused()) return false;

        double silent = TickRate.INSTANCE.getTimeSinceLastTick();
        if (silent < serverLagSeconds.get()) return false;

        c.reset("server-lag", 90);
        c.icon = Icon.PING;
        c.level = 0;
        c.accent = RED;
        c.pulse = true;
        c.title = tr("server-lag", "Server not responding");
        c.subtitle = tr("server-lag-detail", "No update for %.1f s").formatted(silent);
        c.value = "%.1f s".formatted(silent);
        c.chip = "%.0fs".formatted(silent);
        return true;
    }

    private boolean fillAuraTarget(IslandCard c) {
        if (!auraTargets.get()) return false;

        if (killAura == null) {
            killAura = Modules.get().get(KillAura.class);
            killAura1 = Modules.get().get(KillAura1.class);
            crossbowRagebot = Modules.get().get(CrossbowRagebot.class);
            spearAura = Modules.get().get(SpearAura.class);
            crystalAura = Modules.get().get(CrystalAura.class);
            anchorAura = Modules.get().get(AnchorAura.class);
            bedAura = Modules.get().get(BedAura.class);
            bowAimbot = Modules.get().get(BowAimbot.class);
        }

        Module source = null;
        Entity target = null;

        if (killAura1.isActive() && (target = killAura1.getTarget()) != null) source = killAura1;
        else if (killAura.isActive() && (target = killAura.getTarget()) != null) source = killAura;
        else if (spearAura.isActive() && (target = spearAura.getTarget()) != null) source = spearAura;
        else if (crystalAura.isActive() && (target = crystalAura.getTarget()) != null) source = crystalAura;
        else if (anchorAura.isActive() && (target = anchorAura.getTarget()) != null) source = anchorAura;
        else if (bedAura.isActive() && (target = bedAura.getTarget()) != null) source = bedAura;
        else if (bowAimbot.isActive() && (target = bowAimbot.getTarget()) != null) source = bowAimbot;
        else if (!crossbowCard.get() && crossbowRagebot.isActive() && (target = crossbowRagebot.getTarget()) != null) source = crossbowRagebot;

        if (source == null || target.isRemoved()) return false;

        String name = EntityUtils.getName(target);
        double distance = Math.sqrt(mc.player.distanceToSqr(target));

        c.reset("aura:" + target.getId(), IslandSource.COMBAT);
        c.icon = Icon.TARGET;
        c.accent = RED;
        c.title = name;
        c.subtitle = "%s  ·  %.1f m".formatted(source.title, distance);
        c.compactTitle = name;

        if (target instanceof LivingEntity living) {
            double health = living.getHealth() + living.getAbsorptionAmount();
            double ratio = Mth.clamp(living.getHealth() / Math.max(living.getMaxHealth(), 1), 0, 1);

            c.value = "%.1f HP".formatted(health);
            c.compactValue = "%.1f".formatted(health);
            c.chip = "%.0f".formatted(health);
            c.progress = ratio;
            c.accent = DynamicIsland.mix(RED, GREEN, ratio);
        } else {
            c.value = "%.1f m".formatted(distance);
        }

        return true;
    }

    private boolean fillCrossbow(IslandCard c) {
        if (!crossbowCard.get()) return false;
        if (crossbowRagebot == null) crossbowRagebot = Modules.get().get(CrossbowRagebot.class);
        if (!crossbowRagebot.isActive()) return false;

        Entity target = crossbowRagebot.getTarget();
        if (target != null && target.isRemoved()) target = null;

        CrossbowRagebot.Phase phase = crossbowRagebot.getPhase();
        double charge = crossbowRagebot.chargeProgress();

        // Without a target it only shows while a crossbow is being charged
        if (target == null && charge < 0) return false;

        double chance = crossbowRagebot.getHitChance();
        int arrows = crossbowRagebot.getArrowAmount();
        double durability = crossbowRagebot.getDurabilityPercent();

        if (crossbowRagebot.isSwitching()) return fillCrossbowSwitch(c, phase, charge, arrows, durability);

        String phaseText = switch (phase) {
            case Shooting -> tr("crossbow-shooting", "Shooting");
            case Charging -> tr("crossbow-charging", "Charging");
            case Loaded -> tr("crossbow-loaded", "Loaded");
            case Waiting -> tr("crossbow-waiting", "Waiting for a better shot");
            case Searching -> tr("crossbow-searching", "Loading");
            case Idle -> tr("crossbow-idle", "Ready");
        };

        c.reset("crossbow", IslandSource.COMBAT + 2);
        c.icon = Icon.TARGET;
        c.pulse = phase == CrossbowRagebot.Phase.Shooting;
        c.accent = chance >= 0 ? DynamicIsland.mix(RED, GREEN, Mth.clamp(chance, 0, 1)) : RED;

        StringBuilder sub = new StringBuilder(phaseText);

        if (target != null) {
            c.title = EntityUtils.getName(target);
            sub.append("  ·  %.1f m".formatted(Math.sqrt(mc.player.distanceToSqr(target))));
            sub.append("  ·  ").append(crossbowRagebot.getCandidateCount()).append(' ').append(tr("crossbow-targets", "in range"));
        } else {
            c.title = tr("crossbow-ragebot", "Crossbow Ragebot");
        }

        sub.append("  ·  ").append(arrows).append(' ').append(tr("crossbow-arrows", "arrows"));
        sub.append("  ·  ").append(crossbowRagebot.loadedCount()).append(' ').append(tr("crossbow-loaded-count", "loaded"));
        if (durability >= 0) sub.append("  ·  ").append(Math.round(durability)).append("% ").append(tr("crossbow-durability", "durability"));

        if (crossbowRagebot.getFollowedCount() > 0) {
            sub.append("  ·  ").append(crossbowRagebot.getHitCount()).append('/').append(crossbowRagebot.getFollowedCount())
                .append(' ').append(tr("crossbow-hits", "hits"));
        }

        if (crossbowRagebot.isSwitching()) sub.append("  ·  ").append(tr("crossbow-switch", "switch"));
        c.subtitle = sub.toString();

        if (charge >= 0) {
            c.progress = charge;
            c.value = "%d%%".formatted(Math.round(charge * 100));
        } else if (chance >= 0) {
            c.progress = Mth.clamp(chance, 0, 1);
            c.value = "%d%%".formatted(Math.round(chance * 100));
        }

        c.compactTitle = target != null ? EntityUtils.getName(target) : phaseText;
        c.compactValue = c.value;
        c.chip = c.value.isEmpty() ? String.valueOf(arrows) : c.value;
        return true;
    }

    // The count of targets shown in Switch mode, held for a moment so it does not jump around
    private int switchShown;
    private long switchShownAt;

    /**
     * Switch mode changes the target every shot, so a card about "the target" would never rest. This one is about the whole
     * group instead: how many are in range, how it goes, and the arrows. The name of the one being shot is only a small hint.
     */
    private boolean fillCrossbowSwitch(IslandCard c, CrossbowRagebot.Phase phase, double charge, int arrows, double durability) {
        long now = System.currentTimeMillis();
        int count = crossbowRagebot.getCandidateCount();

        // Goes up at once, comes down only after a second
        if (count >= switchShown || now - switchShownAt > 1000) {
            switchShown = count;
            switchShownAt = now;
        } else if (count > 0) {
            switchShownAt = Math.max(switchShownAt, now - 500);
        }

        if (switchShown <= 0 && charge < 0) return false;

        int followed = crossbowRagebot.getFollowedCount();
        int hits = crossbowRagebot.getHitCount();
        double ratio = followed > 0 ? Mth.clamp(hits / (double) followed, 0, 1) : -1;

        String phaseText = switch (phase) {
            case Shooting -> tr("crossbow-shooting", "Shooting");
            case Charging -> tr("crossbow-charging", "Charging");
            case Loaded -> tr("crossbow-loaded", "Loaded");
            case Waiting -> tr("crossbow-waiting", "Waiting for a better shot");
            case Searching -> tr("crossbow-searching", "Loading");
            case Idle -> tr("crossbow-idle", "Ready");
        };

        c.reset("crossbow-switch", IslandSource.COMBAT + 2);
        c.icon = Icon.TARGET;
        c.pulse = phase == CrossbowRagebot.Phase.Shooting;
        c.accent = ratio >= 0 ? DynamicIsland.mix(RED, GREEN, ratio) : RED;
        c.expand = false;

        c.title = "%s  ×%d".formatted(tr("crossbow-switch-title", "Switching"), switchShown);

        StringBuilder sub = new StringBuilder(phaseText);
        sub.append("  ·  ").append(arrows).append(' ').append(tr("crossbow-arrows", "arrows"));
        sub.append("  ·  ").append(crossbowRagebot.loadedCount()).append(' ').append(tr("crossbow-loaded-count", "loaded"));
        if (durability >= 0) sub.append("  ·  ").append(Math.round(durability)).append("% ").append(tr("crossbow-durability", "durability"));
        c.subtitle = sub.toString();

        if (charge >= 0) {
            c.progress = charge;
            c.value = "%d%%".formatted(Math.round(charge * 100));
        } else if (ratio >= 0) {
            c.progress = ratio;
            c.value = "%d/%d".formatted(hits, followed);
        } else {
            c.value = String.valueOf(crossbowRagebot.getShotCount());
        }

        c.compactTitle = "×" + switchShown;
        c.compactValue = c.value;
        c.chip = "×" + switchShown;
        return true;
    }

    private boolean fillEating(IslandCard c) {
        if (!eating.get() || !mc.player.isUsingItem()) return false;

        ItemStack stack = mc.player.getUseItem();
        boolean food = stack.getComponents().has(DataComponents.FOOD);
        if (!food && !stack.getComponents().has(DataComponents.CONSUMABLE)) return false;

        int duration = stack.getUseDuration(mc.player);
        if (duration <= 0) return false;

        double progress = Mth.clamp((duration - mc.player.getUseItemRemainingTicks()) / (double) duration, 0, 1);

        c.reset("eat", 70);
        c.icon = Icon.FOOD;
        c.accent = MINT;
        c.expand = false;
        c.title = stack.getHoverName().getString();
        c.subtitle = food ? tr("eating", "Eating") : tr("drinking", "Drinking");
        c.value = "%d%%".formatted(Math.round(progress * 100));
        c.chip = c.value;
        c.progress = progress;
        return true;
    }

    private boolean fillElytra(IslandCard c) {
        if (!elytra.get()) return false;
        if (elytraNavigator == null) elytraNavigator = Modules.get().get(ElytraNavigator.class);
        if (!elytraNavigator.isActive() || !elytraNavigator.isNavigating()) return false;

        double progress = elytraNavigator.getProgress();
        String percent = "%d%%".formatted(Math.round(progress * 100));
        String remaining = distance(elytraNavigator.getRemainingDistance());

        c.reset("elytra", IslandSource.TASK);
        c.icon = Icon.PLANE;
        c.accent = BLUE;
        c.title = elytraNavigator.title;
        c.subtitle = "%s  ·  %s  ·  %d %s".formatted(remaining, DynamicIsland.time(elytraNavigator.getEtaSeconds()),
            elytraNavigator.getRocketsUsed(), tr("fireworks", "fireworks"));
        c.value = percent;
        c.compactTitle = remaining;
        c.compactValue = DynamicIsland.time(elytraNavigator.getEtaSeconds());
        c.chip = percent;
        c.progress = progress;
        return true;
    }

    private boolean fillPathing(IslandCard c) {
        if (!pathing.get()) return false;

        IPathManager paths = PathManagers.get();
        if (!paths.isPathing()) {
            pathGoal = null;
            return false;
        }

        BlockPos goal = paths.getGoalPos();
        double eta = paths.getEtaSeconds();
        String process = paths.getProcessName();

        c.reset("path", IslandSource.TASK - 2);
        c.icon = Icon.PIN;
        c.accent = SKY;
        c.title = process != null && !process.isBlank() ? process : paths.getName();
        c.value = eta >= 0 ? DynamicIsland.time(eta) : "";

        if (goal != null) {
            double dist = Math.sqrt(mc.player.blockPosition().distSqr(goal));

            if (!goal.equals(pathGoal)) {
                pathGoal = goal;
                pathStartDistance = Math.max(dist, 1);
            }

            String where = "%d, %d, %d".formatted(goal.getX(), goal.getY(), goal.getZ());
            c.subtitle = distance(dist) + "  ·  " + where;
            c.compactTitle = distance(dist);
            c.chip = distance(dist);
            c.progress = Mth.clamp(1 - dist / pathStartDistance, 0, 1);
        } else {
            c.subtitle = paths.getName();
        }

        return true;
    }

    private boolean fillNotebot(IslandCard c) {
        if (!notebot.get()) return false;
        if (notebotModule == null) notebotModule = Modules.get().get(Notebot.class);
        if (!notebotModule.isActive()) return false;

        Song song = notebotModule.getPlayingSong();
        if (song == null) return false;

        double progress = notebotModule.getSongProgress();
        String title = song.getTitle();
        String author = song.getAuthor();

        c.reset("notebot", IslandSource.TASK - 5);
        c.icon = Icon.NOTE;
        c.accent = PINK;
        c.title = title == null || title.isBlank() ? notebotModule.title : title;
        c.subtitle = (author == null || author.isBlank() ? "" : author + "  ·  ") + "-" + DynamicIsland.time(notebotModule.getSongSecondsLeft());
        c.value = "%d%%".formatted(Math.round(progress * 100));
        c.chip = c.value;
        c.progress = progress;
        return true;
    }

    private boolean fillBlink(IslandCard c) {
        if (!blink.get()) return false;
        if (blinkModule == null) blinkModule = Modules.get().get(Blink.class);
        if (!blinkModule.isActive()) return false;

        int packets = blinkModule.getPacketCount();

        c.reset("blink", IslandSource.MODE + 5);
        c.icon = Icon.PAUSE;
        c.accent = AMBER;
        c.expand = false;
        c.title = blinkModule.title;
        c.subtitle = tr("blink-detail", "%d packets held").formatted(packets);
        c.value = "%.1f s".formatted(blinkModule.getSeconds());
        c.compactValue = packets + " · " + "%.1fs".formatted(blinkModule.getSeconds());
        c.chip = String.valueOf(packets);
        return true;
    }

    private boolean fillFreecam(IslandCard c) {
        if (!freecam.get()) return false;
        if (freecamModule == null) freecamModule = Modules.get().get(Freecam.class);
        if (!freecamModule.isActive()) return false;

        double dx = freecamModule.pos.x - mc.player.getX();
        double dy = freecamModule.pos.y - mc.player.getEyeY();
        double dz = freecamModule.pos.z - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        c.reset("freecam", IslandSource.MODE);
        c.icon = Icon.EYE;
        c.accent = VIOLET;
        c.expand = false;
        c.title = freecamModule.title;
        c.subtitle = tr("freecam-detail", "%s from you").formatted(distance(dist));
        c.value = distance(dist);
        return true;
    }

    private boolean fillTimer(IslandCard c) {
        if (!timer.get()) return false;
        if (timerModule == null) timerModule = Modules.get().get(Timer.class);
        if (!timerModule.isActive()) return false;

        double multiplier = timerModule.getMultiplier();
        if (Math.abs(multiplier - 1) < 0.005) return false;

        c.reset("timer", IslandSource.MODE - 5);
        c.icon = Icon.SPEED;
        c.accent = ORANGE;
        c.expand = false;
        c.gauge = Mth.clamp(multiplier / 4, 0, 1);
        c.title = timerModule.title;
        c.subtitle = tr("timer-detail", "Game speed");
        c.value = "x%.2f".formatted(multiplier);
        c.chip = "x%.1f".formatted(multiplier);
        return true;
    }

    // Events, checked once per tick

    public void tick() {
        long now = System.nanoTime();

        // Totems
        int pops = pendingPops.getAndSet(0);

        if (pops > 0 && totemPops.get()) {
            popStreak = now - lastPopNanos < 8_000_000_000L ? popStreak + pops : pops;
            lastPopNanos = now;

            int left = InvUtils.find(Items.TOTEM_OF_UNDYING).count();
            String title = popStreak > 1 ? tr("totem-popped-many", "Totem popped  x%d").formatted(popStreak) : tr("totem-popped", "Totem popped");
            String subtitle = left == 0 ? tr("totem-none-left", "No totems left") : tr("totem-left", "%d left").formatted(left);

            island.postNotice("totem", title, subtitle, left == 0 ? RED : left <= 2 ? AMBER : GREEN, Math.max(cardSeconds.get(), 3), Icon.TOTEM);
        }

        // Setbacks
        int moved = pendingSetbacks.getAndSet(0);

        if (moved > 0 && setbacks.get() && seconds(joinNanos) > 5 && seconds(dimensionNanos) > 3) {
            setbackStreak = now - lastSetbackNanos < 5_000_000_000L ? setbackStreak + moved : moved;
            lastSetbackNanos = now;

            String subtitle = setbackStreak > 1
                ? tr("setback-many", "%d times in a row").formatted(setbackStreak)
                : tr("setback-detail", "The server moved you back");

            island.postNotice("setback", tr("setback", "Position corrected"), subtitle, setbackStreak >= 3 ? RED : AMBER, cardSeconds.get(), Icon.WARNING);
        }

        // Dimension
        ResourceKey<Level> dimension = mc.level.dimension();

        if (lastDimension != null && !dimension.equals(lastDimension)) {
            dimensionNanos = now;

            if (dimensionChange.get()) {
                island.postNotice("dimension", tr("entered", "Entered %s").formatted(dimensionName(dimension)),
                    "%d, %d, %d".formatted(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ()),
                    dimensionColor(dimension), Math.max(cardSeconds.get(), 2.5), Icon.PORTAL);
            }
        }

        lastDimension = dimension;

        // Death
        boolean dead = mc.player.isDeadOrDying();

        if (dead && !wasDead) {
            deathPos = mc.player.blockPosition();
            deathDimension = dimensionName(dimension);
        } else if (!dead && wasDead && deathPos != null && deathPosition.get()) {
            island.postNotice("death", tr("died-at", "You died here"),
                "%d, %d, %d  ·  %s".formatted(deathPos.getX(), deathPos.getY(), deathPos.getZ(), deathDimension), RED, 8, Icon.PIN);
        }

        wasDead = dead;

        // Durability
        if (durability.get()) checkDurability();
    }

    private void checkDurability() {
        for (int i = 0; i < WATCHED_SLOTS.length; i++) {
            ItemStack stack = mc.player.getItemBySlot(WATCHED_SLOTS[i]);

            if (stack.isEmpty() || !stack.isDamageableItem() || stack.getMaxDamage() <= 0) {
                durabilityWarned[i] = false;
                durabilityItem[i] = null;
                continue;
            }

            // A different item in the slot gets its own warning
            if (durabilityItem[i] == null || !ItemStack.isSameItem(durabilityItem[i], stack)) {
                durabilityItem[i] = stack.copy();
                durabilityWarned[i] = false;
            }

            int left = stack.getMaxDamage() - stack.getDamageValue();
            double percent = 100.0 * left / stack.getMaxDamage();

            if (percent > durabilityThreshold.get()) {
                durabilityWarned[i] = false;
            } else if (!durabilityWarned[i]) {
                durabilityWarned[i] = true;

                island.postNotice("durability:" + i, tr("durability-low", "Low durability"),
                    "%s  ·  %d%%  ·  %d".formatted(stack.getHoverName().getString(), Math.round(percent), left),
                    percent <= durabilityThreshold.get() / 2.0 ? RED : AMBER, Math.max(cardSeconds.get(), 3.5), Icon.SHIELD);
            }
        }
    }

    // Event handlers, these can be called from other threads, so they only hand the work over

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        reset();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundEntityEventPacket p) {
            if (p.getEventId() != EntityEvent.PROTECTED_FROM_DEATH || mc.level == null) return;

            Entity entity = p.getEntity(mc.level);
            if (entity != null && entity == mc.player) pendingPops.incrementAndGet();
        } else if (event.packet instanceof ClientboundPlayerPositionPacket) {
            pendingSetbacks.incrementAndGet();
        }
    }

    @EventHandler
    private void onModuleToggled(ModuleToggledEvent event) {
        if (!moduleToggles.get() || !Utils.canUpdate() || event.module == island || !event.module.chatFeedback) return;

        long now = System.nanoTime();

        if (now - lastToggleNanos < 350_000_000L) {
            burstCount++;
        } else {
            burstCount = 1;
            burstNames.clear();
        }

        lastToggleNanos = now;
        if (burstNames.size() < 4) burstNames.add(event.module.title);

        if (burstCount >= 3) {
            // Many toggles at once (a profile, a keybind for several modules): one card instead of a queue
            island.removeNotices("toggle:");

            String names = String.join(", ", burstNames.subList(0, Math.min(3, burstNames.size())));
            if (burstCount > 3) names += " …";

            island.postNotice("toggle-burst", tr("toggled-many", "%d modules toggled").formatted(burstCount), names, BLUE, cardSeconds.get(), Icon.INFO);
            return;
        }

        String state = event.active ? tr("enabled", "Enabled") : tr("disabled", "Disabled");
        island.postNotice("toggle:" + event.module.name, event.module.title, state, event.active ? GREEN : RED, cardSeconds.get(),
            event.active ? Icon.CHECK : Icon.CROSS);
    }

    @EventHandler
    private void onModuleMessage(ModuleMessageEvent event) {
        if (!moduleMessages.get() || event.module == island || event.message == null || event.message.isBlank()) return;

        island.postNotice("message:" + event.module.name, event.module.title, event.message.strip(), event.error ? RED : AMBER,
            Math.max(cardSeconds.get(), 3), event.error ? Icon.CROSS : Icon.WARNING);
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (!chatMentions.get() || mc.player == null || event.getMessage() == null) return;

        String name = mc.player.getName().getString();
        String text = event.getMessage().getString();
        if (name.length() < 3 || text.isEmpty()) return;

        String lower = text.toLowerCase(Locale.ROOT);
        String needle = name.toLowerCase(Locale.ROOT);
        int index = lower.indexOf(needle);
        if (index < 0) return;

        // Your own chat messages start with your name, for example "<name> hi" or "name: hi"
        if (index <= 2 && lower.indexOf(needle, index + needle.length()) < 0) return;

        // Only whole words, so "Steve" does not match "Steven"
        int end = index + needle.length();
        boolean before = index == 0 || !Character.isLetterOrDigit(lower.charAt(index - 1)) && lower.charAt(index - 1) != '_';
        boolean after = end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end)) && lower.charAt(end) != '_';
        if (!before || !after) return;

        island.postNotice("mention", tr("mentioned", "Mentioned in chat"), text.strip(), BLUE, Math.max(cardSeconds.get(), 4), Icon.CHAT);
    }

    // Helpers

    private static double seconds(long since) {
        return (System.nanoTime() - since) / 1e9;
    }

    private static String distance(double meters) {
        if (meters >= 10_000) return "%.1f km".formatted(meters / 1000);
        return "%d m".formatted(Math.round(meters));
    }

    private static String dimensionName(ResourceKey<Level> dimension) {
        if (dimension.equals(Level.NETHER)) return tr("nether", "the Nether");
        if (dimension.equals(Level.END)) return tr("end", "the End");
        if (dimension.equals(Level.OVERWORLD)) return tr("overworld", "the Overworld");
        return dimension.identifier().getPath();
    }

    private static int dimensionColor(ResourceKey<Level> dimension) {
        if (dimension.equals(Level.NETHER)) return ORANGE;
        if (dimension.equals(Level.END)) return VIOLET;
        return GREEN;
    }

    static String tr(String key, String english) {
        return LanguageManager.translate("dynamic-island." + key, english);
    }
}
