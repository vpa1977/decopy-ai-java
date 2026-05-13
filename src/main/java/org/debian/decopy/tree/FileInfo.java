package org.debian.decopy.tree;

import org.debian.decopy.Options;
import org.debian.decopy.datatypes.CopyrightHolder;
import org.debian.decopy.dep5.Group;

import java.util.*;
import java.util.logging.Logger;

/**
 * Information about a single file in the source tree.
 */
public class FileInfo {

    private static final Logger LOG = Logger.getLogger(FileInfo.class.getName());

    protected DirInfo parent;
    protected String name;

    protected Set<String> licenses = new LinkedHashSet<>();
    protected Set<String> licenseFilenames = new LinkedHashSet<>();

    protected Group group;
    protected Group storedGroup;
    protected String matchingPattern;

    protected CopyrightGroup copyrights = new CopyrightGroup();

    protected boolean tallied = false;
    protected Boolean included = null;

    public FileInfo(DirInfo parent, String name) {
        this.parent = parent;
        this.name = name;
    }

    public DirInfo getParent() { return parent; }
    public String getName() { return name; }

    public String getFullname() {
        if (parent != null) {
            String pf = parent.getFullname();
            return pf.isEmpty() ? name : pf + "/" + name;
        }
        return name;
    }

    public String getParsedLicense() {
        if (licenses.isEmpty()) return "";
        List<String> sorted = new ArrayList<>(licenses);
        Collections.sort(sorted);
        return String.join(" or ", sorted);
    }

    public void addCopyrights(List<CopyrightHolder> holders) {
        copyrights.extend(holders);
    }

    public void addLicenses(Set<String> lics, Set<String> filenames) {
        licenses.addAll(lics);
        if (filenames != null) licenseFilenames.addAll(filenames);
    }

    public Set<String> getLicenses() {
        if (!licenses.isEmpty() || parent == null) return licenses;
        return parent.getLicenses();
    }

    public Set<String> getLicenseFilenames() {
        if (!licenses.isEmpty() || parent == null) return licenseFilenames;
        return parent.getLicenseFilenames();
    }

    public String getLicenseKey() {
        if (storedGroup != null && getFullname().equals(matchingPattern)) {
            return storedGroup.getLicenseKey();
        }
        String parsed = getParsedLicense();
        if (!parsed.isEmpty()) return parsed;
        if (storedGroup != null) return storedGroup.getLicenseKey();
        if (parent != null) return parent.getLicenseKey();
        if (!copyrights.isEmpty()) return "UnknownCopyrighted";
        return "Unknown";
    }

    public Object getCopyrightKey() {
        return copyrights.key();
    }

    public String getSplittingPath(Options options) {
        if (parent != null) return parent.getSplittingPath(options);
        return null;
    }

    public GroupKey getGroupKey(Options options) {
        String licenseKey = getLicenseKey();
        Object copyrightKey = options.groupBy.equals("copyright") ? getCopyrightKey() : null;
        String pathKey = getSplittingPath(options);
        return new GroupKey(licenseKey, copyrightKey, pathKey);
    }

    public FileInfo getFirstUntallied() {
        assert !tallied;
        if (parent != null && !parent.tallied) {
            return parent.getFirstUntallied();
        }
        return this;
    }

    public void tally() {
        if (tallied) return;
        tallied = true;
        if (parent != null) parent.tally();
    }

    public boolean isIncluded() {
        if (included != null) return included;
        if (parent != null) {
            included = parent.isIncluded();
            return included;
        }
        return false;
    }

    public void setIncluded(boolean v) { included = v; }

    public Group getGroup() { return group; }
    public void setGroup(Group g) { group = g; }

    public Group getStoredGroup() { return storedGroup; }
    public void setStoredGroup(Group g) { storedGroup = g; }

    public String getMatchingPattern() { return matchingPattern; }
    public void setMatchingPattern(String p) { matchingPattern = p; }

    public CopyrightGroup getCopyrights() { return copyrights; }

    public boolean isTallied() { return tallied; }

    @Override
    public String toString() { return getFullname(); }

    public record GroupKey(String licenseKey, Object copyrightKey, String pathKey) {
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof GroupKey other)) return false;
            return Objects.equals(licenseKey, other.licenseKey) &&
                   Objects.equals(copyrightKey, other.copyrightKey) &&
                   Objects.equals(pathKey, other.pathKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(licenseKey, copyrightKey, pathKey);
        }
    }
}
