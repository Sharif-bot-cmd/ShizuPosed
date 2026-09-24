package com.shizuposed.manager.ui;

import java.util.Set;

/**
 * ScopeProvider
 *
 * Small abstraction over the two sources of module scope. Normal
 * modules read/write through ModuleLoader; XStealth reads/writes
 * through XStealthPrefs. The scope editor only needs to know how to
 * load the current set and save a new one.
 *
 * IMPLEMENTATIONS
 * ---------------
 *   • ModuleScopeProvider   — backed by ModuleLoader, for normal
 *                             modules. Empty scope means "nothing
 *                             selected," which is the historical
 *                             behavior for those modules.
 *   • XStealthScopeProvider — backed by XStealthPrefs. Empty scope
 *                             means "all apps," which is XStealth's
 *                             historical behavior.
 *
 * The editor's UI label reflects which one is in play. For normal
 * modules an empty scope says "No apps scoped." For XStealth it
 * says "All apps (default)."
 */
public interface ScopeProvider {

    /** Module display name, for the dialog title. */
    String getDisplayName();

    /** Package name of the module. Used to exclude it from the list. */
    String getModulePackageName();

    /** Current scope. Empty set has provider-specific meaning. */
    Set<String> getScope();

    /** Persist a new scope. */
    void setScope(Set<String> scope);

    /**
     * True if this provider treats an empty scope as "all apps."
     * The editor uses this to pick the right empty-state label and
     * the right default when the dialog opens.
     */
    boolean emptyMeansAllApps();
}