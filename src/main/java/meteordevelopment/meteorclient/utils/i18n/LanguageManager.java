/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.i18n;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Language packs. A pack is one JSON file with every text of the client:
 * <pre>{@code
 * {
 *   "_meta": { "code": "zh_tw", "name": "繁體中文", "author": "...", "format": 1 },
 *   "strings": { "module.kill-aura.name": "殺戮光環", ... },
 *   "english": { ... }   // only a reference for translators, ignored when loading
 * }
 * }</pre>
 * English is written in the code, so it needs no pack. Traditional Chinese ships with the client. Players can export a
 * pack from the Config tab, translate it into any language and import it again. Imported packs live in
 * {@code meteor-client/languages} and replace a built-in pack with the same code.
 */
public final class LanguageManager {
    public static final String AUTO = "auto";
    public static final String ENGLISH = "en_us";
    public static final int FORMAT = 1;

    public record LanguagePack(String code, String name, String author, Map<String, String> strings, boolean builtIn) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String[] BUILT_IN = {"zh_tw"};

    private static final Map<String, LanguagePack> PACKS = new LinkedHashMap<>();
    /** Every English text the client ships, so an export also has texts that were not shown yet. */
    private static final Map<String, String> ENGLISH_TEXTS = new LinkedHashMap<>();
    /** Texts asked for while running, with their English. Catches texts of addons. */
    private static final Map<String, String> SEEN = new ConcurrentHashMap<>();

    private static final LanguagePack ENGLISH_PACK = new LanguagePack(ENGLISH, "English", "Meteor Development", Map.of(), true);

    private static String selected = AUTO;
    private static volatile LanguagePack active = ENGLISH_PACK;
    private static long lastLocaleCheck;
    private static boolean loaded;
    /** Whether the active language has characters that the custom fonts cannot draw. */
    private static volatile boolean needsVanillaFont;

    private LanguageManager() {
    }

    public static File getFolder() {
        return new File(MeteorClient.FOLDER, "languages");
    }

    // Choosing

    /** Selects a language by its code, or {@link #AUTO} to follow Minecraft. */
    public static void setLanguage(String code) {
        selected = normalizeSelection(code);
        reload();
    }

    /** Settings saved by older versions used AUTO, ENGLISH and TRADITIONAL_CHINESE. */
    public static String normalizeSelection(String code) {
        if (code == null || code.isBlank()) return AUTO;

        return switch (code.strip()) {
            case "AUTO" -> AUTO;
            case "ENGLISH" -> ENGLISH;
            case "TRADITIONAL_CHINESE" -> "zh_tw";
            default -> code.strip().toLowerCase(Locale.ROOT).replace('-', '_');
        };
    }

    public static String getSelected() {
        return selected;
    }

    public static LanguagePack getActive() {
        return active;
    }

    public static String getActiveLocale() {
        return active.code;
    }

    /** "auto", "en_us" and the codes of every pack, for the language setting. */
    public static String[] availableLanguages() {
        ensureLoaded();

        List<String> codes = new ArrayList<>();
        codes.add(AUTO);
        codes.add(ENGLISH);
        for (String code : PACKS.keySet()) if (!code.equals(ENGLISH)) codes.add(code);
        return codes.toArray(String[]::new);
    }

    public static List<LanguagePack> getPacks() {
        ensureLoaded();

        List<LanguagePack> packs = new ArrayList<>();
        packs.add(ENGLISH_PACK);
        packs.addAll(PACKS.values());
        return Collections.unmodifiableList(packs);
    }

    // Looking up

    public static String translate(String key, String fallback) {
        if (fallback != null) SEEN.putIfAbsent(key, fallback);

        String value = active.strings.get(key);
        return value != null && !value.isBlank() ? value : fallback;
    }

    public static String moduleTitle(Module module) {
        return translate("module." + module.name + ".name", module.getEnglishTitle());
    }

    public static String moduleDescription(Module module) {
        return translate("module." + module.name + ".description", module.getEnglishDescription());
    }

    public static String groupTitle(Module module, String groupName) {
        if (groupName == null || groupName.isBlank()) return groupName;

        String general = translate("setting-group." + groupKey(groupName), groupName);
        if (module == null) return general;

        return translate("module." + module.name + ".group." + groupKey(groupName), general);
    }

    private static String groupKey(String groupName) {
        return groupName.toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
    }

    public static String settingTitle(Module module, Setting<?> setting) {
        return translate("module." + module.name + ".setting." + setting.name + ".name", setting.getEnglishTitle());
    }

    public static String settingDescription(Module module, Setting<?> setting) {
        return translate("module." + module.name + ".setting." + setting.name + ".description", setting.getEnglishDescription());
    }

    public static String configTitle(Setting<?> setting) {
        return translate("config.setting." + setting.name + ".name", setting.getEnglishTitle());
    }

    public static String configDescription(Setting<?> setting) {
        return translate("config.setting." + setting.name + ".description", setting.getEnglishDescription());
    }

    public static String commandTitle(Command command) {
        return translate("command." + command.getName() + ".name", command.getEnglishTitle());
    }

    public static String commandDescription(Command command) {
        return translate("command." + command.getName() + ".description", command.getEnglishDescription());
    }

    /**
     * Whether the active language has characters that the custom fonts (LXGW WenKai, or the font the player picked
     * with it as the fallback) cannot draw, for example Korean. The vanilla font is used then.
     */
    public static boolean needsVanillaFont() {
        if (MeteorClient.mc == null) return false;

        // Auto follows the Minecraft language, checked at most once per second since this is called while rendering
        if (AUTO.equals(selected)) {
            long now = System.currentTimeMillis();

            if (now - lastLocaleCheck >= 1000) {
                lastLocaleCheck = now;
                if (!resolve().code.equals(active.code)) reload();
            }
        }

        return needsVanillaFont;
    }

    /** Checks the active language against the custom fonts again, for example after the font was changed. */
    public static void refreshFontSupport() {
        boolean missing = false;

        for (String value : active.strings.values()) {
            if (!Fonts.canRender(value)) {
                missing = true;
                break;
            }
        }

        needsVanillaFont = missing;
    }

    // Loading

    private static void ensureLoaded() {
        if (!loaded) loadPacks();
    }

    public static void reload() {
        if (MeteorClient.mc == null) return;

        loadPacks();
        active = resolve();
        refreshFontSupport();
        refreshEverything();
    }

    private static void loadPacks() {
        PACKS.clear();
        ENGLISH_TEXTS.clear();

        // The full English reference
        LanguagePack english = readResource("en_us");
        if (english != null) ENGLISH_TEXTS.putAll(english.strings);

        for (String code : BUILT_IN) {
            LanguagePack pack = readResource(code);
            if (pack != null) PACKS.put(pack.code, pack);
        }

        // Imported packs, they win over the built-in ones
        File folder = getFolder();
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));

        if (files != null) {
            java.util.Arrays.sort(files);

            for (File file : files) {
                try {
                    LanguagePack pack = parse(Files.readString(file.toPath(), StandardCharsets.UTF_8), fileCode(file), false);
                    if (pack != null && !pack.code.equals(ENGLISH)) PACKS.put(pack.code, pack);
                } catch (IOException | RuntimeException e) {
                    MeteorClient.LOG.warn("Could not read the language file {}", file, e);
                }
            }
        }

        loaded = true;
    }

    private static LanguagePack readResource(String code) {
        try (InputStream in = LanguageManager.class.getResourceAsStream("/assets/meteor-client/languages/" + code + ".json")) {
            if (in == null) return null;
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), code, true);
        } catch (IOException | RuntimeException e) {
            MeteorClient.LOG.warn("Could not read the built-in language {}", code, e);
            return null;
        }
    }

    /** Reads a pack. Also takes a plain key to text map (the old format), then the code comes from the file name. */
    private static LanguagePack parse(String json, String fallbackCode, boolean builtIn) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) return null;

        JsonObject object = root.getAsJsonObject();
        JsonObject strings = object.has("strings") && object.get("strings").isJsonObject() ? object.getAsJsonObject("strings") : object;
        JsonObject meta = object.has("_meta") && object.get("_meta").isJsonObject() ? object.getAsJsonObject("_meta") : new JsonObject();

        Map<String, String> map = new LinkedHashMap<>();

        for (Map.Entry<String, JsonElement> entry : strings.entrySet()) {
            if (entry.getKey().startsWith("_") || !entry.getValue().isJsonPrimitive()) continue;

            String value = entry.getValue().getAsString();
            map.put(entry.getKey(), value);
        }

        String code = text(meta, "code", fallbackCode).toLowerCase(Locale.ROOT).replace('-', '_');
        String name = text(meta, "name", code);
        String author = text(meta, "author", "");

        return new LanguagePack(code, name, author, Collections.unmodifiableMap(map), builtIn);
    }

    private static String text(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() && !o.get(key).getAsString().isBlank() ? o.get(key).getAsString().strip() : fallback;
    }

    private static String fileCode(File file) {
        String name = file.getName();
        return name.substring(0, name.length() - 5);
    }

    /** Which pack to use for the current selection. */
    private static LanguagePack resolve() {
        if (ENGLISH.equals(selected)) return ENGLISH_PACK;
        if (!AUTO.equals(selected)) return PACKS.getOrDefault(selected, ENGLISH_PACK);

        String locale = getMinecraftLocale().toLowerCase(Locale.ROOT).replace('-', '_');
        LanguagePack exact = PACKS.get(locale);
        if (exact != null) return exact;

        // Same language, other region: zh_cn and zh_hk get zh_tw, pt_pt gets pt_br, and so on
        String language = locale.contains("_") ? locale.substring(0, locale.indexOf('_')) : locale;
        for (LanguagePack pack : PACKS.values()) {
            if (pack.code.equals(language) || pack.code.startsWith(language + "_")) return pack;
        }

        return ENGLISH_PACK;
    }

    private static void refreshEverything() {
        try {
            for (Module module : Modules.get().getAll()) module.refreshLanguage();
        } catch (Throwable ignored) {
        }

        try {
            for (Command command : Commands.COMMANDS) command.refreshLanguage();
        } catch (Throwable ignored) {
        }

        try {
            for (SettingGroup group : Config.get().settings) for (Setting<?> setting : group) setting.refreshLanguage();
        } catch (Throwable ignored) {
        }

        try {
            for (HudElement element : Hud.get()) {
                for (SettingGroup group : element.settings) for (Setting<?> setting : group) setting.refreshLanguage();
            }
        } catch (Throwable ignored) {
        }
    }

    // Exporting and importing

    /**
     * Every text of the client, sorted by key: the modules, settings, commands and config that exist right now
     * (addons included), the full English reference, and anything else that was shown.
     */
    public static Map<String, String> collectEnglish() {
        ensureLoaded();
        Map<String, String> all = new TreeMap<>(ENGLISH_TEXTS);

        try {
            for (Category category : Modules.loopCategories()) all.put("category." + category.name.toLowerCase(Locale.ROOT) + ".name", category.name);
            for (Tab tab : Tabs.get()) all.put("tab." + tab.name.toLowerCase(Locale.ROOT) + ".name", tab.name);

            for (Module module : Modules.get().getAll()) {
                all.put("module." + module.name + ".name", module.getEnglishTitle());
                all.put("module." + module.name + ".description", module.getEnglishDescription());

                for (SettingGroup group : module.settings) {
                    all.put("module." + module.name + ".group." + groupKey(group.name), group.name);

                    for (Setting<?> setting : group) {
                        all.put("module." + module.name + ".setting." + setting.name + ".name", setting.getEnglishTitle());
                        all.put("module." + module.name + ".setting." + setting.name + ".description", setting.getEnglishDescription());
                    }
                }
            }

            for (SettingGroup group : Config.get().settings) {
                all.put("setting-group." + groupKey(group.name), group.name);

                for (Setting<?> setting : group) {
                    all.put("config.setting." + setting.name + ".name", setting.getEnglishTitle());
                    all.put("config.setting." + setting.name + ".description", setting.getEnglishDescription());
                }
            }

            for (HudElement element : Hud.get()) {
                for (SettingGroup group : element.settings) {
                    all.putIfAbsent("setting-group." + groupKey(group.name), group.name);

                    for (Setting<?> setting : group) {
                        all.putIfAbsent("config.setting." + setting.name + ".name", setting.getEnglishTitle());
                        all.putIfAbsent("config.setting." + setting.name + ".description", setting.getEnglishDescription());
                    }
                }
            }

            for (Command command : Commands.COMMANDS) {
                all.put("command." + command.getName() + ".name", command.getEnglishTitle());
                all.put("command." + command.getName() + ".description", command.getEnglishDescription());
            }
        } catch (Throwable t) {
            MeteorClient.LOG.warn("Could not collect every text for the language export", t);
        }

        for (Map.Entry<String, String> entry : SEEN.entrySet()) all.putIfAbsent(entry.getKey(), entry.getValue());
        all.values().removeIf(value -> value == null);

        return all;
    }

    /**
     * Writes a pack file with every text. With {@code template} the texts are English, otherwise they are the active
     * language with English where it has no translation. Returns how many texts were written.
     */
    public static int export(File file, boolean template) throws IOException {
        Map<String, String> english = collectEnglish();
        LanguagePack pack = template ? ENGLISH_PACK : active;

        Map<String, String> strings = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : english.entrySet()) {
            String value = pack.strings.get(entry.getKey());
            strings.put(entry.getKey(), value != null && !value.isBlank() ? value : entry.getValue());
        }

        // Translations of texts that do not exist in this version are kept, an older client might still use them
        for (Map.Entry<String, String> entry : new TreeMap<>(pack.strings).entrySet()) strings.putIfAbsent(entry.getKey(), entry.getValue());

        JsonObject root = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("code", template ? "xx_xx" : pack.code);
        meta.addProperty("name", template ? "My Language" : pack.name);
        meta.addProperty("author", template ? "" : pack.author);
        meta.addProperty("format", FORMAT);
        meta.addProperty("help", "Translate the values in \"strings\", keep the keys. Keep %s, %d, {...}, (highlight) and \\n as they are. "
            + "Set \"code\" to a Minecraft language code (for example ja_jp, ko_kr, zh_cn) and \"name\" to the name of the language. "
            + "\"english\" is only there to compare with and is not read. Import the file in the Config tab.");
        root.add("_meta", meta);
        root.add("strings", GSON.toJsonTree(strings));
        root.add("english", GSON.toJsonTree(english));

        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        Files.writeString(file.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);

        return strings.size();
    }

    /**
     * Copies a pack file into the languages folder and selects it. Returns the pack, or throws with a message that
     * can be shown to the player.
     */
    public static LanguagePack importFile(File file) throws IOException {
        String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        LanguagePack pack;

        try {
            pack = parse(json, fileCode(file), false);
        } catch (RuntimeException e) {
            throw new IOException("Not a valid JSON file: " + e.getMessage());
        }

        if (pack == null || pack.strings.isEmpty()) throw new IOException("The file has no texts.");
        if (pack.code.equals(ENGLISH) || !pack.code.matches("[a-z0-9_]{2,16}")) {
            throw new IOException("Set \"code\" in \"_meta\" to a language code like ja_jp (not en_us).");
        }

        File folder = getFolder();
        folder.mkdirs();
        Files.writeString(new File(folder, pack.code + ".json").toPath(), json, StandardCharsets.UTF_8);

        loadPacks();
        return PACKS.getOrDefault(pack.code, pack);
    }

    /** How much of the client a pack translates, from 0 to 1. */
    public static double coverage(LanguagePack pack) {
        if (pack.strings.isEmpty()) return pack.code.equals(ENGLISH) ? 1 : 0;

        Map<String, String> english = collectEnglish();
        if (english.isEmpty()) return 0;

        long done = english.keySet().stream().filter(key -> {
            String value = pack.strings.get(key);
            return value != null && !value.isBlank();
        }).count();

        return done / (double) english.size();
    }

    // Minecraft language

    private static String getMinecraftLocale() {
        Object manager = MeteorClient.mc.getLanguageManager();

        for (String methodName : new String[]{"getSelected", "getSelectedLanguage", "getLanguageCode", "getCurrentLanguageCode"}) {
            try {
                Method method = manager.getClass().getMethod(methodName);
                String locale = extractLocale(method.invoke(manager));
                if (locale != null) return locale;
            } catch (Throwable ignored) {
            }
        }

        for (Class<?> type = manager.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    String locale = extractLocale(field.get(manager));
                    if (locale != null) return locale;
                } catch (Throwable ignored) {
                }
            }
        }

        return ENGLISH;
    }

    private static String extractLocale(Object value) {
        if (value instanceof String string && looksLikeLocale(string)) return string;
        if (value == null) return null;

        for (String methodName : new String[]{"getCode", "code", "getId", "id"}) {
            try {
                Method method = value.getClass().getMethod(methodName);
                Object result = method.invoke(value);
                if (result instanceof String string && looksLikeLocale(string)) return string;
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static boolean looksLikeLocale(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).replace('-', '_').matches("[a-z]{2,3}(_[a-z]{2,4})?");
    }
}
