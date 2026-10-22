package io.github.lihuu.panamaview;

import java.util.Set;
import java.util.Objects;

public record Shortcut(String key, Set<Modifier> modifiers) {
    public enum Modifier {
        CMD_OR_CTRL, CTRL, SHIFT, ALT
    }

    public static Shortcut parse(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("Shortcut string cannot be null or blank: " + s);
        }

        String[] parts = s.split("\\+");
        if (parts.length == 0) {
            throw new IllegalArgumentException("Invalid shortcut format: " + s);
        }

        String key = parts[parts.length - 1];
        java.util.Set<Modifier> modifiers = new java.util.HashSet<>();

        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i].trim().toUpperCase();
            switch (part) {
                case "CMDORCTRL" -> modifiers.add(Modifier.CMD_OR_CTRL);
                case "CTRL" -> modifiers.add(Modifier.CTRL);
                case "SHIFT" -> modifiers.add(Modifier.SHIFT);
                case "ALT" -> modifiers.add(Modifier.ALT);
                default -> throw new IllegalArgumentException("Unknown modifier: " + parts[i] + " in shortcut " + s);
            }
        }

        return new Shortcut(key, java.util.Set.copyOf(modifiers));
    }
}
