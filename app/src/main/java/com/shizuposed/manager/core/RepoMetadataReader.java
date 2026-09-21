package com.shizuposed.manager.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.model.RepoModuleInfo;
import com.shizuposed.manager.utils.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads repo-style metadata from a module APK.
 *
 * Sources, in order of preference:
 *   • Description:  APK's ApplicationInfo.loadDescription(),
 *                   then assets/xposed_description, then
 *                   the first paragraph of the README.
 *   • Author:       assets/xposed_author, then APK's meta-data
 *                   "xposedmodule_author", then null.
 *   • Homepage:     assets/xposed_homepage, then meta-data
 *                   "xposedmodule_homepage", then null.
 *   • Support URL:  assets/xposed_support, then meta-data
 *                   "xposedmodule_support", then null.
 *   • README:       assets/README.md, assets/readme.md,
 *                   README.md at APK root, in that order.
 *
 * All lookups are best-effort. Missing metadata is null, not an
 * exception. A module that ships none of these still appears in the
 * Repo tab, just without those fields.
 */
public final class RepoMetadataReader {

    private static final String TAG = "RepoMetadataReader";

    private RepoMetadataReader() {}

    /** Read metadata + README for a module. Never returns null. */
    public static RepoModuleInfo read(Context context, ModuleInfo module) {
        RepoModuleInfo out = new RepoModuleInfo(module);
        if (module == null || module.packageName == null) return out;

        Logger logger = Logger.getInstance(context);

        // Description + author + links from ApplicationInfo.
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(module.packageName, 0);
            if (ai != null) {
                CharSequence desc = ai.loadDescription(pm);
                if (desc != null && desc.length() > 0) {
                    out.description = desc.toString();
                }
                if (ai.metaData != null) {
                    String author = ai.metaData.getString("xposedmodule_author");
                    if (author == null) author = ai.metaData.getString("xposed_author");
                    if (author != null && !author.isEmpty()) out.author = author;

                    String homepage = ai.metaData.getString("xposedmodule_homepage");
                    if (homepage == null) homepage = ai.metaData.getString("xposed_homepage");
                    if (homepage != null && !homepage.isEmpty()) out.homepage = homepage;

                    String support = ai.metaData.getString("xposedmodule_support");
                    if (support == null) support = ai.metaData.getString("xposed_support");
                    if (support != null && !support.isEmpty()) out.supportUrl = support;
                }
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.d(TAG + ": ApplicationInfo read failed for "
                    + module.packageName + ": " + t.getMessage());
            }
        }

        // APK-side metadata + README.
        String apkPath = module.apkPath;
        if (apkPath == null) {
            // Cached dex path is a copy of the APK; the APK itself
            // lives at the module's sourceDir. Try the loader.
            try {
                apkPath = ModuleLoader.getInstance(context)
                    .findModuleApkPath(module.packageName);
            } catch (Throwable ignored) {}
        }

        if (apkPath != null) {
            File apk = new File(apkPath);
            if (apk.exists()) {
                try (ZipFile zip = new ZipFile(apk)) {
                    // Metadata files
                    if (out.author == null) {
                        String a = readAssetLine(zip, "assets/xposed_author");
                        if (a != null) out.author = a;
                    }
                    if (out.homepage == null) {
                        String h = readAssetLine(zip, "assets/xposed_homepage");
                        if (h != null) out.homepage = h;
                    }
                    if (out.supportUrl == null) {
                        String s = readAssetLine(zip, "assets/xposed_support");
                        if (s != null) out.supportUrl = s;
                    }
                    if (out.description == null) {
                        String d = readAssetLine(zip, "assets/xposed_description");
                        if (d != null) out.description = d;
                    }

                    // README, in preference order.
                    String[] candidates = {
                        "assets/README.md",
                        "assets/readme.md",
                        "README.md",
                        "readme.md"
                    };
                    for (String name : candidates) {
                        String text = readAssetText(zip, name);
                        if (text != null && !text.isEmpty()) {
                            out.readme = text;
                            out.readmeSource = name;
                            break;
                        }
                    }
                } catch (Throwable t) {
                    if (logger != null) {
                        logger.d(TAG + ": APK read failed for "
                            + module.packageName + ": " + t.getMessage());
                    }
                }
            }
        }

        // Fallback: first paragraph of README as description.
        if (out.description == null && out.hasReadme()) {
            out.description = firstParagraph(out.readme);
        }

        return out;
    }

    // ── helpers ───────────────────────────────────────────────────

    private static String readAssetLine(ZipFile zip, String name) {
        try {
            ZipEntry e = zip.getEntry(name);
            if (e == null) return null;
            try (InputStream is = zip.getInputStream(e);
                 BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#")) return t;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String readAssetText(ZipFile zip, String name) {
        try {
            ZipEntry e = zip.getEntry(name);
            if (e == null) return null;
            StringBuilder sb = new StringBuilder();
            try (InputStream is = zip.getInputStream(e);
                 BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String firstParagraph(String md) {
        if (md == null) return null;
        String[] lines = md.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (started) break;
                continue;
            }
            // Skip headings and code fences.
            if (t.startsWith("#") || t.startsWith("```")) {
                if (!started) continue;
                else break;
            }
            if (started) sb.append(' ');
            sb.append(t);
            started = true;
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }
}