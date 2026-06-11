package com.uvpro.plugin.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import com.uvpro.plugin.kiss.KissFrameDecoder;
import com.uvpro.plugin.kiss.KissFrameEncoder;
import com.uvpro.plugin.protocol.PacketRouter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Classic Bluetooth SPP transport shell for BTECH UV-PRO radios.
 *
 * <p>Scan, connect, auto-connect, ACL/passive reconnect, and device registry integration were
 * removed for a clean-slate rebuild. Discovery helpers live in {@link UvProBtDeviceMatcher}.
 * When a session is active, KISS read/write still flows through {@link PacketRouter}.
 */
public class BtConnectionManager {

    private static final String TAG = "UVPro.BT";

    private final Context context;
    private final PacketRouter packetRouter;
    private final KissFrameDecoder kissDecoder;
    private final KissFrameEncoder kissEncoder;

    private InputStream inputStream;
    private OutputStream outputStream;
    private Thread readThread;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean radioSilenceEnabled = new AtomicBoolean(false);
    private final AtomicLong lastIoActivityMs = new AtomicLong(0L);

    private final CopyOnWriteArrayList<ConnectionListener> listeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RawDataListener> rawDataListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> beforeDisconnectHooks =
            new CopyOnWriteArrayList<>();

    public interface ConnectionListener {
        void onConnected(BluetoothDevice device);
        void onDisconnected(String reason);
        void onError(String error);
        void onDeviceFound(BluetoothDevice device);
        default void onScanComplete() {
        }
    }

    public interface RawDataListener {
        /** @return true if the listener consumed the bytes (skip KISS decode). */
        boolean onRawBytes(byte[] data);
    }

    /** @deprecated Scan/connect removed; retained for compile compatibility during rebuild. */
    public interface MeshCoexistenceListener {
        boolean isMeshConnecting();
        boolean isMeshConnected();
        void onRadioConnectStartingWhileMeshUp();
    }

    public BtConnectionManager(Context context, PacketRouter packetRouter) {
        Context atakContext = com.atakmap.android.maps.MapView.getMapView() != null
                ? com.atakmap.android.maps.MapView.getMapView().getContext()
                : context;
        this.context = atakContext;
        this.packetRouter = packetRouter;
        this.kissDecoder = new KissFrameDecoder();
        this.kissEncoder = new KissFrameEncoder();
    }

    public void setMeshCoexistenceListener(MeshCoexistenceListener listener) {
        // No-op until coexistence policy is reimplemented.
    }

    public void shutdown() {
        disconnect();
    }

    /** Removed pending Bluetooth rebuild. */
    public void startScan() {
        Log.i(TAG, "startScan: not available (Bluetooth connect rebuild in progress)");
        notifyScanComplete();
    }

    /** Removed pending Bluetooth rebuild. */
    public void connect(BluetoothDevice device) {
        notifyError("Bluetooth connect not available — rebuild in progress");
    }

    /** Removed pending Bluetooth rebuild. */
    public void connectToLastDevice() {
        notifyError("Bluetooth connect not available — rebuild in progress");
    }

    public void addProbeSocket(String address, BluetoothSocket socket) {
    }

    public void clearProbeSockets() {
    }

    public boolean hasBondedUvProRadio() {
        return false;
    }

    public void resumeRadioAutoConnect() {
    }

    public void onMeshReleased() {
    }

    public void disconnect() {
        connecting.set(false);
        connected.set(false);
        runBeforeDisconnectHooks();
        cleanup();
        notifyDisconnected("User disconnected");
    }

    public void cancelConnectionAttempts() {
        connecting.set(false);
        connected.set(false);
        cleanup();
        notifyDisconnected("Connection attempt cancelled");
    }

    public boolean primeKissTxTiming() {
        if (!connected.get() || outputStream == null) {
            return false;
        }
        try {
            outputStream.write(kissEncoder.encodeCommand(
                    com.uvpro.plugin.kiss.KissConstants.CMD_TXDELAY, (byte) 30));
            outputStream.write(kissEncoder.encodeCommand(
                    com.uvpro.plugin.kiss.KissConstants.CMD_TXTAIL, (byte) 50));
            outputStream.flush();
            markIoActivity();
            return true;
        } catch (IOException e) {
            Log.w(TAG, "KISS TX timing prime failed: " + e.getMessage());
            return false;
        }
    }

    public boolean sendKissFrame(byte[] ax25Frame) {
        if (!connected.get() || outputStream == null) {
            return false;
        }
        if (radioSilenceEnabled.get()) {
            return false;
        }
        try {
            byte[] kissFrame = kissEncoder.encode(ax25Frame);
            outputStream.write(kissFrame);
            outputStream.flush();
            markIoActivity();
            packetRouter.notifyPacketTransmitted();
            return true;
        } catch (IOException e) {
            handleConnectionLost();
            return false;
        }
    }

    public boolean sendRawBytes(byte[] data) {
        if (!connected.get() || outputStream == null) {
            return false;
        }
        try {
            outputStream.write(data);
            outputStream.flush();
            markIoActivity();
            return true;
        } catch (IOException e) {
            handleConnectionLost();
            return false;
        }
    }

    public void setRadioSilenceEnabled(boolean enabled) {
        radioSilenceEnabled.set(enabled);
    }

    public boolean isRadioSilenceEnabled() {
        return radioSilenceEnabled.get();
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
        return (System.currentTimeMillis() - last) <= Math.max(0L, withinMs);
    }

    public String getConnectedDeviceName() {
        return connected.get() ? "UV-PRO" : null;
    }

    public void addListener(ConnectionListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(ConnectionListener listener) {
        listeners.remove(listener);
    }

    public void addRawDataListener(RawDataListener listener) {
        if (listener != null) {
            rawDataListeners.add(listener);
        }
    }

    public void removeRawDataListener(RawDataListener listener) {
        rawDataListeners.remove(listener);
    }

    public void addBeforeDisconnectHook(Runnable hook) {
        if (hook != null) {
            beforeDisconnectHooks.add(hook);
        }
    }

    private void runBeforeDisconnectHooks() {
        for (Runnable hook : beforeDisconnectHooks) {
            try {
                hook.run();
            } catch (Exception e) {
                Log.w(TAG, "beforeDisconnect hook failed", e);
            }
        }
    }

    private void handleConnectionLost() {
        connected.set(false);
        connecting.set(false);
        cleanup();
        notifyDisconnected("Connection lost");
    }

    private void cleanup() {
        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException ignored) {
        }
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) {
        }
        inputStream = null;
        outputStream = null;
    }

    private void markIoActivity() {
        lastIoActivityMs.set(System.currentTimeMillis());
    }

    protected void notifyDisconnected(String reason) {
        for (ConnectionListener listener : listeners) {
            try {
                listener.onDisconnected(reason);
            } catch (Exception e) {
                Log.w(TAG, "Listener onDisconnected failed", e);
            }
        }
    }

    protected void notifyError(String error) {
        Log.w(TAG, error);
        for (ConnectionListener listener : listeners) {
            try {
                listener.onError(error);
            } catch (Exception e) {
                Log.w(TAG, "Listener onError failed", e);
            }
        }
    }

    protected void notifyDeviceFound(BluetoothDevice device) {
        for (ConnectionListener listener : listeners) {
            try {
                listener.onDeviceFound(device);
            } catch (Exception e) {
                Log.w(TAG, "Listener onDeviceFound failed", e);
            }
        }
    }

    protected void notifyScanComplete() {
        for (ConnectionListener listener : listeners) {
            try {
                listener.onScanComplete();
            } catch (Exception e) {
                Log.w(TAG, "Listener onScanComplete failed", e);
            }
        }
    }

    protected void notifyConnected(BluetoothDevice device) {
        for (ConnectionListener listener : listeners) {
            try {
                listener.onConnected(device);
            } catch (Exception e) {
                Log.w(TAG, "Listener onConnected failed", e);
            }
        }
    }

    protected Context getContext() {
        return context;
    }
}
