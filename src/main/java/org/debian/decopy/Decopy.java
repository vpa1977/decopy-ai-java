package org.debian.decopy;

import org.debian.decopy.dep5.Copyright;
import org.debian.decopy.dep5.Group;
import org.debian.decopy.tree.DirInfo;
import org.debian.decopy.tree.FileInfo;
import org.debian.decopy.tree.RootInfo;

import java.util.*;
import java.util.logging.Logger;

/**
 * Main orchestration class – mirrors decopy.py.
 */
public final class Decopy {

    private static final Logger LOG = Logger.getLogger(Decopy.class.getName());

    private Decopy() {}

    public static Map<FileInfo.GroupKey, Group> prepareOutputGroups(
            RootInfo filetree,
            Copyright copyright_,
            Options options) {

        copyright_.removeMisplacedFiles(options);
        Map<FileInfo.GroupKey, Group> groups = copyright_.getGroupDict(options);

        for (FileInfo fileinfo : filetree.walk()) {
            if (options.mode.equals("partial") && !fileinfo.isIncluded()) continue;

            // Already assigned to a matching group
            if (fileinfo.getGroup() != null) continue;
            if (fileinfo instanceof DirInfo) continue;

            FileInfo.GroupKey fileKey = fileinfo.getGroupKey(options);
            groups.computeIfAbsent(fileKey, k -> new Group(k));
            Group group = groups.get(fileKey);
            group.addFile(fileinfo);
            fileinfo.setGroup(group);
        }

        return groups;
    }

    public static int run(Options options) {
        RootInfo filetree = RootInfo.build(options);
        Copyright copyright_ = Copyright.build(filetree, options);

        copyright_.process(filetree);
        filetree.process(options);

        Map<FileInfo.GroupKey, Group> groups = prepareOutputGroups(filetree, copyright_, options);

        try {
            Output.generateOutput(groups, filetree, copyright_, options);
        } catch (Exception e) {
            LOG.severe("Error generating output: " + e.getMessage());
            return 1;
        }

        return 0;
    }
}
