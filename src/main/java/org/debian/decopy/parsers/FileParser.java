package org.debian.decopy.parsers;

import org.apache.tika.Tika;
import org.debian.decopy.Options;
import org.debian.decopy.datatypes.CopyrightHolder;
import org.debian.decopy.matchers.CopyrightParser;
import org.debian.decopy.matchers.LicenseMatcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Parse a file to extract copyright holders and license identifiers.
 * Java port of parsers.py.
 */
public final class FileParser {

    private static final Logger LOG = Logger.getLogger(FileParser.class.getName());
    private static final Tika TIKA = new Tika();

    private static final byte[] PRINTABLE_ASCII;
    static {
        byte[] buf = new byte[128];
        for (int i = 0; i < 128; i++) {
            char c = (char) i;
            buf[i] = (c >= 32 && c < 127) ? (byte) 1 : 0;
        }
        PRINTABLE_ASCII = buf;
    }

    public record ParseResult(List<CopyrightHolder> copyrights, List<String> licenses) {}

    public static ParseResult parseFile(String fullPath, Options options) throws IOException {
        if (options.text) {
            return genericParser(fullPath);
        }

        String mimeType;
        try {
            mimeType = TIKA.detect(new File(fullPath));
        } catch (Exception e) {
            LOG.fine("MIME detection failed for " + fullPath + ": " + e.getMessage());
            mimeType = "application/octet-stream";
        }
        LOG.fine("Type for " + fullPath + " is: " + mimeType);

        return switch (mimeType) {
            case "application/gzip" -> cmdParser(fullPath, "zcat");
            case "application/x-bzip2", "application/x-bzip" -> cmdParser(fullPath, "bzcat");
            case "application/x-lzma", "application/x-xz" -> cmdParser(fullPath, "xzcat");
            case "text/x-python" -> pythonParser(fullPath);
            case "text/html", "application/xhtml+xml" -> htmlParser(fullPath);
            case "text/x-gettext-translation", "text/x-gettext-translation-template",
                 "text/x-po", "text/x-pot" -> poFileParser(fullPath);
            default -> {
                if (isBinaryMimeType(mimeType)) {
                    yield exifToolParser(fullPath);
                }
                yield genericParser(fullPath);
            }
        };
    }

    private static boolean isBinaryMimeType(String mime) {
        return mime.startsWith("image/") ||
               mime.startsWith("video/") ||
               mime.startsWith("audio/") ||
               mime.startsWith("font/") ||
               mime.equals("application/pdf") ||
               mime.equals("application/msword") ||
               mime.equals("application/vnd.ms-excel") ||
               mime.equals("application/vnd.ms-powerpoint") ||
               mime.startsWith("application/vnd.openxmlformats") ||
               mime.startsWith("application/vnd.oasis") ||
               mime.equals("application/x-shockwave-flash") ||
               mime.equals("application/x-font-ttf") ||
               mime.equals("application/x-font-otf");
    }

    /** Read a file with BOM detection and fallback to latin1. */
    static String readFileContent(String path) throws IOException {
        byte[] raw = Files.readAllBytes(Path.of(path));

        // Detect BOM
        String encoding = detectBom(raw);
        String text;
        try {
            text = new String(raw, encoding);
        } catch (Exception e) {
            text = new String(raw, "latin1");
        }

        // Convert binary content: replace control chars with newlines
        if (hasBinaryContent(raw)) {
            StringBuilder sb = new StringBuilder(raw.length);
            for (byte b : raw) {
                int c = b & 0xFF;
                if (c < 128 && PRINTABLE_ASCII[c] != 0) {
                    sb.append((char) c);
                } else if (c == 9 || c == 10 || c == 13) {
                    sb.append((char) c);
                } else {
                    sb.append('\n');
                }
            }
            text = sb.toString();
            // Collapse multiple newlines
            text = text.replaceAll("\n{2,}", "\n");
        }

        // Mac to Unix line endings
        text = text.replaceAll("\r([^\n])", "\n$1");
        return text;
    }

    private static String detectBom(byte[] buf) {
        if (buf.length >= 3 && buf[0] == (byte) 0xEF && buf[1] == (byte) 0xBB && buf[2] == (byte) 0xBF)
            return "UTF-8";
        if (buf.length >= 4 && buf[0] == 0 && buf[1] == 0 && buf[2] == (byte) 0xFE && buf[3] == (byte) 0xFF)
            return "UTF-32BE";
        if (buf.length >= 4 && buf[0] == (byte) 0xFF && buf[1] == (byte) 0xFE && buf[2] == 0 && buf[3] == 0)
            return "UTF-32LE";
        if (buf.length >= 2 && buf[0] == (byte) 0xFE && buf[1] == (byte) 0xFF)
            return "UTF-16BE";
        if (buf.length >= 2 && buf[0] == (byte) 0xFF && buf[1] == (byte) 0xFE)
            return "UTF-16LE";
        return "UTF-8";
    }

    private static boolean hasBinaryContent(byte[] raw) {
        int check = Math.min(raw.length, 8192);
        for (int i = 0; i < check; i++) {
            int c = raw[i] & 0xFF;
            if (c < 32 && c != 9 && c != 10 && c != 13) return true;
            if (c == 127) return true;
        }
        return false;
    }

    static ParseResult processContent(String content) {
        List<CopyrightHolder> holders = CopyrightParser.parseHolders(content);
        String cleaned = CopyrightParser.cleanComments(content);
        List<String> licenses = LicenseMatcher.findLicenses(cleaned);
        return new ParseResult(holders, licenses);
    }

    public static ParseResult genericParser(String path) throws IOException {
        String content = readFileContent(path);
        return processContent(content);
    }

    static ParseResult cmdParser(String path, String cmd) throws IOException {
        String which = whichCommand(cmd);
        if (which == null) {
            LOG.warning("Command " + cmd + " not found, falling back to generic parser");
            return genericParser(path);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd, path);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            byte[] output = proc.getInputStream().readAllBytes();
            proc.waitFor();
            String content = new String(output, StandardCharsets.UTF_8);
            return processContent(content);
        } catch (Exception e) {
            LOG.info("Failed to parse " + path + " with " + cmd + ": " + e.getMessage() + ", falling back");
            return genericParser(path);
        }
    }

    static ParseResult exifToolParser(String path) throws IOException {
        return cmdParser(path, "exiftool");
    }

    private static final Pattern POUND_LINE = Pattern.compile("^\\s*#");

    static ParseResult poundLinesParser(String path) throws IOException {
        String content = readFileContent(path);
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (POUND_LINE.matcher(line).find()) {
                sb.append(line).append('\n');
            } else {
                sb.append('\n');
            }
        }
        return processContent(sb.toString());
    }

    private static final Pattern PO_COMMENT = Pattern.compile("^\\s*#.{0,2}\\smsg(?:str|id|ctxt)\\b");

    static ParseResult poFileParser(String path) throws IOException {
        String content = readFileContent(path);
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (!POUND_LINE.matcher(line).find()) {
                sb.append('\n');
            } else if (PO_COMMENT.matcher(line).find()) {
                sb.append('\n');
            } else {
                sb.append(line).append('\n');
            }
        }
        return processContent(sb.toString());
    }

    static ParseResult htmlParser(String path) throws IOException {
        String content = readFileContent(path);
        content = content.replaceAll("<\\s*br\\s*/?>", "\n");
        // Unescape basic HTML entities
        content = content.replace("&amp;", "&")
                         .replace("&lt;", "<")
                         .replace("&gt;", ">")
                         .replace("&quot;", "\"")
                         .replace("&apos;", "'")
                         .replace("&copy;", "©")
                         .replace("&#169;", "©");
        return processContent(content);
    }

    static ParseResult pythonParser(String path) throws IOException {
        // For python files: extract comments and string literals only
        // Simplified: just read # comment lines and triple-quoted strings
        String content = readFileContent(path);
        StringBuilder sb = new StringBuilder();
        boolean inTriple = false;
        for (String line : content.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (!inTriple) {
                if (trimmed.startsWith("#")) {
                    sb.append(line).append('\n');
                } else if (trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''")) {
                    inTriple = true;
                    sb.append(line).append('\n');
                } else {
                    sb.append('\n');
                }
            } else {
                sb.append(line).append('\n');
                if (line.contains("\"\"\"") || line.contains("'''")) {
                    inTriple = false;
                }
            }
        }
        return processContent(sb.toString());
    }

    private static String whichCommand(String cmd) {
        for (String dir : System.getenv("PATH").split(File.pathSeparator)) {
            File f = new File(dir, cmd);
            if (f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }
}
