package org.debian.decopy;

import org.debian.decopy.datatypes.License;
import org.debian.decopy.dep5.Copyright;
import org.debian.decopy.dep5.Group;
import org.debian.decopy.tree.FileInfo;
import org.debian.decopy.tree.RootInfo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Generates the output dep5 copyright file.
 */
public final class Output {

    private static final Logger LOG = Logger.getLogger(Output.class.getName());

    private static final String CURRENT_FORMAT =
            "https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/";

    private Output() {}

    public static void generateOutput(Map<FileInfo.GroupKey, Group> groups,
                                       RootInfo filetree,
                                       Copyright copyright_,
                                       Options options) throws IOException {

        try (PrintWriter out = openOutput(options.output)) {
            generateHeader(copyright_, out, options);

            // Partial mode: tally parent dirs for the specified files
            if (options.mode.equals("partial")) {
                for (String item : options.files) {
                    try {
                        FileInfo fi = filetree.getByPath(item);
                        if (fi.getParent() != null) {
                            fi.getParent().tally();
                        }
                    } catch (NoSuchElementException e) {
                        // ignore
                    }
                }
            }

            // Sort groups
            List<Map.Entry<FileInfo.GroupKey, Group>> sortedGroups = new ArrayList<>(groups.entrySet());
            sortedGroups.sort((a, b) -> compareGroups(a.getValue(), b.getValue(), options));

            Set<String> usedLicenses = new LinkedHashSet<>();

            for (Map.Entry<FileInfo.GroupKey, Group> entry : sortedGroups) {
                Group group = entry.getValue();
                if (!group.copyrightBlockValid()) continue;

                usedLicenses.addAll(group.getLicenses().keySet());

                String paragraph = group.copyrightBlock(options.glob);
                out.println();
                out.println(paragraph);

                if (!options.output.isEmpty()) {
                    LOG.fine("Generated group:\n" + paragraph);
                }
            }

            // License paragraphs
            List<String> licSorted = new ArrayList<>(usedLicenses);
            Collections.sort(licSorted);
            for (String key : licSorted) {
                License lic = License.get(key);
                String paragraph = lic.toString();
                out.println();
                out.println(paragraph);

                if (!options.output.isEmpty()) {
                    LOG.fine("Generated license block:\n" + paragraph);
                }
            }
        }
    }

    private static void generateHeader(Copyright copyright_, PrintWriter out, Options options) {
        String paragraph;
        if (options.mode.equals("partial")) {
            paragraph = "Format: " + CURRENT_FORMAT + "\n" +
                        "Source: TODO\n" +
                        "Comment: *** only: " + String.join(", ", options.files) + " ***";
        } else if (copyright_ != null && !copyright_.isEmpty()) {
            copyright_.getHeader().setFormat(CURRENT_FORMAT);
            if (copyright_.getHeader().getSource().isEmpty()) {
                copyright_.getHeader().setSource("TODO");
            }
            paragraph = copyright_.getHeader().dump().stripTrailing();
        } else {
            paragraph = "Format: " + CURRENT_FORMAT + "\nSource: TODO";
        }
        out.println(paragraph);
        if (!options.output.isEmpty()) {
            LOG.fine("Generated header:\n" + paragraph);
        }
    }

    private static PrintWriter openOutput(String filename) throws IOException {
        if (filename.isEmpty()) {
            return new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        }
        return new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(filename), StandardCharsets.UTF_8), true);
    }

    private static int compareGroups(Group a, Group b, Options options) {
        int s0a = a.sortKey0(options);
        int s0b = b.sortKey0(options);
        if (s0a != s0b) return Integer.compare(s0a, s0b);

        // Then by -position (higher position = earlier in output)
        int cmp = Integer.compare(b.getPosition(), a.getPosition());
        if (cmp != 0) return cmp;

        // Then by -file count
        cmp = Integer.compare(b.getFiles().size(), a.getFiles().size());
        if (cmp != 0) return cmp;

        // Then by -copyright count
        cmp = Integer.compare(b.getCopyrights().size(), a.getCopyrights().size());
        if (cmp != 0) return cmp;

        // Then by key
        FileInfo.GroupKey ka = a.getKey(options, true);
        FileInfo.GroupKey kb = b.getKey(options, true);
        if (ka.licenseKey() != null && kb.licenseKey() != null) {
            return ka.licenseKey().compareTo(kb.licenseKey());
        }
        return 0;
    }
}
