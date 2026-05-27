package com.uvpro.plugin.bluetooth;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.util.Base64;
import android.util.Log;

import com.uvpro.plugin.protocol.PacketRouter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MeshCore BLE companion transport used by UV-PRO-MESH plugin mode.
 */
public class MeshBtConnectionManager extends BtConnectionManager {

    private static final String TAG = "UVPro.MeshBLE";

    private static final UUID UUID_UART_SERVICE =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    // Meshtastic primary BLE service UUID.
    private static final UUID UUID_MESHTASTIC_SERVICE =
            UUID.fromString("6BA1B218-15A8-461F-9FA8-5DCAE273EAFD");
    private static final UUID UUID_UART_RX =
            UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UUID_UART_TX =
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UUID_CCC =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private static final byte CMD_APP_START = 0x01;
    private static final byte CMD_SEND_CHANNEL_MSG = 0x03;
    private static final byte CMD_GET_NEXT_MSG = 0x0A;
    private static final byte CMD_DEVICE_QUERY = 0x16;
    private static final byte CMD_DEVICE_QUERY_ARG = 0x03;
    private static final byte CMD_GET_CHANNEL = 0x1F;
    private static final byte CMD_SET_CHANNEL = 0x20;
    private static final byte CMD_GET_GPS_STATE = 0x28;
    private static final byte CMD_SET_SETTING_TEXT = 0x29;
    private static final byte CMD_SEND_CHANNEL_DATA = 0x3E;

    private static final byte RESP_CHANNEL_MSG = 0x08;
    private static final byte RESP_CONTACT_MSG = 0x07;
    private static final byte RESP_SELF_INFO = 0x05;
    private static final byte RESP_DEVICE_INFO = 0x0D;
    private static final byte RESP_CHANNEL_MSG_V3 = 0x11;
    private static final byte RESP_CONTACT_MSG_V3 = 0x10;
    private static final byte RESP_CHANNEL_INFO = 0x12;
    private static final byte RESP_SETTING_TEXT = 0x15;
    private static final byte RESP_CHANNEL_DATA_RECV = 0x1B;
    private static final byte RESP_NO_MORE_MSGS = 0x0A;
    private static final byte PUSH_MESSAGES_WAITING = (byte) 0x83;

    private static final int MAX_MESH_MESSAGE_LEN = 130;
    private static final int MAX_RAW_AX25_CHUNK = 57;
    private static final String ENV_PREFIX = "UVAX1|";
    // Use MeshCore companion app-id for firmware compatibility with settings commands.
    private static final String COMPANION_APP_ID = "meshcore-flutter";
    private static final int ATAK_CHANNEL_INDEX = 7;
    private static final String ATAK_CHANNEL_NAME = "ATAK_DATA";
    private static final byte[] ATAK_CHANNEL_SECRET = new byte[]{
            (byte) 0xA3, (byte) 0x74, (byte) 0x1E, (byte) 0x6A,
            (byte) 0x52, (byte) 0x9C, (byte) 0xCF, (byte) 0x31,
            (byte) 0xD0, (byte) 0x4B, (byte) 0x89, (byte) 0xFE,
            (byte) 0x17, (byte) 0x63, (byte) 0xB8, (byte) 0x2D
    };
    private static final int ATAK_DATA_TYPE_AX25 = 0xFF01;
    private static final int ATAK_DATA_TYPE_RAW = 0xFF02;
    private static final int OUT_PATH_FLOOD = 0xFF;

    private final Context context;
    private final PacketRouter packetRouter;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicBoolean radioSilenceEnabled = new AtomicBoolean(false);
    private final AtomicBoolean scanCompleteNotified = new AtomicBoolean(false);
    private final Set<String> seenScanAddresses = new HashSet<>();
    private final AtomicLong lastIoActivityMs = new AtomicLong(0L);
    private final AtomicInteger outboundMsgId = new AtomicInteger(1);

    private final CopyOnWriteArrayList<ConnectionListener> listeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RawDataListener> rawDataListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> beforeDisconnectHooks =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MeshStateListener> meshStateListeners =
            new CopyOnWriteArrayList<>();

    private final Map<Integer, ChunkAccumulator> chunkBuffers = new ConcurrentHashMap<>();
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private boolean writeInFlight = false;

    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxCharacteristic;
    private BluetoothGattCharacteristic txCharacteristic;
    private BluetoothDevice lastDevice;
    private BluetoothDevice pendingBondDevice;
    private volatile Boolean meshGpsEnabled = null;
    private volatile MeshLocationFix latestSelfLocation = null;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    private final HandlerThread ioThread = new HandlerThread("UVPro-MeshBLE-IO");
    private Handler ioHandler;
    private final Runnable periodicMessagePoll = new Runnable() {
        @Override
        public void run() {
            if (!connected.get()) {
                return;
            }
            enqueueCommand(buildGetNextMessageCommand());
            ioHandler.postDelayed(this, 2500L);
        }
    };

    public MeshBtConnectionManager(Context context, PacketRouter packetRouter) {
        super(context, packetRouter);
        Context atakContext = com.atakmap.android.maps.MapView.getMapView() != null
                ? com.atakmap.android.maps.MapView.getMapView().getContext()
                : context;
        this.context = atakContext;
        this.packetRouter = packetRouter;
        this.btAdapter = BluetoothAdapter.getDefaultAdapter();
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
        registerBondReceiver();
    }

    public interface MeshStateListener {
        void onMeshGpsStateChanged(boolean enabled);
        void onMeshSelfLocationUpdated(MeshLocationFix fix);
    }

    public static final class MeshLocationFix {
        public final double latitude;
        public final double longitude;
        public final long receivedAtMs;
        public final String nodeName;

        public MeshLocationFix(double latitude, double longitude, long receivedAtMs, String nodeName) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.receivedAtMs = receivedAtMs;
            this.nodeName = nodeName;
        }

        public boolean isValid() {
            if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
                return false;
            }
            if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
                return false;
            }
            return !(Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001);
        }
    }

    @Override
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

        stopScanInternal();
        scanCompleteNotified.set(false);
        synchronized (seenScanAddresses) {
            seenScanAddresses.clear();
        }

        bleScanner = btAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            notifyScanComplete();
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (device != null) {
                    if (!isLikelyMeshDevice(result, device)) {
                        return;
                    }
                    String address = device.getAddress();
                    boolean isNew = false;
                    if (address != null) {
                        synchronized (seenScanAddresses) {
                            if (!seenScanAddresses.contains(address)) {
                                seenScanAddresses.add(address);
                                isNew = true;
                            }
                        }
                    } else {
                        isNew = true;
                    }
                    if (isNew) {
                        notifyDeviceFound(device);
                    }
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                notifyError("BLE scan failed: " + errorCode);
                if (scanCompleteNotified.compareAndSet(false, true)) {
                    notifyScanComplete();
                }
            }
        };
        try {
            // Use broad BLE scan so first-time/unpaired Mesh devices are discoverable.
            bleScanner.startScan(null, settings, scanCallback);
            ioHandler.postDelayed(() -> {
                stopScanInternal();
                if (scanCompleteNotified.compareAndSet(false, true)) {
                    notifyScanComplete();
                }
            }, 8000L);
        } catch (Exception e) {
            Log.w(TAG, "BLE scan start failed", e);
            if (scanCompleteNotified.compareAndSet(false, true)) {
                notifyScanComplete();
            }
        }
    }

    @Override
    public void addProbeSocket(String address, BluetoothSocket socket) {
        // No-op for BLE path.
    }

    @Override
    public void clearProbeSockets() {
        // No-op for BLE path.
    }

    private boolean checkBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
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
        requestBtPermissions();
        return false;
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            Context ctx = context;
            if (ctx instanceof Activity) {
                ((Activity) ctx).requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                }, 1001);
            } else {
                com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
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

    @Override
    public void connect(BluetoothDevice device) {
        if (device == null) return;
        stopScanInternal();
        if (connected.get()) {
            disconnect();
        }
        if (connecting.getAndSet(true)) {
            return;
        }

        lastDevice = device;
        shouldReconnect.set(true);
        reconnectAttempts = 0;
        clearQueues();

        // If not paired yet, trigger Android pairing flow first.
        int bondState = device.getBondState();
        if (bondState != BluetoothDevice.BOND_BONDED) {
            pendingBondDevice = device;
            boolean requested = false;
            try {
                requested = device.createBond();
            } catch (Exception e) {
                Log.w(TAG, "createBond failed", e);
            }
            if (requested || bondState == BluetoothDevice.BOND_BONDING) {
                notifyError("Pairing requested for " + resolveName(device)
                        + ". Accept the Bluetooth pair prompt.");
            } else {
                notifyError("Pairing not initiated for " + resolveName(device)
                        + ". Attempting BLE connect...");
            }
            // Continue with BLE connect path for devices that pair lazily during GATT access.
        }

        connectGattNow(device);
    }

    @Override
    public void connectToLastDevice() {
        if (lastDevice != null) {
            connect(lastDevice);
        } else {
            startScan();
        }
    }

    @Override
    public void disconnect() {
        shouldReconnect.set(false);
        connecting.set(false);
        connected.set(false);
        meshGpsEnabled = null;
        latestSelfLocation = null;
        stopScanInternal();
        ioHandler.removeCallbacks(periodicMessagePoll);
        ioHandler.post(() -> {
            runBeforeDisconnectHooks();
            closeGattInternal();
        });
        notifyDisconnected("User disconnected");
    }

    @Override
    public void cancelConnectionAttempts() {
        shouldReconnect.set(false);
        reconnectAttempts = 0;
        connecting.set(false);
        connected.set(false);
        meshGpsEnabled = null;
        latestSelfLocation = null;
        stopScanInternal();
        ioHandler.removeCallbacks(periodicMessagePoll);
        ioHandler.post(() -> {
            runBeforeDisconnectHooks();
            closeGattInternal();
        });
        notifyDisconnected("Connection attempt cancelled");
    }

    @Override
    public boolean sendKissFrame(byte[] ax25Frame) {
        if (!connected.get() || ax25Frame == null || ax25Frame.length == 0) {
            return false;
        }
        if (radioSilenceEnabled.get()) {
            return false;
        }

        int channel = getMeshChannelIndex();
        int msgId = outboundMsgId.getAndIncrement() & 0x7fffffff;
        int total = (ax25Frame.length + MAX_RAW_AX25_CHUNK - 1) / MAX_RAW_AX25_CHUNK;
        for (int i = 0; i < total; i++) {
            int off = i * MAX_RAW_AX25_CHUNK;
            int len = Math.min(MAX_RAW_AX25_CHUNK, ax25Frame.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(ax25Frame, off, chunk, 0, len);
            String b64 = Base64.encodeToString(chunk, Base64.NO_WRAP);
            String payload = ENV_PREFIX + msgId + "|" + (i + 1) + "|" + total + "|" + b64;
            if (payload.length() > MAX_MESH_MESSAGE_LEN) {
                return false;
            }
            enqueueCommand(buildSendChannelDataCommand(
                    channel,
                    ATAK_DATA_TYPE_AX25,
                    payload.getBytes(StandardCharsets.UTF_8)));
        }
        packetRouter.notifyPacketTransmitted();
        return true;
    }

    @Override
    public boolean sendRawBytes(byte[] data) {
        if (!connected.get() || data == null || data.length == 0) {
            return false;
        }
        String payload = "UVRAW|" + Base64.encodeToString(data, Base64.NO_WRAP);
        if (payload.length() > MAX_MESH_MESSAGE_LEN) {
            return false;
        }
        enqueueCommand(buildSendChannelDataCommand(
                getMeshChannelIndex(),
                ATAK_DATA_TYPE_RAW,
                payload.getBytes(StandardCharsets.UTF_8)));
        return true;
    }

    @Override
    public void setRadioSilenceEnabled(boolean enabled) {
        radioSilenceEnabled.set(enabled);
    }

    @Override
    public boolean isRadioSilenceEnabled() {
        return radioSilenceEnabled.get();
    }

    private void handleConnectionLost() {
        connected.set(false);
        connecting.set(false);
        ioHandler.removeCallbacks(periodicMessagePoll);
        clearQueues();
        ioHandler.post(this::closeGattInternal);
        notifyDisconnected("Connection lost");
        if (shouldReconnect.get()) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (lastDevice == null) return;
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            notifyError("Reconnect failed after " + MAX_RECONNECT_ATTEMPTS + " attempts.");
            return;
        }
        reconnectAttempts++;
        int delaySec = 5 * reconnectAttempts;
        ioHandler.postDelayed(() -> {
            if (shouldReconnect.get() && !connected.get() && !connecting.get()) {
                connect(lastDevice);
            }
        }, delaySec * 1000L);
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public boolean isConnecting() {
        return connecting.get();
    }

    @Override
    public long getLastIoActivityMs() {
        return lastIoActivityMs.get();
    }

    @Override
    public boolean hasRecentIo(long withinMs) {
        long last = lastIoActivityMs.get();
        if (last <= 0L) {
            return false;
        }
        return (System.currentTimeMillis() - last) <= Math.max(0L, withinMs);
    }

    @Override
    public String getConnectedDeviceName() {
        if (!connected.get()) return null;
        if (lastDevice != null) {
            String name = lastDevice.getName();
            return name != null ? name : lastDevice.getAddress();
        }
        return "MeshCore";
    }

    @Override
    public void addListener(ConnectionListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(ConnectionListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void addRawDataListener(RawDataListener listener) {
        rawDataListeners.add(listener);
    }

    @Override
    public void removeRawDataListener(RawDataListener listener) {
        rawDataListeners.remove(listener);
    }

    @Override
    public void addBeforeDisconnectHook(Runnable hook) {
        if (hook != null) {
            beforeDisconnectHooks.add(hook);
        }
    }

    @Override
    public void removeBeforeDisconnectHook(Runnable hook) {
        beforeDisconnectHooks.remove(hook);
    }

    public void addMeshStateListener(MeshStateListener listener) {
        if (listener != null) {
            meshStateListeners.add(listener);
        }
    }

    public void removeMeshStateListener(MeshStateListener listener) {
        meshStateListeners.remove(listener);
    }

    public Boolean getMeshGpsEnabled() {
        return meshGpsEnabled;
    }

    public MeshLocationFix getLatestSelfLocation() {
        return latestSelfLocation;
    }

    public void queryMeshGpsEnabled() {
        if (!connected.get()) {
            return;
        }
        enqueueCommand(new byte[]{CMD_GET_GPS_STATE});
    }

    public void setMeshGpsEnabled(boolean enabled) {
        if (!connected.get()) {
            return;
        }
        byte[] txt = ("gps:" + (enabled ? "1" : "0")).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + txt.length];
        out[0] = CMD_SET_SETTING_TEXT;
        System.arraycopy(txt, 0, out, 1, txt.length);
        enqueueCommand(out);
        // Query immediately after set to refresh authoritative state.
        enqueueCommand(new byte[]{CMD_GET_GPS_STATE});
    }

    public void requestSelfInfo() {
        if (!connected.get()) {
            return;
        }
        enqueueCommand(buildAppStartCommand());
    }

    private void runBeforeDisconnectHooks() {
        for (Runnable hook : beforeDisconnectHooks) {
            try {
                hook.run();
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyConnected(BluetoothDevice device) {
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

    private void notifyMeshGpsStateChanged(boolean enabled) {
        for (MeshStateListener l : meshStateListeners) {
            try {
                l.onMeshGpsStateChanged(enabled);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyMeshSelfLocation(MeshLocationFix fix) {
        for (MeshStateListener l : meshStateListeners) {
            try {
                l.onMeshSelfLocationUpdated(fix);
            } catch (Exception ignored) {
            }
        }
    }

    private boolean isLikelyMeshDevice(ScanResult result, BluetoothDevice device) {
        try {
            ScanRecord record = result != null ? result.getScanRecord() : null;
            if (record != null && record.getServiceUuids() != null) {
                for (ParcelUuid pu : record.getServiceUuids()) {
                    if (pu == null || pu.getUuid() == null) {
                        continue;
                    }
                    UUID adv = pu.getUuid();
                    if (UUID_UART_SERVICE.equals(adv) || UUID_MESHTASTIC_SERVICE.equals(adv)) {
                        return true;
                    }
                }
            }
            String name = null;
            if (record != null) {
                name = record.getDeviceName();
            }
            if (name == null || name.trim().isEmpty()) {
                name = device.getName();
            }
            if (name == null) {
                return false;
            }
            String n = name.toLowerCase(java.util.Locale.US);
            return n.contains("meshtastic")
                    || n.contains("meshcore")
                    || n.contains("mesh")
                    || n.contains("heltec")
                    || n.contains("lilygo")
                    || n.contains("t-echo")
                    || n.contains("tdeck")
                    || n.contains("t-deck")
                    || n.contains("wismesh")
                    || n.contains("rak")
                    || n.contains("seeed")
                    || n.contains("seed")
                    || n.contains("sensecap");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String resolveName(BluetoothDevice device) {
        if (device == null) {
            return "MeshCore";
        }
        String name = device.getName();
        return name != null && !name.trim().isEmpty()
                ? name
                : device.getAddress();
    }

    private void markIoActivity() {
        lastIoActivityMs.set(System.currentTimeMillis());
    }

    private void stopScanInternal() {
        if (bleScanner != null && scanCallback != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanCallback = null;
    }

    private void closeGattInternal() {
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (Exception ignored) {
        }
        gatt = null;
        rxCharacteristic = null;
        txCharacteristic = null;
    }

    private void connectGattNow(BluetoothDevice device) {
        ioHandler.post(() -> {
            try {
                closeGattInternal();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    gatt = device.connectGatt(context.getApplicationContext(),
                            false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    gatt = device.connectGatt(context.getApplicationContext(),
                            false, gattCallback);
                }
                if (gatt == null) {
                    connecting.set(false);
                    notifyError("BLE connectGatt failed");
                }
            } catch (Exception e) {
                connecting.set(false);
                notifyError("BLE connect failed: " + e.getMessage());
            }
        });
    }

    private void registerBondReceiver() {
        try {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            context.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device == null || pendingBondDevice == null) {
                        return;
                    }
                    if (!device.getAddress().equalsIgnoreCase(pendingBondDevice.getAddress())) {
                        return;
                    }
                    int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                    int prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        notifyError("Pairing complete for " + resolveName(device) + ". Connecting...");
                        pendingBondDevice = null;
                        connecting.set(true);
                        connectGattNow(device);
                    } else if (bondState == BluetoothDevice.BOND_NONE
                            && prevBondState == BluetoothDevice.BOND_BONDING) {
                        notifyError("Pairing failed or canceled for " + resolveName(device));
                        pendingBondDevice = null;
                        connecting.set(false);
                    }
                }
            }, filter);
        } catch (Exception e) {
            Log.w(TAG, "Could not register bond receiver", e);
        }
    }

    private void clearQueues() {
        synchronized (writeQueue) {
            writeQueue.clear();
            writeInFlight = false;
        }
        chunkBuffers.clear();
    }

    private void enqueueCommand(byte[] cmd) {
        if (cmd == null || cmd.length == 0) return;
        synchronized (writeQueue) {
            writeQueue.addLast(cmd);
            if (writeInFlight) {
                return;
            }
            writeInFlight = true;
        }
        ioHandler.post(this::drainWriteQueue);
    }

    private void drainWriteQueue() {
        while (connected.get()) {
            byte[] next;
            synchronized (writeQueue) {
                next = writeQueue.peekFirst();
                if (next == null) {
                    writeInFlight = false;
                    return;
                }
            }
            if (gatt == null || rxCharacteristic == null) {
                synchronized (writeQueue) {
                    writeInFlight = false;
                }
                return;
            }
            rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            rxCharacteristic.setValue(next);
            boolean started = gatt.writeCharacteristic(rxCharacteristic);
            if (!started) {
                synchronized (writeQueue) {
                    writeInFlight = false;
                }
                ioHandler.postDelayed(this::drainWriteQueue, 150L);
                return;
            }
            return;
        }
        synchronized (writeQueue) {
            writeInFlight = false;
        }
    }

    private byte[] buildAppStartCommand() {
        byte[] app = COMPANION_APP_ID.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[8 + app.length];
        out[0] = CMD_APP_START;
        System.arraycopy(app, 0, out, 8, app.length);
        return out;
    }

    private byte[] buildDeviceQueryCommand() {
        return new byte[]{CMD_DEVICE_QUERY, CMD_DEVICE_QUERY_ARG};
    }

    private byte[] buildGetNextMessageCommand() {
        return new byte[]{CMD_GET_NEXT_MSG};
    }

    private byte[] buildGetChannelInfoCommand(int idx) {
        return new byte[]{CMD_GET_CHANNEL, (byte) (idx & 0xFF)};
    }

    private byte[] buildSetChannelCommand(int idx, String name, byte[] secret) {
        byte[] out = new byte[1 + 1 + 32 + 16];
        out[0] = CMD_SET_CHANNEL;
        out[1] = (byte) (idx & 0xFF);
        byte[] nameBytes = name != null ? name.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int nameLen = Math.min(32, nameBytes.length);
        System.arraycopy(nameBytes, 0, out, 2, nameLen);
        if (secret != null) {
            int secLen = Math.min(16, secret.length);
            System.arraycopy(secret, 0, out, 34, secLen);
        }
        return out;
    }

    private byte[] buildSendChannelMessageCommand(int channel, String text) {
        byte[] msg = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(7 + msg.length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SEND_CHANNEL_MSG);
        buf.put((byte) 0x00);
        buf.put((byte) Math.max(0, Math.min(7, channel)));
        buf.putInt((int) (System.currentTimeMillis() / 1000L));
        buf.put(msg);
        return buf.array();
    }

    private byte[] buildSendChannelDataCommand(int channel, int dataType, byte[] payload) {
        int payloadLen = payload != null ? payload.length : 0;
        ByteBuffer buf = ByteBuffer.allocate(6 + payloadLen);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SEND_CHANNEL_DATA);
        buf.put((byte) Math.max(0, Math.min(7, channel)));
        buf.put((byte) OUT_PATH_FLOOD);
        buf.put((byte) (dataType & 0xFF));
        buf.put((byte) ((dataType >> 8) & 0xFF));
        if (payloadLen > 0) {
            buf.put(payload);
        }
        return buf.array();
    }

    private int getMeshChannelIndex() {
        return ATAK_CHANNEL_INDEX;
    }

    private void handleCompanionPacket(byte[] pkt) {
        if (pkt == null || pkt.length == 0) return;
        markIoActivity();
        pruneStaleChunks();
        byte t = pkt[0];
        Log.d(TAG, "RX companion pkt type=0x" + Integer.toHexString(t & 0xFF)
                + " len=" + pkt.length);
        if (t == PUSH_MESSAGES_WAITING) {
            enqueueCommand(buildGetNextMessageCommand());
            return;
        }
        if (t == RESP_NO_MORE_MSGS) {
            return;
        }
        if (t == RESP_SETTING_TEXT) {
            applySettingText(pkt);
            return;
        }
        if (t == RESP_SELF_INFO) {
            logSelfInfo(pkt);
            return;
        }
        if (t == RESP_DEVICE_INFO) {
            logDeviceInfo(pkt);
            return;
        }
        if (t == RESP_CHANNEL_INFO) {
            applyChannelInfo(pkt);
            return;
        }
        if (t == RESP_CHANNEL_DATA_RECV) {
            handleChannelData(pkt);
            enqueueCommand(buildGetNextMessageCommand());
            return;
        }

        String message = null;
        if (t == RESP_CHANNEL_MSG) {
            message = extractChannelText(pkt, false);
        } else if (t == RESP_CHANNEL_MSG_V3) {
            message = extractChannelText(pkt, true);
        } else if (t == RESP_CONTACT_MSG) {
            message = extractContactText(pkt, false);
        } else if (t == RESP_CONTACT_MSG_V3) {
            message = extractContactText(pkt, true);
        }
        if (message != null) {
            String routed = extractRoutableEnvelope(message);
            if (routed != null) {
                handleMeshMessage(routed);
            }
            enqueueCommand(buildGetNextMessageCommand());
        }
    }

    private void logSelfInfo(byte[] pkt) {
        try {
            if (pkt.length < 58) {
                Log.d(TAG, "SELF info short len=" + pkt.length);
                return;
            }
            ByteBuffer bb = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN);
            int latE6 = bb.getInt(36);
            int lonE6 = bb.getInt(40);
            long freqHz = ((long) bb.getInt(48)) & 0xFFFFFFFFL;
            long bwHz = ((long) bb.getInt(52)) & 0xFFFFFFFFL;
            int sf = pkt[56] & 0xFF;
            int cr = pkt[57] & 0xFF;
            String node = "";
            if (pkt.length > 58) {
                node = new String(pkt, 58, pkt.length - 58, StandardCharsets.UTF_8).trim();
            }
            double lat = latE6 / 1_000_000.0;
            double lon = lonE6 / 1_000_000.0;
            Log.i(TAG, "SELF info node='" + node + "' lat=" + lat + " lon=" + lon
                    + " freqHz=" + freqHz + " bwHz=" + bwHz + " sf=" + sf + " cr=" + cr);
            MeshLocationFix fix = new MeshLocationFix(lat, lon, System.currentTimeMillis(), node);
            if (fix.isValid()) {
                latestSelfLocation = fix;
                notifyMeshSelfLocation(fix);
            }
        } catch (Exception e) {
            Log.w(TAG, "SELF info parse failed", e);
        }
    }

    private void logDeviceInfo(byte[] pkt) {
        try {
            if (pkt.length < 4) {
                return;
            }
            int fwCode = pkt[1] & 0xFF;
            int maxContactsHalf = pkt[2] & 0xFF;
            int maxChannels = pkt[3] & 0xFF;
            Log.i(TAG, "DEVICE info fwCode=" + fwCode + " maxChannels=" + maxChannels
                    + " maxContacts=" + (maxContactsHalf * 2));
        } catch (Exception e) {
            Log.w(TAG, "DEVICE info parse failed", e);
        }
    }

    private void applySettingText(byte[] pkt) {
        if (pkt == null || pkt.length < 2) {
            return;
        }
        try {
            String text = new String(pkt, 1, pkt.length - 1, StandardCharsets.UTF_8).trim();
            Log.d(TAG, "SETTING text: " + text);
            if (text.startsWith("gps:")) {
                boolean enabled = text.endsWith("1")
                        || text.equalsIgnoreCase("gps:on")
                        || text.equalsIgnoreCase("gps:true");
                meshGpsEnabled = enabled;
                notifyMeshGpsStateChanged(enabled);
            }
        } catch (Exception ignored) {
        }
    }

    private String extractRoutableEnvelope(String text) {
        if (text == null) {
            return null;
        }
        int p = text.indexOf(ENV_PREFIX);
        if (p < 0) {
            return null;
        }
        return text.substring(p).trim();
    }

    private void applyChannelInfo(byte[] pkt) {
        if (pkt == null || pkt.length < 50) {
            return;
        }
        int idx = pkt[1] & 0xFF;
        if (idx < 0 || idx > 7) {
            return;
        }
        String raw = new String(pkt, 2, 32, StandardCharsets.UTF_8);
        int nul = raw.indexOf('\0');
        String name = (nul >= 0 ? raw.substring(0, nul) : raw).trim();
        if (idx == ATAK_CHANNEL_INDEX) {
            Log.i(TAG, "ATAK channel slot " + idx + " name='" + name + "'");
        }
    }

    private void handleChannelData(byte[] pkt) {
        if (pkt == null || pkt.length < 9) {
            return;
        }
        int dataType = ((pkt[7] & 0xFF) << 8) | (pkt[6] & 0xFF);
        int dataLen = pkt[8] & 0xFF;
        int available = pkt.length - 9;
        if (available <= 0 || dataLen <= 0) {
            return;
        }
        int copyLen = Math.min(dataLen, available);
        if (dataType != ATAK_DATA_TYPE_AX25 && dataType != ATAK_DATA_TYPE_RAW) {
            return;
        }
        String text = new String(pkt, 9, copyLen, StandardCharsets.UTF_8);
        String routed = extractRoutableEnvelope(text);
        if (routed != null) {
            handleMeshMessage(routed);
        }
    }

    private String extractChannelText(byte[] pkt, boolean v3) {
        int off = v3 ? 11 : 8;
        if (pkt.length < off) return null;
        return new String(pkt, off, pkt.length - off, StandardCharsets.UTF_8);
    }

    private String extractContactText(byte[] pkt, boolean v3) {
        int txtTypeIndex = v3 ? 11 : 8;
        int off = v3 ? 16 : 13;
        if (pkt.length < off) return null;
        byte txtType = pkt[txtTypeIndex];
        if (txtType == 2) off += 4;
        if (pkt.length < off) return null;
        return new String(pkt, off, pkt.length - off, StandardCharsets.UTF_8);
    }

    private void handleMeshMessage(String msg) {
        if (msg == null || !msg.startsWith(ENV_PREFIX)) return;
        String[] parts = msg.split("\\|", 5);
        if (parts.length != 5) return;
        try {
            int msgId = Integer.parseInt(parts[1]);
            int seq = Integer.parseInt(parts[2]);
            int total = Integer.parseInt(parts[3]);
            byte[] chunk = Base64.decode(parts[4], Base64.DEFAULT);
            if (chunk == null || total < 1 || seq < 1 || seq > total) return;

            ChunkAccumulator acc = chunkBuffers.get(msgId);
            if (acc == null || acc.total != total) {
                acc = new ChunkAccumulator(total);
                chunkBuffers.put(msgId, acc);
            }
            acc.parts.put(seq, chunk);
            acc.lastUpdateMs = System.currentTimeMillis();
            if (acc.parts.size() < total) return;

            int len = 0;
            for (int i = 1; i <= total; i++) {
                byte[] p = acc.parts.get(i);
                if (p == null) return;
                len += p.length;
            }
            byte[] ax25 = new byte[len];
            int off = 0;
            for (int i = 1; i <= total; i++) {
                byte[] p = acc.parts.get(i);
                System.arraycopy(p, 0, ax25, off, p.length);
                off += p.length;
            }
            chunkBuffers.remove(msgId);

            for (RawDataListener listener : rawDataListeners) {
                try {
                    if (listener.onRawBytes(ax25)) {
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
            packetRouter.routeIncoming(ax25);
        } catch (Exception ignored) {
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g;
                reconnectAttempts = 0;
                connected.set(false);
                g.requestMtu(512);
                g.discoverServices();
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handleConnectionLost();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyError("BLE service discovery failed");
                handleConnectionLost();
                return;
            }
            BluetoothGattService svc = g.getService(UUID_UART_SERVICE);
            if (svc == null) {
                notifyError("MeshCore UART service not found");
                handleConnectionLost();
                return;
            }
            rxCharacteristic = svc.getCharacteristic(UUID_UART_RX);
            txCharacteristic = svc.getCharacteristic(UUID_UART_TX);
            if (rxCharacteristic == null || txCharacteristic == null) {
                notifyError("MeshCore RX/TX characteristic missing");
                handleConnectionLost();
                return;
            }
            g.setCharacteristicNotification(txCharacteristic, true);
            BluetoothGattDescriptor ccc = txCharacteristic.getDescriptor(UUID_CCC);
            if (ccc == null) {
                notifyError("MeshCore CCC descriptor missing");
                handleConnectionLost();
                return;
            }
            ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(ccc);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (!UUID_CCC.equals(descriptor.getUuid())) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyError("BLE notification enable failed");
                handleConnectionLost();
                return;
            }
            connecting.set(false);
            connected.set(true);
            markIoActivity();
            notifyConnected(lastDevice);
            enqueueCommand(buildAppStartCommand());
            enqueueCommand(buildDeviceQueryCommand());
            enqueueCommand(buildSetChannelCommand(
                    ATAK_CHANNEL_INDEX,
                    ATAK_CHANNEL_NAME,
                    ATAK_CHANNEL_SECRET));
            enqueueCommand(new byte[]{CMD_GET_GPS_STATE});
            for (int i = 0; i < 8; i++) {
                enqueueCommand(buildGetChannelInfoCommand(i));
            }
            enqueueCommand(buildGetNextMessageCommand());
            ioHandler.removeCallbacks(periodicMessagePoll);
            ioHandler.postDelayed(periodicMessagePoll, 2500L);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (characteristic == null || !UUID_UART_TX.equals(characteristic.getUuid())) return;
            handleCompanionPacket(characteristic.getValue());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            synchronized (writeQueue) {
                writeQueue.pollFirst();
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                markIoActivity();
            }
            drainWriteQueue();
        }
    };

    private static final class ChunkAccumulator {
        final int total;
        final Map<Integer, byte[]> parts = new ConcurrentHashMap<>();
        long lastUpdateMs;

        ChunkAccumulator(int total) {
            this.total = total;
            this.lastUpdateMs = System.currentTimeMillis();
        }
    }

    private void pruneStaleChunks() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, ChunkAccumulator> e : chunkBuffers.entrySet()) {
            ChunkAccumulator acc = e.getValue();
            if (acc == null) continue;
            if (now - acc.lastUpdateMs > 120_000L) {
                chunkBuffers.remove(e.getKey());
            }
        }
    }
}
