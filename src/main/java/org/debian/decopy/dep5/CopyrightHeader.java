package org.debian.decopy.dep5;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents the header paragraph of a debian/copyright file.
 */
public final class CopyrightHeader {

    private String format;
    private String source;
    private String comment;
    private final Map<String, String> extraFields = new LinkedHashMap<>();

    public String getFormat() { return format != null ? format : ""; }
    public void setFormat(String f) { this.format = f; }

    public String getSource() { return source != null ? source : ""; }
    public void setSource(String s) { this.source = s; }

    public String getComment() { return comment != null ? comment : ""; }
    public void setComment(String c) { this.comment = c; }

    public Map<String, String> getExtraFields() { return extraFields; }

    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("Format: ").append(getFormat()).append('\n');
        if (!getSource().isEmpty()) sb.append("Source: ").append(getSource()).append('\n');
        if (!getComment().isEmpty()) sb.append("Comment: ").append(getComment()).append('\n');
        for (Map.Entry<String, String> e : extraFields.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }
}
