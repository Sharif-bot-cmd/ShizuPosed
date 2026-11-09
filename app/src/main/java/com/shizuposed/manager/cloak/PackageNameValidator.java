package com.shizuposed.manager.cloak;

import java.util.Random;
import java.util.regex.Pattern;

/**
 * PackageNameValidator
 *
 * Validates and generates the package name and launcher label
 * for the Hide ShizuPosed app flow.
 *
 * The package-name regex is the same one LSPatch's cloak flow
 * uses: a dot-separated list of identifiers, each starting with
 * a letter, using only [A-Za-z0-9_]. This is what Android's
 * PackageParser accepts, and it's what aapt2 emits.
 *
 * The label validator is deliberately loose: 2-30 characters,
 * no angle brackets. The label is what shows in the launcher,
 * not a resource name, so the only hard requirements are length
 * and "doesn't break XML".
 */
public final class PackageNameValidator {

    private static final Pattern PACKAGE_NAME_PATTERN =
        Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");

    private PackageNameValidator() {}

    public static boolean isValidPackageName(String s) {
        if (s == null) return false;
        if (s.length() < 3 || s.length() > 255) return false;
        return PACKAGE_NAME_PATTERN.matcher(s).matches();
    }

    public static boolean isValidLabel(String s) {
        if (s == null) return false;
        if (s.length() < 2 || s.length() > 30) return false;
        if (s.contains("<") || s.contains(">")) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;
        }
        return true;
    }

    /**
     * Generate a package name of the form
     * <letter>.<5-8 letters>.<8-12 letters>, e.g.
     * "h.sghdt.kihyturd". This is the same format the stealth
     * build uses for its scrambled package name.
     */
    public static String generateRandomPackageName() {
        Random r = new Random();
        char first = (char) ('a' + r.nextInt(26));

        StringBuilder second = new StringBuilder();
        int secondLen = 5 + r.nextInt(4);
        for (int i = 0; i < secondLen; i++) {
            second.append((char) ('a' + r.nextInt(26)));
        }

        StringBuilder third = new StringBuilder();
        int thirdLen = 8 + r.nextInt(5);
        for (int i = 0; i < thirdLen; i++) {
            third.append((char) ('a' + r.nextInt(26)));
        }

        return first + "." + second + "." + third;
    }

    /**
     * Generate a plausible-looking launcher label, e.g.
     * "Sync Service" or "Cloud Helper". The stealth build uses
     * "Sync Service" by default; the generator here produces
     * variety so multiple cloaked installs on the same device
     * don't all share a label.
     */
    public static String generateRandomLabel() {
        String[] adjectives = {
            "Sync", "Account", "Calendar", "Backup",
            "Cloud", "Media", "Contact", "File"
        };
        String[] nouns = {
            "Service", "Manager", "Helper", "Provider",
            "Agent", "Bridge", "Sync", "Tool"
        };
        Random r = new Random();
        return adjectives[r.nextInt(adjectives.length)]
             + " " + nouns[r.nextInt(nouns.length)];
    }
}