/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.meteor.KeyInputEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneElytra;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.item.Items;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Picking the destination of the Elytra Navigator with the Freecam: one key starts it, you fly the camera to the place, a second
 * key marks it, and the player flies there. The keys work when the navigator is off too, which is why this listens for them by
 * itself. The flight is done by the elytra pathfinding of Baritone when it is installed, or else by the navigator.
 */
public final class ElytraPick {
    private static boolean picking, baritoneFlying;
    private static int graceTicks, baritoneTicks, takeOffTimer;
    /** The player has been in the air since Baritone got the flight. */
    private static boolean flownYet;
    /** The speed override of the navigator is turned off while Baritone flies, and turned on again after. */
    private static boolean overrideSuspended, overrideWasOn;
    private static BlockPos destination;

    private ElytraPick() {
    }

    /** Whether Baritone flies the elytra now. */
    public static boolean isBaritoneFlying() {
        return baritoneFlying;
    }

    private static void suspendOverride(ElytraNavigator navigator) {
        if (overrideSuspended) return;

        overrideSuspended = true;
        overrideWasOn = navigator.speedOverrideOn();
        if (overrideWasOn) navigator.setSpeedOverride(false);
    }

    private static void restoreOverride(ElytraNavigator navigator) {
        if (!overrideSuspended) return;

        if (overrideWasOn) navigator.setSpeedOverride(true);
        overrideSuspended = false;
        overrideWasOn = false;
    }

    @PostInit
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(ElytraPick.class);
    }

    private static ElytraNavigator navigator() {
        return Modules.get().get(ElytraNavigator.class);
    }

    @EventHandler
    private static void onKey(KeyInputEvent event) {
        if (event.action != KeyAction.Press || !Utils.canUpdate() || mc.gui.screen() != null) return;

        ElytraNavigator navigator = navigator();
        if (navigator == null) return;

        if (matches(navigator.pickBind(), event)) togglePicking(navigator);
        else if (matches(navigator.markBind(), event)) mark(navigator);
        else if (matches(navigator.cancelBind(), event)) cancel(navigator);
    }

    @EventHandler
    private static void onMouse(MouseClickEvent event) {
        if (event.action != KeyAction.Press || !Utils.canUpdate() || mc.gui.screen() != null) return;

        ElytraNavigator navigator = navigator();
        if (navigator == null) return;

        if (navigator.pickBind().isSet() && !navigator.pickBind().isKey() && navigator.pickBind().matches(event.input)) togglePicking(navigator);
        else if (navigator.markBind().isSet() && !navigator.markBind().isKey() && navigator.markBind().matches(event.input)) mark(navigator);
        else if (navigator.cancelBind().isSet() && !navigator.cancelBind().isKey() && navigator.cancelBind().matches(event.input)) cancel(navigator);
    }

    private static boolean matches(Keybind bind, KeyInputEvent event) {
        return bind.isSet() && bind.isKey() && bind.matches(event.input);
    }

    @EventHandler
    private static void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate()) return;

        // The freecam was turned off by hand
        if (picking) {
            Freecam freecam = Modules.get().get(Freecam.class);
            if (!freecam.isActive()) picking = false;
        }

        // Baritone is done flying (it needs a moment to start)
        if (graceTicks > 0) graceTicks--;

        if (baritoneFlying) {
            baritoneTicks++;

            // Baritone only flies once you are in the air (unless its elytraAutoJump is on and there is a ledge), so the take off is
            // done here, but only to get into the air: once the flight has begun, the landing is Baritone's, jumping again there
            // is what makes the landing unsteady
            ElytraNavigator navigator = navigator();

            if (mc.player.isFallFlying()) flownYet = true;
            else if (!flownYet && baritoneTicks < 200 && navigator != null && navigator.baritoneTakeOff()) takeOff();
        }

        if (baritoneFlying && graceTicks == 0 && BaritoneUtils.IS_AVAILABLE && !BaritoneElytra.isActive()) {
            baritoneFlying = false;
            ElytraNavigator navigator = navigator();

            if (navigator != null) {
                restoreOverride(navigator);

                if (baritoneTicks < 200 && destination != null) {
                    // It did not start, or stopped at once: Baritone refused the flight (it does not tell), the navigator takes over
                    navigator.warning("Baritone did not start the flight, flying with the navigator.");
                    if (!navigator.isActive()) navigator.toggle();
                } else {
                    navigator.info("The flight is over.");
                }
            }
        }
    }

    private static void togglePicking(ElytraNavigator navigator) {
        Freecam freecam = Modules.get().get(Freecam.class);

        if (picking) {
            picking = false;
            if (freecam.isActive()) freecam.toggle();
            navigator.info("Picking stopped.");
            return;
        }

        picking = true;
        if (!freecam.isActive()) freecam.toggle();
        navigator.info("Fly the camera to where you want to go, then press the mark key.");
    }

    private static void mark(ElytraNavigator navigator) {
        Freecam freecam = Modules.get().get(Freecam.class);

        if (!picking || !freecam.isActive()) {
            navigator.warning("Press the pick key first, and fly the camera to the place.");
            return;
        }

        int x = Mth.floor(freecam.pos.x), z = Mth.floor(freecam.pos.z);
        int y = Mth.floor(freecam.pos.y);

        // The floor under the camera, if that part of the world is loaded
        if (navigator.markOnGround() && mc.level.hasChunkAt(new BlockPos(x, y, z))) {
            y = floorBelow(x, y, z);
        }

        BlockPos pos = new BlockPos(x, y, z);
        navigator.setTarget(pos);

        picking = false;
        freecam.toggle();

        fly(navigator, pos);
    }

    /**
     * The height to stand at under the camera: down from the camera to the first block that holds you. The height map would be
     * the roof, which is wrong for a camera in a cave or a house. A camera inside a block first goes up to the free space.
     */
    private static int floorBelow(int x, int y, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
        int top = mc.level.getMaxY() - 1;

        for (int i = 0; i < 32 && p.getY() < top && !mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty(); i++) {
            p.move(0, 1, 0);
        }

        for (int level = p.getY(); level > mc.level.getMinY(); level--) {
            p.setY(level - 1);
            if (!mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty()) return level;
        }

        // Nothing below (the void): the height of the camera
        return y;
    }

    private static void fly(ElytraNavigator navigator, BlockPos pos) {
        if (navigator.useBaritone()) {
            if (!BaritoneUtils.IS_AVAILABLE) {
                navigator.warning("Baritone is not installed, flying with the navigator.");
            } else if (!BaritoneElytra.isReady()) {
                navigator.warning("The elytra pathfinding of Baritone is not ready (its native library is not loaded), flying with the navigator.");
            } else if (!InvUtils.find(Items.FIREWORK_ROCKET).found()) {
                // The flight of Baritone is planned with fireworks, without them it does not even take off
                navigator.warning("The elytra flight of Baritone needs firework rockets in the inventory, flying with the navigator.");
            } else {
                if (navigator.isActive()) navigator.toggle();

                BaritoneElytra.pathTo(pos);
                baritoneFlying = true;
                suspendOverride(navigator);
                graceTicks = 60;
                baritoneTicks = 0;
                flownYet = mc.player.isFallFlying();
                destination = pos;
                navigator.info("Flying to %d, %d, %d with Baritone.", pos.getX(), pos.getY(), pos.getZ());
                return;
            }
        }

        if (!navigator.isActive()) navigator.toggle();
        navigator.info("Flying to %d, %d, %d with the navigator.", pos.getX(), pos.getY(), pos.getZ());
    }

    /** Jumps and opens the elytra, like the navigator does. */
    private static void takeOff() {
        if (mc.player.onGround()) {
            takeOffTimer = 0;
            mc.player.jumpFromGround();
            return;
        }

        takeOffTimer++;

        if (mc.player.getDeltaMovement().y < 0 && takeOffTimer % 4 == 0) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
    }

    private static void cancel(ElytraNavigator navigator) {
        Freecam freecam = Modules.get().get(Freecam.class);

        if (picking) {
            picking = false;
            if (freecam.isActive()) freecam.toggle();
        }

        if (baritoneFlying && BaritoneUtils.IS_AVAILABLE) {
            BaritoneElytra.cancel();
            baritoneFlying = false;
        }

        restoreOverride(navigator);

        if (navigator.isActive()) navigator.toggle();
        navigator.info("Stopped.");
    }
}
