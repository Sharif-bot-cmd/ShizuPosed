package com.shizuposed.manager.cloak;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * In-place AXML string-pool patcher.
 *
 * Rewrites specific strings in the AXML string pool to another
 * string of the SAME LENGTH. Because the length is unchanged, no
 * offsets shift, no chunk sizes change, and no string-pool rebuild
 * is needed — the new bytes overwrite the old in place.
 *
 * WHAT IT PATCHES
 * ---------------
 * Exactly two categories of pool entries:
 *
 *   1. The exact package name: "com.shizuposed.manager".
 *      This is the value of <manifest package="...">.
 *
 *   2. Known applicationId-derived suffixes:
 *      "<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
 *      "<package>.androidx-startup"
 *      "<package>.fileprovider"
 *      "<package>.shizuku"
 *      "<package>.shizuku-watcher"
 *      "<package>.status"
 *
 *   These are merged into the manifest as
 *   ${applicationId}.<suffix> by AGP (or written that way in
 *   the source manifest), and they MUST be rewritten so the
 *   cloaked package doesn't collide with the original on
 *   install. The DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION one
 *   is what causes INSTALL_FAILED_DUPLICATE_PERMISSION if left
 *   unpatched: it is owned by the first package that declares
 *   it, and that is still com.shizuposed.manager at install time.
 *
 * WHAT IT DOES NOT PATCH
 * ----------------------
 * Java class names that also start with the package name:
 *
 *   "<package>.MainActivity"
 *   "<package>.ShizuPosedManagerApp"
 *   "<package>.receiver.BootReceiver"
 *   "<package>.service.ShizuPosedService"
 *   "<package>.status.ModuleStatusProvider"
 *   "<package>.watcher.ShizukuBinderWatcher"
 *
 *   Those are defined by the dex under the ORIGINAL package
 *   name. Rewriting them in the manifest would point activities
 *   and services at classes that don't exist, and the cloaked
 *   app would install but crash on launch.
 *
 *   A full "starts with oldName + '.'" scan would catch both
 *   the suffixes above and the class names. The whitelist
 *   approach below is deliberate: it is safer to miss a future
 *   suffix (caught by a clear install error, one line to add)
 *   than to rewrite a class name (silent runtime crash).
 *
 * STRING-POOL ENCODING
 * --------------------
 * The pool can be UTF-8 or UTF-16 (flags bit 0x100), and the
 * two encodings have DIFFERENT length prefixes:
 *
 *   UTF-8:  char count (1-2 bytes, 7-bit chunks)
 *           + byte length (1-2 bytes, 7-bit chunks)
 *   UTF-16: char count (1 u16 = 2 bytes, or 2 u16s = 4 bytes
 *           if the first u16's high bit is set)
 *
 * Reading a UTF-16 prefix as 7-bit chunks makes every decode
 * return null, which is what makes a patch "not find" a string
 * that is visibly in the pool.
 *
 * CHUNK LOCATION
 * --------------
 * The string pool is NOT always the first chunk after the 8-byte
 * XML file header. aapt2 emits a RES_XML_RESOURCE_MAP_TYPE chunk
 * (type 0x0180) before the string pool when the manifest uses
 * framework attributes, which is always. So this class walks the
 * chunk headers to find the pool rather than assuming offset 8.
 *
 * WRITE STRATEGY FOR PREFIX MATCHES
 * ---------------------------------
 * For "<oldName>.<suffix>", only the oldName prefix is
 * rewritten. The ".suffix" bytes stay exactly where they are.
 * Because newName and oldName are the same length, the bytes
 * for newName go where oldName was, and the suffix is
 * untouched. No length prefix changes, no offsets shift.
 */
public final class AxmlPatcher {

    /**
     * applicationId-derived suffixes that must be rewritten
     * along with the package name. These are merged into the
     * manifest as ${applicationId}.<suffix> and owned by the
     * declaring package.
     *
     * Keep this list in sync with what AGP generates. If a
     * future AGP introduces a new ${applicationId}-qualified
     * permission or authority, add it here; the alternative is
     * an INSTALL_FAILED_DUPLICATE_PERMISSION at cloak time with
     * the offending suffix named in the error.
     */
    private static final String[] APPLICATION_ID_SUFFIXES = {
        "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        "androidx-startup",
        "fileprovider",
        "shizuku",
        "shizuku-watcher",
        "status",
    };

    private AxmlPatcher() {}

    /**
     * Replace the package name (and its known applicationId-derived
     * suffixes) in the AXML string pool.
     *
     * @param axml     the AndroidManifest.xml bytes
     * @param oldName  the current package name
     * @param newName  the replacement, same length as oldName
     * @return         a new byte[] with the substitutions applied
     * @throws IOException if the AXML is malformed or the exact
     *                     package name isn't found
     */
    public static byte[] replacePackageName(byte[] axml,
                                            String oldName,
                                            String newName)
            throws IOException {
        if (axml == null) throw new IOException("null AXML");
        if (oldName == null || newName == null) {
            throw new IOException("null package name");
        }
        if (oldName.length() != newName.length()) {
            throw new IllegalArgumentException(
                "newName must be same length as oldName ("
                + oldName.length() + " chars); got "
                + newName.length() + " chars");
        }
        if (axml.length < 0x24) throw new IOException("AXML too short");
        if (readU16(axml, 0) != 0x0003) throw new IOException("not AXML");

        int poolOff = -1;
        int walkP = 8;
        StringBuilder chunkWalk = new StringBuilder();
        while (walkP + 8 <= axml.length) {
            int type = readU16(axml, walkP);
            int chunkHeaderSize = readU16(axml, walkP + 2);
            int chunkSize = readU32(axml, walkP + 4);
            chunkWalk.append("  chunk @").append(walkP)
                .append(" type=0x").append(Integer.toHexString(type))
                .append(" headerSize=").append(chunkHeaderSize)
                .append(" size=").append(chunkSize).append("\n");
            if (chunkHeaderSize < 8 || chunkSize < chunkHeaderSize) break;
            if (walkP + chunkSize > axml.length) break;
            if (type == 0x0001) { poolOff = walkP; break; }
            walkP += chunkSize;
        }
        if (poolOff < 0) {
            throw new IOException("no string pool found in AXML. Chunks:\n"
                + chunkWalk);
        }

        int headerSize    = readU16(axml, poolOff + 2);
        int chunkSize     = readU32(axml, poolOff + 4);
        int stringCount   = readU32(axml, poolOff + 8);
        int flags         = readU32(axml, poolOff + 0x10);
        int stringsStart  = readU32(axml, poolOff + 0x14);
        boolean utf8      = (flags & 0x0100) != 0;

        if (headerSize < 0x1c) throw new IOException("bad header size");
        if (poolOff + chunkSize > axml.length) {
            throw new IOException("string pool overruns buffer");
        }
        if (stringsStart < headerSize) {
            throw new IOException("stringsStart before header");
        }

        int offsetsStart     = poolOff + headerSize;
        int stringsDataStart = poolOff + stringsStart;
        int stringsEnd       = poolOff + chunkSize;

        byte[] out = axml.clone();
        int exactMatches = 0;
        int prefixMatches = 0;

        for (int i = 0; i < stringCount; i++) {
            int offPos = offsetsStart + i * 4;
            if (offPos + 4 > out.length) break;
            int relOff = readU32(out, offPos);
            int absOff = stringsDataStart + relOff;
            if (absOff < stringsDataStart || absOff >= stringsEnd) continue;

            String s = decodeAxmlString(out, absOff, stringsEnd, utf8);
            if (s == null) continue;

            boolean isExact = s.equals(oldName);
            if (!isExact && !isApplicationIdDerived(s, oldName)) continue;

            int dataStart = absOff + lengthPrefixSize(out, absOff, utf8);
            if (utf8) {
                byte[] nb = newName.getBytes(StandardCharsets.UTF_8);
                // Only the prefix is rewritten. For a suffix match,
                // the ".suffix" bytes stay exactly where they are.
                if (dataStart + nb.length > stringsEnd) {
                    throw new IOException("UTF-8 write overruns pool");
                }
                System.arraycopy(nb, 0, out, dataStart, nb.length);
            } else {
                if (dataStart + newName.length() * 2 > stringsEnd) {
                    throw new IOException("UTF-16 write overruns pool");
                }
                for (int j = 0; j < newName.length(); j++) {
                    char c = newName.charAt(j);
                    out[dataStart + j * 2]     = (byte) (c & 0xFF);
                    out[dataStart + j * 2 + 1] = (byte) ((c >> 8) & 0xFF);
                }
            }
            if (isExact) exactMatches++;
            else prefixMatches++;
        }

        if (exactMatches == 0) {
            throw new IOException(buildNotFoundDiagnostic(
                oldName, out, poolOff, headerSize, chunkSize,
                stringCount, flags, utf8, stringsStart,
                offsetsStart, stringsDataStart, stringsEnd,
                chunkWalk.toString()));
        }

        return out;
    }

    /**
     * True if {@code s} is either the exact package name or
     * "<package>.<knownSuffix>" for one of APPLICATION_ID_SUFFIXES.
     *
     * Deliberately narrow: a general "<package>.*" match would
     * catch Java class names (MainActivity, ShizuPosedManagerApp,
     * receiver.BootReceiver, ...) which must NOT be rewritten,
     * because the dex still defines them under the original
     * package name.
     */
    private static boolean isApplicationIdDerived(String s, String oldName) {
        if (s.equals(oldName)) return true;
        String prefix = oldName + ".";
        if (!s.startsWith(prefix)) return false;
        String suffix = s.substring(prefix.length());
        for (String known : APPLICATION_ID_SUFFIXES) {
            if (suffix.equals(known)) return true;
        }
        return false;
    }

    /**
     * Number of bytes occupied by the length prefix.
     *
     * UTF-8:  TWO prefixes (char count + byte length), each 1 or
     *         2 bytes using 7-bit chunks.
     * UTF-16: ONE prefix (char count), which is one u16 (2 bytes)
     *         if the high bit is clear, or two u16s (4 bytes) if
     *         the high bit is set.
     */
    private static int lengthPrefixSize(byte[] b, int start, boolean utf8) {
        if (utf8) {
            int p = start;
            int v = b[p] & 0xFF;
            int skip = (v & 0x80) != 0 ? 2 : 1;
            p += skip;
            int w = b[p] & 0xFF;
            int skip2 = (w & 0x80) != 0 ? 2 : 1;
            return skip + skip2;
        } else {
            int u16len = readU16(b, start);
            return (u16len & 0x8000) != 0 ? 4 : 2;
        }
    }

    /**
     * Decode a single AXML string-pool entry.
     *
     * UTF-8:
     *   <charCount:1|2 bytes, 7-bit chunks><byteLen:1|2 bytes, 7-bit chunks><UTF-8 data>
     *
     * UTF-16:
     *   <charCount:1 or 2 u16s><UTF-16LE data, charCount*2 bytes>
     */
    private static String decodeAxmlString(byte[] b, int start, int end,
                                           boolean utf8) {
        try {
            if (start >= end) return null;

            if (utf8) {
                int p = start;
                int charCount = 0;
                int shift = 0;
                while (p < end) {
                    int v = b[p++] & 0xFF;
                    charCount |= (v & 0x7F) << shift;
                    if ((v & 0x80) == 0) break;
                    shift += 7;
                    if (shift > 28) return null;
                }
                if (p >= end) return null;

                int byteLen;
                int lenByte = b[p++] & 0xFF;
                if ((lenByte & 0x80) == 0) {
                    byteLen = lenByte;
                } else {
                    if (p >= end) return null;
                    byteLen = ((lenByte & 0x7F) << 8) | (b[p++] & 0xFF);
                }
                if (byteLen < 0 || p + byteLen > end) return null;
                return new String(b, p, byteLen, StandardCharsets.UTF_8);
            } else {
                if (start + 2 > end) return null;
                int len = readU16(b, start);
                int p = start + 2;
                if ((len & 0x8000) != 0) {
                    if (start + 4 > end) return null;
                    len = ((len & 0x7FFF) << 16) | readU16(b, start + 2);
                    p = start + 4;
                }
                if (len < 0) return null;
                int byteLen = len * 2;
                if (byteLen < 0 || p + byteLen > end) return null;
                char[] chars = new char[len];
                for (int i = 0; i < len; i++) {
                    chars[i] = (char) readU16(b, p + i * 2);
                }
                return new String(chars);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String buildNotFoundDiagnostic(
            String oldName, byte[] out, int poolOff, int headerSize,
            int chunkSize, int stringCount, int flags, boolean utf8,
            int stringsStart, int offsetsStart, int stringsDataStart,
            int stringsEnd, String chunkWalk) {
        StringBuilder diag = new StringBuilder();
        diag.append("package name not found in string pool: ")
            .append(oldName).append("\n");
        diag.append("Chunk walk:\n").append(chunkWalk);
        diag.append("String pool:\n");
        diag.append("  poolOff=").append(poolOff)
            .append(" headerSize=").append(headerSize)
            .append(" chunkSize=").append(chunkSize)
            .append(" stringCount=").append(stringCount)
            .append(" flags=0x").append(Integer.toHexString(flags))
            .append(" utf8=").append(utf8)
            .append(" stringsStart=").append(stringsStart)
            .append(" offsetsStart=").append(offsetsStart)
            .append(" stringsDataStart=").append(stringsDataStart)
            .append(" stringsEnd=").append(stringsEnd).append("\n");
        diag.append("First 20 strings in pool:\n");
        int n = Math.min(20, stringCount);
        for (int i = 0; i < n; i++) {
            int offPos = offsetsStart + i * 4;
            if (offPos + 4 > out.length) break;
            int relOff = readU32(out, offPos);
            int absOff = stringsDataStart + relOff;
            String s = decodeAxmlString(out, absOff, stringsEnd, utf8);
            diag.append("  [").append(i).append("] relOff=").append(relOff)
                .append(" absOff=").append(absOff)
                .append(" = ").append(s == null ? "null" : "\"" + s + "\"")
                .append("\n");
        }
        return diag.toString();
    }

    private static int readU16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int readU32(byte[] b, int off) {
        return (b[off] & 0xFF)
            | ((b[off + 1] & 0xFF) << 8)
            | ((b[off + 2] & 0xFF) << 16)
            | ((b[off + 3] & 0xFF) << 24);
    }
}