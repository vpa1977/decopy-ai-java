package org.debian.decopy.dep5;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A parsed paragraph from a debian/copyright file, stored as raw field map.
 */
public record StoredParagraph(Map<String, String> fields) {

    public static StoredParagraph empty() {
        return new StoredParagraph(new LinkedHashMap<>());
    }

    public String get(String field) {
        return fields.getOrDefault(field, "");
    }

    public boolean has(String field) {
        return fields.containsKey(field);
    }

    /** Return all fields not in the standard set. */
    public Map<String, String> extraFields() {
        Map<String, String> extra = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String k = e.getKey();
            if (!k.equals("Files") && !k.equals("Copyright") &&
                !k.equals("License") && !k.equals("Comment")) {
                extra.put(k, e.getValue());
            }
        }
        return extra;
    }
}
