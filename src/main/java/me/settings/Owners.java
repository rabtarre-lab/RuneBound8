package me.settings;

import org.bukkit.block.Block;

import java.io.*;
import java.util.*;

/** Запоминает, кто поставил блок. Нужен для защиты «чужого» от новичков. */
public class Owners {
    private final SettingsPlugin plugin;
    private final Map<String, Map<Long, Integer>> worlds = new HashMap<>();
    private final List<UUID> players = new ArrayList<>();
    private final Map<UUID, Integer> index = new HashMap<>();
    private boolean dirty;
    private File file;

    public Owners(SettingsPlugin plugin) {
        this.plugin = plugin;
    }

    private static long pack(Block b) {
        return ((long) (b.getX() & 0x3FFFFFF) << 38) | ((long) (b.getZ() & 0x3FFFFFF) << 12) | ((b.getY() + 64) & 0xFFF);
    }

    private Map<Long, Integer> map(Block b, boolean create) {
        String w = b.getWorld().getName();
        Map<Long, Integer> m = worlds.get(w);
        if (m == null && create) {
            m = new HashMap<>();
            worlds.put(w, m);
        }
        return m;
    }

    public UUID owner(Block b) {
        Map<Long, Integer> m = map(b, false);
        if (m == null) return null;
        Integer i = m.get(pack(b));
        return i == null ? null : players.get(i);
    }

    public void set(Block b, UUID id) {
        Integer idx = index.get(id);
        if (idx == null) {
            players.add(id);
            idx = players.size() - 1;
            index.put(id, idx);
        }
        map(b, true).put(pack(b), idx);
        dirty = true;
    }

    public void remove(Block b) {
        Map<Long, Integer> m = map(b, false);
        if (m != null && m.remove(pack(b)) != null) dirty = true;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "owners.dat");
        worlds.clear(); players.clear(); index.clear();
        if (!file.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int np = in.readInt();
            for (int i = 0; i < np; i++) {
                UUID id = new UUID(in.readLong(), in.readLong());
                players.add(id);
                index.put(id, i);
            }
            int nw = in.readInt();
            for (int w = 0; w < nw; w++) {
                String name = in.readUTF();
                int n = in.readInt();
                Map<Long, Integer> m = new HashMap<>(Math.max(16, n * 2));
                for (int i = 0; i < n; i++) m.put(in.readLong(), in.readInt());
                worlds.put(name, m);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось прочитать owners.dat: " + e.getMessage());
        }
    }

    public void saveIfDirty() {
        if (!dirty) return;
        plugin.getDataFolder().mkdirs();
        File tmp = new File(plugin.getDataFolder(), "owners.dat.tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(players.size());
            for (UUID id : players) {
                out.writeLong(id.getMostSignificantBits());
                out.writeLong(id.getLeastSignificantBits());
            }
            out.writeInt(worlds.size());
            for (Map.Entry<String, Map<Long, Integer>> w : worlds.entrySet()) {
                out.writeUTF(w.getKey());
                out.writeInt(w.getValue().size());
                for (Map.Entry<Long, Integer> en : w.getValue().entrySet()) {
                    out.writeLong(en.getKey());
                    out.writeInt(en.getValue());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить owners.dat: " + e.getMessage());
            return;
        }
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("Не удалось заменить owners.dat");
            return;
        }
        if (tmp.renameTo(file)) dirty = false;
    }
}
