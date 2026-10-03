/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.events.meteor;

import meteordevelopment.meteorclient.systems.modules.Module;

public class ModuleToggledEvent {
    private static final ModuleToggledEvent INSTANCE = new ModuleToggledEvent();

    public Module module;
    public boolean active;

    public static ModuleToggledEvent get(Module module, boolean active) {
        INSTANCE.module = module;
        INSTANCE.active = active;
        return INSTANCE;
    }
}
