package com.shizuposed.manager.stealth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * XStealthRegistry
 *
 * Per-process record of which checks XStealth installed. Used for
 * diagnostics and for the status line shown in the manager's detail
 * sheet. Not synchronized across processes — each hooked process
 * has its own registry, which is correct.
 */
public final class XStealthRegistry {

    private static final List<String> sInstalled =
        new CopyOnWriteArrayList<>();

    private XStealthRegistry() {}

    public static void record(String checkName) {
        if (checkName == null) return;
        if (!sInstalled.contains(checkName)) {
            sInstalled.add(checkName);
        }
    }

    public static List<String> getInstalled() {
        return Collections.unmodifiableList(new ArrayList<>(sInstalled));
    }

    public static int count() {
        return sInstalled.size();
    }
}