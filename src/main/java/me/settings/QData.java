package me.settings;

import java.util.*;

/** Данные игрока: руны, прогресс заданий, серия дней, покупки, доступ к чужому. */
public class QData {
    public String name = "";
    public long runes, totalEarned;
    public int completedTotal, streak, bestStreak;
    public boolean notify = true, dailyBonus, weeklyBonus;
    public String dailyKey = "", weeklyKey = "", lastStreakDay = "";
    public List<String> daily = new ArrayList<>();
    public List<String> weekly = new ArrayList<>();
    public final Map<String, Integer> progress = new HashMap<>();
    public final Set<String> claimed = new HashSet<>();
    public final Set<String> owned = new HashSet<>();

    // косметика (надето сейчас)
    public String trail = "", nickColor = "", title = "", killFx = "";

    // правила и доступ к чужому
    public boolean rulesAccepted, unlockedBreak, unlockedOpen, accNether;
    public int accStone, accWood, accIron;
    public long playMinutes;

    public String equipped(String slot) {
        return switch (slot) {
            case "trail" -> trail;
            case "nick" -> nickColor;
            case "title" -> title;
            case "killfx" -> killFx;
            default -> "";
        };
    }

    public void equip(String slot, String value) {
        switch (slot) {
            case "trail" -> trail = value;
            case "nick" -> nickColor = value;
            case "title" -> title = value;
            case "killfx" -> killFx = value;
            default -> { }
        }
    }
}
