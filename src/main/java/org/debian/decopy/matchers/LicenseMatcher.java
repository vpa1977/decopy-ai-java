package org.debian.decopy.matchers;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.function.*;

/**
 * License detection patterns and helpers.
 * Java port of the Python res.py and matchers.py license-matching logic.
 */
public final class LicenseMatcher {

    private LicenseMatcher() {}

    private record LicensePattern(Pattern pattern, String license, BiFunction<String, Matcher, String> getDetail) {}

    private static final List<LicensePattern> LICENSES = buildLicensePatterns();

    private static final int THREAD_POOL_SIZE = 4;

    private static List<LicensePattern> buildLicensePatterns() {
        List<LicensePattern> list = new ArrayList<>();
        int flags = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

        // SPDX
        list.add(lp("SPDX-License-Identifier:\\s*([^ \\n]*)", "SPDX",
                (t, m) -> replaceSpdx(m.group(1)), flags));

        // Public domain
        list.add(lp("(?:This [^ ]+ is in|is (?:hereby |released )?(?:in(?:to)|to|for)) the public domain", "public-domain", null, flags));
        list.add(lp("This work is free of known copyright restrictions", "public-domain", null, flags));
        list.add(lp("https?[/ ]{,2}creativecommons\\.org/publicdomain/(?:mark|zero)", "public-domain", null, flags));

        // Apache
        list.add(lp("under the Apache License,? Version ([\\d.]+)", "Apache",
                (t, m) -> "Apache-" + m.group(1).replaceAll("\\.0$", ""), flags));
        list.add(lp("Apache License Version ([\\d.]+)", "Apache",
                (t, m) -> "Apache-" + m.group(1).replaceAll("\\.0$", ""), flags));
        list.add(lp("Licensed under the Apache License v([\\d.]+)", "Apache",
                (t, m) -> "Apache-" + m.group(1).replaceAll("\\.0$", ""), flags));

        // Artistic
        list.add(lp("Released under the terms of the Artistic License (?:v|version )?([\\d.]+)", "Artistic",
                (t, m) -> "Artistic-" + m.group(1).replaceAll("\\.0$", ""), flags));
        list.add(lp("Artistic License.*?2\\.0", "Artistic", (t, m) -> "Artistic-2", flags));
        list.add(lp("under the terms of.* the Artistic License", "Artistic", null, flags));

        // BSD
        list.add(lp("THIS SOFTWARE IS PROVIDED .*?[\"']?AS IS[\"']? AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY", "BSD", null, flags));
        list.add(lp("Redistribution and use in source and binary forms.*?Redistributions of source code must retain the above copyright notice.*?Redistributions in binary form must reproduce", "BSD", null, flags));
        list.add(lp("Use of this source code is governed by a BSD-style license", "BSD", null, flags));
        list.add(lp("Distributed under the OSI-approved BSD License", "BSD", null, flags));
        list.add(lp("Redistribution and use is allowed according to the terms of the 2-clause BSD license", "BSD", (t, m) -> "BSD-2-clause", flags));
        list.add(lp("Redistribution and use is allowed according to the terms of the (?:new )?BSD license", "BSD", (t, m) -> "BSD-3-clause", flags));
        list.add(lp("(?:the names?|neither the (?:names? |authors?)) .*? (?:may not|nor the names of) .*? contributors may be used to endorse or promote", "BSD",
                (t, m) -> "BSD-3-clause", flags));
        list.add(lp("All advertising materials mentioning features or use of this software must display the following acknowledge?ment.*?This product includes software developed by", "BSD",
                (t, m) -> "BSD-4-clause", flags));
        list.add(lp("Redistributions of source code must retain the(?: above)? copyright notice", "BSD",
                (t, m) -> "BSD-2-clause", flags));
        list.add(lp("Permission to use, copy, modify, and(?:/or)? distribute this (?:[^ ]+ ){0,4}for any purpose with or without fee is hereby granted.*?THE SOFTWARE IS PROVIDED.*?AS IS.*?AND THE AUTHOR DISCLAIMS ALL WARRANTIES", "BSD",
                (t, m) -> "0BSD", flags));

        // ISC
        list.add(lp("Permission to use, copy, modify, and(?:/or)? distribute this (?:[^ ]+ ){0,4}for any purpose with or without fee is hereby granted.*?copyright notice.*?permission notice.*?all copies.*?Except as contained in this notice, the name of a copyright holder shall not be used", "ISC",
                (t, m) -> "ISC", flags));
        list.add(lp("Permission to use, copy, modify, and(?:/or)? distribute this (?:[^ ]+ ){0,4}for any purpose with or without fee is hereby granted, provided.*?copyright notice.*?permission notice.*?all copies", "ISC",
                null, flags));

        // CC-BY
        list.add(lp("https?[/ ]{0,2}creativecommons\\.org/licenses/by", "CC-BY", (t, m) -> parseCCBY(t), flags));
        list.add(lp("(?:Creative Commons|CC) (?:Legal Code )?Attribution", "CC-BY", (t, m) -> parseCCBY(t), flags));
        // CC0
        list.add(lp("https?[/ ]{0,2}creativecommons\\.org/publicdomain/zero", "CC0", null, flags));
        list.add(lp("to the extent possible under law.*?the person who associated CC0.*?with this work has waived all copyright", "CC0", null, flags));

        // CDDL
        list.add(lp("terms of the Common Development and Distribution License(?:, Version ([^ (]+))? \\(the[\" ]*License[\" ]?\\)", "CDDL",
                (t, m) -> "CDDL" + (m.group(1) != null ? "-" + m.group(1) : ""), flags));

        // GPL
        list.add(lp("GNU GENERAL PUBLIC LICENSE\\s+Version (\\d+)", "GPL",
                (t, m) -> "GPL-" + m.group(1), flags));
        list.add(lp("GNU General Public License,? Version ([\\d.]+) or later", "GPL",
                (t, m) -> "GPL-" + m.group(1).replaceAll("\\.0$", "") + "+", flags));
        list.add(lp("version ([\\d.]+) of the License,? or.*?(?:\\(at your option\\)|any later version)", "GPL",
                (t, m) -> "GPL-" + m.group(1).replaceAll("\\.0$", "") + "+", flags));
        list.add(lp("either version ([^ ]+?) of the License.*?or.*?any later version", "GPL",
                (t, m) -> "GPL-" + m.group(1).replaceAll("\\.0+$", "") + "+", flags));
        list.add(lp("General Public License,? Version ([\\d.]+)", "GPL",
                (t, m) -> "GPL-" + m.group(1).replaceAll("\\.0$", ""), flags));
        list.add(lp("License:? GPL[- v]*([\\d.]*)(\\+?)", "GPL",
                (t, m) -> "GPL" + (!m.group(1).isEmpty() ? "-" + m.group(1).replaceAll("\\.0$", "") + m.group(2) : ""), flags));
        list.add(lp("(?:is free software.*?you (?:can|may) (?:re)?distribute.*?modify.*?|is (?:distributed|licensed)) under the terms of (?:the )?(?:GNU )?(?:General Public License|GPL)", "GPL", null, flags));
        list.add(lp("may be distributed and/or modified under the terms of the GNU General Public License", "GPL", null, flags));
        list.add(lp("may be used under the terms of the GNU General Public License version ([\\d.]+)", "GPL",
                (t, m) -> "GPL-" + m.group(1).replaceAll("\\.0$", ""), flags));
        list.add(lp("same terms as Perl", "GPL", (t, m) -> "GPL-1+", flags));

        // LGPL
        list.add(lp("GNU (?:Lesser|Library) General Public License,? (?:Version|v\\.?) ?([\\d.]+).*?or later", "LGPL",
                (t, m) -> "LGPL-" + m.group(1).replaceAll("\\.0$", "") + "+", flags));
        list.add(lp("GNU (?:Lesser|Library) General Public License,? (?:Version|v\\.?) ?([\\d.]+)", "LGPL",
                (t, m) -> "LGPL-" + m.group(1).replaceAll("\\.0$", ""), flags));
        list.add(lp("License:? LGPL[- v]*([\\d.]*)(\\+?)", "LGPL",
                (t, m) -> "LGPL" + (!m.group(1).isEmpty() ? "-" + m.group(1).replaceAll("\\.0$", "") + m.group(2) : ""), flags));
        list.add(lp("(?:is free software.*?you (?:can|may) (?:re)?distribute.*?modify.*?|is (?:distributed|licensed)) under the terms of (?:the )?(?:GNU )?(?:(?:Library|Lesser).*?General Public Licen[sc]e|LGPL)", "LGPL", null, flags));
        list.add(lp("may be (?:distributed and/or modified|used) under the terms of the GNU Lesser(?:/Library)? General Public License", "LGPL", null, flags));
        list.add(lp("This version of the GNU Lesser General Public License incorporates the terms and conditions of version 3 of the GNU General Public License", "LGPL", (t, m) -> "LGPL-3+", flags));
        list.add(lp("Distributed under the LGPL\\.", "LGPL", null, flags));

        // AGPL
        list.add(lp("(?:is distributed|licensed) under the terms of (?:the )?(?:GNU )?(?:Affero (?:GNU )?General Public License|AGPL)", "AGPL", null, flags));
        list.add(lp("GNU Affero General Public License.*?version ([\\d.]+)", "AGPL",
                (t, m) -> "AGPL-" + m.group(1).replaceAll("\\.0$", ""), flags));

        // GFDL
        list.add(lp("Permission is (?:hereby )?granted to copy, distribute and(?:/or)? modify this [^ ]+ under the terms of the GNU Free Documentation License", "GFDL", null, flags));
        list.add(lp("GNU Free Documentation License.*?no Invariant Sections, no Front-Cover Texts, and no Back-Cover Texts", "GFDL", (t, m) -> "GFDL-NIV", flags));

        // LPPL
        list.add(lp("LaTeX Project Public License.*?(?:version|v) ?([\\d.]+)", "LPPL",
                (t, m) -> "LPPL-" + m.group(1), flags));
        list.add(lp("(?:distributed and/or modified|is) under the (?:terms|conditions) of the LaTeX Project Public License", "LPPL", null, flags));

        // MPL
        list.add(lp("This Source Code Form is subject to the terms of the Mozilla Public License,? (?:v\\.|version) ([\\d.]+)", "MPL",
                (t, m) -> "MPL-" + m.group(1).replaceAll("\\.0$", ""), flags));
        list.add(lp("Mozilla Public License,? (?:Version|v\\.?) ?([\\d.]+)", "MPL",
                (t, m) -> "MPL-" + m.group(1).replaceAll("\\.0$", ""), flags));
        list.add(lp("contents of this file are subject to the Mozilla Public License", "MPL", null, flags));

        // MIT/Expat
        list.add(lp("MIT license.*?https?[: ][/ ]{1,2}www\\.opensource\\.org/licenses/mit", "MIT/X11", (t, m) -> "Expat", flags));
        list.add(lp("SPDX-License-Identifier:? MIT\\b", "MIT/X11", (t, m) -> "Expat", flags));
        list.add(lp("this project is licensed under the terms of the MIT license", "MIT/X11", (t, m) -> "Expat", flags));
        list.add(lp("Permission is hereby granted, free of charge, to any person obtaining a copy of this software and(?:/or)? associated documentation files.*?to deal in the (?:Software|Materials) without restriction.*?The above copyright notice and this permission notice shall be included in all copies or substantial portions", "MIT/X11",
                (t, m) -> "Expat", flags));
        list.add(lp("Permission is hereby granted, free of charge, to any person obtaining a copy of this software.*?to deal in the Software without restriction", "MIT/X11", null, flags));

        // Python
        list.add(lp("PYTHON SOFTWARE FOUNDATION LICENSE(?: VERSION ([^ \\n]+))?", "Python",
                (t, m) -> "Python" + (m.group(1) != null ? "-" + m.group(1) : ""), flags));

        // QPL
        list.add(lp("may be distributed under the terms of the Q Public License(?: version ([\\d.]+))?", "QPL",
                (t, m) -> "QPL" + (m.group(1) != null ? "-" + m.group(1).replaceAll("\\.0$", "") : ""), flags));

        // W3C
        list.add(lp("(?:may|can|is) (?:re)?distributed under the (?:W3C®|W3C) Software License", "W3C", null, flags));

        // Zlib
        list.add(lp("The origin of this software must not be misrepresented.*?Altered source versions must be plainly marked as such.*?This notice may not be removed or altered from any source distribution", "Zlib", null, flags));
        list.add(lp("see copyright notice in zlib\\.h", "Zlib", null, flags));
        list.add(lp("This code is released under the libpng license", "Zlib", (t, m) -> "Libpng", flags));

        // ZPL (Zope)
        list.add(lp("Zope Public License(?:\\s*\\(ZPL\\)?)?", "ZPL", (t, m) -> parseZPL(t), flags));
        list.add(lp("(?:distributed and/or modified|subject to the provisions) (?:under|of) the (?:terms|conditions) of the Zope Public License", "ZPL", null, flags));

        // EPL
        list.add(lp("Eclipse Public License - v ([\\d.]+)", "EPL",
                (t, m) -> "EPL-" + m.group(1), flags));

        // CPL
        list.add(lp("under the terms of.*?the.*?Common Public License", "CPL", (t, m) -> "CPL-1", flags));

        // CPAL
        list.add(lp("Common Public Attribution License Version ([\\d.]+)", "MPL",
                (t, m) -> "CPAL-" + m.group(1).replaceAll("\\.0$", ""), flags));

        // Boost
        list.add(lp("Boost Software License[-.,]+(?: Version ([\\d.]+))?", "BSL",
                (t, m) -> "BSL" + (m.group(1) != null ? "-" + m.group(1).replaceAll("\\.0$", "") : ""), flags));

        // Artistic/Clarified
        list.add(lp("The (?:\")?Clarified (?:\")?Artistic License", "Artistic", (t, m) -> "ClArtistic", flags));

        // EFL
        list.add(lp("Permission is hereby granted to use, copy, modify and(?:/or) distribute this [^ ]+ provided that.*?copyright notices are retained unchanged.*?Permission is hereby also granted to distribute binary programs which depend on this [^ ]+.*?if the binary program depends on a modified version of this [^ ]+ you (must|are encouraged to) publicly release the modified version", "EFL",
                (t, m) -> "EFL-" + ("must".equalsIgnoreCase(m.group(1)) ? "1" : "2"), flags));

        // Beer-ware
        list.add(lp("THE BEER-WARE LICENSE", "Beerware", null, flags));

        // Unlicense / public domain alternatives
        list.add(lp("This is free and unencumbered software released into the public domain", "public-domain", (t, m) -> "Unlicense", flags));

        return list;
    }

    private static LicensePattern lp(String regex, String license,
                                      BiFunction<String, Matcher, String> getDetail,
                                      int flags) {
        try {
            Pattern p = Pattern.compile(regex, flags);
            return new LicensePattern(p, license, getDetail);
        } catch (PatternSyntaxException e) {
            // fall back to a no-match pattern
            return new LicensePattern(Pattern.compile("\\A(?!x)x"), license, getDetail);
        }
    }

    /**
     * Scan text for known licenses using a parallel thread pool.
     * Each license pattern is matched concurrently for improved throughput
     * on multi-core systems when processing large files.
     *
     * @return list of detected license identifiers.
     */
    public static List<String> findLicenses(String text) {
        Map<String, String> found = new ConcurrentHashMap<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE)) {
            List<Future<?>> futures = new ArrayList<>();

            for (LicensePattern lp : LICENSES) {
                futures.add(executor.submit(() -> {
                    // Skip if we already found this license family
                    if (found.containsKey(lp.license())) return;

                    Matcher m = lp.pattern().matcher(text);
                    if (!m.find()) return;

                    String licId;
                    if (lp.getDetail() != null) {
                        licId = lp.getDetail().apply(text, m);
                    } else {
                        licId = lp.license();
                    }
                    if (licId != null && !licId.isEmpty()) {
                        found.put(lp.license(), licId);
                    }
                }));
            }

            // Wait for all tasks to complete
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    // Log and continue — a single pattern failure shouldn't break the scan
                    System.err.println("License pattern match failed: " + e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return new ArrayList<>(found.values());
    }

    private static String parseCCBY(String text) {
        StringBuilder sb = new StringBuilder("CC-BY");
        if (Pattern.compile("NonCommercial|\\bNC\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) sb.append("-NC");
        if (Pattern.compile("ShareAlike|\\bSA\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) sb.append("-SA");
        if (Pattern.compile("NoDerivatives|NoDerivs|\\bND\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) sb.append("-ND");
        Matcher ver = Pattern.compile("licenses/by[^/]*/([\\d]+\\.[\\d]+)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (ver.find()) sb.append("-").append(ver.group(1));
        return sb.toString();
    }

    private static String parseZPL(String text) {
        Matcher m = Pattern.compile("Zope Public License(?:\\s*\\(ZPL\\))?[,;.\\s]+(?:Version|v)?\\s*([\\d.]+?)[^\\d.]",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return "ZPL-" + m.group(1).replaceAll("\\.0$", "");
        return "ZPL";
    }

    private static String replaceSpdx(String id) {
        Map<String, String> replacements = Map.of(
             "MIT", "Expat",
             "CC0-1.0", "CC0",
             "GPL-2.0-only", "GPL-2",
             "GPL-2.0-or-later", "GPL-2+",
             "GPL-3.0-only", "GPL-3",
             "GPL-3.0-or-later", "GPL-3+",
             "LGPL-2.1-only", "LGPL-2.1",
             "LGPL-2.1-or-later", "LGPL-2.1+",
             "LGPL-3.0-only", "LGPL-3",
             "LGPL-3.0-or-later", "LGPL-3+"
        );
        String result = replacements.getOrDefault(id, id);
        // General rules
        result = result.replace("-only", "").replace("-or-later", "+");
        result = result.replaceAll("\\.0(?![.\\d])", "");
        return result;
    }
}
