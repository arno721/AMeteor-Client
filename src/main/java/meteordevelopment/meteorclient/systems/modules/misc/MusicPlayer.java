/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.Texture;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.render.DynamicIsland;
import meteordevelopment.meteorclient.systems.modules.render.island.IslandCard;
import meteordevelopment.meteorclient.systems.modules.render.island.IslandImage;
import meteordevelopment.meteorclient.systems.modules.render.island.IslandSdf;
import meteordevelopment.meteorclient.systems.modules.render.island.IslandSource;
import meteordevelopment.meteorclient.systems.modules.render.island.PixelText;
import meteordevelopment.meteorclient.systems.modules.render.island.PixelText.Align;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.i18n.LanguageManager;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.music.Artwork;
import meteordevelopment.meteorclient.utils.music.Lyrics;
import meteordevelopment.meteorclient.utils.music.LyricsService;
import meteordevelopment.meteorclient.utils.music.MediaBridge;
import meteordevelopment.meteorclient.utils.music.MediaState;
import meteordevelopment.meteorclient.utils.music.MusicStarscript;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows what is playing on the computer (Spotify, a browser, any player that shows up in the Windows media flyout):
 * title, artist, album, the cover, the time and synced lyrics. Can control the player with keys, and feeds the
 * Dynamic Island. All the text is made of Starscript templates, so it can show anything in any order.
 */
public class MusicPlayer extends Module implements IslandSource {
    public enum Style {
        Card,
        Bar,
        Lyrics,
        Text
    }

    public enum Anchor {
        TopLeft,
        TopCenter,
        TopRight,
        MiddleLeft,
        MiddleRight,
        BottomLeft,
        BottomCenter,
        BottomRight
    }

    public enum TimeLabel {
        Total,
        Remaining,
        Hidden
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgControls = settings.createGroup("Controls");
    private final SettingGroup sgOverlay = settings.createGroup("Overlay");
    private final SettingGroup sgColors = settings.createGroup("Colors");
    private final SettingGroup sgLyrics = settings.createGroup("Lyrics");
    private final SettingGroup sgIsland = settings.createGroup("Dynamic Island");

    // General

    private final Setting<String> preferredApp = sgGeneral.add(new StringSetting.Builder()
        .name("preferred-app")
        .description("Only follows the player whose name contains this, for example spotify, chrome or msedge. Empty follows the one Windows shows.")
        .defaultValue("")
        .onChanged(app -> MediaBridge.INSTANCE.setPreferredApp(app))
        .build()
    );

    private final Setting<Boolean> hideWhenPaused = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-when-paused")
        .description("Hides the overlay a moment after the music is paused.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> hideDelay = sgGeneral.add(new DoubleSetting.Builder()
        .name("hide-delay")
        .description("Seconds after pausing before the overlay hides.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 30)
        .visible(hideWhenPaused::get)
        .build()
    );

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("announce-songs")
        .description("Writes the new song in your chat (only you can see it) when it changes.")
        .defaultValue(false)
        .build()
    );

    // Controls

    private final Setting<Keybind> playPauseKey = sgControls.add(new KeybindSetting.Builder()
        .name("play-pause")
        .description("Plays or pauses the music.")
        .defaultValue(Keybind.none())
        .action(() -> MediaBridge.INSTANCE.togglePlayPause())
        .build()
    );

    private final Setting<Keybind> nextKey = sgControls.add(new KeybindSetting.Builder()
        .name("next")
        .description("Skips to the next song.")
        .defaultValue(Keybind.none())
        .action(() -> MediaBridge.INSTANCE.next())
        .build()
    );

    private final Setting<Keybind> previousKey = sgControls.add(new KeybindSetting.Builder()
        .name("previous")
        .description("Goes back to the previous song.")
        .defaultValue(Keybind.none())
        .action(() -> MediaBridge.INSTANCE.previous())
        .build()
    );

    private final Setting<Integer> seekStep = sgControls.add(new IntSetting.Builder()
        .name("seek-step")
        .description("Seconds to jump with the seek keys.")
        .defaultValue(10)
        .range(1, 120)
        .sliderRange(5, 30)
        .build()
    );

    private final Setting<Keybind> forwardKey = sgControls.add(new KeybindSetting.Builder()
        .name("seek-forward")
        .description("Jumps forward in the song.")
        .defaultValue(Keybind.none())
        .action(() -> seekBy(seekStep.get()))
        .build()
    );

    private final Setting<Keybind> backKey = sgControls.add(new KeybindSetting.Builder()
        .name("seek-back")
        .description("Jumps back in the song.")
        .defaultValue(Keybind.none())
        .action(() -> seekBy(-seekStep.get()))
        .build()
    );

    // Overlay

    private final Setting<Boolean> overlay = sgOverlay.add(new BoolSetting.Builder()
        .name("overlay")
        .description("Shows the player on the screen.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Style> style = sgOverlay.add(new EnumSetting.Builder<Style>()
        .name("style")
        .description("Card: cover, text, time and lyrics. Bar: one slim pill. Lyrics: big lyrics only. Text: just the lines, no background.")
        .defaultValue(Style.Card)
        .build()
    );

    private final Setting<Anchor> anchor = sgOverlay.add(new EnumSetting.Builder<Anchor>()
        .name("anchor")
        .description("Which corner or edge of the screen the overlay sticks to.")
        .defaultValue(Anchor.BottomRight)
        .build()
    );

    private final Setting<Integer> xOffset = sgOverlay.add(new IntSetting.Builder()
        .name("x-offset")
        .description("Distance from the side of the screen, in GUI pixels.")
        .defaultValue(8)
        .range(-2000, 2000)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Integer> yOffset = sgOverlay.add(new IntSetting.Builder()
        .name("y-offset")
        .description("Distance from the top or bottom of the screen, in GUI pixels.")
        .defaultValue(8)
        .range(-2000, 2000)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Double> scale = sgOverlay.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the overlay. It follows the GUI scale up to 4.")
        .defaultValue(0.8)
        .min(0.3)
        .sliderRange(0.4, 2)
        .build()
    );

    private final Setting<Integer> width = sgOverlay.add(new IntSetting.Builder()
        .name("width")
        .description("Width of the overlay, in design units.")
        .defaultValue(210)
        .range(100, 600)
        .sliderRange(140, 360)
        .build()
    );

    private final Setting<List<String>> lines = sgOverlay.add(new StringListSetting.Builder()
        .name("lines")
        .description("The lines of text, in order. Starscript, for example {music.title}, {music.artist}, {music.album}, {music.app}, {player} or {fps}. The first line is the big one. Empty lines are skipped.")
        .defaultValue("{music.title}", "{music.artist}", "{music.album}")
        .build()
    );

    private final Setting<Boolean> artwork = sgOverlay.add(new BoolSetting.Builder()
        .name("artwork")
        .description("Shows the album cover.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> spinArtwork = sgOverlay.add(new BoolSetting.Builder()
        .name("spin-artwork")
        .description("Turns the round cover of the Bar style like a record while playing.")
        .defaultValue(true)
        .visible(() -> style.get() == Style.Bar && artwork.get())
        .build()
    );

    private final Setting<Boolean> progressBar = sgOverlay.add(new BoolSetting.Builder()
        .name("progress-bar")
        .description("Shows how far the song is.")
        .defaultValue(true)
        .build()
    );

    private final Setting<TimeLabel> timeLabel = sgOverlay.add(new EnumSetting.Builder<TimeLabel>()
        .name("time-label")
        .description("What the time on the right shows.")
        .defaultValue(TimeLabel.Total)
        .build()
    );

    private final Setting<Boolean> soundBars = sgOverlay.add(new BoolSetting.Builder()
        .name("sound-bars")
        .description("Shows little moving sound bars in the corner while it plays.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> marquee = sgOverlay.add(new BoolSetting.Builder()
        .name("scroll-long-text")
        .description("Long lines scroll from side to side instead of being cut.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> shadow = sgOverlay.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Soft shadow below the overlay.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> border = sgOverlay.add(new BoolSetting.Builder()
        .name("border")
        .description("Thin light outline, brighter on top.")
        .defaultValue(true)
        .build()
    );

    // Colors

    private final Setting<Boolean> artworkColors = sgColors.add(new BoolSetting.Builder()
        .name("cover-colors")
        .description("Takes the accent color from the album cover.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> accent = sgColors.add(new ColorSetting.Builder()
        .name("accent")
        .description("Accent color, when the cover does not give one.")
        .defaultValue(new SettingColor(244, 114, 182))
        .build()
    );

    private final Setting<SettingColor> background = sgColors.add(new ColorSetting.Builder()
        .name("background")
        .description("Color of the overlay.")
        .defaultValue(new SettingColor(14, 14, 18, 232))
        .build()
    );

    private final Setting<Double> coverTint = sgColors.add(new DoubleSetting.Builder()
        .name("cover-tint")
        .description("How much the background takes the color of the cover.")
        .defaultValue(0.22)
        .range(0, 1)
        .sliderRange(0, 0.6)
        .build()
    );

    private final Setting<SettingColor> textColor = sgColors.add(new ColorSetting.Builder()
        .name("text")
        .description("Color of the first line.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> secondaryColor = sgColors.add(new ColorSetting.Builder()
        .name("secondary-text")
        .description("Color of the other lines and the time.")
        .defaultValue(new SettingColor(170, 170, 180))
        .build()
    );

    // Lyrics

    private final Setting<Boolean> lyrics = sgLyrics.add(new BoolSetting.Builder()
        .name("lyrics")
        .description("Shows synced lyrics.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlineLyrics = sgLyrics.add(new BoolSetting.Builder()
        .name("online-lyrics")
        .description("Looks up lyrics on lrclib.net (sends the title, artist and album there). Found lyrics are kept on disk.")
        .defaultValue(true)
        .visible(lyrics::get)
        .build()
    );

    private final Setting<Boolean> localLyrics = sgLyrics.add(new BoolSetting.Builder()
        .name("local-lyrics")
        .description("Uses your own .lrc or .txt files from meteor-client/music/lyrics, named \"Artist - Title.lrc\" or \"Title.lrc\".")
        .defaultValue(true)
        .visible(lyrics::get)
        .build()
    );

    private final Setting<Double> lyricOffset = sgLyrics.add(new DoubleSetting.Builder()
        .name("lyric-offset")
        .description("Shows the lyrics earlier (positive) or later (negative), in seconds.")
        .defaultValue(0.2)
        .range(-10, 10)
        .sliderRange(-3, 3)
        .visible(lyrics::get)
        .build()
    );

    private final Setting<Integer> linesBefore = sgLyrics.add(new IntSetting.Builder()
        .name("lines-before")
        .description("Lyric lines shown before the current one.")
        .defaultValue(1)
        .range(0, 5)
        .sliderRange(0, 3)
        .visible(lyrics::get)
        .build()
    );

    private final Setting<Integer> linesAfter = sgLyrics.add(new IntSetting.Builder()
        .name("lines-after")
        .description("Lyric lines shown after the current one.")
        .defaultValue(1)
        .range(0, 5)
        .sliderRange(0, 3)
        .visible(lyrics::get)
        .build()
    );

    private final Setting<Double> lyricSize = sgLyrics.add(new DoubleSetting.Builder()
        .name("lyric-size")
        .description("Size of the current lyric line.")
        .defaultValue(10)
        .range(5, 30)
        .sliderRange(7, 16)
        .visible(lyrics::get)
        .build()
    );

    private final Setting<Boolean> karaoke = sgLyrics.add(new BoolSetting.Builder()
        .name("karaoke")
        .description("Fills the current line with color as it is sung.")
        .defaultValue(true)
        .visible(lyrics::get)
        .build()
    );

    private final Setting<Boolean> smoothScroll = sgLyrics.add(new BoolSetting.Builder()
        .name("smooth-scroll")
        .description("Lyric lines slide up instead of jumping.")
        .defaultValue(true)
        .visible(lyrics::get)
        .build()
    );

    private final Setting<Boolean> lyricStatus = sgLyrics.add(new BoolSetting.Builder()
        .name("lyric-status")
        .description("Says when lyrics are being searched or were not found.")
        .defaultValue(true)
        .visible(lyrics::get)
        .build()
    );

    private final Setting<SettingColor> lyricColor = sgLyrics.add(new ColorSetting.Builder()
        .name("lyric-color")
        .description("Color of the sung part of the current line. Uses the accent when the alpha is 0.")
        .defaultValue(new SettingColor(255, 255, 255, 0))
        .visible(lyrics::get)
        .build()
    );

    private final Setting<SettingColor> lyricDimColor = sgLyrics.add(new ColorSetting.Builder()
        .name("lyric-dim-color")
        .description("Color of the other lyric lines.")
        .defaultValue(new SettingColor(200, 200, 210, 150))
        .visible(lyrics::get)
        .build()
    );

    // Dynamic Island

    private final Setting<Boolean> island = sgIsland.add(new BoolSetting.Builder()
        .name("island")
        .description("Shows the music on the Dynamic Island.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> islandPriority = sgIsland.add(new IntSetting.Builder()
        .name("priority")
        .description("How important the music is next to other activities. Combat is 80, flights 60, modes 40.")
        .defaultValue(50)
        .range(0, 150)
        .sliderRange(0, 120)
        .visible(island::get)
        .build()
    );

    private final Setting<Boolean> islandWhenPaused = sgIsland.add(new BoolSetting.Builder()
        .name("show-when-paused")
        .description("Keeps the music on the island while paused.")
        .defaultValue(true)
        .visible(island::get)
        .build()
    );

    private final Setting<Boolean> islandExpand = sgIsland.add(new BoolSetting.Builder()
        .name("expand-on-new-song")
        .description("Opens the island in full when the song changes.")
        .defaultValue(true)
        .visible(island::get)
        .build()
    );

    private final Setting<Boolean> islandArtwork = sgIsland.add(new BoolSetting.Builder()
        .name("cover-badge")
        .description("Shows the album cover in the round badge.")
        .defaultValue(true)
        .visible(island::get)
        .build()
    );

    private final Setting<Boolean> islandSpin = sgIsland.add(new BoolSetting.Builder()
        .name("spin-cover")
        .description("Turns the cover like a record while playing.")
        .defaultValue(true)
        .visible(() -> island.get() && islandArtwork.get())
        .build()
    );

    private final Setting<Boolean> islandWaveform = sgIsland.add(new BoolSetting.Builder()
        .name("sound-bars")
        .description("Shows moving sound bars where a value template is empty.")
        .defaultValue(true)
        .visible(island::get)
        .build()
    );

    private final Setting<Boolean> islandProgress = sgIsland.add(new BoolSetting.Builder()
        .name("progress")
        .description("Shows the song progress as a bar and a ring.")
        .defaultValue(true)
        .visible(island::get)
        .build()
    );

    private final Setting<Boolean> islandLyrics = sgIsland.add(new BoolSetting.Builder()
        .name("lyrics-as-subtitle")
        .description("Shows the current lyric line under the title instead of the subtitle template.")
        .defaultValue(true)
        .visible(island::get)
        .build()
    );

    private final Setting<String> islandTitle = sgIsland.add(new StringSetting.Builder()
        .name("title")
        .description("Starscript for the title of the full card.")
        .defaultValue("{music.title}")
        .visible(island::get)
        .build()
    );

    private final Setting<String> islandSubtitle = sgIsland.add(new StringSetting.Builder()
        .name("subtitle")
        .description("Starscript for the line under the title.")
        .defaultValue("{music.artist}")
        .visible(island::get)
        .build()
    );

    private final Setting<String> islandValue = sgIsland.add(new StringSetting.Builder()
        .name("value")
        .description("Starscript for the right side of the full card. Empty shows sound bars.")
        .defaultValue("")
        .visible(island::get)
        .build()
    );

    private final Setting<String> islandCompactTitle = sgIsland.add(new StringSetting.Builder()
        .name("compact-title")
        .description("Starscript for the one line card.")
        .defaultValue("{music.title}")
        .visible(island::get)
        .build()
    );

    private final Setting<String> islandCompactValue = sgIsland.add(new StringSetting.Builder()
        .name("compact-value")
        .description("Starscript for the right side of the one line card. Empty shows sound bars.")
        .defaultValue("")
        .visible(island::get)
        .build()
    );

    private final Setting<String> islandChip = sgIsland.add(new StringSetting.Builder()
        .name("small-pill")
        .description("Starscript for the small second pill. Empty shows sound bars.")
        .defaultValue("")
        .visible(island::get)
        .build()
    );

    // State

    private MediaState state = MediaState.NONE;
    private boolean acquired;
    private String lastTrackKey = "";
    private long pausedSinceNanos;
    private boolean wasPlaying;

    private final IslandSdf sdf = new IslandSdf();
    private final PixelText text = new PixelText();
    private boolean sdfOn;
    private double u;
    private int guiScale;
    private double time, overlayAlpha, contentAlpha = 1, panelHeight;
    private long trackChangedNanos;
    private int accentDisplay = 0xFFF472B6;

    private int shownLyric = -1;
    private double lyricScroll;

    private final List<String> lineTexts = new ArrayList<>();

    public MusicPlayer() {
        super(Categories.Misc, "music-player", "Shows the song playing on your computer with its cover and synced lyrics, and controls it.");
    }

    @Override
    public void onActivate() {
        if (!MediaBridge.isSupported()) {
            error("The music player only works on Windows.");
            toggle();
            return;
        }

        MediaBridge.INSTANCE.setPreferredApp(preferredApp.get());

        if (!acquired) {
            MediaBridge.INSTANCE.acquire();
            acquired = true;
        }
        lastTrackKey = "";
        overlayAlpha = 0;
        panelHeight = 0;
        shownLyric = -1;
    }

    @Override
    public void onDeactivate() {
        if (acquired) {
            MediaBridge.INSTANCE.release();
            acquired = false;
        }

        Artwork.INSTANCE.clear();
    }

    @Override
    public String getInfoString() {
        return state.hasTrack() ? state.status : null;
    }

    private void seekBy(double seconds) {
        MediaState s = MediaBridge.INSTANCE.getState();
        if (s.hasTrack()) MediaBridge.INSTANCE.seek(s.positionNow() + seconds);
    }

    /** Looks up the lyrics of the current song again. */
    public void reloadLyrics() {
        LyricsService.INSTANCE.reload(MediaBridge.INSTANCE.getState(), localLyrics.get(), onlineLyrics.get());
    }

    // Ticking

    @EventHandler
    private void onTick(TickEvent.Post event) {
        MediaBridge bridge = MediaBridge.INSTANCE;
        bridge.ensureRunning();
        MusicStarscript.lyricOffset = lyricOffset.get();

        state = bridge.getState();

        if (lyrics.get() || island.get()) LyricsService.INSTANCE.request(state, localLyrics.get(), onlineLyrics.get());

        String key = state.hasTrack() ? state.trackKey() : "";

        if (!key.equals(lastTrackKey)) {
            if (!key.isEmpty()) {
                trackChangedNanos = System.nanoTime();
                if (announce.get() && Utils.canUpdate()) info("%s (highlight)%s(default)", tr("now-playing", "Now playing:"), MusicStarscript.nowPlaying());
            }

            lastTrackKey = key;
        }

        boolean playing = state.isPlaying();
        if (wasPlaying && !playing) pausedSinceNanos = System.nanoTime();
        wasPlaying = playing;
    }

    // Dynamic Island

    @Override
    public boolean fillIslandCard(IslandCard c) {
        if (!island.get() || !state.hasTrack()) return false;
        if (!state.isPlaying() && !islandWhenPaused.get()) return false;

        boolean playing = state.isPlaying();

        c.reset(islandExpand.get() ? "music:" + state.trackKey() : "music", islandPriority.get());
        c.expand = islandExpand.get();
        c.icon = DynamicIsland.Icon.NOTE;
        c.accent = accentColor();
        c.title = MusicStarscript.run(islandTitle.get());
        c.subtitle = MusicStarscript.run(islandSubtitle.get());
        c.value = MusicStarscript.run(islandValue.get());
        c.compactTitle = MusicStarscript.run(islandCompactTitle.get());
        c.compactValue = MusicStarscript.run(islandCompactValue.get());
        c.chip = MusicStarscript.run(islandChip.get());

        if (c.title.isEmpty()) c.title = state.title;
        if (islandLyrics.get()) {
            String lyric = MusicStarscript.lyric(0);
            if (!lyric.isEmpty()) c.subtitle = lyric;
        }

        if (!playing && c.value.isEmpty() && !islandWaveform.get()) c.value = tr("paused", "Paused");

        if (islandProgress.get() && state.duration() > 0) c.progress = state.progress();

        if (islandArtwork.get()) {
            c.image = Artwork.INSTANCE.getTexture();
            c.spin = islandSpin.get() && playing;
        }

        c.waveform = islandWaveform.get();
        c.waveformLive = playing;
        return true;
    }

    private int accentColor() {
        if (artworkColors.get() && Artwork.INSTANCE.hasColors()) return Artwork.INSTANCE.getAccent();
        return accent.get().getPacked() | 0xFF000000;
    }

    // Drawing

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        // The cover is also used by the island, so it is kept up to date even without the overlay
        Artwork.INSTANCE.update();

        double dt = Mth.clamp(event.frameTime, 0, 0.05);
        time += dt;

        state = MediaBridge.INSTANCE.getState();

        boolean paused = !state.isPlaying();
        boolean hiddenByPause = paused && hideWhenPaused.get() && (System.nanoTime() - pausedSinceNanos) / 1e9 > hideDelay.get();
        boolean visible = overlay.get() && state.hasTrack() && !hiddenByPause && Utils.canUpdate()
            && !mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden;

        overlayAlpha += ((visible ? 1 : 0) - overlayAlpha) * (1 - Math.exp(-dt * 10));
        contentAlpha = smooth((System.nanoTime() - trackChangedNanos) / 1e9 / 0.35);
        accentDisplay = DynamicIsland.mix(accentDisplay, accentColor(), 1 - Math.exp(-dt * 6));

        if (overlayAlpha < 0.01) {
            panelHeight = 0;
            return;
        }

        guiScale = mc.getWindow().getGuiScale();
        u = Math.min(guiScale, 4) * scale.get();
        sdfOn = IslandSdf.isAvailable();

        text.begin(event.graphics, guiScale, u);
        Renderer2D r2 = Renderer2D.COLOR;
        r2.begin();
        if (sdfOn) sdf.begin();

        try {
            switch (style.get()) {
                case Card -> drawCard(dt);
                case Bar -> drawBar(dt);
                case Lyrics -> drawLyricsStyle(dt);
                case Text -> drawTextStyle(dt);
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
                IslandSdf.markBroken();
            }
        }

        IslandImage.INSTANCE.flush();
        r2.render();
        text.flush();
    }

    /** Works out where the panel goes, given its size in screen pixels. Returns {x, y}. */
    private int[] place(int w, int h) {
        int sw = mc.getWindow().getWidth(), sh = mc.getWindow().getHeight();
        int mx = xOffset.get() * guiScale, my = yOffset.get() * guiScale;

        int x = switch (anchor.get()) {
            case TopLeft, MiddleLeft, BottomLeft -> mx;
            case TopCenter, BottomCenter -> (sw - w) / 2 + mx;
            case TopRight, MiddleRight, BottomRight -> sw - w - mx;
        };

        int y = switch (anchor.get()) {
            case TopLeft, TopCenter, TopRight -> my;
            case MiddleLeft, MiddleRight -> (sh - h) / 2 + my;
            case BottomLeft, BottomCenter, BottomRight -> sh - h - my;
        };

        return new int[]{x, y};
    }

    /** The panel height follows its content smoothly, so lyrics coming and going do not make it jump. */
    private int animatedHeight(double target, double dt) {
        if (panelHeight <= 0) panelHeight = target;
        else panelHeight += (target - panelHeight) * (1 - Math.exp(-dt * 12));
        if (Math.abs(target - panelHeight) < 0.5) panelHeight = target;
        return (int) Math.round(panelHeight);
    }

    private int px(double units) {
        return (int) Math.round(units * u);
    }

    private void evaluateLines() {
        lineTexts.clear();

        for (String template : lines.get()) {
            String line = MusicStarscript.run(template);
            if (!line.isEmpty()) lineTexts.add(line);
        }
    }

    // Card: cover on the left, lines next to it, the time below, lyrics at the bottom

    private void drawCard(double dt) {
        double a = overlayAlpha;
        evaluateLines();

        int w = px(width.get());
        double pad = 10, cover = 56;
        boolean showCover = artwork.get();
        boolean showLyrics = lyrics.get() && hasLyricContent();

        // Height of the whole thing, in units
        double textBlock = lineTexts.isEmpty() ? 0 : 14 + (lineTexts.size() - 1) * 12;
        double topRow = showCover ? Math.max(cover, textBlock) : textBlock;
        double y = pad + topRow;
        double barY = 0, lyricsY = 0;

        if (progressBar.get() && state.duration() > 0) {
            barY = y + 10;
            y = barY + 4 + (timeLabel.get() != TimeLabel.Hidden ? 14 : 0);
        }

        int lyricCount = 1 + linesBefore.get() + linesAfter.get();
        double spacing = lyricSize.get() * 1.55;

        if (showLyrics) {
            lyricsY = y + 8;
            y = lyricsY + lyricCount * spacing;
        }

        int h = animatedHeight((y + pad) * u, dt);
        int[] pos = place(w, h);
        int left = pos[0], top = pos[1], right = left + w;
        double radius = 20 * u;

        panel(left, top, w, h, radius, a);

        double ca = a * contentAlpha;
        int accentColor = accentDisplay;

        // Cover, concentric with the corner: radius 10 inside a radius 20 corner with 10 of padding
        if (showCover) drawCover(left + pad * u, top + pad * u, cover * u, 10 * u, 0, ca, accentColor);

        int textX = left + px(showCover ? pad + cover + 10 : pad + 2);
        int textRight = right - px(pad + 2);
        double blockTop = top + (pad + (showCover ? Math.max(0, (cover - textBlock) / 2) : 0)) * u;

        // Sound bars in the top right corner, on the line of the title
        if (soundBars.get()) {
            drawBars(right - px(14), blockTop + 7 * u, 9 * u, accentColor, ca, state.isPlaying());
            textRight -= px(18);
        }

        for (int i = 0; i < lineTexts.size(); i++) {
            boolean first = i == 0;
            double size = first ? 10.5 : 8;
            double cy = blockTop + (first ? 7 : 14 + (i - 1) * 12 + 6) * u;
            int color = first ? color(textColor.get(), ca) : color(secondaryColor.get(), ca);

            line(lineTexts.get(i), size, textX, textRight, cy, color, i);
        }

        if (barY > 0) {
            int barLeft = left + px(pad), barRight = right - px(pad);
            drawProgress(barLeft, top + barY * u, barRight - barLeft, 4 * u, accentColor, ca);

            if (timeLabel.get() != TimeLabel.Hidden) {
                double ty = top + (barY + 4 + 8) * u;
                int secondary = color(secondaryColor.get(), ca);

                text.add(MusicStarscript.time(state.positionNow()), 7, barLeft, ty, Align.LEFT, secondary);
                text.add(rightTime(), 7, barRight, ty, Align.RIGHT, secondary);
            }
        }

        if (showLyrics) drawLyricLines(left + w / 2, left + px(pad), right - px(pad), top + lyricsY * u, spacing, ca, accentColor, true, dt);
    }

    // Bar: one slim pill, round cover with the progress around it

    private void drawBar(double dt) {
        double a = overlayAlpha;
        evaluateLines();

        int w = px(width.get());
        int h = px(36);
        animatedHeight(h, dt);
        int[] pos = place(w, h);
        int left = pos[0], top = pos[1], right = left + w;

        panel(left, top, w, h, h / 2.0, a);

        double ca = a * contentAlpha;
        int accentColor = accentDisplay;
        double cx = left + 18 * u, cy = top + 18 * u;

        // The ring around the cover is the progress, both are concentric with the end of the pill
        if (progressBar.get() && state.duration() > 0) {
            ring(cx, cy, 14.5 * u, 2 * u, state.progress(), accentColor, ca);
        }

        if (artwork.get()) {
            double angle = spinArtwork.get() && state.isPlaying() ? time * 0.45 : 0;
            drawCover(cx - 11 * u, cy - 11 * u, 22 * u, 11 * u, angle, ca, accentColor);
        }

        int textX = left + px(38);
        int textRight = right - px(16);

        String time = timeLabel.get() == TimeLabel.Hidden ? "" : MusicStarscript.time(state.positionNow());
        if (!time.isEmpty()) {
            text.add(time, 7, textRight, cy, Align.RIGHT, color(secondaryColor.get(), ca));
            textRight -= (int) text.width(time, 7) + px(8);
        }

        String first = lineTexts.isEmpty() ? state.title : lineTexts.getFirst();
        String second = lineTexts.size() > 1 ? lineTexts.get(1) : "";

        if (lyrics.get()) {
            String lyric = MusicStarscript.lyric(0);
            if (!lyric.isEmpty()) second = lyric;
        }

        if (second.isEmpty()) {
            line(first, 9, textX, textRight, cy, color(textColor.get(), ca), 0);
        } else {
            line(first, 9, textX, textRight, top + 13 * u, color(textColor.get(), ca), 0);
            line(second, 7, textX, textRight, top + 25 * u, color(secondaryColor.get(), ca), 1);
        }
    }

    // Lyrics: big centered lyrics, the title small on top

    private void drawLyricsStyle(double dt) {
        double a = overlayAlpha;
        evaluateLines();

        int w = px(width.get());
        int lyricCount = 1 + linesBefore.get() + linesAfter.get();
        double spacing = lyricSize.get() * 1.7;
        double pad = 10;
        boolean title = !lineTexts.isEmpty();

        double heightUnits = pad + (title ? 14 : 0) + lyricCount * spacing + pad;
        int h = animatedHeight(heightUnits * u, dt);
        int[] pos = place(w, h);
        int left = pos[0], top = pos[1], right = left + w;

        if ((background.get().a) > 0) panel(left, top, w, h, 16 * u, a);

        double ca = a * contentAlpha;
        int accentColor = accentDisplay;
        double y = top + pad * u;

        if (title) {
            String head = lineTexts.size() > 1 ? lineTexts.get(0) + "  ·  " + lineTexts.get(1) : lineTexts.getFirst();
            text.add(text.fit(head, 7, w - 2 * pad * u), 7, left + w / 2, y + 5 * u, Align.CENTER, color(secondaryColor.get(), ca));
            y += 14 * u;
        }

        if (progressBar.get() && state.duration() > 0) {
            drawProgress(left + px(pad), top + h - 4 * u, w - 2 * pad * u, 1.5 * u, accentColor, ca);
        }

        drawLyricLines(left + w / 2, left + px(pad), right - px(pad), y, spacing, ca, accentColor, true, dt);
    }

    // Text: only the lines and the current lyric, with a shadow and no background

    private void drawTextStyle(double dt) {
        double a = overlayAlpha;
        evaluateLines();

        String lyric = lyrics.get() ? MusicStarscript.lyric(0) : "";
        int count = lineTexts.size() + (lyric.isEmpty() ? 0 : 1);
        if (count == 0) return;

        int w = px(width.get());
        int h = animatedHeight(count * 12 * u, dt);
        int[] pos = place(w, h);
        int left = pos[0], top = pos[1];
        boolean alignRight = anchor.get() == Anchor.TopRight || anchor.get() == Anchor.MiddleRight || anchor.get() == Anchor.BottomRight;
        int x = alignRight ? left + w : left;
        Align align = alignRight ? Align.RIGHT : Align.LEFT;

        double ca = a * contentAlpha;
        text.setShadow(true);

        double y = top + 6 * u;
        for (int i = 0; i < lineTexts.size(); i++) {
            int color = i == 0 ? color(textColor.get(), ca) : color(secondaryColor.get(), ca);
            text.add(text.fit(lineTexts.get(i), i == 0 ? 9 : 8, w), i == 0 ? 9 : 8, x, y, align, color);
            y += 12 * u;
        }

        if (!lyric.isEmpty()) text.add(text.fit(lyric, 8, w), 8, x, y, align, DynamicIsland.mix(0xFFFFFFFF, accentDisplay, 0.6) & 0xFFFFFF | (int) (255 * ca) << 24);
        text.setShadow(false);
    }

    // Parts

    private void panel(int left, int top, int w, int h, double radius, double a) {
        SettingColor bg = background.get();
        int base = (bg.getPacked() & 0xFFFFFF) | (int) Mth.clamp(bg.a * a, 0, 255) << 24;

        // A little of the cover color in the glass
        if (artworkColors.get() && Artwork.INSTANCE.hasColors() && coverTint.get() > 0) {
            int tinted = DynamicIsland.mix(base | 0xFF000000, Artwork.INSTANCE.getAverage(), coverTint.get());
            base = (tinted & 0xFFFFFF) | (base & 0xFF000000);
        }

        int topColor = DynamicIsland.mix(base, 0xFFFFFFFF, 0.06) & 0xFFFFFF | (base & 0xFF000000);
        int bottomColor = DynamicIsland.mix(base, 0xFF000000, 0.22) & 0xFFFFFF | (base & 0xFF000000);
        double cx = left + w / 2.0, cy = top + h / 2.0;

        if (sdfOn) {
            if (shadow.get()) {
                int shadowColor = (int) (255 * 0.38 * a) << 24;
                sdf.box(cx, cy + 4 * u, w / 2.0 - 2 * u, h / 2.0 - u, radius, IslandSdf.SHADOW, 6 * u, 0, shadowColor, shadowColor, 0);
            }

            int borderColor = border.get() ? ((int) (255 * 0.2 * a) << 24 | 0xFFFFFF) : 0;
            sdf.box(cx, cy, w / 2.0, h / 2.0, radius, IslandSdf.FILL, border.get() ? Math.max(1, 0.5 * u) : 0, 0.85, topColor, bottomColor, borderColor);
        } else {
            Renderer2D.COLOR.quad(left, top, w, h, set(topColor), set(topColor), set(bottomColor), set(bottomColor));
        }
    }

    private void drawCover(double x, double y, double size, double radius, double angle, double a, int accentColor) {
        Texture cover = Artwork.INSTANCE.getTexture();
        double half = size / 2;

        if (cover != null && IslandImage.isAvailable()) {
            IslandImage.INSTANCE.add(cover, x + half, y + half, half, half, radius, angle, (int) (255 * a) << 24 | 0xFFFFFF);
            return;
        }

        // No cover: a tinted tile with a note
        int tile = (int) (255 * 0.22 * a) << 24 | (accentColor & 0xFFFFFF);
        if (sdfOn) sdf.box(x + half, y + half, half, half, radius, IslandSdf.FILL, 0, 0, tile, tile, 0);
        else Renderer2D.COLOR.quad(x, y, size, size, set(tile));

        text.add("♪", size / u * 0.55, (int) Math.round(x + half), y + half, Align.CENTER, (int) (255 * a) << 24 | (accentColor & 0xFFFFFF));
    }

    private void drawProgress(double x, double y, double w, double h, int accentColor, double a) {
        int track = (int) (255 * 0.14 * a) << 24 | 0xFFFFFF;
        double fill = Math.max(h, w * state.progress());
        int start = (int) (255 * a) << 24 | (accentColor & 0xFFFFFF);
        int end = (int) (255 * a) << 24 | (DynamicIsland.mix(accentColor, 0xFFFFFFFF, 0.35) & 0xFFFFFF);

        if (sdfOn) {
            sdf.box(x + w / 2, y + h / 2, w / 2, h / 2, h / 2, IslandSdf.FILL_HORIZONTAL, 0, 0, track, track, 0);
            sdf.box(x + fill - h / 2, y + h / 2, h / 2, h / 2, h / 2, IslandSdf.GLOW, 5 * u, 0, (int) (255 * 0.4 * a) << 24 | (accentColor & 0xFFFFFF), 0, 0);
            sdf.box(x + fill / 2, y + h / 2, fill / 2, h / 2, h / 2, IslandSdf.FILL_HORIZONTAL, 0, 0, start, end, 0);
        } else {
            Renderer2D.COLOR.quad(x, y, w, h, set(track));
            Renderer2D.COLOR.quad(x, y, fill, h, set(start));
        }
    }

    /** A progress ring, starting at the top and going clockwise. */
    private void ring(double cx, double cy, double radius, double thickness, double progress, int accentColor, double a) {
        if (!sdfOn) return;

        sdf.arc(cx, cy, radius, thickness, 0, Math.PI * 2, (int) (255 * 0.14 * a) << 24 | 0xFFFFFF);
        if (progress > 0.004) sdf.arc(cx, cy, radius, thickness, -Math.PI / 2, Math.PI * 2 * Math.min(progress, 1), (int) (255 * a) << 24 | (accentColor & 0xFFFFFF));
    }

    /** Five little bars, right aligned, moving while playing. */
    private void drawBars(double rightX, double cy, double maxH, int accentColor, double a, boolean live) {
        double bw = 1.6 * u, gap = 1.6 * u;
        int c = (int) (255 * a) << 24 | (accentColor & 0xFFFFFF);

        for (int i = 0; i < 4; i++) {
            double x = rightX - (3 - i) * (bw + gap) - bw / 2;
            double phase = time * (4.4 + i * 1.2) + i * 1.7;
            double level = live ? 0.3 + 0.7 * Math.abs(Math.sin(phase) * Math.cos(phase * 0.41 + i)) : 0;
            double h = Math.max(bw, maxH * level);

            if (sdfOn) sdf.box(x, cy, bw / 2, h / 2, bw / 2, IslandSdf.FILL, 0, 0, c, c, 0);
            else Renderer2D.COLOR.quad(x - bw / 2, cy - h / 2, bw, h, set(c));
        }
    }

    /** One line of text that scrolls when it is too long, or is shortened when scrolling is off. */
    private void line(String value, double size, int x, int right, double cy, int argb, int index) {
        int room = right - x;
        if (room <= 0) return;

        double width = text.width(value, size);

        if (width <= room || !marquee.get()) {
            text.add(text.fit(value, size, room), size, x, cy, Align.LEFT, argb);
            return;
        }

        // Waits at the start, scrolls through, and comes around again
        double gap = 24 * u;
        double cycle = width + gap;
        double speed = 26 * u;
        double wait = 1.8;
        double seconds = (System.nanoTime() - trackChangedNanos) / 1e9 + index * 0.4;
        double period = wait + cycle / speed;
        double t = seconds % period;
        int offset = t < wait ? 0 : (int) Math.round((t - wait) * speed);

        int[] clip = {x, (int) Math.floor(cy - size * u), right, (int) Math.ceil(cy + size * u)};
        text.add(value, size, x - offset, cy, Align.LEFT, argb, clip);
        text.add(value, size, x - offset + (int) Math.round(cycle), cy, Align.LEFT, argb, clip);
    }

    private String rightTime() {
        return switch (timeLabel.get()) {
            case Total -> MusicStarscript.time(state.duration());
            case Remaining -> "-" + MusicStarscript.time(Math.max(0, state.duration() - state.positionNow()));
            case Hidden -> "";
        };
    }

    // Lyrics

    private boolean hasLyricContent() {
        Lyrics l = LyricsService.INSTANCE.getLyrics();
        if (!l.isEmpty() && l.isSynced()) return true;
        return lyricStatus.get();
    }

    private void drawLyricLines(int centerX, int minX, int maxX, double topY, double spacingUnits, double a, int accentColor, boolean center, double dt) {
        Lyrics l = LyricsService.INSTANCE.getLyrics();
        double spacing = spacingUnits * u;
        int before = linesBefore.get(), after = linesAfter.get();
        double middleY = topY + (before + 0.5) * spacing;
        int dim = color(lyricDimColor.get(), a);

        if (l.isEmpty() || !l.isSynced()) {
            if (!lyricStatus.get()) return;

            String status = switch (LyricsService.INSTANCE.getStatus()) {
                case SEARCHING -> tr("lyrics-searching", "Looking for lyrics...");
                case FOUND -> tr("lyrics-not-synced", "These lyrics have no timing");
                default -> state.hasTrack() ? tr("lyrics-none", "No lyrics found") : "";
            };

            text.add(text.fit(status, 8, maxX - minX), 8, centerX, middleY, Align.CENTER, dim);
            return;
        }

        double position = state.positionNow() + lyricOffset.get();
        int index = l.indexAt(position);

        // When the line changes, everything slides up by one line
        if (index != shownLyric) {
            lyricScroll = smoothScroll.get() && shownLyric >= 0 && index == shownLyric + 1 ? 1 : 0;
            shownLyric = index;
        }

        lyricScroll *= Math.exp(-dt * 11);
        if (lyricScroll < 0.002) lyricScroll = 0;

        double size = lyricSize.get();
        int sung = lyricColor.get().a > 0 ? color(lyricColor.get(), a) : (int) (255 * a) << 24 | (DynamicIsland.mix(accentColor, 0xFFFFFFFF, 0.25) & 0xFFFFFF);

        for (int k = -before - 1; k <= after + 1; k++) {
            int i = index + k;
            if (i < 0 || i >= l.size()) continue;

            double y = middleY + (k + lyricScroll) * spacing;
            double distance = Math.abs(k + lyricScroll);
            if (distance > Math.max(before, after) + 0.75) continue;

            // Lines fade out toward the edges of the block
            double edge = k < 0 ? before + 0.5 : after + 0.5;
            double fade = Mth.clamp(edge + 0.5 - distance, 0, 1);

            String value = l.text(i);
            if (value.isEmpty()) value = "♪";

            boolean current = k == 0;
            double lineSize = current ? size : size * 0.8;
            int room = maxX - minX;

            // A long current line gets a little smaller before it gets cut
            while (current && lineSize > size * 0.7 && text.width(value, lineSize) > room) lineSize -= 0.5;
            value = text.fit(value, lineSize, room);

            int x = center ? centerX : minX;
            Align align = center ? Align.CENTER : Align.LEFT;
            int baseColor = alphaMul(dim, fade);

            if (!current) {
                text.add(value, lineSize, x, y, align, baseColor);
                continue;
            }

            int lineColor = alphaMul(sung, fade);

            if (!karaoke.get()) {
                text.add(value, lineSize, x, y, align, lineColor);
                continue;
            }

            // The sung part in the bright color, the rest dim, cut at the point being sung
            double width = text.width(value, lineSize);
            int start = center ? (int) Math.round(centerX - width / 2) : minX;
            double progress = l.lineProgress(i, position, state.duration());
            int split = start + (int) Math.round(width * progress);
            int y0 = (int) Math.floor(y - lineSize * u), y1 = (int) Math.ceil(y + lineSize * u);

            text.add(value, lineSize, x, y, align, alphaMul(dim, fade * 1.4), new int[]{split, y0, start + (int) Math.ceil(width) + 2, y1});
            text.add(value, lineSize, x, y, align, lineColor, new int[]{start - 2, y0, split, y1});
        }
    }

    // Helpers

    private int color(SettingColor c, double a) {
        return (int) Mth.clamp(c.a * a, 0, 255) << 24 | (c.getPacked() & 0xFFFFFF);
    }

    private static int alphaMul(int argb, double factor) {
        return (int) Mth.clamp((argb >>> 24) * factor, 0, 255) << 24 | (argb & 0xFFFFFF);
    }

    private Color set(int argb) {
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, argb >>> 24);
    }

    private static double smooth(double t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static String tr(String key, String english) {
        return LanguageManager.translate("music-player." + key, english);
    }
}
