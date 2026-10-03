/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * The files the Crossbow Ragebot writes its debug log to: a text file that is made to be read by a person, with a block
 * for every shot, and a csv file with one row per shot, for sheets and scripts. Both are added to, and moved aside when
 * they get too big.
 */
final class CrossbowRagebotLog implements AutoCloseable {
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    final File textFile, csvFile;
    private final BufferedWriter text, csv;
    private final String[] columns;

    CrossbowRagebotLog(File folder, String[] columns) throws IOException {
        this.columns = columns;

        folder.mkdirs();
        textFile = new File(folder, "crossbow-ragebot.log");
        csvFile = new File(folder, "crossbow-ragebot-shots.csv");

        rotate(textFile);
        rotate(csvFile);

        boolean newCsv = !csvFile.exists() || csvFile.length() == 0;

        text = Files.newBufferedWriter(textFile.toPath(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        csv = Files.newBufferedWriter(csvFile.toPath(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        if (newCsv) {
            csv.write(String.join(",", columns));
            csv.newLine();
            csv.flush();
        }
    }

    private static void rotate(File file) {
        if (file.length() <= MAX_BYTES) return;

        File old = new File(file.getPath() + ".old");
        old.delete();
        file.renameTo(old);
    }

    /** Writes text as it is. A line break is added when the text does not end with one. */
    synchronized void write(String block) {
        try {
            text.write(block);
            if (!block.endsWith("\n")) text.newLine();
            text.flush();
        } catch (IOException ignored) {
            // A full disk must not stop the game
        }
    }

    /** Writes the row of a shot. Values the shot does not have stay empty. */
    synchronized void row(Map<String, String> values) {
        StringBuilder row = new StringBuilder();

        for (int i = 0; i < columns.length; i++) {
            if (i > 0) row.append(',');
            row.append(escape(values.getOrDefault(columns[i], "")));
        }

        try {
            csv.write(row.toString());
            csv.newLine();
            csv.flush();
        } catch (IOException ignored) {
        }
    }

    private static String escape(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    @Override
    public synchronized void close() {
        try {
            text.close();
            csv.close();
        } catch (IOException ignored) {
        }
    }
}
