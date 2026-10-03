/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.MeteorClient;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Talks to the Windows media controls (what the volume flyout shows: Spotify, browsers, the Windows media player and
 * so on) through a small PowerShell script that ships with the client. The script runs in the background while
 * something uses it, prints the current track as JSON and takes play, pause, next and seek commands.
 * <p>
 * Everything here is safe to call from any thread.
 */
public final class MediaBridge {
    public static final MediaBridge INSTANCE = new MediaBridge();

    private static final long RESTART_DELAY_NANOS = 5_000_000_000L;

    private volatile MediaState state = MediaState.NONE;
    private volatile String error = "";

    /** The newest cover image, as encoded bytes, with a number that goes up every time it changes. */
    private volatile byte[] artwork;
    private volatile int artworkVersion;

    private Process process;
    private BufferedWriter input;
    private Thread reader;
    private int users;
    private long lastStartNanos;
    private String preferredApp = "";

    private MediaBridge() {
    }

    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public MediaState getState() {
        return state;
    }

    public String getError() {
        return error;
    }

    public byte[] getArtwork() {
        return artwork;
    }

    public int getArtworkVersion() {
        return artworkVersion;
    }

    // Lifetime: the script runs while at least one user wants it

    public synchronized void acquire() {
        users++;
        ensureRunning();
    }

    public synchronized void release() {
        users = Math.max(0, users - 1);
        if (users == 0) stop();
    }

    /** Called often by users, starts the script again if it died (at most every few seconds). */
    public synchronized void ensureRunning() {
        if (users == 0 || !isSupported()) return;
        if (process != null && process.isAlive()) return;
        if (System.nanoTime() - lastStartNanos < RESTART_DELAY_NANOS && lastStartNanos != 0) return;

        lastStartNanos = System.nanoTime();
        start();
    }

    private void start() {
        stop();

        try {
            File script = extractScript();

            process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-File", script.getAbsolutePath())
                .redirectErrorStream(false)
                .start();

            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            if (!preferredApp.isBlank()) send("select " + preferredApp);

            Process running = process;
            reader = Thread.ofPlatform().daemon().name("Meteor Media Bridge").start(() -> read(running));

            // Nobody reads the errors, but the pipe must be drained or the script could block on it
            Thread.ofPlatform().daemon().name("Meteor Media Bridge Errors").start(() -> {
                try (InputStream err = running.getErrorStream()) {
                    byte[] buffer = new byte[4096];
                    StringBuilder last = new StringBuilder();
                    int n;

                    while ((n = err.read(buffer)) > 0) {
                        last.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                        if (last.length() > 2000) last.delete(0, last.length() - 2000);
                        error = last.toString().strip();
                    }
                } catch (IOException ignored) {
                }
            });

            error = "";
        } catch (IOException e) {
            error = e.getMessage();
            process = null;
            MeteorClient.LOG.warn("Could not start the media bridge", e);
        }
    }

    private synchronized void stop() {
        if (process != null) {
            try {
                if (input != null) input.close();
            } catch (IOException ignored) {
            }

            process.destroy();
            process = null;
            input = null;
        }

        state = MediaState.NONE;
    }

    private static File extractScript() throws IOException {
        File folder = new File(MeteorClient.FOLDER, "music");
        folder.mkdirs();
        File script = new File(folder, "smtc.ps1");

        // Written every time, so an updated client also updates the script
        try (InputStream in = MediaBridge.class.getResourceAsStream("/assets/meteor-client/music/smtc.ps1")) {
            if (in == null) throw new IOException("The media script is missing from the jar");
            Files.copy(in, script.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        return script;
    }

    // Commands

    public void play() {
        send("play");
    }

    public void pause() {
        send("pause");
    }

    public void togglePlayPause() {
        send("toggle");
    }

    public void next() {
        send("next");
    }

    public void previous() {
        send("previous");
    }

    /** Jumps to a position in seconds from the start of the track. */
    public void seek(double seconds) {
        MediaState s = state;
        send(String.format(Locale.ROOT, "seek %.2f", Math.max(0, seconds) + s.start));
    }

    /** Follows the player whose app id contains this text, or the one Windows thinks is current when empty. */
    public synchronized void setPreferredApp(String app) {
        app = app == null ? "" : app.strip();
        if (app.equals(preferredApp)) return;

        preferredApp = app;
        send("select " + app);
    }

    private synchronized void send(String command) {
        if (input == null) return;

        try {
            input.write(command);
            input.newLine();
            input.flush();
        } catch (IOException e) {
            error = e.getMessage();
        }
    }

    // Reading

    private void read(Process running) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
            String line;

            while ((line = in.readLine()) != null) {
                if (!line.startsWith("{")) continue;

                try {
                    parse(JsonParser.parseString(line).getAsJsonObject());
                } catch (RuntimeException e) {
                    error = "Bad data from the media script: " + e.getMessage();
                }
            }
        } catch (IOException ignored) {
            // The script stopped
        }

        synchronized (this) {
            if (process == running) state = MediaState.NONE;
        }
    }

    private void parse(JsonObject o) {
        long now = System.nanoTime();

        List<String> apps = new ArrayList<>();
        JsonElement appsElement = o.get("apps");

        if (appsElement != null && appsElement.isJsonArray()) {
            JsonArray array = appsElement.getAsJsonArray();
            for (JsonElement e : array) apps.add(e.getAsString());
        } else if (appsElement != null && appsElement.isJsonPrimitive()) {
            // ConvertTo-Json writes a list of one as a plain value
            apps.add(appsElement.getAsString());
        }

        if (o.has("error")) error = string(o, "error");

        if (!bool(o, "active", false)) {
            state = new MediaState(false, "", "", "", "", "", 0, 0, "Closed", false, "None", 1, 0, 0, 0, false, false, apps, now);
            return;
        }

        // The position was measured a moment before the line was written
        double age = number(o, "age", 0);
        long measured = now - (long) (Math.min(Math.max(age, 0), 30) * 1e9);

        MediaState previous = state;
        MediaState next = new MediaState(true,
            string(o, "app"), string(o, "title"), string(o, "artist"), string(o, "album"), string(o, "albumArtist"),
            (int) number(o, "track", 0), (int) number(o, "tracks", 0),
            string(o, "status"), bool(o, "shuffle", false), string(o, "repeat"), number(o, "rate", 1),
            number(o, "position", 0), number(o, "start", 0), number(o, "end", 0),
            bool(o, "canNext", true), bool(o, "canPrevious", true), apps, measured);

        // Some players only update the position every few seconds. Keep the moving estimate unless they disagree.
        if (previous.trackKey().equals(next.trackKey()) && previous.status.equals(next.status) && next.isPlaying()
            && Math.abs(previous.positionNow() - next.positionNow()) < 1.5) {
            next = new MediaState(true, next.app, next.title, next.artist, next.album, next.albumArtist, next.track, next.tracks,
                next.status, next.shuffle, next.repeat, next.rate, previous.positionNow() + next.start, next.start, next.end,
                next.canNext, next.canPrevious, apps, now);
        }

        state = next;

        if (o.has("thumbnail")) {
            String data = string(o, "thumbnail");

            try {
                artwork = data.isEmpty() ? null : Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException e) {
                artwork = null;
            }

            artworkVersion++;
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static double number(JsonObject o, String key, double fallback) {
        JsonElement e = o.get(key);

        try {
            return e == null || e.isJsonNull() ? fallback : e.getAsDouble();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);

        try {
            return e == null || e.isJsonNull() ? fallback : e.getAsBoolean();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
