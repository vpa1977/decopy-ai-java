package org.debian.decopy.tree;

import org.debian.decopy.Options;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A collection of files for one copyright group.
 */
public final class FileGroup {

    private final Map<String, FileInfo> files = new LinkedHashMap<>();

    public int size() { return files.size(); }
    public boolean isEmpty() { return files.isEmpty(); }

    public Iterable<FileInfo> values() { return files.values(); }

    public void addFile(FileInfo fi) {
        files.put(fi.getFullname(), fi);
    }

    public FileInfo removeFile(FileInfo fi) {
        return files.remove(fi.getFullname());
    }

    public List<String> getPatterns() {
        Set<String> patterns = new LinkedHashSet<>();
        Set<FileInfo> toTally = new LinkedHashSet<>();

        for (FileInfo fi : files.values()) {
            FileInfo untallied = fi.getFirstUntallied();
            toTally.add(untallied);
        }

        for (FileInfo fi : toTally) {
            if (fi instanceof DirInfo dir) {
                String fn = dir.getFullname();
                patterns.add(fn.isEmpty() ? "*" : fn + "/*");
            } else {
                patterns.add(fi.getFullname());
            }
        }
        for (FileInfo fi : files.values()) {
            fi.tally();
        }

        List<String> result = new ArrayList<>(patterns);
        Collections.sort(result);
        return result;
    }

    public String key(Options options) {
        if (files.isEmpty()) return "";
        List<String> paths = files.values().stream()
                .map(f -> f.getSplittingPath(options))
                .collect(Collectors.toList());
        return commonPath(paths);
    }

    public String commonpath() {
        if (files.isEmpty()) return "";
        List<String> names = new ArrayList<>(files.keySet());
        return commonPath(names);
    }

    public List<String> sortedMembers() {
        List<String> result = new ArrayList<>(files.keySet());
        Collections.sort(result);
        return result;
    }

    static String commonPath(List<String> paths) {
        if (paths.isEmpty()) return "";
        if (paths.size() == 1) return paths.get(0);

        // Split by path separator and find common prefix components
        List<String[]> split = paths.stream()
                .map(p -> p.isEmpty() ? new String[]{""} : p.split("/"))
                .collect(Collectors.toList());

        String[] first = split.get(0);
        int commonLen = first.length;

        for (String[] parts : split) {
            int len = Math.min(commonLen, parts.length);
            int i = 0;
            while (i < len && first[i].equals(parts[i])) i++;
            commonLen = i;
        }

        if (commonLen == 0) return "";
        return String.join("/", Arrays.copyOf(first, commonLen));
    }
}
