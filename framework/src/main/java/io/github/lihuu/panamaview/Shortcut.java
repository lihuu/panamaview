package io.github.lihuu.panamaview;

import java.util.Set;
import java.util.HashSet;
import java.util.Locale;

public record Shortcut(String key, Set<Modifier> modifiers) {
    public enum Modifier {
        CMD_OR_CTRL, CTRL, SHIFT, ALT
    }

    public static Shortcut parse(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("Shortcut string cannot be null or blank: " + s);
        }

        String[] parts = s.split("\\+", -1);
        if (parts.length == 0) {
            throw new IllegalArgumentException("Invalid shortcut format: " + s);
        }

        String key = parts[parts.length - 1].trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Shortcut key cannot be empty: " + s);
        }

        Set<Modifier> modifiers = new HashSet<>();

        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i].trim().toUpperCase(Locale.ROOT);
            switch (part) {
                case "CMDORCTRL" -> modifiers.add(Modifier.CMD_OR_CTRL);
                case "CTRL" -> modifiers.add(Modifier.CTRL);
                case "SHIFT" -> modifiers.add(Modifier.SHIFT);
                case "ALT" -> modifiers.add(Modifier.ALT);
                default -> throw new IllegalArgumentException("Unknown modifier: " + parts[i] + " in shortcut " + s);
            }
        }

        // The key cannot be a known modifier name, regardless of segment count
        String keyUpper = key.toUpperCase(Locale.ROOT);
        if (keyUpper.equals("CMDORCTRL") || keyUpper.equals("CTRL") ||
            keyUpper.equals("SHIFT") || keyUpper.equals("ALT")) {
            throw new IllegalArgumentException("Modifier cannot be the key: " + s);
        }

        return new Shortcut(key, Set.copyOf(modifiers));
    }
}
