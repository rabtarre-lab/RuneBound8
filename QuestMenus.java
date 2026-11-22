package me.settings;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Все меню системы заданий, магазина и топа + команды /quests /shop /runes. */
public class QuestMenus implements Listener, CommandExecutor {

    private static final int[] GRID = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};
    private static final int[] TOP_SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};

    private final SettingsPlugin plugin;
    private final QuestManager qm;

    public QuestMenus(SettingsPlugin plugin, QuestManager qm) {
        this.plugin = plugin;
        this.qm = qm;
    }

    // ===================================================================
    //  Команды
    // ===================================================================
    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        String name = c.getName().toLowerCase();
        if (name.equals("runes")) return runesCommand(s, a);
        if (!(s instanceof Player p)) { s.sendMessage("Только для игроков."); return true; }

        if (name.equals("shop")) { open(p, MenuHolder.T.SHOP, "", 0); return true; }
        // /quests
        if (a.length > 0) {
            switch (a[0].toLowerCase()) {
                case "daily" -> { open(p, MenuHolder.T.QUESTS, "DAILY", 0); return true; }
                case "weekly" -> { open(p, MenuHolder.T.QUESTS, "WEEKLY", 0); return true; }
                case "chains" -> { open(p, MenuHolder.T.CHAINS, "", 0); return true; }
                case "ach" -> { open(p, MenuHolder.T.QUESTS, "ACH", 0); return true; }
                case "top" -> { open(p, MenuHolder.T.TOP, "", 0); return true; }
                case "shop" -> { open(p, MenuHolder.T.SHOP, "", 0); return true; }
                default -> { }
            }
        }
        Gui.sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f);
        open(p, MenuHolder.T.HUB, "", 0);
        return true;
    }

    private boolean runesCommand(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (s instanceof Player p) Gui.msg(p, "§7Твои руны: §e" + qm.data(p.getUniqueId()).runes + " ✦");
            else s.sendMessage("Использование: /runes <give|take|set|reset> <игрок> [число]");
            return true;
        }
        if (a[0].equalsIgnoreCase("top") && s instanceof Player p) { open(p, MenuHolder.T.TOP, "", 0); return true; }
        if (!s.hasPermission("settings.admin")) { s.sendMessage("§cНет прав."); return true; }
        if (a.length < 2) { s.sendMessage("§e/runes <give|take|set|reset> <игрок> [число]"); return true; }

        OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
        QData d = qm.data(op.getUniqueId());
        String sub = a[0].toLowerCase();
        if (sub.equals("reset")) {
            qm.resetPlayer(op.getUniqueId());
            s.sendMessage("§aДанные заданий игрока " + a[1] + " сброшены.");
            return true;
        }
        long n;
        try {
            n = Long.parseLong(a.length > 2 ? a[2] : "");
        } catch (NumberFormatException e) {
            s.sendMessage("§cУкажи число.");
            return true;
        }
        switch (sub) {
            case "give" -> qm.giveRunesTo(op.getUniqueId(), n);
            case "take" -> { d.runes = Math.max(0, d.runes - n); qm.markDirty(); }
            case "set" -> { d.runes = Math.max(0, n); qm.markDirty(); }
            default -> { s.sendMessage("§cНеизвестная подкоманда."); return true; }
        }
        s.sendMessage("§aГотово. Баланс " + a[1] + ": §e" + d.runes);
        return true;
    }

    // ===================================================================
    //  Открытие меню
    // ===================================================================
    public void open(Player p, MenuHolder.T t, String arg, int page) {
        QData d = qm.data(p.getUniqueId());
        qm.ensure(p.getUniqueId(), d);
        switch (t) {
            case HUB -> hub(p, d);
            case QUESTS -> quests(p, d, arg, page);
            case CHAINS -> chains(p, d);
            case CHAIN -> chain(p, d, arg);
            case SHOP -> shopMain(p, d);
            case SHOPCAT -> shopCat(p, d, arg, page);
            case TOP -> top(p, d);
            default -> { }
        }
    }

    private Inventory inv(MenuHolder.T t, String arg, int page, int size, String title) {
        MenuHolder h = new MenuHolder(t, arg, page);
        Inventory inv = Bukkit.createInventory(h, size, Gui.neon(title));
        h.inv = inv;
        Gui.fill(inv);
        return inv;
    }

    // ---------------------------- ХАБ ----------------------------
    private void hub(Player p, QData d) {
        Inventory inv = inv(MenuHolder.T.HUB, "", 0, 45, "Задания");

        int rank = qm.rankIndex(d.completedTotal);
        List<String> pl = new ArrayList<>();
        pl.add("§7Руны: §e" + d.runes + " ✦");
        pl.add("§7Ранг: §f" + QuestManager.RANK_NAMES[rank] + " §8(+" + QuestManager.RANK_PCT[rank] + "% к наградам)");
        pl.add("§7Выполнено заданий: §f" + d.completedTotal);
        pl.add("§7Серия дней: §6" + d.streak + " §8(рекорд " + d.bestStreak + ")");
        if (rank < QuestManager.RANK_NAMES.length - 1) {
            int need = QuestManager.RANK_MIN[rank + 1] - d.completedTotal;
            int span = QuestManager.RANK_MIN[rank + 1] - QuestManager.RANK_MIN[rank];
            pl.add("");
            pl.add("§7До ранга §f" + QuestManager.RANK_NAMES[rank + 1] + "§7: §f" + need + " зад.");
            pl.add(Gui.bar(span - need, span, 20));
        } else {
            pl.add("");
            pl.add("§6★ Максимальный ранг!");
        }
        inv.setItem(4, Gui.head(p, Gui.neon(p.getName()), pl));

        List<Quest> dl = qm.list(d, "DAILY");
        int dReady = qm.count(d, dl, QuestManager.Status.READY);
        int dDone = dReady + qm.count(d, dl, QuestManager.Status.CLAIMED);
        List<String> dlore = new ArrayList<>(List.of(
                "§7Новые задания каждый день.", "",
                "§7Выполнено: §a" + dDone + "§7/§f" + dl.size(),
                "§7Обновление через: §f" + Gui.dur(qm.dayLeft()), ""));
        if (dReady > 0) dlore.add("§a● Есть награды для получения!");
        dlore.add("§8» §eНажми, чтобы открыть");
        inv.setItem(19, Gui.item(Material.CLOCK, "Ежедневные задания", dReady > 0, dlore));

        List<Quest> wl = qm.list(d, "WEEKLY");
        int wReady = qm.count(d, wl, QuestManager.Status.READY);
        int wDone = wReady + qm.count(d, wl, QuestManager.Status.CLAIMED);
        List<String> wlore = new ArrayList<>(List.of(
                "§7Сложнее и щедрее ежедневных.", "",
                "§7Выполнено: §a" + wDone + "§7/§f" + wl.size(),
                "§7Обновление через: §f" + Gui.dur(qm.weekLeft()), ""));
        if (wReady > 0) wlore.add("§a● Есть награды для получения!");
        wlore.add("§8» §eНажми, чтобы открыть");
        inv.setItem(21, Gui.item(Material.COMPASS, "Еженедельные задания", wReady > 0, wlore));

        int cTotal = 0, cClaimed = 0, cReady = 0;
        for (QuestManager.ChainInfo ci : qm.chains.values()) {
            cTotal += ci.steps.size();
            cClaimed += qm.count(d, ci.steps, QuestManager.Status.CLAIMED);
            cReady += qm.count(d, ci.steps, QuestManager.Status.READY);
        }
        List<String> clore = new ArrayList<>(List.of(
                "§7Истории из шагов: каждый шаг", "§7открывается после предыдущего.", "",
                "§7Пройдено шагов: §a" + cClaimed + "§7/§f" + cTotal, ""));
        if (cReady > 0) clore.add("§a● Есть награды для получения!");
        clore.add("§8» §eНажми, чтобы открыть");
        inv.setItem(23, Gui.item(Material.WRITTEN_BOOK, "Цепочки заданий", cReady > 0, clore));

        int aReady = qm.count(d, qm.achievements, QuestManager.Status.READY);
        int aClaimed = qm.count(d, qm.achievements, QuestManager.Status.CLAIMED);
        List<String> alore = new ArrayList<>(List.of(
                "§7Большие цели на всю жизнь.", "",
                "§7Получено: §a" + aClaimed + "§7/§f" + qm.achievements.size(), ""));
        if (aReady > 0) alore.add("§a● Есть награды для получения!");
        alore.add("§8» §eНажми, чтобы открыть");
        inv.setItem(25, Gui.item(Material.NETHER_STAR, "Достижения", aReady > 0, alore));

        inv.setItem(29, Gui.item(Material.GOLDEN_HELMET, "Топ игроков", false,
                "§7Лучшие исполнители сервера.", "", "§7Твоё место: §f" + (qm.placeOf(p.getUniqueId()) < 0 ? "—" : "#" + qm.placeOf(p.getUniqueId())),
                "", "§8» §eНажми, чтобы открыть"));
        inv.setItem(31, Gui.item(Material.EMERALD, "Магазин рун", false,
                "§7Трать руны на ресурсы, бусты", "§7и красивые эффекты.", "", "§7Баланс: §e" + d.runes + " ✦",
                "", "§8» §eНажми, чтобы открыть"));
        inv.setItem(33, Gui.item(Material.COMPARATOR, "Настройки", false,
                "§7Ник, рост, PvP, пинг и другое.", "", "§8» §eНажми, чтобы открыть"));
        inv.setItem(40, Gui.item(Material.BARRIER, "Закрыть", false, "§7Закрыть меню."));
        p.openInventory(inv);
    }

    // ---------------------------- СПИСОК ЗАДАНИЙ ----------------------------
    private Map<Integer, Quest> layout(String arg, List<Quest> list, int page) {
        Map<Integer, Quest> m = new LinkedHashMap<>();
        if (arg.equals("ACH")) {
            int from = page * GRID.length;
            for (int i = 0; i < GRID.length && from + i < list.size(); i++) m.put(GRID[i], list.get(from + i));
        } else {
            int n = list.size();
            if (n == 0) return m;
            int step = n <= 4 ? 2 : 1;
            int width = step * (n - 1) + 1;
            int start = Math.max(0, (9 - width) / 2);
            for (int i = 0; i < n && start + step * i < 9; i++) m.put(18 + start + step * i, list.get(i));
        }
        return m;
    }

    private void quests(Player p, QData d, String arg, int page) {
        String title = switch (arg) {
            case "DAILY" -> "Ежедневные задания";
            case "WEEKLY" -> "Еженедельные задания";
            default -> "Достижения";
        };
        List<Quest> list = qm.list(d, arg);
        int pages = Math.max(1, (list.size() + GRID.length - 1) / GRID.length);
        page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = inv(MenuHolder.T.QUESTS, arg, page, 54, title);

        for (Map.Entry<Integer, Quest> en : layout(arg, list, page).entrySet())
            inv.setItem(en.getKey(), questItem(d, en.getValue()));

        if (arg.equals("DAILY")) {
            inv.setItem(38, Gui.item(Material.BLAZE_POWDER, "Серия дней", d.streak > 0,
                    "§7Выполняй хотя бы одно ежедневное", "§7задание каждый день.", "",
                    "§7Сейчас: §6" + d.streak + " дн.", "§7Рекорд: §f" + d.bestStreak + " дн.", "",
                    "§7Бонусы за серию: §f3, 7, 14, 30 дн."));
            inv.setItem(40, bonusItem(d, false, list));
            inv.setItem(42, Gui.item(Material.CLOCK, "Обновление", false,
                    "§7Новые задания через:", "§f" + Gui.dur(qm.dayLeft())));
        } else if (arg.equals("WEEKLY")) {
            inv.setItem(40, bonusItem(d, true, list));
            inv.setItem(42, Gui.item(Material.CLOCK, "Обновление", false,
                    "§7Новые задания через:", "§f" + Gui.dur(qm.weekLeft())));
        } else {
            inv.setItem(4, Gui.item(Material.NETHER_STAR, "Достижения", false,
                    "§7Страница §f" + (page + 1) + "§7/§f" + pages));
            if (page > 0) inv.setItem(48, Gui.item(Material.ARROW, "Назад по списку", false, "§7Страница " + page));
            if (page < pages - 1) inv.setItem(50, Gui.item(Material.ARROW, "Дальше", false, "§7Страница " + (page + 2)));
        }
        inv.setItem(49, Gui.item(Material.ARROW, "В меню", false, "§7Вернуться к заданиям."));
        p.openInventory(inv);
    }

    private ItemStack bonusItem(QData d, boolean weekly, List<Quest> list) {
        boolean got = weekly ? d.weeklyBonus : d.dailyBonus;
        int claimed = qm.count(d, list, QuestManager.Status.CLAIMED);
        int amt = plugin.getConfig().getInt(weekly ? "quests.weekly-bonus" : "quests.daily-bonus", weekly ? 300 : 60);
        List<String> l = new ArrayList<>();
        l.add("§7Забери награды за все задания,");
        l.add("§7чтобы открыть бонус.");
        l.add("");
        l.add("§7Бонус: §e+" + amt + " рун");
        l.add("§7Забрано: §f" + claimed + "§7/§f" + list.size());
        l.add("");
        boolean ready = qm.bonusReady(d, weekly);
        if (got) l.add("§a✔ Бонус получен");
        else if (ready) l.add("§8» §eНажми, чтобы забрать!");
        else l.add("§7Статус: §6ещё не готов");
        return Gui.item(got ? Material.CHEST : (ready ? Material.ENDER_CHEST : Material.CHEST), "Бонус за все задания", ready, l);
    }

    private ItemStack questItem(QData d, Quest q) {
        QuestManager.Status s = qm.status(d, q);
        if (s == QuestManager.Status.LOCKED) {
            return Gui.itemL(Material.BARRIER, Gui.legacy("§8Заблокировано"), false,
                    List.of("§7Заверши предыдущий шаг цепочки,", "§7чтобы открыть это задание."));
        }
        int cur = d.progress.getOrDefault(q.id, 0);
        int pct = (int) Math.floor(Math.min(cur, q.amount) * 100.0 / q.amount);
        List<String> l = new ArrayList<>();
        if (!q.desc.isEmpty()) l.add("§7" + q.desc);
        if (q.cat == Quest.Cat.DAILY || q.cat == Quest.Cat.WEEKLY) {
            int t = Math.max(1, Math.min(3, q.tier));
            l.add("§7Сложность: §e" + "★".repeat(t) + "§8" + "☆".repeat(3 - t));
        }
        l.add("");
        l.add("§7Прогресс: §f" + Math.min(cur, q.amount) + "§7/§f" + q.amount);
        l.add(Gui.bar(cur, q.amount, 20) + " §7" + pct + "%");
        l.add("");
        l.add("§7Награда:");
        int runes = qm.rewardFor(d, q);
        int pctRank = QuestManager.RANK_PCT[qm.rankIndex(d.completedTotal)];
        l.add(" §e+" + runes + " рун" + (pctRank > 0 ? " §8(ранг +" + pctRank + "%)" : ""));
        if (q.rewardItem != null) l.add(" §f+" + q.rewardItemAmount + "× " + Gui.pretty(q.rewardItem.name()));
        if (q.xp > 0) l.add(" §a+" + q.xp + " ур. опыта");
        l.add("");
        switch (s) {
            case READY -> { l.add("§a✔ Выполнено!"); l.add("§8» §eНажми, чтобы забрать награду"); }
            case CLAIMED -> l.add("§a✔ Награда получена");
            default -> l.add("§7Статус: §6в процессе");
        }
        if (s == QuestManager.Status.CLAIMED)
            return Gui.itemL(q.icon, Gui.legacy("§a✔ §7§m" + q.name), false, l);
        return Gui.itemL(q.icon, Gui.neon(q.name), s == QuestManager.Status.READY, l);
    }

    // ---------------------------- ЦЕПОЧКИ ----------------------------
    private void chains(Player p, QData d) {
        Inventory inv = inv(MenuHolder.T.CHAINS, "", 0, 45, "Цепочки заданий");
        List<QuestManager.ChainInfo> list = new ArrayList<>(qm.chains.values());
        int n = Math.min(list.size(), 7);
        int start = (9 - n) / 2;
        for (int i = 0; i < n; i++) {
            QuestManager.ChainInfo ci = list.get(i);
            int claimed = qm.count(d, ci.steps, QuestManager.Status.CLAIMED);
            int ready = qm.count(d, ci.steps, QuestManager.Status.READY);
            List<String> l = new ArrayList<>();
            if (!ci.desc.isEmpty()) l.add("§7" + ci.desc);
            l.add("");
            l.add("§7Шагов пройдено: §a" + claimed + "§7/§f" + ci.steps.size());
            l.add(Gui.bar(claimed, ci.steps.size(), 20));
            l.add("");
            for (Quest q : ci.steps) {
                QuestManager.Status s = qm.status(d, q);
                String mark = switch (s) {
                    case CLAIMED -> "§a✔ §7";
                    case READY -> "§e★ §f";
                    case PROGRESS -> "§6▶ §f";
                    default -> "§8✖ §8";
                };
                l.add(mark + q.name);
            }
            l.add("");
            if (ready > 0) l.add("§a● Есть награды для получения!");
            l.add("§8» §eНажми, чтобы открыть");
            inv.setItem(18 + start + i, Gui.item(ci.icon, ci.name, ready > 0 || claimed == ci.steps.size(), l));
        }
        inv.setItem(40, Gui.item(Material.ARROW, "В меню", false, "§7Вернуться к заданиям."));
        p.openInventory(inv);
    }

    private Map<Integer, Quest> chainLayout(QuestManager.ChainInfo ci) {
        Map<Integer, Quest> m = new LinkedHashMap<>();
        int n = Math.min(ci.steps.size(), 7);
        int start = (9 - n) / 2;
        for (int i = 0; i < n; i++) m.put(9 + start + i, ci.steps.get(i));
        return m;
    }

    private void chain(Player p, QData d, String id) {
        QuestManager.ChainInfo ci = qm.chains.get(id);
        if (ci == null) { open(p, MenuHolder.T.CHAINS, "", 0); return; }
        Inventory inv = inv(MenuHolder.T.CHAIN, id, 0, 27, ci.name);
        for (Map.Entry<Integer, Quest> en : chainLayout(ci).entrySet())
            inv.setItem(en.getKey(), questItem(d, en.getValue()));
        inv.setItem(22, Gui.item(Material.ARROW, "Назад", false, "§7К списку цепочек."));
        p.openInventory(inv);
    }

    // ---------------------------- МАГАЗИН ----------------------------
    private void shopMain(Player p, QData d) {
        Inventory inv = inv(MenuHolder.T.SHOP, "", 0, 27, "Магазин рун");
        List<Shop.Cat> cats = new ArrayList<>(qm.shop.values());
        int n = Math.min(cats.size(), 7);
        int start = (9 - n) / 2;
        for (int i = 0; i < n; i++) {
            Shop.Cat c = cats.get(i);
            List<String> l = new ArrayList<>(c.desc);
            l.add("");
            l.add("§7Товаров: §f" + c.items.size());
            l.add("§8» §eНажми, чтобы открыть");
            inv.setItem(9 + start + i, Gui.item(c.icon, c.name, false, l));
        }
        inv.setItem(4, Gui.item(Material.EMERALD, "Твои руны", false, "§e" + d.runes + " ✦",
                "", "§7Руны дают за задания."));
        inv.setItem(22, Gui.item(Material.ARROW, "В меню", false, "§7Вернуться к заданиям."));
        p.openInventory(inv);
    }

    private void shopCat(Player p, QData d, String catId, int page) {
        Shop.Cat c = qm.shop.get(catId);
        if (c == null) { open(p, MenuHolder.T.SHOP, "", 0); return; }
        int pages = Math.max(1, (c.items.size() + GRID.length - 1) / GRID.length);
        page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = inv(MenuHolder.T.SHOPCAT, catId, page, 54, c.name);
        int from = page * GRID.length;
        for (int i = 0; i < GRID.length && from + i < c.items.size(); i++)
            inv.setItem(GRID[i], shopItem(d, c.items.get(from + i)));
        inv.setItem(4, Gui.item(Material.EMERALD, "Твои руны", false, "§e" + d.runes + " ✦"));
        if (page > 0) inv.setItem(48, Gui.item(Material.ARROW, "Назад по списку", false, "§7Страница " + page));
        if (page < pages - 1) inv.setItem(50, Gui.item(Material.ARROW, "Дальше", false, "§7Страница " + (page + 2)));
        inv.setItem(49, Gui.item(Material.ARROW, "К категориям", false, "§7Вернуться в магазин."));
        p.openInventory(inv);
    }

    private ItemStack shopItem(QData d, Shop.Item it) {
        List<String> l = new ArrayList<>(it.desc);
        if (!it.title.isEmpty()) l.add("§7Превью в чате: " + it.title + " §f" + "Игрок");
        l.add("");
        boolean owned = it.once && d.owned.contains(it.id);
        boolean glow = false;
        String slot = it.slot();
        if (owned && !slot.isEmpty()) {
            boolean on = d.equipped(slot).equalsIgnoreCase(it.value());
            glow = on;
            l.add(on ? "§a● Надето" : "§a✔ Куплено");
            l.add(on ? "§8» §eНажми, чтобы снять" : "§8» §eНажми, чтобы надеть");
        } else if (owned) {
            l.add("§a✔ Уже куплено");
        } else {
            boolean can = d.runes >= it.price;
            l.add("§7Цена: " + (can ? "§a" : "§c") + it.price + " рун");
            l.add(can ? "§8» §eНажми, чтобы купить" : "§cНе хватает " + (it.price - d.runes) + " рун");
        }
        return Gui.item(it.icon, it.name, glow, l);
    }

    // ---------------------------- ТОП ----------------------------
    private void top(Player p, QData d) {
        Inventory inv = inv(MenuHolder.T.TOP, "", 0, 36, "Топ игроков");
        List<Map.Entry<UUID, QData>> list = qm.top(TOP_SLOTS.length);
        for (int i = 0; i < list.size(); i++) {
            UUID id = list.get(i).getKey();
            QData pd = list.get(i).getValue();
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            String nm = pd.name.isEmpty() ? (op.getName() == null ? id.toString().substring(0, 8) : op.getName()) : pd.name;
            int rank = qm.rankIndex(pd.completedTotal);
            inv.setItem(TOP_SLOTS[i], Gui.head(op, Gui.neon("#" + (i + 1) + " " + nm), List.of(
                    "§7Выполнено заданий: §f" + pd.completedTotal,
                    "§7Ранг: §f" + QuestManager.RANK_NAMES[rank],
                    "§7Руны заработано: §e" + pd.totalEarned)));
        }
        int place = qm.placeOf(p.getUniqueId());
        inv.setItem(4, Gui.item(Material.GOLDEN_HELMET, "Твоё место", false,
                "§7Место: §f" + (place < 0 ? "—" : "#" + place), "§7Заданий: §f" + d.completedTotal));
        inv.setItem(31, Gui.item(Material.ARROW, "В меню", false, "§7Вернуться к заданиям."));
        p.openInventory(inv);
    }

    // ===================================================================
    //  Клики
    // ===================================================================
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder h)) return;
        if (h.type == MenuHolder.T.SETTINGS || h.type == MenuHolder.T.HEIGHT) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getSlot();
        QData d = qm.data(p.getUniqueId());
        qm.ensure(p.getUniqueId(), d);

        switch (h.type) {
            case HUB -> {
                switch (slot) {
                    case 19 -> go(p, MenuHolder.T.QUESTS, "DAILY", 0);
                    case 21 -> go(p, MenuHolder.T.QUESTS, "WEEKLY", 0);
                    case 23 -> go(p, MenuHolder.T.CHAINS, "", 0);
                    case 25 -> go(p, MenuHolder.T.QUESTS, "ACH", 0);
                    case 29 -> go(p, MenuHolder.T.TOP, "", 0);
                    case 31 -> go(p, MenuHolder.T.SHOP, "", 0);
                    case 33 -> { Gui.sound(p, Sound.UI_BUTTON_CLICK, 1.2f); plugin.openMain(p); }
                    case 40 -> { Gui.sound(p, Sound.UI_BUTTON_CLICK, 0.8f); p.closeInventory(); }
                    default -> { }
                }
            }
            case QUESTS -> clickQuests(p, h, d, slot);
            case CHAINS -> {
                if (slot == 40) { go(p, MenuHolder.T.HUB, "", 0); return; }
                List<QuestManager.ChainInfo> list = new ArrayList<>(qm.chains.values());
                int n = Math.min(list.size(), 7);
                int start = (9 - n) / 2;
                int idx = slot - 18 - start;
                if (idx >= 0 && idx < n) go(p, MenuHolder.T.CHAIN, list.get(idx).id, 0);
            }
            case CHAIN -> {
                if (slot == 22) { go(p, MenuHolder.T.CHAINS, "", 0); return; }
                QuestManager.ChainInfo ci = qm.chains.get(h.arg);
                if (ci == null) return;
                Quest q = chainLayout(ci).get(slot);
                if (q != null) clickQuest(p, q, () -> open(p, MenuHolder.T.CHAIN, h.arg, 0));
            }
            case SHOP -> {
                if (slot == 22) { go(p, MenuHolder.T.HUB, "", 0); return; }
                List<Shop.Cat> cats = new ArrayList<>(qm.shop.values());
                int n = Math.min(cats.size(), 7);
                int start = (9 - n) / 2;
                int idx = slot - 9 - start;
                if (idx >= 0 && idx < n) go(p, MenuHolder.T.SHOPCAT, cats.get(idx).id, 0);
            }
            case SHOPCAT -> {
                if (slot == 49) { go(p, MenuHolder.T.SHOP, "", 0); return; }
                if (slot == 48) { go(p, MenuHolder.T.SHOPCAT, h.arg, h.page - 1); return; }
                if (slot == 50) { go(p, MenuHolder.T.SHOPCAT, h.arg, h.page + 1); return; }
                Shop.Cat c = qm.shop.get(h.arg);
                if (c == null) return;
                for (int i = 0; i < GRID.length; i++) {
                    if (GRID[i] != slot) continue;
                    int idx = h.page * GRID.length + i;
                    if (idx < c.items.size()) {
                        qm.buy(p, c.items.get(idx));
                        open(p, MenuHolder.T.SHOPCAT, h.arg, h.page);
                    }
                    return;
                }
            }
            case TOP -> { if (slot == 31) go(p, MenuHolder.T.HUB, "", 0); }
            default -> { }
        }
    }

    private void go(Player p, MenuHolder.T t, String arg, int page) {
        Gui.sound(p, Sound.UI_BUTTON_CLICK, 1.2f);
        open(p, t, arg, Math.max(0, page));
    }

    private void clickQuests(Player p, MenuHolder h, QData d, int slot) {
        if (slot == 49) { go(p, MenuHolder.T.HUB, "", 0); return; }
        if (h.arg.equals("ACH")) {
            if (slot == 48 && h.page > 0) { go(p, MenuHolder.T.QUESTS, "ACH", h.page - 1); return; }
            if (slot == 50) { go(p, MenuHolder.T.QUESTS, "ACH", h.page + 1); return; }
        } else if (slot == 40) {
            boolean weekly = h.arg.equals("WEEKLY");
            if (qm.claimBonus(p, weekly)) open(p, MenuHolder.T.QUESTS, h.arg, h.page);
            else Gui.sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
            return;
        }
        Quest q = layout(h.arg, qm.list(d, h.arg), h.page).get(slot);
        if (q != null) clickQuest(p, q, () -> open(p, MenuHolder.T.QUESTS, h.arg, h.page));
    }

    private void clickQuest(Player p, Quest q, Runnable refresh) {
        QData d = qm.data(p.getUniqueId());
        QuestManager.Status s = qm.status(d, q);
        if (s == QuestManager.Status.READY && qm.claim(p, q)) {
            refresh.run();
        } else if (s != QuestManager.Status.CLAIMED) {
            Gui.sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f);
        }
    }
}
