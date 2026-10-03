/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.meteor.CustomFontChangedEvent;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.renderer.text.CustomTextRenderer;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.renderer.text.FontFamily;
import meteordevelopment.meteorclient.renderer.text.FontInfo;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PreInit;
import meteordevelopment.meteorclient.utils.i18n.LanguageManager;
import meteordevelopment.meteorclient.utils.render.FontUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Fonts {
    /** LXGW WenKai (霞鶩文楷) is embedded: the monospace one for letters and the normal one for Chinese. */
    public static final String LXGW_MONO = "LXGW WenKai Mono TC";
    public static final String LXGW_TC = "LXGW WenKai TC";

    public static final String[] BUILTIN_FONTS = {LXGW_MONO, LXGW_TC, "JetBrains Mono", "Comfortaa", "Tw Cen MT", "Pixelation"};

    public static String DEFAULT_FONT_FAMILY;
    public static FontFace DEFAULT_FONT;
    /** The font for Chinese, Japanese and the characters the chosen font does not have. */
    public static FontFace CJK_FONT;

    public static final List<FontFamily> FONT_FAMILIES = new ArrayList<>();
    public static CustomTextRenderer RENDERER;

    private Fonts() {
    }

    @PreInit
    public static void refresh() {
        FONT_FAMILIES.clear();

        for (String builtinFont : BUILTIN_FONTS) {
            boolean lxgw = builtinFont.equals(LXGW_MONO) || builtinFont.equals(LXGW_TC);
            FontUtils.loadBuiltin(FONT_FAMILIES, builtinFont, lxgw ? new FontInfo(builtinFont, FontInfo.Type.Regular) : null);
        }

        for (String fontPath : FontUtils.getSearchPaths()) {
            FontUtils.loadSystem(FONT_FAMILIES, new File(fontPath));
        }

        FONT_FAMILIES.sort(Comparator.comparing(FontFamily::getName));

        MeteorClient.LOG.info("Found {} font families.", FONT_FAMILIES.size());

        DEFAULT_FONT_FAMILY = LXGW_MONO;
        DEFAULT_FONT = getFamily(DEFAULT_FONT_FAMILY).get(FontInfo.Type.Regular);
        CJK_FONT = getFamily(LXGW_TC).get(FontInfo.Type.Regular);

        Config config = Config.get();
        load(config != null ? config.font.get() : DEFAULT_FONT);
    }

    public static void load(FontFace fontFace) {
        if (RENDERER != null) {
            if (RENDERER.fontFace.equals(fontFace)) return;
            else RENDERER.destroy();
        }

        try {
            RENDERER = new CustomTextRenderer(fontFace, CJK_FONT);
            LanguageManager.refreshFontSupport();
            MeteorClient.EVENT_BUS.post(CustomFontChangedEvent.get());
        } catch (Exception e) {
            if (fontFace.equals(DEFAULT_FONT)) {
                throw new RuntimeException("Failed to load default font: " + fontFace, e);
            }

            MeteorClient.LOG.error("Failed to load font: {}", fontFace, e);
            load(Fonts.DEFAULT_FONT);
        }

        if (mc.gui.screen() instanceof WidgetScreen widgetScreen && Config.get().customFont.get()) {
            widgetScreen.invalidate();
        }
    }

    /** Whether the custom font can draw every character of the text. True when there is no custom font yet. */
    public static boolean canRender(String text) {
        return RENDERER == null || RENDERER.canRender(text);
    }

    /**
     * The default font used to be Comfortaa, which cannot draw Chinese. Moves a saved Comfortaa to the new default once,
     * so players who picked it on purpose afterwards can choose it again.
     */
    public static void migrateDefaultFont() {
        File marker = new File(MeteorClient.FOLDER, "font-migrated-1");
        if (marker.exists()) return;

        try {
            Config config = Config.get();

            if (config != null && DEFAULT_FONT != null && config.font.get().info.family().equals("Comfortaa")) {
                config.font.set(DEFAULT_FONT);
            }

            marker.getParentFile().mkdirs();
            marker.createNewFile();
        } catch (Exception e) {
            MeteorClient.LOG.warn("Could not move the font setting to the new default.", e);
        }
    }

    public static FontFamily getFamily(String name) {
        for (FontFamily fontFamily : Fonts.FONT_FAMILIES) {
            if (fontFamily.getName().equalsIgnoreCase(name)) {
                return fontFamily;
            }
        }

        return null;
    }
}
