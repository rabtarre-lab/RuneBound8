package me.settings;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Shop {
    private Shop() {}

    public static Material mat(String name, Material def) {
        Material m = Material.matchMaterial(name);
        return m == null ? def : m;
    }

    public static List<String> strList(Object o) {
        List<String> l = new ArrayList<>();
        if (o instanceof List<?> src) {
            for (Object x : src) l.add(String.valueOf(x));
        } else if (o != null) {
            l.add(String.valueOf(o));
        }
        return l;
    }

    public static final class Cat {
        public final String id, name;
        public final Material icon;
        public final List<String> desc;
        public final List<Item> items = new ArrayList<>();

        public Cat(Map<?, ?> m) {
            this.id = Quest.str(m, "id", "cat");
            this.name = Quest.str(m, "name", id);
            this.icon = mat(Quest.str(m, "icon", "CHEST"), Material.CHEST);
            this.desc = strList(m.get("desc"));
        }
    }

    public static final class Item {
        public final String id, name, effect, trail, nick, title, killfx;
        public final Material icon;
        public final int price;
        public final List<String> desc;
        public final List<String> commands;
        public Material giveMat;
        public int giveAmt = 1;
        public final boolean once;

        public Item(String catId, int idx, Map<?, ?> m) {
            this.name = Quest.str(m, "name", "?");
            this.id = Quest.str(m, "id", catId + "_" + idx);
            this.icon = mat(Quest.str(m, "icon", "CHEST"), Material.CHEST);
            this.price = Quest.num(m, "price", 100);
            this.desc = strList(m.get("desc"));
            this.commands = strList(m.get("commands"));
            this.effect = Quest.str(m, "effect", "");
            this.trail = Quest.str(m, "trail", "");
            this.nick = Quest.str(m, "nick", "");
            this.title = Quest.str(m, "title", "");
            this.killfx = Quest.str(m, "killfx", "");
            this.once = !trail.isEmpty() || !nick.isEmpty() || !title.isEmpty() || !killfx.isEmpty()
                    || Boolean.TRUE.equals(m.get("once"));
            String g = Quest.str(m, "give", "");
            if (!g.isEmpty()) {
                String[] a = g.split(":");
                giveMat = Material.matchMaterial(a[0]);
                if (a.length > 1) {
                    try { giveAmt = Integer.parseInt(a[1].trim()); } catch (NumberFormatException ignored) {}
                }
            }
        }

        /** Слот косметики: trail / nick / title / killfx (или пусто, если это не косметика). */
        public String slot() {
            if (!trail.isEmpty()) return "trail";
            if (!nick.isEmpty()) return "nick";
            if (!title.isEmpty()) return "title";
            if (!killfx.isEmpty()) return "killfx";
            return "";
        }

        public String value() {
            return switch (slot()) {
                case "trail" -> trail;
                case "nick" -> nick;
                case "title" -> title;
                case "killfx" -> killfx;
                default -> "";
            };
        }
    }
}
