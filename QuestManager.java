package me.settings;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

public class QuestManager {

    public enum Status { LOCKED, PROGRESS, READY, CLAIMED }

    public static final class ChainInfo {
        public final String id, name, desc;
        public final org.bukkit.Material icon;
        public final List<Quest> steps = new ArrayList<>();

        ChainInfo(Map<?, ?> m) {
            this.id = Quest.str(m, "id", "chain");
            this.name = Quest.str(m, "name", id);
            this.desc = Quest.str(m, "desc", "");
            this.icon = Shop.mat(Quest.str(m, "icon", "BOOK"), org.bukkit.Material.BOOK);
        }
    }

    public static final String[] RANK_NAMES = {"Новичок", "Исполнитель", "Охотник", "Следопыт", "Мастер", "Герой", "Легенда", "Бог заданий"};
    public static final int[] RANK_MIN = {0, 10, 30, 60, 120, 250, 500, 1000};
    public static final int[] RANK_PCT = {0, 2, 4, 6, 8, 10, 15, 20};

    private final SettingsPlugin plugin;
    private final Map<UUID, QData> data = new HashMap<>();

    public final List<Quest> dailyPool = new ArrayList<>();
    public final List<Quest> weeklyPool = new ArrayList<>();
    public final List<Quest> achievements = new ArrayList<>();
    public final LinkedHashMap<String, ChainInfo> chains = new LinkedHashMap<>();
    public final LinkedHashMap<String, Shop.Cat> shop = new LinkedHashMap<>();
    private final Map<String, Quest> byId = new HashMap<>();
    private final Map<Quest.Type, List<Quest>> byType = new EnumMap<>(Quest.Type.class);

    private final Map<UUID, Long> lastWalk = new HashMap<>();
    private final Map<UUID, Location> lastLoc = new HashMap<>();

    private ZoneId zone = ZoneId.systemDefault();
    private String dayKey = "", yesterdayKey = "", weekKey = "";
    private boolean dirty;
    private File dataFile;

    public final Owners owners;
    public final Access access;

    public QuestManager(SettingsPlugin plugin) {
        this.plugin = plugin;
        this.owners = new Owners(plugin);
        this.access = new Access(plugin, this);
    }

    // ===================== загрузка =====================
    public void load() {
        try {
            zone = ZoneId.of(plugin.getConfig().getString("timezone", "Europe/Moscow"));
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        refreshDays();

        File f = new File(plugin.getDataFolder(), "quests.yml");
        if (!f.exists()) plugin.saveResource("quests.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);

        dailyPool.clear(); weeklyPool.clear(); achievements.clear();
        chains.clear(); shop.clear(); byId.clear(); byType.clear();

        for (Map<?, ?> m : y.getMapList("daily")) { Quest q = new Quest(m, Quest.Cat.DAILY); dailyPool.add(q); reg(q); }
        for (Map<?, ?> m : y.getMapList("weekly")) { Quest q = new Quest(m, Quest.Cat.WEEKLY); weeklyPool.add(q); reg(q); }
        for (Map<?, ?> m : y.getMapList("achievements")) { Quest q = new Quest(m, Quest.Cat.ACH); achievements.add(q); reg(q); }

        for (Map<?, ?> m : y.getMapList("chains")) {
            ChainInfo ci = new ChainInfo(m);
            Object so = m.get("steps");
            if (so instanceof List<?> steps) {
                String prev = null;
                int idx = 0;
                for (Object s : steps) {
                    if (!(s instanceof Map<?, ?> sm)) continue;
                    Quest q = new Quest(sm, Quest.Cat.CHAIN);
                    q.chain = ci.id;
                    q.prev = prev;
                    q.step = idx++;
                    prev = q.id;
                    ci.steps.add(q);
                    reg(q);
                }
            }
            chains.put(ci.id, ci);
        }

        for (Map<?, ?> m : y.getMapList("shop")) {
            Shop.Cat c = new Shop.Cat(m);
            Object io = m.get("items");
            if (io instanceof List<?> items) {
                int idx = 0;
                for (Object i : items) {
                    if (i instanceof Map<?, ?> im) c.items.add(new Shop.Item(c.id, idx++, im));
                }
            }
            shop.put(c.id, c);
        }

        loadData();
        owners.load();
        plugin.getLogger().info("Заданий: " + byId.size() + ", цепочек: " + chains.size() + ", категорий магазина: " + shop.size());
    }

    private void reg(Quest q) {
        byId.put(q.id, q);
        byType.computeIfAbsent(q.type, k -> new ArrayList<>()).add(q);
    }

    // ===================== запуск задач =====================
    public void start() {
        // раз в минуту: смена дня, время игры
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            refreshDays();
            for (Player p : Bukkit.getOnlinePlayers()) {
                QData d = data(p.getUniqueId());
                ensure(p.getUniqueId(), d);
                d.playMinutes++;
                dirty = true;
                access.check(p, d);
                add(p, Quest.Type.PLAYTIME, "*", 1);
            }
        }, 1200L, 1200L);

        // каждые 5 секунд: пройденное расстояние
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                long cm = walkCm(p);
                Long last = lastWalk.get(id);
                if (last == null || cm < last) { lastWalk.put(id, cm); continue; }
                long blocks = (cm - last) / 100;
                if (blocks > 0) {
                    lastWalk.put(id, last + blocks * 100);
                    add(p, Quest.Type.WALK, "*", (int) Math.min(blocks, 100000));
                }
            }
        }, 100L, 100L);

        // следы-частицы из магазина
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                QData d = data.get(p.getUniqueId());
                if (d == null || d.trail.isEmpty()) continue;
                Location loc = p.getLocation();
                Location last = lastLoc.put(p.getUniqueId(), loc);
                if (last == null || last.getWorld() == null || !last.getWorld().equals(loc.getWorld())) continue;
                if (last.distanceSquared(loc) < 0.01) continue;
                try {
                    Particle part = Particle.valueOf(d.trail.toUpperCase());
                    p.getWorld().spawnParticle(part, loc.clone().add(0, 0.1, 0), 4, 0.25, 0.05, 0.25, 0.01);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }, 4L, 4L);

        // автосохранение
        long mins = Math.max(1, plugin.getConfig().getInt("quests.autosave-minutes", 5));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (dirty) save(); owners.saveIfDirty(); }, mins * 1200L, mins * 1200L);
    }

    private long walkCm(Player p) {
        return (long) p.getStatistic(Statistic.WALK_ONE_CM) + p.getStatistic(Statistic.SPRINT_ONE_CM);
    }

    // ===================== дни / недели =====================
    private void refreshDays() {
        LocalDate today = LocalDate.now(zone);
        dayKey = today.toString();
        yesterdayKey = today.minusDays(1).toString();
        weekKey = today.get(IsoFields.WEEK_BASED_YEAR) + "-W" + today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    public long dayLeft() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(zone)).getSeconds();
    }

    public long weekLeft() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        return Duration.between(now, now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(zone)).getSeconds();
    }

    public void ensure(UUID id, QData d) {
        if (!dayKey.equals(d.dailyKey)) {
            for (String q : d.daily) { d.progress.remove(q); d.claimed.remove(q); }
            d.daily = pick(dailyPool, plugin.getConfig().getIntegerList("quests.daily-tiers"), id, dayKey);
            d.dailyKey = dayKey;
            d.dailyBonus = false;
            if (!d.lastStreakDay.equals(dayKey) && !d.lastStreakDay.equals(yesterdayKey)) d.streak = 0;
            dirty = true;
        }
        if (!weekKey.equals(d.weeklyKey)) {
            for (String q : d.weekly) { d.progress.remove(q); d.claimed.remove(q); }
            d.weekly = pick(weeklyPool, plugin.getConfig().getIntegerList("quests.weekly-tiers"), id, weekKey);
            d.weeklyKey = weekKey;
            d.weeklyBonus = false;
            dirty = true;
        }
    }

    private List<String> pick(List<Quest> pool, List<Integer> tiers, UUID id, String key) {
        if (tiers == null || tiers.isEmpty()) tiers = List.of(1, 2, 3);
        long seed = id.getMostSignificantBits() ^ (id.getLeastSignificantBits() * 31L) ^ (key.hashCode() * 131L);
        Random r = new Random(seed);
        List<Quest> avail = new ArrayList<>(pool);
        Collections.shuffle(avail, r);
        List<String> out = new ArrayList<>();
        for (int tier : tiers) {
            Iterator<Quest> it = avail.iterator();
            while (it.hasNext()) {
                Quest q = it.next();
                if (q.tier == tier) { out.add(q.id); it.remove(); break; }
            }
        }
        return out;
    }

    // ===================== доступ к данным =====================
    public QData data(UUID id) {
        return data.computeIfAbsent(id, k -> new QData());
    }

    public Map<UUID, QData> all() { return data; }

    public void markDirty() { dirty = true; }

    public void resetPlayer(UUID id) {
        data.put(id, new QData());
        dirty = true;
    }

    public Quest quest(String id) { return byId.get(id); }

    public List<Quest> list(QData d, String which) {
        List<Quest> out = new ArrayList<>();
        switch (which) {
            case "DAILY" -> { for (String id : d.daily) { Quest q = byId.get(id); if (q != null) out.add(q); } }
            case "WEEKLY" -> { for (String id : d.weekly) { Quest q = byId.get(id); if (q != null) out.add(q); } }
            case "ACH" -> out.addAll(achievements);
            default -> { }
        }
        return out;
    }

    private boolean active(QData d, Quest q) {
        return switch (q.cat) {
            case DAILY -> d.daily.contains(q.id);
            case WEEKLY -> d.weekly.contains(q.id);
            case CHAIN -> q.prev == null || d.claimed.contains(q.prev);
            case ACH -> true;
        };
    }

    public Status status(QData d, Quest q) {
        if (d.claimed.contains(q.id)) return Status.CLAIMED;
        if (!active(d, q)) return Status.LOCKED;
        return d.progress.getOrDefault(q.id, 0) >= q.amount ? Status.READY : Status.PROGRESS;
    }

    public int count(QData d, List<Quest> list, Status s) {
        int n = 0;
        for (Quest q : list) if (status(d, q) == s) n++;
        return n;
    }

    // ===================== ранги =====================
    public int rankIndex(int total) {
        int r = 0;
        for (int i = 0; i < RANK_MIN.length; i++) if (total >= RANK_MIN[i]) r = i;
        return r;
    }

    public int rewardFor(QData d, Quest q) {
        return (int) Math.round(q.reward * (1 + RANK_PCT[rankIndex(d.completedTotal)] / 100.0));
    }

    // ===================== прогресс =====================
    public void add(Player p, Quest.Type t, String target, int amount) { progress(p, t, target, amount, false); }

    public void setMax(Player p, Quest.Type t, String target, int value) { progress(p, t, target, value, true); }

    private void progress(Player p, Quest.Type t, String target, int amount, boolean max) {
        if (amount <= 0) return;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        List<Quest> list = byType.get(t);
        if (list == null) return;
        UUID id = p.getUniqueId();
        QData d = data(id);
        ensure(id, d);
        for (Quest q : list) {
            if (!q.matches(target)) continue;
            if (!active(d, q) || d.claimed.contains(q.id)) continue;
            int cur = d.progress.getOrDefault(q.id, 0);
            if (cur >= q.amount) continue;
            int nv = max ? Math.max(cur, amount) : cur + amount;
            nv = Math.min(nv, q.amount);
            if (nv == cur) continue;
            d.progress.put(q.id, nv);
            dirty = true;
            if (nv >= q.amount) onComplete(p, d, q);
            else if (d.notify) {
                p.sendActionBar(Gui.legacy("§6" + q.name + " §8» " + Gui.bar(nv, q.amount, 12) + " §f" + nv + "§7/§f" + q.amount));
            }
        }
    }

    private void onComplete(Player p, QData d, Quest q) {
        Gui.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        p.showTitle(Title.title(Gui.neon("Задание выполнено!"), Gui.legacy("§f" + q.name),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(1800), Duration.ofMillis(500))));
        Gui.msg(p, "§aЗадание выполнено: §f" + q.name + " §7— забери награду в §e/quests");

        if (q.cat == Quest.Cat.DAILY && !d.lastStreakDay.equals(dayKey)) {
            d.streak = d.lastStreakDay.equals(yesterdayKey) ? d.streak + 1 : 1;
            d.lastStreakDay = dayKey;
            d.bestStreak = Math.max(d.bestStreak, d.streak);
            int bonus = streakBonus(d.streak);
            if (bonus > 0) {
                giveRunes(d, bonus);
                Gui.msg(p, "§6Серия " + d.streak + " дн.! §7Бонус: §e+" + bonus + " рун");
            }
        }
    }

    public int streakBonus(int days) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("quests.streak-bonuses");
        if (s != null && s.isSet(String.valueOf(days))) return s.getInt(String.valueOf(days));
        return switch (days) { case 3 -> 30; case 7 -> 100; case 14 -> 250; case 30 -> 1000; default -> 0; };
    }

    // ===================== награды =====================
    private void giveRunes(QData d, long n) {
        d.runes += n;
        d.totalEarned += n;
        dirty = true;
    }

    public void giveRunesTo(UUID id, long n) { giveRunes(data(id), n); }

    private void giveItem(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values())
            p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    private void celebrate(Player p) {
        Gui.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.2f);
        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.05);
    }

    public boolean claim(Player p, Quest q) {
        UUID id = p.getUniqueId();
        QData d = data(id);
        ensure(id, d);
        if (status(d, q) != Status.READY) return false;

        d.claimed.add(q.id);
        int runes = rewardFor(d, q);
        giveRunes(d, runes);
        int before = rankIndex(d.completedTotal);
        d.completedTotal++;
        int after = rankIndex(d.completedTotal);

        StringBuilder sb = new StringBuilder("§aНаграда: §e+" + runes + " рун");
        if (q.rewardItem != null) {
            giveItem(p, new ItemStack(q.rewardItem, q.rewardItemAmount));
            sb.append("§7, §f").append(q.rewardItemAmount).append("× ").append(Gui.pretty(q.rewardItem.name()));
        }
        if (q.xp > 0) {
            p.giveExpLevels(q.xp);
            sb.append("§7, §a+").append(q.xp).append(" ур.");
        }
        Gui.msg(p, sb.toString());
        celebrate(p);

        if (after > before) {
            p.showTitle(Title.title(Gui.neon("Новый ранг!"), Gui.legacy("§f" + RANK_NAMES[after] + " §8(+" + RANK_PCT[after] + "% к наградам)"),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2500), Duration.ofMillis(600))));
            Bukkit.broadcast(Gui.legacy("§e" + p.getName() + " §7получил ранг заданий §6" + RANK_NAMES[after] + "§7!"));
        }
        dirty = true;
        return true;
    }

    public boolean bonusReady(QData d, boolean weekly) {
        List<String> ids = weekly ? d.weekly : d.daily;
        boolean got = weekly ? d.weeklyBonus : d.dailyBonus;
        if (got || ids.isEmpty()) return false;
        for (String s : ids) if (!d.claimed.contains(s)) return false;
        return true;
    }

    public boolean claimBonus(Player p, boolean weekly) {
        QData d = data(p.getUniqueId());
        ensure(p.getUniqueId(), d);
        if (!bonusReady(d, weekly)) return false;
        int amt = plugin.getConfig().getInt(weekly ? "quests.weekly-bonus" : "quests.daily-bonus", weekly ? 300 : 60);
        if (weekly) d.weeklyBonus = true; else d.dailyBonus = true;
        giveRunes(d, amt);
        Gui.msg(p, "§6Бонус за все " + (weekly ? "еженедельные" : "ежедневные") + " задания: §e+" + amt + " рун");
        celebrate(p);
        return true;
    }

    // ===================== магазин =====================
    public void buy(Player p, Shop.Item it) {
        QData d = data(p.getUniqueId());
        if (it.once && d.owned.contains(it.id)) {
            String slot = it.slot();
            if (!slot.isEmpty()) {
                if (d.equipped(slot).equalsIgnoreCase(it.value())) {
                    d.equip(slot, "");
                    Gui.msg(p, "§7Снято: §f" + it.name);
                } else {
                    d.equip(slot, it.value());
                    Gui.msg(p, "§aНадето: §f" + it.name);
                }
                plugin.applyNick(p);
                Gui.sound(p, Sound.UI_BUTTON_CLICK, 1.2f);
                dirty = true;
            } else {
                Gui.sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
            }
            return;
        }
        if (d.runes < it.price) {
            Gui.sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
            Gui.msg(p, "§cНе хватает рун: нужно §f" + it.price + "§c, у тебя §f" + d.runes);
            return;
        }
        d.runes -= it.price;
        dirty = true;
        if (it.giveMat != null) giveItem(p, new ItemStack(it.giveMat, Math.max(1, it.giveAmt)));
        if (!it.effect.isEmpty()) applyEffect(p, it.effect);
        for (String c : it.commands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()));
        if (it.once) {
            d.owned.add(it.id);
            if (!it.slot().isEmpty()) {
                d.equip(it.slot(), it.value());
                plugin.applyNick(p);
            }
        }
        Gui.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f);
        Gui.msg(p, "§aКуплено: §f" + it.name + " §7(−" + it.price + " рун)");
    }

    /** Косметика «эффект убийства». */
    public void playKillFx(Location loc, String fx) {
        if (loc.getWorld() == null) return;
        if (fx.equalsIgnoreCase("LIGHTNING")) {
            loc.getWorld().strikeLightningEffect(loc);
            return;
        }
        try {
            Particle part = Particle.valueOf(fx.toUpperCase());
            loc.getWorld().spawnParticle(part, loc.clone().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.05);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void applyEffect(Player p, String spec) {
        try {
            String[] a = spec.split(":");
            PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(a[0].trim().toLowerCase()));
            if (t == null) return;
            int lvl = a.length > 1 ? Integer.parseInt(a[1].trim()) : 1;
            int sec = a.length > 2 ? Integer.parseInt(a[2].trim()) : 60;
            p.addPotionEffect(new PotionEffect(t, sec * 20, Math.max(0, lvl - 1)));
        } catch (Exception e) {
            plugin.getLogger().warning("Неверный эффект в магазине: " + spec);
        }
    }

    // ===================== топ =====================
    public List<Map.Entry<UUID, QData>> top(int n) {
        List<Map.Entry<UUID, QData>> l = new ArrayList<>(data.entrySet());
        l.removeIf(e -> e.getValue().completedTotal <= 0);
        l.sort((a, b) -> Integer.compare(b.getValue().completedTotal, a.getValue().completedTotal));
        return l.size() > n ? new ArrayList<>(l.subList(0, n)) : l;
    }

    public int placeOf(UUID id) {
        QData me = data.get(id);
        if (me == null || me.completedTotal <= 0) return -1;
        int place = 1;
        for (QData o : data.values()) if (o.completedTotal > me.completedTotal) place++;
        return place;
    }

    // ===================== вход / выход =====================
    public void onJoin(Player p) {
        UUID id = p.getUniqueId();
        QData d = data(id);
        d.name = p.getName();
        ensure(id, d);
        lastWalk.put(id, walkCm(p));
        dirty = true;
    }

    public void onQuit(Player p) {
        lastWalk.remove(p.getUniqueId());
        lastLoc.remove(p.getUniqueId());
    }

    public void welcome(Player p) {
        QData d = data(p.getUniqueId());
        ensure(p.getUniqueId(), d);
        if (plugin.getConfig().getBoolean("join.welcome-title", true)) {
            p.showTitle(Title.title(Gui.neon("Добро пожаловать"), Gui.legacy("§f" + p.getName()),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(500))));
        }
        if (plugin.getConfig().getBoolean("join.quest-reminder", true)) {
            List<Quest> dl = list(d, "DAILY");
            int left = dl.size() - count(d, dl, Status.CLAIMED);
            int ready = count(d, dl, Status.READY) + count(d, list(d, "WEEKLY"), Status.READY);
            if (left > 0) Gui.msg(p, "§7Ежедневных заданий осталось: §e" + left + "§7. Открой §e/quests");
            if (ready > 0) Gui.msg(p, "§aЕсть выполненные задания — забери награду!");
        }
    }

    // ===================== сохранение =====================
    private void loadData() {
        data.clear();
        dataFile = new File(plugin.getDataFolder(), "quest-data.yml");
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps == null) return;
        for (String k : ps.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(k); } catch (IllegalArgumentException e) { continue; }
            ConfigurationSection s = ps.getConfigurationSection(k);
            if (s == null) continue;
            QData d = new QData();
            d.name = s.getString("name", "");
            d.runes = s.getLong("runes");
            d.totalEarned = s.getLong("total-earned");
            d.completedTotal = s.getInt("completed");
            d.streak = s.getInt("streak");
            d.bestStreak = s.getInt("best-streak");
            d.notify = s.getBoolean("notify", true);
            d.dailyBonus = s.getBoolean("daily-bonus");
            d.weeklyBonus = s.getBoolean("weekly-bonus");
            d.dailyKey = s.getString("daily-key", "");
            d.weeklyKey = s.getString("weekly-key", "");
            d.lastStreakDay = s.getString("last-streak-day", "");
            d.trail = s.getString("trail", "");
            d.nickColor = s.getString("nick-color", "");
            d.title = s.getString("title", "");
            d.killFx = s.getString("kill-fx", "");
            d.rulesAccepted = s.getBoolean("rules-accepted");
            d.unlockedBreak = s.getBoolean("unlocked-break");
            d.unlockedOpen = s.getBoolean("unlocked-open");
            d.accNether = s.getBoolean("acc-nether");
            d.accStone = s.getInt("acc-stone");
            d.accWood = s.getInt("acc-wood");
            d.accIron = s.getInt("acc-iron");
            d.playMinutes = s.getLong("play-minutes");
            d.daily = new ArrayList<>(s.getStringList("daily"));
            d.weekly = new ArrayList<>(s.getStringList("weekly"));
            d.claimed.addAll(s.getStringList("claimed"));
            d.owned.addAll(s.getStringList("owned"));
            ConfigurationSection pr = s.getConfigurationSection("progress");
            if (pr != null) for (String q : pr.getKeys(false)) d.progress.put(q, pr.getInt(q));
            data.put(id, d);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, QData> en : data.entrySet()) {
            String b = "players." + en.getKey();
            QData d = en.getValue();
            y.set(b + ".name", d.name);
            y.set(b + ".runes", d.runes);
            y.set(b + ".total-earned", d.totalEarned);
            y.set(b + ".completed", d.completedTotal);
            y.set(b + ".streak", d.streak);
            y.set(b + ".best-streak", d.bestStreak);
            y.set(b + ".notify", d.notify);
            y.set(b + ".daily-bonus", d.dailyBonus);
            y.set(b + ".weekly-bonus", d.weeklyBonus);
            y.set(b + ".daily-key", d.dailyKey);
            y.set(b + ".weekly-key", d.weeklyKey);
            y.set(b + ".last-streak-day", d.lastStreakDay);
            y.set(b + ".trail", d.trail);
            y.set(b + ".nick-color", d.nickColor);
            y.set(b + ".title", d.title);
            y.set(b + ".kill-fx", d.killFx);
            y.set(b + ".rules-accepted", d.rulesAccepted);
            y.set(b + ".unlocked-break", d.unlockedBreak);
            y.set(b + ".unlocked-open", d.unlockedOpen);
            y.set(b + ".acc-nether", d.accNether);
            y.set(b + ".acc-stone", d.accStone);
            y.set(b + ".acc-wood", d.accWood);
            y.set(b + ".acc-iron", d.accIron);
            y.set(b + ".play-minutes", d.playMinutes);
            y.set(b + ".daily", d.daily);
            y.set(b + ".weekly", d.weekly);
            y.set(b + ".claimed", new ArrayList<>(d.claimed));
            y.set(b + ".owned", new ArrayList<>(d.owned));
            for (Map.Entry<String, Integer> pe : d.progress.entrySet())
                y.set(b + ".progress." + pe.getKey(), pe.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(dataFile);
            dirty = false;
            owners.saveIfDirty();
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить quest-data.yml: " + e.getMessage());
        }
    }
}
