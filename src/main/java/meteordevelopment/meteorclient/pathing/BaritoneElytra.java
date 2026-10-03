/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.process.IElytraProcess;
import net.minecraft.core.BlockPos;

/**
 * The elytra pathfinding of Baritone. Only touch this class when {@link BaritoneUtils#IS_AVAILABLE} is true, Baritone is not
 * part of the client and these classes are not there without it.
 */
public final class BaritoneElytra {
    private BaritoneElytra() {
    }

    private static IElytraProcess process() {
        return BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess();
    }

    /** Whether the elytra pathfinding of Baritone can be used (its native library has to be loaded). */
    public static boolean isReady() {
        return process().isLoaded();
    }

    public static void pathTo(BlockPos pos) {
        process().pathTo(pos);
    }

    public static boolean isActive() {
        return process().isActive();
    }

    public static void cancel() {
        process().resetState();
    }
}
