/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render.island;

/**
 * Something that can show an ongoing activity on the Dynamic Island.
 * <p>
 * Active modules that implement this are asked automatically. Anything else can be added with
 * {@link meteordevelopment.meteorclient.systems.modules.render.DynamicIsland#register(IslandSource)}. For a one time
 * message use {@link meteordevelopment.meteorclient.systems.modules.render.DynamicIsland#show} instead.
 */
public interface IslandSource {
    /** Warnings about the player itself, like low health. */
    int CRITICAL = 100;
    /** Fighting, like an aura target. */
    int COMBAT = 80;
    /** Long running work with progress, like flying or pathing somewhere. */
    int TASK = 60;
    /** Modes that change how the game behaves, like Freecam or Blink. */
    int MODE = 40;
    /** Anything else. */
    int INFO = 20;

    /**
     * Called once per tick. Fill the card (start with {@link IslandCard#reset}) and return true to show it, or return
     * false when there is nothing to show.
     */
    boolean fillIslandCard(IslandCard card);
}
