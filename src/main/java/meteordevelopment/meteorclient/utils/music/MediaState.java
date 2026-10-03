/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.music;

import java.util.List;

/** What a media player reports through the Windows media controls, at one moment. */
public final class MediaState {
    public static final MediaState NONE = new MediaState(false, "", "", "", "", "", 0, 0, "Closed", false, "None", 1, 0, 0, 0, true, true, List.of(), System.nanoTime());

    public final boolean active;
    public final String app, title, artist, album, albumArtist;
    public final int track, tracks;
    public final String status;
    public final boolean shuffle;
    public final String repeat;
    public final double rate;
    /** Position and length in seconds, measured at {@link #measuredNanos}. */
    public final double position, start, end;
    public final boolean canNext, canPrevious;
    public final List<String> apps;
    public final long measuredNanos;

    public MediaState(boolean active, String app, String title, String artist, String album, String albumArtist, int track, int tracks,
                      String status, boolean shuffle, String repeat, double rate, double position, double start, double end,
                      boolean canNext, boolean canPrevious, List<String> apps, long measuredNanos) {
        this.active = active;
        this.app = app;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.albumArtist = albumArtist;
        this.track = track;
        this.tracks = tracks;
        this.status = status;
        this.shuffle = shuffle;
        this.repeat = repeat;
        this.rate = rate;
        this.position = position;
        this.start = start;
        this.end = end;
        this.canNext = canNext;
        this.canPrevious = canPrevious;
        this.apps = apps;
        this.measuredNanos = measuredNanos;
    }

    public boolean isPlaying() {
        return active && "Playing".equals(status);
    }

    public boolean hasTrack() {
        return active && !title.isBlank();
    }

    /** Length of the track in seconds, or 0 when the player does not say. */
    public double duration() {
        return Math.max(0, end - start);
    }

    /** The position right now: while playing it keeps moving from the last measurement. */
    public double positionNow() {
        double p = position - start;
        if (isPlaying()) p += (System.nanoTime() - measuredNanos) / 1e9 * (rate > 0 ? rate : 1);

        double d = duration();
        return d > 0 ? Math.min(Math.max(p, 0), d) : Math.max(p, 0);
    }

    /** From 0 to 1, or 0 when the length is not known. */
    public double progress() {
        double d = duration();
        return d > 0 ? positionNow() / d : 0;
    }

    /** Identifies the track, so a change of song can be noticed. */
    public String trackKey() {
        return app + "|" + title + "|" + artist + "|" + album;
    }

    /** A readable name for the player, "Spotify" instead of "Spotify.exe" or a long app id. */
    public String appName() {
        return prettyApp(app);
    }

    public static String prettyApp(String app) {
        if (app == null || app.isBlank()) return "";

        String name = app;
        int bang = name.indexOf('!');
        if (bang >= 0) name = name.substring(bang + 1);
        int underscore = name.indexOf('_');
        if (underscore > 0 && name.contains(".")) name = name.substring(0, underscore);
        if (name.toLowerCase().endsWith(".exe")) name = name.substring(0, name.length() - 4);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) name = name.substring(dot + 1);

        if (name.equalsIgnoreCase("App")) name = app.split("[._!]")[0];
        return name.isEmpty() ? app : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
