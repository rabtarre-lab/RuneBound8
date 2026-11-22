package me.settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Доступ к чужому: пока новичок не выполнит задания, он не может
 * ломать и открывать блоки, поставленные другими игроками.
 */
public class Access {
    private final SettingsPlugin plugin;
    private final QuestManager qm;
    private final Map<UUID, Long> lastMsg = new HashMap<>();

    public Access(SettingsPlugin plugin, QuestManager qm) {
        this.plugin = plugin;
        this.qm = qm;
    }

    // ---------- настройки ----------
    public boolean enabled() { return plugin.getConfig().getBoolean("access.enabled", true); }
    public int stone() { return Math.max(0, plugin.getConfig().getInt("access.stone", 32)); }
    public int wood() { return Math.max(0, plugin.getConfig().getInt("access.wood", 16)); }
    public int iron() { return Math.max(0, plugin.getConfig().getInt("access.iron", 8)); }
    public boolean nether() { return plugin.getConfig().getBoolean("access.nether", true); }
    public int breakMin() { return Math.max(0, plugin.getConfig().getInt("access.break-minutes", 15)); }
    public int openMin() { return Math.max(0, plugin.getConfig().getInt("access.open-minutes", 30)); }

    /** Игроков с правом settings.bypass и в креативе не проверяем. */
    public boolean exempt(Player p) {
        return !enabled() || p.hasPermission("settings.bypass") || p.getGameMode() == GameMode.CREATIVE;
    }

    public boolean tasksDone(QData d) {
        return d.accStone >= stone() && d.accWood >= wood() && d.accIron >= iron() && (!nether() || d.accNether);
    }

    public boolean allowedBreak(QData d) {
        return !enabled() || d.unlockedBreak || (tasksDone(d) && d.playMinutes >= breakMin());
    }

    public boolean allowedOpen(QData d) {
        return !enabled() || d.unlockedOpen || (tasksDone(d) && d.playMinutes >= openMin());
    }

    // ---------- что считаем ----------
    private boolean isStone(String m) {
        return switch (m) {
            case "STONE", "COBBLESTONE", "DEEPSLATE", "ANDESITE", "DIORITE", "GRANITE" -> true;
            default -> false;
        };
    }

    private boolean isWood(String m) { return m.endsWith("_LOG"); }

    private boolean isIron(String m) { return m.equals("IRON_ORE") || m.equals("DEEPSLATE_IRON_ORE"); }

    private void done(Player p, String text) {
        Gui.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.3f);
        Gui.msg(p, "§a✔ §f" + text);
    }

    /** Вызывается, когда игрок сломал природный блок. */
    public void onMined(Player p, String mat) {
        if (!enabled() || p.getGameMode() != GameMode.SURVIVAL) return;
        QData d = qm.data(p.getUniqueId());
        boolean changed = false;
        if (isStone(mat) && d.accStone < stone()) {
            d.accStone++;
            changed = true;
            if (d.accStone >= stone()) done(p, "Камень добыт §8(" + stone() + "/" + stone() + ")");
        } else if (isWood(mat) && d.accWood < wood()) {
            d.accWood++;
            changed = true;
            if (d.accWood >= wood()) done(p, "Дерево добыто §8(" + wood() + "/" + wood() + ")");
        } else if (isIron(mat) && d.accIron < iron()) {
            d.accIron++;
            changed = true;
            if (d.accIron >= iron()) done(p, "Железо добыто §8(" + iron() + "/" + iron() + ")");
        }
        if (changed) {
            qm.markDirty();
            check(p, d);
        }
    }

    public void onNether(Player p) {
        if (!enabled()) return;
        QData d = qm.data(p.getUniqueId());
        if (d.accNether) return;
        d.accNether = true;
        qm.markDirty();
        done(p, "Ты посетил Незер (ад)");
        check(p, d);
    }

    /** Проверяет, не открылся ли доступ. */
    public void check(Player p, QData d) {
        if (!enabled()) return;
        if (!d.unlockedBreak && tasksDone(d) && d.playMinutes >= breakMin()) {
            d.unlockedBreak = true;
            qm.markDirty();
            announce(p, "ломать чужие постройки");
        }
        if (!d.unlockedOpen && tasksDone(d) && d.playMinutes >= openMin()) {
            d.unlockedOpen = true;
            qm.markDirty();
            announce(p, "открывать чужие сундуки и двери");
        }
    }

    private void announce(Player p, String what) {
        Gui.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        p.showTitle(Title.title(Gui.neon("Доступ открыт!"), Gui.legacy("§fТеперь можно " + what),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(600))));
        p.sendMessage(Component.empty());
        p.sendMessage(Gui.neon("━━━━━━━━ ✦ Доступ открыт ✦ ━━━━━━━━"));
        p.sendMessage(Gui.legacy("§7Теперь ты можешь §f" + what + "§7."));
        p.sendMessage(Gui.legacy("§8Помни: без разрешения владельца это запрещено правилами (§f/rules§8)."));
        p.sendMessage(Gui.neon("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
    }

    // ---------- сообщения ----------
    /** Игрок упёрся в запрет. Сообщение показываем не чаще, чем раз в 4 секунды. */
    public void denied(Player p, boolean opening) {
        long now = System.currentTimeMillis();
        Long last = lastMsg.get(p.getUniqueId());
        if (last != null && now - last < 4000) return;
        lastMsg.put(p.getUniqueId(), now);
        Gui.sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
        sendStatus(p, opening ? 2 : 1);
    }

    private String minutes(int n) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return n + " минуту";
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return n + " минуты";
        return n + " минут";
    }

    private Component line(boolean ok, String text, String progress) {
        return Gui.legacy((ok ? " §a✔ §f" : " §c✘ §7") + text + " §8» " + (ok ? "§a" : "§e") + progress);
    }

    /** mode: 0 — всё сразу, 1 — про «ломать», 2 — про «открывать». */
    public void sendStatus(Player p, int mode) {
        QData d = qm.data(p.getUniqueId());
        String what = mode == 2 ? "открывать чужое" : mode == 1 ? "ломать чужое" : "ломать и открывать чужое";

        p.sendMessage(Component.empty());
        p.sendMessage(Gui.neon("━━━━━━━━ ✦ Доступ к чужому ✦ ━━━━━━━━"));
        p.sendMessage(Gui.legacy("§7Чтобы §f" + what + "§7, выполни задания новичка:"));
        p.sendMessage(line(d.accStone >= stone(), "Добудь " + stone() + " камня", Math.min(d.accStone, stone()) + "/" + stone()));
        p.sendMessage(line(d.accWood >= wood(), "Добудь " + wood() + " дерева", Math.min(d.accWood, wood()) + "/" + wood()));
        p.sendMessage(line(d.accIron >= iron(), "Добудь " + iron() + " железа", Math.min(d.accIron, iron()) + "/" + iron()));
        if (nether()) p.sendMessage(line(d.accNether, "Посети Незер (ад)", d.accNether ? "был" : "не был"));

        if (mode == 0 || mode == 1) {
            p.sendMessage(line(d.playMinutes >= breakMin(),
                    "Отыграй " + minutes(breakMin()) + (mode == 0 ? " §8(ломать)" : ""),
                    Math.min(d.playMinutes, breakMin()) + "/" + breakMin()));
        }
        if (mode == 0 || mode == 2) {
            p.sendMessage(line(d.playMinutes >= openMin(),
                    "Отыграй " + minutes(openMin()) + (mode == 0 ? " §8(открывать)" : ""),
                    Math.min(d.playMinutes, openMin()) + "/" + openMin()));
        }
        if (mode == 1) p.sendMessage(Gui.legacy("§8Чтобы открывать чужие сундуки и двери — отыграй ещё до §f" + minutes(openMin()) + "§8."));
        if (mode == 2) p.sendMessage(Gui.legacy("§8Ломать чужое можно после §f" + minutes(breakMin()) + " §8игры."));
        p.sendMessage(Gui.legacy("§8Проверить прогресс: §f/access"));
        p.sendMessage(Gui.neon("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
    }
}
