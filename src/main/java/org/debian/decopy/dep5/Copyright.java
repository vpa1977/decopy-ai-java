package org.debian.decopy.dep5;

import org.debian.decopy.Options;
import org.debian.decopy.datatypes.CopyrightHolder;
import org.debian.decopy.datatypes.License;
import org.debian.decopy.matchers.CopyrightParser;
import org.debian.decopy.tree.DirInfo;
import org.debian.decopy.tree.FileInfo;
import org.debian.decopy.tree.RootInfo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Represents the entire debian/copyright file, managing file groups.
 */
public final class Copyright {

    private static final Logger LOG = Logger.getLogger(Copyright.class.getName());

    private static final String CURRENT_FORMAT =
            "https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/";

    private CopyrightHeader header;
    private List<Group> groups = new ArrayList<>();
    private List<StoredParagraph> licenseParagraphs = new ArrayList<>();

    public Copyright() {
        this.header = new CopyrightHeader();
        this.header.setFormat(CURRENT_FORMAT);
    }

    public CopyrightHeader getHeader() { return header; }
    public List<Group> getGroups() { return groups; }

    public static Copyright build(RootInfo filetree, Options options) {
        String filename = options.copyrightFile;
        if (!new File(filename).isAbsolute()) {
            filename = filetree.getRoot() + "/" + filename;
        }

        if (!new File(filename).exists()) {
            return new Copyright();
        }

        try {
            String content = Files.readString(new File(filename).toPath(), StandardCharsets.UTF_8);
            return parse(content);
        } catch (IOException e) {
            LOG.warning("Could not read copyright file: " + e.getMessage());
            return new Copyright();
        }
    }

    /**
     * Minimal DEP-5 parser - parses paragraphs separated by blank lines.
     */
    public static Copyright parse(String content) {
        Copyright result = new Copyright();
        List<Map<String, String>> paragraphs = parseParagraphs(content);

        if (paragraphs.isEmpty()) return result;

        // First paragraph is the header
        Map<String, String> headerFields = paragraphs.get(0);
        CopyrightHeader hdr = new CopyrightHeader();
        hdr.setFormat(headerFields.getOrDefault("Format", CURRENT_FORMAT));
        hdr.setSource(headerFields.getOrDefault("Source", ""));
        hdr.setComment(headerFields.getOrDefault("Comment", ""));
        for (Map.Entry<String, String> e : headerFields.entrySet()) {
            String k = e.getKey();
            if (!k.equals("Format") && !k.equals("Source") && !k.equals("Comment")) {
                hdr.getExtraFields().put(k, e.getValue());
            }
        }
        result.header = hdr;

        // Remaining paragraphs: Files or License paragraphs
        for (int i = 1; i < paragraphs.size(); i++) {
            Map<String, String> p = paragraphs.get(i);
            if (p.containsKey("Files")) {
                result.groups.add(parseFilesGroup(p, i - 1));
            } else if (p.containsKey("License")) {
                // A standalone license paragraph - set the stored text
                String licName = p.get("License").split("\n")[0].trim();
                String fullText = p.get("License");
                License lic = License.get(licName);
                if (!lic.hasStoredText()) {
                    lic.setStoredText("License: " + fullText);
                }
                result.licenseParagraphs.add(new StoredParagraph(p));
            }
        }

        return result;
    }

    private static Group parseFilesGroup(Map<String, String> fields, int position) {
        Group group = new Group(position);
        StoredParagraph stored = new StoredParagraph(fields);
        group.setStored(stored);

        String filesField = fields.getOrDefault("Files", "");
        List<String> filePatterns = new ArrayList<>();
        for (String line : filesField.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) filePatterns.add(trimmed);
        }
        // Store file patterns as a list in the stored paragraph (already in fields)

        // Parse copyright holders
        String copyrightField = fields.getOrDefault("Copyright", "");
        if (!copyrightField.isEmpty()) {
            for (String line : copyrightField.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    List<CopyrightHolder> holders = CopyrightParser.getCopyrightHolders(trimmed);
                    group.addCopyrights(holders);
                }
            }
        }

        // Parse licenses
        String licenseField = fields.getOrDefault("License", "");
        String licSynopsis = licenseField.split("\n")[0].trim();
        for (String key : licSynopsis.split("(?i)\\s+or\\s+")) {
            String k = key.trim();
            if (!k.isEmpty()) {
                group.getLicenses().put(k, License.get(k));
            }
        }

        // Parse comment
        String comment = fields.getOrDefault("Comment", "");
        if (!comment.isEmpty()) {
            group.getCommentsList().add(comment);
        }

        return group;
    }

    /**
     * Match files in the filetree to stored copyright paragraphs.
     * Mirrors the Python process() method.
     */
    public void process(RootInfo filetree) {
        // Collect all file names
        List<String> allNames = new ArrayList<>();
        for (String n : filetree.getNames()) {
            allNames.add(n);
        }

        // Process in reverse so later paragraphs (lower index after reverse) win
        List<Group> validGroups = new ArrayList<>();
        List<Map.Entry<Group, Set<String>>> filesGroups = new ArrayList<>();

        List<Group> reversed = new ArrayList<>(groups);
        Collections.reverse(reversed);

        for (Group group : reversed) {
            List<String> matchedPatterns = new ArrayList<>();
            Set<String> matchedFiles = new LinkedHashSet<>();

            StoredParagraph stored = group.getStored();
            if (stored == null) continue;

            String filesField = stored.get("Files");
            List<String> patterns = new ArrayList<>();
            for (String line : filesField.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty()) patterns.add(t);
            }

            for (String pattern : patterns) {
                boolean found = false;
                for (String name : allNames) {
                    try {
                        FileInfo fi = filetree.getByPath(name);
                        if (fi.getMatchingPattern() != null) continue;
                        if (matchesGlob(pattern, name)) {
                            fi.setMatchingPattern(pattern);
                            if (!(fi instanceof DirInfo)) {
                                matchedFiles.add(name);
                            }
                            if (!found) {
                                matchedPatterns.add(pattern);
                                found = true;
                            }
                        }
                    } catch (NoSuchElementException e) {
                        // skip
                    }
                }
                if (!found) {
                    LOG.info("No match found for " + pattern);
                }
            }

            if (matchedPatterns.isEmpty()) {
                LOG.info("No matching pattern in group at position " + group.getPosition());
                continue;
            }

            filesGroups.add(Map.entry(group, matchedFiles));
        }

        processGroups(filetree, filesGroups);
    }

    private void processGroups(RootInfo filetree,
                                List<Map.Entry<Group, Set<String>>> filesGroups) {
        List<Group> newGroups = new ArrayList<>();

        for (Map.Entry<Group, Set<String>> entry : filesGroups) {
            Group group = entry.getKey();
            Set<String> files = entry.getValue();

            for (String filename : files) {
                try {
                    FileInfo fi = filetree.getByPath(filename);
                    fi.setStoredGroup(group);
                    group.addFile(fi);
                } catch (NoSuchElementException e) {
                    // skip
                }
            }
            newGroups.add(group);
        }
        this.groups = newGroups;
    }

    public void removeMisplacedFiles(Options options) {
        for (Group group : groups) {
            FileInfo.GroupKey groupKey = group.getKey(options);
            FileInfo.GroupKey groupKeyNoCopyright = group.getKey(options, true);

            List<FileInfo> toRemove = new ArrayList<>();
            for (FileInfo fi : group.getFiles().values()) {
                if (options.mode.equals("partial") && !fi.isIncluded()) {
                    toRemove.add(fi);
                    continue;
                }
                FileInfo.GroupKey fileKey = fi.getGroupKey(options);
                boolean remove = !fileKey.equals(groupKey);
                if (remove && options.groupBy.equals("copyright")) {
                    remove = !fileKey.equals(groupKeyNoCopyright);
                }
                if (remove) {
                    toRemove.add(fi);
                } else {
                    group.getCopyrights().merge(fi.getCopyrights());
                    fi.setGroup(group);
                }
            }
            for (FileInfo fi : toRemove) {
                group.getFiles().removeFile(fi);
            }
        }
    }

    public Map<FileInfo.GroupKey, Group> getGroupDict(Options options) {
        Map<FileInfo.GroupKey, Group> result = new LinkedHashMap<>();
        for (Group group : groups) {
            FileInfo.GroupKey key = group.getKey(options);
            if (result.containsKey(key)) {
                Group existing = result.get(key);
                for (FileInfo fi : group.getFiles().values()) {
                    existing.addFile(fi);
                }
            } else {
                result.put(key, group);
            }
        }
        return result;
    }

    /** Simple glob matching: * matches any sequence except /, ** matches across /. */
    static boolean matchesGlob(String pattern, String path) {
        // Convert glob pattern to regex
        StringBuilder sb = new StringBuilder("^");
        int len = pattern.length();
        for (int i = 0; i < len; i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < len && pattern.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i++; // skip next *
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else if (c == '.') {
                sb.append("\\.");
            } else if ("[{(+^$|".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString()).matcher(path).matches();
    }

    /**
     * Parse DEP-5 formatted content into a list of field maps.
     * Each paragraph is separated by a blank line; fields may be multi-line
     * (continuation lines start with whitespace).
     */
    static List<Map<String, String>> parseParagraphs(String content) {
        List<Map<String, String>> paragraphs = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();
        String lastKey = null;

        for (String line : content.split("\n", -1)) {
            if (line.isEmpty() || line.equals("\r")) {
                // Blank line: end of paragraph
                if (!current.isEmpty()) {
                    paragraphs.add(current);
                    current = new LinkedHashMap<>();
                    lastKey = null;
                }
            } else if (line.startsWith(" ") || line.startsWith("\t")) {
                // Continuation line
                if (lastKey != null) {
                    String existing = current.get(lastKey);
                    // A line with just a "." means blank line within the value
                    String cont = line.trim().equals(".") ? "" : line.stripLeading();
                    current.put(lastKey, existing + "\n" + cont);
                }
            } else if (line.startsWith("#")) {
                // Comment line - ignore
            } else {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    current.put(key, value);
                    lastKey = key;
                }
            }
        }
        if (!current.isEmpty()) paragraphs.add(current);
        return paragraphs;
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    public String getHeaderDump(Options options) {
        header.setFormat(CURRENT_FORMAT);
        if (header.getSource().isEmpty()) header.setSource("TODO");
        return header.dump().stripTrailing();
    }
}
