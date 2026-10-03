/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.island.IslandCard;
import meteordevelopment.meteorclient.systems.modules.render.island.IslandImage;
import meteordevelopment.meteorclient.systems.modules.render.island.IslandSdf;
import meteordevelopment.meteorclient.systems.modules.render.island.IslandSource;
import meteordevelopment.meteorclient.systems.modules.render.island.IslandSources;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.MeteorStarscript;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import org.meteordev.starscript.Script;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A pill at the top of the screen that morphs to show what is going on: notices, the most important activity (an aura
 * target, a flight, a path, a song), and a second small pill for the next activity, like the iPhone.
 * <p>
 * The layout sits on a grid of design units (one GUI pixel, with the GUI scale capped at 4). Every round part is
 * concentric with the corner it sits in, so the paddings match all the way around. Text is placed on whole screen
 * pixels relative to the edges of the pill, so it never shimmers, and the shapes are drawn by a signed distance
 * shader with exact anti-aliasing (with a triangle fallback when the shader is not available).
 */
public class DynamicIsland extends Module {
    public enum Idle {
        Hidden,
        Clock,
        Fps,
        Ping,
        Coordinates,
        Speed,
        Custom
    }

    /** The glyph shown in the round badge on the left. Other modules can pick one for their notices. */
    public enum Icon {
        CHECK,
        CROSS,
        HEART,
        TARGET,
        PLANE,
        CLOCK,
        FPS,
        PING,
        PIN,
        SPEED,
        FLAG,
        WARNING,
        INFO,
        FOOD,
        NOTE,
        EYE,
        PAUSE,
        SHIELD,
        CHAT,
        PORTAL,
        TOTEM
    }

    private enum Mode {
        /** Small status pill. */
        MINIMAL,
        /** Badge on the left, one line of text and a value on the right. */
        COMPACT,
        /** Badge, title, subtitle, a value and a progress bar. */
        EXPANDED
    }

    // The grid, in design units

    /** Height of the small pills. Their ends are half circles with a radius of 14. */
    private static final double SMALL_H = 28;
    private static final double EXPANDED_H = 56;
    private static final double EXPANDED_BAR_H = 72;
    /** Corner radius of the full card. The badge sits in the corner circle, 10 units from the edge. */
    private static final double EXPANDED_R = 28;
    private static final double SAT_GAP = 6;
    /** How far the two pills melt into each other while the second one comes out. */
    private static final double MELT = 12;

    // Text sizes in design units
    private static final double TEXT_BODY = 9;
    private static final double TEXT_TITLE = 10.5;
    private static final double TEXT_SUBTITLE = 8;
    private static final double TEXT_VALUE = 13;

    /** Width of the soft edge of the triangle shapes, in screen pixels. */
    private static final double FEATHER = 1.0;

    // Round shapes are built from corner arcs
    private static final int SEG = 16;
    private static final int PATH_POINTS = 4 * (SEG + 1);
    private static final int MAX_TEXT = 24;

    /** Distance along the blur (0 to 1) and how much of the shadow is left there, for the triangle fallback. */
    private static final double[][] SHADOW_PROFILE = {{0, 1}, {0.28, 0.80}, {0.50, 0.50}, {0.75, 0.17}, {1, 0}};

    /** Width of the sound bars, in design units. */
    private static final double WAVE_W = 18;

    private static final int GREY = 0xFFA1A1AA;
    private static final int DARK = 0xFF0B0B0E;

    private static final List<IslandSource> EXTRA_SOURCES = new CopyOnWriteArrayList<>();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCombat = settings.createGroup("Combat");
    private final SettingGroup sgMovement = settings.createGroup("Movement");
    private final SettingGroup sgUtility = settings.createGroup("Utility");
    private final SettingGroup sgEvents = settings.createGroup("Events");
    private final SettingGroup sgStyle = settings.createGroup("Style");

    // General

    private final Setting<Idle> idle = sgGeneral.add(new EnumSetting.Builder<Idle>()
        .name("idle")
        .description("What the small pill shows when nothing else is going on.")
        .defaultValue(Idle.Clock)
        .build()
    );

    private final Setting<List<String>> idlePages = sgGeneral.add(new StringListSetting.Builder()
        .name("idle-pages")
        .description("Pages of the Custom idle, shown in turn. Uses Starscript like {player} or {round(server.tps, 1)}. Start a page with [ICON] or [ICON#RRGGBB] to pick the icon and color, and put \" | \" before the part for the right side. Pages that come out empty are skipped.")
        .defaultValue(
            "[CLOCK] {time} | {date}",
            "[INFO] {player} | {ping} ms",
            "[FPS#4ADE80] {fps} FPS | {round(server.tps, 1)} TPS",
            "[NOTE#F472B6] {music.title} | {music.position}",
            "[PIN] {floor(camera.pos.x)} {floor(camera.pos.y)} {floor(camera.pos.z)} | {player.biome}"
        )
        .visible(() -> idle.get() == Idle.Custom)
        .build()
    );

    private final Setting<Double> idlePageSeconds = sgGeneral.add(new DoubleSetting.Builder()
        .name("idle-page-seconds")
        .description("How long each Custom idle page stays.")
        .defaultValue(5)
        .min(1)
        .sliderRange(2, 15)
        .visible(() -> idle.get() == Idle.Custom)
        .build()
    );

    private final Setting<Integer> yOffset = sgGeneral.add(new IntSetting.Builder()
        .name("y-offset")
        .description("Distance from the top of the screen.")
        .defaultValue(6)
        .range(0, 400)
        .sliderRange(0, 60)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the island. It follows the GUI scale up to 4.")
        .defaultValue(1)
        .min(0.5)
        .sliderRange(0.6, 2)
        .build()
    );

    private final Setting<Double> animationSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("animation-speed")
        .description("How fast the island grows and shrinks.")
        .defaultValue(1)
        .min(0.3)
        .sliderRange(0.5, 2.5)
        .build()
    );

    private final Setting<Boolean> autoCompact = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-compact")
        .description("Shows a new activity in full for a moment, then shrinks it to one line.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> expandSeconds = sgGeneral.add(new DoubleSetting.Builder()
        .name("expand-seconds")
        .description("How long a new activity stays in full before it shrinks.")
        .defaultValue(2.8)
        .min(0.5)
        .sliderRange(1, 8)
        .visible(autoCompact::get)
        .build()
    );

    private final Setting<Integer> sidePills = sgGeneral.add(new IntSetting.Builder()
        .name("side-pills")
        .description("How many small pills can come out next to the island, one on each side. 0 shows only the island.")
        .defaultValue(2)
        .range(0, 2)
        .sliderRange(0, 2)
        .build()
    );

    // Combat, movement, utility and events

    private final IslandSources sources = new IslandSources(this, sgCombat, sgMovement, sgUtility, sgEvents);

    // Style

    private final Setting<SettingColor> background = sgStyle.add(new ColorSetting.Builder()
        .name("background")
        .description("Color of the island.")
        .defaultValue(new SettingColor(12, 12, 14, 246))
        .build()
    );

    private final Setting<SettingColor> accent = sgStyle.add(new ColorSetting.Builder()
        .name("accent")
        .description("Color of the idle badge.")
        .defaultValue(new SettingColor(140, 120, 255))
        .build()
    );

    private final Setting<Boolean> glow = sgStyle.add(new BoolSetting.Builder()
        .name("glow")
        .description("Soft light in the color of the card around the island.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> glowStrength = sgStyle.add(new DoubleSetting.Builder()
        .name("glow-strength")
        .description("How strong the glow is.")
        .defaultValue(0.7)
        .min(0.1)
        .sliderRange(0.2, 1.5)
        .visible(glow::get)
        .build()
    );

    private final Setting<Boolean> shadow = sgStyle.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Soft shadow below the island.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> border = sgStyle.add(new BoolSetting.Builder()
        .name("border")
        .description("Thin light outline that is brighter on top.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> smoothShapes = sgStyle.add(new BoolSetting.Builder()
        .name("smooth-shapes")
        .description("Draws the shapes with a shader for perfectly smooth edges. Falls back to triangles when it is not supported.")
        .defaultValue(true)
        .build()
    );

    // Notices

    private static final class Notice {
        String key, title, subtitle;
        int accent;
        Icon icon;
        double seconds, age;
        int id, version;
    }

    /** Notices can be posted from any thread, so they go through here and are handled on the next tick. */
    private final ConcurrentLinkedQueue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    private Notice notice;
    private final ArrayDeque<Notice> pendingNotices = new ArrayDeque<>();
    private int noticeId;

    /** A value that follows its target like a spring, so it can overshoot a little. */
    private static final class Spring {
        double value, velocity;

        void update(double target, double dt, double omega, double zeta, double unitsPerPixel) {
            double stiffness = omega * omega;
            double damping = 2 * zeta * omega;

            int steps = Math.max(1, (int) Math.ceil(dt / 0.006));
            double h = dt / steps;

            for (int i = 0; i < steps; i++) {
                velocity += ((target - value) * stiffness - velocity * damping) * h;
                value += velocity * h;
            }

            // Close enough to not be visible: stop, so it does not wobble by a fraction of a pixel forever
            if (Math.abs(target - value) < 0.3 * unitsPerPixel && Math.abs(velocity) < 6 * unitsPerPixel) snap(target);
        }

        void snap(double v) {
            value = v;
            velocity = 0;
        }
    }

    /** One of the two pills: what it shows, what it showed before (fading out) and how it moves. */
    private static final class Slot {
        final IslandCard shown = new IslandCard(), outgoing = new IslandCard();
        Mode mode = Mode.MINIMAL, outgoingMode = Mode.MINIMAL;
        String layoutKey = "";
        boolean present, outgoingValid;
        double switchTime = 10, progress;

        void show(IslandCard card, Mode newMode, boolean crossFade) {
            String key = card.key + "|" + newMode;

            if (!key.equals(layoutKey)) {
                if (crossFade && present && !layoutKey.isEmpty()) {
                    outgoing.set(shown);
                    outgoingMode = mode;
                    outgoingValid = true;
                } else {
                    outgoingValid = false;
                }

                if (!card.key.equals(shown.key)) progress = Math.max(card.progress, 0);

                switchTime = 0;
                layoutKey = key;
            }

            shown.set(card);
            mode = newMode;
            present = true;
        }

        void hide() {
            present = false;
        }
    }

    /** A small pill next to the island. The right one shows the next most important activity, the left one the one after. */
    private static final class Side {
        final Slot slot = new Slot();
        final Spring width = new Spring(), offset = new Spring();
        final int sign;
        double alpha;

        // Where it is this frame, in screen pixels
        int w, cx, left;
        double emerged, in, out;
        boolean drawn;

        Side(int sign) {
            this.sign = sign;
        }
    }

    private final Slot main = new Slot();
    private final Side[] sides = {new Side(1), new Side(-1)};
    private final List<IslandCard> sideCards = new ArrayList<>();
    private boolean show, failed, wasVisible;

    // Activities
    private final List<IslandCard> cardPool = new ArrayList<>();
    private final List<IslandCard> activities = new ArrayList<>();
    private int poolIndex;
    private final IslandCard idleCard = new IslandCard(), noticeCard = new IslandCard();
    private String primaryKey = "", mainKey = "", expandKey = "";
    private long primarySinceNanos, expandUntilNanos;

    // Animation
    private final Spring width = new Spring(), height = new Spring();
    private double islandAlpha, time;
    private int accentDisplay = 0xFF8C78FF;
    private double heldWidth, shrinkTimer;
    private String heldKey = "";
    private double measuredW, measuredH;

    private double clockHour, clockMinute;
    private double speed, lastX, lastZ;
    private boolean hasLast;

    // Drawing state
    private final IslandSdf sdf = new IslandSdf();
    private boolean sdfOn;
    private double u;
    private int guiScale;
    private GuiGraphicsExtractor graphics;

    private MeshBuilder mesh;
    private final Color tmp = new Color();
    private final double[] vxs = new double[32768], vys = new double[32768];

    private final double[] px = new double[PATH_POINTS], py = new double[PATH_POINTS];
    private final double[] nx = new double[PATH_POINTS], ny = new double[PATH_POINTS];
    private final int[] ringA = new int[PATH_POINTS], ringB = new int[PATH_POINTS];
    private double pathX, pathY, pathW, pathH;

    private final double[] gx = new double[64], gy = new double[64];

    private int textCount;
    private final String[] textString = new String[MAX_TEXT];
    private final double[] textSize = new double[MAX_TEXT], textCy = new double[MAX_TEXT];
    private final int[] textX = new int[MAX_TEXT], textArgb = new int[MAX_TEXT];
    private final boolean[] textRight = new boolean[MAX_TEXT];

    public DynamicIsland() {
        super(Categories.Render, "dynamic-island", "A pill at the top of the screen that morphs to show notices, aura targets, flights, paths and warnings.");
    }

    @Override
    public void onActivate() {
        notice = null;
        pendingNotices.clear();
        inbox.clear();
        show = false;
        failed = false;
        hasLast = false;
        speed = 0;
        wasVisible = false;
        islandAlpha = 0;
        main.layoutKey = "";
        main.present = false;
        primaryKey = mainKey = expandKey = heldKey = "";
        width.snap(40);
        height.snap(14);

        for (Side side : sides) {
            side.alpha = 0;
            side.slot.layoutKey = "";
            side.slot.present = false;
            side.width.snap(SMALL_H);
            side.offset.snap(-SMALL_H / 2);
        }

        sources.reset();
        MeteorClient.EVENT_BUS.subscribe(sources);
    }

    @Override
    public void onDeactivate() {
        MeteorClient.EVENT_BUS.unsubscribe(sources);
    }

    // Public API

    /** Adds a source that is not a module. Active modules that implement {@link IslandSource} are found on their own. */
    public static void register(IslandSource source) {
        if (!EXTRA_SOURCES.contains(source)) EXTRA_SOURCES.add(source);
    }

    public static void unregister(IslandSource source) {
        EXTRA_SOURCES.remove(source);
    }

    /** Shows a notice on the island, if it is on. Can be called from any thread. */
    public static void show(String title, String subtitle, int accentArgb, double seconds, Icon icon) {
        DynamicIsland island = Modules.get() == null ? null : Modules.get().get(DynamicIsland.class);
        if (island != null) island.notify(title, subtitle, accentArgb, seconds, icon);
    }

    /** Shows a card for a moment. Other modules can call this. */
    public void notify(String title, String subtitle, int accentArgb, double seconds) {
        notify(title, subtitle, accentArgb, seconds, Icon.INFO);
    }

    public void notify(String title, String subtitle, int accentArgb, double seconds, Icon icon) {
        if (!isActive() || !sources.externalNotices()) return;
        postNotice("notify:" + title, title, subtitle, accentArgb, seconds, icon);
    }

    /**
     * Shows a notice. A notice with the same key as the one showing replaces it in place, and one with the same key as
     * a waiting one takes its place in the queue. Can be called from any thread.
     */
    public void postNotice(String key, String title, String subtitle, int accentArgb, double seconds, Icon icon) {
        if (!isActive()) return;

        inbox.add(() -> {
            if (notice != null && notice.key.equals(key)) {
                if (notice.icon != icon || notice.accent != (accentArgb | 0xFF000000)) notice.version++;

                notice.title = title;
                notice.subtitle = subtitle;
                notice.accent = accentArgb | 0xFF000000;
                notice.icon = icon;
                notice.seconds = seconds;
                notice.age = 0;
                return;
            }

            Notice n = new Notice();
            n.key = key;
            n.title = title;
            n.subtitle = subtitle;
            n.accent = accentArgb | 0xFF000000;
            n.icon = icon;
            n.seconds = seconds;
            n.id = ++noticeId;

            pendingNotices.removeIf(p -> p.key.equals(key));

            if (notice == null) {
                notice = n;
            } else {
                pendingNotices.addLast(n);
                while (pendingNotices.size() > 3) pendingNotices.pollFirst();
            }
        });
    }

    /** Removes notices whose key starts with the given text. Can be called from any thread. */
    public void removeNotices(String keyPrefix) {
        inbox.add(() -> {
            pendingNotices.removeIf(n -> n.key.startsWith(keyPrefix));
            if (notice != null && notice.key.startsWith(keyPrefix)) notice = null;
        });
    }

    // What to show is decided once per tick, drawing only animates it

    @EventHandler
    private void onTick(TickEvent.Post event) {
        Runnable job;
        while ((job = inbox.poll()) != null) job.run();

        if (!Utils.canUpdate()) {
            show = false;
            return;
        }

        sources.tick();

        while ((job = inbox.poll()) != null) job.run();

        // When others are waiting, each notice is shown for a shorter time
        if (notice != null) {
            double limit = pendingNotices.isEmpty() ? notice.seconds : Math.min(notice.seconds, 1.4);
            if (notice.age >= limit) notice = null;
        }

        if (notice == null && !pendingNotices.isEmpty()) notice = pendingNotices.pollFirst();

        double dx = mc.player.getX() - lastX, dz = mc.player.getZ() - lastZ;
        double instant = hasLast ? Math.sqrt(dx * dx + dz * dz) * 20 : 0;
        speed += (instant - speed) * 0.25;
        lastX = mc.player.getX();
        lastZ = mc.player.getZ();
        hasLast = true;

        LocalTime clock = LocalTime.now();
        clockHour = clock.getHour() % 12 + clock.getMinute() / 60.0;
        clockMinute = clock.getMinute() + clock.getSecond() / 60.0;

        collectActivities();
        arrange(System.nanoTime());
    }

    private void collectActivities() {
        activities.clear();
        poolIndex = 0;

        sources.collect(activities, this::nextCard);

        for (IslandSource source : EXTRA_SOURCES) addSource(source);

        for (Module module : Modules.get().getActive()) {
            if (module != this && module instanceof IslandSource source) addSource(source);
        }

        // Most important first, the order they were added in breaks ties
        activities.sort((a, b) -> Integer.compare(b.priority, a.priority));
    }

    private void addSource(IslandSource source) {
        IslandCard card = nextCard();

        try {
            if (source.fillIslandCard(card) && !card.key.isEmpty()) activities.add(card);
        } catch (RuntimeException ignored) {
            // Someone else's bug should not break the island
        }
    }

    private IslandCard nextCard() {
        if (poolIndex == cardPool.size()) cardPool.add(new IslandCard());
        return cardPool.get(poolIndex++);
    }

    private static IslandCard find(List<IslandCard> cards, String key) {
        for (IslandCard card : cards) {
            if (card.key.equals(key)) return card;
        }

        return null;
    }

    /** Decides what goes in the island and in the second pill. */
    private void arrange(long now) {
        // The main activity only changes to a more important one, and not more than once in a quarter second
        IslandCard top = activities.isEmpty() ? null : activities.getFirst();
        IslandCard primary = find(activities, primaryKey);

        if (top == null) {
            primaryKey = "";
            expandKey = "";
            primary = null;
        } else if (primary == null || top.priority > primary.priority && now - primarySinceNanos > 250_000_000L) {
            primary = top;
            primaryKey = top.key;
            primarySinceNanos = now;
        }

        IslandCard mainCard;
        IslandCard leftOut = null;
        Mode mode;

        if (notice != null) {
            fillNotice(noticeCard, notice);
            mainCard = noticeCard;
            mode = Mode.EXPANDED;
        } else if (primary != null) {
            mainCard = primary;
            leftOut = primary;

            // A notice in between does not count as new, so the activity does not open up again after it
            if (!primary.key.equals(expandKey)) {
                expandKey = primary.key;
                expandUntilNanos = primary.expand ? now + (long) (expandSeconds.get() * 1e9) : 0;
            }
            mode = !autoCompact.get() || now < expandUntilNanos ? Mode.EXPANDED : Mode.COMPACT;
        } else {
            if (!fillIdle(idleCard)) {
                show = false;
                main.hide();
                for (Side side : sides) side.slot.hide();
                mainKey = "";
                return;
            }

            mainCard = idleCard;
            mode = idleCard.value.isEmpty() && !idleCard.waveform ? Mode.MINIMAL : Mode.COMPACT;
        }

        mainKey = mainCard.key;
        show = true;

        main.show(mainCard, mode, islandAlpha > 0.3);

        // The other activities go to the small pills, the most important first. When there are more than pills, they
        // take turns.
        sideCards.clear();
        for (IslandCard card : activities) {
            if (card != leftOut) sideCards.add(card);
        }

        int slots = Math.min(sidePills.get(), sides.length);
        int count = sideCards.size();
        int start = count > slots ? (int) ((now / 4_000_000_000L) % count) : 0;

        for (int k = 0; k < sides.length; k++) {
            Side side = sides[k];

            if (k < slots && k < count) side.slot.show(sideCards.get((start + k) % count), Mode.MINIMAL, side.alpha > 0.3);
            else side.slot.hide();
        }
    }

    private void fillNotice(IslandCard c, Notice n) {
        c.reset("notice:" + n.id + ":" + n.version, 0);
        c.icon = n.icon;
        c.accent = n.accent;
        c.title = n.title;
        c.subtitle = n.subtitle;
    }

    private boolean fillIdle(IslandCard c) {
        int accentColor = accent.get().getPacked() | 0xFF000000;

        switch (idle.get()) {
            case Hidden -> {
                return false;
            }
            case Clock -> {
                LocalTime now = LocalTime.now();

                c.reset("idle:clock", 0);
                c.icon = Icon.CLOCK;
                c.accent = accentColor;
                c.title = "%02d:%02d".formatted(now.getHour(), now.getMinute());
            }
            case Fps -> {
                int fps = mc.getFps();

                c.reset("idle:fps", 0);
                c.icon = Icon.FPS;
                c.accent = fps >= 60 ? IslandSources.GREEN : fps >= 30 ? IslandSources.AMBER : IslandSources.RED;
                c.title = fps + " FPS";
            }
            case Ping -> {
                int ping = PlayerUtils.getPing();

                c.reset("idle:ping", 0);
                c.icon = Icon.PING;
                c.accent = ping < 80 ? IslandSources.GREEN : ping < 160 ? IslandSources.AMBER : IslandSources.RED;
                c.level = ping < 50 ? 4 : ping < 100 ? 3 : ping < 200 ? 2 : 1;
                c.title = ping + " ms";
            }
            case Speed -> {
                c.reset("idle:speed", 0);
                c.icon = Icon.SPEED;
                c.accent = accentColor;
                c.gauge = Mth.clamp(speed / 40.0, 0, 1);
                c.title = "%.1f m/s".formatted(speed);
            }
            case Custom -> {
                return fillCustomIdle(c, accentColor);
            }
            case Coordinates -> {
                c.reset("idle:coords", 0);
                c.icon = Icon.PIN;
                c.accent = accentColor;
                c.title = "%d  %d  %d".formatted(Mth.floor(mc.player.getX()), Mth.floor(mc.player.getY()), Mth.floor(mc.player.getZ()));
            }
        }

        return true;
    }

    private final Map<String, Optional<Script>> scripts = new HashMap<>();

    /**
     * Fills a card from the Custom idle pages. Pages that come out empty are skipped, so a page about music only
     * shows while something plays.
     */
    private boolean fillCustomIdle(IslandCard c, int accentColor) {
        List<String> pages = idlePages.get();
        if (pages.isEmpty()) return false;

        int start = (int) ((System.nanoTime() / (long) (idlePageSeconds.get() * 1e9)) % pages.size());

        for (int n = 0; n < pages.size(); n++) {
            int index = (start + n) % pages.size();
            String page = pages.get(index).strip();

            Icon icon = Icon.INFO;
            int color = accentColor;

            // [ICON] or [ICON#RRGGBB] at the start
            if (page.startsWith("[")) {
                int close = page.indexOf(']');

                if (close > 0) {
                    String tag = page.substring(1, close).strip();
                    String colorPart = null;
                    int hash = tag.indexOf('#');

                    if (hash >= 0) {
                        colorPart = tag.substring(hash + 1);
                        tag = tag.substring(0, hash);
                    }

                    try {
                        icon = Icon.valueOf(tag.toUpperCase(Locale.ROOT));
                        page = page.substring(close + 1).strip();

                        if (colorPart != null) color = 0xFF000000 | Integer.parseInt(colorPart, 16);
                    } catch (IllegalArgumentException ignored) {
                        // Not an icon (or not a color), keep it as text
                    }
                }
            }

            String titlePart = page, valuePart = "";
            int bar = page.indexOf(" | ");

            if (bar >= 0) {
                titlePart = page.substring(0, bar);
                valuePart = page.substring(bar + 3);
            }

            String title = runTemplate(titlePart).strip();
            if (title.isEmpty()) continue;

            c.reset("idle:custom:" + index, 0);
            c.icon = icon;
            c.accent = color;
            c.title = title;
            c.value = runTemplate(valuePart).strip();
            return true;
        }

        return false;
    }

    /** Runs a Starscript template. A broken template shows as it is written, and only complains once. */
    public String runTemplate(String source) {
        if (source == null || source.isBlank()) return "";
        if (source.indexOf('{') < 0) return source;

        if (scripts.size() > 256) scripts.clear();
        Optional<Script> script = scripts.computeIfAbsent(source, text -> Optional.ofNullable(MeteorStarscript.compile(text)));
        if (script.isEmpty()) return source;

        try {
            String result = MeteorStarscript.run(script.get());
            return result == null ? "" : result;
        } catch (RuntimeException e) {
            return "";
        }
    }

    // Drawing

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (failed) return;

        try {
            render(event);
        } catch (RuntimeException e) {
            // Better to lose the island than to take the game down with it
            failed = true;
            error("Drawing failed, turning off: %s", e);
            toggle();
        }
    }

    private void render(Render2DEvent event) {
        double dt = Mth.clamp(event.frameTime, 0, 0.05);
        time += dt;
        main.switchTime += dt;
        for (Side side : sides) side.slot.switchTime += dt;

        graphics = event.graphics;
        guiScale = mc.getWindow().getGuiScale();
        u = Math.min(guiScale, 4) * scale.get();
        double unitsPerPixel = 1 / u;

        boolean visible = show && main.present && Utils.canUpdate() && !mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden;

        if (visible && !wasVisible) {
            main.switchTime = 0;
            main.outgoingValid = false;
        }

        wasVisible = visible;

        // Notices only count down while they can be seen
        if (visible && notice != null && islandAlpha > 0.5) notice.age += dt;

        int windowW = mc.getWindow().getWidth();

        // Size the island wants to have
        double targetW = 40, targetH = 14;

        if (visible) {
            measure(main.shown, main.mode);
            // Leave room for the small pills, so the whole row stays on the screen
            double reserve = 0;
            for (Side side : sides) {
                if (side.slot.present) reserve += side.width.value + SAT_GAP;
            }

            targetW = Math.min(measuredW, Math.max(windowW / u * 0.8 - reserve, 60));
            targetH = measuredH;
        }

        // The width only shrinks when it has been smaller for a while, so a number that changes does not make it pump
        if (!main.layoutKey.equals(heldKey) || !visible) {
            heldKey = main.layoutKey;
            heldWidth = targetW;
            shrinkTimer = 0;
        } else if (targetW > heldWidth) {
            heldWidth = targetW;
            shrinkTimer = 0;
        } else if (targetW < heldWidth - 4) {
            shrinkTimer += dt;
            if (shrinkTimer > 1.5) heldWidth = targetW;
        } else {
            shrinkTimer = 0;
        }

        double speedFactor = animationSpeed.get();
        width.update(heldWidth, dt, 17 * speedFactor, 0.66, unitsPerPixel);
        height.update(targetH, dt, 17 * speedFactor, 0.66, unitsPerPixel);
        width.value = Math.max(width.value, 6);
        height.value = Math.max(height.value, 6);

        double follow = 1 - Math.exp(-dt * 12 * speedFactor);
        islandAlpha += ((visible ? 1 : 0) - islandAlpha) * follow;

        main.progress += (Math.max(main.shown.progress, 0) - main.progress) * (1 - Math.exp(-dt * 9));
        // The color follows the card, but it does not slide when the island first appears
        accentDisplay = islandAlpha < 0.05 ? main.shown.accent | 0xFF000000 : mix(accentDisplay, main.shown.accent | 0xFF000000, 1 - Math.exp(-dt * 8));

        // The small pills come out of the ends of the island, and go back into it
        for (Side side : sides) {
            boolean sideVisible = visible && side.slot.present;
            double targetWidth = sideVisible ? measureSatellite(side.slot.shown) : side.width.value;

            side.width.update(targetWidth, dt, 17 * speedFactor, 0.7, unitsPerPixel);
            double hidden = -side.width.value / 2;
            side.offset.update(sideVisible ? SAT_GAP + side.width.value / 2 : hidden, dt, 13 * speedFactor, 0.62, unitsPerPixel);
            side.alpha += ((sideVisible ? 1 : 0) - side.alpha) * follow;
            side.slot.progress += (Math.max(side.slot.shown.progress, 0) - side.slot.progress) * (1 - Math.exp(-dt * 9));
        }

        if (islandAlpha < 0.01) return;

        // Everything below is in whole screen pixels where it matters, so nothing lands between pixels
        int cx = windowW / 2;
        int w = 2 * (int) Math.round(width.value * u / 2);
        int h = (int) Math.round(height.value * u);
        int top = (int) Math.round(yOffset.get() * guiScale + 2 * u);
        int left = cx - w / 2;
        int right = left + w;
        double radius = Math.min(h / 2.0, EXPANDED_R * u);

        int satH = (int) Math.round(SMALL_H * u);

        for (Side side : sides) {
            side.w = 2 * (int) Math.round(side.width.value * u / 2);
            int shift = (int) Math.round(side.offset.value * u);
            side.cx = side.sign > 0 ? right + shift : left - shift;
            side.left = side.cx - side.w / 2;

            double hidden = -side.width.value / 2;
            side.emerged = Mth.clamp((side.offset.value - hidden) / Math.max(SAT_GAP + side.width.value, 1), 0, 1);
            side.drawn = side.emerged > 0.002 && side.w > 0;

            // The content of a small pill only shows once the pill is out from under the island
            double clearAt = side.width.value / (SAT_GAP + side.width.value);
            double clear = smooth((side.emerged - clearAt) / Math.max(1 - clearAt, 0.05));
            side.in = smooth((side.slot.switchTime - 0.10) / 0.16) * side.alpha * clear;
            side.out = side.slot.outgoingValid ? (1 - smooth(side.slot.switchTime / 0.10)) * side.alpha * clear : 0;
        }

        double a = islandAlpha;

        // Timing of the cross-fade between the old and the new content
        // The old content is gone before the new one starts, so the two never show on top of each other
        double contentIn = smooth((main.switchTime - 0.10) / 0.16);
        double contentOut = main.outgoingValid ? 1 - smooth(main.switchTime / 0.10) : 0;

        sdfOn = smoothShapes.get() && IslandSdf.isAvailable();

        Renderer2D r2 = Renderer2D.COLOR;
        r2.begin();
        mesh = r2.triangles;
        textCount = 0;

        if (sdfOn) sdf.begin();

        try {
            drawBodies(cx, top + h / 2.0, w / 2.0, h / 2.0, radius, top + satH / 2.0, satH / 2.0, a);

            if (contentOut > 0.02) drawCard(main.outgoing, main.outgoingMode, a * contentOut, left, top, right, main.progress);
            if (contentIn > 0.02) drawCard(main.shown, main.mode, a * contentIn, left, top, right, main.progress);

            for (Side side : sides) {
                if (!side.drawn) continue;

                if (side.out > 0.02) drawSatellite(side.slot.outgoing, a * side.out, side.left, top, side.slot.progress);
                if (side.in > 0.02) drawSatellite(side.slot.shown, a * side.in, side.left, top, side.slot.progress);
            }
        } catch (RuntimeException e) {
            if (sdfOn) sdf.abort();
            IslandImage.INSTANCE.clear();
            r2.render();
            throw e;
        }

        if (sdfOn) {
            try {
                sdf.render();
            } catch (RuntimeException e) {
                // Something about the shader does not work here, use the triangles from now on
                IslandSdf.markBroken();
                MeteorClient.LOG.warn("Dynamic Island shader failed, using the fallback renderer.", e);
            }
        }

        IslandImage.INSTANCE.flush();
        r2.render();
        flushText();
    }

    // Layout

    private void measure(IslandCard c, Mode mode) {
        switch (mode) {
            case MINIMAL -> {
                double t = textWidth(c.title, TEXT_BODY) / u;
                measuredW = Math.max(72, 30 + t + 14);
                measuredH = SMALL_H;
            }
            case COMPACT -> {
                double t = textWidth(c.compactTitle(), TEXT_BODY) / u;
                double v = waveformInstead(c, c.compactValue()) ? WAVE_W : textWidth(c.compactValue(), TEXT_BODY) / u;
                measuredW = Math.max(120, 32 + t + (v > 0 ? 12 + v : 0) + 14);
                measuredH = SMALL_H;
            }
            case EXPANDED -> {
                double t = Math.max(textWidth(c.title, TEXT_TITLE), textWidth(c.subtitle, TEXT_SUBTITLE)) / u;
                double v = waveformInstead(c, c.value) ? WAVE_W : textWidth(c.value, TEXT_VALUE) / u;
                measuredW = Math.max(200, 56 + t + (v > 0 ? 16 + v : 0) + 20);
                measuredH = c.progress >= 0 ? EXPANDED_BAR_H : EXPANDED_H;
            }
        }
    }

    private double measureSatellite(IslandCard c) {
        if (c.chip.isEmpty()) return c.waveform ? 31 + WAVE_W + 13 : SMALL_H;
        return Math.min(31 + textWidth(c.chip, TEXT_BODY) / u + 13, 96);
    }

    /** Size of one pixel of the vanilla font, in screen pixels. It is a bitmap font, so this is a whole number. */
    private int step(double sizeUnits) {
        return Math.max(1, (int) Math.round(sizeUnits * u / 9));
    }

    /** Width of the text in screen pixels, without the space after the last letter. */
    private double textWidth(String text, double sizeUnits) {
        if (text == null || text.isEmpty()) return 0;

        TextRenderer tr = TextRenderer.get();

        if (tr instanceof VanillaTextRenderer) return Math.max(0, mc.font.width(text) - 1) * step(sizeUnits);

        tr.begin(graphics, sizeUnits * u / 18.0, true, false);

        try {
            return tr.getWidth(text);
        } finally {
            tr.end();
        }
    }

    /** Shortens the text until it fits. */
    private String fit(String text, double sizeUnits, double maxPixels) {
        if (text.isEmpty() || textWidth(text, sizeUnits) <= maxPixels) return text;

        int end = text.length();
        while (end > 1 && textWidth(text.substring(0, end) + "..", sizeUnits) > maxPixels) end--;

        return text.substring(0, end).stripTrailing() + "..";
    }

    private int px(double units) {
        return (int) Math.round(units * u);
    }

    // Body of the island

    private void drawBodies(double cx, double cy, double halfW, double halfH, double radius, double satCy, double satHalfH, double a) {
        Side right = sides[0], left = sides[1];
        double rightHalfW = right.drawn ? right.w / 2.0 : 0, leftHalfW = left.drawn ? left.w / 2.0 : 0;

        int accentColor = accentDisplay;
        double breathe = 0.85 + 0.15 * Math.sin(time * 1.7);

        SettingColor bg = background.get();
        int base = (bg.getPacked() & 0xFFFFFF) | (int) Mth.clamp(bg.a * a, 0, 255) << 24;
        int topColor = mix(base, 0xFFFFFFFF, 0.07) & 0xFFFFFF | (base & 0xFF000000);
        int bottomColor = mix(base, 0xFF000000, 0.25) & 0xFFFFFF | (base & 0xFF000000);
        int borderColor = argb(255, 255, 255, (int) (255 * 0.22 * a));
        int shadowColor = argb(0, 0, 0, (int) (255 * 0.40 * a));
        int glowColor = alpha(accentColor, glowStrength.get() * 0.42 * breathe * a);

        if (sdfOn) {
            double rx = right.cx - cx, lx = left.cx - cx, oy = satCy - cy;
            double satR = satHalfH;

            // Each small pill melts into the island until the gap between them opens up, then they are cleanly apart
            double rightGap = (right.cx - rightHalfW - (cx + halfW)) / u;
            double leftGap = ((cx - halfW) - (left.cx + leftHalfW)) / u;
            double rightMelt = rightHalfW > 0 ? MELT * u * Mth.clamp(1 - rightGap / SAT_GAP, 0, 1) : 0;
            double leftMelt = leftHalfW > 0 ? MELT * u * Mth.clamp(1 - leftGap / SAT_GAP, 0, 1) : 0;

            if (shadow.get()) {
                double sigma = 6 * u;
                sdf.trio(cx, cy + 4 * u, halfW - 2 * u, halfH - 1 * u, radius,
                    rx, oy, Math.max(rightHalfW - 2 * u, 0), satHalfH - 1 * u, satR, rightMelt,
                    lx, oy, Math.max(leftHalfW - 2 * u, 0), satHalfH - 1 * u, satR, leftMelt,
                    IslandSdf.SHADOW, sigma, 0, shadowColor, shadowColor, 0);
            }

            if (glow.get()) {
                sdf.trio(cx, cy, halfW, halfH, radius,
                    rx, oy, rightHalfW, satHalfH, satR, rightMelt,
                    lx, oy, leftHalfW, satHalfH, satR, leftMelt,
                    IslandSdf.GLOW, 22 * u, 0, glowColor, glowColor, 0);
            }

            sdf.trio(cx, cy, halfW, halfH, radius,
                rx, oy, rightHalfW, satHalfH, satR, rightMelt,
                lx, oy, leftHalfW, satHalfH, satR, leftMelt,
                IslandSdf.FILL, border.get() ? Math.max(1.0, 0.5 * u) : 0, 0.85, topColor, bottomColor, borderColor);
            return;
        }

        // Triangle fallback: separate pills, the small ones fade in
        drawBodyTriangles(cx - halfW, cy - halfH, halfW * 2, halfH * 2, radius, a, topColor, bottomColor, base, shadowColor, glowColor);

        for (Side side : sides) {
            if (!side.drawn) continue;

            double sa = smooth((side.emerged - 0.25) / 0.5);
            double hw = side.w / 2.0;

            if (sa > 0.01) {
                drawBodyTriangles(side.cx - hw, satCy - satHalfH, hw * 2, satHalfH * 2, satHalfH, a * sa,
                    alpha(topColor, sa), alpha(bottomColor, sa), alpha(base, sa), alpha(shadowColor, sa), alpha(glowColor, sa));
            }
        }
    }

    private void drawBodyTriangles(double left, double top, double w, double h, double radius, double a,
                                   int topColor, int bottomColor, int base, int shadowColor, int glowColor) {
        if (shadow.get()) {
            double blur = Math.max(Math.min(10 * u, h / 2 - 1 * u), 0);

            path(left + blur + 1 * u, top + 5 * u + blur, Math.max(w - 2 * blur - 2 * u, 1), Math.max(h - 2 * blur, 1), Math.max(0, radius - blur));
            ensure(PATH_POINTS + 1, PATH_POINTS * 3);
            fan(shadowColor, shadowColor, false);

            double span = 2 * blur;
            for (int i = 0; i < SHADOW_PROFILE.length - 1; i++) {
                ring(span * SHADOW_PROFILE[i][0], span * SHADOW_PROFILE[i + 1][0], alpha(shadowColor, SHADOW_PROFILE[i][1]), alpha(shadowColor, SHADOW_PROFILE[i + 1][1]), 0);
            }
        }

        path(left, top, w, h, radius);

        if (glow.get()) {
            double size = 22 * u;

            ring(0, size * 0.10, glowColor, alpha(glowColor, 0.55), 0);
            ring(size * 0.10, size * 0.35, alpha(glowColor, 0.55), alpha(glowColor, 0.18), 0);
            ring(size * 0.35, size, alpha(glowColor, 0.18), alpha(glowColor, 0), 0);
        }

        ensure(PATH_POINTS + 1, PATH_POINTS * 3);
        fan(topColor, bottomColor, false);
        ring(0, FEATHER, base, alpha(base, 0), 0);

        if (border.get()) {
            double bw = Math.max(1.0, 0.5 * u);
            ring(0, -bw, argb(255, 255, 255, (int) (255 * 0.22 * a)), argb(255, 255, 255, (int) (255 * 0.015 * a)), 0.85);
        }
    }

    // Content

    private void drawCard(IslandCard c, Mode mode, double a, int left, int top, int right, double progress) {
        int white = alpha(0xFFFFFFFF, a);
        int grey = alpha(GREY, a);
        int accentColor = c.accent | 0xFF000000;
        int valueColor = alpha(accentColor, a);

        switch (mode) {
            case MINIMAL -> {
                double cy = top + 14 * u;

                drawBadge(c, left + 14 * u, cy, 9 * u, a);

                int x = left + px(30);
                addText(fit(c.title, TEXT_BODY, right - px(14) - x), TEXT_BODY, x, cy, false, white);
            }
            case COMPACT -> {
                double cy = top + 14 * u;
                double badgeX = left + 14 * u;

                // The progress goes around the badge, on the same center as the end of the pill
                if (c.progress >= 0) drawRing(badgeX, cy, 11 * u, 2 * u, progress, accentColor, a);

                drawBadge(c, badgeX, cy, 8 * u, a);

                String value = c.compactValue();
                int valueRight = right - px(14);
                boolean wave = waveformInstead(c, value);
                double valueW = wave ? WAVE_W * u : textWidth(value, TEXT_BODY);
                int x = left + px(32);

                addText(fit(c.compactTitle(), TEXT_BODY, valueRight - x - (valueW > 0 ? valueW + px(12) : 0)), TEXT_BODY, x, cy, false, white);
                if (wave) drawWaveform(c, valueRight, cy, 12 * u, a);
                else if (!value.isEmpty()) addText(value, TEXT_BODY, valueRight, cy, true, valueColor);
            }
            case EXPANDED -> {
                double badgeY = top + 28 * u;
                boolean twoLines = !c.subtitle.isEmpty();

                drawBadge(c, left + 28 * u, badgeY, 18 * u, a);

                int valueRight = right - px(20);
                boolean wave = waveformInstead(c, c.value);
                double valueW = wave ? WAVE_W * u : textWidth(c.value, TEXT_VALUE);
                int x = left + px(56);
                double room = valueRight - x - (valueW > 0 ? valueW + px(16) : 0);

                addText(fit(c.title, TEXT_TITLE, room), TEXT_TITLE, x, twoLines ? top + 21 * u : badgeY, false, white);
                if (twoLines) addText(fit(c.subtitle, TEXT_SUBTITLE, room), TEXT_SUBTITLE, x, top + 37 * u, false, grey);
                if (wave) drawWaveform(c, valueRight, badgeY, 18 * u, a);
                else if (!c.value.isEmpty()) addText(c.value, TEXT_VALUE, valueRight, badgeY, true, valueColor);

                // The bar starts under the text, not at the edge of the card, and is no longer than a short bar: on a wide card
                // (a long subtitle) a bar over the whole width looks out of place
                if (c.progress >= 0) {
                    double barW = Math.min(valueRight - x, 150 * u);
                    drawBar(x, top + 58 * u, barW, 3.5 * u, accentColor, progress, a);
                }
            }
        }
    }

    private void drawSatellite(IslandCard c, double a, int left, int top, double progress) {
        double cy = top + 14 * u;
        double badgeX = left + 14 * u;
        int accentColor = c.accent | 0xFF000000;

        if (c.progress >= 0) {
            drawRing(badgeX, cy, 11 * u, 2 * u, progress, accentColor, a);
            drawBadge(c, badgeX, cy, 8 * u, a);
        } else {
            drawBadge(c, badgeX, cy, 9 * u, a);
        }

        // Same text column as the one line card, clear of the progress ring
        if (!c.chip.isEmpty()) addText(fit(c.chip, TEXT_BODY, px(52)), TEXT_BODY, left + px(31), cy, false, alpha(accentColor, a));
        else if (c.waveform) drawWaveform(c, left + (31 + WAVE_W) * u, cy, 12 * u, a);
    }

    /** Round badge with a tinted background and the glyph of the card. */
    private void drawBadge(IslandCard c, double cx, double cy, double radius, double a) {
        int accentColor = c.accent | 0xFF000000;

        // A cover fills the whole badge, cut to the same circle
        if (c.image != null && IslandImage.isAvailable()) {
            // The triangle fallback is drawn after the images, so it would cover them
            if (sdfOn) discShape(cx, cy, radius, alpha(0xFF000000, 0.5 * a));
            IslandImage.INSTANCE.add(c.image, cx, cy, radius, radius, radius, c.spin ? time * 0.45 : 0, alpha(0xFFFFFFFF, a));
            return;
        }

        discShape(cx, cy, radius, alpha(accentColor, 0.20 * a));
        drawGlyph(c, cx, cy, radius * 0.58, a);
    }

    private static boolean waveformInstead(IslandCard c, String value) {
        return c.waveform && value.isEmpty();
    }

    /** Five sound bars, right aligned at {@code rightX}. They move while the card is live and lie flat when not. */
    private void drawWaveform(IslandCard c, double rightX, double cy, double maxH, double a) {
        int acc = alpha(c.accent | 0xFF000000, a);
        double bw = 2 * u, gap = 2 * u;

        for (int i = 0; i < 5; i++) {
            double x = rightX - (4 - i) * (bw + gap) - bw / 2;
            double phase = time * (4.6 + i * 1.15) + i * 1.9;
            double level = c.waveformLive ? 0.3 + 0.7 * Math.abs(Math.sin(phase) * Math.cos(phase * 0.43 + i * 0.7)) : 0;
            double h = Math.max(bw, maxH * level);

            line(x, cy - h / 2 + bw / 2, x, cy + h / 2 - bw / 2, bw, acc);
        }
    }

    private void drawRing(double cx, double cy, double radius, double thickness, double progress, int accentColor, double a) {
        if (sdfOn) {
            sdf.arc(cx, cy, radius, thickness, 0, Math.PI * 2, alpha(0xFFFFFFFF, 0.14 * a));
            if (progress > 0.004) sdf.arc(cx, cy, radius, thickness, -Math.PI / 2, Math.PI * 2 * Math.min(progress, 1), alpha(accentColor, a));
            return;
        }

        arc(cx, cy, radius, thickness, 0, Math.PI * 2, alpha(0xFFFFFFFF, 0.14 * a));
        if (progress > 0.004) arc(cx, cy, radius, thickness, -Math.PI / 2, Math.PI * 2 * Math.min(progress, 1), alpha(accentColor, a));
    }

    /** Progress bar with a gradient, a glow at the leading end and a moving sheen. */
    private void drawBar(double x, double y, double w, double h, int accentColor, double progress, double a) {
        barShape(x, y, w, h, alpha(0xFFFFFFFF, 0.13 * a), alpha(0xFFFFFFFF, 0.13 * a));

        double fill = Math.max(h, w * Mth.clamp(progress, 0, 1));
        int start = alpha(accentColor, a);
        int end = alpha(mix(accentColor, 0xFFFFFFFF, 0.38), a);

        // Glow at the leading end
        glowShape(x + fill - h / 2, y + h / 2, h / 2, 6 * u, alpha(accentColor, 0.45 * a));

        barShape(x, y, fill, h, start, end);

        // Sheen: a soft band of light that travels along the filled part
        double innerStart = x + h / 2;
        double innerEnd = x + fill - h / 2;

        if (innerEnd - innerStart > 6 * u) {
            double band = 16 * u;
            double position = ((time * 0.55) % 1.6) - 0.3;
            double center = innerStart + (innerEnd - innerStart) * position;

            double edge = Mth.clamp(Math.min(center - innerStart, innerEnd - center) / band, 0, 1);
            double x0 = Math.max(center - band, innerStart);
            double x1 = Math.min(center + band, innerEnd);

            if (edge > 0.01 && x1 - x0 > 1) {
                int peak = alpha(0xFFFFFFFF, 0.38 * a * edge);
                int none = alpha(0xFFFFFFFF, 0);

                ensure(6, 12);
                int p0 = v(x0, y, none), p1 = v(center, y, peak), p2 = v(x1, y, none);
                int q0 = v(x0, y + h, none), q1 = v(center, y + h, peak), q2 = v(x1, y + h, none);
                quad(p0, p1, q1, q0);
                quad(p1, p2, q2, q1);
            }
        }
    }

    // Shapes that use the shader when it is there

    private void discShape(double cx, double cy, double r, int argb) {
        if (sdfOn) sdf.box(cx, cy, r, r, r, IslandSdf.FILL, 0, 0, argb, argb, 0);
        else disc(cx, cy, r, argb);
    }

    private void barShape(double x, double y, double w, double h, int from, int to) {
        if (sdfOn) {
            sdf.box(x + w / 2, y + h / 2, w / 2, h / 2, h / 2, IslandSdf.FILL_HORIZONTAL, 0, 0, from, to, 0);
            return;
        }

        path(x, y, w, h, h / 2);
        ensure(PATH_POINTS + 1, PATH_POINTS * 3);
        fan(from, to, true);
        ring(0, FEATHER, mix(from, to, 0.5), alpha(mix(from, to, 0.5), 0), 0);
    }

    private void glowShape(double cx, double cy, double r, double reach, int argb) {
        if (sdfOn) sdf.box(cx, cy, r, r, r, IslandSdf.GLOW, reach, 0, argb, argb, 0);
        else softDisc(cx, cy, r + reach, argb);
    }

    // Glyphs. g is half the size of the glyph in pixels.

    private void drawGlyph(IslandCard c, double cx, double cy, double g, double a) {
        int acc = alpha(c.accent | 0xFF000000, a);
        int dark = alpha(DARK, a);
        double th = Math.max(g * 0.30, 1.2);

        switch (c.icon) {
            case CHECK -> {
                line(cx - 0.52 * g, cy + 0.04 * g, cx - 0.14 * g, cy + 0.42 * g, th, acc);
                line(cx - 0.14 * g, cy + 0.42 * g, cx + 0.56 * g, cy - 0.42 * g, th, acc);
            }
            case CROSS -> {
                line(cx - 0.46 * g, cy - 0.46 * g, cx + 0.46 * g, cy + 0.46 * g, th, acc);
                line(cx + 0.46 * g, cy - 0.46 * g, cx - 0.46 * g, cy + 0.46 * g, th, acc);
            }
            case HEART -> {
                // Beats when it is a warning
                double beat = c.pulse ? 1 + 0.14 * Math.pow(Math.max(0, Math.sin(time * 6.5)), 3) : 1;
                double k = g * 1.08 * beat / 17.0;
                int n = 40;

                for (int i = 0; i < n; i++) {
                    double t = Math.PI * 2 * i / n;
                    gx[i] = cx + k * 16 * Math.pow(Math.sin(t), 3);
                    gy[i] = cy + g * 0.10 - k * (13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t));
                }

                // The fan starts from the middle of the heart
                polyFan(acc, cx, cy + g * 0.10 + k * 2, n);
            }
            case TARGET -> {
                double rot = time * 0.7;

                arc(cx, cy, 0.62 * g, Math.max(g * 0.16, 1.1), 0, Math.PI * 2, acc);

                for (int i = 0; i < 4; i++) {
                    double ang = rot + i * Math.PI / 2;
                    double ca = Math.cos(ang), sa = Math.sin(ang);

                    line(cx + ca * 0.84 * g, cy + sa * 0.84 * g, cx + ca * 1.08 * g, cy + sa * 1.08 * g, Math.max(g * 0.16, 1.1), acc);
                }

                disc(cx, cy, Math.max(g * 0.15, 0.9), acc);
            }
            case PLANE -> {
                // A paper plane that rocks gently
                double rot = Math.sin(time * 1.3) * 0.07;
                double cr = Math.cos(rot), sr = Math.sin(rot);

                double[][] pts = {{0.62, -0.62}, {-0.62, -0.12}, {-0.08, 0.10}, {0.12, 0.62}};
                double[] ox = new double[4], oy = new double[4];

                for (int i = 0; i < 4; i++) {
                    ox[i] = cx + (pts[i][0] * cr - pts[i][1] * sr) * g;
                    oy[i] = cy + (pts[i][0] * sr + pts[i][1] * cr) * g;
                }

                // Upper wing
                gx[0] = ox[0]; gy[0] = oy[0];
                gx[1] = ox[1]; gy[1] = oy[1];
                gx[2] = ox[2]; gy[2] = oy[2];
                polyFan(acc, (ox[0] + ox[1] + ox[2]) / 3, (oy[0] + oy[1] + oy[2]) / 3, 3);

                // Lower wing, a little darker
                gx[0] = ox[0]; gy[0] = oy[0];
                gx[1] = ox[2]; gy[1] = oy[2];
                gx[2] = ox[3]; gy[2] = oy[3];
                polyFan(mix(acc, dark, 0.28), (ox[0] + ox[2] + ox[3]) / 3, (oy[0] + oy[2] + oy[3]) / 3, 3);
            }
            case CLOCK -> {
                double hourAngle = clockHour / 12 * Math.PI * 2 - Math.PI / 2;
                double minuteAngle = clockMinute / 60 * Math.PI * 2 - Math.PI / 2;

                arc(cx, cy, 0.82 * g, Math.max(g * 0.16, 1.1), 0, Math.PI * 2, acc);
                line(cx, cy, cx + Math.cos(hourAngle) * 0.42 * g, cy + Math.sin(hourAngle) * 0.42 * g, Math.max(g * 0.17, 1.1), acc);
                line(cx, cy, cx + Math.cos(minuteAngle) * 0.62 * g, cy + Math.sin(minuteAngle) * 0.62 * g, Math.max(g * 0.14, 1.0), acc);
                disc(cx, cy, Math.max(g * 0.11, 0.9), acc);
            }
            case FPS -> {
                double bw = g * 0.30;
                double base = cy + 0.58 * g;
                double[] heights = {0.55, 1.05, 0.78};

                for (int i = 0; i < 3; i++) {
                    double x = cx + (i - 1) * 0.58 * g;
                    line(x, base - heights[i] * g + bw / 2, x, base - bw / 2, bw, acc);
                }
            }
            case PING -> {
                double bw = g * 0.24;
                double base = cy + 0.6 * g;
                double[] heights = {0.36, 0.64, 0.92, 1.2};

                for (int i = 0; i < 4; i++) {
                    double x = cx + (i - 1.5) * 0.42 * g;
                    boolean lit = i < c.level;

                    line(x, base - heights[i] * g + bw / 2, x, base - bw / 2, bw, lit ? acc : alpha(acc, 0.28));
                }
            }
            case SPEED -> {
                // Gauge: a 270 degree dial with a needle
                double start = Math.toRadians(135);
                double needle = start + Math.toRadians(270) * Mth.clamp(c.gauge, 0, 1);

                arc(cx, cy + 0.06 * g, 0.82 * g, Math.max(g * 0.16, 1.1), start, Math.toRadians(270), alpha(acc, 0.35));
                if (c.gauge > 0.02) arc(cx, cy + 0.06 * g, 0.82 * g, Math.max(g * 0.16, 1.1), start, needle - start, acc);

                line(cx, cy + 0.06 * g, cx + Math.cos(needle) * 0.6 * g, cy + 0.06 * g + Math.sin(needle) * 0.6 * g, Math.max(g * 0.17, 1.1), acc);
                disc(cx, cy + 0.06 * g, Math.max(g * 0.13, 1.0), acc);
            }
            case PIN -> {
                disc(cx, cy - 0.2 * g, 0.46 * g, acc);

                gx[0] = cx - 0.38 * g; gy[0] = cy - 0.02 * g;
                gx[1] = cx + 0.38 * g; gy[1] = cy - 0.02 * g;
                gx[2] = cx; gy[2] = cy + 0.74 * g;
                polyFan(acc, cx, cy + 0.2 * g, 3);

                disc(cx, cy - 0.2 * g, 0.17 * g, dark);
            }
            case FLAG -> {
                line(cx - 0.42 * g, cy - 0.68 * g, cx - 0.42 * g, cy + 0.68 * g, Math.max(g * 0.15, 1.1), acc);

                gx[0] = cx - 0.42 * g; gy[0] = cy - 0.68 * g;
                gx[1] = cx + 0.58 * g; gy[1] = cy - 0.30 * g;
                gx[2] = cx - 0.42 * g; gy[2] = cy + 0.08 * g;
                polyFan(acc, cx + 0.05 * g, cy - 0.30 * g, 3);
            }
            case WARNING -> {
                gx[0] = cx; gy[0] = cy - 0.72 * g;
                gx[1] = cx + 0.74 * g; gy[1] = cy + 0.52 * g;
                gx[2] = cx - 0.74 * g; gy[2] = cy + 0.52 * g;
                polyFan(acc, cx, cy + 0.1 * g, 3);

                line(cx, cy - 0.22 * g, cx, cy + 0.14 * g, Math.max(g * 0.15, 1.0), dark);
                disc(cx, cy + 0.34 * g, Math.max(g * 0.09, 0.8), dark);
            }
            case INFO -> {
                disc(cx, cy - 0.42 * g, Math.max(g * 0.12, 1.0), acc);
                line(cx, cy - 0.12 * g, cx, cy + 0.5 * g, Math.max(g * 0.17, 1.1), acc);
            }
            case FOOD -> {
                // An apple with a stem and a leaf
                disc(cx, cy + 0.14 * g, 0.6 * g, acc);
                line(cx, cy - 0.42 * g, cx + 0.1 * g, cy - 0.78 * g, Math.max(g * 0.13, 1.0), acc);
                line(cx + 0.14 * g, cy - 0.6 * g, cx + 0.46 * g, cy - 0.74 * g, Math.max(g * 0.2, 1.1), acc);
            }
            case NOTE -> {
                // A music note that sways with the beat
                double sway = Math.sin(time * 4) * 0.05 * g;

                disc(cx - 0.26 * g + sway, cy + 0.46 * g, 0.3 * g, acc);
                line(cx + 0.04 * g + sway, cy + 0.46 * g, cx + 0.04 * g + sway, cy - 0.7 * g, Math.max(g * 0.15, 1.0), acc);
                line(cx + 0.04 * g + sway, cy - 0.7 * g, cx + 0.5 * g + sway, cy - 0.42 * g, Math.max(g * 0.18, 1.1), acc);
            }
            case EYE -> {
                // Two arcs meet at the corners of the eye
                double radius = 0.9725 * g, drop = 0.4725 * g, half = Math.toRadians(60.9);
                double t = Math.max(g * 0.15, 1.0);

                arc(cx, cy + drop, radius, t, -Math.PI / 2 - half, half * 2, acc);
                arc(cx, cy - drop, radius, t, Math.PI / 2 - half, half * 2, acc);
                disc(cx, cy, 0.28 * g, acc);
            }
            case PAUSE -> {
                double t = Math.max(g * 0.3, 1.2);

                line(cx - 0.3 * g, cy - 0.5 * g, cx - 0.3 * g, cy + 0.5 * g, t, acc);
                line(cx + 0.3 * g, cy - 0.5 * g, cx + 0.3 * g, cy + 0.5 * g, t, acc);
            }
            case SHIELD -> {
                double[][] pts = {{0, -0.8}, {0.64, -0.6}, {0.6, 0.02}, {0.36, 0.5}, {0, 0.8}, {-0.36, 0.5}, {-0.6, 0.02}, {-0.64, -0.6}};

                for (int i = 0; i < pts.length; i++) {
                    gx[i] = cx + pts[i][0] * g;
                    gy[i] = cy + pts[i][1] * g;
                }

                polyFan(acc, cx, cy - 0.05 * g, pts.length);
                line(cx, cy - 0.5 * g, cx, cy + 0.5 * g, Math.max(g * 0.1, 0.9), alpha(dark, 0.55));
            }
            case CHAT -> {
                // A speech bubble with three dots
                int n = 28;

                for (int i = 0; i < n; i++) {
                    double t = Math.PI * 2 * i / n;
                    gx[i] = cx + Math.cos(t) * 0.82 * g;
                    gy[i] = cy - 0.1 * g + Math.sin(t) * 0.62 * g;
                }

                polyFan(acc, cx, cy - 0.1 * g, n);

                gx[0] = cx - 0.5 * g; gy[0] = cy + 0.3 * g;
                gx[1] = cx - 0.1 * g; gy[1] = cy + 0.45 * g;
                gx[2] = cx - 0.62 * g; gy[2] = cy + 0.8 * g;
                polyFan(acc, cx - 0.4 * g, cy + 0.5 * g, 3);

                for (int i = -1; i <= 1; i++) disc(cx + i * 0.36 * g, cy - 0.1 * g, Math.max(g * 0.1, 0.8), dark);
            }
            case PORTAL -> {
                // Two arcs that swirl in opposite directions
                arc(cx, cy, 0.78 * g, Math.max(g * 0.17, 1.1), time * 1.6, 4.4, acc);
                arc(cx, cy, 0.4 * g, Math.max(g * 0.15, 1.0), -time * 2.3, 3.8, alpha(acc, 0.75));
            }
            case TOTEM -> {
                disc(cx, cy - 0.5 * g, 0.26 * g, acc);

                gx[0] = cx - 0.3 * g; gy[0] = cy - 0.16 * g;
                gx[1] = cx + 0.3 * g; gy[1] = cy - 0.16 * g;
                gx[2] = cx + 0.22 * g; gy[2] = cy + 0.76 * g;
                gx[3] = cx - 0.22 * g; gy[3] = cy + 0.76 * g;
                polyFan(acc, cx, cy + 0.3 * g, 4);

                line(cx - 0.74 * g, cy - 0.02 * g, cx + 0.74 * g, cy - 0.02 * g, Math.max(g * 0.2, 1.1), acc);
            }
        }
    }

    // Geometry for the triangle renderer. Everything is in screen pixels.

    private void ensure(int vertices, int indices) {
        mesh.ensureCapacity(vertices, indices);
    }

    private int v(double x, double y, int argb) {
        tmp.set((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, argb >>> 24);
        int index = mesh.vec2(x, y).color(tmp).next();

        if (index < vxs.length) {
            vxs[index] = x;
            vys[index] = y;
        }

        return index;
    }

    /** Adds a triangle, flipping it when needed so it faces the screen (back faces are culled). */
    private void tri(int a, int b, int c) {
        if (a >= vxs.length || b >= vxs.length || c >= vxs.length) return;

        double cross = (vxs[b] - vxs[a]) * (vys[c] - vys[a]) - (vys[b] - vys[a]) * (vxs[c] - vxs[a]);
        if (Math.abs(cross) < 1e-9) return;

        // Front faces have a negative cross product here, because screen y points down
        if (cross > 0) mesh.triangle(a, c, b);
        else mesh.triangle(a, b, c);
    }

    private void quad(int a, int b, int c, int d) {
        tri(a, b, c);
        tri(c, d, a);
    }

    /** Builds the outline of a rounded rectangle, with the outward normal of every point. */
    private void path(double x, double y, double w, double h, double r) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));

        pathX = x;
        pathY = y;
        pathW = w;
        pathH = h;

        for (int c = 0; c < 4; c++) {
            // Corner centers: top right, bottom right, bottom left, top left
            double ccx = (c == 0 || c == 1) ? x + w - r : x + r;
            double ccy = (c == 1 || c == 2) ? y + h - r : y + r;

            for (int i = 0; i <= SEG; i++) {
                double angle = Math.toRadians(-90 + 90.0 * c + 90.0 * i / SEG);
                int k = c * (SEG + 1) + i;

                nx[k] = Math.cos(angle);
                ny[k] = Math.sin(angle);
                px[k] = ccx + nx[k] * r;
                py[k] = ccy + ny[k] * r;
            }
        }
    }

    /** Fills the last path, with a vertical or horizontal gradient. */
    private void fan(int from, int to, boolean horizontal) {
        int center = v(pathX + pathW / 2, pathY + pathH / 2, mix(from, to, 0.5));
        int first = -1, previous = -1;

        for (int i = 0; i < PATH_POINTS; i++) {
            double t = horizontal ? (px[i] - pathX) / Math.max(pathW, 1e-6) : (py[i] - pathY) / Math.max(pathH, 1e-6);
            int index = v(px[i], py[i], mix(from, to, t));

            if (previous >= 0) tri(center, previous, index);
            else first = index;

            previous = index;
        }

        tri(center, previous, first);
    }

    /**
     * Draws a band along the last path, from {@code d0} to {@code d1} pixels away from the edge (negative is inside).
     * The color goes from {@code inner} to {@code outer}. With a top bias the band is brighter at the top.
     */
    private void ring(double d0, double d1, int inner, int outer, double topBias) {
        ensure(PATH_POINTS * 2, PATH_POINTS * 6);

        for (int i = 0; i < PATH_POINTS; i++) {
            double bias = topBias == 0 ? 1 : (1 - topBias) + topBias * Math.max(0, -ny[i]);

            ringA[i] = v(px[i] + nx[i] * d0, py[i] + ny[i] * d0, alpha(inner, bias));
            ringB[i] = v(px[i] + nx[i] * d1, py[i] + ny[i] * d1, alpha(outer, bias));
        }

        for (int i = 0; i < PATH_POINTS; i++) {
            int j = (i + 1) % PATH_POINTS;
            quad(ringA[i], ringA[j], ringB[j], ringB[i]);
        }
    }

    /** A disc with a soft edge. */
    private void disc(double cx, double cy, double r, int argb) {
        if ((argb >>> 24) == 0 || r <= 0) return;

        int n = (int) Mth.clamp(Math.ceil(r * 1.4), 12, 48);
        ensure(1 + 2 * n, 9 * n);

        int center = v(cx, cy, argb);
        int[] in = new int[n], out = new int[n];
        int clear = alpha(argb, 0);

        for (int i = 0; i < n; i++) {
            double angle = Math.PI * 2 * i / n;
            double ca = Math.cos(angle), sa = Math.sin(angle);

            in[i] = v(cx + ca * r, cy + sa * r, argb);
            out[i] = v(cx + ca * (r + FEATHER), cy + sa * (r + FEATHER), clear);
        }

        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;

            tri(center, in[i], in[j]);
            quad(in[i], in[j], out[j], out[i]);
        }
    }

    /** A disc that fades from the given color in the middle to nothing at the edge. */
    private void softDisc(double cx, double cy, double r, int centerArgb) {
        if ((centerArgb >>> 24) == 0 || r <= 0) return;

        int n = 28;
        ensure(n + 1, 3 * n);

        int center = v(cx, cy, centerArgb);
        int clear = alpha(centerArgb, 0);
        int[] rim = new int[n];

        for (int i = 0; i < n; i++) {
            double angle = Math.PI * 2 * i / n;
            rim[i] = v(cx + Math.cos(angle) * r, cy + Math.sin(angle) * r, clear);
        }

        for (int i = 0; i < n; i++) tri(center, rim[i], rim[(i + 1) % n]);
    }

    /** A line with round ends and soft edges. */
    private void line(double x1, double y1, double x2, double y2, double thickness, int argb) {
        if ((argb >>> 24) == 0) return;

        double dx = x2 - x1, dy = y2 - y1;
        double length = Math.sqrt(dx * dx + dy * dy);

        if (length < 1e-6) {
            disc(x1, y1, thickness / 2, argb);
            return;
        }

        double ux = dx / length, uy = dy / length;
        double sx = -uy, sy = ux;
        double half = thickness / 2;
        int clear = alpha(argb, 0);

        ensure(12, 36);

        // Core and the soft edge on both sides
        int a1 = v(x1 + sx * half, y1 + sy * half, argb), a2 = v(x2 + sx * half, y2 + sy * half, argb);
        int b1 = v(x1 - sx * half, y1 - sy * half, argb), b2 = v(x2 - sx * half, y2 - sy * half, argb);
        int c1 = v(x1 + sx * (half + FEATHER), y1 + sy * (half + FEATHER), clear), c2 = v(x2 + sx * (half + FEATHER), y2 + sy * (half + FEATHER), clear);
        int d1 = v(x1 - sx * (half + FEATHER), y1 - sy * (half + FEATHER), clear), d2 = v(x2 - sx * (half + FEATHER), y2 - sy * (half + FEATHER), clear);

        quad(a1, a2, b2, b1);
        quad(a1, c1, c2, a2);
        quad(b1, d1, d2, b2);

        double heading = Math.atan2(uy, ux);
        cap(x2, y2, heading - Math.PI / 2, half, argb);
        cap(x1, y1, heading + Math.PI / 2, half, argb);
    }

    /** Half a disc, starting at the given angle. */
    private void cap(double cx, double cy, double angle0, double r, int argb) {
        int m = (int) Mth.clamp(Math.ceil(r * 0.9), 5, 16);
        ensure(1 + 2 * (m + 1), 9 * m);

        int center = v(cx, cy, argb);
        int clear = alpha(argb, 0);
        int[] in = new int[m + 1], out = new int[m + 1];

        for (int i = 0; i <= m; i++) {
            double angle = angle0 + Math.PI * i / m;
            double ca = Math.cos(angle), sa = Math.sin(angle);

            in[i] = v(cx + ca * r, cy + sa * r, argb);
            out[i] = v(cx + ca * (r + FEATHER), cy + sa * (r + FEATHER), clear);
        }

        for (int i = 0; i < m; i++) {
            tri(center, in[i], in[i + 1]);
            quad(in[i], in[i + 1], out[i + 1], out[i]);
        }
    }

    /** A curved band with round ends, used for progress rings and the outlines of icons. */
    private void arc(double cx, double cy, double radius, double thickness, double start, double sweep, int argb) {
        if ((argb >>> 24) == 0 || Math.abs(sweep) < 1e-4) return;

        int n = Math.max(4, (int) Math.ceil(Math.abs(sweep) / (Math.PI * 2 / 64)));
        double half = thickness / 2;
        int clear = alpha(argb, 0);

        ensure(4 * (n + 1), 18 * n);

        int[] inner = new int[n + 1], outer = new int[n + 1], featherOut = new int[n + 1], featherIn = new int[n + 1];

        for (int i = 0; i <= n; i++) {
            double angle = start + sweep * i / n;
            double ca = Math.cos(angle), sa = Math.sin(angle);

            inner[i] = v(cx + ca * (radius - half), cy + sa * (radius - half), argb);
            outer[i] = v(cx + ca * (radius + half), cy + sa * (radius + half), argb);
            featherOut[i] = v(cx + ca * (radius + half + FEATHER), cy + sa * (radius + half + FEATHER), clear);
            featherIn[i] = v(cx + ca * (radius - half - FEATHER), cy + sa * (radius - half - FEATHER), clear);
        }

        for (int i = 0; i < n; i++) {
            quad(inner[i], inner[i + 1], outer[i + 1], outer[i]);
            quad(outer[i], outer[i + 1], featherOut[i + 1], featherOut[i]);
            quad(featherIn[i], featherIn[i + 1], inner[i + 1], inner[i]);
        }

        // Round ends, unless it is a full circle
        if (Math.abs(sweep) < Math.PI * 2 - 0.05) {
            double endAngle = start + sweep;
            boolean forward = sweep > 0;

            // The caps bulge along the direction of the arc, at the end and (backwards) at the start
            cap(cx + Math.cos(endAngle) * radius, cy + Math.sin(endAngle) * radius, forward ? endAngle : endAngle - Math.PI, half, argb);
            cap(cx + Math.cos(start) * radius, cy + Math.sin(start) * radius, forward ? start + Math.PI : start, half, argb);
        }
    }

    /** Fills the polygon in {@code gx}/{@code gy} (star shaped around the given point) with a soft edge. */
    private void polyFan(int argb, double cx, double cy, int count) {
        if ((argb >>> 24) == 0) return;

        ensure(1 + 2 * count, 9 * count);

        int center = v(cx, cy, argb);
        int clear = alpha(argb, 0);
        int[] in = new int[count], out = new int[count];

        for (int i = 0; i < count; i++) {
            double dx = gx[i] - cx, dy = gy[i] - cy;
            double length = Math.max(Math.sqrt(dx * dx + dy * dy), 1e-6);

            in[i] = v(gx[i], gy[i], argb);
            out[i] = v(gx[i] + dx / length * FEATHER, gy[i] + dy / length * FEATHER, clear);
        }

        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;

            tri(center, in[i], in[j]);
            quad(in[i], in[j], out[j], out[i]);
        }
    }

    // Text. It is collected while drawing and drawn after the shapes, so it is always on top.

    /** {@code x} is the left edge, or the right edge when aligned right, in whole screen pixels. */
    private void addText(String text, double sizeUnits, int x, double centerY, boolean alignRight, int argb) {
        if (text == null || text.isEmpty() || textCount >= MAX_TEXT || (argb >>> 24) < 8) return;

        int i = textCount++;

        textString[i] = text;
        textSize[i] = sizeUnits;
        textX[i] = x;
        textCy[i] = centerY;
        textRight[i] = alignRight;
        textArgb[i] = argb;
    }

    private void flushText() {
        if (textCount == 0) return;

        TextRenderer tr = TextRenderer.get();
        boolean vanilla = tr instanceof VanillaTextRenderer;
        GuiGraphicsExtractor g = graphics;

        g.nextStratum();
        g.pose().pushMatrix();
        g.pose().scale(1.0f / guiScale);

        try {
            for (int i = 0; i < textCount; i++) {
                String text = textString[i];
                int argb = textArgb[i];

                if (vanilla) {
                    // The font is drawn at a whole number of screen pixels per font pixel, at a whole pixel position,
                    // so every letter lands exactly on the pixel grid and moves together with the pill
                    int step = step(textSize[i]);
                    int width = Math.max(0, mc.font.width(text) - 1) * step;
                    int x = textRight[i] ? textX[i] - width : textX[i];

                    // Letters fill rows 0 to 7 of the 9 pixel line, CJK glyphs sit a little lower
                    double middle = hasWideGlyphs(text) ? 4 : 3.5;
                    int y = (int) Math.round(textCy[i] - middle * step);

                    g.pose().pushMatrix();
                    g.pose().translate(x, y);
                    g.pose().scale(step, step);
                    g.text(mc.font, text, 0, 0, argb, false);
                    g.pose().popMatrix();
                } else {
                    tr.begin(g, textSize[i] * u / 18.0);

                    try {
                        double width = tr.getWidth(text);
                        double x = textRight[i] ? textX[i] - Math.round(width) : textX[i];

                        tmp.set((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, argb >>> 24);
                        tr.render(text, x, Math.round(textCy[i] - tr.getHeight() / 2), tmp, false);
                    } finally {
                        tr.end();
                    }
                }
            }
        } finally {
            g.pose().popMatrix();
            g.nextStratum();
        }
    }

    private static boolean hasWideGlyphs(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x2E80) return true;
        }

        return false;
    }

    // Colors and small helpers

    private static int argb(int r, int g, int b, int a) {
        return (Mth.clamp(a, 0, 255) << 24) | (Mth.clamp(r, 0, 255) << 16) | (Mth.clamp(g, 0, 255) << 8) | Mth.clamp(b, 0, 255);
    }

    /** Multiplies the alpha of a color. */
    private static int alpha(int argb, double factor) {
        int a = (int) Mth.clamp((argb >>> 24) * factor, 0, 255);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Blends two colors, alpha included. */
    public static int mix(int from, int to, double t) {
        t = Mth.clamp(t, 0, 1);

        int a = (int) Math.round(Mth.lerp(t, from >>> 24, to >>> 24));
        int r = (int) Math.round(Mth.lerp(t, (from >> 16) & 0xFF, (to >> 16) & 0xFF));
        int g = (int) Math.round(Mth.lerp(t, (from >> 8) & 0xFF, (to >> 8) & 0xFF));
        int b = (int) Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static double smooth(double t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Formats seconds as m:ss or h:mm:ss. */
    public static String time(double seconds) {
        int total = (int) Math.min(Math.max(seconds, 0), 99 * 3600 + 59 * 60 + 59);
        int h = total / 3600;
        int m = total % 3600 / 60;
        int s = total % 60;
        return h > 0 ? "%d:%02d:%02d".formatted(h, m, s) : "%d:%02d".formatted(m, s);
    }
}
