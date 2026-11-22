package me.settings;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Превращает игровые события в прогресс заданий и запоминает владельцев блоков. */
public class QuestListener implements Listener {
    private final QuestManager qm;

    public QuestListener(QuestManager qm) { this.qm = qm; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        // запоминаем владельца: за такие блоки не засчитываем «сломать»,
        // а новички не смогут их ломать и открывать
        qm.owners.set(e.getBlockPlaced(), e.getPlayer().getUniqueId());
        qm.add(e.getPlayer(), Quest.Type.PLACE, e.getBlockPlaced().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        String mat = b.getType().name();
        UUID owner = qm.owners.owner(b);
        qm.owners.remove(b);

        if (b.getBlockData() instanceof Ageable ag && ag.getAge() >= ag.getMaximumAge())
            qm.add(p, Quest.Type.HARVEST, mat, 1);
        if (owner != null) return; // поставленные игроками блоки в задания не идут

        qm.add(p, Quest.Type.BREAK, mat, 1);
        qm.access.onMined(p, mat);
        if (b.getType() == Material.MELON || b.getType() == Material.PUMPKIN)
            qm.add(p, Quest.Type.HARVEST, mat, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) qm.owners.remove(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) qm.owners.remove(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        qm.owners.remove(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null) return;
        qm.add(k, Quest.Type.KILL, e.getEntityType().name(), 1);
        QData d = qm.data(k.getUniqueId());
        if (!d.killFx.isEmpty()) qm.playKillFx(e.getEntity().getLocation(), d.killFx);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH && e.getCaught() instanceof Item i)
            qm.add(e.getPlayer(), Quest.Type.FISH, i.getItemStack().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack res = e.getRecipe().getResult();
        int times = 1;
        if (e.isShiftClick()) {
            int min = 64;
            for (ItemStack s : e.getInventory().getMatrix())
                if (s != null && s.getType() != Material.AIR) min = Math.min(min, s.getAmount());
            times = Math.max(1, min);
        }
        qm.add(p, Quest.Type.CRAFT, res.getType().name(), res.getAmount() * times);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSmelt(FurnaceExtractEvent e) {
        qm.add(e.getPlayer(), Quest.Type.SMELT, e.getItemType().name(), e.getItemAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        qm.add(e.getPlayer(), Quest.Type.EAT, e.getItem().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        qm.add(e.getEnchanter(), Quest.Type.ENCHANT, "*", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (e.getBreeder() instanceof Player p)
            qm.add(p, Quest.Type.BREED, e.getEntityType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent e) {
        if (e.getOwner() instanceof Player p)
            qm.add(p, Quest.Type.TAME, e.getEntityType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent e) {
        qm.add(e.getPlayer(), Quest.Type.SHEAR, e.getEntity().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent e) {
        qm.add(e.getPlayer(), Quest.Type.TRADE, "*", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        World.Environment env = p.getWorld().getEnvironment();
        qm.add(p, Quest.Type.DIMENSION, env.name(), 1);
        if (env == World.Environment.NETHER) qm.access.onNether(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevel(PlayerLevelChangeEvent e) {
        qm.setMax(e.getPlayer(), Quest.Type.LEVEL, "*", e.getNewLevel());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        String k = e.getAdvancement().getKey().getKey();
        if (k.startsWith("recipes/")) return;
        qm.add(e.getPlayer(), Quest.Type.ADVANCEMENT, "*", 1);
    }
}
