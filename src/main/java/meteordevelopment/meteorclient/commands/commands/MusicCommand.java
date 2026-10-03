/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.commands.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.MusicPlayer;
import meteordevelopment.meteorclient.utils.music.Lyrics;
import meteordevelopment.meteorclient.utils.music.LyricsService;
import meteordevelopment.meteorclient.utils.music.MediaBridge;
import meteordevelopment.meteorclient.utils.music.MediaState;
import meteordevelopment.meteorclient.utils.music.MusicStarscript;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

/** Controls the music player from the chat: .music play, pause, next, seek 1:30, lyrics and so on. */
public class MusicCommand extends Command {
    public MusicCommand() {
        super("music", "Controls the music playing on your computer. Needs the Music Player module.", "song");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(context -> {
            printInfo();
            return SINGLE_SUCCESS;
        });

        builder.then(literal("info").executes(context -> {
            printInfo();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("play").executes(context -> run(MediaBridge.INSTANCE::play)));
        builder.then(literal("pause").executes(context -> run(MediaBridge.INSTANCE::pause)));
        builder.then(literal("toggle").executes(context -> run(MediaBridge.INSTANCE::togglePlayPause)));
        builder.then(literal("next").executes(context -> run(MediaBridge.INSTANCE::next)));
        builder.then(literal("previous").executes(context -> run(MediaBridge.INSTANCE::previous)));

        builder.then(literal("seek").then(argument("time", StringArgumentType.word()).executes(context -> {
            String value = StringArgumentType.getString(context, "time");
            double seconds = parseTime(value);

            if (seconds < 0) {
                error("Use seconds or minutes:seconds, for example (highlight)90(default) or (highlight)1:30(default).");
                return SINGLE_SUCCESS;
            }

            return run(() -> MediaBridge.INSTANCE.seek(seconds));
        })));

        builder.then(literal("forward").then(argument("seconds", DoubleArgumentType.doubleArg(0)).executes(context -> {
            double seconds = DoubleArgumentType.getDouble(context, "seconds");
            return run(() -> MediaBridge.INSTANCE.seek(MediaBridge.INSTANCE.getState().positionNow() + seconds));
        })));

        builder.then(literal("back").then(argument("seconds", DoubleArgumentType.doubleArg(0)).executes(context -> {
            double seconds = DoubleArgumentType.getDouble(context, "seconds");
            return run(() -> MediaBridge.INSTANCE.seek(MediaBridge.INSTANCE.getState().positionNow() - seconds));
        })));

        builder.then(literal("lyrics")
            .executes(context -> {
                printLyrics();
                return SINGLE_SUCCESS;
            })
            .then(literal("reload").executes(context -> {
                if (!checkModule()) return SINGLE_SUCCESS;

                Modules.get().get(MusicPlayer.class).reloadLyrics();
                info("Looking for the lyrics again.");
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("apps").executes(context -> {
            if (!checkModule()) return SINGLE_SUCCESS;

            MediaState state = MediaBridge.INSTANCE.getState();
            if (state.apps.isEmpty()) info("No player is open.");
            else for (String app : state.apps) info("(highlight)%s(default)  %s", MediaState.prettyApp(app), app);
            return SINGLE_SUCCESS;
        }));
    }

    private int run(Runnable action) {
        if (checkModule()) action.run();
        return SINGLE_SUCCESS;
    }

    private boolean checkModule() {
        if (!MediaBridge.isSupported()) {
            error("The music player only works on Windows.");
            return false;
        }

        if (!Modules.get().get(MusicPlayer.class).isActive()) {
            error("Turn on the (highlight)Music Player(default) module first.");
            return false;
        }

        return true;
    }

    private void printInfo() {
        if (!checkModule()) return;

        MediaState s = MediaBridge.INSTANCE.getState();

        if (!s.hasTrack()) {
            info("Nothing is playing.");

            String problem = MediaBridge.INSTANCE.getError();
            if (!problem.isBlank()) warning("Media bridge: %s", problem.length() > 200 ? problem.substring(0, 200) + "..." : problem);
            return;
        }

        info("(highlight)%s(default)", s.title);
        if (!s.artist.isBlank()) info("Artist: (highlight)%s", s.artist);
        if (!s.album.isBlank()) info("Album: (highlight)%s", s.album);
        info("%s  (highlight)%s / %s(default)  %s", s.status, MusicStarscript.time(s.positionNow()), MusicStarscript.time(s.duration()), s.appName());

        Lyrics lyrics = LyricsService.INSTANCE.getLyrics();
        if (!lyrics.isEmpty()) info("Lyrics: %d lines from %s%s", lyrics.size(), lyrics.source, lyrics.isSynced() ? "" : " (no timing)");
    }

    private void printLyrics() {
        if (!checkModule()) return;

        Lyrics lyrics = LyricsService.INSTANCE.getLyrics();

        if (lyrics.isEmpty()) {
            info("No lyrics for this song.");
            return;
        }

        int index = Math.max(lyrics.indexAt(MediaBridge.INSTANCE.getState().positionNow() + MusicStarscript.lyricOffset), 0);
        int from = Math.max(0, index - 2), to = Math.min(lyrics.size(), index + 6);

        for (int i = from; i < to; i++) {
            String text = lyrics.text(i).isEmpty() ? "♪" : lyrics.text(i);
            if (i == index) info("(highlight)> %s", text);
            else info("  %s", text);
        }
    }

    /** Reads "90", "1:30" or "1:02:03". Returns -1 when it cannot. */
    private static double parseTime(String value) {
        try {
            String[] parts = value.split(":");
            double seconds = 0;
            for (String part : parts) seconds = seconds * 60 + Double.parseDouble(part);
            return seconds;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
