/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.music;

import meteordevelopment.meteorclient.utils.misc.MeteorStarscript;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.world.item.Items;
import org.meteordev.starscript.Script;
import org.meteordev.starscript.Starscript;
import org.meteordev.starscript.value.Value;
import org.meteordev.starscript.value.ValueMap;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Starscript variables for the music player, usable everywhere Starscript is: {@code {music.title}},
 * {@code {music.lyric}}, {@code {music.position}} and so on. Also a few general ones the island templates use.
 */
public final class MusicStarscript {
    /** Moves the lyrics earlier (positive) or later, set by the Music Player module. */
    public static volatile double lyricOffset;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private MusicStarscript() {
    }

    public static void register(Starscript ss) {
        ss.set("music", new ValueMap()
            .set("_toString", () -> Value.string(nowPlaying()))
            .set("title", () -> Value.string(state().title))
            .set("artist", () -> Value.string(state().artist))
            .set("album", () -> Value.string(state().album))
            .set("album_artist", () -> Value.string(state().albumArtist))
            .set("app", () -> Value.string(state().appName()))
            .set("app_id", () -> Value.string(state().app))
            .set("status", () -> Value.string(state().status))
            .set("playing", () -> Value.bool(state().isPlaying()))
            .set("active", () -> Value.bool(state().hasTrack()))
            .set("position", () -> Value.string(time(state().positionNow())))
            .set("position_seconds", () -> Value.number(Math.floor(state().positionNow())))
            .set("duration", () -> Value.string(time(state().duration())))
            .set("duration_seconds", () -> Value.number(Math.floor(state().duration())))
            .set("remaining", () -> Value.string("-" + time(Math.max(0, state().duration() - state().positionNow()))))
            .set("progress", () -> Value.number(Math.round(state().progress() * 100)))
            .set("track", () -> Value.number(state().track))
            .set("track_count", () -> Value.number(state().tracks))
            .set("shuffle", () -> Value.bool(state().shuffle))
            .set("repeat", () -> Value.string(state().repeat))
            .set("lyric", () -> Value.string(lyric(0)))
            .set("next_lyric", () -> Value.string(lyric(1)))
            .set("previous_lyric", () -> Value.string(lyric(-1)))
            .set("has_lyrics", () -> Value.bool(!LyricsService.INSTANCE.getLyrics().isEmpty()))
            .set("lyrics_source", () -> Value.string(LyricsService.INSTANCE.getLyrics().source))
        );

        ss.set("date", () -> Value.string(LocalDate.now().format(DATE)));
        ss.set("clock", () -> Value.string(LocalTime.now().format(CLOCK)));
        ss.set("weekday", () -> Value.string(LocalDate.now().getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())));
        ss.set("day", () -> Value.number(mc.level != null ? Math.floor(mc.level.getOverworldClockTime() / 24000.0) : 0));
        ss.set("totems", () -> Value.number(mc.player != null ? InvUtils.find(Items.TOTEM_OF_UNDYING).count() : 0));
        ss.set("memory", () -> {
            Runtime runtime = Runtime.getRuntime();
            return Value.number(Math.round((runtime.totalMemory() - runtime.freeMemory()) / 1048576.0));
        });
    }

    private static final Map<String, Optional<Script>> SCRIPTS = new ConcurrentHashMap<>();

    /** Runs a Starscript template. A broken one shows as written, and only complains in chat once. */
    public static String run(String source) {
        if (source == null || source.isBlank()) return "";
        if (source.indexOf('{') < 0) return source;

        if (SCRIPTS.size() > 256) SCRIPTS.clear();
        Optional<Script> script = SCRIPTS.computeIfAbsent(source, text -> Optional.ofNullable(MeteorStarscript.compile(text)));
        if (script.isEmpty()) return source;

        try {
            String result = MeteorStarscript.run(script.get());
            return result == null ? "" : result.strip();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static MediaState state() {
        return MediaBridge.INSTANCE.getState();
    }

    /** "Title - Artist", or the title alone. */
    public static String nowPlaying() {
        MediaState s = state();
        if (!s.hasTrack()) return "";
        return s.artist.isBlank() ? s.title : s.title + " - " + s.artist;
    }

    /** The current lyric line, or the one before or after it. Empty lines between verses become a note. */
    public static String lyric(int relative) {
        Lyrics lyrics = LyricsService.INSTANCE.getLyrics();
        if (lyrics.isEmpty() || !lyrics.isSynced()) return "";

        int index = lyrics.indexAt(state().positionNow() + lyricOffset) + relative;
        if (index < 0 || index >= lyrics.size()) return "";

        String text = lyrics.text(index);
        return text.isEmpty() ? "♪" : text;
    }

    public static String time(double seconds) {
        int total = (int) Math.max(0, seconds);
        int h = total / 3600, m = total % 3600 / 60, s = total % 60;
        return h > 0 ? "%d:%02d:%02d".formatted(h, m, s) : "%d:%02d".formatted(m, s);
    }
}
