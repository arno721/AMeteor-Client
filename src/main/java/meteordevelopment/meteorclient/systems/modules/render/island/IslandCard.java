/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render.island;

import meteordevelopment.meteorclient.renderer.Texture;
import meteordevelopment.meteorclient.systems.modules.render.DynamicIsland;

/**
 * What the Dynamic Island shows for one activity. Sources fill it once per tick, see {@link IslandSource}.
 * <p>
 * The island shows a card in full for a moment when it appears, then shrinks it to one line. When another activity
 * is more important, the card moves to the small second pill, which shows the icon and the {@link #chip} text.
 */
public class IslandCard {
    /** Cards with the same key are the same activity, so changes to them do not restart the animation. */
    public String key = "";

    /** Which card wins when several want to show. See the constants in {@link IslandSource}. */
    public int priority = IslandSource.INFO;

    public DynamicIsland.Icon icon = DynamicIsland.Icon.INFO;

    /** Color of the badge, the value, the progress and the glow, as ARGB. */
    public int accent = 0xFFFFFFFF;

    /** The full card. */
    public String title = "", subtitle = "", value = "";

    /** The one line card. When empty, the title and the value are used. */
    public String compactTitle = "", compactValue = "";

    /** Short text next to the icon in the small second pill, for example "12" or "75%". Can be empty. */
    public String chip = "";

    /** From 0 to 1, or negative for no progress bar. */
    public double progress = -1;

    /** Used by some icons: the number of lit bars for {@link DynamicIsland.Icon#PING}, the dial for SPEED. */
    public int level;
    public double gauge;

    /** Makes the icon beat, for warnings. */
    public boolean pulse;

    /** Shows the card in full when it appears. When false it starts as one line. */
    public boolean expand = true;

    /** A picture shown in the badge instead of the icon, for example an album cover. Can be null. */
    public Texture image;

    /** Turns the picture slowly, like a record. */
    public boolean spin;

    /** Shows moving sound bars where the value would be, when there is no value. They lie flat when not live. */
    public boolean waveform, waveformLive;

    public void reset(String key, int priority) {
        this.key = key;
        this.priority = priority;
        icon = DynamicIsland.Icon.INFO;
        accent = 0xFFFFFFFF;
        title = subtitle = value = compactTitle = compactValue = chip = "";
        progress = -1;
        level = 0;
        gauge = 0;
        pulse = false;
        expand = true;
        image = null;
        spin = false;
        waveform = waveformLive = false;
    }

    public void set(IslandCard o) {
        key = o.key;
        priority = o.priority;
        icon = o.icon;
        accent = o.accent;
        title = o.title;
        subtitle = o.subtitle;
        value = o.value;
        compactTitle = o.compactTitle;
        compactValue = o.compactValue;
        chip = o.chip;
        progress = o.progress;
        level = o.level;
        gauge = o.gauge;
        pulse = o.pulse;
        expand = o.expand;
        image = o.image;
        spin = o.spin;
        waveform = o.waveform;
        waveformLive = o.waveformLive;
    }

    public String compactTitle() {
        return compactTitle.isEmpty() ? title : compactTitle;
    }

    public String compactValue() {
        return compactValue.isEmpty() ? value : compactValue;
    }
}
