package com.fiendfyre.AdHell2.utils;

/**
 * Created by Matt on 19/01/2018.
 */

import java.util.regex.Pattern;

public final class BlockUrlPatternsMatch {

    private static final String WILDCARD_PATTERN = "(?i)^([*])([A-Z0-9-_.]+)$|^([A-Z0-9-_.]+)([*])$|^([*])([A-Z0-9-_.]+)([*])$";
    private static final Pattern wilcard_r = Pattern.compile(WILDCARD_PATTERN);

    private static final String DOMAIN_PATTERN = "(?i)(?=^.{4,253}$)(^((?!-)[a-z0-9-]{1,63}(?<!-)\\.)+[a-z]{2,63}$)";
    private static final Pattern domain_r = Pattern.compile(DOMAIN_PATTERN);

    private BlockUrlPatternsMatch() {
    }

    private static boolean wildcardValid (String domain) {
        return wilcard_r.matcher(domain).matches();
    }

    private static boolean domainValid (String domain){
        return domain_r.matcher(domain).matches();
    }

    private static String normalizeUrl(String url) {
        return url

        // Remove 'deadzone' - We only want the domain
        .replace("127.0.0.1", "")
        .replace("0.0.0.0", "")

        // Let's remove the unnecessary www, www1 etc.
        .replaceAll("^(www)([0-9]{0,3})?(\\.)", "");
    }

    public static boolean isUrlValid(String url) {
        url = normalizeUrl(url);
        if (url.contains("*")) {
            return BlockUrlPatternsMatch.wildcardValid(url);
        }
        return BlockUrlPatternsMatch.domainValid(url);
    }

    public static String getValidatedUrl(String url) {
        return (url.contains("*") ? "" : "*") + url;
    }

}