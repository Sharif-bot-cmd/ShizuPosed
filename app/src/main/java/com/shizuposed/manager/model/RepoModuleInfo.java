package com.shizuposed.manager.model;

import java.io.Serializable;

/**
 * A module entry as shown in the Repo tab.
 *
 * Wraps the ModuleInfo the loader already has, plus metadata
 * extracted from the APK: description, author, homepage URL, and
 * the module's bundled README.
 *
 * The README is held as a string. It's read once from the APK on
 * first display and cached in memory. Not persisted: an APK update
 * can change the README, and re-reading is cheap.
 */
public class RepoModuleInfo implements Serializable {

    /** The underlying module record. Never null. */
    public ModuleInfo module;

    /** Human-readable description. May be null. */
    public String description;

    /** Author string. May be null. */
    public String author;

    /** Homepage / source URL. May be null. */
    public String homepage;

    /** Support / issues URL. May be null. */
    public String supportUrl;

    /** Raw Markdown of the README, or null if none was found. */
    public String readme;

    /** Where the README was found, e.g. "assets/README.md". Null if none. */
    public String readmeSource;

    public RepoModuleInfo(ModuleInfo module) {
        this.module = module;
    }

    public String getDisplayName() {
        if (module == null) return "";
        if (module.name != null && !module.name.isEmpty()) return module.name;
        return module.packageName != null ? module.packageName : "";
    }

    public boolean hasReadme() {
        return readme != null && !readme.isEmpty();
    }

    public boolean hasLinks() {
        return (homepage != null && !homepage.isEmpty())
            || (supportUrl != null && !supportUrl.isEmpty());
    }
}