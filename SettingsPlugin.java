package me.settings;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SettingsPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final double MIN_H = 0.4, MAX_H = 1.3;
    private static final int[] HEIGHT_SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};

    private final Set<UUID> hiddenNick = new HashSet<>();
    private final Set<UUID> noRide = new HashSet<>();
    private final Set<UUID> pingShown = new HashSet<>();
    private final Set<UUID> pvpOff = new HashSet<>();
    private final Map<UUID, Double> heights = new HashMap<>();
    private final Set<UUID> awaitingHeight = new HashSet<>();

    private File dataFile;
    private Team hiddenTeam;

    private QuestManager quests;
    private QuestMenus menus;
    private RulesManager rules;

    // ================= запуск =================
    @Override
    public void onEnable() {
        saveDefaultConfig();
        Gui.prefix = getConfig().getString("prefix", "Settings");

        getCommand("settings").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        // система заданий, магазин, руны
        quests = new QuestManager(this);
        quests.load();
        quests.start();
        menus = new QuestMenus(this, quests);
        Bukkit.getPluginManager().registerEvents(new QuestListener(quests), this);
        Bukkit.getPluginManager().registerEvents(menus, this);
        Bukkit.getPluginManager().registerEvents(new AccessListener(quests), this);
        for (String cmd : new String[]{"quests", "shop", "runes"}) {
            if (getCommand(cmd) != null) getCommand(cmd).setExecutor(menus);
        }

        // правила: книга при входе и /rules, доступ к чужому: /access
        rules = new RulesManager(this, quests);
        rules.load();
        Bukkit.getPluginManager().registerEvents(rules, this);
        for (String cmd : new String[]{"rules", "access"}) {
            if (getCommand(cmd) != null) getCommand(cmd).setExecutor(rules);
        }
        for (Player p : Bukkit.getOnlinePlayers()) quests.onJoin(p);

        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        hiddenTeam = sb.getTeam("st_hidden");
        if (hiddenTeam == null) hiddenTeam = sb.registerNewTeam("st_hidden");
        hiddenTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);

        // обновление пинга в табе
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers())
                if (pingShown.contains(p.getUniqueId())) updatePing(p);
        }, 40L, 40L);
    }

    @Override
    public void onDisable() {
        saveData();
        if (quests != null) quests.save();
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (s instanceof Player p) {
            Gui.sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f);
            openMain(p);
        } else s.sendMessage("Только для игроков.");
        return true;
    }

    private void updatePing(Player p) {
        p.playerListName(Gui.LEGACY.deserialize(p.getName() + " §7" + p.getPing() + "ms"));
    }

    private boolean canHeight(Player p) {
        for (String perm : getConfig().getStringList("height-permissions"))
            if (p.hasPermission(perm)) return true;
        return false;
    }

    // ================= меню настроек =================
    public void openMain(Player p) {
        UUID id = p.getUniqueId();
        MenuHolder h = new MenuHolder(MenuHolder.T.SETTINGS, "", 0);
        Inventory inv = Bukkit.createInventory(h, 27, Gui.neon("Settings"));
        h.inv = inv;
        Gui.fill(inv);

        boolean nick = hiddenNick.contains(id);
        boolean ride = noRide.contains(id);
        boolean ping = pingShown.contains(id);
        boolean pvp = pvpOff.contains(id);
        boolean notify = quests.data(id).notify;
        double cur = heights.getOrDefault(id, 1.0);

        inv.setItem(10, Gui.item(Material.ENDER_CHEST, "Скрыть ник", nick,
                "§7Прячет твой ник над головой.", "",
                "§7Сейчас: " + (nick ? "§c● Ник скрыт" : "§a● Ник виден"),
                "", "§8» §eНажми, чтобы переключить"));

        inv.setItem(11, Gui.item(Material.AMETHYST_CLUSTER, "Сменить рост", cur != 1.0,
                "§7Выбери рост от §f" + MIN_H + " §7до §f" + MAX_H + "§7.", "",
                "§7Сейчас: §f" + cur,
                canHeight(p) ? "" : "§c✖ Только для Sponsor и Sponsor+",
                canHeight(p) ? "§8» §eНажми, чтобы открыть" : ""));

        inv.setItem(12, Gui.item(Material.SLIME_BLOCK, "Сидеть на мне", ride,
                "§7Можно ли садиться на тебя (GSit).", "",
                "§7Сейчас: " + (ride ? "§c● Запрещено" : "§a● Разрешено"),
                "", "§8» §eНажми, чтобы переключить"));

        inv.setItem(13, Gui.item(Material.SCULK_SENSOR, "Пинг в табе", ping,
                "§7Показывает твой пинг рядом с ником в табе.", "",
                "§7Сейчас: " + (ping ? "§a● Показан" : "§c● Скрыт"),
                "", "§8» §eНажми, чтобы переключить"));

        inv.setItem(14, Gui.item(Material.MACE, "PvP режим", pvp,
                "§7Отключает урон от игроков и по тебе.", "",
                "§7Сейчас: " + (pvp ? "§c● PvP отключён" : "§a● PvP включён"),
                "", "§8» §eНажми, чтобы переключить"));

        inv.setItem(15, Gui.item(Material.BELL, "Уведомления заданий", notify,
                "§7Полоска прогресса над хотбаром", "§7при выполнении заданий.", "",
                "§7Сейчас: " + (notify ? "§a● Включены" : "§c● Выключены"),
                "", "§8» §eНажми, чтобы переключить"));

        inv.setItem(16, Gui.item(Material.WRITTEN_BOOK, "Задания и магазин", false,
                "§7Задания, награды, руны и магазин.", "", "§8» §eНажми, чтобы открыть"));

        inv.setItem(22, Gui.item(Material.BARRIER, "Закрыть", false, "§7Закрыть меню."));
        p.openInventory(inv);
    }

    private void openHeight(Player p) {
        UUID id = p.getUniqueId();
        double cur = heights.getOrDefault(id, 1.0);
        MenuHolder h = new MenuHolder(MenuHolder.T.HEIGHT, "", 0);
        Inventory inv = Bukkit.createInventory(h, 36, Gui.neon("Settings"));
        h.inv = inv;
        Gui.fill(inv);

        for (int i = 0; i < HEIGHT_SLOTS.length; i++) {
            double v = heightAt(i);
            boolean sel = Math.abs(cur - v) < 0.001;
            org.bukkit.inventory.ItemStack it = Gui.item(Material.AMETHYST_SHARD, "Рост " + v, sel,
                    "§7Выбрать рост §f" + v, "",
                    sel ? "§a✔ Сейчас выбран" : "§8» §eНажми, чтобы выбрать");
            it.setAmount((int) Math.round(v * 10));
            inv.setItem(HEIGHT_SLOTS[i], it);
        }
        inv.setItem(30, Gui.item(Material.LIME_DYE, "Сбросить (1.0)", false, "§7Вернуть обычный рост."));
        inv.setItem(31, Gui.item(Material.ARROW, "Назад", false, "§7Вернуться в настройки."));
        inv.setItem(32, Gui.item(Material.WRITABLE_BOOK, "Свой рост", false,
                "§7Введи любое число от §f" + MIN_H + " §7до §f" + MAX_H, "§7в чат, например §f0.75"));
        p.openInventory(inv);
    }

    private double heightAt(int i) {
        return Math.round((MIN_H + i * 0.1) * 10) / 10.0;
    }

    // ================= клики =================
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder h)) return;
        if (h.type != MenuHolder.T.SETTINGS && h.type != MenuHolder.T.HEIGHT) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        UUID id = p.getUniqueId();
        int slot = e.getSlot();

        if (h.type == MenuHolder.T.SETTINGS) {
            switch (slot) {
                case 10 -> { toggle(hiddenNick, id); applyNick(p); }
                case 11 -> {
                    if (!canHeight(p)) {
                        Gui.sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
                        Gui.msg(p, "§cСмена роста доступна только для Sponsor и Sponsor+.");
                        return;
                    }
                    Gui.sound(p, Sound.UI_BUTTON_CLICK, 1.2f);
                    openHeight(p);
                    return;
                }
                case 12 -> toggle(noRide, id);
                case 13 -> {
                    toggle(pingShown, id);
                    if (pingShown.contains(id)) updatePing(p);
                    else p.playerListName(null);
                }
                case 14 -> toggle(pvpOff, id);
                case 15 -> {
                    QData qd = quests.data(id);
                    qd.notify = !qd.notify;
                    quests.markDirty();
                }
                case 16 -> {
                    Gui.sound(p, Sound.UI_BUTTON_CLICK, 1.2f);
                    menus.open(p, MenuHolder.T.HUB, "", 0);
                    return;
                }
                case 22 -> { Gui.sound(p, Sound.UI_BUTTON_CLICK, 0.8f); p.closeInventory(); return; }
                default -> { return; }
            }
            Gui.sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4f);
            saveData();
            openMain(p);
        } else {
            if (!canHeight(p)) { p.closeInventory(); return; }
            if (slot == 31) { Gui.sound(p, Sound.UI_BUTTON_CLICK, 0.8f); openMain(p); return; }
            if (slot == 30) { setHeight(p, 1.0); return; }
            if (slot == 32) {
                awaitingHeight.add(id);
                p.closeInventory();
                Gui.msg(p, "§eВведи рост от §f" + MIN_H + " §eдо §f" + MAX_H + " §eв чат §7(или «cancel»).");
                return;
            }
            for (int i = 0; i < HEIGHT_SLOTS.length; i++) {
                if (HEIGHT_SLOTS[i] == slot) { setHeight(p, heightAt(i)); return; }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }

    private void toggle(Set<UUID> set, UUID id) {
        if (!set.remove(id)) set.add(id);
    }

    // ================= ник =================
    /** Скрытый ник или цвет ника из магазина (цвет виден над головой и в табе). */
    public void applyNick(Player p) {
        UUID id = p.getUniqueId();
        String name = p.getName();
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        hiddenTeam.removeEntry(name);
        for (Team t : sb.getTeams()) if (t.getName().startsWith("stc_")) t.removeEntry(name);

        if (hiddenNick.contains(id)) {
            hiddenTeam.addEntry(name);
            return;
        }
        String c = quests.data(id).nickColor;
        if (c == null || c.isEmpty()) return;
        String key = c.toLowerCase();
        NamedTextColor col = NamedTextColor.NAMES.value(key);
        if (col == null) return;
        Team t = sb.getTeam("stc_" + key);
        if (t == null) t = sb.registerNewTeam("stc_" + key);
        t.color(col);
        t.addEntry(name);
    }

    // ================= рост =================
    private AttributeInstance scale(Player p) {
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("scale"));
        return a == null ? null : p.getAttribute(a);
    }

    private void setHeight(Player p, double v) {
        v = Math.max(MIN_H, Math.min(MAX_H, v));
        AttributeInstance at = scale(p);
        if (at == null) { Gui.msg(p, "§cЭта версия не поддерживает смену роста."); return; }
        at.setBaseValue(v);
        heights.put(p.getUniqueId(), v);
        saveData();
        Gui.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.3f);
        Gui.msg(p, "§aРост изменён: §f" + v);
        openHeight(p);
    }

    @EventHandler
    @SuppressWarnings("deprecation")
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!awaitingHeight.contains(p.getUniqueId())) return;
        e.setCancelled(true);
        String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(this, () -> {
            awaitingHeight.remove(p.getUniqueId());
            if (text.equalsIgnoreCase("cancel")) { Gui.msg(p, "§7Отменено."); return; }
            if (!canHeight(p)) return;
            try {
                double v = Double.parseDouble(text.replace(',', '.'));
                if (v < MIN_H || v > MAX_H) { Gui.msg(p, "§cДопустимо от " + MIN_H + " до " + MAX_H + "."); return; }
                setHeight(p, v);
            } catch (NumberFormatException ex) {
                Gui.msg(p, "§cЭто не число.");
            }
        });
    }

    // ================= вход / выход =================
    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // убираем стандартное «игрок зашёл в игру»
        if (getConfig().getBoolean("join.hide-join-message", true)) e.joinMessage(null);

        applyNick(p);
        if (pingShown.contains(p.getUniqueId())) updatePing(p);
        Double h = heights.get(p.getUniqueId());
        AttributeInstance at = scale(p);
        if (h != null && at != null) {
            if (canHeight(p)) at.setBaseValue(h);
            else { at.setBaseValue(1.0); heights.remove(p.getUniqueId()); }
        }

        quests.onJoin(p);
        applyNick(p);
        rules.startForced(p);
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) quests.welcome(p); }, 40L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent e) {
        if (getConfig().getBoolean("join.hide-quit-message", true)) e.quitMessage(null);
        awaitingHeight.remove(e.getPlayer().getUniqueId());
        quests.onQuit(e.getPlayer());
        rules.onQuit(e.getPlayer());
    }

    // ================= титул в чате (косметика) =================
    @EventHandler(priority = EventPriority.HIGH)
    public void onChatTitle(AsyncChatEvent e) {
        QData d = quests.data(e.getPlayer().getUniqueId());
        if (d.title == null || d.title.isEmpty()) return;
        Component prefix = Gui.LEGACY.deserialize(d.title);
        e.renderer((source, name, message, viewer) -> Component.empty()
                .append(prefix).append(Component.space()).append(name)
                .append(Component.text(" » ", NamedTextColor.GRAY)).append(message));
    }

    // ================= посадка на игрока =================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof Player t && noRide.contains(t.getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text("Этот игрок запретил садиться на него", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMount(EntityMountEvent e) {
        if (e.getMount() instanceof Player t && noRide.contains(t.getUniqueId())) e.setCancelled(true);
    }

    // ================= PvP =================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player damager = null;
        if (e.getDamager() instanceof Player p) damager = p;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) damager = p;
        if (damager == null || damager.equals(victim)) return;

        if (pvpOff.contains(damager.getUniqueId())) {
            e.setCancelled(true);
            damager.sendActionBar(Component.text("У тебя отключён PvP режим", NamedTextColor.RED));
        } else if (pvpOff.contains(victim.getUniqueId())) {
            e.setCancelled(true);
            damager.sendActionBar(Component.text("У этого игрока отключён PvP", NamedTextColor.RED));
        }
    }

    // ================= сохранение =================
    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        load(y, "hidden-nick", hiddenNick);
        load(y, "no-ride", noRide);
        load(y, "ping", pingShown);
        load(y, "pvp-off", pvpOff);
        if (y.isConfigurationSection("heights"))
            for (String k : y.getConfigurationSection("heights").getKeys(false))
                heights.put(UUID.fromString(k), y.getDouble("heights." + k));
    }

    private void load(YamlConfiguration y, String key, Set<UUID> set) {
        for (String s : y.getStringList(key)) set.add(UUID.fromString(s));
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("hidden-nick", hiddenNick.stream().map(UUID::toString).toList());
        y.set("no-ride", noRide.stream().map(UUID::toString).toList());
        y.set("ping", pingShown.stream().map(UUID::toString).toList());
        y.set("pvp-off", pvpOff.stream().map(UUID::toString).toList());
        for (Map.Entry<UUID, Double> en : heights.entrySet())
            y.set("heights." + en.getKey(), en.getValue());
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Не удалось сохранить data.yml: " + ex.getMessage());
        }
    }
}
