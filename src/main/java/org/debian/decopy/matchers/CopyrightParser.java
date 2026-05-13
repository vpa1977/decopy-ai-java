package org.debian.decopy.matchers;

import org.debian.decopy.datatypes.CopyrightHolder;
import org.debian.decopy.datatypes.YearRange;

import java.util.*;
import java.util.regex.*;

/**
 * Parses copyright holder information from text.
 * Java port of the Python matchers.py and its helpers.
 */
public final class CopyrightParser {

    private CopyrightParser() {}

    private static final int MIN_PART_LEN = 3;

    // Year pattern: matches "1990", "1990-2", "1990-1995", etc.
    private static final Pattern YEAR_RE = Pattern.compile(
        "\\s*(?:[\\s:(\\[]*)?(?<lo>\\d{2,4})[]:\\s]*" +
        "(?:(?:[-~=\u2013\u2014]|to)[\\s:\\[]*(?<hi>\\d{1,4})[]:\\s]*)?[,/)]*",
        Pattern.CASE_INSENSITIVE
    );

    // Email pattern
    private static final Pattern EMAIL_RE = Pattern.compile(
        "(?:^|[,;:\\s<>/\\\\(\\[])\\s*(?<email>[^\\]),;:\\s<>/\\\\(\\[]+?@[^\\]),;:\\s<>@/\\\\(\\[]+?)\\s*(?:[\\]),;:\\s/\\\\<>]|$)"
    );

    private static final Pattern HOLDER_RE = Pattern.compile(
        "\\s*(?:by\\s*)?(?<holder>\\S.*?\\S)[\\s\"*,;/]*$",
        Pattern.CASE_INSENSITIVE
    );

    // Pre-indicator regex to quickly find lines that may have copyright
    private static final Pattern COPYRIGHT_PRE_INDICATOR_RE = Pattern.compile(
        "Copyright|copr\\.|©|\u00a9|&copy;|&#169;|\\(\\s?c\\s?\\)",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );

    // Copyright indicator regex (full)
    private static final Pattern COPYRIGHT_INDICATOR_RE = Pattern.compile(
        "(?:^|\\s|\\W)" +
        "(" +
        "(?:[^ ]+ ){0,8}(?:is|are) " +
        "(?:" +
        "copyright\\ by\\ the[\\s]+" +
        "|Copyright(?:\\ \\(C\\))?[:\\s]+" +
        "|the\\ Copyright\\ (?:<\\w+>)?property\\ of(?:[:\\s]+|$)" +
        ")" +
        ")" +
        "(?=\\S|$)" +
        "|(?:^|\\s|\\W)(copyright(?:\\s*\\(c\\))?(?:[:\\s]+))(?=\\S|$)" +
        "|(?:^|\\s|\\W)(copr\\.(?:[:\\s]+))(?=\\S|$)" +
        "|(?:^|\\s)(©(?:[:\\s]+))(?=\\S|$)" +
        "|(?:^|\\s)(\u00a9(?:[:\\s]+))(?=\\S|$)" +
        "|(?:^|\\s|\\W)(&copy;(?:[:\\s]+))(?=\\S|$)" +
        "|(?:^|\\s|\\W)(@copyright\\{?\\}?(?:[:\\s]+))(?=\\S|$)" +
        "|(?:^|\\s|\\W)(&#169;(?:[:\\s]+))(?=\\S|$)" +
        "|(?:^|\\s|\\W)((?:Upstream Authors? ?(?:and|,) )?Copyright Holders(?:[:\\s]+))(?=\\S|$)" +
        "|(?:^|\\s|\\W)((?:\\(\\s?c\\s?\\))[:\\s]*)(?=\\S|$)" +
        "|(?:^|\\s|\\W)(SPDX-FileCopyrightText(?:\\s*\\(c\\))?(?:[:\\s]+))(?=\\S|$)",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );

    // Lines to strip comment markers from
    private static final Pattern[] COMMENT_PATTERNS = {
        Pattern.compile("^C[ \\t]|(?<!://)//+|^dnl\\s|^[\\s\\W]+\\s", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
        Pattern.compile("[^ \\w'.,@:;()/+-]"),
        Pattern.compile(" {2,}")
    };
    private static final String[] COMMENT_REPLS = { " ", " ", " " };

    public static String cleanComments(String text) {
        // Remove common comment markers
        text = text.replace("/*", " ").replace("*/", " ")
                   .replace(";;", " ")
                   .replace("<!--", " ").replace("-->", " ");
        text = COMMENT_PATTERNS[0].matcher(text).replaceAll(COMMENT_REPLS[0]);
        text = COMMENT_PATTERNS[1].matcher(text).replaceAll(COMMENT_REPLS[1]);
        text = COMMENT_PATTERNS[2].matcher(text).replaceAll(COMMENT_REPLS[2]);
        return text;
    }

    /**
     * Parse copyright holders from a block of text.
     */
    public static List<CopyrightHolder> parseHolders(String content) {
        List<CopyrightHolder> holders = new ArrayList<>();
        int lastSeen = -1;

        Matcher preMatcher = COPYRIGHT_PRE_INDICATOR_RE.matcher(content);
        while (preMatcher.find()) {
            if (preMatcher.start() < lastSeen) continue;

            int matchStart = preMatcher.start();
            int lineStart = content.lastIndexOf('\n', matchStart) + 1;

            String continuation = null;
            String continuationPrefix = null;
            YearRange pendingYears = null;

            // iterate lines from lineStart
            int pos = lineStart;
            while (pos < content.length()) {
                int lineEnd = content.indexOf('\n', pos);
                if (lineEnd < 0) lineEnd = content.length();
                String line = content.substring(pos, lineEnd);
                lastSeen = lineEnd;

                ParseCopyrightResult result = parseCopyright(
                        line, continuation, continuationPrefix, pendingYears);

                if (result.continuation() == null) {
                    break;
                }

                continuation = result.continuation();
                continuationPrefix = result.continuationPrefix();

                if (result.copyrights() == null || result.copyrights().isEmpty()) {
                    pendingYears = null;
                    pos = lineEnd + 1;
                    continue;
                }

                pendingYears = addNewHolders(holders, result.copyrights(), pendingYears);
                pos = lineEnd + 1;
            }
        }
        return holders;
    }

    private record ParseCopyrightResult(
            List<String> copyrights,
            String continuation,
            String continuationPrefix,
            YearRange years) {}

    private static ParseCopyrightResult parseCopyright(
            String line,
            String continuation,
            String continuationPrefix,
            YearRange years) {

        if (line.length() < 3) {
            return new ParseCopyrightResult(null, null, null, null);
        }

        Matcher m = COPYRIGHT_INDICATOR_RE.matcher(line);
        String rest;
        String newContinuation;
        String newContinuationPrefix;

        if (m.find()) {
            rest = line.substring(m.end());
            // Find the matched group to use as continuation prefix
            newContinuation = m.group(0);
            newContinuationPrefix = line.substring(0, m.start(0));
        } else {
            // Try continuation
            if (continuation == null) {
                return new ParseCopyrightResult(null, null, null, null);
            }
            // Check if line continues the previous copyright block
            if (!lineIsContinuation(line, continuationPrefix)) {
                return new ParseCopyrightResult(null, null, null, null);
            }
            rest = line.stripLeading();
            if (rest.trim().isEmpty()) {
                return new ParseCopyrightResult(null, null, null, null);
            }
            newContinuation = continuation;
            newContinuationPrefix = continuationPrefix;
        }

        // Apply ignore filters
        if (shouldIgnorePre(line) || shouldIgnorePost(rest)) {
            return new ParseCopyrightResult(Collections.emptyList(), newContinuation, newContinuationPrefix, null);
        }

        List<String> copyrights = new ArrayList<>();
        // Split rest by copyright indicators
        int lastEnd = 0;
        Matcher splitMatcher = COPYRIGHT_INDICATOR_RE.matcher(rest);
        while (splitMatcher.find()) {
            String part = rest.substring(lastEnd, splitMatcher.start());
            lastEnd = splitMatcher.end();
            if (!part.isEmpty()) copyrights.add(part);
        }
        copyrights.add(rest.substring(lastEnd));

        // Remove empty parts and apply cruft removal
        List<String> cleaned = new ArrayList<>();
        for (String part : copyrights) {
            part = removeCruft(part);
            if (!part.isEmpty() && part.length() > 2) {
                cleaned.add(part);
            }
        }

        return new ParseCopyrightResult(cleaned, newContinuation, newContinuationPrefix, null);
    }

    private static boolean lineIsContinuation(String line, String prefix) {
        if (prefix == null) return false;
        if (line.startsWith(prefix)) {
            String rest = line.substring(prefix.length());
            return rest.startsWith(" ") || rest.startsWith("\t");
        }
        // Try C-style /* continuation
        String altPrefix = prefix.replace("/*", " *").replace("\t", "  ");
        String altLine = line.replace("\t", "  ");
        if (altLine.startsWith(altPrefix)) {
            String rest = altLine.substring(altPrefix.length());
            return rest.startsWith(" ") || rest.startsWith("\t");
        }
        return false;
    }

    // Pre-ignore patterns (subset of the full Python list - the most common ones)
    private static final Pattern[] PRE_IGNORE = {
        Pattern.compile("^#\\s*define\\s+.*\\([^)]*c[^)]*\\)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^msgstr\\s", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^msgid\\s", Pattern.CASE_INSENSITIVE),
        Pattern.compile("We\\sprotect\\syour\\srights", Pattern.CASE_INSENSITIVE),
        Pattern.compile("and\\sput\\sthe\\sfollowing\\scopyright", Pattern.CASE_INSENSITIVE),
        Pattern.compile("connection\\swith\\sthe\\scopyright", Pattern.CASE_INSENSITIVE),
        Pattern.compile("copyright\\s(?:info|headers?|lines?|messages?|reasons?|symbols?)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("copyright\\son\\s(?:the|this)\\s(?:program|software)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("copyright\\sfile|copyright\\sformat|copyright\\shelper|copyright\\sdetection", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bif\\b.*\\(\\s?c\\s?\\)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bwhile\\b.*\\(\\s?c\\s?\\)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bCopyright:?\\s(?:\\(\\s*C\\s*\\)\\s)?YEAR\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("group_by\\scopyright", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\(c\\)\\s*[\\*/&|<>+\\-]?=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("sizeof\\([^)]+\\)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\(c\\)\\s*->|\\(c\\)\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("following\\scopyright\\sand\\slicense", Pattern.CASE_INSENSITIVE),
        Pattern.compile("59\\sTemple\\sPlace", Pattern.CASE_INSENSITIVE),
        Pattern.compile("51\\sFranklin\\s", Pattern.CASE_INSENSITIVE),
    };

    private static boolean shouldIgnorePre(String line) {
        for (Pattern p : PRE_IGNORE) {
            if (p.matcher(line).find()) return true;
        }
        return false;
    }

    // Post-ignore patterns
    private static final Pattern[] POST_IGNORE = {
        Pattern.compile("\\binformation\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bnotices?\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bstatements?\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bclaims?\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bstrings?\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b<name\\sof\\sauthor>\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bYEAR\\s+YOUR\\s+NAME\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bholder\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bowner\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bif\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|\\s)&&(?:\\s|$)"),
        Pattern.compile("(?:^|\\s)\\|\\|(?:\\s|$)"),
        Pattern.compile("^\\s*\\{\\s*$"),
        Pattern.compile("^\\s*L?GPL$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^\\s*is\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^\\s*law[.:]?\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bin\\b", Pattern.CASE_INSENSITIVE),
    };

    private static boolean shouldIgnorePost(String rest) {
        for (Pattern p : POST_IGNORE) {
            if (p.matcher(rest).find()) return true;
        }
        return false;
    }

    // Cruft removal substitutions
    private static final Pattern[][] CRUFT_SUBS = {
        { Pattern.compile("(?:(?:some|all)?\\srights\\sreserved|(?:some|all)\\srights)[\\s,.;*#'\"]*", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("It can be distributed", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("and contributors", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("Franklin St(?:reet)?, Fifth Floor,?", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("et al", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("\\band$", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("\\\\$"), null },
        { Pattern.compile("[\\s,.;*#'\"]*$"), null },
        { Pattern.compile("\\(\\sc\\s\\)", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("</?\\w{1,2}/?>"), null },
        { Pattern.compile("^(?:of|and)\\s", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("\uFFFD"), null }, // Unicode replacement char
        { Pattern.compile("\\s{2,}"), null },
        { Pattern.compile("^\\s+"), null },
        { Pattern.compile("\\s+$"), null },
        { Pattern.compile("\\\\@"), null },
        { Pattern.compile("&ndash;"), null },
    };
    private static final String[] CRUFT_REPLS = {
        "", "", "", "", "", "", "", "", "", "", "", "", " ", "", "", "@", "-"
    };

    private static String removeCruft(String text) {
        for (int i = 0; i < CRUFT_SUBS.length; i++) {
            text = CRUFT_SUBS[i][0].matcher(text).replaceAll(
                    CRUFT_REPLS[i] != null ? CRUFT_REPLS[i] : "");
        }
        return text;
    }

    private static YearRange addNewHolders(List<CopyrightHolder> holders,
                                            List<String> copyrights,
                                            YearRange years) {

        for (String copyright : copyrights) {
            ParseHoldersResult r = getCopyrightHoldersInternal(copyright, years);
            var yearHolders = r.holders.stream().filter( x ->
                    x.getYears() != null &&
                            (x.getYears().getLow() != 0 ||
                            x.getYears().getHigh() !=0))
                    .toList();
            if (!yearHolders.isEmpty()) {
                holders.addAll(yearHolders);
                years = r.remainingYears();
            } else if (r.remainingYears() != null) {
                years = r.remainingYears();
                if (!holders.isEmpty() && holders.get(holders.size() - 1).getYears().isEmpty()) {
                    holders.get(holders.size() - 1).getYears().merge(years);
                    years = null;
                }
            }
        }
        return years;
    }

    private record ParseHoldersResult(List<CopyrightHolder> holders, YearRange remainingYears) {}

    /**
     * Public entry point: parse copyright holders from a string.
     */
    public static List<CopyrightHolder> getCopyrightHolders(String text) {
        return getCopyrightHoldersInternal(text, null).holders();
    }

    private static ParseHoldersResult getCopyrightHoldersInternal(String text, YearRange initialYears) {
        List<HolderYear> yearTextPairs = splitByYears(text, initialYears);
        List<CopyrightHolder> holders = new ArrayList<>();
        YearRange pendingYears = null;

        for (HolderYear hy : yearTextPairs) {
            YearRange years = hy.years();
            String part = hy.text();

            if (years != null && pendingYears != null) {
                pendingYears.merge(years);
            } else if (pendingYears == null || years != null) {
                pendingYears = years;
            }

            Matcher holderMatcher = HOLDER_RE.matcher(part);
            if (!holderMatcher.find()) {
                if (pendingYears != null && !holders.isEmpty() &&
                    holders.get(holders.size() - 1).getYears().isEmpty()) {
                    holders.get(holders.size() - 1).getYears().merge(pendingYears);
                    pendingYears = null;
                }
                continue;
            }

            String holderText = holderMatcher.group("holder");
            List<NameEmail> nameEmails = getHolderEmails(holderText);
            pendingYears = processNameEmailPairs(nameEmails, holders, pendingYears);
        }

        return new ParseHoldersResult(holders, pendingYears);
    }

    private record HolderYear(YearRange years, String text) {}

    private static List<HolderYear> splitByYears(String text, YearRange initialYears) {
        List<YearRange> yearsList = initialYears != null
                ? new ArrayList<>(List.of(initialYears)) : new ArrayList<>();
        List<String> parts = initialYears != null ? new ArrayList<>(List.of("")) : new ArrayList<>();

        int lastEnd = 0;
        YearRange currentYears = null;
        Matcher m = YEAR_RE.matcher(text);

        while (m.find()) {
            if (isYearPostIgnore(text, m)) continue;

            String lo = m.group("lo");
            if (lo == null) continue;
            Integer low = getYear(lo, 0);
            if (low == null) continue;
            Integer high = low;
            String hiStr = m.group("hi");
            if (hiStr != null) {
                Integer h = getYear(hiStr, low);
                if (h != null) high = h;
            }

            int partLen = m.start() - lastEnd;
            if (currentYears == null || partLen >= MIN_PART_LEN) {
                parts.add(text.substring(lastEnd, m.start()));
                currentYears = new YearRange(low, high);
                yearsList.add(currentYears);
            } else {
                currentYears.add(low).add(high);
            }
            lastEnd = m.end();
        }
        parts.add(text.substring(lastEnd));

        // Zip years and parts
        List<HolderYear> result = new ArrayList<>();
        if (parts.get(0).length() < MIN_PART_LEN) {
            // first part is short: skip it, zip years with parts[1:]
            for (int i = 0; i < yearsList.size() && i + 1 < parts.size(); i++) {
                result.add(new HolderYear(yearsList.get(i), parts.get(i + 1)));
            }
        } else if (!parts.isEmpty() && parts.get(parts.size() - 1).length() < MIN_PART_LEN) {
            // last part is short
            List<YearRange> yl = new ArrayList<>();
            yl.add(new YearRange()); // empty
            yl.addAll(yearsList);
            for (int i = 0; i < yl.size() && i < parts.size(); i++) {
                result.add(new HolderYear(yl.get(i), parts.get(i)));
            }
        } else {
            List<YearRange> yl = new ArrayList<>();
            yl.add(new YearRange()); // empty
            yl.addAll(yearsList);
            for (int i = 0; i < yl.size() && i < parts.size(); i++) {
                result.add(new HolderYear(yl.get(i), parts.get(i)));
            }
        }
        return result;
    }

    private static boolean isYearPostIgnore(String text, Matcher m) {
        String lo = m.group("lo");
        if (lo == null) return true;
        int start = Math.max(0, m.start("lo") - 1);
        int end = Math.min(text.length(), m.end("lo") + 1);
        String context = text.substring(start, end);
        // If surrounded by alpha chars or @, ignore
        Pattern alphaCheck = Pattern.compile("[a-zA-Z]{0}|.{0}[a-zA-Z@]".replace("{0}", lo));
        return alphaCheck.matcher(context).find();
    }

    private static Integer getYear(String text, int ref) {
        try {
            int year = Integer.parseInt(text.trim());
            if (year < 10 && ref != 0) {
                int fix = (ref % 10) < year ? 0 : 1;
                year += ((ref / 10) + fix) * 10;
            }
            if (year < 50) year += 2000;
            if (year < 100) year += 1900;
            if (year < 1000) return null;
            return year;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record NameEmail(String name, String email) {}

    private static List<NameEmail> getHolderEmails(String text) {
        // Apply email subs first
        text = applyEmailSubs(text);

        List<NameEmail> result = new ArrayList<>();
        int lastEnd = 0;
        Matcher m = EMAIL_RE.matcher(text);
        while (m.find()) {
            String email = m.group("email");
            String name = text.substring(lastEnd, m.start());
            lastEnd = m.end();
            result.add(new NameEmail(name, email));
        }
        if (text.length() - lastEnd >= MIN_PART_LEN) {
            result.add(new NameEmail(text.substring(lastEnd), ""));
        }
        return result;
    }

    private static final Pattern[][] EMAIL_SUBS = {
        { Pattern.compile("</?tt>"), null },
        { Pattern.compile("%20"), null },
        { Pattern.compile("(^\\s?<!--|-->\\s?$)"), null },
        { Pattern.compile("&lt;?"), null },
        { Pattern.compile("&gt;?"), null },
        { Pattern.compile("&#x40;"), null },
        { Pattern.compile("\\(c\\)$", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("\\s+\\(?(where|at|@)\\)?\\s+", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("\\(at\\)", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("\\s+\\(?do?[tm]\\)?\\s+", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("\\s\\s+"), null },
        { Pattern.compile("^\\s"), null },
        { Pattern.compile("\\s$"), null },
    };
    private static final String[] EMAIL_REPLS = {
        "", " ", " ", "<", ">", "@", "", "@", "@", ".", " ", "", ""
    };

    private static String applyEmailSubs(String text) {
        for (int i = 0; i < EMAIL_SUBS.length; i++) {
            text = EMAIL_SUBS[i][0].matcher(text).replaceAll(
                    EMAIL_REPLS[i] != null ? EMAIL_REPLS[i] : "");
        }
        return text;
    }

    private static final Pattern[][] NAME_CRUFT_SUBS = {
        { Pattern.compile("</item>", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("^>", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("<$", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("\\\\[nt]$", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("^\\(\\s*c\\s*\\)\\s*", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("^and$", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("^and\\s+(?:others|contributors)?\\s*", Pattern.CASE_INSENSITIVE), null },
        { Pattern.compile("\\bbut is\\b.*$", Pattern.CASE_INSENSITIVE), null },
    };

    private static YearRange processNameEmailPairs(List<NameEmail> pairs,
                                                    List<CopyrightHolder> holders,
                                                    YearRange years) {
        boolean yearsUsed = false;
        for (NameEmail ne : pairs) {
            String name = ne.name() != null ? ne.name() : "";
            String email = ne.email() != null ? ne.email() : "";

            // Clean name
            name = name.strip().replaceAll("^[,.;*'\"@\\-\u2013\u2014\\[\\]{} \\t]+|[,.;*'\"@\\-\u2013\u2014\\[\\]{} \\t]+$", "");
            for (int i = 0; i < NAME_CRUFT_SUBS.length; i++) {
                name = NAME_CRUFT_SUBS[i][0].matcher(name).replaceAll("");
            }
            if (name.length() < MIN_PART_LEN) name = "";

            if (name.isEmpty() && email.isEmpty()) {
                if (years != null && !holders.isEmpty() &&
                    holders.get(holders.size() - 1).getYears().isEmpty()) {
                    holders.get(holders.size() - 1).getYears().merge(years);
                    years = null;
                }
                continue;
            }
            if (name.isEmpty() && !years.isEmpty() &&
                !email.isEmpty() && !holders.isEmpty() &&
                holders.get(holders.size() - 1).getEmail().isEmpty()) {
                holders.get(holders.size() - 1).setEmail(email);
                continue;
            }
            if (email.isEmpty() && (years == null || years.isEmpty()) &&
                !name.isEmpty() && !holders.isEmpty() &&
                holders.get(holders.size() - 1).getName().isEmpty()) {
                holders.get(holders.size() - 1).setName(name);
                continue;
            }

            holders.add(new CopyrightHolder(name, email, years != null ? years : new YearRange()));
            yearsUsed = true;
        }
        return yearsUsed ? null : years;
    }
}
