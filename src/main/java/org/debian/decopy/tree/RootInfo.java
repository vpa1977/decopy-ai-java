package org.debian.decopy.tree;

import org.debian.decopy.Options;
import org.debian.decopy.parsers.FileParser;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The root of the file tree, providing tree-walking and processing.
 */
public final class RootInfo extends DirInfo {

    private static final Logger LOG = Logger.getLogger(RootInfo.class.getName());

    private final String root;

    private static final List<Pattern> KNOWN_LICENSE_FILENAMES = List.of(
            Pattern.compile("^COPYING", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^COPYRIGHT$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^LICENSE", Pattern.CASE_INSENSITIVE)
    );

    public RootInfo(String root) {
        super();
        this.root = root;
    }

    public String getRoot() { return root; }

    public Iterable<String> getNames() {
        List<String> names = new ArrayList<>();
        for (FileInfo fi : walk()) {
            names.add(fi.toString());
        }
        return names;
    }

    public FileInfo getByPath(String key) {
        if (key == null || key.isEmpty() || ".".equals(key)) return this;
        String[] parts = key.split("/");
        DirInfo current = this;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            FileInfo fi = current.fileList.get(part);
            if (fi == null) throw new NoSuchElementException(key + " not found");
            if (fi instanceof DirInfo dir) {
                current = dir;
            } else {
                // last component is a file
                return fi;
            }
        }
        return current;
    }

    public static RootInfo build(Options options) {
        RootInfo tree = new RootInfo(options.root);
        Path rootPath = Path.of(options.root);

        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {

                // Track which dirs to skip
                private final Set<Path> skipDirs = new HashSet<>();

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (!dirName.isEmpty() &&
                            options.excludeDirectoryRe.matcher(dirName).find()) {
                        LOG.fine("Ignoring directory " + dirName);
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    // Collect children lazily - we handle it in postVisitDirectory
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();

                    if (options.excludeFileRe.matcher(fileName).find()) {
                        LOG.fine("Ignoring file " + fileName);
                        return FileVisitResult.CONTINUE;
                    }
                    if (attrs.isSymbolicLink()) {
                        LOG.fine("Ignoring symlink " + fileName);
                        return FileVisitResult.CONTINUE;
                    }
                    // We'll add files below via the directory scan approach
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warning("Error walking tree: " + e.getMessage());
        }

        // Use os.walk equivalent - iterate directories top-down
        buildTree(tree, rootPath, options);

        tree.tagIncludedFiles(options);
        return tree;
    }

    private static void buildTree(RootInfo tree, Path rootPath, Options options) {
        Deque<Path> queue = new ArrayDeque<>();
        queue.add(rootPath);

        while (!queue.isEmpty()) {
            Path dir = queue.poll();
            List<String> subDirs = new ArrayList<>();
            List<String> files = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();
                    BasicFileAttributes attrs;
                    try {
                        attrs = Files.readAttributes(entry, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                    } catch (IOException e) {
                        continue;
                    }

                    if (attrs.isDirectory()) {
                        if (options.excludeDirectoryRe.matcher(name).find()) {
                            LOG.fine("Ignoring directory " + name);
                        } else {
                            subDirs.add(name);
                            queue.add(entry);
                        }
                    } else if (attrs.isSymbolicLink()) {
                        LOG.fine("Ignoring symlink " + name);
                    } else if (attrs.isRegularFile()) {
                        if (options.excludeFileRe.matcher(name).find()) {
                            LOG.fine("Ignoring file " + name);
                        } else {
                            files.add(name);
                        }
                    }
                }
            } catch (IOException e) {
                LOG.warning("Cannot read directory " + dir + ": " + e.getMessage());
                continue;
            }

            // Compute relative path from root
            Path relDir = rootPath.relativize(dir);
            List<String> relPath = new ArrayList<>();
            for (int i = 0; i < relDir.getNameCount(); i++) {
                String part = relDir.getName(i).toString();
                if (!part.isEmpty()) relPath.add(part);
            }

            tree.add(relPath, subDirs, files);
        }
    }

    public void tagIncludedFiles(Options options) {
        if (options.files.isEmpty()) {
            setIncluded(true);
            return;
        }

        for (String filename : options.files) {
            if (".".equals(filename)) {
                setIncluded(true);
                return;
            }
            try {
                getByPath(filename).setIncluded(true);
            } catch (NoSuchElementException e) {
                LOG.severe(filename + " not in source tree");
            }
        }
    }

    public void process(Options options) {
        int workers = options.jobs > 0 ? options.jobs :
                8;

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        Set<Future<FileInfo>> tasks = new HashSet<>();
        Set<DirInfo> dirsWithLicenses = new HashSet<>();

        int processed = 0;
        int total = this.total;

        try {
            for (FileInfo item : walk()) {
                if (item instanceof DirInfo) continue;
                if (!item.isIncluded()) continue;

                String root = this.root;
                Options opts = options;
                Future<FileInfo> task = executor.submit(() -> {
                    processFileLicenses(item, root, opts);
                    return item;
                });

                boolean isLicenseFile = false;
                for (Pattern p : KNOWN_LICENSE_FILENAMES) {
                    if (p.matcher(item.getName()).find()) {
                        isLicenseFile = true;
                        break;
                    }
                }

                if (isLicenseFile && item.getParent() != null) {
                    item.getParent().addTask(task);
                    dirsWithLicenses.add(item.getParent());
                } else {
                    tasks.add(task);
                }

                processed++;
                if (options.progress && total > 0 && processed % 100 == 0) {
                    printProgress("Processing", processed, total);
                    for (Future<FileInfo> t : tasks) {
                        try {
                            t.get();
                        } catch (ExecutionException e) {
                            LOG.warning("Error processing file: " + e.getCause().getMessage());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    tasks.clear();
                }
            }

            // Process directories with license files
            total = dirsWithLicenses.size();
            processed = 0;
            for (DirInfo dirInfo : dirsWithLicenses) {
                Future<FileInfo> task = executor.submit(() -> {
                    dirInfo.processLicensesFromTasks();
                    return dirInfo;
                });
                tasks.add(task);
                processed++;
                if (options.progress && processed % 100 == 0) {
                    printProgress("Processing dir", processed, total);
                    for (Future<FileInfo> t : tasks) {
                        try {
                            t.get();
                        } catch (ExecutionException e) {
                            LOG.warning("Error processing file: " + e.getCause().getMessage());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    tasks.clear();
                }
            }


        } finally {
            executor.shutdown();
        }
    }

    private static void processFileLicenses(FileInfo item, String root, Options options) {
        String fullname = item.getFullname();
        if (options.excludeFullnameRe.matcher(fullname).find() ||
                options.excludeSpecialRe.matcher(fullname).find()) {
            LOG.fine("Ignored file " + item.getName() + " (by --exclude)");
            return;
        }

        String fullPath = root + "/" + fullname;
        try {
            var result = FileParser.parseFile(fullPath, options);
            LOG.fine("Adding copyrights " + result.copyrights() + " to " + item);
            item.addCopyrights(result.copyrights());
            LOG.fine("Adding licenses " + result.licenses() + " to " + item);
            item.addLicenses(new LinkedHashSet<>(result.licenses()), null);
        } catch (Exception e) {
            LOG.warning("Error parsing " + fullPath + ": " + e.getMessage());
        }
    }

    private static void printProgress(String label, int done, int total) {
        int pct = (int) (100.0 * done / total);
        System.err.printf("\r%s: %d/%d (%d%%)  ", label, done, total, pct);
        if (done >= total) System.err.println();
    }
}
