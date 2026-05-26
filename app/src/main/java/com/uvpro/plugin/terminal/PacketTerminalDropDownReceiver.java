package com.uvpro.plugin.terminal;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.uvpro.plugin.ax25.Ax25Frame;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.protocol.PacketRouter;
import com.uvpro.plugin.ui.SettingsFragment;
import com.uvpro.plugin.util.CallsignUtil;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Basic packet terminal for BBS/Winlink-style exchanges using AX.25 connected mode.
 */
public class PacketTerminalDropDownReceiver extends DropDownReceiver
        implements DropDown.OnStateListener, PacketRouter.Ax25FrameListener {

    public static final String SHOW_PACKET_TERMINAL =
            "com.uvpro.plugin.SHOW_PACKET_TERMINAL";

    private static final int MAX_TRANSCRIPT_CHARS = 24_000;
    private static final int MAX_PAYLOAD_BYTES = 180;
    private static final int TERMINAL_LOCAL_SSID = 0;
    private static final int TX_WINDOW_SIZE = 3;
    private static final int TX_RETRY_LIMIT = 5;
    private static final long TX_ACK_TIMEOUT_MS = 3_500L;

    private final Context pluginContext;
    private final BtConnectionManager btManager;

    private PacketRouter packetRouter;
    private View panelView;
    private TextView statusView;
    private TextView transcriptView;
    private EditText remoteCallInput;
    private EditText remoteSsidInput;
    private EditText lineInput;
    private Button connectButton;
    private Button disconnectButton;
    private Button sendButton;
    private Button clearButton;

    private enum SessionState {
        IDLE,
        CONNECTING,
        CONNECTED
    }

    private volatile SessionState sessionState = SessionState.IDLE;
    private String remoteCall = "";
    private int remoteSsid = 0;
    private int txSeq = 0;
    private int rxSeq = 0;
    private String localCall = "";
    private int localSsid = TERMINAL_LOCAL_SSID;
    private final StringBuilder transcript = new StringBuilder();
    private final ArrayDeque<byte[]> outboundQueue = new ArrayDeque<>();
    private final LinkedHashMap<Integer, PendingIFrame> pendingTx = new LinkedHashMap<>();
    private final Handler sessionHandler = new Handler(Looper.getMainLooper());
    private final Runnable retransmitTick = this::onRetransmitTick;

    private static final class PendingIFrame {
        final int seq;
        final byte[] payload;
        long sentAtMs;
        int retryCount;

        PendingIFrame(int seq, byte[] payload, long sentAtMs, int retryCount) {
            this.seq = seq;
            this.payload = payload;
            this.sentAtMs = sentAtMs;
            this.retryCount = retryCount;
        }
    }

    public PacketTerminalDropDownReceiver(MapView mapView,
                                          Context pluginContext,
                                          BtConnectionManager btManager) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.btManager = btManager;
    }

    public void setPacketRouter(PacketRouter packetRouter) {
        if (this.packetRouter != null) {
            this.packetRouter.setAx25FrameListener(null);
        }
        this.packetRouter = packetRouter;
        if (this.packetRouter != null) {
            this.packetRouter.setAx25FrameListener(this);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !SHOW_PACKET_TERMINAL.equals(intent.getAction())) {
            return;
        }
        ensurePanel();
        refreshStatus();
        showDropDown(panelView,
                HALF_WIDTH, FULL_HEIGHT,
                FULL_WIDTH, HALF_HEIGHT,
                false, this);
    }

    @Override
    public void onAx25Frame(Ax25Frame frame) {
        if (frame == null) {
            return;
        }
        final String src = frame.getSrcCallsign();
        final int srcSsid = frame.getSrcSsid();
        final int control = frame.getControlField() & 0xFF;

        // Accept first inbound SABM while idle even if remote fields are not pre-populated.
        if (!isFromRemote(src, srcSsid)) {
            if (sessionState == SessionState.IDLE
                    && control == (Ax25Frame.CONTROL_SABM & 0xFF)) {
                remoteCall = normalizeCallsign(src);
                remoteSsid = srcSsid;
                if (localCall.isEmpty()) {
                    localCall = resolveLocalCallsign();
                }
                localSsid = resolveLocalSsid();
                appendTranscript(String.format(Locale.US,
                        "* Incoming link request from %s-%d (SABM)\n",
                        remoteCall, remoteSsid));
            } else {
                Log.d("UVPro.Terminal", String.format(Locale.US,
                        "drop frame src=%s-%d expected=%s-%d ctrl=0x%02X state=%s",
                        normalizeCallsign(src), srcSsid, remoteCall, remoteSsid, control, sessionState));
                return;
            }
        }
        MapView mv = getMapView();
        if (mv != null) {
            mv.post(() -> handleInboundFrame(frame, control));
        }
    }

    private void handleInboundFrame(Ax25Frame frame, int control) {
        String frameDest = normalizeCallsign(frame.getDestCallsign());
        if (!localCall.isEmpty() && !frameDest.equalsIgnoreCase(localCall)) {
            return;
        }
        if (control == (Ax25Frame.CONTROL_UA & 0xFF)) {
            if (sessionState == SessionState.CONNECTING || sessionState == SessionState.CONNECTED) {
                sessionState = SessionState.CONNECTED;
                appendTranscript("* Link established (UA)\n");
                Log.d("UVPro.Terminal", "link established via UA");
                refreshStatus();
            }
            return;
        }
        if (control == (Ax25Frame.CONTROL_DISC & 0xFF)) {
            appendTranscript("* Remote requested disconnect\n");
            sendControlFrame(Ax25Frame.CONTROL_UA);
            setSessionIdle();
            refreshStatus();
            return;
        }
        if (control == (Ax25Frame.CONTROL_SABM & 0xFF)) {
            appendTranscript("* Remote requested link (SABM)\n");
            sendControlFrame(Ax25Frame.CONTROL_UA);
            // Treat inbound SABM as an active link request even if we already pressed Connect.
            // This avoids a deadlock when both sides initiate simultaneously.
            if (sessionState != SessionState.CONNECTED) {
                sessionState = SessionState.CONNECTED;
                Log.d("UVPro.Terminal", "link established via SABM");
                refreshStatus();
            }
            return;
        }
        if (isRrFrame(control)) {
            int nr = (control >> 5) & 0x07;
            acknowledgeUpTo(nr);
            pumpOutboundQueue();
            refreshStatus();
            return;
        }
        if (!isIFrame(control)) {
            return;
        }

        int ns = (control >> 1) & 0x07;
        int nr = (control >> 5) & 0x07;
        acknowledgeUpTo(nr);
        if (ns != rxSeq) {
            appendTranscript(String.format(Locale.US,
                    "* Out-of-order frame ns=%d expected=%d (re-ACK)\n", ns, rxSeq));
            sendRrAck();
            pumpOutboundQueue();
            refreshStatus();
            return;
        }
        rxSeq = (ns + 1) & 0x07;
        txSeq = nr & 0x07;

        final String text = sanitizeIncomingText(frame.getInfoField());
        if (!text.isEmpty()) {
            appendTranscript("< " + text + "\n");
        }
        sendRrAck();
        pumpOutboundQueue();
        refreshStatus();
    }

    private static boolean isIFrame(int control) {
        return (control & 0x01) == 0;
    }

    private static boolean isRrFrame(int control) {
        return (control & 0x0F) == Ax25Frame.CONTROL_RR_BASE;
    }

    private void sendRrAck() {
        if (sessionState == SessionState.IDLE) {
            return;
        }
        String localCall = resolveLocalCallsign();
        int localSsid = resolveLocalSsid();
        Ax25Frame rr = Ax25Frame.createRrFrame(
                localCall, localSsid, remoteCall, remoteSsid, rxSeq);
        btManager.sendKissFrame(rr.encode());
    }

    private void queueTextForTransmit(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        byte[] all = text.getBytes(StandardCharsets.UTF_8);
        int pos = 0;
        while (pos < all.length) {
            int n = Math.min(MAX_PAYLOAD_BYTES, all.length - pos);
            byte[] chunk = new byte[n];
            System.arraycopy(all, pos, chunk, 0, n);
            outboundQueue.addLast(chunk);
            pos += n;
        }
    }

    private void pumpOutboundQueue() {
        if (sessionState != SessionState.CONNECTED) {
            return;
        }
        while (!outboundQueue.isEmpty() && pendingTx.size() < TX_WINDOW_SIZE) {
            byte[] payload = outboundQueue.pollFirst();
            if (payload == null) {
                continue;
            }
            int seq = txSeq & 0x07;
            if (sendIFrame(seq, payload)) {
                long now = System.currentTimeMillis();
                pendingTx.put(seq, new PendingIFrame(seq, payload, now, 0));
                txSeq = (txSeq + 1) & 0x07;
                scheduleRetransmitTick();
            } else {
                // Put it back and abort send burst on TX failure.
                outboundQueue.addFirst(payload);
                appendTranscript("! TX failed\n");
                break;
            }
        }
        refreshStatus();
    }

    private boolean sendIFrame(int seq, byte[] payload) {
        Ax25Frame frame = Ax25Frame.createIFrame(
                localCall, localSsid, remoteCall, remoteSsid, seq, rxSeq, payload);
        return btManager != null && btManager.sendKissFrame(frame.encode());
    }

    private void acknowledgeUpTo(int nr) {
        if (pendingTx.isEmpty()) {
            return;
        }
        boolean removedAny = false;
        Iterator<Integer> it = pendingTx.keySet().iterator();
        while (it.hasNext()) {
            int seq = it.next();
            if (seq == nr) {
                break;
            }
            it.remove();
            removedAny = true;
        }
        if (removedAny) {
            appendTranscript(String.format(Locale.US, "* ACK up to Nr=%d\n", nr));
        }
        if (pendingTx.isEmpty()) {
            cancelRetransmitTick();
        } else {
            scheduleRetransmitTick();
        }
    }

    private void onRetransmitTick() {
        if (sessionState != SessionState.CONNECTED || pendingTx.isEmpty()) {
            cancelRetransmitTick();
            return;
        }
        long now = System.currentTimeMillis();
        for (PendingIFrame pending : pendingTx.values()) {
            if ((now - pending.sentAtMs) < TX_ACK_TIMEOUT_MS) {
                continue;
            }
            if (pending.retryCount >= TX_RETRY_LIMIT) {
                appendTranscript(String.format(Locale.US,
                        "! Link timeout waiting ACK for seq=%d\n", pending.seq));
                setSessionIdle();
                refreshStatus();
                return;
            }
            if (sendIFrame(pending.seq, pending.payload)) {
                pending.retryCount++;
                pending.sentAtMs = now;
                appendTranscript(String.format(Locale.US,
                        "* Retransmit seq=%d retry=%d\n", pending.seq, pending.retryCount));
            } else {
                appendTranscript("! Retransmit failed\n");
                break;
            }
        }
        scheduleRetransmitTick();
    }

    private void scheduleRetransmitTick() {
        sessionHandler.removeCallbacks(retransmitTick);
        sessionHandler.postDelayed(retransmitTick, 500L);
    }

    private void cancelRetransmitTick() {
        sessionHandler.removeCallbacks(retransmitTick);
    }

    private void setSessionIdle() {
        sessionState = SessionState.IDLE;
        outboundQueue.clear();
        pendingTx.clear();
        cancelRetransmitTick();
    }

    private void sendControlFrame(byte controlField) {
        if (btManager == null || !btManager.isConnected()) {
            return;
        }
        if (localCall.isEmpty()) {
            localCall = resolveLocalCallsign();
        }
        if (localCall.isEmpty() || remoteCall.isEmpty()) {
            return;
        }
        Ax25Frame frame = Ax25Frame.createControlFrame(
                localCall, localSsid, remoteCall, remoteSsid, controlField);
        btManager.sendKissFrame(frame.encode());
    }

    private boolean isFromRemote(String srcCallsign, int srcSsid) {
        if (srcCallsign == null || srcCallsign.trim().isEmpty()) {
            return false;
        }
        String src = normalizeCallsign(srcCallsign);
        if (src.isEmpty()) {
            return false;
        }
        if (!src.equalsIgnoreCase(remoteCall)) {
            return false;
        }
        return srcSsid == remoteSsid;
    }

    private void ensurePanel() {
        if (panelView != null) {
            return;
        }
        int layoutId = pluginContext.getResources().getIdentifier(
                "packet_terminal_dropdown", "layout", pluginContext.getPackageName());
        panelView = LayoutInflater.from(pluginContext).inflate(layoutId, null);

        statusView = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_status", "id", pluginContext.getPackageName()));
        transcriptView = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_transcript", "id", pluginContext.getPackageName()));
        remoteCallInput = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_remote_call", "id", pluginContext.getPackageName()));
        remoteSsidInput = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_remote_ssid", "id", pluginContext.getPackageName()));
        lineInput = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_line_input", "id", pluginContext.getPackageName()));
        connectButton = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_connect", "id", pluginContext.getPackageName()));
        disconnectButton = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_disconnect", "id", pluginContext.getPackageName()));
        sendButton = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_send", "id", pluginContext.getPackageName()));
        clearButton = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_clear", "id", pluginContext.getPackageName()));

        if (transcriptView != null) {
            transcriptView.setMovementMethod(new ScrollingMovementMethod());
            transcriptView.setText(transcript.toString());
        }

        if (connectButton != null) {
            connectButton.setOnClickListener(v -> startSession());
        }
        if (disconnectButton != null) {
            disconnectButton.setOnClickListener(v -> stopSession());
        }
        if (sendButton != null) {
            sendButton.setOnClickListener(v -> sendLine());
        }
        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                transcript.setLength(0);
                if (transcriptView != null) {
                    transcriptView.setText("");
                }
            });
        }
    }

    private void startSession() {
        String remoteRaw = remoteCallInput != null
                ? remoteCallInput.getText().toString()
                : "";
        int uiSsid = parseSsid(remoteSsidInput != null
                ? remoteSsidInput.getText().toString() : "0");
        String[] remoteEndpoint = parseCallAndOptionalSsid(remoteRaw, uiSsid);
        String call = remoteEndpoint[0];
        if (call.isEmpty()) {
            toast("Enter remote callsign.");
            return;
        }
        int ssid = parseSsid(remoteEndpoint[1]);
        String computedLocal = resolveLocalCallsign();
        if (computedLocal.isEmpty()) {
            toast("Could not resolve local callsign.");
            return;
        }
        localCall = computedLocal;
        localSsid = resolveLocalSsid();
        remoteCall = call;
        remoteSsid = ssid;
        txSeq = 0;
        rxSeq = 0;
        outboundQueue.clear();
        pendingTx.clear();
        sessionState = SessionState.CONNECTING;
        sendControlFrame(Ax25Frame.CONTROL_SABM);
        appendTranscript(String.format(Locale.US,
                "* Local %s-%d -> Remote %s-%d (SABM)\n",
                localCall, localSsid, remoteCall, remoteSsid));
        Log.d("UVPro.Terminal", String.format(Locale.US,
                "connect local=%s-%d remote=%s-%d",
                localCall, localSsid, remoteCall, remoteSsid));
        refreshStatus();
    }

    private void stopSession() {
        if (sessionState == SessionState.IDLE) {
            return;
        }
        if (sessionState == SessionState.CONNECTED || sessionState == SessionState.CONNECTING) {
            sendControlFrame(Ax25Frame.CONTROL_DISC);
        }
        setSessionIdle();
        appendTranscript("* Session stopped (DISC)\n");
        refreshStatus();
    }

    private void sendLine() {
        if (sessionState != SessionState.CONNECTED) {
            toast("Terminal link is not connected yet.");
            return;
        }
        if (btManager == null || !btManager.isConnected()) {
            toast("Radio not connected.");
            return;
        }
        if (localCall.isEmpty()) {
            localCall = resolveLocalCallsign();
        }
        if (localSsid < 0 || localSsid > 15) {
            localSsid = resolveLocalSsid();
        }
        if (localCall.isEmpty()) {
            toast("Set local callsign first.");
            return;
        }
        String line = lineInput != null ? lineInput.getText().toString() : "";
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        queueTextForTransmit(line + "\r");
        appendTranscript("> " + line + "\n");
        pumpOutboundQueue();
        if (lineInput != null) {
            lineInput.setText("");
        }
    }

    private String resolveLocalCallsign() {
        Context ctx = getMapView() != null ? getMapView().getContext() : pluginContext;
        String aprsCall = normalizeCallsign(SettingsFragment.getAprsCallsign(ctx));
        if (!aprsCall.isEmpty()) {
            return aprsCall;
        }
        String mapCall = "UNKNOWN";
        try {
            if (getMapView() != null && getMapView().getSelfMarker() != null) {
                mapCall = getMapView().getSelfMarker().getMetaString("callsign", "UNKNOWN");
            }
        } catch (Exception ignored) {
        }
        String fromAtak = normalizeCallsign(mapCall);
        if (!fromAtak.isEmpty() && !"UNKNOWN".equalsIgnoreCase(fromAtak)) {
            return fromAtak;
        }
        String fallbackRadioStyle = normalizeCallsign(CallsignUtil.toRadioCallsign(mapCall));
        return fallbackRadioStyle;
    }

    private int resolveLocalSsid() {
        Context ctx = getMapView() != null ? getMapView().getContext() : pluginContext;
        int ssid = SettingsFragment.getAprsSsid(ctx);
        return Math.max(0, Math.min(15, ssid));
    }

    private void refreshStatus() {
        if (statusView == null) {
            return;
        }
        boolean radioConnected = btManager != null && btManager.isConnected();
        String remote = remoteCall.isEmpty() ? "N/A"
                : String.format(Locale.US, "%s-%d", remoteCall, remoteSsid);
        String mode;
        if (sessionState == SessionState.CONNECTED) {
            mode = "CONNECTED";
        } else if (sessionState == SessionState.CONNECTING) {
            mode = "CONNECTING";
        } else {
            mode = "IDLE";
        }
        statusView.setText(String.format(Locale.US,
                "Radio: %s   Session: %s   Remote: %s   TXq:%d   Unacked:%d",
                radioConnected ? "CONNECTED" : "DISCONNECTED",
                mode,
                remote,
                outboundQueue.size(),
                pendingTx.size()));
    }

    private void appendTranscript(String line) {
        if (line == null) {
            return;
        }
        transcript.append(line);
        if (transcript.length() > MAX_TRANSCRIPT_CHARS) {
            int trimTo = transcript.length() - MAX_TRANSCRIPT_CHARS;
            transcript.delete(0, Math.min(trimTo, transcript.length()));
        }
        if (transcriptView != null) {
            transcriptView.setText(transcript.toString());
        }
    }

    private static String sanitizeIncomingText(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "";
        }
        String raw = new String(payload, StandardCharsets.UTF_8);
        // Lightweight ANSI/VT100 cleanup for BBS prompts/menus:
        // - drop ESC [ ... command sequences
        // - apply backspace edits
        // - normalize CR/LF for transcript display
        String noAnsi = raw.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
        StringBuilder out = new StringBuilder(noAnsi.length());
        for (int i = 0; i < noAnsi.length(); i++) {
            char c = noAnsi.charAt(i);
            if (c == '\r') {
                out.append('\n');
            } else if (c == '\b' || c == 0x7F) {
                if (out.length() > 0) {
                    out.deleteCharAt(out.length() - 1);
                }
            } else if (c == '\n' || c == '\t' || (c >= 32 && c <= 126)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String normalizeCallsign(String input) {
        if (input == null) {
            return "";
        }
        String cleaned = input.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        if (cleaned.length() > 6) {
            cleaned = cleaned.substring(0, 6);
        }
        return cleaned;
    }

    private static int parseSsid(String input) {
        try {
            int ssid = Integer.parseInt(input.trim());
            return Math.max(0, Math.min(15, ssid));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String[] parseCallAndOptionalSsid(String rawCallsignInput, int defaultSsid) {
        String raw = rawCallsignInput == null ? "" : rawCallsignInput.trim();
        if (raw.isEmpty()) {
            return new String[]{"", String.valueOf(defaultSsid)};
        }
        int dashIdx = raw.lastIndexOf('-');
        if (dashIdx > 0 && dashIdx < raw.length() - 1) {
            String callPart = normalizeCallsign(raw.substring(0, dashIdx));
            String ssidPart = raw.substring(dashIdx + 1).trim();
            return new String[]{callPart, String.valueOf(parseSsid(ssidPart))};
        }
        return new String[]{normalizeCallsign(raw), String.valueOf(defaultSsid)};
    }

    private void toast(String msg) {
        Context ctx = getMapView() != null ? getMapView().getContext() : pluginContext;
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDropDownVisible(boolean visible) {
        if (visible) {
            refreshStatus();
        }
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void disposeImpl() {
        setSessionIdle();
        localCall = "";
        localSsid = TERMINAL_LOCAL_SSID;
        if (packetRouter != null) {
            packetRouter.setAx25FrameListener(null);
        }
        panelView = null;
        statusView = null;
        transcriptView = null;
        remoteCallInput = null;
        remoteSsidInput = null;
        lineInput = null;
        connectButton = null;
        disconnectButton = null;
        sendButton = null;
        clearButton = null;
    }
}
