package de.robv.android.xposed;

import android.content.SharedPreferences;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Compatibility shim for the legacy XSharedPreferences API.
 *
 * Older modules use this to read their own preferences from the hook
 * side. The class bypasses the usual Context-based access and reads
 * the module's own shared_prefs XML file directly from its data
 * directory. It is read-only: writes throw UnsupportedOperationException.
 *
 * The shell-spawned process runs as shell uid, which has read access
 * to /data/user/0/<pkg>/shared_prefs/ on most Android versions. On
 * devices where this is blocked by SELinux, getX() methods return
 * their default values and the module sees "no preferences set"
 * rather than crashing.
 *
 * Construction forms supported:
 *   new XSharedPreferences(String packageName)
 *   new XSharedPreferences(String packageName, String prefsName)
 *   new XSharedPreferences(File prefsFile)
 *
 * After the file on disk changes, call reload() to re-read it.
 */
public class XSharedPreferences implements SharedPreferences {

    private final File prefsFile;
    private final Map<String, Object> cache = new HashMap<>();

    // ═════════════════════════════════════════════════════════════
    // CONSTRUCTORS
    // ═════════════════════════════════════════════════════════════

    /**
     * Standard form: reads <packageName>'s default preferences file,
     * which is named <packageName>_preferences.xml.
     */
    public XSharedPreferences(String packageName) {
        this(packageName, packageName + "_preferences");
    }

    /**
     * Named preferences file: reads <packageName>/shared_prefs/<prefsName>.xml.
     */
    public XSharedPreferences(String packageName, String prefsName) {
        this(resolvePrefsFile(packageName, prefsName));
    }

    /**
     * Direct file form: the caller already knows the full path.
     */
    public XSharedPreferences(File prefsFile) {
        this.prefsFile = prefsFile;
        reload();
    }

    private static File resolvePrefsFile(String packageName, String prefsName) {
        // Android 4.2+ uses /data/user/0/<pkg>; older devices use
        // /data/data/<pkg>. Try both — whichever exists wins.
        File dataDir = new File("/data/user/0/" + packageName);
        if (!dataDir.exists()) {
            dataDir = new File("/data/data/" + packageName);
        }
        File prefsDir = new File(dataDir, "shared_prefs");
        return new File(prefsDir, prefsName + ".xml");
    }

    // ═════════════════════════════════════════════════════════════
    // RELOAD
    // ═════════════════════════════════════════════════════════════

    /**
     * Re-read the preferences file into the in-memory cache. Safe to
     * call at any time. If the file is missing or unparseable, the
     * cache becomes empty and getters return their defaults.
     */
    public void reload() {
        cache.clear();
        if (prefsFile == null || !prefsFile.exists()) {
            return;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(prefsFile);
            NodeList children = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) n;
                String key = e.getAttribute("name");
                String type = e.getTagName();
                String value = e.getAttribute("value");
                if (key == null || key.isEmpty()) continue;
                switch (type) {
                    case "boolean":
                        cache.put(key, "true".equals(value));
                        break;
                    case "int":
                        cache.put(key, Integer.parseInt(value));
                        break;
                    case "long":
                        cache.put(key, Long.parseLong(value));
                        break;
                    case "float":
                        cache.put(key, Float.parseFloat(value));
                        break;
                    case "string":
                        cache.put(key, value);
                        break;
                    case "set": {
                        Set<String> set = new HashSet<>();
                        NodeList items = e.getElementsByTagName("string");
                        for (int j = 0; j < items.getLength(); j++) {
                            Node item = items.item(j);
                            String text = item.getTextContent();
                            if (text != null) set.add(text);
                        }
                        cache.put(key, set);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // File missing, unreadable, or malformed — treat as empty.
        }
    }

    // ═════════════════════════════════════════════════════════════
    // EXTRA API — LSPosed provides these
    // ═════════════════════════════════════════════════════════════

    public boolean isReadOnly() {
        return true;
    }

    public File getFile() {
        return prefsFile;
    }

    /** No-op: the file is re-read on demand, not watched. */
    public void startSync() {}

    /** No-op. */
    public void stopSync() {}

    // ═════════════════════════════════════════════════════════════
    // SharedPreferences INTERFACE — READ METHODS
    // ═════════════════════════════════════════════════════════════

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(cache);
    }

    @Override
    public String getString(String key, String defValue) {
        Object v = cache.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        Object v = cache.get(key);
        if (v instanceof Set) return new HashSet<>((Set<String>) v);
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object v = cache.get(key);
        return v instanceof Integer ? (Integer) v : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object v = cache.get(key);
        return v instanceof Long ? (Long) v : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object v = cache.get(key);
        return v instanceof Float ? (Float) v : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object v = cache.get(key);
        return v instanceof Boolean ? (Boolean) v : defValue;
    }

    @Override
    public boolean contains(String key) {
        return cache.containsKey(key);
    }

    // ═════════════════════════════════════════════════════════════
    // SharedPreferences INTERFACE — WRITE METHODS
    // ═════════════════════════════════════════════════════════════

    @Override
    public Editor edit() {
        throw new UnsupportedOperationException(
            "XSharedPreferences is read-only; write from the module's own process");
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
        // No-op: the file is re-read on reload(), not watched.
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
        // No-op.
    }
}