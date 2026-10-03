/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Sneak;
import meteordevelopment.meteorclient.systems.modules.movement.VulcanFly;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void isPressed(CallbackInfo ci) {
        if (Modules.get().get(Sneak.class).doVanilla() || Modules.get().get(Freecam.class).staySneaking())
            keyPresses = new Input(
                keyPresses.forward(),
                keyPresses.backward(),
                keyPresses.left(),
                keyPresses.right(),
                keyPresses.jump(),
                true,
                keyPresses.sprint()
            );
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void blockHorizontalMovement(CallbackInfo ci) {
        VulcanFly vulcanFly = Modules.get().get(VulcanFly.class);

        // Packet mode moves by exact steps, so the player gets no vanilla input at all (no jumping or sneaking either)
        if (vulcanFly.overridesInput()) {
            moveVector = Vec2.ZERO;
            keyPresses = new Input(false, false, false, false, false, false, false);
            return;
        }

        if (!vulcanFly.blockHorizontal()) return;

        // Moving up or down in Vulcan Fly: drop every horizontal input
        moveVector = Vec2.ZERO;
        keyPresses = new Input(false, false, false, false, keyPresses.jump(), keyPresses.shift(), false);
    }
}
