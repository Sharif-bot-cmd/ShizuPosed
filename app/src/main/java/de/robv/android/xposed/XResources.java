package de.robv.android.xposed;

import android.content.res.Resources;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Resource replacement façade.
 *
 * Modules call setReplacement(...) on this object from
 * handleInitPackageResources. Replacements are stored in a map, and
 * the framework installs hooks on Resources methods that consult the
 * map first.
 *
 * The map is keyed by resource ID. A single replacement per ID per
 * package is supported; last write wins.
 */
public final class XResources {

    /** The original Resources object for the target package. */
    private final Resources resources;

    /** The package these resources belong to. */
    private final String packageName;

    /** id → replacement value. */
    private final ConcurrentHashMap<Integer, Object> replacements =
        new ConcurrentHashMap<>();

    /** name → replacement value (for by-name lookups). */
    private final ConcurrentHashMap<String, Object> nameReplacements =
        new ConcurrentHashMap<>();

    public XResources(Resources resources, String packageName) {
        this.resources = resources;
        this.packageName = packageName;
    }

    public Resources getResources() {
        return resources;
    }

    public String getPackageName() {
        return packageName;
    }

    // ═════════════════════════════════════════════════════════════
    // PUBLIC API — what modules call
    // ═════════════════════════════════════════════════════════════

    public void setReplacement(int resId, Object replacement) {
        if (replacement == null) {
            replacements.remove(resId);
        } else {
            replacements.put(resId, replacement);
        }
    }

    public void setReplacement(String resourceName, Object replacement) {
        if (replacement == null) {
            nameReplacements.remove(resourceName);
        } else {
            nameReplacements.put(resourceName, replacement);
        }
    }

    public Object getReplacement(int resId) {
        return replacements.get(resId);
    }

    public Object getReplacement(String resourceName) {
        return nameReplacements.get(resourceName);
    }

    public boolean hasReplacement(int resId) {
        return replacements.containsKey(resId);
    }

    /** Snapshot for debugging. */
    public int replacementCount() {
        return replacements.size() + nameReplacements.size();
    }
}