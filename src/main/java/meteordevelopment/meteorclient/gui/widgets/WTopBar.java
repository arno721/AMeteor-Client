/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.widgets;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.i18n.LanguageManager;
import net.minecraft.client.gui.screens.Screen;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static com.mojang.blaze3d.platform.InputConstants.*;

public abstract class WTopBar extends WVerticalList {
    protected abstract Color getButtonColor(boolean pressed, boolean hovered);

    protected abstract Color getNameColor();

    public WTopBar() {
        spacing = 0;
    }

    @Override
    public void init() {
        for (Tab tab : Tabs.get()) {
            add(new WTopBarButton(tab));
        }
    }

    protected class WTopBarButton extends WPressable {
        private final Tab tab;

        public WTopBarButton(Tab tab) {
            this.tab = tab;
        }

        @Override
        protected void onCalculateSize() {
            double pad = pad();
            String name = LanguageManager.translate("tab." + tab.name.toLowerCase(java.util.Locale.ROOT) + ".name", tab.name);
            double maxWidth = 0;
            for (int i = 0; i < name.length(); i++) maxWidth = Math.max(maxWidth, theme.textWidth(name.substring(i, i + 1)));
            width = maxWidth + pad * 2;
            height = theme.textHeight() * Math.max(1, name.length()) + pad * 2;
        }

        @Override
        protected void onPressed(int button) {
            Screen screen = mc.gui.screen();

            if (!(screen instanceof TabScreen tabScreen) || tabScreen.tab != tab) {
                double mouseX = mc.mouseHandler.xpos();
                double mouseY = mc.mouseHandler.ypos();

                tab.openScreen(theme);
                grabOrReleaseMouse(mc.getWindow(), CURSOR_NORMAL, mouseX, mouseY);
            }
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            double pad = pad();
            Color color = getButtonColor(pressed || (mc.gui.screen() instanceof TabScreen tabScreen && tabScreen.tab == tab), mouseOver);

            renderer.quad(x, y, width, height, color);
            String name = LanguageManager.translate("tab." + tab.name.toLowerCase(java.util.Locale.ROOT) + ".name", tab.name);
            double textY = y + pad;
            for (int i = 0; i < name.length(); i++) {
                String character = name.substring(i, i + 1);
                double textX = x + (width - theme.textWidth(character)) / 2;
                renderer.text(character, textX, textY, getNameColor(), false);
                textY += theme.textHeight();
            }
        }
    }
}
