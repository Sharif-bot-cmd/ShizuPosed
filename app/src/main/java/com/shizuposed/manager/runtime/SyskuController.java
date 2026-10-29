package com.shizuposed.manager.runtime;

import android.content.Context;
import android.content.SharedPreferences;

import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.stealth.SyskuNative;
import com.shizuposed.manager.utils.Logger;

import org.json.JSONObject;

/**
 * SyskuController
 *
 * Manager-side controller for the optional Sysku backend.
 *
 * OWNERSHIP
 * ---------
 * This controller — driven by the Settings tab — is the ONLY
 * writer of the user's on/off choice. The Sysku ADB module reads
 * that choice and reports whether service.sh is running; it does
 * not set the choice.
 *
 * STATE DERIVATION
 * ----------------
 * getState() combines two signals:
 *
 *   • <shell-base>/sysku/state.json   — the user's choice
 *   • <shell-base>/sysku/heartbeat.json — service.sh liveness
 *
 * Outcomes:
 *
 *   state.json              libsysku.so    heartbeat    State
 *   ─────────────────────────────────────────────────────────────
 *   enabled: false          any            any          DISABLED
 *   enabled: true           missing        any          NOT_INSTALLED
 *   enabled: true           present        fresh        ENABLED
 *   enabled: true           present        stale/absent ENABLED_MODULE_STOPPED
 *   enabled: true           present        fresh, but   FAILED
 *                                          last bootstrap
 *                                          load returned 0
 *
 * ENABLED_MODULE_STOPPED is the "user wants Sysku on, but the
 * ADB module's service.sh isn't running" case. It's a warning
 * state: Sysku's functionality is intact (the framework loads
 * libsysku.so based on state.json + isSyskuGateLive() in
 * XposedHook, which now reads heartbeat freshness), but the
 * status tab is telling the user the module isn't contributing
 * its heartbeat loop.
 *
 * The state used to distinguish "gate live" vs "gate absent" and
 * reported DISABLED for the latter. That made "Enabled" depend on
 * a file the module had to actively refresh — so a module crash
 * silently reported Sysku as disabled. The new model separates
 * intent (state.json, owned by ShizuPosed) from liveness
 * (heartbeat, owned by service.sh) and surfaces both to the user
 * as a single readable label.
 */
public final class SyskuController {

    public enum State {
        NOT_INSTALLED,
        DISABLED,
        ENABLED_MODULE_STOPPED,
        FAILED,
        ENABLED
    }

    private static final String PREFS = "shizuposed_settings";
    private static final String KEY_ENABLED = "sysku_enabled";
    private static final String KEY_LAST_LOAD_OK = "sysku_last_load_ok";

    /** Heartbeat freshness — service.sh writes every 60 s. */
    private static final long HEARTBEAT_FRESH_MS = 2 * 60 * 1000L;
    private static final long HEARTBEAT_STALE_MS = 5 * 60 * 1000L;

    private SyskuController() {}

    // ═════════════════════════════════════════════════════════════
    // PUBLIC API — what SettingsFragment calls
    // ═════════════════════════════════════════════════════════════

    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return false;
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false);
    }

    /**
     * Set the user's choice.
     *
     * Writes SharedPreferences, state.json, and (for backward
     * compatibility with existing XposedHook builds) gate.live.
     * The gate.live write is a no-op when the framework side has
     * been updated to read heartbeat freshness instead.
     */
    public static void setEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply();

        writeStateToShell(ctx, enabled);
    }

    public static void reportLoadResult(Context ctx, boolean ok) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LAST_LOAD_OK, ok).apply();
    }

    public static void reportLoadResultFromShell(boolean ok) {
        try {
            ShizukuHelper sh = ShizukuHelper.getInstance(null);
            if (sh == null || !sh.isAvailable() || !sh.isAuthorized()) {
                return;
            }

            String base = ShizuPosedService.getResolvedShellBase();
            if (base == null) return;

            String readCmd = "cat '" + base + "/sysku/state.json' 2>/dev/null";
            com.shizuposed.manager.utils.ShellUtils.CommandResult r =
                sh.executeCommand(readCmd);

            String existing = (r != null && r.isSuccess()
                && r.getStdoutString() != null)
                ? r.getStdoutString().trim()
                : "";

            String merged;
            if (existing.isEmpty() || !existing.startsWith("{")) {
                merged = "{\"schema\":1,\"last_load_ok\":"
                    + (ok ? "true" : "false")
                    + ",\"updated_at\":" + System.currentTimeMillis() + "}";
            } else {
                String replacement = "\"last_load_ok\":"
                    + (ok ? "true" : "false");
                if (existing.contains("\"last_load_ok\":")) {
                    merged = existing.replaceAll(
                        "\"last_load_ok\"\\s*:\\s*(true|false)",
                        replacement);
                } else {
                    int close = existing.lastIndexOf('}');
                    if (close < 0) close = existing.length();
                    merged = existing.substring(0, close).trim();
                    if (!merged.endsWith("{")) merged += ",";
                    merged += replacement + "}";
                }
            }

            String escaped = merged.replace("'", "'\\''");
            String writeCmd =
                "mkdir -p '" + base + "/sysku' && "
              + "printf '%s' '" + escaped + "' > '" + base + "/sysku/state.json.tmp' && "
              + "mv '" + base + "/sysku/state.json.tmp' '" + base + "/sysku/state.json'";
            sh.executeCommand(writeCmd);
        } catch (Throwable t) {
            // Best-effort from inside XposedHook.main().
        }
    }

    /**
     * Compute the state. Reads state.json (user's choice),
     * heartbeat.json (module liveness), and probes libsysku.so.
     * Up to 3 shell calls. Call from a background thread.
     *
     * Reconciliation: state.json's "enabled" field overrides
     * SharedPreferences when they disagree. This is what makes
     * the Settings toggle and state.json stay in sync — both are
     * written by this controller, so disagreement should only
     * happen transiently (between the SharedPreferences write and
     * the shell write completing).
     */
    public static State getState(Context ctx) {
        if (ctx == null) return State.NOT_INSTALLED;

        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean userEnabled = p.getBoolean(KEY_ENABLED, false);
        boolean lastLoadOk  = p.getBoolean(KEY_LAST_LOAD_OK, true);

        // Read state.json — the user's choice.
        StateJsonSnapshot snap = readStateJson(ctx);
        if (snap != null) {
            if (snap.enabled != null && snap.enabled != userEnabled) {
                p.edit().putBoolean(KEY_ENABLED, snap.enabled).apply();
                userEnabled = snap.enabled;
                Logger l = Logger.getInstance(ctx);
                if (l != null) {
                    l.i("SyskuController: reconciled enabled="
                        + userEnabled + " from state.json");
                }
            }
            if (snap.lastLoadOk != null && snap.lastLoadOk != lastLoadOk) {
                p.edit().putBoolean(KEY_LAST_LOAD_OK, snap.lastLoadOk).apply();
                lastLoadOk = snap.lastLoadOk;
            }
        }

        if (!userEnabled) return State.DISABLED;
        if (!isLibPresent(ctx)) return State.NOT_INSTALLED;

        // Heartbeat freshness = service.sh running.
        long hbAge = heartbeatAgeMs(ctx);
        boolean hbFresh = hbAge >= 0 && hbAge <= HEARTBEAT_STALE_MS;

        if (!hbFresh) return State.ENABLED_MODULE_STOPPED;
        if (!lastLoadOk) return State.FAILED;
        return State.ENABLED;
    }

    public static String describe(State s) {
        switch (s) {
            case ENABLED:                 return "Enabled";
            case ENABLED_MODULE_STOPPED:  return "Enabled (module not running)";
            case DISABLED:                return "Disabled";
            case NOT_INSTALLED:           return "Not installed";
            case FAILED:                  return "Enabled (load failed)";
            default:                      return "Unknown";
        }
    }

    public static int colorFor(State s) {
        switch (s) {
            case ENABLED:                 return 0xFF4CAF50;
            case ENABLED_MODULE_STOPPED:  return 0xFFFF9800;
            case DISABLED:                return 0xFF9E9E9E;
            case NOT_INSTALLED:           return 0xFF9E9E9E;
            case FAILED:                  return 0xFFF44336;
            default:                      return 0xFF9E9E9E;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // DIAGNOSTICS
    // ═════════════════════════════════════════════════════════════

    public static String getDiagnosticInfo(Context ctx) {
        if (ctx == null) return "Sysku: no context";

        StringBuilder sb = new StringBuilder();
        sb.append("=== Sysku ===\n");

        State st = getState(ctx);
        sb.append("State:       ").append(describe(st)).append('\n');
        sb.append("Enabled:     ").append(isEnabled(ctx)).append('\n');
        sb.append("Lib present: ").append(isLibPresent(ctx)).append('\n');

        long hbAge = heartbeatAgeMs(ctx);
        if (hbAge < 0) {
            sb.append("Heartbeat:   absent (service.sh never ran)\n");
        } else {
            String tag = hbAge < HEARTBEAT_FRESH_MS ? "fresh"
                       : hbAge < HEARTBEAT_STALE_MS ? "aging"
                       : "stale";
            sb.append("Heartbeat:   ")
              .append(hbAge / 1000).append(" s ago (").append(tag).append(")\n");
        }

        sb.append('\n');

        if (SyskuNative.isLoaded()) {
            sb.append("=== libsysku.so (in this process) ===\n");
            try {
                sb.append(SyskuNative.nativeDescribe()).append('\n');
            } catch (Throwable t) {
                sb.append("describe failed: ").append(t.getMessage())
                  .append('\n');
            }
            sb.append("Calls seen:    ")
              .append(SyskuNative.nativeGetCallsSeen()).append('\n');
            sb.append("Calls blocked: ")
              .append(SyskuNative.nativeGetCallsBlocked()).append('\n');
            sb.append("Dents seen:    ")
              .append(SyskuNative.nativeGetDentsSeen()).append('\n');
            sb.append("Dents dropped: ")
              .append(SyskuNative.nativeGetDentsDropped()).append('\n');
        } else {
            sb.append("=== libsysku.so (in this process) ===\n");
            sb.append("Not loaded in the manager process. This is\n");
            sb.append("normal — libsysku.so loads only inside\n");
            sb.append("bootstrapped target apps. Per-target counters\n");
            sb.append("appear in that target's launch.log.\n");
        }

        sb.append('\n');

        String base = ShizuPosedService.getResolvedShellBase();
        sb.append("=== Files ===\n");
        sb.append("Shell base:  ")
          .append(base != null ? base : "(unresolved)").append('\n');
        if (base != null) {
            sb.append("state.json:  ").append(base)
              .append("/sysku/state.json\n");
            sb.append("heartbeat:   ").append(base)
              .append("/sysku/heartbeat.json\n");
            sb.append("lib:         ").append(base)
              .append("/libs/libsysku.so\n");
        }

        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════
    // MANAGER → SHELL  (write state.json / gate.live)
    // ═════════════════════════════════════════════════════════════

    private static void writeStateToShell(Context ctx, boolean enabled) {
        try {
            ShizukuHelper sh = ShizukuHelper.getInstance(ctx);
            if (sh == null || !sh.isAvailable() || !sh.isAuthorized()) {
                Logger.getInstance(ctx).d(
                    "writeStateToShell: Shizuku not ready, skipping");
                return;
            }

            String base = ShizuPosedService.getResolvedShellBase();
            if (base == null) return;

            String readCmd = "cat '" + base + "/sysku/state.json' 2>/dev/null";
            com.shizuposed.manager.utils.ShellUtils.CommandResult rr =
                sh.executeCommand(readCmd);
            String existing = (rr != null && rr.isSuccess()
                && rr.getStdoutString() != null)
                ? rr.getStdoutString().trim()
                : "";

            JSONObject o;
            try {
                o = (existing.startsWith("{"))
                    ? new JSONObject(existing)
                    : new JSONObject();
            } catch (Throwable t) {
                o = new JSONObject();
            }

            o.put("schema", 1);
            o.put("enabled", enabled);
            o.put("preferred", enabled);
            o.put("updated_at", System.currentTimeMillis());
            o.put("updated_by", "manager");
            if (!o.has("min_sysku_version")) {
                o.put("min_sysku_version", "1.0.0");
            }

            String json = o.toString();
            String escaped = json.replace("'", "'\\''");

            String cmd =
                "mkdir -p '" + base + "/sysku' && "
              + "printf '%s' '" + escaped + "' > '" + base + "/sysku/state.json.tmp' && "
              + "mv '" + base + "/sysku/state.json.tmp' '" + base + "/sysku/state.json'";

            com.shizuposed.manager.utils.ShellUtils.CommandResult r =
                sh.executeCommand(cmd);
            if (r != null && r.isSuccess()) {
                Logger.getInstance(ctx).i(
                    "Sysku state written: enabled=" + enabled);
            } else {
                Logger.getInstance(ctx).w(
                    "Sysku state write returned non-success: "
                    + (r != null ? r.getStderrString() : "null"));
            }
        } catch (Throwable t) {
            Logger.getInstance(ctx).w(
                "writeStateToShell failed: " + t.getMessage());
        }
    }

    /**
     * Write or remove <shell-base>/sysku/gate.live.
     *
     * Kept for backward compatibility with XposedHook builds that
     * read gate.live mtime to decide whether to load libsysku.so.
     * Newer XposedHook builds read heartbeat.json freshness instead
     * — but writing gate.live anyway means both old and new builds
     * agree on the runtime state.
     */
    private static void writeGateToShell(Context ctx, boolean enabled) {
        try {
            ShizukuHelper sh = ShizukuHelper.getInstance(ctx);
            if (sh == null || !sh.isAvailable() || !sh.isAuthorized()) {
                Logger.getInstance(ctx).d(
                    "writeGateToShell: Shizuku not ready, skipping");
                return;
            }

            String base = ShizuPosedService.getResolvedShellBase();
            if (base == null) return;

            if (!enabled) {
                String cmd = "rm -f '" + base + "/sysku/gate.live'";
                sh.executeCommand(cmd);
                return;
            }

            JSONObject o = new JSONObject();
            o.put("schema", 1);
            o.put("ts", System.currentTimeMillis());
            o.put("version", "1.0");
            o.put("pid", 0);

            String json = o.toString();
            String escaped = json.replace("'", "'\\''");

            String cmd =
                "mkdir -p '" + base + "/sysku' && "
              + "printf '%s' '" + escaped + "' > '" + base + "/sysku/gate.live.tmp' && "
              + "mv '" + base + "/sysku/gate.live.tmp' '" + base + "/sysku/gate.live' && "
              + "chmod 0644 '" + base + "/sysku/gate.live'";

            sh.executeCommand(cmd);
        } catch (Throwable t) {
            Logger.getInstance(ctx).w(
                "writeGateToShell failed: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SHELL → MANAGER  (read state.json / heartbeat.json)
    // ═════════════════════════════════════════════════════════════

    private static final class StateJsonSnapshot {
        Boolean enabled;
        Boolean lastLoadOk;
    }

    private static StateJsonSnapshot readStateJson(Context ctx) {
        try {
            ShizukuHelper sh = ShizukuHelper.getInstance(ctx);
            if (sh == null || !sh.isAvailable() || !sh.isAuthorized()) {
                return null;
            }

            String base = ShizuPosedService.getResolvedShellBase();
            if (base == null) return null;

            String cmd = "cat '" + base + "/sysku/state.json' 2>/dev/null";
            com.shizuposed.manager.utils.ShellUtils.CommandResult r =
                sh.executeCommand(cmd);
            if (r == null || !r.isSuccess() || r.getStdoutString() == null) {
                return null;
            }

            String json = r.getStdoutString().trim();
            if (json.isEmpty() || !json.startsWith("{")) return null;

            JSONObject o = new JSONObject(json);
            if (o.optInt("schema", 0) != 1) return null;

            StateJsonSnapshot s = new StateJsonSnapshot();
            if (o.has("enabled"))       s.enabled    = o.optBoolean("enabled");
            if (o.has("last_load_ok"))  s.lastLoadOk = o.optBoolean("last_load_ok");
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static long heartbeatAgeMs(Context ctx) {
        try {
            ShizukuHelper sh = ShizukuHelper.getInstance(ctx);
            if (sh == null || !sh.isAvailable() || !sh.isAuthorized()) return -1;

            String base = ShizuPosedService.getResolvedShellBase();
            if (base == null) return -1;

            String cmd = "cat '" + base + "/sysku/heartbeat.json' 2>/dev/null";
            com.shizuposed.manager.utils.ShellUtils.CommandResult r =
                sh.executeCommand(cmd);
            if (r == null || !r.isSuccess() || r.getStdoutString() == null) return -1;

            String json = r.getStdoutString().trim();
            if (json.isEmpty()) return -1;

            JSONObject o = new JSONObject(json);
            if (o.optInt("schema", 0) != 1) return -1;

            long ts = o.optLong("ts", 0);
            if (ts <= 0) return -1;

            return System.currentTimeMillis() - ts;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean isLibPresent(Context ctx) {
        try {
            ShizukuHelper sh = ShizukuHelper.getInstance(ctx);
            if (sh == null || !sh.isAvailable() || !sh.isAuthorized()) return false;

            String base = ShizuPosedService.getResolvedShellBase();
            if (base == null) return false;

            String cmd = "test -f '" + base + "/libs/libsysku.so' "
                       + "&& echo YES || echo NO";
            com.shizuposed.manager.utils.ShellUtils.CommandResult r =
                sh.executeCommand(cmd);
            return r != null && r.isSuccess()
                && r.getStdoutString() != null
                && r.getStdoutString().trim().equals("YES");
        } catch (Throwable t) {
            return false;
        }
    }
}