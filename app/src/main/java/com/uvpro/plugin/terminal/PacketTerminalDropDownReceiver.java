package com.uvpro.plugin.terminal;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.uvpro.plugin.ax25.Ax25Frame;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.kiss.KissRadioFrequencyControl;
import com.uvpro.plugin.protocol.PacketRouter;
import com.uvpro.plugin.ui.SettingsFragment;
import com.uvpro.plugin.util.CallsignUtil;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * AX.25 terminal for node + bulletin-board sessions (EAGLE/KL7AA Site Summit).
 * Node menus use UI frames; the BBS leg typically uses connected-mode I-frames.
 */
public class PacketTerminalDropDownReceiver extends DropDownReceiver
        implements DropDown.OnStateListener, PacketRouter.Ax25FrameListener {

    public static final String SHOW_PACKET_TERMINAL =
            "com.uvpro.plugin.SHOW_PACKET_TERMINAL";

    private static final String PREFS_NAME = "uvpro_packet_terminal";
    private static final String PREF_LAST_REMOTE_CALL = "last_remote_call";
    private static final String PREF_LAST_REMOTE_SSID = "last_remote_ssid";

    private static final int MAX_TRANSCRIPT_CHARS = 24_000;
    private static final int MAX_PAYLOAD_BYTES = 180;
    /** Digipeater / other station — never show or route in this terminal. */
    private static final String IGNORE_CALLSIGN = "KW4MP";
    /** EAGLE node alias on Site Summit (KL7AA-7 node, KL7AA-5 BBS). */
    private static final String EAGLE_NODE_CALL = "EAGLE";
    private static final String EAGLE_SITE_CALL = "KL7AA";
    private static final int KL7AA_BBS_SSID = 5;
    private static final long TX_ACK_TIMEOUT_MS = 3_500L;
    private static final int TX_RETRY_LIMIT = 5;
    private static final long KEEPALIVE_MS = 28_000L;
    private static final long SABM_RETRY_MS = 4_000L;
    private static final int SABM_RETRY_LIMIT = 10;
    /** Reconnect if BBS stops answering (no DISC frame). */
    private static final long BBS_STALL_MS = 60_000L;
    /** Spacing between hangup KISS frames so the UV-PRO TNC keys RF for each one. */
    private static final long HANGUP_STEP_MS = 850L;

    private enum BbsLinkState {
        IDLE,
        CONNECTING,
        CONNECTED
    }

    private final Context pluginContext;
    private final BtConnectionManager btManager;

    private PacketRouter packetRouter;
    private View panelView;
    private ScrollView transcriptScrollView;
    private TextView statusView;
    private TextView transcriptView;
    private boolean panelListenersAttached = false;
    private EditText remoteCallInput;
    private EditText remoteSsidInput;
    private EditText lineInput;
    private Button connectButton;
    private Button disconnectButton;
    private Button sendButton;
    private Button clearButton;

    private enum SessionState {
        IDLE,
        ACTIVE
    }

    private volatile SessionState sessionState = SessionState.IDLE;
    /** User-configured node (e.g. EAGLE-0). */
    private String nodeCall = "";
    private int nodeSsid = 0;
    /** Learned BBS peer after C &lt;call&gt; (e.g. KL7AA-5). */
    private String bbsPeerCall = "";
    private int bbsPeerSsid = 0;
    private String localCall = "";
    private int localSsid = 0;
    private int bbsRxSeq = 0;
    private int bbsTxSeq = 0;
    private BbsLinkState bbsLinkState = BbsLinkState.IDLE;
    private PendingIFrame pendingBbsTx = null;
    private int sabmAttempts = 0;
    private long lastBbsRxMs = 0L;
    private volatile boolean hangupInProgress = false;
    private int hangupStep = 0;
    private boolean hangupEndSession = false;
    private boolean hangupHadConnectedLink = false;
    private final Handler sessionHandler = new Handler(Looper.getMainLooper());
    private final Runnable retransmitTick = this::onRetransmitTick;
    private final Runnable keepaliveTick = this::onKeepaliveTick;
    private final Runnable sabmRetryTick = this::onSabmRetryTick;
    private final Runnable hangupStepTick = this::onHangupStepTick;
    private final StringBuilder transcript = new StringBuilder();

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
        ensureStandalonePanel();
        refreshStatus();
        showDropDown(panelView,
                HALF_WIDTH, HALF_HEIGHT,
                FULL_WIDTH, HALF_HEIGHT,
                false, this);
    }

    /** Binds the shared terminal panel embedded in the main plugin dropdown. */
    public void attachInlinePanel(View sectionRoot) {
        if (sectionRoot == null) {
            return;
        }
        View panel = sectionRoot.findViewById(pluginContext.getResources()
                .getIdentifier("packet_terminal_panel_root", "id", pluginContext.getPackageName()));
        if (panel == null) {
            panel = sectionRoot;
        }
        bindPanelViews(panel);
        refreshStatus();
    }

    @Override
    public boolean onAx25Frame(Ax25Frame frame) {
        if (frame == null || sessionState != SessionState.ACTIVE) {
            return false;
        }
        final String src = frame.getSrcCallsign();
        final String dest = frame.getDestCallsign();
        final int srcSsid = frame.getSrcSsid();
        final int control = frame.getControlField() & 0xFF;
        final byte[] info = frame.getInfoField();

        if (shouldIgnoreCallsign(src) || shouldIgnoreCallsign(dest)) {
            return false;
        }
        String srcNorm = normalizeCallsign(src);
        if (isBbsLinkSource(srcNorm, srcSsid)
                && handleBbsLinkControl(srcNorm, srcSsid, control)) {
            return true;
        }
        if (!isTerminalControl(control)) {
            return false;
        }
        if (!acceptRemoteEndpoint(src, srcSsid, info, control)) {
            Log.d("UVPro.Terminal", String.format(Locale.US,
                    "drop frame src=%s-%d node=%s-%d bbs=%s-%d ctrl=0x%02X",
                    normalizeCallsign(src), srcSsid, nodeCall, nodeSsid,
                    bbsPeerCall, bbsPeerSsid, control));
            return false;
        }
        MapView mv = getMapView();
        if (mv != null) {
            mv.post(() -> handleInboundFrame(frame, control));
        } else {
            handleInboundFrame(frame, control);
        }
        return true;
    }

    private void handleInboundFrame(Ax25Frame frame, int control) {
        String frameDest = normalizeCallsign(frame.getDestCallsign());
        if (!acceptsLocalDest(frameDest)) {
            Log.d("UVPro.Terminal", String.format(Locale.US,
                    "drop dest=%s local=%s", frameDest, localCall));
            return;
        }
        if (isIFrame(control)) {
            markBbsActivity();
            promoteBbsLinkConnected("data");
            int ns = (control >> 1) & 0x07;
            int nr = (control >> 5) & 0x07;
            acknowledgeBbsUpTo(nr);
            bbsRxSeq = (ns + 1) & 0x07;
            final String text = sanitizeIncomingText(frame.getInfoField());
            if (!text.isEmpty()) {
                appendTranscript("< " + text + "\n");
            }
            sendBbsRrAck();
            refreshStatus();
            return;
        }
        if (control == (Ax25Frame.CONTROL_UI & 0xFF)) {
            markBbsActivity();
            final String text = sanitizeIncomingText(frame.getInfoField());
            if (!text.isEmpty()) {
                appendTranscript("< " + text + "\n");
            }
            refreshStatus();
        }
    }

    private void markBbsActivity() {
        lastBbsRxMs = System.currentTimeMillis();
    }

    private static boolean isTerminalControl(int control) {
        return control == (Ax25Frame.CONTROL_UI & 0xFF) || isIFrame(control);
    }

    private static boolean isIFrame(int control) {
        return (control & 0x01) == 0;
    }

    private boolean acceptRemoteEndpoint(String srcCallsign, int srcSsid,
                                         byte[] info, int control) {
        if (srcCallsign == null || srcCallsign.trim().isEmpty() || nodeCall.isEmpty()) {
            return false;
        }
        String src = normalizeCallsign(srcCallsign);
        if (src.isEmpty()) {
            return false;
        }
        if (shouldIgnoreCallsign(src)) {
            return false;
        }
        if (src.equalsIgnoreCase(nodeCall)) {
            if (srcSsid != nodeSsid) {
                nodeSsid = srcSsid;
            }
            return true;
        }
        if (isEagleSiteCall(src)) {
            return trackEagleSiteEndpoint(src, srcSsid, info, control);
        }
        if (!bbsPeerCall.isEmpty() && src.equalsIgnoreCase(bbsPeerCall)) {
            if (srcSsid != bbsPeerSsid) {
                bbsPeerSsid = srcSsid;
            }
            return true;
        }
        if (maybeLearnBbsPeer(src, srcSsid, info, control)) {
            return true;
        }
        return false;
    }

    /** EAGLE and KL7AA are the same Site Summit node/BBS system. */
    private boolean isEagleSiteSession() {
        return EAGLE_NODE_CALL.equalsIgnoreCase(nodeCall)
                || EAGLE_SITE_CALL.equalsIgnoreCase(nodeCall);
    }

    private boolean isEagleSiteCall(String call) {
        if (!isEagleSiteSession()) {
            return false;
        }
        String c = normalizeCallsign(call);
        return EAGLE_NODE_CALL.equalsIgnoreCase(c) || EAGLE_SITE_CALL.equalsIgnoreCase(c);
    }

    private boolean isBbsPeer(String srcNorm, int srcSsid) {
        if (bbsPeerCall.isEmpty() || srcNorm.isEmpty()) {
            return false;
        }
        return srcNorm.equalsIgnoreCase(bbsPeerCall);
    }

    private boolean isBbsLinkSource(String srcNorm, int srcSsid) {
        if (isBbsPeer(srcNorm, srcSsid)) {
            return true;
        }
        return isEagleSiteBbsSource(srcNorm);
    }

    private boolean isEagleSiteBbsSource(String srcNorm) {
        return isEagleSiteSession()
                && EAGLE_SITE_CALL.equalsIgnoreCase(srcNorm);
    }

    private void ensureBbsPeerFromSource(String srcNorm, int srcSsid) {
        if (bbsPeerCall.isEmpty() && !srcNorm.isEmpty()) {
            bbsPeerCall = srcNorm;
            bbsPeerSsid = srcSsid;
        } else if (srcNorm.equalsIgnoreCase(bbsPeerCall) && srcSsid != bbsPeerSsid) {
            bbsPeerSsid = srcSsid;
        }
    }

    private boolean handleBbsLinkControl(String srcNorm, int srcSsid, int control) {
        if (!isBbsLinkSource(srcNorm, srcSsid)) {
            return false;
        }
        ensureBbsPeerFromSource(srcNorm, srcSsid);
        markBbsActivity();
        if (control == (Ax25Frame.CONTROL_UA & 0xFF)) {
            if (hangupInProgress || bbsLinkState == BbsLinkState.IDLE) {
                Log.d("UVPro.Terminal", String.format(Locale.US,
                        "ua during hangup from %s-%d", srcNorm, srcSsid));
                return true;
            }
            promoteBbsLinkConnected("UA");
            return true;
        }
        if (control == (Ax25Frame.CONTROL_SABM & 0xFF)) {
            if (hangupInProgress) {
                sendControlToBbs(Ax25Frame.CONTROL_UA);
                return true;
            }
            sendControlToBbs(Ax25Frame.CONTROL_UA);
            promoteBbsLinkConnected("SABM");
            return true;
        }
        if (isDiscControl(control)) {
            Log.i("UVPro.Terminal", String.format(Locale.US,
                    "bbs DISC from %s-%d ctrl=0x%02X", srcNorm, srcSsid, control));
            sendControlToBbs(Ax25Frame.CONTROL_UA);
            if (!hangupInProgress) {
                beginBbsReconnect("BBS disconnected");
            }
            return true;
        }
        if (isRrFrame(control)) {
            int nr = (control >> 5) & 0x07;
            acknowledgeBbsUpTo(nr);
            return true;
        }
        return false;
    }

    private void beginBbsReconnect(String reason) {
        if (sessionState != SessionState.ACTIVE || hangupInProgress) {
            return;
        }
        Log.i("UVPro.Terminal", "bbs reconnect: " + reason);
        appendTranscript(String.format(Locale.US, "* %s — reconnecting\n", reason));
        pendingBbsTx = null;
        bbsRxSeq = 0;
        bbsTxSeq = 0;
        lastBbsRxMs = 0L;
        bbsLinkState = BbsLinkState.CONNECTING;
        sabmAttempts = 0;
        cancelKeepalive();
        cancelRetransmitTick();
        if (bbsPeerCall.isEmpty() && isEagleSiteSession()) {
            if (EAGLE_NODE_CALL.equalsIgnoreCase(nodeCall)) {
                bbsPeerCall = EAGLE_SITE_CALL;
                bbsPeerSsid = resolveKl7aaBbsSsid(nodeSsid);
            } else {
                bbsPeerCall = nodeCall;
                bbsPeerSsid = resolveKl7aaBbsSsid(nodeSsid);
            }
        }
        sendSabm();
        scheduleSabmRetry();
        refreshStatus();
    }

    private void acknowledgeBbsUpTo(int nr) {
        if (pendingBbsTx == null) {
            bbsTxSeq = nr & 0x07;
            cancelRetransmitTick();
            return;
        }
        int ackSeq = (pendingBbsTx.seq + 1) & 0x07;
        if (nr != ackSeq) {
            return;
        }
        bbsTxSeq = ackSeq;
        pendingBbsTx = null;
        cancelRetransmitTick();
    }

    private static boolean isRrFrame(int control) {
        return (control & 0x0F) == Ax25Frame.CONTROL_RR_BASE;
    }

    private void promoteBbsLinkConnected(String reason) {
        if (bbsLinkState == BbsLinkState.CONNECTED) {
            return;
        }
        bbsLinkState = BbsLinkState.CONNECTED;
        sabmAttempts = 0;
        markBbsActivity();
        cancelSabmRetry();
        scheduleKeepalive();
        appendTranscript(String.format(Locale.US, "* BBS link active (%s)\n", reason));
        Log.d("UVPro.Terminal", "bbs link active via " + reason);
        refreshStatus();
    }

    private boolean trackEagleSiteEndpoint(String src, int srcSsid, byte[] info, int control) {
        String call = normalizeCallsign(src);
        if (EAGLE_NODE_CALL.equalsIgnoreCase(call)) {
            if (srcSsid != nodeSsid) {
                nodeSsid = srcSsid;
            }
            return true;
        }
        if (EAGLE_SITE_CALL.equalsIgnoreCase(call)) {
            if (isIFrame(control) && info != null && info.length > 0) {
                adoptBbsPeer(call, srcSsid, "EAGLE BBS");
            }
            return true;
        }
        return false;
    }

    private boolean maybeLearnBbsPeer(String src, int srcSsid, byte[] info, int control) {
        if (src.equalsIgnoreCase(nodeCall) || shouldIgnoreCallsign(src)) {
            return false;
        }
        if (info == null || info.length == 0) {
            return false;
        }
        if (!isIFrame(control) && control != (Ax25Frame.CONTROL_UI & 0xFF)) {
            return false;
        }
        String text = sanitizeIncomingText(info);
        if (text.isEmpty()) {
            return false;
        }
        adoptBbsPeer(src, srcSsid, "BBS");
        return true;
    }

    private void adoptBbsPeer(String call, int ssid, String label) {
        bbsPeerCall = normalizeCallsign(call);
        bbsPeerSsid = ssid;
        bbsRxSeq = 0;
        bbsTxSeq = 0;
        appendTranscript(String.format(Locale.US,
                "* %s on %s-%d\n", label, bbsPeerCall, bbsPeerSsid));
        Log.d("UVPro.Terminal", String.format(Locale.US,
                "%s on %s-%d", label, bbsPeerCall, bbsPeerSsid));
        refreshStatus();
    }

    private static boolean shouldIgnoreCallsign(String call) {
        return IGNORE_CALLSIGN.equalsIgnoreCase(normalizeCallsign(call));
    }

    private boolean acceptsLocalDest(String frameDest) {
        if (frameDest.isEmpty()) {
            return true;
        }
        if (shouldIgnoreCallsign(frameDest)) {
            return false;
        }
        if ("APRS".equalsIgnoreCase(frameDest) || "OPENRL".equalsIgnoreCase(frameDest)) {
            return false;
        }
        if (!localCall.isEmpty() && frameDest.equalsIgnoreCase(localCall)) {
            return true;
        }
        Context ctx = getMapView() != null ? getMapView().getContext() : pluginContext;
        String aprs = normalizeCallsign(SettingsFragment.getAprsCallsign(ctx));
        return !aprs.isEmpty() && frameDest.equalsIgnoreCase(aprs);
    }

    private void ensureStandalonePanel() {
        if (panelView != null) {
            return;
        }
        int layoutId = pluginContext.getResources().getIdentifier(
                "packet_terminal_dropdown", "layout", pluginContext.getPackageName());
        panelView = LayoutInflater.from(pluginContext).inflate(layoutId, null);
        View panel = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("packet_terminal_panel_root", "id", pluginContext.getPackageName()));
        bindPanelViews(panel != null ? panel : panelView);
    }

    private void bindPanelViews(View panel) {
        if (panel == null) {
            return;
        }
        panelView = panel;
        statusView = panel.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_status", "id", pluginContext.getPackageName()));
        transcriptScrollView = panel.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_transcript_scroll", "id", pluginContext.getPackageName()));
        transcriptView = panel.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_transcript", "id", pluginContext.getPackageName()));
        remoteCallInput = panel.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_remote_call", "id", pluginContext.getPackageName()));
        remoteSsidInput = panel.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_remote_ssid", "id", pluginContext.getPackageName()));
        lineInput = panel.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_line_input", "id", pluginContext.getPackageName()));
        connectButton = panel.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_connect", "id", pluginContext.getPackageName()));
        disconnectButton = panel.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_disconnect", "id", pluginContext.getPackageName()));
        sendButton = panel.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_send", "id", pluginContext.getPackageName()));
        clearButton = panel.findViewById(pluginContext.getResources()
                .getIdentifier("terminal_clear", "id", pluginContext.getPackageName()));

        if (transcriptView != null) {
            transcriptView.setText(transcript.toString());
        }
        setupTranscriptScrollHandoff();

        if (!panelListenersAttached) {
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
            panelListenersAttached = true;
        }
        restoreLastRemoteStation();
    }

    private void setupTranscriptScrollHandoff() {
        if (transcriptScrollView == null) {
            return;
        }
        final float[] lastTouchY = new float[1];
        transcriptScrollView.setOnTouchListener((v, event) -> {
            ScrollView outer = findAncestorScrollView(v.getParent());
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchY[0] = event.getY();
                    if (outer != null) {
                        outer.requestDisallowInterceptTouchEvent(true);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dy = event.getY() - lastTouchY[0];
                    lastTouchY[0] = event.getY();
                    boolean atTop = !v.canScrollVertically(-1);
                    boolean atBottom = !v.canScrollVertically(1);
                    if (outer != null) {
                        boolean handOffToOuter = (atTop && dy > 0f) || (atBottom && dy < 0f);
                        outer.requestDisallowInterceptTouchEvent(!handOffToOuter);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (outer != null) {
                        outer.requestDisallowInterceptTouchEvent(false);
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private ScrollView findAncestorScrollView(ViewParent start) {
        ViewParent cursor = start;
        while (cursor != null) {
            if (cursor instanceof ScrollView) {
                return (ScrollView) cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private void startSession() {
        if (hangupInProgress) {
            cancelHangupTimers();
            hangupInProgress = false;
            hangupStep = 0;
            hangupHadConnectedLink = false;
        }
        if (btManager == null || !btManager.isConnected()) {
            toast("Radio not connected.");
            return;
        }
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
        saveLastRemoteStation(remoteRaw.trim(), uiSsid);
        String computedLocal = resolveLocalCallsign();
        if (computedLocal.isEmpty()) {
            toast("Could not resolve local callsign.");
            return;
        }
        localCall = computedLocal;
        localSsid = resolveLocalSsid();
        nodeCall = call;
        nodeSsid = ssid;
        bbsPeerCall = "";
        bbsPeerSsid = 0;
        bbsRxSeq = 0;
        bbsTxSeq = 0;
        bbsLinkState = BbsLinkState.IDLE;
        sessionState = SessionState.ACTIVE;
        appendTranscript(String.format(Locale.US,
                "* Session %s-%d remote %s-%d\n",
                localCall, localSsid, nodeCall, nodeSsid));
        Log.d("UVPro.Terminal", String.format(Locale.US,
                "session local=%s-%d remote=%s-%d",
                localCall, localSsid, nodeCall, nodeSsid));
        warnIfKissNotLocked();
        if (isEagleSiteSession()) {
            openDirectBbsLink();
        }
        refreshStatus();
    }

    private void openDirectBbsLink() {
        if (EAGLE_NODE_CALL.equalsIgnoreCase(nodeCall)) {
            bbsPeerCall = EAGLE_SITE_CALL;
            bbsPeerSsid = resolveKl7aaBbsSsid(nodeSsid);
        } else {
            bbsPeerCall = nodeCall;
            bbsPeerSsid = resolveKl7aaBbsSsid(nodeSsid);
        }
        bbsRxSeq = 0;
        bbsTxSeq = 0;
        bbsLinkState = BbsLinkState.CONNECTING;
        sabmAttempts = 0;
        pendingBbsTx = null;
        appendTranscript(String.format(Locale.US,
                "* Connecting to BBS %s-%d (SABM)\n", bbsPeerCall, bbsPeerSsid));
        sendSabm();
        scheduleSabmRetry();
    }

    private static int resolveKl7aaBbsSsid(int enteredSsid) {
        return enteredSsid == 0 ? KL7AA_BBS_SSID : enteredSsid;
    }

    private void stopSession() {
        if (sessionState == SessionState.IDLE) {
            return;
        }
        initiateBbsHangup("Disconnect button", true);
    }

    private void sendLine() {
        if (sessionState != SessionState.ACTIVE) {
            toast("Press Connect to open session first.");
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
        warnIfKissNotLocked();
        String line = lineInput != null ? lineInput.getText().toString() : "";
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        appendTranscript("> " + line + "\n");
        if (isBbsDisconnectCommand(line)) {
            if (lineInput != null) {
                lineInput.setText("");
            }
            initiateBbsHangup("BBS bye command", true);
            return;
        }
        String payload = line + "\r";
        if (!bbsPeerCall.isEmpty()) {
            if (bbsLinkState == BbsLinkState.CONNECTING) {
                toast("BBS link still opening — retrying SABM.");
                sendSabm();
            }
            if (bbsLinkState != BbsLinkState.CONNECTED) {
                toast("BBS link not up yet — wait for UA from KL7AA.");
                return;
            }
            sendBbsText(payload);
        } else {
            sendUiText(nodeCall, nodeSsid, payload);
        }
        if (lineInput != null) {
            lineInput.setText("");
        }
    }

    private void sendUiText(String destCall, int destSsid, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        byte[] all = text.getBytes(StandardCharsets.UTF_8);
        int pos = 0;
        while (pos < all.length) {
            int n = Math.min(MAX_PAYLOAD_BYTES, all.length - pos);
            byte[] chunk = new byte[n];
            System.arraycopy(all, pos, chunk, 0, n);
            if (!sendUiPayload(destCall, destSsid, chunk)) {
                appendTranscript("! TX failed\n");
                break;
            }
            pos += n;
        }
        refreshStatus();
    }

    private static boolean isBbsDisconnectCommand(String line) {
        if (line == null) {
            return false;
        }
        String t = line.trim().toLowerCase(Locale.US);
        return t.equals("b") || t.equals("bye") || t.equals("disconnect") || t.equals("quit");
    }

    private void initiateBbsHangup(String reason, boolean endSession) {
        cancelHangupTimers();
        hangupInProgress = true;
        hangupStep = 0;
        hangupEndSession = endSession;
        hangupHadConnectedLink = false;
        cancelAllLinkTimers();
        pendingBbsTx = null;
        warnIfKissNotLocked();

        if (bbsPeerCall.isEmpty() && !nodeCall.isEmpty()) {
            appendTranscript(String.format(Locale.US, "* Disconnecting node (%s)\n", reason));
            prepareRadioForHangupTx();
            boolean ok = sendUiPayload(nodeCall, nodeSsid,
                    "B\r".getBytes(StandardCharsets.UTF_8));
            appendTranscript(ok ? "* Sent B (node UI)\n" : "! B TX failed\n");
            sessionHandler.postDelayed(() -> finishHangup(endSession), HANGUP_STEP_MS);
            refreshStatus();
            return;
        }
        if (bbsPeerCall.isEmpty()) {
            appendTranscript("* Session stopped (no peer)\n");
            finishHangup(endSession);
            return;
        }
        if (btManager == null || !btManager.isConnected()) {
            appendTranscript("! Hangup failed (radio not connected)\n");
            finishHangup(endSession);
            return;
        }

        appendTranscript(String.format(Locale.US, "* Disconnecting (%s)\n", reason));
        hangupHadConnectedLink = bbsLinkState == BbsLinkState.CONNECTED;
        bbsLinkState = BbsLinkState.IDLE;
        prepareRadioForHangupTx();
        onHangupStepTick();
        refreshStatus();
    }

    private void prepareRadioForHangupTx() {
        if (btManager == null || !btManager.isConnected()) {
            return;
        }
        if (KissRadioFrequencyControl.reaffirmLock(btManager)) {
            appendTranscript("* KISS frequency lock reaffirmed\n");
        }
        btManager.primeKissTxTiming();
    }

    private void transmitBbsByeImmediate() {
        if (btManager == null || !btManager.isConnected() || bbsPeerCall.isEmpty()) {
            return;
        }
        byte[] payload = "B\r".getBytes(StandardCharsets.UTF_8);
        int seq = bbsTxSeq & 0x07;
        Ax25Frame frame = Ax25Frame.createIFrame(
                localCall, localSsid, bbsPeerCall, bbsPeerSsid,
                seq, bbsRxSeq, payload);
        byte[] ax25 = frame.encode();
        boolean ok = btManager.sendKissFrame(ax25);
        Log.d("UVPro.Terminal", String.format(Locale.US,
                "tx bye %s-%d -> %s-%d seq=%d ax25=%d ok=%s",
                localCall, localSsid, bbsPeerCall, bbsPeerSsid, seq, ax25.length, ok));
        appendTranscript(ok ? "* Sent B (bye)\n" : "! B TX failed\n");
        if (ok) {
            bbsTxSeq = (seq + 1) & 0x07;
        }
    }

    private void onHangupStepTick() {
        if (!hangupInProgress) {
            return;
        }
        if (btManager == null || !btManager.isConnected() || bbsPeerCall.isEmpty()) {
            appendTranscript("! Hangup TX failed (radio not connected)\n");
            finishHangup(hangupEndSession);
            return;
        }
        switch (hangupStep) {
            case 0:
                if (hangupHadConnectedLink) {
                    transmitBbsByeImmediate();
                }
                hangupStep = 1;
                sessionHandler.postDelayed(hangupStepTick, HANGUP_STEP_MS);
                break;
            case 1:
                boolean okPoll = sendControlToBbs(Ax25Frame.CONTROL_DISC);
                Log.d("UVPro.Terminal", String.format(Locale.US,
                        "tx disc poll %s-%d -> %s-%d ok=%s step=1",
                        localCall, localSsid, bbsPeerCall, bbsPeerSsid, okPoll));
                appendTranscript(okPoll ? "* Sent DISC\n" : "! DISC TX failed\n");
                hangupStep = 2;
                sessionHandler.postDelayed(hangupStepTick, HANGUP_STEP_MS);
                break;
            case 2:
                boolean okNoPoll = sendControlToBbs(Ax25Frame.CONTROL_DISC_NOPOLL);
                Log.d("UVPro.Terminal", String.format(Locale.US,
                        "tx disc nopoll %s-%d -> %s-%d ok=%s step=2",
                        localCall, localSsid, bbsPeerCall, bbsPeerSsid, okNoPoll));
                hangupStep = 3;
                sessionHandler.postDelayed(hangupStepTick, HANGUP_STEP_MS);
                break;
            case 3:
                sendControlToBbs(Ax25Frame.CONTROL_DISC);
                Log.d("UVPro.Terminal", String.format(Locale.US,
                        "tx disc poll %s-%d -> %s-%d step=3 (final)",
                        localCall, localSsid, bbsPeerCall, bbsPeerSsid));
                finishHangup(hangupEndSession);
                break;
            default:
                finishHangup(hangupEndSession);
                break;
        }
    }

    private void finishHangup(boolean endSession) {
        hangupInProgress = false;
        hangupStep = 0;
        hangupHadConnectedLink = false;
        cancelHangupTimers();
        if (endSession) {
            setSessionIdle();
            appendTranscript("* Session stopped\n");
            refreshStatus();
        }
    }

    private void cancelHangupTimers() {
        sessionHandler.removeCallbacks(hangupStepTick);
    }

    private static boolean isDiscControl(int control) {
        return control == (Ax25Frame.CONTROL_DISC & 0xFF)
                || control == (Ax25Frame.CONTROL_DISC_NOPOLL & 0xFF);
    }

    private void sendBbsText(String text) {
        if (isBbsDisconnectCommand(text.replace("\r", ""))) {
            pendingBbsTx = null;
            cancelRetransmitTick();
        } else if (pendingBbsTx != null) {
            toast("Waiting for BBS ACK — try again in a moment.");
            return;
        }
        byte[] all = text.getBytes(StandardCharsets.UTF_8);
        int pos = 0;
        while (pos < all.length) {
            int n = Math.min(MAX_PAYLOAD_BYTES, all.length - pos);
            byte[] chunk = new byte[n];
            System.arraycopy(all, pos, chunk, 0, n);
            if (!sendBbsIFrame(chunk)) {
                appendTranscript("! BBS TX failed\n");
                break;
            }
            pos += n;
            if (pendingBbsTx != null) {
                break;
            }
        }
        refreshStatus();
    }

    private boolean sendUiPayload(String destCall, int destSsid, byte[] payload) {
        if (btManager == null || !btManager.isConnected()) {
            return false;
        }
        if (localCall.isEmpty() || destCall.isEmpty()) {
            return false;
        }
        Ax25Frame frame = new Ax25Frame(
                localCall, localSsid, destCall, destSsid, payload);
        byte[] ax25 = frame.encode();
        boolean ok = btManager.sendKissFrame(ax25);
        Log.d("UVPro.Terminal", String.format(Locale.US,
                "tx ui %s-%d -> %s-%d ax25=%d ok=%s payload=\"%s\"",
                localCall, localSsid, destCall, destSsid, ax25.length, ok,
                sanitizeIncomingText(payload).replace('\n', ' ')));
        return ok;
    }

    private boolean sendBbsIFrame(byte[] payload) {
        if (btManager == null || !btManager.isConnected() || bbsPeerCall.isEmpty()) {
            return false;
        }
        int seq = bbsTxSeq & 0x07;
        Ax25Frame frame = Ax25Frame.createIFrame(
                localCall, localSsid, bbsPeerCall, bbsPeerSsid,
                seq, bbsRxSeq, payload);
        byte[] ax25 = frame.encode();
        boolean ok = btManager.sendKissFrame(ax25);
        if (ok) {
            long now = System.currentTimeMillis();
            pendingBbsTx = new PendingIFrame(seq, payload, now, 0);
            scheduleRetransmitTick();
        }
        Log.d("UVPro.Terminal", String.format(Locale.US,
                "tx if %s-%d -> %s-%d seq=%d ax25=%d ok=%s payload=\"%s\"",
                localCall, localSsid, bbsPeerCall, bbsPeerSsid, seq, ax25.length, ok,
                sanitizeIncomingText(payload).replace('\n', ' ')));
        return ok;
    }

    private void onRetransmitTick() {
        if (sessionState != SessionState.ACTIVE
                || bbsLinkState != BbsLinkState.CONNECTED
                || pendingBbsTx == null) {
            cancelRetransmitTick();
            return;
        }
        long now = System.currentTimeMillis();
        if ((now - pendingBbsTx.sentAtMs) < TX_ACK_TIMEOUT_MS) {
            scheduleRetransmitTick();
            return;
        }
        if (pendingBbsTx.retryCount >= TX_RETRY_LIMIT) {
            int timedOutSeq = pendingBbsTx.seq;
            pendingBbsTx = null;
            beginBbsReconnect(String.format(Locale.US, "BBS ACK timeout seq=%d", timedOutSeq));
            return;
        }
        if (retransmitBbsIFrame(pendingBbsTx)) {
            pendingBbsTx.retryCount++;
            pendingBbsTx.sentAtMs = now;
            appendTranscript(String.format(Locale.US,
                    "* Retransmit seq=%d retry=%d\n", pendingBbsTx.seq, pendingBbsTx.retryCount));
        }
        scheduleRetransmitTick();
    }

    private boolean retransmitBbsIFrame(PendingIFrame pending) {
        Ax25Frame frame = Ax25Frame.createIFrame(
                localCall, localSsid, bbsPeerCall, bbsPeerSsid,
                pending.seq, bbsRxSeq, pending.payload);
        return btManager != null && btManager.sendKissFrame(frame.encode());
    }

    private void onKeepaliveTick() {
        if (sessionState != SessionState.ACTIVE) {
            cancelKeepalive();
            return;
        }
        if (btManager == null || !btManager.isConnected()) {
            appendTranscript("* Radio Bluetooth disconnected\n");
            bbsLinkState = BbsLinkState.IDLE;
            cancelAllLinkTimers();
            refreshStatus();
            return;
        }
        if (bbsLinkState != BbsLinkState.CONNECTED) {
            scheduleKeepalive();
            return;
        }
        long now = System.currentTimeMillis();
        if (lastBbsRxMs > 0L && (now - lastBbsRxMs) > BBS_STALL_MS) {
            beginBbsReconnect("BBS link lost (no response)");
            return;
        }
        if (pendingBbsTx == null) {
            sendBbsRrAck();
        }
        scheduleKeepalive();
    }

    private void onSabmRetryTick() {
        if (sessionState != SessionState.ACTIVE
                || bbsLinkState != BbsLinkState.CONNECTING) {
            cancelSabmRetry();
            return;
        }
        if (sabmAttempts >= SABM_RETRY_LIMIT) {
            appendTranscript("! BBS connect timeout\n");
            cancelSabmRetry();
            return;
        }
        sabmAttempts++;
        sendSabm();
        sessionHandler.postDelayed(sabmRetryTick, SABM_RETRY_MS);
    }

    private void scheduleRetransmitTick() {
        sessionHandler.removeCallbacks(retransmitTick);
        sessionHandler.postDelayed(retransmitTick, 500L);
    }

    private void cancelRetransmitTick() {
        sessionHandler.removeCallbacks(retransmitTick);
    }

    private void scheduleKeepalive() {
        sessionHandler.removeCallbacks(keepaliveTick);
        sessionHandler.postDelayed(keepaliveTick, KEEPALIVE_MS);
    }

    private void cancelKeepalive() {
        sessionHandler.removeCallbacks(keepaliveTick);
    }

    private void scheduleSabmRetry() {
        sessionHandler.removeCallbacks(sabmRetryTick);
        sessionHandler.postDelayed(sabmRetryTick, SABM_RETRY_MS);
    }

    private void cancelSabmRetry() {
        sessionHandler.removeCallbacks(sabmRetryTick);
    }

    private void cancelAllLinkTimers() {
        cancelRetransmitTick();
        cancelKeepalive();
        cancelSabmRetry();
        cancelHangupTimers();
    }

    private void sendBbsRrAck() {
        if (bbsPeerCall.isEmpty() || btManager == null || !btManager.isConnected()) {
            return;
        }
        Ax25Frame rr = Ax25Frame.createRrFrame(
                localCall, localSsid, bbsPeerCall, bbsPeerSsid, bbsRxSeq);
        btManager.sendKissFrame(rr.encode());
    }

    private void sendSabm() {
        if (bbsPeerCall.isEmpty()) {
            return;
        }
        boolean ok = sendControlToBbs(Ax25Frame.CONTROL_SABM);
        Log.d("UVPro.Terminal", String.format(Locale.US,
                "tx sabm %s-%d -> %s-%d ok=%s",
                localCall, localSsid, bbsPeerCall, bbsPeerSsid, ok));
        if (!ok) {
            appendTranscript("! SABM TX failed\n");
        }
    }

    private boolean sendControlToBbs(byte controlField) {
        if (btManager == null || !btManager.isConnected()
                || localCall.isEmpty() || bbsPeerCall.isEmpty()) {
            return false;
        }
        Ax25Frame frame = Ax25Frame.createControlFrame(
                localCall, localSsid, bbsPeerCall, bbsPeerSsid, controlField);
        byte[] ax25 = frame.encode();
        boolean ok = btManager.sendKissFrame(ax25);
        Log.d("UVPro.Terminal", String.format(Locale.US,
                "tx ctrl 0x%02X %s-%d -> %s-%d ax25=%d ok=%s",
                controlField & 0xFF, localCall, localSsid, bbsPeerCall, bbsPeerSsid,
                ax25.length, ok));
        return ok;
    }

    private void warnIfKissNotLocked() {
        if (KissRadioFrequencyControl.isFrequencyLocked()) {
            return;
        }
        toast("Packet channel not in Digital/KISS lock — set Digital Only on your packet CH first.");
        appendTranscript("* Warning: KISS frequency not locked (use Digital Only on packet channel)\n");
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
        return normalizeCallsign(CallsignUtil.toRadioCallsign(mapCall));
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
        String node = nodeCall.isEmpty() ? "N/A"
                : String.format(Locale.US, "%s-%d", nodeCall, nodeSsid);
        String bbs = bbsPeerCall.isEmpty() ? "none"
                : String.format(Locale.US, "%s-%d %s",
                bbsPeerCall, bbsPeerSsid, bbsLinkState.name());
        String mode = sessionState == SessionState.ACTIVE ? "ACTIVE" : "IDLE";
        statusView.setText(String.format(Locale.US,
                "Radio: %s   Session: %s   Remote: %s   BBS: %s",
                radioConnected ? "CONNECTED" : "DISCONNECTED",
                mode, node, bbs));
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

    private void setSessionIdle() {
        cancelAllLinkTimers();
        hangupInProgress = false;
        hangupStep = 0;
        hangupHadConnectedLink = false;
        sessionState = SessionState.IDLE;
        nodeCall = "";
        nodeSsid = 0;
        bbsPeerCall = "";
        bbsPeerSsid = 0;
        bbsRxSeq = 0;
        bbsTxSeq = 0;
        bbsLinkState = BbsLinkState.IDLE;
        pendingBbsTx = null;
        sabmAttempts = 0;
        lastBbsRxMs = 0L;
    }

    private SharedPreferences terminalPrefs() {
        Context ctx = getMapView() != null ? getMapView().getContext() : pluginContext;
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void saveLastRemoteStation(String remoteCall, int remoteSsid) {
        if (remoteCall == null || remoteCall.isEmpty()) {
            return;
        }
        terminalPrefs().edit()
                .putString(PREF_LAST_REMOTE_CALL, remoteCall)
                .putInt(PREF_LAST_REMOTE_SSID, Math.max(0, Math.min(15, remoteSsid)))
                .apply();
    }

    private void restoreLastRemoteStation() {
        SharedPreferences prefs = terminalPrefs();
        String call = prefs.getString(PREF_LAST_REMOTE_CALL, "");
        if (call.isEmpty()) {
            return;
        }
        if (remoteCallInput != null) {
            remoteCallInput.setText(call);
        }
        if (remoteSsidInput != null && prefs.contains(PREF_LAST_REMOTE_SSID)) {
            remoteSsidInput.setText(String.valueOf(prefs.getInt(PREF_LAST_REMOTE_SSID, 0)));
        }
    }

    private void toast(String msg) {
        Context ctx = getMapView() != null ? getMapView().getContext() : pluginContext;
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDropDownVisible(boolean visible) {
        if (visible) {
            if (remoteCallInput != null
                    && remoteCallInput.getText().toString().trim().isEmpty()) {
                restoreLastRemoteStation();
            }
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
        localSsid = 0;
        if (packetRouter != null) {
            packetRouter.setAx25FrameListener(null);
        }
        panelView = null;
        transcriptScrollView = null;
        statusView = null;
        transcriptView = null;
        panelListenersAttached = false;
        remoteCallInput = null;
        remoteSsidInput = null;
        lineInput = null;
        connectButton = null;
        disconnectButton = null;
        sendButton = null;
        clearButton = null;
    }
}
