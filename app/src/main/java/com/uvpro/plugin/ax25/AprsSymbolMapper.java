package com.uvpro.plugin.ax25;

import java.util.Locale;

/**
 * Maps APRS symbol table/code bytes to ATAK imported iconset paths.
 */
public final class AprsSymbolMapper {

    public static final String ICONSET_UID = "c4a2b9d1-3e77-4e94-8a5f-2dd1e0a7c6ab";

    private static final String OVERLAY_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private AprsSymbolMapper() {
    }

    public static String iconsetPath(char symbolTable, char symbolCode) {
        if (symbolCode < '!' || symbolCode > '~') {
            return null;
        }
        // Custom meshcore entry for plugin-specific icon selection.
        if (symbolTable == 'M' && symbolCode == '>') {
            return ICONSET_UID + "/meshcore.png";
        }
        String codeHex = String.format(Locale.US, "%02x", (int) symbolCode);

        if (symbolTable == '/') {
            return ICONSET_UID + "/Primary/p-" + codeHex + ".png";
        }
        if (symbolTable == '\\') {
            return ICONSET_UID + "/Alternate/a-" + codeHex + ".png";
        }
        if (OVERLAY_CHARS.indexOf(Character.toUpperCase(symbolTable)) >= 0) {
            // Phase 1: overlay glyph packets use alternate base symbol only.
            return ICONSET_UID + "/Alternate/a-" + codeHex + ".png";
        }
        return null;
    }
}
