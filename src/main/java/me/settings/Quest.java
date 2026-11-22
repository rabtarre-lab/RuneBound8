package me.settings;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Quest {
    public enum Type {
        BREAK, PLACE, HARVEST, KILL, FISH, CRAFT, SMELT, EAT, ENCHANT, BREED,
        TAME, SHEAR, TRADE, WALK, PLAYTIME, DIMENSION, LEVEL, ADVANCEMENT
    }

    public enum Cat { DAILY, WEEKLY, CHAIN, ACH }

    public final String id, name, desc;
    public final Type type;
    public final Cat cat;
    public final List<String> targets = new ArrayList<>();
    public final int amount, reward, tier, xp;
    public final Material icon;
    public Material rewardItem;
    public int rewardItemAmount = 1;

    // для цепочек
    public String chain;
    public String prev;
    public int step;

    public Quest(Map<?, ?> m, Cat cat) {
        this.cat = cat;
        this.id = str(m, "id", "q_" + Integer.toHexString(m.hashCode()));
        this.name = str(m, "name", id);
        this.desc = str(m, "desc", "");
        Type t;
        try {
            t = Type.valueOf(str(m, "type", "BREAK").toUpperCase());
        } catch (IllegalArgumentException e) {
            t = Type.BREAK;
        }
        this.type = t;
        Object o = m.get("targets");
        if (o instanceof List<?> l) {
            for (Object x : l) targets.add(String.valueOf(x).toUpperCase());
        } else if (o != null) {
            targets.add(String.valueOf(o).toUpperCase());
        }
        this.amount = Math.max(1, num(m, "amount", 1));
        this.reward = num(m, "reward", 10);
        this.tier = num(m, "tier", 1);
        this.xp = num(m, "xp", 0);
        Material ic = Material.matchMaterial(str(m, "icon", "BOOK"));
        this.icon = ic == null ? Material.BOOK : ic;
        String it = str(m, "item", "");
        if (!it.isEmpty()) {
            String[] a = it.split(":");
            Material rm = Material.matchMaterial(a[0]);
            if (rm != null) {
                rewardItem = rm;
                if (a.length > 1) {
                    try { rewardItemAmount = Integer.parseInt(a[1].trim()); } catch (NumberFormatException ignored) {}
                }
            }
        }
    }

    /** Подходит ли цель (материал/моб/мир) под список targets. Поддерживает *, *_ORE, COOKED_*, *LOG*. */
    public boolean matches(String target) {
        if (targets.isEmpty()) return true;
        String t = target.toUpperCase();
        for (String p : targets) {
            if (p.equals("*")) return true;
            boolean s = p.startsWith("*");
            boolean e = p.length() > 1 && p.endsWith("*");
            String core = p.substring(s ? 1 : 0, e ? p.length() - 1 : p.length());
            if (s && e) { if (t.contains(core)) return true; }
            else if (s) { if (t.endsWith(core)) return true; }
            else if (e) { if (t.startsWith(core)) return true; }
            else if (t.equals(p)) return true;
        }
        return false;
    }

    public static String str(Map<?, ?> m, String k, String def) {
        Object o = m.get(k);
        return o == null ? def : String.valueOf(o);
    }

    public static int num(Map<?, ?> m, String k, int def) {
        Object o = m.get(k);
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try { return Integer.parseInt(String.valueOf(o).trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }
}
