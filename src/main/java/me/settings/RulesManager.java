package me.settings;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Duration;
import java.util.*;

/**
 * Правила: книга при входе (игрок заморожен, пока не прочитает) и по команде /rules.
 * Сервер не видит, как игрок листает книгу, поэтому время на чтение считается по таймеру,
 * а когда оно выходит, книга закрывается сама и игрок освобождается.
 */
public class RulesManager implements Listener, CommandExecutor {

    // тёмные цвета: книга светлая, неоновый жёлтый на ней не виден
    private static final TextColor BA = TextColor.fromHexString("#9A6B00");
    private static final TextColor BB = TextColor.fromHexString("#C2410C");
    private static final TextColor BC = TextColor.fromHexString("#9F1D00");
    private static final int PAGE_LINES = 13;
    private static final double CHARS_PER_LINE = 19.0;

    private record Section(String title, List<String> lines) {}

    private final SettingsPlugin plugin;
    private final QuestManager qm;
    private final Set<UUID> frozen = new HashSet<>();
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final List<Section> sections = new ArrayList<>();
    private List<String> welcome = List.of();
    private List<String> outro = List.of();
    private String bookTitle = "Правила сервера";
    private String author = "Администрация";

    public RulesManager(SettingsPlugin plugin, QuestManager qm) {
        this.plugin = plugin;
        this.qm = qm;
    }

    public void load() {
        File f = new File(plugin.getDataFolder(), "rules.yml");
        if (!f.exists()) plugin.saveResource("rules.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        bookTitle = y.getString("title", "Правила сервера");
        author = y.getString("author", "Администрация");
        welcome = y.getStringList("welcome");
        outro = y.getStringList("outro");
        sections.clear();
        for (Map<?, ?> m : y.getMapList("sections"))
            sections.add(new Section(Quest.str(m, "title", "Раздел"), Shop.strList(m.get("lines"))));
        plugin.getLogger().info("Разделов правил: " + sections.size());
    }

    // ===================================================================
    //  Книга
    // ===================================================================
    private Component nl() { return Component.newline(); }

    private Component head(String text) { return Gui.gradient(text, BA, BB, BC); }

    private Component black(String text) { return Component.text(text, NamedTextColor.BLACK); }

    private Component rule(String line) {
        int sp = line.indexOf(' ');
        if (sp > 0 && Character.isDigit(line.charAt(0)))
            return Component.text(line.substring(0, sp + 1), BA).append(black(line.substring(sp + 1)));
        return black(line);
    }

    private int rows(String s) { return Math.max(1, (int) Math.ceil(s.length() / CHARS_PER_LINE)); }

    private Component mark(boolean ok) {
        return Component.text(ok ? "✔" : "✘", ok ? NamedTextColor.DARK_GREEN : NamedTextColor.DARK_RED);
    }

    private Component cover() {
        Component c = Component.empty()
                .append(nl())
                .append(head("   ✦ ПРАВИЛА ✦")).append(nl())
                .append(head("      сервера")).append(nl())
                .append(nl());
        for (String w : welcome) c = c.append(black(w)).append(nl()).append(nl());
        return c.append(Component.text("Листай страницы ›", BA));
    }

    private List<Component> sectionPages(Section s) {
        List<Component> out = new ArrayList<>();
        Component cur = null;
        int used = 0, part = 0;
        for (String line : s.lines()) {
            int r = rows(line);
            if (cur == null || used + r > PAGE_LINES) {
                if (cur != null) out.add(cur);
                part++;
                cur = head("★ " + s.title() + (part > 1 ? " ›" : "")).append(nl()).append(nl());
                used = 2;
            }
            cur = cur.append(rule(line)).append(nl());
            used += r;
        }
        if (cur != null) out.add(cur);
        return out;
    }

    private Component accessPage(Player p) {
        Access a = qm.access;
        Component c = head("★ Доступ к чужому").append(nl()).append(nl());
        if (!a.enabled()) {
            return c.append(black("Ограничений на чужое нет. Но правила 2 и 3 действуют всегда."));
        }
        QData d = qm.data(p.getUniqueId());
        c = c.append(black("Ломать и открывать чужое можно после заданий:")).append(nl());
        c = c.append(mark(d.accStone >= a.stone())).append(black(" Добудь " + a.stone() + " камня")).append(nl());
        c = c.append(mark(d.accWood >= a.wood())).append(black(" Добудь " + a.wood() + " дерева")).append(nl());
        c = c.append(mark(d.accIron >= a.iron())).append(black(" Добудь " + a.iron() + " железа")).append(nl());
        if (a.nether()) c = c.append(mark(d.accNether)).append(black(" Посети Незер (ад)")).append(nl());
        c = c.append(mark(d.playMinutes >= a.breakMin())).append(black(" " + a.breakMin() + " мин игры: ломать")).append(nl());
        c = c.append(mark(d.playMinutes >= a.openMin())).append(black(" " + a.openMin() + " мин игры: открывать"));
        return c;
    }

    private Component outroPage() {
        Component c = head("★ Готово").append(nl()).append(nl());
        for (String line : outro) c = c.append(black(line)).append(nl()).append(nl());
        return c;
    }

    private List<Component> pages(Player p) {
        List<Component> pages = new ArrayList<>();
        pages.add(cover());
        for (Section s : sections) pages.addAll(sectionPages(s));
        pages.add(accessPage(p));
        pages.add(outroPage());
        return pages;
    }

    public void openBook(Player p) {
        p.openBook(Book.book(Component.text(bookTitle), Component.text(author), pages(p)));
    }

    // ===================================================================
    //  Принудительное чтение при входе
    // ===================================================================
    public void startForced(Player p) {
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("rules.enabled", true)) return;
        UUID id = p.getUniqueId();
        QData d = qm.data(id);
        if (d.rulesAccepted && !c.getBoolean("rules.show-every-join", true)) return;

        cleanup(id);
        frozen.add(id);

        int perPage = Math.max(1, c.getInt("rules.seconds-per-page", 3));
        int secs = Math.max(15, Math.min(240, pages(p).size() * perPage));

        BossBar bar = BossBar.bossBar(Gui.legacy("§eПрочитай правила"), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        p.showBossBar(bar);
        bars.put(id, bar);
        Gui.msg(p, "§7Прочитай правила. Пока идёт чтение, двигаться нельзя.");

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && frozen.contains(id)) openBook(p);
        }, 30L);

        int[] left = {secs};
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) { cleanup(id); return; }
            left[0]--;
            bar.progress(Math.max(0f, Math.min(1f, left[0] / (float) secs)));
            bar.name(Gui.legacy("§eПрочитай правила §8» §f" + Math.max(0, left[0]) + " с §7— листай страницы"));
            if (left[0] <= 0) finish(p);
        }, 50L, 20L);
        tasks.put(id, t);
    }

    private void finish(Player p) {
        UUID id = p.getUniqueId();
        cleanup(id);
        p.closeInventory();
        QData d = qm.data(id);
        d.rulesAccepted = true;
        qm.markDirty();
        Gui.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.2f);
        p.showTitle(Title.title(Gui.neon("Правила прочитаны"), Gui.legacy("§fПриятной игры!"),
                Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2000), Duration.ofMillis(600))));
        if (qm.access.enabled() && !(d.unlockedBreak && d.unlockedOpen)) qm.access.sendStatus(p, 0);
    }

    private void cleanup(UUID id) {
        BukkitTask t = tasks.remove(id);
        if (t != null) t.cancel();
        BossBar b = bars.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (p != null && b != null) p.hideBossBar(b);
        frozen.remove(id);
    }

    public void onQuit(Player p) { cleanup(p.getUniqueId()); }

    // ===================================================================
    //  Заморозка
    // ===================================================================
    private boolean frozen(Player p) { return frozen.contains(p.getUniqueId()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!frozen(e.getPlayer())) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location t = from.clone();
            t.setYaw(to.getYaw());
            t.setPitch(to.getPitch());
            e.setTo(t);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && frozen(p)) e.setCancelled(true);
    }

    // ===================================================================
    //  Команды /rules и /access
    // ===================================================================
    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Только для игроков."); return true; }
        if (c.getName().equalsIgnoreCase("access")) {
            qm.access.sendStatus(p, 0);
            return true;
        }
        Gui.sound(p, Sound.ITEM_BOOK_PAGE_TURN, 1f);
        openBook(p);
        Gui.msg(p, "§7Правила открыты. Закрыть книгу можно в любой момент (§fEsc§7).");
        return true;
    }
}
