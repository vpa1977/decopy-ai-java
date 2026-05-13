package org.debian.decopy.dep5;

import org.debian.decopy.Options;
import org.debian.decopy.datatypes.CopyrightHolder;
import org.debian.decopy.datatypes.License;
import org.debian.decopy.tree.CopyrightGroup;
import org.debian.decopy.tree.DirInfo;
import org.debian.decopy.tree.FileGroup;
import org.debian.decopy.tree.FileInfo;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A copyright group (Files paragraph in dep5).
 */
public final class Group {

    private static final Logger LOG = Logger.getLogger(Group.class.getName());

    public static final String UNKNOWN = "Unknown";
    public static final String UNKNOWN_COPYRIGHTED = "UnknownCopyrighted";

    private FileInfo.GroupKey key;
    private final FileGroup files = new FileGroup();
    private final CopyrightGroup copyrights = new CopyrightGroup();
    private final Map<String, License> licenses = new LinkedHashMap<>();
    private final Set<String> licenseFilenames = new LinkedHashSet<>();
    private final List<String> comments = new ArrayList<>();
    private StoredParagraph stored;
    private final int position;

    public Group(int position) {
        this.position = position;
    }

    public Group(FileInfo.GroupKey key) {
        this.key = key;
        this.position = 0;
    }

    public String getLicenseKey() {
        if (licenses.isEmpty()) {
            return copyrights.isEmpty() ? UNKNOWN : UNKNOWN_COPYRIGHTED;
        }
        List<String> sorted = new ArrayList<>(licenses.keySet());
        Collections.sort(sorted);
        return String.join(" or ", sorted);
    }

    public String getLicense() {
        return getLicenseKey();
    }

    @Override
    public String toString() {
        return key != null ? key.licenseKey() : getLicense();
    }

    public void addFile(FileInfo fi) {
        files.addFile(fi);
        copyrights.merge(fi.getCopyrights());
        for (String lic : fi.getLicenses()) {
            licenses.put(lic, License.get(lic));
        }
        licenseFilenames.addAll(fi.getLicenseFilenames());
    }

    public void addCopyrights(List<CopyrightHolder> holders) {
        copyrights.extend(holders);
    }

    public String getInheritedComment() {
        if (licenseFilenames.isEmpty()) return null;
        if (stored != null) return null;
        List<String> sorted = new ArrayList<>(licenseFilenames);
        Collections.sort(sorted);
        return "No explicit license found, using license(s) from:\n " +
                String.join("\n ", sorted);
    }

    public String getComments() {
        List<String> all = new ArrayList<>();
        String inherited = getInheritedComment();
        if (inherited != null) all.add(inherited);
        all.addAll(comments);
        return String.join("\n .\n ", all);
    }

    public Object getCopyrightKey() {
        return copyrights.key();
    }

    public String getPathKey(Options options) {
        return files.key(options);
    }

    public FileInfo.GroupKey getKey(Options options) {
        return getKey(options, false);
    }

    public FileInfo.GroupKey getKey(Options options, boolean ignoreCopyright) {
        if (key == null) {
            String licenseKey = getLicenseKey();
            Object copyrightKey = options.groupBy.equals("copyright") ? getCopyrightKey() : null;
            String pathKey = getPathKey(options);
            key = new FileInfo.GroupKey(licenseKey, copyrightKey, pathKey);
        }
        if (ignoreCopyright) {
            return new FileInfo.GroupKey(key.licenseKey(), null, key.pathKey());
        }
        return key;
    }

    public boolean copyrightBlockValid() {
        if (files.isEmpty()) return false;
        if (files.size() > 1) return true;
        // single file: fullname must not be empty
        for (FileInfo fi : files.values()) {
            return !fi.getFullname().isEmpty();
        }
        return false;
    }

    public String copyrightBlock(boolean glob) {
        if (!copyrightBlockValid()) return "";

        List<String> filesList;
        if (glob) {
            filesList = files.getPatterns();
        } else {
            filesList = files.sortedMembers();
        }

        List<String> block = new ArrayList<>();
        block.add("Files: " + String.join("\n       ", filesList));

        if (!copyrights.isEmpty()) {
            String holders = String.join("\n           ", copyrights.sortedMembers());
            block.add("Copyright: " + holders);
        }
        block.add("License: " + getLicense());

        String comments = getComments();
        if (!comments.isEmpty()) {
            block.add("Comment: " + comments);
        }

        // Extra stored fields
        if (stored != null) {
            for (Map.Entry<String, String> entry : stored.extraFields().entrySet()) {
                block.add(entry.getKey() + ": " + entry.getValue());
            }
        }

        return String.join("\n", block);
    }

    public int sortKey0(Options options) {
        int debianGroup = 0;
        if (options.splitDebian) {
            String pathKey = getPathKey(options);
            if ("debian".equals(pathKey)) debianGroup = 2;
        }
        String lic = getLicense();
        if (UNKNOWN.equals(lic) || UNKNOWN_COPYRIGHTED.equals(lic)) debianGroup = 1;
        return debianGroup;
    }

    public FileGroup getFiles() { return files; }
    public CopyrightGroup getCopyrights() { return copyrights; }
    public Map<String, License> getLicenses() { return licenses; }
    public List<String> getCommentsList() { return comments; }
    public StoredParagraph getStored() { return stored; }
    public void setStored(StoredParagraph s) { this.stored = s; }
    public int getPosition() { return position; }
    public void setKey(FileInfo.GroupKey k) { this.key = k; }
}
