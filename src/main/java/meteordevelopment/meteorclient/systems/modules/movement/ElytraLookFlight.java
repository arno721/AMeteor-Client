/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Flies with an elytra in the direction you look. It takes off by itself like the Elytra Navigator does, and sets the speed
 * along the look direction in timed bursts instead of using fireworks. For testing on your own server.
 */
public class ElytraLookFlight extends Module {
    private static final double TICKS_PER_SECOND = 20;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> autoTakeOff = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-take-off")
        .description("Jumps and opens the elytra automatically when you are on the ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("The speed during a burst, in blocks per second.")
        .defaultValue(75)
        .min(1)
        .sliderRange(10, 200)
        .build()
    );

    private final Setting<Double> interval = sgGeneral.add(new DoubleSetting.Builder()
        .name("interval")
        .description("Seconds between the end of one burst and the start of the next.")
        .defaultValue(3)
        .min(0)
        .sliderRange(0, 30)
        .build()
    );

    private final Setting<Double> duration = sgGeneral.add(new DoubleSetting.Builder()
        .name("duration")
        .description("How many seconds each burst lasts.")
        .defaultValue(1)
        .min(0.05)
        .sliderRange(0.1, 10)
        .build()
    );

    private final Setting<Boolean> disableOnLanding = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-landing")
        .description("Turns the module off when you land after a flight.")
        .defaultValue(false)
        .build()
    );

    private int takeOffTimer, flightTick;
    private boolean wasFlying;

    public ElytraLookFlight() {
        super(Categories.Movement, "elytra-look-flight", "Flies in the direction you look: takes off by itself and sets the speed in timed bursts, without fireworks.");
    }

    @Override
    public void onActivate() {
        takeOffTimer = 0;
        flightTick = 0;
        wasFlying = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate()) return;

        if (!hasUsableElytra()) {
            error("Your elytra is gone or broken, disabling.");
            toggle();
            return;
        }

        if (mc.player.isFallFlying()) {
            wasFlying = true;
            flightTick++;
            return;
        }

        if (wasFlying && mc.player.onGround() && disableOnLanding.get()) {
            toggle();
            return;
        }

        wasFlying = false;
        flightTick = 0;

        if (autoTakeOff.get()) takeOff();
    }

    private void takeOff() {
        if (mc.player.onGround()) {
            takeOffTimer = 0;
            mc.player.jumpFromGround();
            return;
        }

        takeOffTimer++;

        // Open the elytra while falling, retrying every few ticks until the server accepts it
        if (mc.player.getDeltaMovement().y < 0 && takeOffTimer % 4 == 0) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
    }

    /** Whether a burst is running: the first part of every interval + duration cycle. */
    private boolean burstActive() {
        int burst = Math.max(1, (int) Math.round(duration.get() * TICKS_PER_SECOND));
        int pause = (int) Math.round(interval.get() * TICKS_PER_SECOND);

        return flightTick % (burst + pause) < burst;
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (!Utils.canUpdate() || !mc.player.isFallFlying() || !burstActive()) return;

        Vec3 look = mc.player.getLookAngle().scale(speed.get() / TICKS_PER_SECOND);
        ((IVec3) event.movement).meteor$set(look.x, look.y, look.z);
    }

    private boolean hasUsableElytra() {
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        return chest.is(Items.ELYTRA) && chest.getMaxDamage() - chest.getDamageValue() > 1;
    }
}
