/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.hud.*;
import meteordevelopment.meteorclient.utils.i18n.LanguageManager;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class EntityListHud extends HudElement {
    public static final HudElementInfo<EntityListHud> INFO = new HudElementInfo<>(Hud.GROUP, "entity-list", "Displays nearby players, mobs and dropped items with their distance.", EntityListHud::new);

    public enum SortMode {
        Distance,
        Farthest,
        Name,
        Count,
        NameLength
    }

    private record ColorRule(String keyword, Color color) {
    }

    private static final Comparator<Entry> BY_DISTANCE = Comparator.comparingDouble(e -> e.distance);
    private static final Comparator<Entry> BY_FARTHEST = BY_DISTANCE.reversed();
    private static final Comparator<Entry> BY_NAME = Comparator.comparing((Entry e) -> e.name, String.CASE_INSENSITIVE_ORDER).thenComparing(BY_DISTANCE);
    private static final Comparator<Entry> BY_COUNT = Comparator.comparingInt((Entry e) -> -e.count).thenComparing(BY_DISTANCE);
    private static final Comparator<Entry> BY_NAME_LENGTH = Comparator.comparingInt((Entry e) -> e.name.length()).thenComparing(BY_NAME);
    private static final Comparator<Entry> HIGHLIGHTED_FIRST = Comparator.comparing((Entry e) -> !e.highlighted);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgStacking = settings.createGroup("Stacking");
    private final SettingGroup sgSorting = settings.createGroup("Sorting");
    private final SettingGroup sgHighlight = settings.createGroup("Highlight");
    private final SettingGroup sgColors = settings.createGroup("Colors");
    private final SettingGroup sgScale = settings.createGroup("Scale");
    private final SettingGroup sgBackground = settings.createGroup("Background");

    // General

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Only entities within this many blocks are listed.")
        .defaultValue(128)
        .min(1)
        .sliderRange(16, 256)
        .build()
    );

    private final Setting<Integer> limit = sgGeneral.add(new IntSetting.Builder()
        .name("limit")
        .description("The max number of rows shown per category.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Boolean> showTitle = sgGeneral.add(new BoolSetting.Builder()
        .name("title")
        .description("Shows the \"Entities\" title.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showHeaders = sgGeneral.add(new BoolSetting.Builder()
        .name("category-headers")
        .description("Shows a header above each category.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showTotals = sgGeneral.add(new BoolSetting.Builder()
        .name("category-totals")
        .description("Shows how many entities are in a category next to its header.")
        .defaultValue(true)
        .visible(showHeaders::get)
        .build()
    );

    private final Setting<Boolean> showDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("distance")
        .description("Shows the distance to the entity. Stacks show the nearest one.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Renders shadow behind text.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Horizontal alignment.")
        .defaultValue(Alignment.Auto)
        .build()
    );

    private final Setting<Integer> border = sgGeneral.add(new IntSetting.Builder()
        .name("border")
        .description("How much space to add around the element.")
        .defaultValue(0)
        .build()
    );

    // Filter

    private final Setting<Boolean> showPlayers = sgFilter.add(new BoolSetting.Builder()
        .name("players")
        .description("Lists nearby players.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showFriends = sgFilter.add(new BoolSetting.Builder()
        .name("display-friends")
        .description("Whether to list friends.")
        .defaultValue(true)
        .visible(showPlayers::get)
        .build()
    );

    private final Setting<Boolean> showMobs = sgFilter.add(new BoolSetting.Builder()
        .name("mobs")
        .description("Lists nearby mobs.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hostile = sgFilter.add(new BoolSetting.Builder()
        .name("hostile")
        .description("Includes hostile mobs.")
        .defaultValue(true)
        .visible(showMobs::get)
        .build()
    );

    private final Setting<Boolean> neutral = sgFilter.add(new BoolSetting.Builder()
        .name("neutral")
        .description("Includes neutral mobs.")
        .defaultValue(true)
        .visible(showMobs::get)
        .build()
    );

    private final Setting<Boolean> passive = sgFilter.add(new BoolSetting.Builder()
        .name("passive")
        .description("Includes passive mobs.")
        .defaultValue(true)
        .visible(showMobs::get)
        .build()
    );

    private final Setting<Boolean> showItems = sgFilter.add(new BoolSetting.Builder()
        .name("items")
        .description("Lists nearby dropped items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyHighlighted = sgFilter.add(new BoolSetting.Builder()
        .name("only-highlighted")
        .description("Only lists entities that match the highlight settings.")
        .defaultValue(false)
        .build()
    );

    // Stacking

    private final Setting<Boolean> stackItems = sgStacking.add(new BoolSetting.Builder()
        .name("stack-items")
        .description("Combines dropped items with the same name into one row with the total count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> stackMobs = sgStacking.add(new BoolSetting.Builder()
        .name("stack-mobs")
        .description("Combines mobs with the same name into one row with the number of them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> stackPlayers = sgStacking.add(new BoolSetting.Builder()
        .name("stack-players")
        .description("Combines players with the same name. Only matters with name spoofing or duplicated entries.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showSingleCount = sgStacking.add(new BoolSetting.Builder()
        .name("show-single-count")
        .description("Also shows \"x1\" for rows that are not stacked.")
        .defaultValue(false)
        .build()
    );

    // Sorting

    private final Setting<SortMode> sortMode = sgSorting.add(new EnumSetting.Builder<SortMode>()
        .name("sort-by")
        .description("How rows are sorted inside each category. Name length puts the shortest names first.")
        .defaultValue(SortMode.Distance)
        .build()
    );

    private final Setting<Boolean> reverse = sgSorting.add(new BoolSetting.Builder()
        .name("reverse")
        .description("Reverses the sort order.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pinHighlighted = sgSorting.add(new BoolSetting.Builder()
        .name("pin-highlighted")
        .description("Keeps highlighted rows at the top of their category.")
        .defaultValue(true)
        .build()
    );

    // Highlight

    private final Setting<List<Item>> highlightItems = sgHighlight.add(new ItemListSetting.Builder()
        .name("highlight-items")
        .description("Dropped items to look for. They are highlighted and can be pinned to the top.")
        .build()
    );

    private final Setting<java.util.Set<EntityType<?>>> highlightMobs = sgHighlight.add(new EntityTypeListSetting.Builder()
        .name("highlight-mobs")
        .description("Mob types to look for.")
        .build()
    );

    private final Setting<List<String>> highlightPlayers = sgHighlight.add(new StringListSetting.Builder()
        .name("highlight-players")
        .description("Player names to look for.")
        .build()
    );

    private final Setting<List<String>> search = sgHighlight.add(new StringListSetting.Builder()
        .name("search")
        .description("Keywords to look for. Any player, mob or item whose name contains one of them is highlighted.")
        .build()
    );

    private final Setting<SettingColor> highlightColor = sgHighlight.add(new ColorSetting.Builder()
        .name("highlight-color")
        .description("Color of highlighted rows.")
        .defaultValue(new SettingColor(255, 215, 0))
        .build()
    );

    // Colors

    private final Setting<SettingColor> titleColor = sgColors.add(new ColorSetting.Builder()
        .name("title-color")
        .description("Color of the title.")
        .defaultValue(new SettingColor())
        .build()
    );

    private final Setting<SettingColor> headerColor = sgColors.add(new ColorSetting.Builder()
        .name("header-color")
        .description("Color of the category headers.")
        .defaultValue(new SettingColor(175, 175, 175))
        .build()
    );

    private final Setting<SettingColor> playerColor = sgColors.add(new ColorSetting.Builder()
        .name("player-color")
        .description("Color of player names. Friends keep their friend color.")
        .defaultValue(new SettingColor())
        .build()
    );

    private final Setting<SettingColor> hostileColor = sgColors.add(new ColorSetting.Builder()
        .name("hostile-color")
        .description("Color of hostile mobs.")
        .defaultValue(new SettingColor(255, 85, 85))
        .build()
    );

    private final Setting<SettingColor> neutralColor = sgColors.add(new ColorSetting.Builder()
        .name("neutral-color")
        .description("Color of neutral mobs.")
        .defaultValue(new SettingColor(255, 170, 0))
        .build()
    );

    private final Setting<SettingColor> passiveColor = sgColors.add(new ColorSetting.Builder()
        .name("passive-color")
        .description("Color of passive mobs.")
        .defaultValue(new SettingColor(85, 255, 85))
        .build()
    );

    private final Setting<SettingColor> itemColor = sgColors.add(new ColorSetting.Builder()
        .name("item-color")
        .description("Color of dropped items.")
        .defaultValue(new SettingColor())
        .build()
    );

    private final Setting<SettingColor> countColor = sgColors.add(new ColorSetting.Builder()
        .name("count-color")
        .description("Color of the stack count.")
        .defaultValue(new SettingColor(255, 255, 85))
        .build()
    );

    private final Setting<SettingColor> distanceColor = sgColors.add(new ColorSetting.Builder()
        .name("distance-color")
        .description("Color of the distance.")
        .defaultValue(new SettingColor(175, 175, 175))
        .build()
    );

    private final Setting<List<String>> colorRules = sgColors.add(new StringListSetting.Builder()
        .name("color-rules")
        .description("Custom colors by name, one per entry as keyword=RRGGBB, for example diamond=55FFFF. Matches part of the name, ignoring case.")
        .build()
    );

    // Scale

    private final Setting<Boolean> customScale = sgScale.add(new BoolSetting.Builder()
        .name("custom-scale")
        .description("Applies a custom scale to this hud element.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> scale = sgScale.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Custom scale.")
        .visible(customScale::get)
        .defaultValue(1)
        .min(0.5)
        .sliderRange(0.5, 3)
        .build()
    );

    // Background

    private final Setting<Boolean> background = sgBackground.add(new BoolSetting.Builder()
        .name("background")
        .description("Displays background.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgBackground.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Color used for the background.")
        .visible(background::get)
        .defaultValue(new SettingColor(25, 25, 25, 50))
        .build()
    );

    private final Section players = new Section("players", "Players");
    private final Section mobs = new Section("mobs", "Mobs");
    private final Section items = new Section("items", "Items");
    private final Section[] sections = {players, mobs, items};

    private final List<ColorRule> rules = new ArrayList<>();
    private int rulesHash = Integer.MIN_VALUE;

    private long lastUpdate = Long.MIN_VALUE;

    public EntityListHud() {
        super(INFO);
    }

    @Override
    public void setSize(double width, double height) {
        super.setSize(width + border.get() * 2, height + border.get() * 2);
    }

    @Override
    protected double alignX(double width, Alignment alignment) {
        return box.alignX(getWidth() - border.get() * 2, width, alignment);
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scale = getScale();
        String title = LanguageManager.translate("hud.entity-list.title", "Entities");
        double lineHeight = renderer.textHeight(shadow.get(), scale) + 2;

        // Entities only change once per game tick, so the lists are not rebuilt every frame.
        long time = mc.level != null ? mc.level.getGameTime() : Long.MIN_VALUE;
        boolean rebuild = time != lastUpdate || isInEditor();
        lastUpdate = time;

        if (rebuild) collect();

        double width = showTitle.get() ? renderer.textWidth(title, shadow.get(), scale) : 0;
        double height = showTitle.get() ? lineHeight : 0;

        for (Section section : sections) {
            if (!section.visible()) continue;

            if (showHeaders.get()) {
                width = Math.max(width, renderer.textWidth(section.header(), shadow.get(), scale));
                height += lineHeight;
            }

            int shown = Math.min(section.used, limit.get());
            for (int i = 0; i < shown; i++) {
                Entry entry = section.entries.get(i);
                if (rebuild || entry.width < 0) {
                    entry.width = renderer.textWidth(entry.name, shadow.get(), scale)
                        + renderer.textWidth(entry.countText, shadow.get(), scale)
                        + renderer.textWidth(entry.distanceText, shadow.get(), scale);
                }

                width = Math.max(width, entry.width);
                height += lineHeight;
            }
        }

        setSize(width, Math.max(height - 2, renderer.textHeight(shadow.get(), scale)));
    }

    @Override
    public void render(HudRenderer renderer) {
        double scale = getScale();
        String title = LanguageManager.translate("hud.entity-list.title", "Entities");
        double lineHeight = renderer.textHeight(shadow.get(), scale) + 2;
        double y = this.y + border.get();

        if (background.get()) {
            renderer.quad(this.x, this.y, getWidth(), getHeight(), backgroundColor.get());
        }

        if (showTitle.get()) {
            renderer.text(title, x + border.get() + alignX(renderer.textWidth(title, shadow.get(), scale), alignment.get()), y, titleColor.get(), shadow.get(), scale);
            y += lineHeight;
        }

        for (Section section : sections) {
            if (!section.visible()) continue;

            if (showHeaders.get()) {
                String header = section.header();
                renderer.text(header, x + border.get() + alignX(renderer.textWidth(header, shadow.get(), scale), alignment.get()), y, headerColor.get(), shadow.get(), scale);
                y += lineHeight;
            }

            int shown = Math.min(section.used, limit.get());
            for (int i = 0; i < shown; i++) {
                Entry entry = section.entries.get(i);

                double x = this.x + border.get() + alignX(entry.width, alignment.get());
                x = renderer.text(entry.name, x, y, entry.color, shadow.get(), scale);
                if (!entry.countText.isEmpty()) x = renderer.text(entry.countText, x, y, countColor.get(), shadow.get(), scale);
                if (!entry.distanceText.isEmpty()) renderer.text(entry.distanceText, x, y, distanceColor.get(), shadow.get(), scale);

                y += lineHeight;
            }
        }
    }

    // Collecting

    private void collect() {
        for (Section section : sections) section.reset();

        if (mc.level == null || mc.player == null) return;

        Entity camera = mc.getCameraEntity();
        if (camera == null) return;

        updateRules();

        double maxSq = (double) range.get() * range.get();
        boolean hasHighlights = hasHighlights();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || entity == camera) continue;

            Section section = sectionFor(entity);
            if (section == null) continue;

            double distSq = entity.distanceToSqr(camera);
            if (distSq > maxSq) continue;

            double distance = Math.sqrt(distSq);

            String name;
            int count = 1;

            if (entity instanceof ItemEntity item) {
                ItemStack stack = item.getItem();
                name = stack.getHoverName().getString();
                count = stack.getCount();
            } else {
                name = entity.getName().getString();
            }

            section.total += count;

            boolean stack = section == items ? stackItems.get() : section == mobs ? stackMobs.get() : stackPlayers.get();
            Entry entry = stack ? section.map.get(name) : null;

            if (entry != null) {
                entry.count += count;
                if (distance < entry.distance) entry.distance = distance;
                continue;
            }

            boolean highlighted = hasHighlights && isHighlighted(entity, name);
            if (onlyHighlighted.get() && !highlighted) continue;

            entry = section.next();
            entry.name = name;
            entry.count = count;
            entry.distance = distance;
            entry.highlighted = highlighted;
            entry.color = highlighted ? highlightColor.get() : colorFor(entity, name);

            if (stack) section.map.put(name, entry);
        }

        Comparator<Entry> comparator = comparator();

        for (Section section : sections) {
            if (section.used == 0) continue;

            section.entries.subList(0, section.used).sort(comparator);

            int shown = Math.min(section.used, limit.get());
            for (int i = 0; i < shown; i++) {
                Entry entry = section.entries.get(i);
                entry.countText = entry.count > 1 || (showSingleCount.get() && entry.count == 1) ? " x" + entry.count : "";
                entry.distanceText = showDistance.get() ? " " + Math.round(entry.distance) + "m" : "";
            }
        }
    }

    private Comparator<Entry> comparator() {
        Comparator<Entry> comparator = switch (sortMode.get()) {
            case Distance -> BY_DISTANCE;
            case Farthest -> BY_FARTHEST;
            case Name -> BY_NAME;
            case Count -> BY_COUNT;
            case NameLength -> BY_NAME_LENGTH;
        };

        if (reverse.get()) comparator = comparator.reversed();
        if (pinHighlighted.get()) comparator = HIGHLIGHTED_FIRST.thenComparing(comparator);

        return comparator;
    }

    private Section sectionFor(Entity entity) {
        if (entity instanceof Player player) {
            if (!showPlayers.get()) return null;
            if (!showFriends.get() && Friends.get().isFriend(player)) return null;
            return players;
        }

        if (entity instanceof ItemEntity) return showItems.get() ? items : null;

        if (entity instanceof Mob mob && showMobs.get()) {
            if (mob.getType().getCategory() == MobCategory.MONSTER) return hostile.get() ? mobs : null;
            if (mob instanceof NeutralMob) return neutral.get() ? mobs : null;
            return passive.get() ? mobs : null;
        }

        return null;
    }

    // Highlighting and colors

    private boolean hasHighlights() {
        return !highlightItems.get().isEmpty() || !highlightMobs.get().isEmpty() || !highlightPlayers.get().isEmpty() || !search.get().isEmpty();
    }

    private boolean isHighlighted(Entity entity, String name) {
        if (entity instanceof ItemEntity item && highlightItems.get().contains(item.getItem().getItem())) return true;
        if (entity instanceof Mob && highlightMobs.get().contains(entity.getType())) return true;

        if (entity instanceof Player) {
            for (String player : highlightPlayers.get()) {
                if (name.equalsIgnoreCase(player.trim())) return true;
            }
        }

        List<String> keywords = search.get();
        if (keywords.isEmpty()) return false;

        String lower = name.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            String k = keyword.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && lower.contains(k)) return true;
        }

        return false;
    }

    private Color colorFor(Entity entity, String name) {
        if (!rules.isEmpty()) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (ColorRule rule : rules) {
                if (lower.contains(rule.keyword)) return rule.color;
            }
        }

        if (entity instanceof Player player) return PlayerUtils.getPlayerColor(player, playerColor.get());
        if (entity instanceof ItemEntity) return itemColor.get();

        if (entity.getType().getCategory() == MobCategory.MONSTER) return hostileColor.get();
        if (entity instanceof NeutralMob) return neutralColor.get();
        return passiveColor.get();
    }

    /** Re-parses the "keyword=RRGGBB" rules, but only when the list actually changed. */
    private void updateRules() {
        List<String> raw = colorRules.get();
        int hash = raw.hashCode();
        if (hash == rulesHash) return;
        rulesHash = hash;

        rules.clear();

        for (String entry : raw) {
            int split = entry.lastIndexOf('=');
            if (split <= 0) continue;

            String keyword = entry.substring(0, split).trim().toLowerCase(Locale.ROOT);
            String hex = entry.substring(split + 1).trim();
            if (hex.startsWith("#")) hex = hex.substring(1);
            if (keyword.isEmpty() || hex.length() != 6) continue;

            try {
                int rgb = Integer.parseInt(hex, 16);
                rules.add(new ColorRule(keyword, new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private double getScale() {
        return customScale.get() ? scale.get() : Hud.get().getTextScale();
    }

    /** A pooled list of entries, so collecting does not allocate once the lists have warmed up. */
    private class Section {
        final String key, englishTitle;
        final List<Entry> entries = new ArrayList<>();
        final Map<String, Entry> map = new HashMap<>();
        int used;
        int total;

        Section(String key, String englishTitle) {
            this.key = key;
            this.englishTitle = englishTitle;
        }

        String header() {
            String title = LanguageManager.translate("hud.entity-list." + key, englishTitle);
            return showTotals.get() ? title + " (" + total + ")" : title;
        }

        void reset() {
            used = 0;
            total = 0;
            map.clear();
        }

        boolean visible() {
            if (this == players) return showPlayers.get() && used > 0;
            if (this == items) return showItems.get() && used > 0;
            return showMobs.get() && used > 0;
        }

        Entry next() {
            if (used == entries.size()) entries.add(new Entry());
            return entries.get(used++);
        }
    }

    private static class Entry {
        String name = "";
        String countText = "";
        String distanceText = "";
        Color color = Color.WHITE;
        boolean highlighted;
        int count = 1;
        double distance;
        double width = -1;
    }
}
