package me.settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Общие помощники для красивых меню: неоновый градиент, предметы, рамка, звуки. */
public final class Gui {
    private Gui() {}

    public static final TextColor YELLOW = TextColor.fromHexString("#FFF200");
    public static final TextColor AMBER = TextColor.fromHexString("#FF9D00");
    public static final TextColor ORANGE = TextColor.fromHexString("#FF5A00");
    public static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    public static String prefix = "Settings";

    // ---------- текст ----------
    public static Component gradient(String text, TextColor... stops) {
        Component out = Component.empty()
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false);
        int n = text.length();
        for (int i = 0; i < n; i++) {
            float t = n == 1 ? 0f : (float) i / (n - 1);
            float scaled = t * (stops.length - 1);
            int seg = Math.min((int) scaled, stops.length - 2);
            float k = scaled - seg;
            TextColor a = stops[seg], b = stops[seg + 1];
            int r = Math.round(a.red() + (b.red() - a.red()) * k);
            int g = Math.round(a.green() + (b.green() - a.green()) * k);
            int bl = Math.round(a.blue() + (b.blue() - a.blue()) * k);
            out = out.append(Component.text(String.valueOf(text.charAt(i)), TextColor.color(r, g, bl)));
        }
        return out;
    }

    public static Component neon(String text) {
        return gradient(text, YELLOW, AMBER, ORANGE);
    }

    public static Component legacy(String s) {
        return LEGACY.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    public static void msg(Player p, String text) {
        p.sendMessage(neon(prefix).append(LEGACY.deserialize(" §8» §f" + text)));
    }

    public static void sound(Player p, Sound s, float pitch) {
        p.playSound(p.getLocation(), s, 0.6f, pitch);
    }

    public static String pretty(String enumName) {
        String s = enumName.toLowerCase().replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String dur(long sec) {
        long d = sec / 86400, h = (sec % 86400) / 3600, m = (sec % 3600) / 60;
        if (d > 0) return d + "д " + h + "ч";
        if (h > 0) return h + "ч " + m + "м";
        return Math.max(1, m) + "м";
    }

    public static String bar(int cur, int max, int len) {
        int filled = max <= 0 ? len : (int) Math.floor((double) Math.min(cur, max) / max * len);
        StringBuilder sb = new StringBuilder(cur >= max ? "§a" : "§6");
        for (int i = 0; i < filled; i++) sb.append('▌');
        sb.append("§8");
        for (int i = filled; i < len; i++) sb.append('▌');
        return sb.toString();
    }

    // ---------- предметы ----------
    private static List<Component> loreComps(List<String> lore) {
        List<Component> l = new ArrayList<>();
        for (String s : lore) {
            if (!s.startsWith("§")) s = "§7" + s;
            l.add(legacy(s));
        }
        return l;
    }

    public static ItemStack itemL(Material m, Component name, boolean glow, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        meta.lore(loreComps(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        if (glow) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack item(Material m, String name, boolean glow, List<String> lore) {
        return itemL(m, neon(name), glow, lore);
    }

    public static ItemStack item(Material m, String name, boolean glow, String... lore) {
        return itemL(m, neon(name), glow, Arrays.asList(lore));
    }

    public static ItemStack head(OfflinePlayer op, Component name, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(op);
        meta.displayName(name);
        meta.lore(loreComps(lore));
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack pane(Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(" "));
        meta.setHideTooltip(true);
        it.setItemMeta(meta);
        return it;
    }

    /** Рамка: чёрное стекло, у углов жёлтое и оранжевое. */
    public static void fill(Inventory inv) {
        int size = inv.getSize();
        int rows = size / 9;
        ItemStack black = pane(Material.BLACK_STAINED_GLASS_PANE);
        ItemStack orange = pane(Material.ORANGE_STAINED_GLASS_PANE);
        ItemStack yellow = pane(Material.YELLOW_STAINED_GLASS_PANE);
        for (int s = 0; s < size; s++) {
            int row = s / 9, col = s % 9;
            boolean edgeRow = row == 0 || row == rows - 1;
            boolean edgeCol = col == 0 || col == 8;
            if (edgeRow && edgeCol) inv.setItem(s, orange);
            else if ((edgeRow && (col == 1 || col == 7)) || (edgeCol && (row == 1 || row == rows - 2)))
                inv.setItem(s, yellow);
            else inv.setItem(s, black);
        }
    }
}
