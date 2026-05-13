package org.debian.decopy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Command-line options for decopy.
 */
public class Options {

    private static final Logger LOG = Logger.getLogger(Options.class.getName());

    // --- defaults ---
    public static final String DEFAULT_EXCLUDE_FILE_REGEX =
            "~$" +
            "|\\A\\.#.*$" +
            "|\\..*\\.swp$" +
            "|\\A,," +
            "|\\A(?:DEADJOE|\\.cvsignore|\\.arch-inventory|\\.bzrignore|\\.gitignore)$" +
            "|\\A(?:CVS|RCS|\\.deps|\\{arch\\}|\\.arch-ids|\\.svn|\\.hg|_darcs|\\.git|" +
            "\\.shelf|_MTN|\\.bzr(?:\\.backup|tags)?)$";

    public static final String DEFAULT_EXCLUDE_DIRECTORY_REGEX =
            "\\A,," +
            "|\\A(?:CVS|RCS|\\.deps|\\{arch\\}|\\.arch-ids|\\.svn|\\.hg|_darcs|\\.git|" +
            "\\.pc|\\.shelf|_MTN|\\.bzr(?:\\.backup|tags)?)$" +
            "|\\A__pycache__$";

    public static final String DEFAULT_EXCLUDE_SPECIAL_REGEX =
            "\\Adebian/(?:copyright|changelog)$";

    public static final String DEFAULT_EXCLUDE_FULLNAME_REGEX = "\\A$";

    // --- fields ---
    public String exclude = DEFAULT_EXCLUDE_FULLNAME_REGEX;
    public String mode = "full";
    public String copyrightFile = "debian/copyright";
    public boolean debug = false;
    public boolean verbose = false;
    public boolean quiet = false;
    public boolean text = false;
    public String groupBy = "license";
    public boolean splitOnLicense = true;
    public boolean splitDebian = true;
    public boolean glob = true;
    public boolean progress = true;
    public int jobs = 0;
    public String output = "";
    public String root = ".";
    public List<String> files = new ArrayList<>();

    // compiled patterns
    public Pattern excludeFullnameRe;
    public Pattern excludeSpecialRe;
    public Pattern excludeFileRe;
    public Pattern excludeDirectoryRe;

    private Options() {}

    public static Options parse(String[] args) {
        Options opts = new Options();
        List<String> positional = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-X", "--exclude" -> opts.exclude = args[++i];
                case "--mode" -> opts.mode = args[++i];
                case "--copyright-file" -> opts.copyrightFile = args[++i];
                case "--debug" -> opts.debug = true;
                case "-v", "--verbose" -> opts.verbose = true;
                case "-q", "--quiet" -> opts.quiet = true;
                case "-a", "--text" -> opts.text = true;
                case "--group-by" -> opts.groupBy = args[++i];
                case "--split-on-license" -> opts.splitOnLicense = true;
                case "--no-split-on-license" -> opts.splitOnLicense = false;
                case "--split-debian" -> opts.splitDebian = true;
                case "--no-split-debian" -> opts.splitDebian = false;
                case "--glob" -> opts.glob = true;
                case "--no-glob" -> opts.glob = false;
                case "--progress" -> opts.progress = true;
                case "--no-progress" -> opts.progress = false;
                case "-o", "--output" -> opts.output = args[++i];
                case "--root" -> opts.root = args[++i];
                case "-h", "--help" -> printHelp();
                default -> {
                    if (arg.startsWith("-j")) {
                        String val = arg.length() > 2 ? arg.substring(2) : args[++i];
                        opts.jobs = Integer.parseInt(val);
                    } else if (arg.startsWith("--jobs=")) {
                        opts.jobs = Integer.parseInt(arg.substring(7));
                    } else if (arg.equals("--jobs")) {
                        opts.jobs = Integer.parseInt(args[++i]);
                    } else if (!arg.startsWith("-")) {
                        positional.add(arg);
                    } else {
                        System.err.println("Unknown option: " + arg);
                        System.exit(1);
                    }
                }
            }
        }

        // Process output "-" means stdout
        if ("-".equals(opts.output)) {
            opts.output = "";
        }

        // Normalize root
        opts.root = Path.of(opts.root).toAbsolutePath().normalize().toString();

        // Process positional files
        for (String f : positional) {
            Path p = Path.of(f);
            if (!p.isAbsolute()) {
                p = Path.of(opts.root).resolve(p);
            }
            p = p.normalize();
            // Make relative to root
            try {
                Path rootPath = Path.of(opts.root);
                if (p.startsWith(rootPath)) {
                    opts.files.add(rootPath.relativize(p).toString());
                } else {
                    opts.files.add(f);
                }
            } catch (Exception e) {
                opts.files.add(f);
            }
        }

        // Set up logging
        Level logLevel;
        if (opts.debug) {
            logLevel = Level.FINE;
        } else if (opts.verbose) {
            logLevel = Level.INFO;
        } else if (opts.quiet) {
            logLevel = Level.SEVERE;
        } else {
            logLevel = Level.WARNING;
        }
        configureLogging(logLevel);

        // Compile patterns
        opts.excludeFullnameRe = Pattern.compile(opts.exclude,
                Pattern.CASE_INSENSITIVE | Pattern.COMMENTS);
        opts.excludeSpecialRe = Pattern.compile(DEFAULT_EXCLUDE_SPECIAL_REGEX,
                Pattern.CASE_INSENSITIVE | Pattern.COMMENTS);
        opts.excludeFileRe = Pattern.compile(DEFAULT_EXCLUDE_FILE_REGEX,
                Pattern.COMMENTS | Pattern.MULTILINE);
        opts.excludeDirectoryRe = Pattern.compile(DEFAULT_EXCLUDE_DIRECTORY_REGEX,
                Pattern.COMMENTS | Pattern.MULTILINE);

        return opts;
    }

    private static void configureLogging(Level level) {
        Logger root = Logger.getLogger("");
        root.setLevel(level);
        for (var handler : root.getHandlers()) {
            handler.setLevel(level);
        }
    }

    private static void printHelp() {
        System.out.println("""
                Usage: decopy [OPTIONS] [FILES...]

                License checker and copyright dep5 helper

                Options:
                  -X, --exclude PATTERN    Exclude files matching pattern
                  --mode {full,partial}    Processing mode (default: full)
                  --copyright-file FILE    Path to debian/copyright (default: debian/copyright)
                  --debug                  Enable debug output
                  -v, --verbose            Enable verbose output
                  -q, --quiet              Suppress non-error output
                  -a, --text               Treat all files as text
                  --group-by {license,copyright}  Group by license or copyright (default: license)
                  --[no-]split-on-license  Split groups on subdirs with licenses (default: yes)
                  --[no-]split-debian      Split debian/* paragraph (default: yes)
                  --[no-]glob              Use glob patterns in output (default: yes)
                  --[no-]progress          Show progress bar (default: yes)
                  -j, --jobs N             Parallel jobs (0=auto, default: 0)
                  -o, --output FILE        Output file (default: stdout)
                  --root DIR               Root directory to scan (default: .)
                  -h, --help               Show this help
                """);
        System.exit(0);
    }
}
