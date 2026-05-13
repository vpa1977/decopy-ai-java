package org.debian.decopy.datatypes;

import java.util.HashMap;
import java.util.Map;

/**
 * A software license with a name and optional stored text.
 */
public final class License {

    private static final Map<String, License> LICENSES = new HashMap<>();

    private final String name;
    private String storedText;

    private License(String name) {
        this.name = name;
        this.storedText = null;
    }

    public static License get(String name) {
        return LICENSES.computeIfAbsent(name, License::new);
    }

    public static void reset() {
        LICENSES.clear();
    }

    public String getName() { return name; }

    public String getStoredText() { return storedText; }
    public void setStoredText(String text) { this.storedText = text; }

    public boolean hasStoredText() { return storedText != null; }

    @Override
    public String toString() {
        if (storedText != null) {
            return storedText.stripTrailing();
        }
        return "License: " + name + "\n" +
               "Comment: Add the corresponding license text here";
    }
}
