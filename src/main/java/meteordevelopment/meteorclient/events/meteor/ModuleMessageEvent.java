/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.events.meteor;

import meteordevelopment.meteorclient.systems.modules.Module;

/** Posted when a module sends a warning or an error to the chat. The message has no formatting codes. */
public class ModuleMessageEvent {
    private static final ModuleMessageEvent INSTANCE = new ModuleMessageEvent();

    public Module module;
    public String message;
    public boolean error;

    public static ModuleMessageEvent get(Module module, String message, boolean error) {
        INSTANCE.module = module;
        INSTANCE.message = message;
        INSTANCE.error = error;
        return INSTANCE;
    }
}
