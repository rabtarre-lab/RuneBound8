package me.settings;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Не пускает новичков к чужим блокам, сундукам, дверям и питомцам. */
public class AccessListener implements Listener {
    private final QuestManager qm;

    public AccessListener(QuestManager qm) { this.qm = qm; }

    private List<Block> parts(Block b) {
        List<Block> l = new ArrayList<>();
        l.add(b);
        BlockData bd = b.getBlockData();
        if (bd instanceof Door) {
            l.add(b.getRelative(BlockFace.UP));
            l.add(b.getRelative(BlockFace.DOWN));
        } else if (bd instanceof Bed || bd instanceof Chest) {
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block r = b.getRelative(f);
                if (r.getType() == b.getType()) l.add(r);
            }
        }
        return l;
    }

    private boolean othersOwn(Block b, UUID me) {
        for (Block part : parts(b)) {
            UUID o = qm.owners.owner(part);
            if (o != null && !o.equals(me)) return true;
        }
        return false;
    }

    private boolean isOpenable(Block b) {
        Material t = b.getType();
        if (t == Material.ENDER_CHEST || t == Material.IRON_DOOR || t == Material.IRON_TRAPDOOR) return false;
        if (b.getBlockData() instanceof Openable) return true;
        return b.getState() instanceof Container;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Access a = qm.access;
        if (a.exempt(p)) return;
        if (a.allowedBreak(qm.data(p.getUniqueId()))) return;
        if (othersOwn(e.getBlock(), p.getUniqueId())) {
            e.setCancelled(true);
            a.denied(p, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Player p = e.getPlayer();
        Access a = qm.access;
        if (a.exempt(p)) return;
        // пригнулся с блоком в руке — это постановка блока, а не открытие
        if (p.isSneaking() && p.getInventory().getItemInMainHand().getType() != Material.AIR) return;
        if (!isOpenable(b)) return;
        if (a.allowedOpen(qm.data(p.getUniqueId()))) return;
        if (othersOwn(b, p.getUniqueId())) {
            e.setCancelled(true);
            a.denied(p, true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPetHit(EntityDamageByEntityEvent e) {
        Player p = null;
        if (e.getDamager() instanceof Player direct) p = direct;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player shooter) p = shooter;
        if (p == null) return;
        if (!(e.getEntity() instanceof Tameable t) || !t.isTamed() || t.getOwner() == null) return;
        if (t.getOwner().getUniqueId().equals(p.getUniqueId())) return;
        Access a = qm.access;
        if (a.exempt(p) || a.allowedBreak(qm.data(p.getUniqueId()))) return;
        e.setCancelled(true);
        a.denied(p, false);
    }
}
