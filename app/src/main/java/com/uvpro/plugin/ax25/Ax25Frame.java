package com.uvpro.plugin.ax25;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Represents an AX.25 frame — the data link layer protocol used by
 * amateur packet radio and APRS.
 *
 * AX.25 frame structure:
 *   [Dest Address (7)] [Src Address (7)] [Digipeater*] [Control (1)] [PID (1)] [Info (N)]
 *
 * Address format (7 bytes per address):
 *   6 bytes: Callsign (ASCII, left-justified, space-padded, shifted left 1 bit)
 *   1 byte:  SSID byte (contains SSID in bits 1-4, flags in other bits)
 *
 * We use the AX.25 UI (Unnumbered Information) frame type, which is connectionless.
 * This is the same type used by APRS.
 *
 * Control byte: 0x03 (UI frame)
 * PID byte: 0xF0 (no layer 3 protocol)
 */
public class Ax25Frame {

    /** UI frame control byte */
    public static final byte CONTROL_UI = 0x03;
    /** AX.25 connected-mode controls used by terminal sessions. */
    public static final byte CONTROL_SABM = 0x3F;
    public static final byte CONTROL_UA = 0x73;
    public static final byte CONTROL_DISC = 0x53;
    public static final byte CONTROL_RR_BASE = 0x01;

    /** No Layer 3 protocol PID */
    public static final byte PID_NO_L3 = (byte) 0xF0;

    private String destCallsign;
    private int destSsid;
    private String srcCallsign;
    private int srcSsid;
    private byte[] infoField;
    private byte controlField = CONTROL_UI;
    private int pid = PID_NO_L3 & 0xFF;
    private boolean includePid = true;

    public Ax25Frame() {}

    public Ax25Frame(String srcCallsign, int srcSsid,
                     String destCallsign, int destSsid,
                     byte[] infoField) {
        this(srcCallsign, srcSsid, destCallsign, destSsid,
                CONTROL_UI, PID_NO_L3 & 0xFF, true, infoField);
    }

    public Ax25Frame(String srcCallsign, int srcSsid,
                     String destCallsign, int destSsid,
                     byte controlField, int pid,
                     boolean includePid, byte[] infoField) {
        this.srcCallsign = srcCallsign;
        this.srcSsid = srcSsid;
        this.destCallsign = destCallsign;
        this.destSsid = destSsid;
        this.controlField = controlField;
        this.pid = pid;
        this.includePid = includePid;
        this.infoField = infoField;
    }

    /**
     * Encode this frame into raw bytes for KISS TNC transmission.
     */
    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Destination address (7 bytes)
        encodeAddress(out, destCallsign, destSsid, false);

        // Source address (7 bytes) — last address, so set the "last" bit
        encodeAddress(out, srcCallsign, srcSsid, true);

        // Control field
        out.write(controlField & 0xFF);

        // Protocol ID (for UI and I-frames)
        if (includePid) {
            out.write(pid & 0xFF);
        }

        // Information field
        if (infoField != null) {
            out.write(infoField, 0, infoField.length);
        }

        return out.toByteArray();
    }

    /**
     * Decode raw AX.25 bytes into an Ax25Frame object.
     */
    public static Ax25Frame decode(byte[] data) {
        // Minimum AX.25 header with no digipeaters and no PID/info is 15 bytes:
        // dest(7) + src(7) + control(1). Connected-mode control frames (SABM/UA/DISC)
        // are valid at this length.
        if (data == null || data.length < 15) {
            return null;
        }

        Ax25Frame frame = new Ax25Frame();

        // Decode destination address (bytes 0-6)
        frame.destCallsign = decodeCallsign(data, 0);
        frame.destSsid = (data[6] >> 1) & 0x0F;

        // Decode source address (bytes 7-13)
        frame.srcCallsign = decodeCallsign(data, 7);
        frame.srcSsid = (data[13] >> 1) & 0x0F;

        // Skip any digipeater addresses
        int pos = 14;
        if ((data[13] & 0x01) == 0) {
            // There are digipeater addresses — scan for the last one
            while (pos + 6 < data.length) {
                if ((data[pos + 6] & 0x01) != 0) {
                    pos += 7; // Skip this digipeater, it's the last
                    break;
                }
                pos += 7;
            }
        }

        // Control byte
        if (pos < data.length) {
            frame.controlField = data[pos];
            pos++;
        } else {
            frame.controlField = CONTROL_UI;
        }

        frame.includePid = shouldIncludePid(frame.controlField);
        frame.pid = -1;

        // PID byte (UI and I-frames only)
        if (frame.includePid && pos < data.length) {
            frame.pid = data[pos] & 0xFF;
            pos++;
        } else if (frame.includePid) {
            // UI/I frames without PID are malformed.
            return null;
        }

        // Info field — remainder of the frame
        if (pos < data.length) {
            frame.infoField = Arrays.copyOfRange(data, pos, data.length);
        } else {
            frame.infoField = new byte[0];
        }

        return frame;
    }

    /**
     * Encode a callsign into the AX.25 address format.
     * Callsign is left-justified, space-padded to 6 chars, each byte shifted left 1 bit.
     */
    private static void encodeAddress(ByteArrayOutputStream out,
                                       String callsign, int ssid,
                                       boolean isLast) {
        // Pad callsign to 6 characters
        String padded = (callsign + "      ").substring(0, 6).toUpperCase();

        // Shift each character left by 1 bit
        for (int i = 0; i < 6; i++) {
            out.write((padded.charAt(i) << 1) & 0xFF);
        }

        // SSID byte: bits 1-4 are SSID, bit 0 is "last address" flag
        // bits 5-6 are reserved (set to 1), bit 7 is command/response
        int ssidByte = 0x60 | ((ssid & 0x0F) << 1);
        if (isLast) {
            ssidByte |= 0x01; // Set "last address" bit
        }
        out.write(ssidByte);
    }

    /**
     * Decode a callsign from AX.25 address bytes.
     */
    private static String decodeCallsign(byte[] data, int offset) {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            char c = (char) ((data[offset + i] & 0xFF) >> 1);
            if (c != ' ') {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    // --- Convenience factory methods ---

    /**
     * Create an AX.25 frame for transmitting our custom UVPro data.
     * Uses "OPENRL" as the destination (our protocol identifier).
     */
    public static Ax25Frame createUVProFrame(String callsign, int ssid,
                                                  byte[] payload) {
        return new Ax25Frame(callsign, ssid, "OPENRL", 0, payload);
    }

    /**
     * Create a standard APRS position frame.
     */
    public static Ax25Frame createAprsFrame(String callsign, int ssid,
                                             String aprsPayload) {
        return new Ax25Frame(callsign, ssid, "APRS", 0,
                aprsPayload.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Create a generic AX.25 UI frame for terminal-mode text exchanges.
     */
    public static Ax25Frame createUiFrame(String srcCallsign, int srcSsid,
                                          String destCallsign, int destSsid,
                                          byte[] payload) {
        return new Ax25Frame(srcCallsign, srcSsid, destCallsign, destSsid, payload);
    }

    public static Ax25Frame createControlFrame(String srcCallsign, int srcSsid,
                                               String destCallsign, int destSsid,
                                               byte controlField) {
        return new Ax25Frame(srcCallsign, srcSsid, destCallsign, destSsid,
                controlField, -1, false, null);
    }

    public static Ax25Frame createIFrame(String srcCallsign, int srcSsid,
                                         String destCallsign, int destSsid,
                                         int sendSeq, int recvSeq,
                                         byte[] payload) {
        int ns = Math.max(0, Math.min(7, sendSeq));
        int nr = Math.max(0, Math.min(7, recvSeq));
        byte control = (byte) (((nr & 0x07) << 5) | ((ns & 0x07) << 1));
        return new Ax25Frame(srcCallsign, srcSsid, destCallsign, destSsid,
                control, PID_NO_L3 & 0xFF, true, payload);
    }

    public static Ax25Frame createRrFrame(String srcCallsign, int srcSsid,
                                          String destCallsign, int destSsid,
                                          int recvSeq) {
        int nr = Math.max(0, Math.min(7, recvSeq));
        byte control = (byte) (CONTROL_RR_BASE | ((nr & 0x07) << 5));
        return new Ax25Frame(srcCallsign, srcSsid, destCallsign, destSsid,
                control, -1, false, null);
    }

    private static boolean shouldIncludePid(byte control) {
        // UI frame has PID; I-frames (LSB=0) have PID.
        return control == CONTROL_UI || ((control & 0x01) == 0);
    }

    // --- Getters ---

    public String getDestCallsign() { return destCallsign; }
    public int getDestSsid() { return destSsid; }
    public String getSrcCallsign() { return srcCallsign; }
    public int getSrcSsid() { return srcSsid; }
    public byte[] getInfoField() { return infoField; }
    public byte getControlField() { return controlField; }
    public int getPid() { return pid; }
    public boolean hasPid() { return includePid; }

    /**
     * Get the info field as a UTF-8 string.
     */
    public String getInfoString() {
        if (infoField == null) return "";
        return new String(infoField, StandardCharsets.UTF_8);
    }
}
