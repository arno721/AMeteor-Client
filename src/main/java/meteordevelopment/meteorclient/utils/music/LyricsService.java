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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Finds lyrics for a track. In this order: a file the user put in {@code meteor-client/music/lyrics}, lyrics found
 * before (kept in {@code meteor-client/music/cache}), and the free LRCLIB database (lrclib.net, no account needed).
 */
public final class LyricsService {
    public static final LyricsService INSTANCE = new LyricsService();

    private static final String USER_AGENT = "Meteor Client music player (https://github.com/MeteorDevelopment/meteor-client)";
    private static final String NOT_FOUND = "#not-found";
    private static final long NOT_FOUND_RETRY_MILLIS = 24L * 3600 * 1000;

    private static final Pattern JUNK = Pattern.compile(
        "[(\\[【（「『][^)\\]】）」』]*(official|mv|m/v|lyric|audio|video|hd|4k|1080p|visualizer|music video|live|完整版|官方|歌詞|動態歌詞|高音質|中字)[^)\\]】）」』]*[)\\]】）」』]",
        Pattern.CASE_INSENSITIVE);

    public enum Status {
        IDLE,
        SEARCHING,
        FOUND,
        NOT_FOUND
    }

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private final Map<String, Lyrics> memory = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Lyrics> eldest) {
            return size() > 48;
        }
    };

    private volatile String currentKey = "";
    private volatile Lyrics current = Lyrics.NONE;
    private volatile Status status = Status.IDLE;
    private volatile int version;

    private LyricsService() {
    }

    public Lyrics getLyrics() {
        return current;
    }

    public Status getStatus() {
        return status;
    }

    /** Goes up every time the lyrics change. */
    public int getVersion() {
        return version;
    }

    /** Looks up lyrics for the track if it is a different one than last time. */
    public void request(MediaState track, boolean local, boolean online) {
        if (!track.hasTrack()) {
            if (!currentKey.isEmpty()) set("", Lyrics.NONE, Status.IDLE);
            return;
        }

        String key = key(track);
        if (key.equals(currentKey)) return;

        currentKey = key;
        current = Lyrics.NONE;
        status = Status.SEARCHING;
        version++;

        synchronized (memory) {
            Lyrics known = memory.get(key);

            if (known != null) {
                set(key, known, known.isEmpty() ? Status.NOT_FOUND : Status.FOUND);
                return;
            }
        }

        String title = track.title, artist = track.artist, album = track.album;
        double duration = track.duration();

        CompletableFuture.supplyAsync(() -> find(title, artist, album, duration, local, online))
            .whenComplete((lyrics, error) -> {
                Lyrics result = lyrics == null ? Lyrics.NONE : lyrics;

                synchronized (memory) {
                    memory.put(key, result);
                }

                if (key.equals(currentKey)) set(key, result, result.isEmpty() ? Status.NOT_FOUND : Status.FOUND);
            });
    }

    /** Forgets what was found for the current track and looks again. */
    public void reload(MediaState track, boolean local, boolean online) {
        String key = key(track);

        synchronized (memory) {
            memory.remove(key);
        }

        File cached = cacheFile(track.title, track.artist);
        if (cached.exists()) cached.delete();

        currentKey = "";
        request(track, local, online);
    }

    private void set(String key, Lyrics lyrics, Status newStatus) {
        currentKey = key;
        current = lyrics;
        status = newStatus;
        version++;
    }

    private static String key(MediaState track) {
        return (track.artist + "|" + track.title).toLowerCase(Locale.ROOT);
    }

    // Finding

    private Lyrics find(String title, String artist, String album, double duration, boolean local, boolean online) {
        if (local) {
            Lyrics own = fromLocalFolder(title, artist);
            if (own != null) return own;
        }

        File cache = cacheFile(title, artist);

        if (cache.exists()) {
            try {
                String text = Files.readString(cache.toPath(), StandardCharsets.UTF_8);

                if (text.startsWith(NOT_FOUND)) {
                    if (System.currentTimeMillis() - cache.lastModified() < NOT_FOUND_RETRY_MILLIS || !online) return Lyrics.NONE;
                } else {
                    Lyrics lyrics = Lyrics.parseLrc(text, "cache");
                    if (!lyrics.isEmpty()) return lyrics;
                }
            } catch (IOException ignored) {
            }
        }

        if (!online) return Lyrics.NONE;

        String[] cleaned = clean(title, artist);
        Lyrics lyrics = fromLrclib(cleaned[0], cleaned[1], album, duration);
        save(cache, lyrics);
        return lyrics;
    }

    private static Lyrics fromLocalFolder(String title, String artist) {
        File folder = new File(MeteorClient.FOLDER, "music/lyrics");
        if (!folder.isDirectory()) {
            folder.mkdirs();
            return null;
        }

        String[] names = {safe(artist + " - " + title), safe(title + " - " + artist), safe(title)};

        for (String name : names) {
            for (String extension : new String[]{".lrc", ".txt"}) {
                File file = new File(folder, name + extension);
                if (!file.isFile()) continue;

                try {
                    String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    Lyrics lyrics = extension.equals(".lrc") ? Lyrics.parseLrc(text, file.getName()) : Lyrics.parsePlain(text, file.getName());
                    if (!lyrics.isEmpty()) return lyrics;
                } catch (IOException ignored) {
                }
            }
        }

        return null;
    }

    private Lyrics fromLrclib(String title, String artist, String album, double duration) {
        if (title.isBlank()) return Lyrics.NONE;

        try {
            // Exact match first, it needs the artist
            if (!artist.isBlank()) {
                StringBuilder url = new StringBuilder("https://lrclib.net/api/get?track_name=").append(encode(title))
                    .append("&artist_name=").append(encode(artist));
                if (!album.isBlank()) url.append("&album_name=").append(encode(album));
                if (duration > 0) url.append("&duration=").append(Math.round(duration));

                JsonElement exact = getJson(url.toString());
                if (exact != null && exact.isJsonObject()) {
                    Lyrics lyrics = fromRecord(exact.getAsJsonObject());
                    if (!lyrics.isEmpty()) return lyrics;
                }
            }

            // Then a search, and the best looking result
            JsonElement results = getJson("https://lrclib.net/api/search?q=" + encode((title + " " + artist).strip()));
            if (results == null || !results.isJsonArray()) return Lyrics.NONE;

            JsonObject best = null;
            double bestScore = -1e9;

            for (JsonElement e : (JsonArray) results) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();

                double score = 0;
                if (has(o, "syncedLyrics")) score += 10;
                else if (!has(o, "plainLyrics")) continue;

                if (duration > 0 && o.has("duration") && !o.get("duration").isJsonNull()) {
                    score -= Math.min(Math.abs(o.get("duration").getAsDouble() - duration), 30) * 0.5;
                }

                String foundTitle = text(o, "trackName").toLowerCase(Locale.ROOT);
                String foundArtist = text(o, "artistName").toLowerCase(Locale.ROOT);
                if (foundTitle.equals(title.toLowerCase(Locale.ROOT))) score += 6;
                if (!artist.isBlank() && foundArtist.contains(artist.toLowerCase(Locale.ROOT))) score += 4;

                if (score > bestScore) {
                    bestScore = score;
                    best = o;
                }
            }

            return best == null ? Lyrics.NONE : fromRecord(best);
        } catch (IOException | InterruptedException | RuntimeException e) {
            return Lyrics.NONE;
        }
    }

    private static Lyrics fromRecord(JsonObject o) {
        if (has(o, "syncedLyrics")) {
            Lyrics lyrics = Lyrics.parseLrc(text(o, "syncedLyrics"), "LRCLIB");
            if (!lyrics.isEmpty()) return lyrics;
        }

        if (has(o, "plainLyrics")) return Lyrics.parsePlain(text(o, "plainLyrics"), "LRCLIB");
        return Lyrics.NONE;
    }

    private JsonElement getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) return null;

        return JsonParser.parseString(response.body());
    }

    // Cache

    private static File cacheFile(String title, String artist) {
        File folder = new File(MeteorClient.FOLDER, "music/cache");
        folder.mkdirs();
        return new File(folder, Integer.toHexString((artist + "|" + title).toLowerCase(Locale.ROOT).hashCode()) + ".lrc");
    }

    private static void save(File file, Lyrics lyrics) {
        StringBuilder sb = new StringBuilder();

        if (lyrics.isEmpty()) {
            sb.append(NOT_FOUND).append('\n');
        } else {
            for (int i = 0; i < lyrics.size(); i++) {
                Lyrics.Line line = lyrics.get(i);

                if (lyrics.isSynced()) {
                    int minutes = (int) (line.time() / 60);
                    double seconds = line.time() - minutes * 60;
                    sb.append(String.format(Locale.ROOT, "[%02d:%05.2f]", minutes, seconds));
                }

                sb.append(line.text()).append('\n');
            }
        }

        try {
            Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    // Helpers

    /** Video titles often carry extra words and the artist, which make the lookup fail. Returns title and artist. */
    static String[] clean(String title, String artist) {
        String t = JUNK.matcher(title).replaceAll(" ");
        String a = artist.replaceAll("(?i)\\s*-\\s*topic$", "").replaceAll("(?i)vevo$", "").strip();

        // "Artist - Title" when the player does not say who it is
        if (a.isEmpty() && t.contains(" - ")) {
            int dash = t.indexOf(" - ");
            a = t.substring(0, dash).strip();
            t = t.substring(dash + 3);
        }

        // 周杰倫 Jay Chou【晴天】: the part in the brackets is the title
        java.util.regex.Matcher bracket = Pattern.compile("[【「『]([^】」』]+)[】」』]").matcher(t);
        if (bracket.find()) {
            String outside = (t.substring(0, bracket.start()) + " " + t.substring(bracket.end())).strip();
            if (a.isEmpty() && !outside.isEmpty()) a = outside;
            t = bracket.group(1);
        }

        t = t.replaceAll("(?i)\\s*[(（\\[]?\\s*(feat\\.?|ft\\.?)\\s[^)）\\]]*[)）\\]]?", "").replaceAll("\\s+", " ").strip();
        return new String[]{t, a};
    }

    private static boolean has(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && !o.get(key).getAsString().isBlank();
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String safe(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").strip();
    }
}
