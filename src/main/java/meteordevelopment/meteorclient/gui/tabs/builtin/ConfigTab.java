/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.tabs.builtin;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.i18n.LanguageManager;
import meteordevelopment.meteorclient.utils.i18n.LanguageManager.LanguagePack;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.prompts.YesNoPrompt;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class ConfigTab extends Tab {
    public ConfigTab() {
        super("Config");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new ConfigScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof ConfigScreen;
    }

    public static class ConfigScreen extends WindowTabScreen {
        private final Settings settings;

        public ConfigScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);

            settings = Config.get().settings;
            settings.onActivated();

            onClosed(() -> {
                String prefix = Config.get().prefix.get();

                if (prefix.isBlank()) {
                    YesNoPrompt.create(theme, this.parent)
                        .title("Empty command prefix")
                        .message("You have set your command prefix to nothing.")
                        .message("This WILL prevent you from sending chat messages.")
                        .message("Do you want to reset your prefix back to '.'?")
                        .onYes(() -> Config.get().prefix.set("."))
                        .id("empty-command-prefix")
                        .show();
                } else if (prefix.equals("/")) {
                    YesNoPrompt.create(theme, this.parent)
                        .title("Potential prefix conflict")
                        .message("You have set your command prefix to '/', which is used by minecraft.")
                        .message("This can cause conflict issues between meteor and minecraft commands.")
                        .message("Do you want to reset your prefix to '.'?")
                        .onYes(() -> Config.get().prefix.set("."))
                        .id("minecraft-prefix-conflict")
                        .show();
                } else if (prefix.length() > 7) {
                    YesNoPrompt.create(theme, this.parent)
                        .title("Long command prefix")
                        .message("You have set your command prefix to a very long string.")
                        .message("This means that in order to execute any command, you will need to type %s followed by the command you want to run.", prefix)
                        .message("Do you want to reset your prefix back to '.'?")
                        .onYes(() -> Config.get().prefix.set("."))
                        .id("long-command-prefix")
                        .show();
                }
            });
        }

        @Override
        public void initWidgets() {
            add(theme.settings(settings)).expandX();
            initLanguageWidgets();
        }

        /** Export, import and the list of language packs. */
        private void initLanguageWidgets() {
            WSection section = add(theme.section(tr("gui.language.title", "Language packs"))).expandX().widget();

            LanguagePack active = LanguageManager.getActive();
            section.add(theme.label(tr("gui.language.active", "Active: %s (%s)").formatted(active.name(), active.code())));

            for (LanguagePack pack : LanguageManager.getPacks()) {
                if (pack.code().equals(LanguageManager.ENGLISH)) continue;

                String origin = pack.builtIn() ? tr("gui.language.built-in", "built in") : tr("gui.language.imported", "imported");
                String author = pack.author().isBlank() ? "" : " - " + pack.author();
                int percent = (int) Math.round(LanguageManager.coverage(pack) * 100);
                section.add(theme.label("  %s (%s)  %d%%  %s%s".formatted(pack.name(), pack.code(), percent, origin, author)));
            }

            WHorizontalList first = section.add(theme.horizontalList()).expandX().widget();

            WButton exportCurrent = first.add(theme.button(tr("gui.language.export", "Export current language"))).expandX().widget();
            exportCurrent.action = () -> export(false);

            WButton exportTemplate = first.add(theme.button(tr("gui.language.export-template", "Export English template"))).expandX().widget();
            exportTemplate.action = () -> export(true);

            WHorizontalList second = section.add(theme.horizontalList()).expandX().widget();

            WButton importButton = second.add(theme.button(tr("gui.language.import", "Import language file"))).expandX().widget();
            importButton.action = this::importPack;

            WButton folder = second.add(theme.button(tr("gui.language.folder", "Open folder"))).expandX().widget();
            folder.action = () -> {
                File dir = LanguageManager.getFolder();
                dir.mkdirs();
                Util.getPlatform().openFile(dir);
            };

            WButton reloadButton = second.add(theme.button(tr("gui.language.reload", "Reload"))).expandX().widget();
            reloadButton.action = () -> {
                LanguageManager.reload();
                reload();
            };

            section.add(theme.label(tr("gui.language.help", "Export a file, translate the texts in \"strings\" with any editor, set the code and name in \"_meta\", then import it.")));
        }

        private void export(boolean template) {
            LanguagePack active = LanguageManager.getActive();
            String name = template ? "meteor-language-template.json" : "meteor-language-" + active.code() + ".json";
            File suggested = new File(LanguageManager.getFolder(), name);
            LanguageManager.getFolder().mkdirs();

            String path;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.json"));
                filters.flip();
                path = TinyFileDialogs.tinyfd_saveFileDialog(tr("gui.language.export", "Export current language"), suggested.getAbsolutePath(), filters, "JSON (*.json)");
            }

            if (path == null) return;
            if (!path.toLowerCase(Locale.ROOT).endsWith(".json")) path += ".json";

            try {
                int count = LanguageManager.export(new File(path), template);
                ChatUtils.infoPrefix("Language", tr("gui.language.exported", "Exported %d texts to %s").formatted(count, path));
            } catch (IOException e) {
                ChatUtils.errorPrefix("Language", tr("gui.language.export-failed", "Could not export: %s").formatted(e.getMessage()));
            }
        }

        private void importPack() {
            String path;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.json"));
                filters.flip();
                path = TinyFileDialogs.tinyfd_openFileDialog(tr("gui.language.import", "Import language file"), null, filters, "JSON (*.json)", false);
            }

            if (path == null) return;

            try {
                LanguagePack pack = LanguageManager.importFile(new File(path));
                Config.get().language.set(pack.code());
                ChatUtils.infoPrefix("Language", tr("gui.language.imported-message", "Imported %s (%s) with %d texts.").formatted(pack.name(), pack.code(), pack.strings().size()));
                reload();
            } catch (IOException e) {
                ChatUtils.errorPrefix("Language", tr("gui.language.import-failed", "Could not import: %s").formatted(e.getMessage()));
            }
        }

        private static String tr(String key, String english) {
            return LanguageManager.translate(key, english);
        }

        @Override
        public void tick() {
            super.tick();

            settings.tick(window, theme);
        }

        @Override
        public boolean toClipboard() {
            return NbtUtils.toClipboard(Config.get());
        }

        @Override
        public boolean fromClipboard() {
            return NbtUtils.fromClipboard(Config.get());
        }
    }
}
