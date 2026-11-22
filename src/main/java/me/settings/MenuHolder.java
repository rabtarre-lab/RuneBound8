package me.settings;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class MenuHolder implements InventoryHolder {
    public enum T { SETTINGS, HEIGHT, HUB, QUESTS, CHAINS, CHAIN, SHOP, SHOPCAT, TOP }

    public final T type;
    public final String arg;
    public final int page;
    public Inventory inv;

    public MenuHolder(T type, String arg, int page) {
        this.type = type;
        this.arg = arg;
        this.page = page;
    }

    @Override
    public Inventory getInventory() { return inv; }
}
