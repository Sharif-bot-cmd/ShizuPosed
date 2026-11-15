package com.shizuposed.manager.cloak;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.security.SecureRandom;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * PackageNameValidator
 *
 * Validates and generates the package name for the Hide
 * ShizuPosed app flow.
 *
 * TWO GENERATORS, TWO USES
 * ------------------------
 * generateSameLengthPackageName(ctx, length) is the cloak path.
 * It produces a name of EXACTLY the requested length, because
 * the cloak patches the manifest in place and a same-length
 * name avoids rebuilding the AXML string pool. Call this from
 * ManagerCloakFlow.
 *
 * generateRandomPackageName() is the pre-built-flavor path. It
 * produces a variable-length name (14-23 chars), which is what
 * a separately built stealth APK would use. It is NOT usable
 * for the in-place cloak; keep it only if you still ship a
 * pre-built distribution variant.
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

    private static final String TAG = "PackageNameValidator";

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

    // ═════════════════════════════════════════════════════════════
    // SAME-LENGTH GENERATOR — the cloak path
    // ═════════════════════════════════════════════════════════════

    /**
     * Generate a package name of exactly {@code length} characters
     * that matches Android's package-name grammar and does not
     * collide with an installed package.
     *
     * Shape: {@code <letter>.<mid>.<last>}, where
     * {@code mid + last == length - 3} (one char for the first
     * letter, two chars for the two dots). For a 22-char target
     * that is 1 + 1 + 9 + 1 + 10, e.g. "x.abcdefghi.jklmnopqrst".
     *
     * @param ctx    context for the collision check
     * @param length the required length (must be >= 5)
     * @return a non-colliding package name of exactly {@code length}
     * @throws IllegalArgumentException if length < 5
     * @throws IllegalStateException if 64 attempts all collide
     */
    public static String generateSameLengthPackageName(Context ctx, int length) {
        if (length < 5) {
            throw new IllegalArgumentException("length must be >= 5");
        }
        SecureRandom r = new SecureRandom();

        for (int attempt = 0; attempt < 64; attempt++) {
            StringBuilder sb = new StringBuilder(length);
            sb.append((char) ('a' + r.nextInt(26)));
            sb.append('.');

            // Total remaining after first letter and first dot:
            // length - 2. We still need one more dot, so split
            // (length - 2 - 1) between mid and last.
            int remaining = length - 2;
            int midLen = Math.max(2, (remaining - 1) / 2);
            int lastLen = remaining - 1 - midLen;
            if (lastLen < 1) lastLen = 1;

            for (int i = 0; i < midLen; i++) {
                sb.append((char) ('a' + r.nextInt(26)));
            }
            sb.append('.');
            for (int i = 0; i < lastLen; i++) {
                sb.append((char) ('a' + r.nextInt(26)));
            }

            String candidate = sb.toString();
            if (candidate.length() != length) {
                Log.d(TAG, "candidate " + candidate + " length="
                    + candidate.length() + " expected=" + length
                    + " — retrying");
                continue;
            }
            if (!isValidPackageName(candidate)) continue;
            if (isPackageInstalled(ctx, candidate)) continue;
            return candidate;
        }
        throw new IllegalStateException(
            "Could not generate a non-colliding " + length
            + "-char package name");
    }

    /**
     * Validate a user-supplied package name against the
     * same-length constraint. Used by the Hide dialog when the
     * user types their own name instead of accepting the
     * auto-generated one.
     */
    public static boolean isValidSameLengthPackageName(String s, int requiredLength) {
        return isValidPackageName(s) && s.length() == requiredLength;
    }

    private static boolean isPackageInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // VARIABLE-LENGTH GENERATOR — pre-built flavor path only
    // ═════════════════════════════════════════════════════════════

    /**
     * Generate a package name of the form
     * {@code <letter>.<5-8 letters>.<8-12 letters>}, e.g.
     * "h.sghdt.kihyturd".
     *
     * This is the same format the old stealth build used. It is
     * NOT usable for the in-place cloak, because the result is
     * 14-23 chars, not the 22 the manifest patch requires. Keep
     * it only if you still ship a separately built distribution
     * variant; the runtime cloak uses
     * generateSameLengthPackageName.
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
     * "Sync Service" or "Cloud Helper".
     *
     * NOTE: the launcher label is a string resource in
     * resources.arsc, not a manifest attribute. The in-place
     * cloak patches AXML, not ARSC, so it cannot change the
     * label per install. This generator is only useful if you
     * later add ARSC patching, or if you expose it as a
     * build-time resValue option.
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