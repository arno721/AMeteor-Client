/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lyrics of one track. Synced lyrics have a time for every line, plain lyrics do not. */
public final class Lyrics {
    public static final Lyrics NONE = new Lyrics(List.of(), false, "");

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);

    public record Line(double time, String text) {
    }

    private final List<Line> lines;
    private final boolean synced;
    /** Where the lyrics came from, for example "LRCLIB" or a file name. */
    public final String source;

    public Lyrics(List<Line> lines, boolean synced, String source) {
        this.lines = lines;
        this.synced = synced;
        this.source = source;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public boolean isSynced() {
        return synced;
    }

    public int size() {
        return lines.size();
    }

    public Line get(int index) {
        return index >= 0 && index < lines.size() ? lines.get(index) : null;
    }

    public String text(int index) {
        Line line = get(index);
        return line == null ? "" : line.text;
    }

    /** The line being sung at this time, or -1 before the first line (and always for plain lyrics). */
    public int indexAt(double seconds) {
        if (!synced || lines.isEmpty()) return -1;

        int lo = 0, hi = lines.size() - 1, found = -1;

        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;

            if (lines.get(mid).time <= seconds) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }

        return found;
    }

    /** How far the line is, from 0 at its start to 1 when the next line starts. */
    public double lineProgress(int index, double seconds, double trackLength) {
        Line line = get(index);
        if (line == null) return 0;

        Line next = get(index + 1);
        double end = next != null ? next.time : Math.max(line.time + 4, trackLength);
        // Long pauses between lines should not make the highlight crawl, a line is sung in at most 8 seconds
        end = Math.min(end, line.time + Math.max(8, line.text.length() * 0.35));

        return end > line.time ? Math.min(Math.max((seconds - line.time) / (end - line.time), 0), 1) : 1;
    }

    /** Reads LRC text. Lines without a time make it plain lyrics. */
    public static Lyrics parseLrc(String text, String source) {
        if (text == null || text.isBlank()) return NONE;

        double offset = 0;
        Matcher offsetMatcher = OFFSET.matcher(text);
        if (offsetMatcher.find()) offset = Integer.parseInt(offsetMatcher.group(1)) / 1000.0;

        List<Line> timed = new ArrayList<>();
        List<Line> plain = new ArrayList<>();

        for (String raw : text.split("\\r?\\n")) {
            Matcher m = TIME.matcher(raw);
            List<Double> times = new ArrayList<>();
            int end = 0;

            // A line can have several times: [00:12.00][01:30.00]chorus
            while (m.find() && m.start() == end) {
                double minutes = Integer.parseInt(m.group(1));
                double seconds = Integer.parseInt(m.group(2));
                String fraction = m.group(3);
                double frac = fraction == null ? 0 : Integer.parseInt(fraction) / Math.pow(10, fraction.length());

                times.add(minutes * 60 + seconds + frac);
                end = m.end();
            }

            String lyric = clean(raw.substring(end));

            if (!times.isEmpty()) {
                // In LRC a positive offset makes the lyrics show earlier
                for (double t : times) timed.add(new Line(Math.max(0, t - offset), lyric));
            } else if (!raw.startsWith("[") || !raw.contains(":")) {
                if (!lyric.isEmpty()) plain.add(new Line(0, lyric));
            }
        }

        if (!timed.isEmpty()) {
            timed.sort((a, b) -> Double.compare(a.time, b.time));
            return new Lyrics(Collections.unmodifiableList(timed), true, source);
        }

        return plain.isEmpty() ? NONE : new Lyrics(Collections.unmodifiableList(plain), false, source);
    }

    /** Plain text, one line per line. */
    public static Lyrics parsePlain(String text, String source) {
        if (text == null || text.isBlank()) return NONE;

        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String lyric = clean(raw);
            if (!lyric.isEmpty()) lines.add(new Line(0, lyric));
        }

        return lines.isEmpty() ? NONE : new Lyrics(Collections.unmodifiableList(lines), false, source);
    }

    private static String clean(String text) {
        // Word timing tags like <00:12.34> are not used
        return text.replaceAll("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>", "").strip();
    }
}
