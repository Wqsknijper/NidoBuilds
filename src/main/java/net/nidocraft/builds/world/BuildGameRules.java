package net.nidocraft.builds.world;

import org.bukkit.GameRule;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class BuildGameRules {
    private BuildGameRules() { }

    public static Map<String, String> defaults() {
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("doWeatherCycle", "false");
        rules.put("doDaylightCycle", "false");
        rules.put("randomTickSpeed", "0");
        rules.put("doMobSpawning", "false");
        rules.put("doMobLoot", "false");
        rules.put("announceAdvancements", "false");
        rules.put("doFireTick", "false");
        rules.put("spectatorsGenerateChunks", "false");
        return rules;
    }

    public static void apply(World world, Map<String, String> values) {
        defaults().forEach((name, value) -> set(world, name, values.getOrDefault(name, value)));
        values.forEach((name, value) -> set(world, name, value));
        world.setStorm(false);
        world.setThundering(false);
        world.setWeatherDuration(Integer.MAX_VALUE);
        world.setThunderDuration(Integer.MAX_VALUE);
    }

    public static String normalizeName(String input) {
        GameRule<?> rule = find(input);
        return displayName(rule);
    }

    public static String normalizeValue(String ruleName, String input) {
        GameRule<?> rule = find(ruleName);
        if (rule.getType() == Boolean.class) {
            String lower = input.toLowerCase(Locale.ROOT);
            if (!lower.equals("true") && !lower.equals("false")) throw new IllegalArgumentException("Boolean gamerule requires true or false.");
            return lower;
        }
        try {
            int value = Integer.parseInt(input);
            if (value < 0) throw new IllegalArgumentException("Integer gamerule cannot be negative.");
            return Integer.toString(value);
        }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("Integer gamerule requires a whole number."); }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void set(World world, String ruleName, String rawValue) {
        GameRule rule = find(ruleName);
        Object value = rule.getType() == Boolean.class ? Boolean.valueOf(normalizeValue(ruleName, rawValue)) : Integer.valueOf(normalizeValue(ruleName, rawValue));
        if (!world.setGameRule(rule, value)) throw new IllegalStateException("Paper rejected gamerule " + ruleName + ".");
    }

    public static java.util.List<String> names() { return rules().keySet().stream().sorted().toList(); }

    public static GameRule<?> find(String input) {
        String wanted = token(input);
        return rules().entrySet().stream().filter(entry -> token(entry.getKey()).equals(wanted)).map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown gamerule: " + input));
    }

    private static String displayName(GameRule<?> rule) {
        return rules().entrySet().stream().filter(entry -> entry.getValue() == rule).map(Map.Entry::getKey).findFirst().orElse(rule.getKey().getKey());
    }

    private static Map<String, GameRule<?>> rules() {
        Map<String, GameRule<?>> result = new LinkedHashMap<>();
        for (Field field : GameRule.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !GameRule.class.isAssignableFrom(field.getType())) continue;
            try { result.put(fieldName(field.getName()), (GameRule<?>) field.get(null)); }
            catch (IllegalAccessException exception) { throw new IllegalStateException("Cannot inspect Paper gamerules.", exception); }
        }
        return result;
    }

    private static String fieldName(String name) {
        String[] parts = name.toLowerCase(Locale.ROOT).split("_"); StringBuilder value = new StringBuilder(parts[0]);
        for (int index = 1; index < parts.length; index++) value.append(Character.toUpperCase(parts[index].charAt(0))).append(parts[index].substring(1));
        return value.toString();
    }

    private static String token(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
}
