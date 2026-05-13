package org.debian.decopy.tree;

import org.debian.decopy.Options;

import java.util.*;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Information about a directory in the source tree.
 */
public class DirInfo extends FileInfo {

    private static final Logger LOG = Logger.getLogger(DirInfo.class.getName());

    protected final Map<String, FileInfo> fileList = new LinkedHashMap<>();
    protected int total = 0;
    protected Set<Future<FileInfo>> tasks = new HashSet<>();

    public DirInfo(DirInfo parent, String name) {
        super(parent, name);
    }

    /** Constructor for root (no parent) */
    public DirInfo() {
        super(null, "");
    }

    @Override
    public String getParsedLicense() {
        if (!licenses.isEmpty()) {
            List<String> sorted = new ArrayList<>(licenses);
            Collections.sort(sorted);
            return "Inherited(" + String.join(" or ", sorted) + ")";
        }
        return "";
    }

    public void add(List<String> path, List<String> dirs, List<String> files) {
        total += dirs.size() + files.size();

        if (!path.isEmpty()) {
            String name = path.get(0);
            fileList.computeIfAbsent(name, n -> new DirInfo(this, n));
            DirInfo subDir = (DirInfo) fileList.get(name);
            subDir.add(path.subList(1, path.size()), dirs, files);
            return;
        }

        for (String d : dirs) {
            fileList.computeIfAbsent(d, n -> new DirInfo(this, n));
        }
        for (String f : files) {
            fileList.computeIfAbsent(f, n -> new FileInfo(this, n));
        }
    }

    @Override
    public String getSplittingPath(Options options) {
        if ((options.splitOnLicense && !licenses.isEmpty()) || parent == null) {
            return getFullname();
        }
        if (options.splitDebian && "debian".equals(getFullname())) {
            return getFullname();
        }
        if (parent != null) return parent.getSplittingPath(options);
        return getFullname();
    }

    public String getLicenseKey() {
        // DirInfo doesn't have its own license key in the same sense
        if (!licenses.isEmpty()) {
            List<String> sorted = new ArrayList<>(licenses);
            Collections.sort(sorted);
            return String.join(" or ", sorted);
        }
        if (parent != null) return parent.getLicenseKey();
        if (!copyrights.isEmpty()) return "UnknownCopyrighted";
        return "Unknown";
    }

    public Map<String, FileInfo> getFileList() { return fileList; }

    public int getTotal() { return total; }

    public Set<Future<FileInfo>> getTasks() { return tasks; }
    public void addTask(Future<FileInfo> task) { tasks.add(task); }
    public void clearTasks() { tasks.clear(); }

    /** Depth-first iterator over this directory and all children */
    public Iterable<FileInfo> walk() {
        List<FileInfo> result = new ArrayList<>();
        collectAll(result);
        return result;
    }

    protected void collectAll(List<FileInfo> result) {
        result.add(this);
        for (FileInfo fi : fileList.values()) {
            if (fi instanceof DirInfo dir) {
                dir.collectAll(result);
            } else {
                result.add(fi);
            }
        }
    }

    public void processLicensesFromTasks() {
        Set<String> dirLicenses = new LinkedHashSet<>();
        Set<String> filenames = new LinkedHashSet<>();

        for (Future<FileInfo> task : tasks) {
            try {
                FileInfo fi = task.get();
                if (fi == null) continue;
                if (fi.licenses.isEmpty()) continue;
                dirLicenses.addAll(fi.licenses);
                filenames.add(fi.getFullname());
            } catch (Exception e) {
                LOG.warning("Error processing task: " + e.getMessage());
            }
        }
        tasks.clear();

        if (!dirLicenses.isEmpty()) {
            addLicenses(dirLicenses, filenames);
        }
    }
}
