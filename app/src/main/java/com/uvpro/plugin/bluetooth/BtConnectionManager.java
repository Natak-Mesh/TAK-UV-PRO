package com.uvpro.plugin.bluetooth;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.uvpro.plugin.kiss.KissFrameDecoder;
import com.uvpro.plugin.kiss.KissFrameEncoder;
import com.uvpro.plugin.protocol.PacketRouter;
import com.uvpro.plugin.ui.SettingsFragment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages Bluetooth SPP connections to BTECH UV-PRO radios.
 *
 * The BTECH UV-PRO exposes a Bluetooth SPP (Serial Port Profile) service
 * that speaks the KISS TNC protocol. Data flows:
 *
 *   Android ←(BT SPP)→ BTECH Radio ←(RF)→ Other Radios
 *
 * This class handles:
 * - Discovering paired BTECH devices
 * - Establishing SPP connections
 * - Reading incoming KISS frames in a background thread
 * - Sending outbound KISS frames
 * - Auto-reconnection on connection loss
 * - Event-driven reconnect when the saved radio re-appears (ACL_CONNECTED; no polling)
 */
public class BtConnectionManager {

    private static final String TAG = "UVPro.BT";

    // Standard SPP UUID
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // BTECH radios often advertise with names containing these patterns
    private static final String[] BTECH_NAME_PATTERNS = {
            "UV-PRO", "BTECH", "GMRS-PRO", "UV-50PRO", "UVPRO",
            "UV-50X", "UV50", "PRO50", "VR-N76", "BT-TNC", "TNC"
    };

    private final Context context;
    private final PacketRouter packetRouter;
    private final KissFrameDecoder kissDecoder;
    private final KissFrameEncoder kissEncoder;

    private BluetoothAdapter btAdapter;
    private BluetoothSocket btSocket;
    private InputStream inputStream;
    private OutputStream outputStream;

    private Thread readThread;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean radioSilenceEnabled = new AtomicBoolean(false);
    private final AtomicLong lastIoActivityMs = new AtomicLong(0L);
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    private BluetoothDevice lastDevice;
    private BluetoothDevice pendingBondDevice;
    // Probe sockets that are already connected — reused by connect() to avoid double-connect
    private final ConcurrentHashMap<String, BluetoothSocket> openProbeSockets =
            new ConcurrentHashMap<>();
    private final java.util.HashSet<String> seenScanAddresses = new java.util.HashSet<>();
    private final AtomicBoolean scanCompleteNotified = new AtomicBoolean(false);
    private BroadcastReceiver discoveryReceiver;
    private BroadcastReceiver bondStateReceiver;
    private BroadcastReceiver aclReceiver;
    private boolean discoveryReceiverRegistered = false;
    private boolean bondReceiverRegistered = false;
    private boolean aclReceiverRegistered = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean scanTimeoutScheduled = new AtomicBoolean(false);
    private static final long SCAN_TIMEOUT_MS = 8000L;
    private static final long ACL_CONNECT_DEBOUNCE_MS = 1500L;
    /** Direct connect to saved bonded MAC — no discovery scan. */
    private static final long PASSIVE_RECONNECT_INTERVAL_MS = 60_000L;
    /** First passive attempt after mesh boot contention ends. */
    private static final long PASSIVE_RECONNECT_INITIAL_DELAY_MS = 3000L;
    private Runnable pendingAclConnectRunnable;
    private Runnable scanTimeoutRunnable;
    private Runnable passiveReconnectRunnable;
    private final AtomicBoolean passiveReconnectArmed = new AtomicBoolean(false);

    private final CopyOnWriteArrayList<ConnectionListener> listeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RawDataListener> rawDataListeners =
            new CopyOnWriteArrayList<>();
    private volatile ReconnectBlocker reconnectBlocker;
    private volatile MeshCoexistenceListener meshCoexistenceListener;

    /** Invoked while the socket is still open, before streams are closed. */
    private final CopyOnWriteArrayList<Runnable> beforeDisconnectHooks =
            new CopyOnWriteArrayList<>();

    public interface ConnectionListener {
        void onConnected(BluetoothDevice device);
        void onDisconnected(String reason);
        void onError(String error);
        void onDeviceFound(BluetoothDevice device);
        default void onScanComplete() {}
    }

    /**
     * Optional listener for raw bytes received from the radio.
     * Return true if the data was consumed and should not be processed as KISS.
     */
    public interface RawDataListener {
        boolean onRawBytes(byte[] data);
    }

    @FunctionalInterface
    public interface ReconnectBlocker {
        /**
         * @return true when UV-PRO auto-reconnect should be suppressed.
         */
        boolean shouldBlockReconnect();
    }

    /**
     * Notifies when a UV-PRO connect may disrupt an active MeshCore BLE session on the same
     * phone adapter, so mesh can be restored after the radio link settles.
     */
    public interface MeshCoexistenceListener {
        /** True while MeshCore BLE session is still being established. */
        boolean isMeshConnecting();
        /** True when MeshCore has an active BLE session. */
        boolean isMeshConnected();
        void onRadioConnectStartingWhileMeshUp();
    }

    public BtConnectionManager(Context context, PacketRouter packetRouter) {
        // Use ATAK's activity context for BT operations — the plugin context
        // runs under a different package and lacks ATAK's runtime permissions.
        Context atakContext = com.atakmap.android.maps.MapView.getMapView() != null
                ? com.atakmap.android.maps.MapView.getMapView().getContext()
                : context;
        this.context = atakContext;
        this.packetRouter = packetRouter;
        this.kissDecoder = new KissFrameDecoder();
        this.kissEncoder = new KissFrameEncoder();
        this.btAdapter = BluetoothAdapter.getDefaultAdapter();
        registerAclReceiver();
        // Passive radio reconnect is armed only after MeshCore releases priority (see
        // onMeshReleased / MapComponent boot resolution), not at construction time.
    }

    /** Release broadcast receivers when the plugin shuts down. */
    public void shutdown() {
        cancelPendingAclConnect();
        cancelPassiveReconnect();
        unregisterAclReceiver();
    }

    /**
     * Start (or keep) a low-cost passive reconnect loop: one direct connect attempt per minute
     * to the saved bonded radio. No discovery scans. Complements ACL events, which many Classic
     * BT radios never fire when they power on.
     */
    public void armPassiveReconnect() {
        resumeRadioAutoConnect(PASSIVE_RECONNECT_INTERVAL_MS);
    }

    /** Arm passive reconnect and schedule a near-term first attempt. */
    public void resumeRadioAutoConnect() {
        resumeRadioAutoConnect(PASSIVE_RECONNECT_INITIAL_DELAY_MS);
    }

    private void resumeRadioAutoConnect(long firstDelayMs) {
        if (connected.get() || connecting.get()) {
            return;
        }
        passiveReconnectArmed.set(true);
        if (passiveReconnectRunnable != null) {
            mainHandler.removeCallbacks(passiveReconnectRunnable);
            passiveReconnectRunnable = null;
        }
        Log.i(TAG, "Radio auto-connect resumed (first attempt in "
                + (firstDelayMs / 1000) + "s, target="
                + getSavedRadioTargetAddress() + ")");
        scheduleNextPassiveReconnect(firstDelayMs);
    }

    public void setReconnectBlocker(ReconnectBlocker blocker) {
        reconnectBlocker = blocker;
    }

    public void setMeshCoexistenceListener(MeshCoexistenceListener listener) {
        meshCoexistenceListener = listener;
    }

    /** Resume UV-PRO auto-connect once MeshCore boot contention is over. */
    public void onMeshReleased() {
        if (!shouldReconnect.get() || connected.get() || connecting.get()) {
            return;
        }
        resumeRadioAutoConnect();
    }

    private boolean isMeshAutoConnectBlocked() {
        MeshCoexistenceListener listener = meshCoexistenceListener;
        if (listener == null) {
            return false;
        }
        return listener.isMeshConnecting();
    }

    /**
     * Scan for already-paired UV-PRO radios.
     * Emits bonded radios immediately, then discovery for bonded devices only.
     * Pairing must be done in Android Bluetooth settings before using Scan & Connect.
     */
    public void startScan() {
        if (btAdapter == null) {
            notifyError("Bluetooth not available on this device");
            return;
        }
        if (!btAdapter.isEnabled()) {
            notifyError("Bluetooth is disabled. Please enable it.");
            return;
        }
        if (!checkBtPermissions()) {
            notifyError("Bluetooth permission denied. Grant in Settings > Apps.");
            return;
        }

        stopDiscoveryIfRunning();
        cancelScanTimeout();
        scanCompleteNotified.set(false);
        synchronized (seenScanAddresses) {
            seenScanAddresses.clear();
        }

        // Emit already-bonded UV-PRO radios immediately so the picker always
        // shows paired radios even if discovery is slow or unavailable.
        emitBondedRadioCandidates();

        registerDiscoveryReceiverIfNeeded();
        boolean started = false;
        try {
            started = btAdapter.startDiscovery();
        } catch (Exception e) {
            Log.w(TAG, "startDiscovery failed", e);
        }
        if (!started) {
            notifyScanCompleteOnce();
            return;
        }
        scheduleScanTimeout();
    }

    private void emitBondedRadioCandidates() {
        try {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded == null) return;
            for (BluetoothDevice device : bonded) {
                if (isLikelyUvProDevice(device) && markSeenIfNew(device.getAddress())) {
                    notifyDeviceFound(device);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate bonded radios", e);
        }
    }

    /** True if Android Bluetooth has at least one bonded UV-PRO-class radio. */
    public boolean hasBondedUvProRadio() {
        if (btAdapter == null) {
            return false;
        }
        try {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded == null) {
                return false;
            }
            for (BluetoothDevice device : bonded) {
                if (isLikelyUvProDevice(device)) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not check bonded radios", e);
        }
        return false;
    }

    /** Stores a probe socket that connect() can reuse to avoid a double-connect. */
    public void addProbeSocket(String address, BluetoothSocket socket) {
        openProbeSockets.put(address, socket);
    }

    /** Closes and discards all open probe sockets. */
    public void clearProbeSockets() {
        for (BluetoothSocket s : openProbeSockets.values()) {
            try { s.close(); } catch (Exception ignored) {}
        }
        openProbeSockets.clear();
    }


    /**
     * Check (and request if possible) Bluetooth runtime permissions for Android 12+.
     * @return true if permissions are granted
     */
    private boolean checkBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true; // Pre-Android 12 doesn't need runtime BT permissions
        }
        boolean connectGranted = context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
        boolean scanGranted = context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;

        if (connectGranted && scanGranted) {
            return true;
        }

        // Try to request permissions if we can get an Activity
        requestBtPermissions();
        return false;
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            // ATAK MapView.getMapView().getContext() returns the Activity
            Context ctx = context;
            if (ctx instanceof Activity) {
                ((Activity) ctx).requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                }, 1001);
            } else {
                // Try via MapView
                com.atakmap.android.maps.MapView mv =
                        com.atakmap.android.maps.MapView.getMapView();
                if (mv != null && mv.getContext() instanceof Activity) {
                    ((Activity) mv.getContext()).requestPermissions(
                            new String[]{
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.BLUETOOTH_SCAN
                            }, 1001);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not request BT permissions", e);
        }
    }

    /**
     * Connect to a specific Bluetooth device.
     * The device must already be paired via Android Bluetooth settings.
     * Tries multiple socket strategies to handle various Android BT quirks.
     */
    public void connect(BluetoothDevice device) {
        if (device == null) {
            notifyError("Invalid Bluetooth device");
            return;
        }
        String targetAddr = device.getAddress();
        if (isConnectedToAddress(targetAddr)) {
            Log.d(TAG, "Already connected to " + resolveName(device) + " — ignoring duplicate connect");
            cancelPendingAclConnect();
            return;
        }
        if (connecting.get() && isConnectingToAddress(targetAddr)) {
            Log.d(TAG, "Already connecting to " + resolveName(device) + " — ignoring duplicate connect");
            cancelPendingAclConnect();
            return;
        }
        if (connected.get()) {
            disconnect();
        }
        if (connecting.getAndSet(true)) {
            Log.w(TAG, "Already connecting, ignoring duplicate request");
            return;
        }

        lastDevice = device;
        shouldReconnect.set(true);
        reconnectAttempts = 0;

        MeshCoexistenceListener coexistence = meshCoexistenceListener;
        if (coexistence != null && coexistence.isMeshConnected()) {
            Log.i(TAG, "Radio connect while MeshCore is up — mesh restore will run if BLE drops");
            coexistence.onRadioConnectStartingWhileMeshUp();
        }

        int bondState = BluetoothDevice.BOND_NONE;
        try {
            bondState = device.getBondState();
        } catch (Exception ignored) {
        }
        if (bondState != BluetoothDevice.BOND_BONDED) {
            notifyError(resolveName(device) + " is not paired. Pair it in Android Bluetooth settings first.");
            connecting.set(false);
            return;
        }

        new Thread(() -> {
            try {
                String devName = device.getName() != null ? device.getName() : device.getAddress();
                Log.i(TAG, "Connecting to " + devName + "...");

                // Cancel discovery to speed up connection
                if (btAdapter.isDiscovering()) {
                    btAdapter.cancelDiscovery();
                }

                // Strategy 0: Reuse probe socket if we have one already open from startScan()
                BluetoothSocket socket = null;
                BluetoothSocket probeSocket = openProbeSockets.remove(device.getAddress());
                if (probeSocket != null && probeSocket.isConnected()) {
                    Log.i(TAG, "Reusing probe socket for " + devName);
                    socket = probeSocket;
                    // Close any other leftover probe sockets we won't use
                    for (BluetoothSocket s : openProbeSockets.values()) {
                        try { s.close(); } catch (Exception ignored) {}
                    }
                    openProbeSockets.clear();
                }

                // Strategy 1: Standard SPP UUID
                if (socket == null) {
                    socket = tryConnect(device, "SPP UUID", () ->
                            device.createRfcommSocketToServiceRecord(SPP_UUID));
                }

                // Strategy 2: Reflection-based createRfcommSocket on channel 1
                if (socket == null) {
                    socket = tryConnect(device, "RFCOMM ch1", () -> {
                        Method m = device.getClass().getMethod(
                                "createRfcommSocket", int.class);
                        return (BluetoothSocket) m.invoke(device, 1);
                    });
                }

                // Strategy 3: Insecure SPP (no encryption handshake)
                if (socket == null) {
                    socket = tryConnect(device, "Insecure SPP", () ->
                            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID));
                }

                if (socket == null) {
                    notifyError("All connection methods failed for " + devName
                            + ". Try turning the radio off/on and re-pairing.");
                    connecting.set(false);
                    if (shouldReconnect.get()) {
                        scheduleReconnect();
                    }
                    return;
                }

                btSocket = socket;
                inputStream = btSocket.getInputStream();
                outputStream = btSocket.getOutputStream();
                connected.set(true);
                connecting.set(false);
                reconnectAttempts = 0;
                markIoActivity();
                cancelPendingAclConnect();

                // Reset decoder state from any previous partial frames
                kissDecoder.reset();

                Log.i(TAG, "Connected to " + devName);
                notifyConnected(device);

                // Start reading KISS frames
                startReadThread();

            } catch (Exception e) {
                Log.e(TAG, "Connection failed: " + e.getMessage());
                notifyError("Connection failed: " + e.getMessage());
                cleanup();
                connecting.set(false);

                // Auto-reconnect
                if (shouldReconnect.get()) {
                    scheduleReconnect();
                }
            }
        }, "BT-Connect").start();
    }

    /**
     * Try a single socket connection strategy.
     * @return Connected socket, or null if failed.
     */
    private BluetoothSocket tryConnect(BluetoothDevice device, String label,
                                       SocketFactory factory) {
        try {
            Log.d(TAG, "Trying " + label + "...");
            BluetoothSocket socket = factory.create();
            socket.connect();
            Log.i(TAG, "Connected via " + label);
            return socket;
        } catch (Exception e) {
            Log.w(TAG, label + " failed: " + e.getMessage());
            return null;
        }
    }

    @FunctionalInterface
    private interface SocketFactory {
        BluetoothSocket create() throws Exception;
    }

    /**
     * Connect to the last known device.
     */
    public void connectToLastDevice() {
        if (lastDevice != null) {
            connect(lastDevice);
        } else {
            // Try to find a BTECH radio in paired devices
            startScan();
        }
    }

    /**
     * Disconnect from the radio.
     */
    public void disconnect() {
        shouldReconnect.set(false);
        connecting.set(false);
        connected.set(false);
        cancelPassiveReconnect();
        cleanup();
        notifyDisconnected("User disconnected");
    }

    /**
     * Cancel any in-progress connect attempt and stop auto-reconnect.
     * Useful when the user explicitly wants to scan/select another device.
     */
    public void cancelConnectionAttempts() {
        shouldReconnect.set(false);
        reconnectAttempts = 0;
        connecting.set(false);
        connected.set(false);
        pendingBondDevice = null;
        cancelPassiveReconnect();
        stopDiscoveryIfRunning();        cancelScanTimeout();
        cleanup();
        clearProbeSockets();
        notifyDisconnected("Connection attempt cancelled");
    }

    /**
     * Send raw data through the KISS TNC to the radio.
     * The data should be an AX.25 frame (without KISS framing — we add that).
     */
    public boolean sendKissFrame(byte[] ax25Frame) {
        if (!connected.get() || outputStream == null) {
            Log.w(TAG, "Cannot send: not connected");
            return false;
        }
        if (radioSilenceEnabled.get()) {
            Log.w(TAG, "TX blocked by Radio Silence");
            return false;
        }

        try {
            byte[] kissFrame = kissEncoder.encode(ax25Frame);
            byte[] wireBytes = java.util.Arrays.copyOf(kissFrame, kissFrame.length);
            java.util.Arrays.fill(kissFrame, (byte) 0);
            outputStream.write(wireBytes);
            outputStream.flush();
            markIoActivity();
            packetRouter.notifyPacketTransmitted();
            Log.d(TAG, "Sent KISS frame: " + wireBytes.length + " bytes");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Send failed: " + e.getMessage());
            handleConnectionLost();
            return false;
        }
    }

    /**
     * Send HT Commander control commands over Bluetooth (not over-the-air KISS/AX.25).
     * Radio Silence does not block these — only {@link #sendKissFrame} RF traffic is inhibited.
     */
    public boolean sendRawBytes(byte[] data) {
        if (!connected.get() || outputStream == null) {
            Log.w(TAG, "Cannot send raw bytes: not connected");
            return false;
        }
        try {
            outputStream.write(data);
            outputStream.flush();
            markIoActivity();
            Log.d(TAG, "Sent raw bytes: " + data.length + " bytes");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Raw send failed: " + e.getMessage());
            handleConnectionLost();
            return false;
        }
    }

    /**
     * Radio Silence blocks outbound KISS/AX.25 (chat, ACKs, beacons, CoT) while receive and
     * Bluetooth radio-control commands ({@link #sendRawBytes}) remain active.
     */
    public void setRadioSilenceEnabled(boolean enabled) {
        radioSilenceEnabled.set(enabled);
        Log.i(TAG, "Radio Silence " + (enabled ? "enabled" : "disabled"));
    }

    public boolean isRadioSilenceEnabled() {
        return radioSilenceEnabled.get();
    }

    /**
     * Background thread that continuously reads KISS frames from the radio.
     */
    private void startReadThread() {
        readThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            Log.i(TAG, "Read thread started");

            while (connected.get()) {
                try {
                    int bytesRead = inputStream.read(buffer);
                    if (bytesRead > 0) {
                        markIoActivity();
                        byte[] data = new byte[bytesRead];
                        System.arraycopy(buffer, 0, data, 0, bytesRead);
                        java.util.Arrays.fill(buffer, (byte) 0);

                        boolean consumed = false;
                        for (RawDataListener listener : rawDataListeners) {
                            try {
                                if (listener.onRawBytes(data)) {
                                    consumed = true;
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "RawDataListener failed: " + e.getMessage());
                            }
                        }
                        if (consumed) {
                            continue;
                        }

                        // KissFrameDecoder accumulates bytes and emits
                        // complete AX.25 frames when FEND delimiters are found
                        byte[][] frames = kissDecoder.decode(data);
                        for (byte[] frame : frames) {
                            Log.d(TAG, "Received AX.25 frame: " + frame.length + " bytes");
                            packetRouter.routeIncoming(frame);
                        }
                    } else if (bytesRead == -1) {
                        // Stream ended
                        handleConnectionLost();
                        break;
                    }
                } catch (IOException e) {
                    if (connected.get()) {
                        Log.e(TAG, "Read error: " + e.getMessage());
                        handleConnectionLost();
                    }
                    break;
                }
            }

            Log.i(TAG, "Read thread stopped");
        }, "BT-Read");

        readThread.setDaemon(true);
        readThread.start();
    }

    private void handleConnectionLost() {
        connected.set(false);
        cleanup();
        notifyDisconnected("Connection lost");

        if (shouldReconnect.get()) {
            scheduleReconnect();
            armPassiveReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!isAutoReconnectEnabled()) {
            return;
        }
        if (isMeshAutoConnectBlocked()) {
            Log.d(TAG, "Reconnect deferred — MeshCore has priority");
            return;
        }
        if (lastDevice == null) return;
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached (" + MAX_RECONNECT_ATTEMPTS
                    + "). Falling back to passive reconnect.");
            notifyError("Reconnect failed after " + MAX_RECONNECT_ATTEMPTS
                    + " attempts. Will keep trying periodically.");
            armPassiveReconnect();
            return;
        }

        reconnectAttempts++;
        int delaySec = 5 * reconnectAttempts; // Back off: 5s, 10s, 15s...
        Log.i(TAG, "Scheduling reconnect #" + reconnectAttempts + " in " + delaySec + " seconds...");
        new Thread(() -> {
            try {
                Thread.sleep(delaySec * 1000L);
                if (shouldReconnect.get() && !connected.get() && !connecting.get()) {
                    Log.i(TAG, "Attempting reconnect #" + reconnectAttempts + "...");
                    connect(lastDevice);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "BT-Reconnect").start();
    }

    private boolean isReconnectBlocked() {
        ReconnectBlocker blocker = reconnectBlocker;
        if (blocker == null) {
            return false;
        }
        try {
            return blocker.shouldBlockReconnect();
        } catch (Exception e) {
            Log.w(TAG, "Reconnect blocker check failed", e);
            return false;
        }
    }

    private void cleanup() {
        runBeforeDisconnectHooks();
        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException ignored) {}
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) {}
        try {
            if (btSocket != null) btSocket.close();
        } catch (IOException ignored) {}

        inputStream = null;
        outputStream = null;
        btSocket = null;
    }

    private boolean isBtechDevice(String name) {
        String upper = name.toUpperCase();
        for (String pattern : BTECH_NAME_PATTERNS) {
            if (upper.contains(pattern)) return true;
        }
        return false;
    }

    private boolean isLikelyUvProDevice(BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        String name = null;
        try {
            name = device.getName();
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        return isBtechDevice(name);
    }

    private String resolveName(BluetoothDevice device) {
        if (device == null) {
            return "Radio";
        }
        try {
            String name = device.getName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (Exception ignored) {
        }
        String addr = device.getAddress();
        return addr != null ? addr : "Radio";
    }

    private void recordSeenAddress(String address) {
        if (address == null || address.isEmpty()) {
            return;
        }
        synchronized (seenScanAddresses) {
            seenScanAddresses.add(address);
        }
    }

    private boolean markSeenIfNew(String address) {
        if (address == null || address.isEmpty()) {
            return true;
        }
        synchronized (seenScanAddresses) {
            if (seenScanAddresses.contains(address)) {
                return false;
            }
            seenScanAddresses.add(address);
            return true;
        }
    }

    private boolean isAutoReconnectEnabled() {
        try {
            return SettingsFragment.isAutoReconnectEnabled(context);
        } catch (Exception e) {
            return true;
        }
    }

    private void registerAclReceiver() {
        if (aclReceiverRegistered) {
            return;
        }
        aclReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) {
                    return;
                }
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        onAclConnected(device);
                    }
                } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR);
                    if (state == BluetoothAdapter.STATE_ON) {
                        onBluetoothEnabled();
                    }
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            context.registerReceiver(aclReceiver, filter);
            aclReceiverRegistered = true;
            Log.d(TAG, "ACL reconnect listener registered");
        } catch (Exception e) {
            Log.w(TAG, "Could not register ACL receiver", e);
        }
    }

    private void unregisterAclReceiver() {
        if (!aclReceiverRegistered || aclReceiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(aclReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Could not unregister ACL receiver", e);
        } finally {
            aclReceiver = null;
            aclReceiverRegistered = false;
        }
    }

    private void cancelPendingAclConnect() {
        if (pendingAclConnectRunnable != null) {
            mainHandler.removeCallbacks(pendingAclConnectRunnable);
            pendingAclConnectRunnable = null;
        }
    }

    private void onAclConnected(BluetoothDevice device) {
        if (device == null || device.getAddress() == null) {
            return;
        }
        String addr = device.getAddress();
        if (isConnectedToAddress(addr) || isConnectingToAddress(addr)) {
            cancelPendingAclConnect();
            return;
        }
        String saved = getSavedRadioTargetAddress();
        Log.d(TAG, "ACL connected " + addr + " (saved target="
                + (saved != null ? saved : "none") + ")");
        if (!isSavedRadioTarget(addr)) {
            return;
        }
        Log.i(TAG, "ACL connected for saved radio " + addr + " — scheduling plugin connect");
        scheduleAclConnect(device, "acl-connected");
    }

    private void onBluetoothEnabled() {
        if (!isAutoReconnectEnabled() || !shouldReconnect.get()) {
            return;
        }
        if (connected.get() || connecting.get()) {
            return;
        }
        String tgt = getSavedRadioTargetAddress();
        if (tgt == null || tgt.isEmpty() || btAdapter == null) {
            return;
        }
        try {
            BluetoothDevice device = btAdapter.getRemoteDevice(tgt);
            Log.i(TAG, "Bluetooth enabled — scheduling connect to saved radio " + tgt);
            scheduleAclConnect(device, "bt-enabled");
        } catch (Exception e) {
            Log.w(TAG, "Could not schedule connect after BT enabled", e);
        }
    }

    private void scheduleAclConnect(BluetoothDevice device, String reason) {
        if (device == null) {
            return;
        }
        cancelPendingAclConnect();
        final BluetoothDevice target = device;
        pendingAclConnectRunnable = () -> {
            pendingAclConnectRunnable = null;
            maybeConnectToSavedRadio(target, reason);
        };
        mainHandler.postDelayed(pendingAclConnectRunnable, ACL_CONNECT_DEBOUNCE_MS);
    }

    /**
     * Connect to the saved radio when it re-appears at the BT stack (ACL) without polling.
     * Skipped after explicit user Disconnect or when auto-reconnect is disabled in settings.
     */
    private void maybeConnectToSavedRadio(BluetoothDevice device, String reason) {
        if (device == null) {
            return;
        }
        if (!isAutoReconnectEnabled()) {
            Log.d(TAG, "ACL connect skipped (" + reason + "): auto-reconnect disabled");
            return;
        }
        if (!shouldReconnect.get()) {
            Log.d(TAG, "ACL connect skipped (" + reason + "): user stopped reconnect");
            return;
        }
        if (isMeshAutoConnectBlocked()) {
            Log.d(TAG, "Radio connect skipped (" + reason + "): MeshCore has priority");
            return;
        }
        String addr = device.getAddress();
        if (isConnectedToAddress(addr) || isConnectingToAddress(addr)) {
            Log.d(TAG, "ACL connect skipped (" + reason + "): already linked to " + addr);
            return;
        }
        if (!isSavedRadioTarget(addr)) {
            return;
        }
        int bondState = BluetoothDevice.BOND_NONE;
        try {
            bondState = device.getBondState();
        } catch (Exception ignored) {
        }
        if (bondState != BluetoothDevice.BOND_BONDED) {
            Log.d(TAG, "ACL connect skipped (" + reason + "): not bonded");
            return;
        }
        reconnectAttempts = 0;
        Log.i(TAG, "ACL trigger (" + reason + "): connecting to " + resolveName(device));
        connect(device);
    }

    private boolean isConnectedToAddress(String address) {
        if (!connected.get() || address == null || lastDevice == null) {
            return false;
        }
        String linked = lastDevice.getAddress();
        return linked != null && linked.equalsIgnoreCase(address);
    }

    private boolean isConnectingToAddress(String address) {
        if (!connecting.get() || address == null || lastDevice == null) {
            return false;
        }
        String linked = lastDevice.getAddress();
        return linked != null && linked.equalsIgnoreCase(address);
    }

    private String getSavedRadioTargetAddress() {
        try {
            return BluetoothDeviceRegistry.getConnectTargetAddress(context);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSavedRadioTarget(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        String tgt = getSavedRadioTargetAddress();
        return tgt != null && !tgt.isEmpty() && tgt.equalsIgnoreCase(address);
    }

    private void cancelPassiveReconnect() {
        passiveReconnectArmed.set(false);
        if (passiveReconnectRunnable != null) {
            mainHandler.removeCallbacks(passiveReconnectRunnable);
            passiveReconnectRunnable = null;
        }
    }

    private boolean shouldRunPassiveReconnect() {
        if (!passiveReconnectArmed.get() || !isAutoReconnectEnabled() || !shouldReconnect.get()) {
            return false;
        }
        if (isMeshAutoConnectBlocked()) {
            return false;
        }
        if (connected.get() || connecting.get()) {
            return false;
        }
        String tgt = getSavedRadioTargetAddress();
        return tgt != null && !tgt.isEmpty();
    }

    private void scheduleNextPassiveReconnect(long delayMs) {
        if (!shouldRunPassiveReconnect()) {
            cancelPassiveReconnect();
            return;
        }
        if (passiveReconnectRunnable != null) {
            mainHandler.removeCallbacks(passiveReconnectRunnable);
        }
        passiveReconnectRunnable = () -> {
            passiveReconnectRunnable = null;
            if (!shouldRunPassiveReconnect()) {
                cancelPassiveReconnect();
                return;
            }
            String tgt = getSavedRadioTargetAddress();
            if (tgt == null || tgt.isEmpty() || btAdapter == null) {
                scheduleNextPassiveReconnect(PASSIVE_RECONNECT_INTERVAL_MS);
                return;
            }
            try {
                BluetoothDevice device = btAdapter.getRemoteDevice(tgt);
                Log.d(TAG, "Passive reconnect attempt for saved radio " + tgt);
                maybeConnectToSavedRadio(device, "passive-watch");
            } catch (Exception e) {
                Log.w(TAG, "Passive reconnect attempt failed", e);
            }
            scheduleNextPassiveReconnect(PASSIVE_RECONNECT_INTERVAL_MS);
        };
        mainHandler.postDelayed(passiveReconnectRunnable, delayMs);
    }

    private void registerDiscoveryReceiverIfNeeded() {
        if (discoveryReceiverRegistered) {
            return;
        }
        discoveryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device == null || !isLikelyUvProDevice(device)) {
                        return;
                    }
                    String address = device.getAddress();
                    if (!markSeenIfNew(address)) {
                        return;
                    }
                    int bondState = BluetoothDevice.BOND_NONE;
                    try {
                        bondState = device.getBondState();
                    } catch (Exception ignored) {
                    }
                    if (bondState != BluetoothDevice.BOND_BONDED) {
                        Log.d(TAG, "Ignoring unpaired UV-PRO during scan (pair in Android BT settings): "
                                + resolveName(device));
                        return;
                    }
                    notifyDeviceFound(device);
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    notifyScanCompleteOnce();
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            context.registerReceiver(discoveryReceiver, filter);
            discoveryReceiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "Could not register discovery receiver", e);
            notifyScanCompleteOnce();
        }
    }

    private void registerBondReceiver() {
        if (bondReceiverRegistered) {
            return;
        }
        bondStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null
                        || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                    return;
                }
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null || pendingBondDevice == null) {
                    return;
                }
                String addr = device.getAddress();
                String pendingAddr = pendingBondDevice.getAddress();
                if (addr == null || pendingAddr == null || !addr.equalsIgnoreCase(pendingAddr)) {
                    return;
                }
                int bondState = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                int prevBondState = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    pendingBondDevice = null;
                    notifyError("Pairing complete for " + resolveName(device) + ". Connecting...");
                    connecting.set(false);
                    connect(device);
                } else if (bondState == BluetoothDevice.BOND_NONE
                        && prevBondState == BluetoothDevice.BOND_BONDING) {
                    pendingBondDevice = null;
                    connecting.set(false);
                    notifyError("Pairing failed or cancelled for " + resolveName(device));
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            context.registerReceiver(bondStateReceiver, filter);
            bondReceiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "Could not register bond receiver", e);
        }
    }

    private void stopDiscoveryIfRunning() {
        if (btAdapter == null) {
            return;
        }
        try {
            if (btAdapter.isDiscovering()) {
                btAdapter.cancelDiscovery();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not cancel discovery", e);
        }
    }

    private void scheduleScanTimeout() {
        if (scanTimeoutRunnable != null) {
            mainHandler.removeCallbacks(scanTimeoutRunnable);
            scanTimeoutRunnable = null;
        }
        if (!scanTimeoutScheduled.compareAndSet(false, true)) {
            return;
        }
        scanTimeoutRunnable = () -> {
            scanTimeoutScheduled.set(false);
            scanTimeoutRunnable = null;
            if (scanCompleteNotified.get()) {
                return;
            }
            Log.i(TAG, "Scan timeout reached; finishing discovery");
            stopDiscoveryIfRunning();
            notifyScanCompleteOnce();
        };
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
    }

    private void cancelScanTimeout() {
        scanTimeoutScheduled.set(false);
        if (scanTimeoutRunnable != null) {
            mainHandler.removeCallbacks(scanTimeoutRunnable);
            scanTimeoutRunnable = null;
        }
    }

    private void notifyScanCompleteOnce() {
        cancelScanTimeout();
        if (scanCompleteNotified.compareAndSet(false, true)) {
            notifyScanComplete();
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isConnecting() {
        return connecting.get();
    }

    public long getLastIoActivityMs() {
        return lastIoActivityMs.get();
    }

    public boolean hasRecentIo(long withinMs) {
        long last = lastIoActivityMs.get();
        if (last <= 0L) {
            return false;
        }
        long window = Math.max(0L, withinMs);
        return (System.currentTimeMillis() - last) <= window;
    }

    public String getConnectedDeviceName() {
        if (!connected.get()) return null;
        if (lastDevice != null) {
            String name = lastDevice.getName();
            return name != null ? name : lastDevice.getAddress();
        }
        return "Radio";
    }

    // --- Listener management ---

    public void addListener(ConnectionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ConnectionListener listener) {
        listeners.remove(listener);
    }

    public void addRawDataListener(RawDataListener listener) {
        rawDataListeners.add(listener);
    }

    public void removeRawDataListener(RawDataListener listener) {
        rawDataListeners.remove(listener);
    }

    public void addBeforeDisconnectHook(Runnable hook) {
        if (hook != null) {
            beforeDisconnectHooks.add(hook);
        }
    }

    public void removeBeforeDisconnectHook(Runnable hook) {
        beforeDisconnectHooks.remove(hook);
    }

    private void runBeforeDisconnectHooks() {
        for (Runnable hook : beforeDisconnectHooks) {
            try {
                hook.run();
            } catch (Exception e) {
                Log.w(TAG, "beforeDisconnect hook failed: " + e.getMessage());
            }
        }
    }

    private void notifyConnected(BluetoothDevice device) {
        cancelPassiveReconnect();
        for (ConnectionListener l : listeners) l.onConnected(device);
    }

    private void notifyDisconnected(String reason) {
        for (ConnectionListener l : listeners) l.onDisconnected(reason);
    }

    private void notifyError(String error) {
        for (ConnectionListener l : listeners) l.onError(error);
    }

    private void notifyDeviceFound(BluetoothDevice device) {
        for (ConnectionListener l : listeners) l.onDeviceFound(device);
    }

    private void notifyScanComplete() {
        for (ConnectionListener l : listeners) l.onScanComplete();
    }

    private void markIoActivity() {
        lastIoActivityMs.set(System.currentTimeMillis());
    }
}
