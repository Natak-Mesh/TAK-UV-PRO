package com.uvpro.plugin;

import android.app.AlertDialog;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.preference.PreferenceManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.util.Log;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.GridLayout;
import android.widget.CheckBox;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;

import com.uvpro.plugin.beacon.SmartBeacon;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.bluetooth.BluetoothDeviceRegistry;
import com.uvpro.plugin.bluetooth.BluetoothDeviceRegistry.BtDeviceRecord;
import com.uvpro.plugin.bluetooth.MeshBtConnectionManager;
import com.uvpro.plugin.chat.ChatBridge;
import com.uvpro.plugin.location.RadioGpsAugmentController;
import com.uvpro.plugin.location.RadioGpsBridge;
import com.uvpro.plugin.location.RadioPositionFix;
import com.uvpro.plugin.kiss.KissRadioFrequencyControl;
import com.uvpro.plugin.contacts.ContactTracker;
import com.uvpro.plugin.contacts.RadioContact;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.protocol.PacketRouter;
import com.uvpro.plugin.radio.UVProRadioControlManager;
import com.uvpro.plugin.terminal.PacketTerminalDropDownReceiver;
import com.uvpro.plugin.ui.MeshStatusOverlay;
import com.uvpro.plugin.ui.SettingsFragment;

import java.text.SimpleDateFormat;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UVPro Drop-Down UI Panel.
 *
 * Slides in from the right side of the ATAK map. Provides:
 * - Radio connection status and controls
 * - Contact count and statistics
 * - Quick-action buttons (beacon, ping, settings)
 * - Debug log view
 */
public class UVProDropDownReceiver extends DropDownReceiver
        implements DropDown.OnStateListener,
        BtConnectionManager.ConnectionListener,
        ContactTracker.ContactListener,
        PacketRouter.PacketCountListener {

    public static final String SHOW_PLUGIN =
            "com.uvpro.plugin.SHOW_PLUGIN";
    public static final String SHOW_PLUGIN_CHANNEL_CONTROL =
            "com.uvpro.plugin.SHOW_PLUGIN_CHANNEL_CONTROL";

    private static final String TAG = "UVPro.UI";
    private static final int MAX_LOG_LINES = 50;
    private static final int COLOR_A_ACTIVE = 0xFF00897B;       // Teal
    private static final int COLOR_A_SUBDUED = 0xFF2E6B63;      // Teal (subdued)
    private static final int COLOR_B_ACTIVE = 0xFF4CAF50;       // Bright Green
    private static final int COLOR_B_SUBDUED = 0xFF2E7D32;      // Green (subdued)
    private static final int COLOR_DIGITAL_ACTIVE = 0xFF005A8D; // Blue
    private static final int COLOR_DIGITAL_SUBDUED = 0xFF2A5674; // Blue (subdued)
    private static final int COLOR_EDIT_SELECTION_BORDER = 0xFFFF9800; // Bright orange
    private static final int EDIT_SELECTION_STROKE_DP = 3;
    /** Corner radius for pill-shaped buttons (matches bg_uvpro_pill_button.xml). */
    private static final int PILL_CORNER_RADIUS_DP = 20;
    private static final int COLOR_TX_HIGHLIGHT = 0xFFFF1744; // Bright red
    private static final int COLOR_TX_STROKE = 0xFFFFFFFF; // White stroke
    /** Primary full-width pill buttons (beacon settings, plugin settings, etc.). */
    private static final int COLOR_PILL_BUTTON_PRIMARY = 0xFF455A64;
    private static final int COLOR_REPEATER_LOAD_FILL = COLOR_PILL_BUTTON_PRIMARY;
    private static final int COLOR_REPEATER_LOAD_ARMED_STROKE = 0xFFFFEB3B;
    private static final String LABEL_LOAD_REPEATER = "Load Selected Repeater";
    private static final String LABEL_SELECT_CHANNEL = "Select Channel";
    private static final String LABEL_DIGITAL_ONLY = "DIGITAL ONLY MODE";
    private static final String LABEL_DIGITAL_SELECT_CHANNEL = "Select Channel";
    private static final String LABEL_DIGITAL_LONG_PRESS_DISABLE =
            "Long Press to Disable KISS Mode";
    private static final int TARGET_A = 0;
    private static final int TARGET_B = 1;
    private static final int TARGET_DIGITAL = 2;
    private static final String[] MESH_DEVICE_NAME_HINTS = {
            "meshcore", "meshtastic", "wismesh", "rak", "heltec", "lilygo",
            "seeed", "sensecap", "t-echo", "tdeck", "t-deck", "mesh"
    };
    private static final String PREF_AUGMENT_GPS_FROM_MESHCORE =
            "uvpro_augment_gps_from_meshcore";
    private static final String PREF_ATAK_WIFI_TRANSMIT =
            "uvpro_atak_wifi_transmit";

    private final Context pluginContext;
    private final BtConnectionManager btManager;
    private final MeshBtConnectionManager meshBtManager;
    private final ContactTracker contactTracker;
    private CotBridge cotBridge;
    private ChatBridge chatBridge;
    private EncryptionManager encryptionManager;

    private View rootView;
    private View statusDot;
    private TextView statusText;
    private TextView deviceName;
    private View meshStatusDot;
    private TextView meshStatusText;
    private TextView meshDeviceName;
    private TextView callsignText;
    private TextView contactsText;
    private TextView packetsText;
    private TextView receiveRssiText;
    private View receiveRssiMeterBlock;
    private View receiveRssiMeterFrame;
    private View receiveRssiFillGreen;
    private View receiveRssiFillRed;
    private android.widget.FrameLayout receiveRssiScaleFrame;
    private boolean receiveRssiScaleBuilt;
    private int lastReceiveRssiScaleLevel = -1;

    private static final int RSSI_SCALE_MAX = 12;
    private static final int[] RSSI_SCALE_TICKS = {1, 2, 4, 6, 8, 10, 11, 12};
    private static final int[] RSSI_SCALE_LABEL_POS = {3, 5, 7, 9};
    private static final String[] RSSI_SCALE_LABELS = {"3", "5", "7", "9"};
    private TextView logText;
    private TextView encryptionStatusText;
    private TextView beaconIntervalText;
    private TextView gpsBeaconIntervalLabel;
    private View rowBeaconInterval;
    private Switch switchSmartBeacon;
    private Button btnManageSmartBeaconSettings;
    private Button btnManagePluginBeaconSettings;
    private Button btnAprsBeaconArm;
    private TextView textAprsStatusCall;
    private android.widget.ImageView imageAprsStatusIcon;
    private TextView textAprsStatusIcon;
    private TextView textAprsStatusMessage;
    private Switch switchAprsDisableAtak;
    private Button btnSendAprsBeacon;
    private Button btnEditAprsSettings;
    private TextView teamColorText;
    private Button btnScan;
    private Button btnDisconnect;
    private Button btnMeshScan;
    private Button btnMeshDisconnect;
    private Button btnUpdateGpsFromMeshcore;
    private Button btnUpdateGpsFromRadio;
    private Button btnLoadSelectedRepeater;
    private Button btnTxPower;
    private Button btnDigitalOnlyMode;
    private Button btnRadioSilence;
    private Button btnRefreshChannels;
    private Button btnInitialChannelGroupSetup;
    private Button btnPacketTerminal;
    private Button btnChannelGroup;
    private Button btnImportChannels;
    private Button btnExportChannels;
    private Button btnVfoA;
    private Button btnVfoB;
    private Button btnDigital;
    private TextView selectedRepeaterText;
    private GridLayout channelsGrid;
    private Switch switchDualWatch;
    private Switch switchDigitalEdit;
    private Switch switchAugmentGpsFromRadio;
    private Switch switchAugmentGpsFromMeshcore;
    private View rowAugmentGpsFromMeshcore;
    private Switch switchMeshTransmit;
    private Switch switchUvProTransmit;
    private Switch switchWifiTransmit;
    private Switch switchMeshEnableGps;

    private TextView favoritesLabel;
    private HorizontalScrollView favoritesScroll;
    private LinearLayout favoritesStrip;
    private TextView meshFavoritesLabel;
    private HorizontalScrollView meshFavoritesScroll;
    private LinearLayout meshFavoritesStrip;
    private TextView connectModeHint;

    private Switch switchEncryption;
    private View passphraseRow;
    private EditText editPassphrase;
    private Button btnSetPassphrase;

    private final LinkedList<String> logLines = new LinkedList<>();
    private final CompoundButton.OnCheckedChangeListener smartBeaconCheckedListener =
            (buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                Context c = getMapView().getContext();
                SmartBeacon.setEnabled(c, isChecked);
                applySmartBeaconIntervalGreyState(isChecked);
                appendLog("Smart beacon " + (isChecked ? "on" : "off"));
                try {
                    AtakBroadcast.getInstance().sendBroadcast(
                            new Intent(UVProMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
                } catch (Exception ignored) {
                }
            };
    private final List<BluetoothDevice> foundDevices = new ArrayList<>();
    private final List<BluetoothDevice> meshFoundDevices = new ArrayList<>();
    private boolean scanForNewRadioOnly = false;
    private int txCount = 0;
    private int rxCount = 0;
    private UVProRadioControlManager radioControlManager;
    private final RadioGpsAugmentController radioGpsAugmentController =
            new RadioGpsAugmentController();
    private boolean activeVfoB = false;
    private boolean channelTargetDigital = false;
    private int selectedTarget = TARGET_A;
    private int lastAnalogTarget = TARGET_A;
    private boolean txVfoB = false;
    private int lastChannelA = -1;
    private int lastChannelB = -1;
    private int lastDigitalChannel = -1;
    private boolean lastDualWatchEnabled = false;
    private int currentTxPowerLevel = UVProRadioControlManager.TX_POWER_LOW;
    private int currentChannelGroup = 0;
    private int availableChannelGroups = UVProRadioControlManager.DEFAULT_GROUP_COUNT;
    private boolean lastHasRxFocus = false;
    private ValueAnimator activeVfoPulseAnimator;
    private Button pulsingVfoButton;
    private GradientDrawable pulsingVfoDrawable;
    private ObjectAnimator repeaterLoadFocusAnimator;
    private ValueAnimator updateGpsButtonPulseAnimator;
    private GradientDrawable updateGpsButtonPulseDrawable;
    private Button updateGpsPulseTargetButton;
    private ValueAnimator scanConnectPulseAnimator;
    private GradientDrawable scanConnectPulseDrawable;
    private final Runnable deferredScanConnectPulseStart = this::startScanConnectButtonPulse;
    private ValueAnimator meshConnectPulseAnimator;
    private GradientDrawable meshConnectPulseDrawable;
    private ValueAnimator initialGroupSetupPulseAnimator;
    private GradientDrawable initialGroupSetupPulseDrawable;
    private final AtomicBoolean snapshotReadInFlight = new AtomicBoolean(false);
    private final AtomicBoolean snapshotRefreshPending = new AtomicBoolean(false);
    private final AtomicBoolean snapshotFullRefreshPending = new AtomicBoolean(false);
    private final AtomicBoolean groupReadInFlight = new AtomicBoolean(false);
    private final AtomicBoolean groupRefreshGridPending = new AtomicBoolean(false);
    private UVProRadioControlManager.RadioControlSnapshot lastSnapshot;
    private boolean repeaterLoadArmed = false;
    private String repeaterLoadArmedUid;
    private UVProRadioControlManager.RepeaterSpec repeaterLoadArmedSpec;
    private boolean digitalOnlyArmed = false;
    private boolean digitalOnlyActive = false;
    private int digitalOnlyChannelId = -1;
    private final Runnable digitalOnlyDisconnectHook = this::releaseDigitalOnlyKissLockSilent;
    private boolean pendingOpenToChannelControl = false;
    private boolean meshConnected = false;
    private boolean meshTransmitEnabled = false;
    private boolean wifiTransmitEnabled = true;
    private boolean suppressTransportSwitchCallbacks = false;
    private Boolean meshGpsEnabledState = null;
    private boolean meshGpsEnableRequested = false;
    private boolean suppressMeshGpsSwitchCallbacks = false;
    private final AtomicBoolean meshGpsAugmentUpdateInFlight = new AtomicBoolean(false);
    private final Runnable meshGpsAugmentRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                Context ctx = getMapView() != null ? getMapView().getContext() : null;
                if (ctx == null || !isAugmentMeshPreferenceEnabled(ctx)) {
                    return;
                }
                if (meshBtManager == null || !meshBtManager.isConnected()) {
                    return;
                }
                if (RadioGpsBridge.isPhoneGpsAvailable(getMapView())) {
                    return;
                }
                if (!Boolean.TRUE.equals(meshGpsEnabledState)) {
                    meshBtManager.queryMeshGpsEnabled();
                    return;
                }
                if (!meshGpsAugmentUpdateInFlight.compareAndSet(false, true)) {
                    return;
                }
                new Thread(() -> {
                    try {
                        updateGpsFromMeshcoreNow(false);
                    } finally {
                        meshGpsAugmentUpdateInFlight.set(false);
                    }
                }, "uvpro-mesh-gps-augment").start();
            } finally {
                scheduleMeshGpsAugmentTick();
            }
        }
    };

    public UVProDropDownReceiver(MapView mapView,
                                     Context pluginContext,
                                     BtConnectionManager btManager,
                                     MeshBtConnectionManager meshBtManager,
                                     ContactTracker contactTracker) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.btManager = btManager;
        this.meshBtManager = meshBtManager;
        this.contactTracker = contactTracker;

        // Register as listener for connection and contact updates
        btManager.addListener(this);
        btManager.addBeforeDisconnectHook(digitalOnlyDisconnectHook);
        contactTracker.setListener(this);
        if (meshBtManager != null) {
            meshBtManager.addListener(new BtConnectionManager.ConnectionListener() {
                @Override
                public void onConnected(BluetoothDevice device) {
                    String name = device != null
                            ? resolveDeviceDisplayName(getMapView().getContext(), device)
                            : "MeshCore";
                    meshConnected = true;
                    meshGpsEnabledState = null;
                    meshGpsEnableRequested = false;
                    if (device != null) {
                        Context ctx = getMapView().getContext();
                        BluetoothDeviceRegistry.recordConnection(ctx, device, false);
                        BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx, device.getAddress());
                    }
                    getMapView().post(() -> {
                        stopMeshConnectButtonPulse(true);
                        updateMeshConnectionUI(true, name);
                        applyPreferredTransmitModeForConnectionState(false);
                        ChatBridge.collapseAllCallsignAliasDuplicates();
                        appendLog("MeshCore connected to " + name);
                        refreshFavoriteStrip();
                        updateMeshScanButtonText();
                        scheduleMeshGpsAugmentTick();
                    });
                    meshBtManager.queryMeshGpsEnabled();
                    meshBtManager.requestSelfInfo();
                }

                @Override
                public void onDisconnected(String reason) {
                    meshConnected = false;
                    meshGpsEnabledState = null;
                    meshGpsEnableRequested = false;
                    getMapView().post(() -> {
                        stopMeshConnectButtonPulse(true);
                        updateMeshConnectionUI(false, null);
                        applyPreferredTransmitModeForConnectionState(false);
                        appendLog("MeshCore disconnected: " + reason);
                        updateMeshScanButtonText();
                        scheduleMeshGpsAugmentTick();
                    });
                }

                @Override
                public void onError(String error) {
                    getMapView().post(() -> {
                        stopMeshConnectButtonPulse(true);
                        appendLog("MeshCore error: " + error);
                        updateMeshScanButtonText();
                    });
                }

                @Override
                public void onDeviceFound(BluetoothDevice device) {
                    if (device != null) {
                        String addr = device.getAddress();
                        for (BluetoothDevice existing : meshFoundDevices) {
                            if (existing != null && addr != null
                                    && addr.equalsIgnoreCase(existing.getAddress())) {
                                return;
                            }
                        }
                        meshFoundDevices.add(device);
                    }
                }

                @Override
                public void onScanComplete() {
                    getMapView().post(UVProDropDownReceiver.this::showMeshDevicePicker);
                }
            });
            meshBtManager.addMeshStateListener(new MeshBtConnectionManager.MeshStateListener() {
                @Override
                public void onMeshGpsStateChanged(boolean enabled) {
                    meshGpsEnabledState = enabled;
                    meshGpsEnableRequested = enabled;
                    getMapView().post(() -> {
                        updateMeshGpsControlsUi();
                        appendLog("MeshCore GPS " + (enabled ? "enabled" : "disabled"));
                        scheduleMeshGpsAugmentTick();
                    });
                }

                @Override
                public void onMeshSelfLocationUpdated(MeshBtConnectionManager.MeshLocationFix fix) {
                    if (fix == null || !fix.isValid()) {
                        return;
                    }
                    getMapView().post(() -> appendLog(String.format(
                            Locale.US,
                            "MeshCore fix %.5f, %.5f",
                            fix.latitude, fix.longitude)));
                }
            });
        }
    }

    public void setCotBridge(CotBridge cotBridge) {
        this.cotBridge = cotBridge;
        applyActiveTransmitTransport();
    }

    public void setChatBridge(ChatBridge chatBridge) {
        this.chatBridge = chatBridge;
        applyActiveTransmitTransport();
    }

    public void setEncryptionManager(EncryptionManager encryptionManager) {
        this.encryptionManager = encryptionManager;
    }

    public void setRadioControlManager(UVProRadioControlManager radioControlManager) {
        this.radioControlManager = radioControlManager;
        radioGpsAugmentController.install(btManager, radioControlManager, getMapView());
        radioGpsAugmentController.setAugmentEnabled(
                isAugmentGpsPreferenceEnabled(getMapView().getContext()));
        if (this.radioControlManager != null) {
            this.radioControlManager.setSelectionListener(spec ->
                    getMapView().post(this::updateSelectedRepeaterUi));
            this.radioControlManager.setRadioEventListener(eventType -> getMapView().post(() -> {
                // Firmware emits channel/settings events when a user changes channel group
                // directly on the radio. Re-read current group first, then refresh grid.
                if (eventType == 5 || eventType == 6) {
                    refreshChannelGroupFromRadioAsync(true);
                } else {
                    refreshChannelGridAsync();
                }
            }));
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (SHOW_PLUGIN.equals(action) || SHOW_PLUGIN_CHANNEL_CONTROL.equals(action)) {
            pendingOpenToChannelControl = SHOW_PLUGIN_CHANNEL_CONTROL.equals(action);
            showDropDown(createView(),
                    HALF_WIDTH, FULL_HEIGHT,
                    FULL_WIDTH, HALF_HEIGHT,
                    false, this);
            if (pendingOpenToChannelControl) {
                // Some ATAK builds do not reliably fire onDropDownVisible on first open.
                scheduleScrollToRepeaterLoadSection();
            }
        }
    }

    /**
     * Create the main plugin UI view by inflating the XML layout.
     */
    private View createView() {
        LayoutInflater inflater = LayoutInflater.from(pluginContext);
        rootView = inflater.inflate(
                pluginContext.getResources().getIdentifier(
                        "uvpro_dropdown", "layout",
                        pluginContext.getPackageName()),
                null);

        // Bind views
        bindViews();
        // Fallback path: ensure MeshCore map icon installs when panel is opened.
        MeshStatusOverlay.install(pluginContext);

        // Restore actual connection state (survives dropdown close/reopen)
        if (btManager.isConnected()) {
            updateConnectionUI(true, btManager.getConnectedDeviceName());
        } else {
            updateConnectionUI(false, null);
        }
        if (meshBtManager != null && meshBtManager.isConnected()) {
            meshConnected = true;
            updateMeshConnectionUI(true, meshBtManager.getConnectedDeviceName());
            meshGpsEnabledState = meshBtManager.getMeshGpsEnabled();
            meshGpsEnableRequested = Boolean.TRUE.equals(meshGpsEnabledState);
            meshBtManager.queryMeshGpsEnabled();
            meshBtManager.requestSelfInfo();
            stopMeshConnectButtonPulse(true);
        } else {
            meshConnected = false;
            meshGpsEnabledState = null;
            meshGpsEnableRequested = false;
            updateMeshConnectionUI(false, null);
            if (meshBtManager != null && meshBtManager.isConnecting()) {
                startMeshConnectButtonPulse();
            } else {
                stopMeshConnectButtonPulse(true);
            }
        }
        wifiTransmitEnabled = isWifiTransmitPreferenceEnabled(getMapView().getContext());
        // Apply connection-priority transmit defaults on open:
        // UV-PRO if connected, otherwise MeshCore when mesh is connected.
        applyPreferredTransmitModeForConnectionState(false);
        syncTransmitSwitchesUi();

        // Set callsign from ATAK self marker
        String callsign = MapView.getMapView().getSelfMarker().getMetaString("callsign","UNKNOWN");
        if (callsignText != null) {
            callsignText.setText(callsign);
        }

        // Make log scrollable inside the outer ScrollView
        if (logText != null) {
            logText.setMovementMethod(new ScrollingMovementMethod());
            logText.setOnTouchListener((v, event) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            });
        }

        // Load saved state into switches BEFORE attaching listeners
        updateStatusFields();
        // Restore packet counts in UI
        updatePacketCount();
        updateContactCount();
        // Now attach change listeners so user interactions are wired
        setupListeners();
        scheduleMeshGpsAugmentTick();
        updateDigitalEditGuardUi();
        updateDigitalOnlyButtonUi();
        updateTxPowerButtonUi();
        refreshFavoriteStrip();
        updateScanButtonText();
        updateMeshScanButtonText();
        updateSelectedRepeaterUi();
        refreshChannelGridFullAsync();
        refreshLogView();
        appendLog("UV-PRO ready");
        return rootView;
    }

    private void bindViews() {
        android.widget.TextView headerVersion = rootView.findViewById(getId("header_version"));
        if (headerVersion != null) {
            headerVersion.setText("v" + com.uvpro.plugin.BuildConfig.VERSION_NAME);
        }

        statusDot = rootView.findViewById(getId("status_dot"));
        statusText = rootView.findViewById(getId("status_text"));
        deviceName = rootView.findViewById(getId("device_name"));
        meshStatusDot = rootView.findViewById(getId("mesh_status_dot"));
        meshStatusText = rootView.findViewById(getId("mesh_status_text"));
        meshDeviceName = rootView.findViewById(getId("mesh_device_name"));
        callsignText = rootView.findViewById(getId("text_callsign"));
        contactsText = rootView.findViewById(getId("text_contacts"));
        packetsText = rootView.findViewById(getId("text_packets"));
        receiveRssiText = rootView.findViewById(getId("text_receive_rssi"));
        receiveRssiMeterBlock = rootView.findViewById(getId("rssi_meter_block"));
        receiveRssiMeterFrame = rootView.findViewById(getId("frame_receive_rssi"));
        receiveRssiFillGreen = rootView.findViewById(getId("view_receive_rssi_fill_green"));
        receiveRssiFillRed = rootView.findViewById(getId("view_receive_rssi_fill_red"));
        receiveRssiScaleFrame = rootView.findViewById(getId("frame_receive_rssi_scale"));
        receiveRssiScaleBuilt = false;
        ensureReceiveRssiScaleMarks();
        logText = rootView.findViewById(getId("text_log"));
        encryptionStatusText = rootView.findViewById(getId("text_encryption_status"));
        beaconIntervalText = rootView.findViewById(getId("text_beacon_interval"));
        gpsBeaconIntervalLabel = rootView.findViewById(getId("text_gps_beacon_interval_label"));
        rowBeaconInterval = rootView.findViewById(getId("row_beacon_interval"));
        switchSmartBeacon = rootView.findViewById(getId("switch_smart_beacon"));
        btnManageSmartBeaconSettings = rootView.findViewById(getId("btn_manage_smart_beacon_settings"));
        btnManagePluginBeaconSettings = rootView.findViewById(getId("btn_manage_plugin_beacon_settings"));
        btnAprsBeaconArm = rootView.findViewById(getId("btn_aprs_beacon_arm"));
        textAprsStatusCall = rootView.findViewById(getId("text_aprs_status_call"));
        imageAprsStatusIcon = rootView.findViewById(getId("image_aprs_status_icon"));
        textAprsStatusIcon = rootView.findViewById(getId("text_aprs_status_icon"));
        textAprsStatusMessage = rootView.findViewById(getId("text_aprs_status_message"));
        switchAprsDisableAtak = rootView.findViewById(getId("switch_aprs_disable_atak"));
        btnSendAprsBeacon = rootView.findViewById(getId("btn_send_aprs_beacon"));
        btnEditAprsSettings = rootView.findViewById(getId("btn_edit_aprs_settings"));
        teamColorText = rootView.findViewById(getId("text_team_color"));
        btnScan = rootView.findViewById(getId("btn_scan"));
        btnDisconnect = rootView.findViewById(getId("btn_disconnect"));
        btnMeshScan = rootView.findViewById(getId("btn_mesh_scan"));
        btnMeshDisconnect = rootView.findViewById(getId("btn_mesh_disconnect"));
        btnUpdateGpsFromMeshcore = rootView.findViewById(getId("btn_update_gps_from_meshcore"));
        btnUpdateGpsFromRadio = rootView.findViewById(getId("btn_update_gps_from_radio"));
        switchAugmentGpsFromRadio = rootView.findViewById(getId("switch_augment_gps_from_radio"));
        btnLoadSelectedRepeater = rootView.findViewById(getId("btn_load_selected_repeater"));
        btnTxPower = rootView.findViewById(getId("btn_tx_power"));
        btnDigitalOnlyMode = rootView.findViewById(getId("btn_digital_only_mode"));
        btnRadioSilence = rootView.findViewById(getId("btn_radio_silence"));
        btnRefreshChannels = rootView.findViewById(getId("btn_refresh_channels"));
        btnInitialChannelGroupSetup = rootView.findViewById(getId("btn_initial_channel_group_setup"));
        btnPacketTerminal = rootView.findViewById(getId("btn_packet_terminal"));
        btnChannelGroup = rootView.findViewById(getId("btn_channel_group"));
        btnImportChannels = rootView.findViewById(getId("btn_import_channels"));
        btnExportChannels = rootView.findViewById(getId("btn_export_channels"));
        btnVfoA = rootView.findViewById(getId("btn_vfo_a"));
        btnVfoB = rootView.findViewById(getId("btn_vfo_b"));
        btnDigital = rootView.findViewById(getId("btn_digital"));
        selectedRepeaterText = rootView.findViewById(getId("text_selected_repeater"));
        channelsGrid = rootView.findViewById(getId("grid_channels"));
        switchDualWatch = rootView.findViewById(getId("switch_dual_watch"));
        switchDigitalEdit = rootView.findViewById(getId("switch_digital_edit"));
        switchMeshTransmit = rootView.findViewById(getId("switch_mesh_transmit"));
        switchUvProTransmit = rootView.findViewById(getId("switch_uvpro_transmit"));
        switchWifiTransmit = rootView.findViewById(getId("switch_wifi_transmit"));
        switchMeshEnableGps = rootView.findViewById(getId("switch_mesh_enable_gps"));
        switchAugmentGpsFromMeshcore = rootView.findViewById(getId("switch_augment_gps_from_meshcore"));
        rowAugmentGpsFromMeshcore = rootView.findViewById(getId("row_augment_gps_from_meshcore"));

        favoritesLabel = rootView.findViewById(getId("favorites_label"));
        favoritesScroll = rootView.findViewById(getId("favorites_scroll"));
        favoritesStrip = rootView.findViewById(getId("favorites_strip"));
        meshFavoritesLabel = rootView.findViewById(getId("mesh_favorites_label"));
        meshFavoritesScroll = rootView.findViewById(getId("mesh_favorites_scroll"));
        meshFavoritesStrip = rootView.findViewById(getId("mesh_favorites_strip"));
        connectModeHint = rootView.findViewById(getId("connect_mode_hint"));

        // Interactive switches
        switchEncryption = rootView.findViewById(getId("switch_encryption"));
        passphraseRow = rootView.findViewById(getId("passphrase_row"));
        editPassphrase = rootView.findViewById(getId("edit_passphrase"));
        btnSetPassphrase = rootView.findViewById(getId("btn_set_passphrase"));
    }

    private void setupListeners() {
        btnScan.setOnClickListener(v -> onScanOrConnectClicked());

        btnDisconnect.setOnClickListener(v -> {
            btManager.disconnect();
        });
        if (btnMeshScan != null) {
            btnMeshScan.setOnClickListener(v -> onMeshScanOrConnectClicked());
        }
        if (btnMeshDisconnect != null) {
            btnMeshDisconnect.setOnClickListener(v -> onMeshDisconnectClicked());
        }
        if (switchMeshTransmit != null) {
            switchMeshTransmit.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressTransportSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                if (isChecked) {
                    setTransmitMode(true);
                } else if (switchUvProTransmit != null && !switchUvProTransmit.isChecked()) {
                    setTransmitMode(false);
                }
            });
        }
        if (switchUvProTransmit != null) {
            switchUvProTransmit.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressTransportSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                if (isChecked) {
                    setTransmitMode(false);
                } else if (switchMeshTransmit != null && !switchMeshTransmit.isChecked()) {
                    setTransmitMode(true);
                }
            });
        }
        if (switchWifiTransmit != null) {
            switchWifiTransmit.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressTransportSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                setWifiTransmitEnabled(isChecked, true);
            });
        }
        if (btnUpdateGpsFromRadio != null) {
            btnUpdateGpsFromRadio.setOnClickListener(v -> requestManualRadioGpsUpdate());
        }
        if (btnUpdateGpsFromMeshcore != null) {
            btnUpdateGpsFromMeshcore.setOnClickListener(v -> requestManualMeshGpsUpdate());
        }
        if (switchMeshEnableGps != null) {
            switchMeshEnableGps.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressMeshGpsSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                if (meshBtManager == null || !meshBtManager.isConnected()) {
                    return;
                }
                meshGpsEnableRequested = isChecked;
                if (!isChecked) {
                    meshGpsEnabledState = Boolean.FALSE;
                }
                updateMeshGpsControlsUi();
                appendLog("Setting MeshCore GPS " + (isChecked ? "ON..." : "OFF..."));
                meshBtManager.setMeshGpsEnabled(isChecked);
                meshBtManager.queryMeshGpsEnabled();
            });
        }
        if (switchAugmentGpsFromMeshcore != null) {
            switchAugmentGpsFromMeshcore.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                setAugmentMeshPreference(isChecked);
                scheduleMeshGpsAugmentTick();
                appendLog(isChecked
                        ? "MeshCore GPS augment enabled (2 min when phone has no fix)."
                        : "MeshCore GPS augment disabled.");
            });
        }
        if (switchAugmentGpsFromRadio != null) {
            switchAugmentGpsFromRadio.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                setAugmentGpsPreference(isChecked);
                radioGpsAugmentController.setAugmentEnabled(isChecked);
                appendLog(isChecked
                        ? "Radio GPS augment enabled (fallback mode)."
                        : "Radio GPS augment disabled.");
            });
        }

        // --- Encryption switch ---
        if (switchEncryption != null) {
            switchEncryption.setOnCheckedChangeListener((buttonView, isChecked) -> {
                SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(getMapView().getContext());
                prefs.edit().putBoolean(SettingsFragment.PREF_ENCRYPTION_ENABLED, isChecked).apply();

                if (passphraseRow != null) {
                    passphraseRow.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }

                if (!isChecked && encryptionManager != null) {
                    encryptionManager.setSharedSecret(null);
                    updateEncryptionStatus();
                    appendLog("Encryption disabled");
                } else if (isChecked) {
                    String existing = SettingsFragment.getEncryptionPassphrase(
                            getMapView().getContext());
                    if (existing != null && !existing.isEmpty() && encryptionManager != null) {
                        encryptionManager.setSharedSecret(existing);
                        updateEncryptionStatus();
                        appendLog("Encryption enabled (AES-256-GCM)");
                    } else {
                        updateEncryptionStatus();
                        appendLog("Configure shared secret to enable encryption");
                    }
                }
            });
        }

        if (btnSetPassphrase != null) {
            btnSetPassphrase.setOnClickListener(v -> {
                if (editPassphrase == null) return;
                String pass = editPassphrase.getText().toString().trim();
                if (pass.isEmpty()) {
                    appendLog("Shared secret cannot be empty");
                    return;
                }
                SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(getMapView().getContext());
                prefs.edit().putString(SettingsFragment.PREF_ENCRYPTION_PASSPHRASE, pass).apply();

                if (encryptionManager != null) {
                    encryptionManager.setSharedSecret(pass);
                }
                editPassphrase.setText("");
                updateEncryptionStatus();
                appendLog("Shared secret saved — encryption active");
            });
        }

        // Quick action buttons
        View btnBeacon = rootView.findViewById(getId("btn_send_beacon"));
        if (btnBeacon != null) {
            btnBeacon.setOnClickListener(v -> sendManualBeacon());
        }

        View btnPing = rootView.findViewById(getId("btn_send_ping"));
        if (btnPing != null) {
            btnPing.setOnClickListener(v -> sendPing());
        }

        View btnSettings = rootView.findViewById(getId("btn_settings"));
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showSettingsDialog());
        }
        if (btnPacketTerminal != null) {
            btnPacketTerminal.setOnClickListener(v -> {
                try {
                    AtakBroadcast.getInstance().sendBroadcast(
                            new Intent(PacketTerminalDropDownReceiver.SHOW_PACKET_TERMINAL));
                } catch (Exception e) {
                    appendLog("Packet terminal open failed: " + e.getMessage());
                }
            });
        }
        if (switchSmartBeacon != null) {
            switchSmartBeacon.setOnCheckedChangeListener(smartBeaconCheckedListener);
        }
        if (btnManageSmartBeaconSettings != null) {
            btnManageSmartBeaconSettings.setOnClickListener(v ->
                    com.uvpro.plugin.beacon.SmartBeaconSettingsDialog.show(
                            getMapView().getContext(), () -> {
                                appendLog("Smart beacon settings updated.");
                                try {
                                    AtakBroadcast.getInstance().sendBroadcast(
                                            new Intent(UVProMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
                                } catch (Exception ignored) {
                                }
                            }));
        }
        if (btnManagePluginBeaconSettings != null) {
            btnManagePluginBeaconSettings.setOnClickListener(v -> showSettingsDialog());
        }
        wireAprsSection();

        if (btnLoadSelectedRepeater != null) {
            btnLoadSelectedRepeater.setOnClickListener(v -> armSelectedRepeaterLoad());
        }
        if (btnTxPower != null) {
            btnTxPower.setOnClickListener(v -> cycleTxPower());
        }
        if (btnDigitalOnlyMode != null) {
            btnDigitalOnlyMode.setOnClickListener(v -> onDigitalOnlyModeClick());
            btnDigitalOnlyMode.setOnLongClickListener(v -> {
                if (digitalOnlyActive) {
                    disableDigitalOnlyMode();
                    return true;
                }
                return false;
            });
        }
        if (btnRadioSilence != null) {
            btnRadioSilence.setOnClickListener(v ->
                    Toast.makeText(getMapView().getContext(),
                            "Long press to toggle Radio Silence.",
                            Toast.LENGTH_SHORT).show());
            btnRadioSilence.setOnLongClickListener(v -> {
                boolean enabled = !btManager.isRadioSilenceEnabled();
                btManager.setRadioSilenceEnabled(enabled);
                updateRadioSilenceButtonUi();
                refreshChannelGridAsync();
                appendLog(enabled
                        ? "Radio Silence ON: RF TX/ACKs blocked; control and RX active."
                        : "Radio Silence OFF: RF TX restored.");
                return true;
            });
        }

        if (btnRefreshChannels != null) {
            btnRefreshChannels.setOnClickListener(v -> refreshChannelGridFullAsync());
        }
        if (btnInitialChannelGroupSetup != null) {
            btnInitialChannelGroupSetup.setOnClickListener(v ->
                    Toast.makeText(getMapView().getContext(),
                            "Long press to run Initial Channel Setup.",
                            Toast.LENGTH_SHORT).show());
            btnInitialChannelGroupSetup.setOnLongClickListener(v -> {
                runInitialChannelGroupSetupAsync();
                return true;
            });
        }
        if (btnChannelGroup != null) {
            btnChannelGroup.setOnClickListener(v -> cycleChannelGroup());
        }
        if (btnImportChannels != null) {
            btnImportChannels.setOnClickListener(v -> showImportChannelsPicker());
        }
        if (btnExportChannels != null) {
            btnExportChannels.setOnClickListener(v -> exportCurrentGroupChannels());
        }

        if (switchDualWatch != null) {
            switchDualWatch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                applyDualWatch(isChecked);
            });
            switchDualWatch.setText("");
        }

        if (btnVfoA != null) {
            btnVfoA.setOnClickListener(v -> {
                channelTargetDigital = false;
                selectedTarget = TARGET_A;
                lastAnalogTarget = TARGET_A;
                activeVfoB = false;
                updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                        lastDualWatchEnabled, txVfoB, lastHasRxFocus);
                rerenderGridFromLastSnapshot();
            });
            btnVfoA.setOnLongClickListener(v -> {
                applyTxSelection(false);
                return true;
            });
        }
        if (btnVfoB != null) {
            btnVfoB.setOnClickListener(v -> {
                channelTargetDigital = false;
                selectedTarget = TARGET_B;
                lastAnalogTarget = TARGET_B;
                activeVfoB = true;
                updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                        lastDualWatchEnabled, txVfoB, lastHasRxFocus);
                rerenderGridFromLastSnapshot();
            });
            btnVfoB.setOnLongClickListener(v -> {
                if (btnVfoB.getVisibility() != View.VISIBLE) {
                    return true;
                }
                applyTxSelection(true);
                return true;
            });
        }
        if (btnDigital != null) {
            btnDigital.setOnClickListener(v -> {
                if (!isDigitalEditArmed()) {
                    Toast.makeText(getMapView().getContext(),
                            "Enable 'Slide to edit' first.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                channelTargetDigital = true;
                selectedTarget = TARGET_DIGITAL;
                updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                        lastDualWatchEnabled, txVfoB, lastHasRxFocus);
            });
        }
        if (switchDigitalEdit != null) {
            switchDigitalEdit.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!isChecked && selectedTarget == TARGET_DIGITAL) {
                    restoreAnalogEditTarget();
                }
                updateDigitalEditGuardUi();
            });
            switchDigitalEdit.setText("");
            switchDigitalEdit.setChecked(false);
        }
    }

    private void onScanOrConnectClicked() {
        if (btManager.isConnected()) {
            btManager.disconnect();
            return;
        }
        if (btManager.isConnecting()) {
            appendLog("Cancelling current connection attempt...");
            btManager.cancelConnectionAttempts();
        }
        Context ctx = getMapView().getContext();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            appendLog("Bluetooth not available");
            return;
        }

        String target = BluetoothDeviceRegistry.getConnectTargetAddress(ctx);
        BtDeviceRecord targetRecord =
                (target != null && !target.isEmpty()) ? BluetoothDeviceRegistry.find(ctx, target) : null;
        // Only explicit favorite selection should force CONNECT mode.
        boolean connectMode = targetRecord != null && targetRecord.favorite;

        if (connectMode) {
            scanForNewRadioOnly = false;
            stopScanConnectButtonPulse(true);
            try {
                BluetoothDevice device = adapter.getRemoteDevice(target);
                if (isLikelyMeshNamedDevice(device)) {
                    appendLog("Saved target appears to be MeshCore; switching UV-PRO to scan mode");
                    BluetoothDeviceRegistry.setConnectTargetAddress(ctx, "");
                    refreshFavoriteStrip();
                    updateScanButtonText();
                    foundDevices.clear();
                    requestScanConnectButtonPulse();
                    btManager.startScan();
                    return;
                }
                String display = targetRecord != null
                        ? BluetoothDeviceRegistry.getDisplayTitle(targetRecord)
                        : target;
                appendLog("Connecting to " + display + "...");
                btManager.connect(device);
            } catch (Exception e) {
                appendLog("Saved radio no longer available, switching to scan");
                BluetoothDeviceRegistry.setConnectTargetAddress(ctx, "");
                refreshFavoriteStrip();
                updateScanButtonText();
            }
            return;
        }

        // Scan mode: clear stale auto-target and discover available radios.
        foundDevices.clear();
        scanForNewRadioOnly = true;
        BluetoothDeviceRegistry.setConnectTargetAddress(ctx, "");
        refreshFavoriteStrip();
        updateScanButtonText();
        appendLog("Scanning for radios...");
        requestScanConnectButtonPulse();
        btManager.startScan();
    }

    private void onMeshScanOrConnectClicked() {
        if (meshBtManager == null) {
            appendLog("MeshCore transport unavailable");
            return;
        }
        if (meshBtManager.isConnected()) {
            appendLog("MeshCore already connected");
            updateMeshConnectionUI(true, meshBtManager.getConnectedDeviceName());
            return;
        }
        if (meshBtManager.isConnecting()) {
            appendLog("Cancelling current MeshCore connection attempt...");
            meshBtManager.cancelConnectionAttempts();
            stopMeshConnectButtonPulse(true);
        }
        Context ctx = getMapView().getContext();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            appendLog("Bluetooth not available");
            return;
        }
        String target = BluetoothDeviceRegistry.getMeshConnectTargetAddress(ctx);
        BtDeviceRecord targetRecord =
                (target != null && !target.isEmpty()) ? BluetoothDeviceRegistry.find(ctx, target) : null;
        boolean connectMode = target != null && !target.isEmpty();
        if (connectMode) {
            try {
                BluetoothDevice device = adapter.getRemoteDevice(target);
                String display = targetRecord != null
                        ? BluetoothDeviceRegistry.getDisplayTitle(targetRecord)
                        : target;
                appendLog("Connecting MeshCore to " + display + "...");
                startMeshConnectButtonPulse();
                meshBtManager.connect(device);
            } catch (Exception e) {
                appendLog("Saved MeshCore no longer available, switching to scan");
                BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx, "");
                refreshFavoriteStrip();
                updateMeshScanButtonText();
            }
            return;
        }
        meshFoundDevices.clear();
        BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx, "");
        refreshFavoriteStrip();
        updateMeshScanButtonText();
        appendLog("Scanning for MeshCore devices...");
        meshBtManager.startScan();
    }

    private void onMeshDisconnectClicked() {
        if (meshBtManager != null) {
            meshBtManager.disconnect();
        }
        stopMeshConnectButtonPulse(true);
        meshConnected = false;
        updateMeshConnectionUI(false, null);
        appendLog("MeshCore disconnected");
    }

    private int dip(Context c, int d) {
        return (int) (d * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void updateScanButtonText() {
        if (btnScan == null) return;
        Context ctx = getMapView().getContext();
        String tgt = BluetoothDeviceRegistry.getConnectTargetAddress(ctx);
        BtDeviceRecord rec = (tgt != null && !tgt.isEmpty())
                ? BluetoothDeviceRegistry.find(ctx, tgt)
                : null;
        if (!btManager.isConnected() && rec != null && rec.favorite) {
            btnScan.setText("CONNECT");
        } else {
            btnScan.setText("SCAN & CONNECT");
        }
    }

    private void updateMeshScanButtonText() {
        if (btnMeshScan == null || meshBtManager == null) {
            return;
        }
        Context ctx = getMapView().getContext();
        String tgt = BluetoothDeviceRegistry.getMeshConnectTargetAddress(ctx);
        if (!meshBtManager.isConnected() && tgt != null && !tgt.isEmpty()) {
            btnMeshScan.setText("CONNECT");
        } else {
            btnMeshScan.setText("SCAN & CONNECT");
        }
    }

    private void refreshFavoriteStrip() {
        if (favoritesStrip == null || favoritesScroll == null
                || favoritesLabel == null || connectModeHint == null) {
            return;
        }
        Context ctx = getMapView().getContext();
        favoritesStrip.removeAllViews();
        if (meshFavoritesStrip != null) {
            meshFavoritesStrip.removeAllViews();
        }
        List<BtDeviceRecord> favs = BluetoothDeviceRegistry.getFavoritesSorted(ctx);
        List<BtDeviceRecord> uvFavs = new ArrayList<>();
        List<BtDeviceRecord> meshFavs = new ArrayList<>();
        for (BtDeviceRecord r : favs) {
            if (isLikelyMeshRecord(r)) {
                meshFavs.add(r);
            } else {
                uvFavs.add(r);
            }
        }

        if (uvFavs.isEmpty()) {
            favoritesLabel.setVisibility(View.GONE);
            favoritesScroll.setVisibility(View.GONE);
            connectModeHint.setVisibility(View.GONE);
        } else {
            favoritesLabel.setVisibility(View.VISIBLE);
            favoritesScroll.setVisibility(View.VISIBLE);
            String selectedRadio = BluetoothDeviceRegistry.getConnectTargetAddress(ctx);
            if (selectedRadio != null && !selectedRadio.isEmpty()) {
                connectModeHint.setVisibility(View.VISIBLE);
                connectModeHint.setText(
                        "Direct connect enabled — tap the same favorite again to use Scan instead");
            } else {
                connectModeHint.setVisibility(View.GONE);
            }
            for (BtDeviceRecord r : uvFavs) {
                Button chip = new Button(ctx);
                chip.setAllCaps(false);
                chip.setText(BluetoothDeviceRegistry.getDisplayTitle(r));
                boolean isSel = selectedRadio != null && selectedRadio.equalsIgnoreCase(r.address);
                applyPillButtonBackground(chip, isSel ? 0xFF00788B : 0xFF3D3D3D);
                chip.setTextColor(0xFFFFFFFF);
                int px = dip(ctx, 8);
                chip.setPadding(px, px / 2, px, px / 2);
                chip.setOnClickListener(v -> {
                    String cur = BluetoothDeviceRegistry.getConnectTargetAddress(ctx);
                    if (cur != null && cur.equalsIgnoreCase(r.address)) {
                        BluetoothDeviceRegistry.setConnectTargetAddress(ctx, "");
                        appendLog("Using Scan & Connect mode");
                    } else {
                        BluetoothDeviceRegistry.setConnectTargetAddress(ctx, r.address);
                        appendLog("Selected: " + BluetoothDeviceRegistry.getDisplayTitle(r));
                    }
                    refreshFavoriteStrip();
                    updateScanButtonText();
                    updateMeshScanButtonText();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(dip(ctx, 6));
                favoritesStrip.addView(chip, lp);
            }
        }

        if (meshFavoritesLabel != null && meshFavoritesScroll != null && meshFavoritesStrip != null) {
            if (meshFavs.isEmpty()) {
                meshFavoritesLabel.setVisibility(View.GONE);
                meshFavoritesScroll.setVisibility(View.GONE);
            } else {
                meshFavoritesLabel.setVisibility(View.VISIBLE);
                meshFavoritesScroll.setVisibility(View.VISIBLE);
                String selectedMesh = BluetoothDeviceRegistry.getMeshConnectTargetAddress(ctx);
                for (BtDeviceRecord r : meshFavs) {
                    Button chip = new Button(ctx);
                    chip.setAllCaps(false);
                    chip.setText(BluetoothDeviceRegistry.getDisplayTitle(r));
                    boolean isSel = selectedMesh != null && selectedMesh.equalsIgnoreCase(r.address);
                    applyPillButtonBackground(chip, isSel ? 0xFF00788B : 0xFF3D3D3D);
                    chip.setTextColor(0xFFFFFFFF);
                    int px = dip(ctx, 8);
                    chip.setPadding(px, px / 2, px, px / 2);
                    chip.setOnClickListener(v -> {
                        String cur = BluetoothDeviceRegistry.getMeshConnectTargetAddress(ctx);
                        if (cur != null && cur.equalsIgnoreCase(r.address)) {
                            BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx, "");
                            appendLog("MeshCore using Scan & Connect mode");
                        } else {
                            BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx, r.address);
                            appendLog("MeshCore selected: " + BluetoothDeviceRegistry.getDisplayTitle(r));
                        }
                        refreshFavoriteStrip();
                        updateScanButtonText();
                        updateMeshScanButtonText();
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMarginEnd(dip(ctx, 6));
                    meshFavoritesStrip.addView(chip, lp);
                }
            }
        } else {
            // Backward compatibility if older layout does not include dedicated Mesh favorites row.
            if (!meshFavs.isEmpty() && !uvFavs.isEmpty()) {
                connectModeHint.setVisibility(View.VISIBLE);
            }
        }
    }

    private int getId(String name) {
        return pluginContext.getResources().getIdentifier(
                name, "id", pluginContext.getPackageName());
    }

    // --- Connection Listener callbacks ---

    @Override
    public void onConnected(BluetoothDevice device) {
        radioGpsAugmentController.onRadioConnected();
        if (device != null) {
            BluetoothDeviceRegistry.recordConnection(getMapView().getContext(),
                    device);
        }
        String displayName = "Radio";
        if (device != null) {
            BtDeviceRecord rec = BluetoothDeviceRegistry.find(
                    getMapView().getContext(), device.getAddress());
            if (rec != null) {
                displayName = BluetoothDeviceRegistry.getDisplayTitle(rec);
            } else {
                String name = device.getName();
                displayName = name != null ? name : device.getAddress();
            }
        }
        final String finalDisplay = displayName;
        getMapView().post(() -> {
            scanForNewRadioOnly = false;
            stopScanConnectButtonPulse(true);
            updateConnectionUI(true, finalDisplay);
            applyPreferredTransmitModeForConnectionState(false);
            appendLog("Connected to " + finalDisplay);
            refreshChannelGroupFromRadioAsync(true);
            refreshFavoriteStrip();
            updateScanButtonText();
            refreshTxPowerFromRadioAsync();
            // Follow-up read: some radios return channel/settings a moment later.
            getMapView().postDelayed(this::refreshChannelGridAsync, 900L);
        });
        AtakBroadcast.getInstance().sendBroadcast(
                new Intent(UVProMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
    }

    @Override
    public void onDisconnected(String reason) {
        radioGpsAugmentController.onRadioDisconnected();
        getMapView().post(() -> {
            scanForNewRadioOnly = false;
            stopScanConnectButtonPulse(true);
            clearDigitalOnlyStateUiOnly();
            updateConnectionUI(false, null);
            applyPreferredTransmitModeForConnectionState(false);
            appendLog("Disconnected: " + reason);
        });
    }

    @Override
    public void onError(String error) {
        getMapView().post(() -> {
            stopScanConnectButtonPulse(true);
            appendLog("Error: " + error);
        });
    }

    @Override
    public void onDeviceFound(BluetoothDevice device) {
        if (isLikelyMeshNamedDevice(device)) {
            return;
        }
        String addr = device != null ? device.getAddress() : null;
        if (scanForNewRadioOnly && device != null) {
            int bondState = BluetoothDevice.BOND_NONE;
            try {
                bondState = device.getBondState();
            } catch (Exception ignored) {
            }
            if (bondState != BluetoothDevice.BOND_NONE) {
                final String display = resolveDeviceDisplayName(getMapView().getContext(), device);
                getMapView().post(() ->
                        appendLog("Skipping bonded radio during new-pair scan: " + display));
                return;
            }
        }
        if (addr != null) {
            for (BluetoothDevice existing : foundDevices) {
                if (existing != null && addr.equalsIgnoreCase(existing.getAddress())) {
                    return;
                }
            }
        }
        foundDevices.add(device);
        String name = device != null ? device.getName() : "Unknown";
        final String display = addr != null ? (name + " [" + addr + "]") : name;
        getMapView().post(() -> {
            appendLog("Found: " + display);
        });
    }

    @Override
    public void onScanComplete() {
        getMapView().post(() -> {
            scanForNewRadioOnly = false;
            stopScanConnectButtonPulse(true);
            showDevicePicker();
        });
    }

    // --- Contact Listener callbacks ---

    @Override
    public void onContactUpdated(RadioContact contact) {
        getMapView().post(() -> {
            updateContactCount();
            appendLog("Contact: " + contact.getCallsign()
                    + " (" + String.format(Locale.US, "%.4f, %.4f",
                    contact.getLatitude(), contact.getLongitude()) + ")");
        });
    }

    @Override
    public void onContactRemoved(RadioContact contact) {
        getMapView().post(() -> {
            updateContactCount();
            appendLog("Contact lost: " + contact.getCallsign());
        });
    }

    @Override
    public void onContactCountChanged(int count) {
        getMapView().post(this::updateContactCount);
    }

    // --- PacketCountListener callback ---

    @Override
    public void onPacketReceived() {
        rxCount++;
        getMapView().post(this::updatePacketCount);
    }

    @Override
    public void onPacketTransmitted() {
        txCount++;
        getMapView().post(this::updatePacketCount);
    }

    // --- UI update methods ---

    private void updateConnectionUI(boolean connected, String device) {
        if (statusDot != null) {
            statusDot.setBackgroundColor(connected ? 0xFF4CAF50 : 0xFFFF0000);
        }
        if (statusText != null) {
            statusText.setText(connected ? "Connected" : "Disconnected");
        }
        if (deviceName != null) {
            if (connected && device != null) {
                deviceName.setText(device);
                deviceName.setVisibility(View.VISIBLE);
            } else {
                deviceName.setVisibility(View.GONE);
            }
        }
        if (btnScan != null) btnScan.setEnabled(!connected);
        if (btnDisconnect != null) btnDisconnect.setEnabled(connected);
        if (btnUpdateGpsFromRadio != null) btnUpdateGpsFromRadio.setEnabled(connected);
        if (switchAugmentGpsFromRadio != null) switchAugmentGpsFromRadio.setEnabled(connected);
        if (btnInitialChannelGroupSetup != null) btnInitialChannelGroupSetup.setEnabled(connected);
        if (btnChannelGroup != null) btnChannelGroup.setEnabled(true);
        if (btnImportChannels != null) btnImportChannels.setEnabled(true);
        if (btnExportChannels != null) btnExportChannels.setEnabled(true);
        refreshFavoriteStrip();
        updateScanButtonText();
        if (!connected) {
            renderChannelGrid(null);
            updateReceiveRssiUi(-1);
            clearDigitalOnlyStateUiOnly();
        }
        updateRadioSilenceButtonUi();
        updateAprsSectionUi();
        updateDigitalOnlyButtonUi();
        updateTxPowerButtonUi();
        if (!connected) {
            currentTxPowerLevel = UVProRadioControlManager.TX_POWER_LOW;
            currentChannelGroup = 0;
            availableChannelGroups = UVProRadioControlManager.DEFAULT_GROUP_COUNT;
        }
        updateChannelGroupButtonUi();
    }

    private void updateMeshConnectionUI(boolean connected, String device) {
        if (meshStatusDot != null) {
            meshStatusDot.setBackgroundColor(connected ? 0xFF4CAF50 : 0xFFFF0000);
        }
        if (meshStatusText != null) {
            meshStatusText.setText(connected ? "Connected" : "Disconnected");
        }
        if (meshDeviceName != null) {
            if (connected && device != null) {
                meshDeviceName.setText(device);
                meshDeviceName.setVisibility(View.VISIBLE);
            } else {
                meshDeviceName.setVisibility(View.GONE);
            }
        }
        if (btnMeshScan != null) {
            btnMeshScan.setEnabled(!connected);
        }
        if (btnMeshDisconnect != null) {
            btnMeshDisconnect.setEnabled(connected);
        }
        updateMeshScanButtonText();
        updateMeshGpsControlsUi();
        scheduleMeshGpsAugmentTick();
        MeshStatusOverlay.setConnected(connected);
    }

    private void updateMeshGpsControlsUi() {
        if (switchMeshEnableGps != null) {
            suppressMeshGpsSwitchCallbacks = true;
            try {
                switchMeshEnableGps.setEnabled(meshConnected);
                switchMeshEnableGps.setChecked(meshGpsEnableRequested
                        || Boolean.TRUE.equals(meshGpsEnabledState));
            } finally {
                suppressMeshGpsSwitchCallbacks = false;
            }
        }
        if (switchAugmentGpsFromMeshcore != null) {
            switchAugmentGpsFromMeshcore.setEnabled(meshConnected);
        }
        if (rowAugmentGpsFromMeshcore != null) {
            boolean showMeshAugment = meshConnected
                    && (meshGpsEnableRequested || Boolean.TRUE.equals(meshGpsEnabledState));
            rowAugmentGpsFromMeshcore.setVisibility(showMeshAugment ? View.VISIBLE : View.GONE);
        }
        if (btnUpdateGpsFromMeshcore != null) {
            boolean show = meshConnected
                    && (meshGpsEnableRequested || Boolean.TRUE.equals(meshGpsEnabledState));
            btnUpdateGpsFromMeshcore.setVisibility(show ? View.VISIBLE : View.GONE);
            btnUpdateGpsFromMeshcore.setEnabled(show);
        }
    }

    private void syncTransmitSwitchesUi() {
        suppressTransportSwitchCallbacks = true;
        try {
            if (switchMeshTransmit != null) {
                switchMeshTransmit.setChecked(meshTransmitEnabled);
            }
            if (switchUvProTransmit != null) {
                switchUvProTransmit.setChecked(!meshTransmitEnabled);
            }
            if (switchWifiTransmit != null) {
                switchWifiTransmit.setChecked(wifiTransmitEnabled);
            }
        } finally {
            suppressTransportSwitchCallbacks = false;
        }
        applyActiveTransmitTransport();
    }

    private void setTransmitMode(boolean useMesh) {
        meshTransmitEnabled = useMesh;
        syncTransmitSwitchesUi();
        appendLog(useMesh
                ? "Transmit mode: ATAK MeshCore"
                : "Transmit mode: ATAK UV-PRO");
    }

    private void setWifiTransmitEnabled(boolean enabled, boolean logChange) {
        wifiTransmitEnabled = enabled;
        setWifiTransmitPreference(enabled);
        syncTransmitSwitchesUi();
        if (logChange) {
            appendLog(enabled
                    ? "Transmit mode: ATAK WiFi enabled"
                    : "Transmit mode: ATAK WiFi disabled");
        }
    }

    /**
     * Connection-priority transmit policy:
     *  - UV-PRO connected => UV-PRO transmit
     *  - UV-PRO disconnected + Mesh connected => Mesh transmit
     *  - neither connected => retain current user choice
     */
    private void applyPreferredTransmitModeForConnectionState(boolean logWhenChanged) {
        boolean uvConnected = btManager != null && btManager.isConnected();
        boolean meshConnectedNow = meshBtManager != null && meshBtManager.isConnected();
        boolean targetMode = meshTransmitEnabled;
        if (uvConnected) {
            targetMode = false;
        } else if (meshConnectedNow) {
            targetMode = true;
        }
        if (meshTransmitEnabled != targetMode) {
            meshTransmitEnabled = targetMode;
            syncTransmitSwitchesUi();
            if (logWhenChanged) {
                appendLog(targetMode
                        ? "Transmit mode: ATAK MeshCore"
                        : "Transmit mode: ATAK UV-PRO");
            }
        } else {
            applyActiveTransmitTransport();
        }
    }

    private void applyActiveTransmitTransport() {
        BtConnectionManager active = resolveActiveTransmitManager();
        if (cotBridge != null) {
            cotBridge.setBtManager(active);
        }
        if (chatBridge != null) {
            chatBridge.setBtManager(active);
        }
    }

    private BtConnectionManager resolveActiveTransmitManager() {
        BtConnectionManager preferred = meshTransmitEnabled && meshBtManager != null
                ? meshBtManager
                : btManager;
        BtConnectionManager alternate = preferred == meshBtManager ? btManager : meshBtManager;
        if (preferred != null && preferred.isConnected()) {
            return preferred;
        }
        if (alternate != null && alternate.isConnected()) {
            return alternate;
        }
        return preferred != null ? preferred : alternate;
    }

    private void showMeshDevicePicker() {
        if (meshFoundDevices.isEmpty()) {
            appendLog("No MeshCore devices found");
            return;
        }
        Context ctx = getMapView().getContext();

        final String[] names = new String[meshFoundDevices.size()];
        for (int i = 0; i < meshFoundDevices.size(); i++) {
            names[i] = resolveDeviceDisplayName(ctx, meshFoundDevices.get(i));
        }
        try {
            new AlertDialog.Builder(ctx)
                    .setTitle("Select MeshCore")
                    .setItems(names, (dialog, which) -> {
                        if (which < 0 || which >= meshFoundDevices.size()) {
                            return;
                        }
                        BluetoothDevice selected = meshFoundDevices.get(which);
                        BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx, selected.getAddress());
                        appendLog("Connecting MeshCore to " + names[which] + "...");
                        updateMeshScanButtonText();
                        startMeshConnectButtonPulse();
                        meshBtManager.connect(selected);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing MeshCore picker", e);
            appendLog("Error showing MeshCore picker");
        }
    }

    private void updateContactCount() {
        if (contactsText != null) {
            int active = contactTracker.getActiveCount();
            int total = contactTracker.getTotalCount();
            contactsText.setText(active + " active / " + total + " total");
        }
    }

    private void updatePacketCount() {
        if (packetsText != null) {
            packetsText.setText(txCount + " / " + rxCount);
        }
    }

    /** Receive S-meter from radio GET_HT_STATUS (0-15); -1 when unknown/disconnected. */
    private void updateReceiveRssiUi(int receiveRssi) {
        if (receiveRssiText == null) {
            return;
        }
        boolean connected = btManager != null && btManager.isConnected();
        if (!connected || receiveRssi < 0) {
            lastReceiveRssiScaleLevel = -1;
            receiveRssiText.setText("—");
            if (receiveRssiMeterBlock != null) {
                receiveRssiMeterBlock.setVisibility(View.GONE);
            }
            return;
        }
        final int scaleLevel = mapReceiveRssiToScale(receiveRssi);
        lastReceiveRssiScaleLevel = scaleLevel;
        receiveRssiText.setText(String.format(Locale.US, "%d / %d", scaleLevel, RSSI_SCALE_MAX));
        if (receiveRssiMeterBlock != null && receiveRssiMeterFrame != null
                && receiveRssiFillGreen != null && receiveRssiFillRed != null) {
            receiveRssiMeterBlock.setVisibility(View.VISIBLE);
            scheduleReceiveRssiMeterLayout();
        }
    }

    private void scheduleReceiveRssiMeterLayout() {
        if (receiveRssiMeterFrame == null || lastReceiveRssiScaleLevel < 0) {
            return;
        }
        receiveRssiMeterFrame.post(() -> {
            if (receiveRssiMeterFrame.getWidth() > 0) {
                applyReceiveRssiFillWidth(lastReceiveRssiScaleLevel);
                layoutReceiveRssiScale();
                return;
            }
            ViewTreeObserver observer = receiveRssiMeterFrame.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    ViewTreeObserver current = receiveRssiMeterFrame.getViewTreeObserver();
                    if (current.isAlive()) {
                        current.removeOnGlobalLayoutListener(this);
                    }
                    if (receiveRssiMeterFrame.getWidth() > 0 && lastReceiveRssiScaleLevel >= 0) {
                        applyReceiveRssiFillWidth(lastReceiveRssiScaleLevel);
                        layoutReceiveRssiScale();
                    }
                }
            });
        });
    }

    /** Map radio RSSI (0-15) to on-screen S-scale (0-12). */
    private static int mapReceiveRssiToScale(int receiveRssi) {
        int raw = Math.max(0, Math.min(15, receiveRssi));
        return Math.min(RSSI_SCALE_MAX, Math.round(raw * (RSSI_SCALE_MAX / 15.0f)));
    }

    private void applyReceiveRssiFillWidth(int scaleLevel) {
        if (receiveRssiMeterFrame == null || receiveRssiFillGreen == null
                || receiveRssiFillRed == null) {
            return;
        }
        int trackWidth = receiveRssiMeterFrame.getWidth();
        if (trackWidth <= 0) {
            return;
        }
        int clamped = Math.max(0, Math.min(RSSI_SCALE_MAX, scaleLevel));
        int ninePx = scalePositionPx(trackWidth, 9);
        int totalPx = clamped == 0
                ? 0
                : Math.max(2, scalePositionPx(trackWidth, clamped));

        int greenWidth = clamped <= 9 ? totalPx : ninePx;
        android.widget.FrameLayout.LayoutParams greenLp =
                (android.widget.FrameLayout.LayoutParams) receiveRssiFillGreen.getLayoutParams();
        greenLp.width = greenWidth;
        greenLp.leftMargin = 0;
        receiveRssiFillGreen.setLayoutParams(greenLp);

        if (clamped > 9) {
            int redWidth = Math.max(0, totalPx - ninePx);
            receiveRssiFillRed.setVisibility(redWidth > 0 ? View.VISIBLE : View.GONE);
            android.widget.FrameLayout.LayoutParams redLp =
                    (android.widget.FrameLayout.LayoutParams) receiveRssiFillRed.getLayoutParams();
            redLp.width = redWidth;
            redLp.leftMargin = ninePx;
            redLp.gravity = android.view.Gravity.START | android.view.Gravity.TOP;
            receiveRssiFillRed.setLayoutParams(redLp);
        } else {
            receiveRssiFillRed.setVisibility(View.GONE);
        }
    }

    private void ensureReceiveRssiScaleMarks() {
        if (receiveRssiScaleBuilt || receiveRssiScaleFrame == null) {
            return;
        }
        Context ctx = getMapView().getContext();
        int tickW = dip(ctx, 1);
        int tickH = dip(ctx, 6);
        for (int pos : RSSI_SCALE_TICKS) {
            View tick = new View(ctx);
            tick.setTag("tick:" + pos);
            tick.setBackgroundColor(0xFFFFFFFF);
            receiveRssiScaleFrame.addView(tick,
                    new android.widget.FrameLayout.LayoutParams(tickW, tickH));
        }
        for (int i = 0; i < RSSI_SCALE_LABELS.length; i++) {
            TextView label = new TextView(ctx);
            label.setText(RSSI_SCALE_LABELS[i]);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(9f);
            label.setTag("label:" + RSSI_SCALE_LABEL_POS[i]);
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dip(ctx, 8);
            receiveRssiScaleFrame.addView(label, lp);
        }
        receiveRssiScaleBuilt = true;
    }

    private void layoutReceiveRssiScale() {
        if (receiveRssiScaleFrame == null) {
            return;
        }
        int width = receiveRssiScaleFrame.getWidth();
        if (width <= 0) {
            return;
        }
        for (int i = 0; i < receiveRssiScaleFrame.getChildCount(); i++) {
            View child = receiveRssiScaleFrame.getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof String)) {
                continue;
            }
            String tagStr = (String) tag;
            if (tagStr.startsWith("tick:")) {
                int pos = Integer.parseInt(tagStr.substring(5));
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) child.getLayoutParams();
                int tickW = lp.width > 0 ? lp.width : 1;
                int x = scalePositionPx(width, pos) - tickW / 2;
                lp.leftMargin = Math.min(Math.max(0, width - tickW), Math.max(0, x));
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                child.setLayoutParams(lp);
            } else if (tagStr.startsWith("label:")) {
                int pos = Integer.parseInt(tagStr.substring(6));
                int xCenter = scalePositionPx(width, pos);
                int measuredW = child.getWidth();
                if (measuredW <= 0) {
                    child.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    measuredW = child.getMeasuredWidth();
                }
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) child.getLayoutParams();
                lp.leftMargin = Math.max(0, xCenter - measuredW / 2);
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                child.setLayoutParams(lp);
            }
        }
    }

    private static int scalePositionPx(int trackWidth, int scaleMark) {
        int clamped = Math.max(0, Math.min(RSSI_SCALE_MAX, scaleMark));
        return (int) (trackWidth * (clamped / (float) RSSI_SCALE_MAX));
    }

    private void updateStatusFields() {
        Context ctx = getMapView().getContext();

        boolean encOn = SettingsFragment.isEncryptionEnabled(ctx);
        if (switchEncryption != null) {
            switchEncryption.setChecked(encOn);
        }

        if (passphraseRow != null) {
            passphraseRow.setVisibility(encOn ? View.VISIBLE : View.GONE);
        }

        updateEncryptionStatus();

        // Beacon interval
        int beaconSec = SettingsFragment.getBeaconIntervalSec(ctx);
        if (beaconIntervalText != null) {
            beaconIntervalText.setText(beaconSec + "s");
        }

        boolean smartOn = SmartBeacon.isEnabled(ctx);
        if (switchSmartBeacon != null) {
            switchSmartBeacon.setChecked(smartOn);
        }
        applySmartBeaconIntervalGreyState(smartOn);
        if (switchAugmentGpsFromRadio != null) {
            switchAugmentGpsFromRadio.setChecked(isAugmentGpsPreferenceEnabled(ctx));
        }
        if (switchAugmentGpsFromMeshcore != null) {
            switchAugmentGpsFromMeshcore.setChecked(isAugmentMeshPreferenceEnabled(ctx));
        }

        // Team color (ATAK preference)
        try {
            String teamColor = com.atakmap.android.chat.ChatManagerMapComponent.getTeamName();
            if (teamColorText != null) {
                teamColorText.setText(teamColor != null ? teamColor : "Cyan");
            }
        } catch (Exception ignored) {
        }
        updateRadioSilenceButtonUi();
        updateAprsSectionUi();
        updateChannelGroupButtonUi();
    }

    private void requestManualRadioGpsUpdate() {
        if (!btManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to the radio first.", Toast.LENGTH_SHORT).show();
            return;
        }
        pulseUpdateGpsButtonFeedback(btnUpdateGpsFromRadio);
        appendLog("Reading GPS from radio...");
        new Thread(() -> {
            RadioGpsBridge.UpdateResult result = radioGpsAugmentController.manualUpdate();
            getMapView().post(() -> {
                appendLog(result.message);
            });
        }, "uvpro-radio-gps-manual").start();
    }

    private void requestManualMeshGpsUpdate() {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to MeshCore first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Boolean.TRUE.equals(meshGpsEnabledState)) {
            Toast.makeText(getMapView().getContext(),
                    "Enable MeshCore GPS first.", Toast.LENGTH_SHORT).show();
            return;
        }
        pulseUpdateGpsButtonFeedback(btnUpdateGpsFromMeshcore);
        appendLog("Reading GPS from MeshCore...");
        new Thread(() -> {
            RadioGpsBridge.UpdateResult result = updateGpsFromMeshcoreNow(true);
            getMapView().post(() -> appendLog(result.message));
        }, "uvpro-mesh-gps-manual").start();
    }

    private RadioGpsBridge.UpdateResult updateGpsFromMeshcoreNow(boolean requestFreshSelfInfo) {
        if (meshBtManager == null || !meshBtManager.isConnected()) {
            return new RadioGpsBridge.UpdateResult(false, "MeshCore not connected.");
        }
        if (requestFreshSelfInfo) {
            meshBtManager.requestSelfInfo();
            try {
                Thread.sleep(700L);
            } catch (InterruptedException ignored) {
            }
        }
        MeshBtConnectionManager.MeshLocationFix fix = meshBtManager.getLatestSelfLocation();
        if (fix == null || !fix.isValid()) {
            return new RadioGpsBridge.UpdateResult(false,
                    "MeshCore did not provide a valid GPS fix yet.");
        }
        RadioPositionFix radioFix = new RadioPositionFix(
                fix.latitude,
                fix.longitude,
                0.0,
                Double.NaN,
                Double.NaN,
                0,
                System.currentTimeMillis() / 1000L);
        boolean ok = RadioGpsBridge.injectIntoAtak(
                getMapView(),
                radioFix,
                RadioGpsBridge.MESHCORE_MOCK_SOURCE_LABEL);
        return ok
                ? new RadioGpsBridge.UpdateResult(true, String.format(
                Locale.US,
                "Updated ATAK from MeshCore GPS: %.5f, %.5f",
                fix.latitude,
                fix.longitude))
                : new RadioGpsBridge.UpdateResult(false,
                "Could not apply MeshCore GPS to ATAK.");
    }

    private boolean isAugmentGpsPreferenceEnabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getBoolean(RadioGpsBridge.PREF_AUGMENT_GPS_FROM_RADIO, false);
    }

    private boolean isAugmentMeshPreferenceEnabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getBoolean(PREF_AUGMENT_GPS_FROM_MESHCORE, false);
    }

    private void setAugmentGpsPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().putBoolean(RadioGpsBridge.PREF_AUGMENT_GPS_FROM_RADIO, enabled).apply();
    }

    private void setAugmentMeshPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().putBoolean(PREF_AUGMENT_GPS_FROM_MESHCORE, enabled).apply();
    }

    private boolean isWifiTransmitPreferenceEnabled(Context ctx) {
        if (ctx == null) {
            return true;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getBoolean(PREF_ATAK_WIFI_TRANSMIT, true);
    }

    private void setWifiTransmitPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().putBoolean(PREF_ATAK_WIFI_TRANSMIT, enabled).apply();
    }

    private boolean isWifiConnected() {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return false;
        }
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null
                    && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void scheduleMeshGpsAugmentTick() {
        if (getMapView() == null) {
            return;
        }
        getMapView().removeCallbacks(meshGpsAugmentRunnable);
        Context ctx = getMapView().getContext();
        if (!isAugmentMeshPreferenceEnabled(ctx)) {
            return;
        }
        getMapView().postDelayed(meshGpsAugmentRunnable, RadioGpsBridge.AUGMENT_INTERVAL_MS);
    }

    private void updateChannelGroupButtonUi() {
        if (btnChannelGroup == null) {
            return;
        }
        btnChannelGroup.setText(String.format(
                Locale.US, "Group\n%d", currentChannelGroup + 1));
    }

    private void refreshChannelGroupFromRadioAsync() {
        refreshChannelGroupFromRadioAsync(false);
    }

    private void refreshChannelGroupFromRadioAsync(boolean refreshGrid) {
        if (radioControlManager == null || !btManager.isConnected()) {
            return;
        }
        if (!groupReadInFlight.compareAndSet(false, true)) {
            if (refreshGrid) {
                groupRefreshGridPending.set(true);
            }
            return;
        }
        final boolean requestGridRefresh = refreshGrid;
        new Thread(() -> {
            try {
                UVProRadioControlManager.ChannelGroupInfo info =
                        radioControlManager.readCurrentGroupInfo();
                if (info == null) {
                    // Startup read can be transiently empty while radio settles.
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    info = radioControlManager.readCurrentGroupInfo();
                }
                if (info == null) {
                    if (requestGridRefresh) {
                        getMapView().post(this::refreshChannelGridAsync);
                    }
                    return;
                }
                final UVProRadioControlManager.ChannelGroupInfo finalInfo = info;
                getMapView().post(() -> {
                    currentChannelGroup = finalInfo.currentGroupIndex;
                    availableChannelGroups = Math.max(1, finalInfo.groupCount);
                    updateChannelGroupButtonUi();
                    if (requestGridRefresh) {
                        refreshChannelGridFullAsync();
                    }
                });
            } finally {
                groupReadInFlight.set(false);
                if (groupRefreshGridPending.getAndSet(false)) {
                    refreshChannelGroupFromRadioAsync(true);
                }
            }
        }, "uvpro-read-group").start();
    }

    private void runInitialChannelGroupSetupAsync() {
        if (radioControlManager == null || !btManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to radio first.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        pulseInitialGroupSetupButtonFeedback();
        appendLog("Starting Initial Channel Group Setup...");
        if (btnInitialChannelGroupSetup != null) {
            btnInitialChannelGroupSetup.setEnabled(false);
        }
        new Thread(() -> {
            try {
                UVProRadioControlManager.ChannelGroupInfo info = radioControlManager.readCurrentGroupInfo();
                final int originalGroup = info != null ? info.currentGroupIndex : currentChannelGroup;
                final int groupCount = info != null
                        ? Math.max(1, info.groupCount)
                        : UVProRadioControlManager.DEFAULT_GROUP_COUNT;
                UVProRadioControlManager.ManualChannelSpec aprsSeed =
                        new UVProRadioControlManager.ManualChannelSpec(
                                "APRS",
                                144.390,
                                144.390,
                                null,
                                null,
                                true,
                                false,
                                true,
                                true,
                                -1);
                int seeded = 0;
                for (int group = 0; group < groupCount; group++) {
                    UVProRadioControlManager.ProgramResult select = radioControlManager.setChannelGroup(group);
                    if (!select.success) {
                        // Empty groups may reject selection until seeded.
                        UVProRadioControlManager.ProgramResult blindSeed =
                                radioControlManager.seedAprsChannelInGroupBlind(group, aprsSeed);
                        if (blindSeed.success) {
                            seeded++;
                        }
                        continue;
                    }
                    UVProRadioControlManager.RadioControlSnapshot snapshot = radioControlManager.readSnapshot(30);
                    if (isGroupEmpty(snapshot)) {
                        UVProRadioControlManager.ProgramResult write =
                                radioControlManager.programManualChannel(
                                        UVProRadioControlManager.CHANNELS_PER_GROUP - 1,
                                        aprsSeed);
                        if (write.success) {
                            seeded++;
                        }
                    }
                }
                radioControlManager.setChannelGroup(originalGroup);
                final int seededCount = seeded;
                getMapView().post(() -> {
                    if (btnInitialChannelGroupSetup != null) {
                        btnInitialChannelGroupSetup.setEnabled(true);
                    }
                    stopInitialGroupSetupPulse(true);
                    refreshChannelGroupFromRadioAsync(true);
                    appendLog("Channel Group Setup Complete");
                    showInfoDialog("Channel Group Setup Complete");
                    if (seededCount > 0) {
                        appendLog(String.format(Locale.US,
                                "Seeded APRS on CH30 for %d empty group(s).", seededCount));
                    }
                });
            } catch (Exception e) {
                getMapView().post(() -> {
                    if (btnInitialChannelGroupSetup != null) {
                        btnInitialChannelGroupSetup.setEnabled(true);
                    }
                    stopInitialGroupSetupPulse(true);
                    appendLog("Initial group setup failed: " + e.getMessage());
                });
            }
        }, "uvpro-initial-group-setup").start();
    }

    private static boolean isGroupEmpty(UVProRadioControlManager.RadioControlSnapshot snapshot) {
        if (snapshot == null || snapshot.channels == null || snapshot.channels.length == 0) {
            return true;
        }
        for (UVProRadioControlManager.ChannelSummary c : snapshot.channels) {
            if (c == null) {
                continue;
            }
            boolean hasFreq = c.rxFreqMHz > 0.0 || c.txFreqMHz > 0.0;
            boolean hasName = c.name != null && !c.name.trim().isEmpty();
            if (hasFreq || hasName) {
                return false;
            }
        }
        return true;
    }

    private void cycleChannelGroup() {
        if (radioControlManager == null || !btManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to radio first.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final int next = (currentChannelGroup + 1) % Math.max(1, availableChannelGroups);
        appendLog(String.format(Locale.US, "Switching to Group %d...", next + 1));
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.setChannelGroup(next);
            getMapView().post(() -> {
                appendLog(result.message);
                if (result.success) {
                    currentChannelGroup = next;
                    updateChannelGroupButtonUi();
                    refreshChannelGridFullAsync();
                    verifyGroupSwitchAcceptedAsync(next);
                } else {
                    Toast.makeText(getMapView().getContext(),
                            result.message, Toast.LENGTH_LONG).show();
                }
            });
        }, "uvpro-set-group").start();
    }

    private void verifyGroupSwitchAcceptedAsync(int expectedGroup) {
        if (radioControlManager == null || !btManager.isConnected()) {
            return;
        }
        new Thread(() -> {
            UVProRadioControlManager.ChannelGroupInfo info = radioControlManager.readCurrentGroupInfo();
            if (info == null) {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                info = radioControlManager.readCurrentGroupInfo();
            }
            if (info != null && info.currentGroupIndex != expectedGroup) {
                getMapView().post(() -> showInfoDialog(
                        "Please run \"Initial Channel Group Setup\" under the actions settings of the plugin"));
            }
        }, "uvpro-verify-group-switch").start();
    }

    private void showImportChannelsPicker() {
        File dir = new File("/sdcard/atak/tools/import");
        if (!dir.exists() || !dir.isDirectory()) {
            appendLog("Import folder not found: /atak/tools/import");
            return;
        }
        File[] csv = dir.listFiles(f -> f != null && f.isFile()
                && f.getName().toLowerCase(Locale.US).endsWith(".csv"));
        if (csv == null || csv.length == 0) {
            appendLog("No CSV files found in /atak/tools/import");
            return;
        }
        java.util.Arrays.sort(csv, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        String[] names = new String[csv.length];
        for (int i = 0; i < csv.length; i++) {
            names[i] = csv[i].getName();
        }
        final int[] selectedIndex = {-1};
        new AlertDialog.Builder(getMapView().getContext())
                .setTitle("Import Channels")
                .setSingleChoiceItems(names, -1, (d, which) -> selectedIndex[0] = which)
                .setPositiveButton("Import", (d, which) -> {
                    if (selectedIndex[0] < 0 || selectedIndex[0] >= csv.length) {
                        appendLog("No CSV selected for import.");
                        return;
                    }
                    importChannelsFromFile(csv[selectedIndex[0]]);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void importChannelsFromFile(File csvFile) {
        if (radioControlManager == null || !btManager.isConnected()) {
            appendLog("Connect to radio before import.");
            return;
        }
        final int targetGroup = currentChannelGroup;
        appendLog(String.format(Locale.US,
                "Importing %s to Group %d...", csvFile.getName(), targetGroup + 1));
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult select =
                    radioControlManager.setChannelGroup(targetGroup);
            if (!select.success) {
                getMapView().post(() -> appendLog("Import aborted: " + select.message));
                return;
            }
            UVProRadioControlManager.ManualChannelSpec[] rows = parseCsvChannelsBySlot(csvFile);
            int validRows = 0;
            for (UVProRadioControlManager.ManualChannelSpec row : rows) {
                if (row != null) {
                    validRows++;
                }
            }
            if (validRows == 0) {
                getMapView().post(() -> appendLog("No valid channels parsed from CSV."));
                return;
            }
            int applied = 0;
            int cleared = 0;
            String firstError = null;
            for (int i = 0; i < Math.min(UVProRadioControlManager.CHANNELS_PER_GROUP, rows.length); i++) {
                UVProRadioControlManager.ProgramResult r;
                if (rows[i] == null) {
                    // CSV empty row means explicitly clear this channel slot.
                    r = radioControlManager.clearManualChannel(i);
                    if (r.success) {
                        cleared++;
                    }
                } else {
                    r = radioControlManager.programManualChannel(i, rows[i]);
                }
                if (r.success) {
                    applied++;
                } else if (firstError == null) {
                    firstError = r.message;
                }
            }
            int imported = applied;
            String finalError = firstError;
            int finalCleared = cleared;
            getMapView().post(() -> {
                appendLog(String.format(Locale.US,
                        "Imported %d slot(s) to Group %d (%d cleared).",
                        imported, targetGroup + 1, finalCleared));
                if (finalError != null) {
                    appendLog("Import note: " + finalError);
                }
                refreshChannelGroupFromRadioAsync(true);
            });
        }, "uvpro-import-group").start();
    }

    private void exportCurrentGroupChannels() {
        if (radioControlManager == null || !btManager.isConnected()) {
            appendLog("Connect to radio before export.");
            return;
        }
        new Thread(() -> {
            UVProRadioControlManager.RadioControlSnapshot snapshot =
                    radioControlManager.readSnapshotForGroup(currentChannelGroup, 30);
            if (snapshot == null || snapshot.channels == null) {
                getMapView().post(() -> appendLog("Export failed: could not read channels."));
                return;
            }
            File outDir = new File("/sdcard/atak/tools/datapackage/transfer");
            if (!outDir.exists() && !outDir.mkdirs()) {
                getMapView().post(() -> appendLog("Export failed: could not create output folder."));
                return;
            }
            String dtg = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = String.format(Locale.US, "group%d_export_%s.csv",
                    currentChannelGroup + 1, dtg);
            File out = new File(outDir, fileName);
            try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
                pw.println("Channel,Name,RX_MHz,TX_MHz,TX_Tone,RX_Tone,Scan,Muted");
                for (UVProRadioControlManager.ChannelSummary c : snapshot.channels) {
                    if (c == null) continue;
                    pw.println(String.format(Locale.US, "%d,%s,%.5f,%.5f,%s,%s,%s,%s",
                            displayChannelNumber(c.channelId),
                            safeCsv(c.name),
                            c.rxFreqMHz,
                            c.txFreqMHz,
                            safeCsv(String.valueOf(c.txTone == null ? "" : c.txTone)),
                            safeCsv(String.valueOf(c.rxTone == null ? "" : c.rxTone)),
                            c.scanEnabled,
                            c.muted));
                }
            } catch (Exception e) {
                getMapView().post(() -> appendLog("Export failed: " + e.getMessage()));
                return;
            }
            getMapView().post(() -> {
                appendLog(fileName + " exported to /atak/tools/datapackage/transfer");
                Toast.makeText(getMapView().getContext(),
                        fileName + " exported to /atak/tools/datapackage/transfer",
                        Toast.LENGTH_LONG).show();
            });
        }, "uvpro-export-group").start();
    }

    private UVProRadioControlManager.ManualChannelSpec[] parseCsvChannelsBySlot(File file) {
        UVProRadioControlManager.ManualChannelSpec[] out =
                new UVProRadioControlManager.ManualChannelSpec[UVProRadioControlManager.CHANNELS_PER_GROUP];
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            Map<String, Integer> hdr = new HashMap<>();
            boolean first = true;
            int rowIndex = 0;
            while ((line = br.readLine()) != null) {
                line = line.replace("\u0000", "");
                if (line.startsWith("#")) continue;
                // After header is parsed, blank lines represent empty channel slots.
                if (line.trim().isEmpty()) {
                    if (!first && rowIndex < UVProRadioControlManager.CHANNELS_PER_GROUP) {
                        rowIndex++;
                    }
                    continue;
                }
                String[] p = line.split(",", -1);
                if (first) {
                    first = false;
                    for (int i = 0; i < p.length; i++) {
                        hdr.put(p[i].trim().toLowerCase(Locale.US), i);
                    }
                    if (hdr.containsKey("rx_mhz") || hdr.containsKey("rx")
                            || hdr.containsKey("frequency")
                            || hdr.containsKey("rx_freq")
                            || hdr.containsKey("tx_freq")) {
                        continue;
                    }
                }
                if (rowIndex >= UVProRadioControlManager.CHANNELS_PER_GROUP) {
                    break;
                }
                double rx = parseCsvFrequency(p, hdr,
                        "rx_mhz", "rx", "frequency", "rxfreq", "rx_freq", "receive_frequency");
                if (rx <= 0) {
                    rowIndex++;
                    continue;
                }
                double tx = parseCsvFrequency(p, hdr,
                        "tx_mhz", "tx", "txfreq", "tx_freq", "transmit_frequency");
                if (tx <= 0) tx = rx;
                String name = parseCsvText(p, hdr, "name", "channel_name", "title", "channel");
                if (name == null || name.trim().isEmpty()) {
                    name = "CH";
                }
                name = name.replace("\u0000", "").trim();
                UVProRadioControlManager.ManualChannelSpec spec =
                        new UVProRadioControlManager.ManualChannelSpec(
                                name,
                                rx,
                                tx,
                                null,
                                null,
                                true,
                                false,
                                true,
                                true,
                                -1);
                out[rowIndex] = spec;
                rowIndex++;
            }
        } catch (Exception e) {
            Log.w(TAG, "parseCsvChannels failed", e);
        }
        return out;
    }

    private static double parseCsvFrequency(String[] parts, Map<String, Integer> hdr, String... keys) {
        double raw = parseCsvDouble(parts, hdr, keys);
        if (raw <= 0) {
            return -1.0;
        }
        // Accept Hz-formatted CSVs (e.g. 146520000) and MHz-formatted CSVs.
        return raw > 1_000_000.0 ? (raw / 1_000_000.0) : raw;
    }

    private static double parseCsvDouble(String[] parts, Map<String, Integer> hdr, String... keys) {
        for (String k : keys) {
            Integer idx = hdr.get(k.toLowerCase(Locale.US));
            if (idx != null && idx >= 0 && idx < parts.length) {
                try {
                    return Double.parseDouble(parts[idx].trim());
                } catch (Exception ignored) {
                }
            }
        }
        return -1.0;
    }

    private static String parseCsvText(String[] parts, Map<String, Integer> hdr, String... keys) {
        for (String k : keys) {
            Integer idx = hdr.get(k.toLowerCase(Locale.US));
            if (idx != null && idx >= 0 && idx < parts.length) {
                String v = parts[idx].trim();
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }

    private int displayChannelNumber(int channelId) {
        if (channelId >= 0 && channelId < UVProRadioControlManager.CHANNELS_PER_GROUP) {
            return channelId + 1;
        }
        return Math.max(1, channelId + 1);
    }

    private static String safeCsv(String value) {
        if (value == null) return "";
        String v = value.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\"")) {
            return "\"" + v + "\"";
        }
        return v;
    }

    private void pulseUpdateGpsButtonFeedback(Button targetButton) {
        if (targetButton == null) {
            return;
        }
        try {
            targetButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ignored) {
        }
        stopUpdateGpsButtonPulse(false);
        updateGpsPulseTargetButton = targetButton;
        targetButton.setBackgroundTintList(null);
        updateGpsButtonPulseDrawable = buildVfoButtonBackground(
                0xFF455A64, 0x00FFEB3B, EDIT_SELECTION_STROKE_DP);
        targetButton.setBackground(updateGpsButtonPulseDrawable);
        updateGpsButtonPulseAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                0x11FFEB3B,
                0xFFFFEB3B);
        updateGpsButtonPulseAnimator.setDuration(220L);
        updateGpsButtonPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        updateGpsButtonPulseAnimator.setRepeatCount(4);
        updateGpsButtonPulseAnimator.addUpdateListener(animation -> {
            if (updateGpsButtonPulseDrawable == null || updateGpsPulseTargetButton == null) {
                return;
            }
            int color = (Integer) animation.getAnimatedValue();
            updateGpsButtonPulseDrawable.setStroke(
                    dip(getMapView().getContext(), EDIT_SELECTION_STROKE_DP), color);
            updateGpsPulseTargetButton.invalidate();
        });
        updateGpsButtonPulseAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                stopUpdateGpsButtonPulse(true);
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                stopUpdateGpsButtonPulse(true);
            }
        });
        updateGpsButtonPulseAnimator.start();
    }

    private void pulseInitialGroupSetupButtonFeedback() {
        if (btnInitialChannelGroupSetup == null) {
            return;
        }
        try {
            btnInitialChannelGroupSetup.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ignored) {
        }
        stopInitialGroupSetupPulse(false);
        btnInitialChannelGroupSetup.setBackgroundTintList(null);
        initialGroupSetupPulseDrawable = buildVfoButtonBackground(
                0xFF455A64, 0x00FFEB3B, EDIT_SELECTION_STROKE_DP);
        btnInitialChannelGroupSetup.setBackground(initialGroupSetupPulseDrawable);
        initialGroupSetupPulseAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                0x11FFEB3B,
                0xFFFFEB3B);
        initialGroupSetupPulseAnimator.setDuration(220L);
        initialGroupSetupPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        initialGroupSetupPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        initialGroupSetupPulseAnimator.addUpdateListener(animation -> {
            if (initialGroupSetupPulseDrawable == null || btnInitialChannelGroupSetup == null) {
                return;
            }
            int color = (Integer) animation.getAnimatedValue();
            initialGroupSetupPulseDrawable.setStroke(
                    dip(getMapView().getContext(), EDIT_SELECTION_STROKE_DP), color);
            btnInitialChannelGroupSetup.invalidate();
        });
        initialGroupSetupPulseAnimator.start();
    }

    private void stopInitialGroupSetupPulse(boolean restoreBackground) {
        ValueAnimator animator = initialGroupSetupPulseAnimator;
        initialGroupSetupPulseAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
        initialGroupSetupPulseDrawable = null;
        if (restoreBackground && btnInitialChannelGroupSetup != null) {
            applyPillButtonBackground(btnInitialChannelGroupSetup, 0xFF455A64);
        }
    }

    private void showInfoDialog(String message) {
        if (getMapView() == null || getMapView().getContext() == null) {
            return;
        }
        try {
            new AlertDialog.Builder(getMapView().getContext())
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception ignored) {
        }
    }

    private void stopUpdateGpsButtonPulse(boolean restoreBackground) {
        ValueAnimator animator = updateGpsButtonPulseAnimator;
        updateGpsButtonPulseAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
        updateGpsButtonPulseDrawable = null;
        Button target = updateGpsPulseTargetButton;
        updateGpsPulseTargetButton = null;
        if (restoreBackground && target != null) {
            applyPillButtonBackground(target, 0xFF455A64);
        }
    }

    private void updateRadioSilenceButtonUi() {
        if (btnRadioSilence == null) {
            return;
        }
        boolean enabled = btManager.isRadioSilenceEnabled();
        btnRadioSilence.setBackgroundTintList(null);
        GradientDrawable bg = buildVfoButtonBackground(
                COLOR_PILL_BUTTON_PRIMARY,
                enabled ? COLOR_EDIT_SELECTION_BORDER : 0x00000000,
                enabled ? EDIT_SELECTION_STROKE_DP : 0);
        btnRadioSilence.setBackground(bg);
        btnRadioSilence.setText(enabled
                ? "Long Press for Radio Silence (ACTIVE)"
                : "Long Press for Radio Silence");
    }

    /** Dims the fixed-interval row when Smart Beacon controls the rate. */
    private void applySmartBeaconIntervalGreyState(boolean smartOn) {
        float alpha = smartOn ? 0.38f : 1.0f;
        if (rowBeaconInterval != null) {
            rowBeaconInterval.setAlpha(alpha);
        }
        int labelColor = smartOn ? 0xFF666666 : 0xFFFFFFFF;
        int valueColor = smartOn ? 0xFF6A9EAC : 0xFF00BCD4;
        if (gpsBeaconIntervalLabel != null) {
            gpsBeaconIntervalLabel.setTextColor(labelColor);
        }
        if (beaconIntervalText != null) {
            beaconIntervalText.setTextColor(valueColor);
        }
    }

    private void updateEncryptionStatus() {
        if (encryptionStatusText == null) return;
        boolean encOn = SettingsFragment.isEncryptionEnabled(getMapView().getContext());
        String pass = SettingsFragment.getEncryptionPassphrase(getMapView().getContext());
        if (encOn && pass != null && !pass.isEmpty()) {
            encryptionStatusText.setText("\u2705 AES-256-GCM active");
            encryptionStatusText.setTextColor(0xFF4CAF50);
        } else if (encOn) {
            encryptionStatusText.setText("\u26A0 Enter shared secret to activate");
            encryptionStatusText.setTextColor(0xFFFF9800);
        } else {
            encryptionStatusText.setText("All radios must use the same shared secret");
            encryptionStatusText.setTextColor(0xFF888888);
        }
    }

    private void refreshLogView() {
        if (logText != null && !logLines.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String l : logLines) {
                sb.append(l).append("\n");
            }
            logText.setText(sb.toString());
        }
    }

    private void appendLog(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss",
                Locale.US);
        String line = sdf.format(new Date()) + " " + message;

        logLines.addLast(line);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.removeFirst();
        }

        refreshLogView();
        Log.d(TAG, message);
    }

    private void refreshTxPowerFromRadioAsync() {
        if (radioControlManager == null) {
            return;
        }
        new Thread(() -> {
            int level = radioControlManager.readTxPowerLevel();
            getMapView().post(() -> {
                currentTxPowerLevel = level;
                updateTxPowerButtonUi();
            });
        }, "uvpro-read-tx-power").start();
    }

    private void cycleTxPower() {
        if (radioControlManager == null || !btManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to the radio first.", Toast.LENGTH_SHORT).show();
            return;
        }
        final int next = (currentTxPowerLevel + 1) % 3;
        appendLog("Setting TX power...");
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.setTxPowerLevel(next);
            getMapView().post(() -> {
                appendLog(result.message);
                Toast.makeText(getMapView().getContext(), result.message,
                        result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                refreshTxPowerFromRadioAsync();
            });
        }, "uvpro-set-tx-power").start();
    }

    private void updateTxPowerButtonUi() {
        if (btnTxPower == null) {
            return;
        }
        boolean connected = btManager.isConnected();
        btnTxPower.setEnabled(connected);
        btnTxPower.setText("TX Power "
                + UVProRadioControlManager.txPowerLabel(currentTxPowerLevel));
        btnTxPower.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(COLOR_PILL_BUTTON_PRIMARY));
    }

    private void updateSelectedRepeaterUi() {
        if (selectedRepeaterText == null || btnLoadSelectedRepeater == null) {
            return;
        }
        UVProRadioControlManager.RepeaterSpec spec =
                radioControlManager != null ? radioControlManager.getSelectedRepeater() : null;
        if (spec == null) {
            selectedRepeaterText.setText("None selected");
            btnLoadSelectedRepeater.setEnabled(false);
            setRepeaterLoadArmed(false, null, null);
            return;
        }
        if (repeaterLoadArmed && repeaterLoadArmedUid != null
                && spec.sourceUid != null
                && !repeaterLoadArmedUid.equals(spec.sourceUid)) {
            setRepeaterLoadArmed(false, null, null);
        }
        selectedRepeaterText.setText(String.format(
                Locale.US, "%s (RX %.5f / TX %.5f)",
                spec.name, spec.rxFreqMHz, spec.txFreqMHz));
        btnLoadSelectedRepeater.setEnabled(true);
        updateLoadSelectedRepeaterButtonUi();
    }

    private void refreshChannelGridAsync() {
        refreshChannelGridAsync(false, -1);
    }

    private void refreshChannelGridAsync(int forceRefreshChannelId) {
        refreshChannelGridAsync(false, forceRefreshChannelId);
    }

    private void refreshChannelGridFullAsync() {
        refreshChannelGridAsync(true, -1);
    }

    private void refreshChannelGridAsync(boolean fullSnapshot) {
        refreshChannelGridAsync(fullSnapshot, -1);
    }

    private void refreshChannelGridAsync(boolean fullSnapshot, int forceRefreshChannelId) {
        if (radioControlManager == null || channelsGrid == null) {
            return;
        }
        if (!snapshotReadInFlight.compareAndSet(false, true)) {
            snapshotRefreshPending.set(true);
            if (fullSnapshot) {
                snapshotFullRefreshPending.set(true);
            }
            return;
        }
        new Thread(() -> {
            int targetGroup = currentChannelGroup;
            final int finalTargetGroup = targetGroup;
            UVProRadioControlManager.RadioControlSnapshot snapshot =
                    radioControlManager.readSnapshotForGroup(finalTargetGroup, 30);
            getMapView().post(() -> {
                try {
                    // Snapshot reads are not authoritative for selected group UI state.
                    // Keep button state driven by explicit user/group-sync actions only.
                    renderChannelGrid(snapshot);
                } finally {
                    snapshotReadInFlight.set(false);
                    if (snapshotRefreshPending.compareAndSet(true, false)) {
                        boolean runFull = snapshotFullRefreshPending.getAndSet(false);
                        refreshChannelGridAsync(runFull);
                    }
                }
            });
        }, "uvpro-read-channels").start();
    }

    private void rerenderGridFromLastSnapshot() {
        if (lastSnapshot != null) {
            renderChannelGrid(lastSnapshot);
            return;
        }
        updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                lastDualWatchEnabled, txVfoB, lastHasRxFocus);
    }

    private void renderChannelGrid(UVProRadioControlManager.RadioControlSnapshot snapshot) {
        if (channelsGrid == null) {
            return;
        }

        if (snapshot == null) {
            // Keep last known UI state on transient read failures while connected.
            // This avoids false flips like "dual watch off" when a single poll misses.
            if (btManager.isConnected()) {
                updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                        lastDualWatchEnabled, txVfoB, lastHasRxFocus);
                if (lastSnapshot != null) {
                    updateReceiveRssiUi(lastSnapshot.receiveRssi);
                }
                return;
            }
            channelsGrid.removeAllViews();
            // If actually disconnected, clear to baseline.
            if (switchDualWatch != null) {
                switchDualWatch.setChecked(false);
                switchDualWatch.setEnabled(false);
                switchDualWatch.setText("");
            }
            lastSnapshot = null;
            lastChannelA = -1;
            lastChannelB = -1;
            lastDigitalChannel = -1;
            lastDualWatchEnabled = false;
            lastHasRxFocus = false;
            channelTargetDigital = false;
            selectedTarget = TARGET_A;
            lastAnalogTarget = TARGET_A;
            activeVfoB = false;
            txVfoB = false;
            if (switchDigitalEdit != null) {
                switchDigitalEdit.setChecked(false);
            }
            updateVfoButtons(-1, -1, -1, false, false, false);
            updateReceiveRssiUi(-1);
            return;
        }
        channelsGrid.removeAllViews();
        lastSnapshot = snapshot;

        if (switchDualWatch != null) {
            switchDualWatch.setEnabled(true);
            switchDualWatch.setChecked(snapshot.dualWatchEnabled);
            switchDualWatch.setText("");
        }

        txVfoB = snapshot.dualWatchEnabled && snapshot.activeVfoB;
        if (!snapshot.dualWatchEnabled && selectedTarget == TARGET_B) {
            selectedTarget = TARGET_A;
            channelTargetDigital = false;
            activeVfoB = false;
            lastAnalogTarget = TARGET_A;
        } else if (selectedTarget == TARGET_A) {
            activeVfoB = false;
            channelTargetDigital = false;
            lastAnalogTarget = TARGET_A;
        } else if (selectedTarget == TARGET_B) {
            activeVfoB = true;
            channelTargetDigital = false;
            lastAnalogTarget = TARGET_B;
        } else {
            if (!isDigitalEditArmed()) {
                restoreAnalogEditTarget();
            } else {
                channelTargetDigital = true;
            }
        }
        lastChannelA = snapshot.channelA;
        lastChannelB = snapshot.channelB;
        lastDigitalChannel = snapshot.digitalChannelId;
        lastDualWatchEnabled = snapshot.dualWatchEnabled;
        lastHasRxFocus = snapshot.currentChannelId >= 0;
        updateVfoButtons(snapshot.channelA, snapshot.channelB, snapshot.digitalChannelId,
                snapshot.dualWatchEnabled, txVfoB, snapshot.currentChannelId >= 0);
        updateReceiveRssiUi(snapshot.receiveRssi);

        for (UVProRadioControlManager.ChannelSummary channel : snapshot.channels) {
            if (channel == null) {
                continue;
            }
            Button chip = new Button(getMapView().getContext());
            chip.setAllCaps(false);
            chip.setTextSize(10f);
            chip.setMinHeight(0);
            chip.setMinimumHeight(0);
            chip.setPadding(dip(getMapView().getContext(), 4),
                    dip(getMapView().getContext(), 3),
                    dip(getMapView().getContext(), 4),
                    dip(getMapView().getContext(), 3));

            String name = (channel.name == null || channel.name.isEmpty())
                    ? "--" : channel.name;
            chip.setText(String.format(
                    Locale.US,
                    "%02d %s\n%.5f",
                    displayChannelNumber(channel.channelId),
                    name,
                    channel.rxFreqMHz));

            boolean activeDigital = selectedTarget == TARGET_DIGITAL;
            boolean activeA = selectedTarget == TARGET_A;
            boolean activeB = selectedTarget == TARGET_B && snapshot.dualWatchEnabled;
            boolean isA = channel.channelId == snapshot.channelA;
            // Always show B assignment in the grid (active or subdued),
            // even if B isn't currently the selected control target.
            boolean isB = channel.channelId == snapshot.channelB;
            boolean isDigital = snapshot.digitalChannelId >= 0
                    && channel.channelId == snapshot.digitalChannelId;

            int bgColor = 0xFF3D3D3D;
            // Keep B assignment always visible in green tones.
            if (isB) {
                bgColor = activeB ? COLOR_B_ACTIVE : COLOR_B_SUBDUED;
            }
            if (isA) {
                bgColor = activeA ? COLOR_A_ACTIVE : COLOR_A_SUBDUED;
            }
            if (isDigital) {
                bgColor = activeDigital ? COLOR_DIGITAL_ACTIVE : COLOR_DIGITAL_SUBDUED;
            }
            // If multiple roles map to same channel, keep selected active role dominant,
            // but preserve B (green) when no active override is selected.
            if (isB && !activeA && !activeDigital) {
                bgColor = activeB ? COLOR_B_ACTIVE : COLOR_B_SUBDUED;
            } else if (isA && activeA) {
                bgColor = COLOR_A_ACTIVE;
            } else if (isB) {
                // Keep B assignment green even when Digital shares same slot.
                bgColor = COLOR_B_ACTIVE;
            } else if (isDigital && activeDigital) {
                bgColor = COLOR_DIGITAL_ACTIVE;
            }

            boolean isSelectedEditChannel =
                    (selectedTarget == TARGET_A && isA)
                            || (selectedTarget == TARGET_B && snapshot.dualWatchEnabled && isB);
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setShape(GradientDrawable.RECTANGLE);
            chipBg.setCornerRadius(dip(getMapView().getContext(), 6));
            chipBg.setColor(bgColor);
            chipBg.setStroke(
                    dip(getMapView().getContext(), isSelectedEditChannel ? EDIT_SELECTION_STROKE_DP : 1),
                    isSelectedEditChannel ? COLOR_EDIT_SELECTION_BORDER : 0x55333333);
            chip.setBackground(chipBg);
            chip.setTextColor(0xFFFFFFFF);
            chip.setOnClickListener(v -> applyChannelSelection(channel.channelId));
            chip.setOnLongClickListener(v -> {
                showChannelProgramDialog(channel);
                return true;
            });

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dip(getMapView().getContext(), 2),
                    dip(getMapView().getContext(), 2),
                    dip(getMapView().getContext(), 2),
                    dip(getMapView().getContext(), 2));
            channelsGrid.addView(chip, lp);
        }
    }

    private void applyDualWatch(boolean enabled) {
        if (radioControlManager == null) {
            return;
        }
        appendLog("Updating dual watch...");
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.setDualWatchEnabled(enabled);
            getMapView().post(() -> {
                appendLog(result.message);
                Toast.makeText(getMapView().getContext(),
                        result.message,
                        result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                if (result.success) {
                    lastDualWatchEnabled = enabled;
                    if (!enabled && selectedTarget == TARGET_B) {
                        selectedTarget = TARGET_A;
                        activeVfoB = false;
                        lastAnalogTarget = TARGET_A;
                    }
                    rerenderGridFromLastSnapshot();
                }
            });
        }, "uvpro-dual-watch").start();
    }

    private void applyChannelSelection(int channelId) {
        if (radioControlManager == null) {
            return;
        }
        if (repeaterLoadArmed) {
            loadSelectedRepeaterToRadio(channelId);
            return;
        }
        if (digitalOnlyArmed) {
            applyDigitalOnlyToChannel(channelId);
            return;
        }
        if (channelTargetDigital) {
            appendLog(String.format(Locale.US,
                    "Setting Digital to CH%02d...", displayChannelNumber(channelId)));
            new Thread(() -> {
                UVProRadioControlManager.ProgramResult result =
                        radioControlManager.setDigitalChannel(channelId);
                getMapView().post(() -> {
                    appendLog(result.message);
                    if (result.success) {
                        lastDigitalChannel = channelId;
                        if (switchDigitalEdit != null) {
                            switchDigitalEdit.setChecked(false);
                        }
                        restoreAnalogEditTarget();
                        rerenderGridFromLastSnapshot();
                    } else {
                        Toast.makeText(getMapView().getContext(),
                                result.message, Toast.LENGTH_LONG).show();
                    }
                });
            }, "uvpro-set-digital-channel").start();
            return;
        }
        boolean targetB = activeVfoB;
        appendLog(String.format(Locale.US,
                "Setting %s to CH%02d...",
                targetB ? "VFO-B" : "VFO-A",
                displayChannelNumber(channelId)));
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.setWatchChannel(channelId, targetB);
            getMapView().post(() -> {
                appendLog(result.message);
                if (result.success) {
                    if (targetB) {
                        lastChannelB = channelId;
                    } else {
                        lastChannelA = channelId;
                    }
                    rerenderGridFromLastSnapshot();
                } else {
                    Toast.makeText(getMapView().getContext(),
                            result.message, Toast.LENGTH_LONG).show();
                }
            });
        }, "uvpro-set-channel").start();
    }

    private void setActiveVfo(boolean useVfoB) {
        if (radioControlManager == null) {
            return;
        }
        if (useVfoB && btnVfoB != null && btnVfoB.getVisibility() != View.VISIBLE) {
            return;
        }
        appendLog(useVfoB ? "Switching active side to VFO-B..." : "Switching active side to VFO-A...");
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result = radioControlManager.setActiveVfo(useVfoB);
            getMapView().post(() -> {
                appendLog(result.message);
                if (result.success) {
                    txVfoB = useVfoB;
                    rerenderGridFromLastSnapshot();
                } else {
                    Toast.makeText(getMapView().getContext(),
                            result.message, Toast.LENGTH_LONG).show();
                }
            });
        }, "uvpro-set-active-vfo").start();
    }

    private void applyTxSelection(boolean useVfoB) {
        if (useVfoB && (!lastDualWatchEnabled || (btnVfoB != null && btnVfoB.getVisibility() != View.VISIBLE))) {
            return;
        }
        channelTargetDigital = false;
        selectedTarget = useVfoB ? TARGET_B : TARGET_A;
        lastAnalogTarget = selectedTarget;
        activeVfoB = useVfoB;
        setActiveVfo(useVfoB);
    }

    private void updateVfoButtons(int channelA, int channelB, int digitalChannel,
                                  boolean dualWatchEnabled, boolean txOnB,
                                  boolean hasRxFocus) {
        if (btnVfoA == null) {
            return;
        }
        final int subduedStroke = 0x55777777;
        final boolean activeDigital = selectedTarget == TARGET_DIGITAL;
        boolean activeA = selectedTarget == TARGET_A;
        boolean activeB = selectedTarget == TARGET_B;
        if (!dualWatchEnabled && activeB) {
            activeB = false;
            activeA = true;
        }

        String aText = channelA >= 0
                ? String.format(Locale.US, "A: CH %02d", displayChannelNumber(channelA))
                : "A: CH --";
        btnVfoA.setText(buildVfoLabelWithTxHighlight(aText, !dualWatchEnabled || !txOnB));
        btnVfoA.setSingleLine(true);
        btnVfoA.setMaxLines(1);
        GradientDrawable aBg = buildVfoButtonBackground(
                activeA ? COLOR_A_ACTIVE : COLOR_A_SUBDUED,
                activeA ? COLOR_EDIT_SELECTION_BORDER : subduedStroke,
                activeA ? EDIT_SELECTION_STROKE_DP : 1);
        btnVfoA.setBackground(aBg);
        btnVfoA.setBackgroundTintList(null);
        btnVfoA.setTextColor(0xFFFFFFFF);
        btnVfoA.setAlpha(activeA ? 1.0f : 0.72f);
        Button activeButton = activeA ? btnVfoA : null;
        GradientDrawable activeDrawable = activeA ? aBg : null;

        if (btnVfoB != null) {
            if (dualWatchEnabled) {
                btnVfoB.setVisibility(View.VISIBLE);
                String bText = channelB >= 0
                        ? String.format(Locale.US, "B: CH %02d", displayChannelNumber(channelB))
                        : "B: CH --";
                btnVfoB.setText(buildVfoLabelWithTxHighlight(bText, txOnB));
                btnVfoB.setSingleLine(true);
                btnVfoB.setMaxLines(1);
                GradientDrawable bBg = buildVfoButtonBackground(
                        activeB ? COLOR_B_ACTIVE : COLOR_B_SUBDUED,
                        activeB ? COLOR_EDIT_SELECTION_BORDER : subduedStroke,
                        activeB ? EDIT_SELECTION_STROKE_DP : 1);
                btnVfoB.setBackground(bBg);
                btnVfoB.setBackgroundTintList(null);
                btnVfoB.setTextColor(0xFFFFFFFF);
                btnVfoB.setAlpha(activeB ? 1.0f : 0.72f);
                if (activeB) {
                    activeButton = btnVfoB;
                    activeDrawable = bBg;
                }
            } else {
                btnVfoB.setVisibility(View.GONE);
            }
        }
        if (btnDigital != null) {
            boolean digitalArmed = isDigitalEditArmed();
            String dText = digitalChannel >= 0
                    ? String.format(Locale.US, "Digital CH %02d", digitalChannel + 1)
                    : "Digital";
            btnDigital.setText(dText);
            GradientDrawable dBg = buildVfoButtonBackground(
                    (digitalArmed && activeDigital) ? COLOR_DIGITAL_ACTIVE : COLOR_DIGITAL_SUBDUED,
                    (digitalArmed && activeDigital) ? COLOR_EDIT_SELECTION_BORDER : subduedStroke,
                    (digitalArmed && activeDigital) ? EDIT_SELECTION_STROKE_DP : 1);
            btnDigital.setBackground(dBg);
            btnDigital.setBackgroundTintList(null);
            btnDigital.setTextColor(0xFFFFFFFF);
            btnDigital.setEnabled(digitalArmed);
            btnDigital.setAlpha(digitalArmed ? (activeDigital ? 1.0f : 0.72f) : 0.45f);
        }
        // Keep pulse visible on selected A/B edit target (never Digital),
        // independent of RX-focus so operators always see active control focus.
        updateActiveVfoPulse(activeButton, activeDrawable, !activeDigital);
    }

    private boolean isDigitalEditArmed() {
        return switchDigitalEdit != null && switchDigitalEdit.isChecked();
    }

    private void restoreAnalogEditTarget() {
        if (lastAnalogTarget == TARGET_B && lastDualWatchEnabled) {
            selectedTarget = TARGET_B;
            activeVfoB = true;
        } else {
            selectedTarget = TARGET_A;
            activeVfoB = false;
            lastAnalogTarget = TARGET_A;
        }
        channelTargetDigital = false;
    }

    private void updateDigitalEditGuardUi() {
        if (btnDigital == null) {
            return;
        }
        if (!isDigitalEditArmed() && selectedTarget == TARGET_DIGITAL) {
            restoreAnalogEditTarget();
        }
        updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                lastDualWatchEnabled, txVfoB, lastHasRxFocus);
    }

    private CharSequence buildVfoLabelWithTxHighlight(String base, boolean isTx) {
        if (!isTx) {
            return base;
        }
        // Keep TX as a single replacement span token so it cannot wrap to the next line.
        final String suffix = "TX";
        SpannableStringBuilder sb = new SpannableStringBuilder(base).append(suffix);
        int txStart = sb.length() - 2;
        int txEnd = sb.length();
        sb.setSpan(new TxBadgeSpan(),
                txStart, txEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static class TxBadgeSpan extends ReplacementSpan {
        private static final float TEXT_SCALE = 0.97f; // ~10% smaller than previous 1.08
        private static final float H_PADDING_FACTOR = 0.216f;
        private static final float V_PADDING_FACTOR = 0.108f;
        private static final float LEADING_OFFSET_FACTOR = 0.30f; // visually ~2 spaces to the right
        private static final float CORNER_RADIUS_FACTOR = 0.22f;
        private static final float STROKE_WIDTH_FACTOR = 0.12f;

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            Paint p = new Paint(paint);
            p.setTextSize(p.getTextSize() * TEXT_SCALE);
            String tx = text.subSequence(start, end).toString();
            float textW = p.measureText(tx);
            float hPad = p.getTextSize() * H_PADDING_FACTOR;
            float leadingOffset = p.getTextSize() * LEADING_OFFSET_FACTOR;
            float totalW = leadingOffset + textW + (hPad * 2f);
            if (fm != null) {
                Paint.FontMetricsInt pfm = p.getFontMetricsInt();
                int vPad = (int) (p.getTextSize() * V_PADDING_FACTOR);
                fm.ascent = pfm.ascent - vPad;
                fm.descent = pfm.descent + vPad;
                fm.top = fm.ascent;
                fm.bottom = fm.descent;
            }
            return (int) Math.ceil(totalW);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            Paint p = new Paint(paint);
            p.setAntiAlias(true);
            p.setTextSize(p.getTextSize() * TEXT_SCALE);

            String tx = text.subSequence(start, end).toString();
            float textW = p.measureText(tx);
            float hPad = p.getTextSize() * H_PADDING_FACTOR;
            float vPad = p.getTextSize() * V_PADDING_FACTOR;
            float leadingOffset = p.getTextSize() * LEADING_OFFSET_FACTOR;
            float strokeW = Math.max(1f, p.getTextSize() * STROKE_WIDTH_FACTOR);

            Paint.FontMetrics fm = p.getFontMetrics();
            float txtAscent = y + fm.ascent;
            float txtDescent = y + fm.descent;

            float left = x + leadingOffset;
            float right = left + textW + (hPad * 2f);
            float boxTop = txtAscent - vPad;
            float boxBottom = txtDescent + vPad;
            float radius = p.getTextSize() * CORNER_RADIUS_FACTOR;

            RectF box = new RectF(left, boxTop, right, boxBottom);

            // Red box with white stroke.
            p.setStyle(Paint.Style.FILL);
            p.setColor(COLOR_TX_HIGHLIGHT);
            canvas.drawRoundRect(box, radius, radius, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(strokeW);
            p.setColor(COLOR_TX_STROKE);
            canvas.drawRoundRect(box, radius, radius, p);

            float txX = left + hPad;
            // White stroke around text.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(strokeW);
            p.setColor(COLOR_TX_STROKE);
            canvas.drawText(tx, txX, y, p);
            // Red fill text.
            p.setStyle(Paint.Style.FILL);
            p.setColor(COLOR_TX_HIGHLIGHT);
            canvas.drawText(tx, txX, y, p);
        }
    }

    private GradientDrawable buildVfoButtonBackground(int fillColor, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dip(getMapView().getContext(), PILL_CORNER_RADIUS_DP));
        d.setColor(fillColor);
        d.setStroke(dip(getMapView().getContext(), strokeDp), strokeColor);
        return d;
    }

    private void applyPillButtonBackground(Button button, int fillColor) {
        if (button == null) {
            return;
        }
        button.setBackgroundTintList(null);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dip(button.getContext(), PILL_CORNER_RADIUS_DP));
        d.setColor(fillColor);
        button.setBackground(d);
    }

    private void updateActiveVfoPulse(Button activeButton,
                                      GradientDrawable activeDrawable,
                                      boolean shouldPulse) {
        if (!shouldPulse || activeButton == null || activeDrawable == null) {
            stopActiveVfoPulse();
            return;
        }
        if (activeVfoPulseAnimator != null && pulsingVfoButton == activeButton
                && activeVfoPulseAnimator.isRunning()) {
            return;
        }
        stopActiveVfoPulse();
        pulsingVfoButton = activeButton;
        pulsingVfoDrawable = activeDrawable;
        activeVfoPulseAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                0x66FF9800,
                0xFFFFB74D);
        activeVfoPulseAnimator.setDuration(900L);
        activeVfoPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        activeVfoPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        activeVfoPulseAnimator.addUpdateListener(animation -> {
            if (pulsingVfoDrawable == null) {
                return;
            }
            int color = (Integer) animation.getAnimatedValue();
            pulsingVfoDrawable.setStroke(dip(getMapView().getContext(), EDIT_SELECTION_STROKE_DP), color);
        });
        activeVfoPulseAnimator.start();
    }

    private void stopActiveVfoPulse() {
        if (activeVfoPulseAnimator != null) {
            activeVfoPulseAnimator.cancel();
            activeVfoPulseAnimator = null;
        }
        pulsingVfoButton = null;
        pulsingVfoDrawable = null;
    }

    private void showChannelProgramDialog(UVProRadioControlManager.ChannelSummary channel) {
        if (channel == null) {
            return;
        }
        Context ctx = getMapView().getContext();

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int p = dip(ctx, 12);
        layout.setPadding(p, p, p, p);

        EditText editName = new EditText(ctx);
        editName.setHint("Name (max 10)");
        editName.setSingleLine(true);
        editName.setText(channel.name == null ? "" : channel.name);
        layout.addView(editName);

        EditText editRx = new EditText(ctx);
        editRx.setHint("RX Frequency MHz (e.g. 146.940)");
        editRx.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (channel.rxFreqMHz > 0) {
            editRx.setText(String.format(Locale.US, "%.5f", channel.rxFreqMHz));
        }
        layout.addView(editRx);

        EditText editTx = new EditText(ctx);
        editTx.setHint("TX Frequency MHz");
        editTx.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (channel.txFreqMHz > 0) {
            editTx.setText(String.format(Locale.US, "%.5f", channel.txFreqMHz));
        }
        layout.addView(editTx);

        EditText editTxTone = new EditText(ctx);
        editTxTone.setHint("TX Tone (blank, 100.0, D023)");
        editTxTone.setSingleLine(true);
        String txToneText = formatToneForInput(channel.txTone);
        if (!txToneText.isEmpty()) {
            editTxTone.setText(txToneText);
        }
        layout.addView(editTxTone);

        EditText editRxTone = new EditText(ctx);
        editRxTone.setHint("RX Tone (blank, 100.0, D023)");
        editRxTone.setSingleLine(true);
        String rxToneText = formatToneForInput(channel.rxTone);
        if (!rxToneText.isEmpty()) {
            editRxTone.setText(rxToneText);
        }
        layout.addView(editRxTone);

        EditText editSquelch = new EditText(ctx);
        editSquelch.setHint("Squelch 0-9 (optional)");
        editSquelch.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(editSquelch);

        CheckBox cbScan = new CheckBox(ctx);
        cbScan.setText("Scan enabled");
        cbScan.setChecked(channel.scanEnabled);
        layout.addView(cbScan);

        CheckBox cbMute = new CheckBox(ctx);
        cbMute.setText("Mute channel audio");
        cbMute.setChecked(channel.muted);
        layout.addView(cbMute);

        CheckBox cbHighPower = new CheckBox(ctx);
        cbHighPower.setText("TX high power");
        cbHighPower.setChecked(true);
        layout.addView(cbHighPower);

        CheckBox cbWide = new CheckBox(ctx);
        cbWide.setText("Wide bandwidth");
        cbWide.setChecked(true);
        layout.addView(cbWide);

        new AlertDialog.Builder(ctx)
                .setTitle(String.format(Locale.US, "Program CH%02d",
                        displayChannelNumber(channel.channelId)))
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = editName.getText().toString().trim();
                    String rxStr = editRx.getText().toString().trim();
                    String txStr = editTx.getText().toString().trim();
                    String txToneStr = editTxTone.getText().toString().trim();
                    String rxToneStr = editRxTone.getText().toString().trim();
                    String sqStr = editSquelch.getText().toString().trim();

                    double rx;
                    double tx;
                    try {
                        rx = Double.parseDouble(rxStr);
                        tx = Double.parseDouble(txStr);
                    } catch (Exception e) {
                        Toast.makeText(ctx, "Invalid RX/TX frequency.", Toast.LENGTH_LONG).show();
                        return;
                    }

                    Object txTone = parseToneInput(txToneStr);
                    Object rxTone = parseToneInput(rxToneStr);
                    if (!txToneStr.isEmpty() && txTone == null) {
                        Toast.makeText(ctx, "Invalid TX tone format.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!rxToneStr.isEmpty() && rxTone == null) {
                        Toast.makeText(ctx, "Invalid RX tone format.", Toast.LENGTH_LONG).show();
                        return;
                    }

                    int sq = -1;
                    if (!sqStr.isEmpty()) {
                        try {
                            sq = Integer.parseInt(sqStr);
                        } catch (Exception e) {
                            Toast.makeText(ctx, "Invalid squelch value.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (sq < 0 || sq > 9) {
                            Toast.makeText(ctx, "Squelch must be 0-9.", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }

                    UVProRadioControlManager.ManualChannelSpec spec =
                            new UVProRadioControlManager.ManualChannelSpec(
                                    name,
                                    rx,
                                    tx,
                                    txTone,
                                    rxTone,
                                    cbScan.isChecked(),
                                    cbMute.isChecked(),
                                    cbHighPower.isChecked(),
                                    cbWide.isChecked(),
                                    sq
                            );

                    appendLog(String.format(Locale.US, "Programming CH%02d...",
                            displayChannelNumber(channel.channelId)));
                    new Thread(() -> {
                        UVProRadioControlManager.ProgramResult result =
                                radioControlManager.programManualChannel(channel.channelId, spec);
                        getMapView().post(() -> {
                            appendLog(result.message);
                            Toast.makeText(ctx, result.message,
                                    result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                            if (result.success) {
                                refreshChannelGridAsync(channel.channelId);
                            }
                        });
                    }, "uvpro-program-manual-channel").start();
                })
                .setNegativeButton("Cancel", (DialogInterface dialog, int which) -> { })
                .show();
    }

    private Object parseToneInput(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        if (t.isEmpty() || "none".equalsIgnoreCase(t)) {
            return null;
        }
        if (t.startsWith("D") || t.startsWith("d")) {
            try {
                return Integer.parseInt(t.substring(1).replaceAll("[^0-9]", ""));
            } catch (Exception e) {
                return null;
            }
        }
        try {
            return Double.parseDouble(t.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private String formatToneForInput(Object tone) {
        if (tone == null) {
            return "";
        }
        if (tone instanceof Double) {
            return String.format(Locale.US, "%.1f", (Double) tone);
        }
        if (tone instanceof Integer) {
            int v = (Integer) tone;
            if (v > 0 && v < 1000) {
                return String.format(Locale.US, "D%03d", v);
            }
            return String.valueOf(v);
        }
        return String.valueOf(tone);
    }

    private void onDigitalOnlyModeClick() {
        if (!btManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to the radio first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (digitalOnlyActive) {
            Toast.makeText(getMapView().getContext(),
                    "Long press to disable KISS single-frequency mode.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (digitalOnlyArmed) {
            setDigitalOnlyArmed(false);
            appendLog("Digital only mode cancelled.");
            Toast.makeText(getMapView().getContext(),
                    "Digital only mode cancelled.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (repeaterLoadArmed) {
            setRepeaterLoadArmed(false, null, null);
        }
        setDigitalOnlyArmed(true);
        appendLog("Digital only mode armed. Select a digital channel.");
        Toast.makeText(getMapView().getContext(),
                "Select a channel for digital-only KISS lock.",
                Toast.LENGTH_SHORT).show();
    }

    private void setDigitalOnlyArmed(boolean armed) {
        digitalOnlyArmed = armed;
        updateDigitalOnlyButtonUi();
    }

    private void setDigitalOnlyActive(boolean active, int channelId) {
        digitalOnlyActive = active;
        digitalOnlyChannelId = active ? channelId : -1;
        digitalOnlyArmed = false;
        updateDigitalOnlyButtonUi();
    }

    private void clearDigitalOnlyStateUiOnly() {
        digitalOnlyArmed = false;
        digitalOnlyActive = false;
        digitalOnlyChannelId = -1;
        updateDigitalOnlyButtonUi();
    }

    private void updateDigitalOnlyButtonUi() {
        if (btnDigitalOnlyMode == null) {
            return;
        }
        boolean connected = btManager.isConnected();
        btnDigitalOnlyMode.setEnabled(connected);
        String label;
        if (digitalOnlyActive) {
            label = LABEL_DIGITAL_LONG_PRESS_DISABLE;
        } else if (digitalOnlyArmed) {
            label = LABEL_DIGITAL_SELECT_CHANNEL;
        } else {
            label = LABEL_DIGITAL_ONLY;
        }
        btnDigitalOnlyMode.setText(label);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dip(getMapView().getContext(), PILL_CORNER_RADIUS_DP));
        d.setColor(COLOR_REPEATER_LOAD_FILL);
        if (digitalOnlyArmed) {
            d.setStroke(dip(getMapView().getContext(), 3), COLOR_REPEATER_LOAD_ARMED_STROKE);
        } else {
            d.setStroke(dip(getMapView().getContext(), 1), 0x00000000);
        }
        btnDigitalOnlyMode.setBackgroundTintList(null);
        btnDigitalOnlyMode.setBackground(d);
    }

    private void applyDigitalOnlyToChannel(int channelId) {
        if (radioControlManager == null) {
            appendLog("Radio control unavailable");
            Toast.makeText(getMapView().getContext(),
                    "Radio control unavailable", Toast.LENGTH_SHORT).show();
            setDigitalOnlyArmed(false);
            return;
        }
        final int selectedChannelId = channelId;
        double freqMHz = resolveChannelRxMHz(selectedChannelId);
        setDigitalOnlyArmed(false);
        appendLog(String.format(Locale.US,
                "Digital only: programming CH%02d and KISS lock...",
                displayChannelNumber(selectedChannelId)));
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.setDigitalChannel(selectedChannelId);
            boolean lockOk = false;
            if (result.success && freqMHz > 0) {
                lockOk = KissRadioFrequencyControl.lockFrequency(
                        btManager, (float) freqMHz);
            }
            final boolean finalLockOk = lockOk;
            final double finalFreq = freqMHz;
            getMapView().post(() -> {
                if (result.success && finalLockOk) {
                    lastDigitalChannel = selectedChannelId;
                    setDigitalOnlyActive(true, selectedChannelId);
                    appendLog(String.format(Locale.US,
                            "Digital only active on CH%02d at %.5f MHz (KISS locked).",
                            displayChannelNumber(selectedChannelId), finalFreq));
                    Toast.makeText(getMapView().getContext(),
                            "Digital only mode active. Long press button to disable.",
                            Toast.LENGTH_LONG).show();
                    rerenderGridFromLastSnapshot();
                } else if (result.success) {
                    appendLog("Digital channel set, but KISS frequency lock failed.");
                    Toast.makeText(getMapView().getContext(),
                            "Channel set; KISS lock failed. Check connection.",
                            Toast.LENGTH_LONG).show();
                } else {
                    appendLog(result.message);
                    Toast.makeText(getMapView().getContext(),
                            result.message, Toast.LENGTH_LONG).show();
                }
            });
        }, "uvpro-digital-only").start();
    }

    private double resolveChannelRxMHz(int channelId) {
        if (lastSnapshot != null && lastSnapshot.channels != null
                && channelId >= 0 && channelId < lastSnapshot.channels.length) {
            UVProRadioControlManager.ChannelSummary ch = lastSnapshot.channels[channelId];
            if (ch != null) {
                if (ch.rxFreqMHz > 0) {
                    return ch.rxFreqMHz;
                }
                if (ch.txFreqMHz > 0) {
                    return ch.txFreqMHz;
                }
            }
        }
        return 0.0;
    }

    private void disableDigitalOnlyMode() {
        new Thread(() -> {
            boolean unlocked = KissRadioFrequencyControl.unlockFrequency(btManager);
            getMapView().post(() -> {
                clearDigitalOnlyStateUiOnly();
                if (unlocked) {
                    appendLog("Digital only / KISS frequency lock disabled.");
                    Toast.makeText(getMapView().getContext(),
                            "KISS single-frequency mode disabled.",
                            Toast.LENGTH_SHORT).show();
                } else {
                    appendLog("KISS unlock failed or radio not connected.");
                    Toast.makeText(getMapView().getContext(),
                            "Could not send KISS unlock.", Toast.LENGTH_SHORT).show();
                }
            });
        }, "uvpro-digital-only-off").start();
    }

    /** Called from {@link BtConnectionManager} before the BT socket is closed. */
    private void releaseDigitalOnlyKissLockSilent() {
        if (!digitalOnlyActive && !KissRadioFrequencyControl.isFrequencyLocked()) {
            return;
        }
        KissRadioFrequencyControl.unlockFrequency(btManager);
        digitalOnlyActive = false;
        digitalOnlyArmed = false;
        digitalOnlyChannelId = -1;
    }

    private void armSelectedRepeaterLoad() {
        if (radioControlManager == null) {
            appendLog("Radio control unavailable");
            Toast.makeText(getMapView().getContext(),
                    "Radio control unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        if (digitalOnlyArmed) {
            setDigitalOnlyArmed(false);
        }
        if (repeaterLoadArmed) {
            setRepeaterLoadArmed(false, null, null);
            appendLog("Repeater load cancelled.");
            Toast.makeText(getMapView().getContext(),
                    "Repeater load cancelled.", Toast.LENGTH_SHORT).show();
            return;
        }
        UVProRadioControlManager.RepeaterSpec spec = radioControlManager.getSelectedRepeater();
        if (spec == null) {
            appendLog("Select a repeater marker first.");
            Toast.makeText(getMapView().getContext(),
                    "Select a repeater marker first.", Toast.LENGTH_SHORT).show();
            setRepeaterLoadArmed(false, null, null);
            return;
        }
        setRepeaterLoadArmed(true, spec.sourceUid, spec);
        appendLog("Repeater load armed. Select destination channel.");
    }

    private void setRepeaterLoadArmed(boolean armed, String sourceUid,
                                      UVProRadioControlManager.RepeaterSpec spec) {
        repeaterLoadArmed = armed;
        repeaterLoadArmedUid = armed ? sourceUid : null;
        repeaterLoadArmedSpec = armed ? spec : null;
        updateLoadSelectedRepeaterButtonUi();
    }

    private void updateLoadSelectedRepeaterButtonUi() {
        if (btnLoadSelectedRepeater == null) {
            return;
        }
        btnLoadSelectedRepeater.setText(
                repeaterLoadArmed ? LABEL_SELECT_CHANNEL : LABEL_LOAD_REPEATER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dip(getMapView().getContext(), PILL_CORNER_RADIUS_DP));
        d.setColor(COLOR_REPEATER_LOAD_FILL);
        if (repeaterLoadArmed) {
            d.setStroke(dip(getMapView().getContext(), 3), COLOR_REPEATER_LOAD_ARMED_STROKE);
        } else {
            d.setStroke(dip(getMapView().getContext(), 1), 0x00000000);
        }
        btnLoadSelectedRepeater.setBackgroundTintList(null);
        btnLoadSelectedRepeater.setBackground(d);
    }

    private void loadSelectedRepeaterToRadio(int channelId) {
        if (radioControlManager == null) {
            appendLog("Radio control unavailable");
            Toast.makeText(getMapView().getContext(),
                    "Radio control unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        final UVProRadioControlManager.RepeaterSpec armedSpec = repeaterLoadArmedSpec;
        setRepeaterLoadArmed(false, null, null);
        if (armedSpec == null) {
            appendLog("No repeater selected to load.");
            Toast.makeText(getMapView().getContext(),
                    "No repeater selected to load.", Toast.LENGTH_SHORT).show();
            return;
        }
        appendLog("Preparing repeater load...");
        new Thread(() -> {
            if (!btManager.isConnected()) {
                appendLog("Radio not connected in plugin; attempting auto-connect...");
                btManager.connectToLastDevice();
                long startMs = System.currentTimeMillis();
                while (!btManager.isConnected()
                        && (System.currentTimeMillis() - startMs) < 8000L) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (!btManager.isConnected()) {
                getMapView().post(() -> {
                    appendLog("Could not connect to radio. Use Scan & Connect, then retry.");
                    Toast.makeText(getMapView().getContext(),
                            "Radio not connected. Tap Scan & Connect.",
                            Toast.LENGTH_LONG).show();
                });
                return;
            }

            final int selectedChannelId = channelId;
            getMapView().post(() -> appendLog(String.format(
                    Locale.US, "Loading selected repeater to CH%02d...",
                    displayChannelNumber(selectedChannelId))));
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.programRepeaterAndTune(armedSpec, selectedChannelId);
            getMapView().post(() -> {
                appendLog(result.message);
                Toast.makeText(getMapView().getContext(),
                        result.message,
                        result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                if (result.success) {
                    refreshChannelGridFullAsync();
                    // Some radios apply write+tune in two phases; re-read once more shortly after.
                    rootView.postDelayed(this::refreshChannelGridFullAsync, 650L);
                }
            });
        }, "uvpro-load-repeater").start();
    }

    private void scheduleScrollToRepeaterLoadSection() {
        // Dropdown/layout animation can delay child measurements; run multiple passes.
        getMapView().postDelayed(this::scrollToRepeaterLoadSection, 120L);
        getMapView().postDelayed(this::scrollToRepeaterLoadSection, 320L);
        getMapView().postDelayed(this::scrollToRepeaterLoadSection, 700L);
        getMapView().postDelayed(this::scrollToRepeaterLoadSection, 1200L);
    }

    private void scrollToRepeaterLoadSection() {
        if (!(rootView instanceof ScrollView)) {
            return;
        }
        ScrollView scroll = (ScrollView) rootView;
        scroll.post(() -> {
            View target = btnLoadSelectedRepeater != null ? btnLoadSelectedRepeater : channelsGrid;
            if (target == null) {
                return;
            }
            // Convert nested child position to ScrollView content coordinates.
            int y = 0;
            View cursor = target;
            while (cursor != null && cursor != scroll) {
                y += cursor.getTop();
                android.view.ViewParent p = cursor.getParent();
                cursor = (p instanceof View) ? (View) p : null;
            }
            // Keep a bit of headroom so "Selected Repeater" and the button are in view.
            y = Math.max(0, y - dip(getMapView().getContext(), 64));
            final int scrollY = y;
            scroll.scrollTo(0, scrollY);
            scroll.post(() -> scroll.smoothScrollTo(0, scrollY));
            pulseRepeaterLoadButton();
        });
    }

    private void pulseRepeaterLoadButton() {
        if (btnLoadSelectedRepeater == null) {
            return;
        }
        if (repeaterLoadFocusAnimator != null) {
            repeaterLoadFocusAnimator.cancel();
            repeaterLoadFocusAnimator = null;
        }
        btnLoadSelectedRepeater.setAlpha(1f);
        repeaterLoadFocusAnimator = ObjectAnimator.ofFloat(
                btnLoadSelectedRepeater, "alpha",
                1f, 0.50f, 1f);
        repeaterLoadFocusAnimator.setDuration(320L);
        repeaterLoadFocusAnimator.setRepeatCount(2);
        repeaterLoadFocusAnimator.start();
    }

    // --- Actions ---

    private void showDevicePicker() {
        if (foundDevices.isEmpty()) {
            stopScanConnectButtonPulse(true);
            appendLog("No UV-PRO radios found");
            return;
        }

        Context ctx = getMapView().getContext();

        if (foundDevices.size() == 1) {
            stopScanConnectButtonPulse(true);
            BluetoothDevice device = foundDevices.get(0);
            String name = resolveDeviceDisplayName(ctx, device);
            appendLog("Connecting to " + name + "...");
            btManager.connect(device);
            return;
        }

        // Multiple devices — show picker with live green/gray availability dots.
        // Dialog appears instantly; dots update as background probes respond.
        final int count = foundDevices.size();
        final int[] dotColors = new int[count];
        final String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = resolveDeviceDisplayName(ctx, foundDevices.get(i));
            dotColors[i] = 0xFF888888; // gray = unknown
        }

        // Custom adapter that renders "● Name" with a colored dot
        android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<String>(ctx,
                        android.R.layout.simple_list_item_1, names) {
                    @Override
                    public android.view.View getView(int pos, android.view.View cv,
                            android.view.ViewGroup parent) {
                        TextView tv = (TextView) super.getView(pos, cv, parent);
                        android.text.SpannableStringBuilder sb =
                                new android.text.SpannableStringBuilder(
                                        "\u25CF  " + names[pos]);
                        sb.setSpan(
                                new android.text.style.ForegroundColorSpan(dotColors[pos]),
                                0, 1,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        tv.setText(sb);
                        tv.setTextColor(0xFFFFFFFF);
                        tv.setTextSize(16);
                        return tv;
                    }
                };

        try {
            new AlertDialog.Builder(ctx)
                    .setTitle("Select Radio")
                    .setAdapter(adapter, (dialog, which) -> {
                        BluetoothDevice selected = foundDevices.get(which);
                        appendLog("Connecting to " + names[which] + "...");
                        btManager.connect(selected);
                    })
                    .setNegativeButton("Cancel", (d, w) -> btManager.clearProbeSockets())
                    .setOnCancelListener(d -> btManager.clearProbeSockets())
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing device picker", e);
            appendLog("Error showing device picker");
            return;
        }

        // Background probes — update dot color when each device responds
        btManager.clearProbeSockets();
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null && btAdapter.isDiscovering()) btAdapter.cancelDiscovery();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            final BluetoothDevice device = foundDevices.get(i);
            new Thread(() -> {
                android.bluetooth.BluetoothSocket socket = null;
                try {
                    socket = device.createRfcommSocketToServiceRecord(
                            java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
                    socket.connect();
                    btManager.addProbeSocket(device.getAddress(), socket);
                    getMapView().post(() -> {
                        dotColors[idx] = 0xFF00CC44; // green = available
                        adapter.notifyDataSetChanged();
                    });
                } catch (Exception e) {
                    if (socket != null) try { socket.close(); } catch (Exception ignored) {}
                    getMapView().post(() -> {
                        dotColors[idx] = 0xFF884444; // dim red = unavailable
                        adapter.notifyDataSetChanged();
                    });
                }
            }, "bt-probe-" + device.getAddress()).start();
        }
    }

    /** Returns the user-assigned name for a device if one exists, otherwise the broadcast name. */
    private String resolveDeviceDisplayName(Context ctx, BluetoothDevice device) {
        try {
            BluetoothDeviceRegistry.BtDeviceRecord r =
                    BluetoothDeviceRegistry.find(ctx, device.getAddress());
            if (r != null) {
                return BluetoothDeviceRegistry.getDisplayTitle(r);
            }
        } catch (Exception ignored) {
        }
        String n = device.getName();
        return n != null ? n : device.getAddress();
    }

    private boolean isLikelyMeshNamedDevice(BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        String name = null;
        try {
            name = device.getName();
        } catch (Exception ignored) {
        }
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.US);
        for (String hint : MESH_DEVICE_NAME_HINTS) {
            if (n.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLikelyMeshRecord(BtDeviceRecord record) {
        if (record == null) {
            return false;
        }
        String[] candidates = new String[]{
                record.customName,
                record.lastSystemName,
                BluetoothDeviceRegistry.getDisplayTitle(record)
        };
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String n = candidate.toLowerCase(Locale.US);
            for (String hint : MESH_DEVICE_NAME_HINTS) {
                if (n.contains(hint)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void requestScanConnectButtonPulse() {
        if (getMapView() == null) {
            startScanConnectButtonPulse();
            return;
        }
        // Start after press-state is released so the pulse remains visible until picker display.
        getMapView().removeCallbacks(deferredScanConnectPulseStart);
        getMapView().postDelayed(deferredScanConnectPulseStart, 60L);
    }

    private void startMeshConnectButtonPulse() {
        // Keep MeshCore connect button stable (no flashing animation) during connect attempts.
        stopMeshConnectButtonPulse(true);
    }

    private void startScanConnectButtonPulse() {
        if (btnScan == null) {
            return;
        }
        stopScanConnectButtonPulse(false);
        btnScan.setBackgroundTintList(null);
        scanConnectPulseDrawable = buildVfoButtonBackground(
                COLOR_PILL_BUTTON_PRIMARY, 0x00FFEB3B, EDIT_SELECTION_STROKE_DP);
        btnScan.setBackground(scanConnectPulseDrawable);
        scanConnectPulseAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                0x11FFEB3B,
                0xFFFFEB3B);
        scanConnectPulseAnimator.setDuration(260L);
        scanConnectPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        scanConnectPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanConnectPulseAnimator.addUpdateListener(animation -> {
            if (scanConnectPulseDrawable == null || btnScan == null) {
                return;
            }
            int color = (Integer) animation.getAnimatedValue();
            scanConnectPulseDrawable.setStroke(
                    dip(getMapView().getContext(), EDIT_SELECTION_STROKE_DP), color);
            btnScan.invalidate();
        });
        scanConnectPulseAnimator.start();
    }

    private void stopScanConnectButtonPulse(boolean restoreBackground) {
        if (getMapView() != null) {
            getMapView().removeCallbacks(deferredScanConnectPulseStart);
        }
        ValueAnimator animator = scanConnectPulseAnimator;
        scanConnectPulseAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
        scanConnectPulseDrawable = null;
        if (restoreBackground && btnScan != null) {
            applyPillButtonBackground(btnScan, COLOR_PILL_BUTTON_PRIMARY);
        }
    }

    private void stopMeshConnectButtonPulse(boolean restoreBackground) {
        ValueAnimator animator = meshConnectPulseAnimator;
        meshConnectPulseAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
        meshConnectPulseDrawable = null;
        if (restoreBackground && btnMeshScan != null) {
            applyPillButtonBackground(btnMeshScan, 0xFF455A64);
        }
    }

    private void showSettingsDialog() {
        Context ctx = getMapView().getContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        // Build a custom dialog with EditTexts for key settings
        android.widget.ScrollView scrollView = new android.widget.ScrollView(ctx);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(ctx);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);

        // Bluetooth Devices — manage history, favorites, rename/delete
        TextView labelBluetooth = new TextView(ctx);
        labelBluetooth.setText("Bluetooth Radio");
        labelBluetooth.setTextColor(0xFFFFFFFF);
        labelBluetooth.setTextSize(16);
        layout.addView(labelBluetooth);

        android.widget.Button btnBluetoothDevices = new android.widget.Button(ctx);
        btnBluetoothDevices.setText("Manage Bluetooth Devices");
        btnBluetoothDevices.setTextColor(0xFFFFFFFF);
        btnBluetoothDevices.setTextSize(13f);
        applyPillButtonBackground(btnBluetoothDevices, 0xFF455A64);
        btnBluetoothDevices.setOnClickListener(v ->
                com.uvpro.plugin.ui.BluetoothDevicesManagement.show(ctx, () ->
                        getMapView().post(() -> {
                            refreshFavoriteStrip();
                            updateScanButtonText();
                            updateMeshScanButtonText();
                        })));
        layout.addView(btnBluetoothDevices);

        SettingsFragment.AprsSettingsUi aprsUi =
                SettingsFragment.appendAprsSettingsSection(ctx, pluginContext, layout);

        TextView headerBeacon = new TextView(ctx);
        headerBeacon.setText("\nBeacon");
        headerBeacon.setTextColor(0xFF00BCD4);
        headerBeacon.setTextSize(14);
        headerBeacon.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        layout.addView(headerBeacon);

        // Beacon interval field (greyed out when smart beacon is on — toggle is on main panel)
        boolean smartBeaconOn = SmartBeacon.isEnabled(ctx);
        TextView labelBeacon = new TextView(ctx);
        labelBeacon.setText("GPS Beacon Interval (seconds)");
        labelBeacon.setTextColor(smartBeaconOn ? 0xFF666666 : 0xFFAAAAAA);
        layout.addView(labelBeacon);
        EditText editBeacon = new EditText(ctx);
        editBeacon.setText(prefs.getString(SettingsFragment.PREF_BEACON_INTERVAL,
                SettingsFragment.DEFAULT_BEACON_INTERVAL));
        editBeacon.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editBeacon.setEnabled(!smartBeaconOn);
        editBeacon.setAlpha(smartBeaconOn ? 0.35f : 1.0f);
        layout.addView(editBeacon);

        // Section header
        TextView headerMessaging = new TextView(ctx);
        headerMessaging.setText("Send messages or other ATAK data options");
        headerMessaging.setTextColor(0xFFFFFFFF);
        headerMessaging.setTextSize(15);
        headerMessaging.setPadding(0, 28, 0, 4);
        layout.addView(headerMessaging);

        // Retry interval field
        TextView labelRetryInterval = new TextView(ctx);
        labelRetryInterval.setText("\nRetry Interval (minutes) — wait before retransmitting");
        labelRetryInterval.setTextColor(0xFFAAAAAA);
        layout.addView(labelRetryInterval);
        EditText editRetryInterval = new EditText(ctx);
        editRetryInterval.setText(prefs.getString(SettingsFragment.PREF_RETRY_INTERVAL_MIN,
                SettingsFragment.DEFAULT_RETRY_INTERVAL_MIN));
        editRetryInterval.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(editRetryInterval);

        // Max retries field
        TextView labelRetryMax = new TextView(ctx);
        labelRetryMax.setText("\nMax Retries — attempts before declaring failure");
        labelRetryMax.setTextColor(0xFFAAAAAA);
        layout.addView(labelRetryMax);
        EditText editRetryMax = new EditText(ctx);
        editRetryMax.setText(prefs.getString(SettingsFragment.PREF_RETRY_MAX,
                SettingsFragment.DEFAULT_RETRY_MAX));
        editRetryMax.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(editRetryMax);

        // Ping Reply
        LinearLayout rowPingReply = new LinearLayout(ctx);
        rowPingReply.setOrientation(LinearLayout.HORIZONTAL);
        rowPingReply.setGravity(android.view.Gravity.CENTER_VERTICAL);
        rowPingReply.setPadding(0, 20, 0, 0);

        TextView labelPingReply = new TextView(ctx);
        labelPingReply.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelPingReply.setText("Send Ping Reply");
        labelPingReply.setTextColor(0xFFFFFFFF);
        labelPingReply.setTextSize(13);
        rowPingReply.addView(labelPingReply);

        Switch switchPingReply = new Switch(ctx);
        switchPingReply.setChecked(SettingsFragment.isPingReplyEnabled(ctx));
        rowPingReply.addView(switchPingReply);
        layout.addView(rowPingReply);

        TextView hintPingReply = new TextView(ctx);
        hintPingReply.setText("Automatically reply to incoming pings with your position.");
        hintPingReply.setTextColor(0xFF888888);
        hintPingReply.setTextSize(11);
        hintPingReply.setPadding(0, 2, 0, 0);
        layout.addView(hintPingReply);

        TextView headerSaRelay = new TextView(ctx);
        headerSaRelay.setText("\nSA Relay");
        headerSaRelay.setTextColor(0xFF00BCD4);
        headerSaRelay.setTextSize(14);
        headerSaRelay.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        layout.addView(headerSaRelay);
        Switch switchSaRelay = new Switch(ctx);
        switchSaRelay.setText("Re-broadcast TAK network positions over radio");
        switchSaRelay.setTextColor(0xFFCCCCCC);
        switchSaRelay.setChecked(SettingsFragment.isSaRelayEnabled(ctx));
        layout.addView(switchSaRelay);

        Switch switchRfToTakUplink = new Switch(ctx);
        switchRfToTakUplink.setText("RF -> TAK Uplink Relay");
        switchRfToTakUplink.setTextColor(0xFFCCCCCC);
        switchRfToTakUplink.setChecked(SettingsFragment.isRfToTakUplinkEnabled(ctx));
        switchRfToTakUplink.setEnabled(switchSaRelay.isChecked());
        layout.addView(switchRfToTakUplink);
        switchSaRelay.setOnCheckedChangeListener((buttonView, isChecked) ->
                switchRfToTakUplink.setEnabled(isChecked));

        TextView hintSaRelay = new TextView(ctx);
        hintSaRelay.setText(
                "Throttled: one update per contact per 30 s. Requires TAK server + radio connected.");
        hintSaRelay.setTextColor(0xFF888888);
        hintSaRelay.setTextSize(12);
        layout.addView(hintSaRelay);

        TextView hintRfToTakUplink = new TextView(ctx);
        hintRfToTakUplink.setText(
                "Forwards RF CoT to TAK network. Active only when SA Relay is enabled.");
        hintRfToTakUplink.setTextColor(0xFF888888);
        hintRfToTakUplink.setTextSize(12);
        layout.addView(hintRfToTakUplink);

        Switch switchRestrictReachableChat = new Switch(ctx);
        switchRestrictReachableChat.setText("Restrict GeoChat to reachable peers");
        switchRestrictReachableChat.setTextColor(0xFFCCCCCC);
        switchRestrictReachableChat.setChecked(
                SettingsFragment.isRestrictChatToReachablePeers(ctx));
        layout.addView(switchRestrictReachableChat);

        TextView hintRestrictReachableChat = new TextView(ctx);
        hintRestrictReachableChat.setText(
                "Map positions stay visible; GeoChat is disabled for peers not on your WiFi or radio path.");
        hintRestrictReachableChat.setTextColor(0xFF888888);
        hintRestrictReachableChat.setTextSize(12);
        layout.addView(hintRestrictReachableChat);

        // Team color is controlled by ATAK core settings (locationTeam). Plugin no longer overrides it.

        SettingsFragment.AdministrationUi adminUi =
                SettingsFragment.appendAdministrationSection(ctx, layout);

        scrollView.addView(layout);

        new AlertDialog.Builder(ctx)
                .setTitle("UV-PRO Settings")
                .setView(scrollView)
                .setPositiveButton("Save", (dialog, which) -> {
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putBoolean(SettingsFragment.PREF_SA_RELAY_ENABLED,
                            switchSaRelay.isChecked());
                    editor.putBoolean(SettingsFragment.PREF_RF_TO_TAK_UPLINK_ENABLED,
                            switchRfToTakUplink.isChecked());
                    editor.putBoolean(SettingsFragment.PREF_RESTRICT_CHAT_TO_REACHABLE_PEERS,
                            switchRestrictReachableChat.isChecked());

                    editor.putBoolean(SettingsFragment.PREF_PING_REPLY_ENABLED,
                            switchPingReply.isChecked());

                    String newBeacon = editBeacon.getText().toString().trim();
                    if (!newBeacon.isEmpty()) {
                        editor.putString(SettingsFragment.PREF_BEACON_INTERVAL, newBeacon);
                        if (beaconIntervalText != null)
                            beaconIntervalText.setText(newBeacon + "s");
                    }

                    String newRetryInterval = editRetryInterval.getText().toString().trim();
                    if (!newRetryInterval.isEmpty()) {
                        editor.putString(SettingsFragment.PREF_RETRY_INTERVAL_MIN, newRetryInterval);
                    }

                    String newRetryMax = editRetryMax.getText().toString().trim();
                    if (!newRetryMax.isEmpty()) {
                        editor.putString(SettingsFragment.PREF_RETRY_MAX, newRetryMax);
                    }

                    SettingsFragment.saveAdministrationFromUi(ctx, adminUi);
                    SettingsFragment.refreshAdministrationStatus(ctx, adminUi);
                    SettingsFragment.saveAprsSettingsFromUi(ctx, aprsUi);

                    editor.apply();
                    appendLog("Settings saved");
                    appendLog("SA Relay " + (switchSaRelay.isChecked() ? "enabled" : "disabled"));
                    appendLog("RF -> TAK Uplink "
                            + (switchRfToTakUplink.isChecked() ? "enabled" : "disabled"));
                    appendLog("Restrict reachable GeoChat "
                            + (switchRestrictReachableChat.isChecked() ? "enabled" : "disabled"));
                    if (rootView != null) {
                        getMapView().post(() -> {
                            updateStatusFields();
                            updateAprsSectionUi();
                        });
                    }
                    try {
                        AtakBroadcast.getInstance().sendBroadcast(
                                new Intent(UVProMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendManualBeacon() {
        BtConnectionManager activeTx = resolveActiveTransmitManager();
        appendLog("Beacon TX route: mode=" + (meshTransmitEnabled ? "mesh" : "uvpro")
                + " meshConnected=" + (meshBtManager != null && meshBtManager.isConnected())
                + " uvproConnected=" + (btManager != null && btManager.isConnected())
                + " wifiEnabled=" + wifiTransmitEnabled
                + " wifiConnected=" + isWifiConnected());
        if (cotBridge != null && activeTx != null && activeTx.isConnected()) {
            Context ctx = getMapView().getContext();
            if (SettingsFragment.isAprsDisableAtakTraffic(ctx)) {
                appendLog("OPENRL beacon skipped (Disable ATAK traffic)");
                return;
            }
            // Get self location from ATAK
            com.atakmap.android.maps.MapItem self =
                    getMapView().getSelfMarker();
            if (self != null && self instanceof com.atakmap.android.maps.PointMapItem) {
                com.atakmap.coremap.maps.coords.GeoPoint gp =
                        ((com.atakmap.android.maps.PointMapItem) self).getPoint();
                cotBridge.sendPositionOverRadio(
                        gp.getLatitude(), gp.getLongitude(),
                        gp.getAltitude(), 0, 0, -1);
                appendLog("Beacon sent over " + (activeTx == meshBtManager ? "MeshCore" : "UV-PRO"));
            } else {
                appendLog("No self-location available");
            }
        } else {
            appendLog("Not connected");
        }
    }

    private void wireAprsSection() {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (btnAprsBeaconArm != null) {
            btnAprsBeaconArm.setOnClickListener(v ->
                    Toast.makeText(ctx, "Long press to enable/disable periodic APRS beacons.",
                            Toast.LENGTH_SHORT).show());
            btnAprsBeaconArm.setOnLongClickListener(v -> {
                if (ctx == null) {
                    return true;
                }
                boolean enabled = SettingsFragment.isAprsTxArmed(ctx);
                if (!enabled && !SettingsFragment.isValidAprsCallsign(
                        SettingsFragment.getAprsCallsign(ctx))) {
                    Toast.makeText(ctx,
                            "Set a valid APRS callsign in Edit APRS Settings first.",
                            Toast.LENGTH_LONG).show();
                    return true;
                }
                boolean nextEnabled = !enabled;
                SettingsFragment.setAprsTxArmed(ctx, nextEnabled);
                if (!nextEnabled && SettingsFragment.isAprsDisableAtakTraffic(ctx)) {
                    SettingsFragment.setAprsDisableAtakTraffic(ctx, false);
                }
                updateAprsSectionUi();
                appendLog(nextEnabled
                        ? "Periodic APRS beaconing enabled"
                        : "Periodic APRS beaconing disabled");
                return true;
            });
        }
        if (switchAprsDisableAtak != null && ctx != null) {
            switchAprsDisableAtak.setOnCheckedChangeListener((buttonView, isChecked) ->
                    SettingsFragment.setAprsDisableAtakTraffic(ctx, isChecked));
        }
        if (btnSendAprsBeacon != null) {
            btnSendAprsBeacon.setOnClickListener(v -> sendManualAprsBeacon());
        }
        if (btnEditAprsSettings != null) {
            btnEditAprsSettings.setOnClickListener(v -> showSettingsDialog());
        }
        updateAprsSectionUi();
    }

    private void updateAprsSectionUi() {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        if (textAprsStatusCall != null) {
            textAprsStatusCall.setText("Call: " + SettingsFragment.formatAprsDisplayCall(ctx));
        }
        if (textAprsStatusMessage != null) {
            String msg = SettingsFragment.getAprsMessage(ctx);
            if (msg != null) {
                msg = msg.trim();
            }
            if (msg == null || msg.isEmpty()) {
                textAprsStatusMessage.setText("APRS Message: No Message");
            } else {
                textAprsStatusMessage.setText("APRS Message: " + msg);
            }
        }
        if (imageAprsStatusIcon != null && textAprsStatusIcon != null) {
            if (!com.uvpro.plugin.aprs.AprsIconPreviewLoader.isIconSelected(ctx)) {
                imageAprsStatusIcon.setVisibility(android.view.View.GONE);
                imageAprsStatusIcon.setImageDrawable(null);
                textAprsStatusIcon.setVisibility(android.view.View.VISIBLE);
                textAprsStatusIcon.setText("(not set)");
            } else {
                android.graphics.Bitmap bmp =
                        com.uvpro.plugin.aprs.AprsIconPreviewLoader.loadSelectedIconBitmap(
                                ctx, pluginContext);
                if (bmp != null) {
                    imageAprsStatusIcon.setImageBitmap(bmp);
                    imageAprsStatusIcon.setVisibility(android.view.View.VISIBLE);
                    textAprsStatusIcon.setVisibility(android.view.View.GONE);
                } else {
                    imageAprsStatusIcon.setVisibility(android.view.View.GONE);
                    imageAprsStatusIcon.setImageDrawable(null);
                    textAprsStatusIcon.setVisibility(android.view.View.VISIBLE);
                    textAprsStatusIcon.setText("(not set)");
                }
            }
        }
        if (switchAprsDisableAtak != null) {
            boolean aprsEnabled = SettingsFragment.isAprsTxArmed(ctx)
                    && SettingsFragment.isValidAprsCallsign(
                    SettingsFragment.getAprsCallsign(ctx));
            boolean disableAtak = SettingsFragment.isAprsDisableAtakTraffic(ctx);
            if (!aprsEnabled && disableAtak) {
                // Without a valid FCC callsign APRS TX is unavailable; do not leave ATAK TX disabled.
                SettingsFragment.setAprsDisableAtakTraffic(ctx, false);
                disableAtak = false;
            }
            switchAprsDisableAtak.setOnCheckedChangeListener(null);
            switchAprsDisableAtak.setChecked(disableAtak);
            switchAprsDisableAtak.setOnCheckedChangeListener((buttonView, isChecked) ->
                    SettingsFragment.setAprsDisableAtakTraffic(ctx, isChecked));
        }
        if (btnAprsBeaconArm != null) {
            boolean enabled = SettingsFragment.isAprsTxArmed(ctx);
            btnAprsBeaconArm.setBackgroundTintList(null);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(24f);
            bg.setColor(0xFF455A64);
            if (enabled) {
                bg.setStroke(4, 0xFFFF9800);
            }
            btnAprsBeaconArm.setBackground(bg);
            btnAprsBeaconArm.setText(enabled
                    ? "Long Press for APRS Beacon (ENABLED)"
                    : "Long Press for APRS Beacon");
        }
    }

    private void sendManualAprsBeacon() {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        if (!SettingsFragment.isValidAprsCallsign(SettingsFragment.getAprsCallsign(ctx))) {
            Toast.makeText(ctx,
                    "Set a valid APRS callsign in Edit APRS Settings first.",
                    Toast.LENGTH_LONG).show();
            appendLog("APRS beacon blocked (invalid FCC callsign)");
            return;
        }
        if (btManager == null || !btManager.isConnected()) {
            appendLog("Not connected");
            return;
        }
        if (com.uvpro.plugin.aprs.AprsOutboundTransmitter
                .sendPositionBeacon(ctx, btManager, false)) {
            appendLog("APRS beacon sent");
        } else {
            appendLog("APRS beacon failed (OPENRL active / callsign / icon / location / silence)");
        }
    }

    private void sendPing() {
        BtConnectionManager activeTx = resolveActiveTransmitManager();
        appendLog("Ping TX route: mode=" + (meshTransmitEnabled ? "mesh" : "uvpro")
                + " wifiEnabled=" + wifiTransmitEnabled
                + " wifiConnected=" + isWifiConnected());
        if (cotBridge != null && activeTx != null && activeTx.isConnected()) {
            String callsign = MapView.getMapView().getSelfMarker().getMetaString("callsign","UNKNOWN");
            try {
                com.uvpro.plugin.protocol.UVProPacket packet =
                        com.uvpro.plugin.protocol.UVProPacket
                                .createPingPacket(com.uvpro.plugin.util.CallsignUtil.toRadioCallsign(callsign));
                byte[] packetBytes = packet.encode();
                if (encryptionManager != null && encryptionManager.isEnabled()) {
                    packetBytes = encryptionManager.encrypt(packetBytes);
                    if (packetBytes == null) {
                        appendLog("Ping encryption failed");
                        return;
                    }
                }
                com.uvpro.plugin.ax25.Ax25Frame frame =
                        com.uvpro.plugin.ax25.Ax25Frame
                                .createUVProFrame(callsign, 0, packetBytes);
                byte[] ax25 = frame.encode();
                activeTx.sendKissFrame(ax25);
                appendLog("Ping sent over " + (activeTx == meshBtManager ? "MeshCore" : "UV-PRO"));
            } catch (Exception e) {
                appendLog("Ping failed: " + e.getMessage());
            }
        } else {
            appendLog("Not connected");
        }
    }

    @Override
    public void onDropDownSelectionRemoved() { }

    @Override
    public void onDropDownClose() {
        stopActiveVfoPulse();
        stopUpdateGpsButtonPulse(true);
        stopScanConnectButtonPulse(true);
        stopMeshConnectButtonPulse(true);
        stopInitialGroupSetupPulse(true);
        if (repeaterLoadFocusAnimator != null) {
            repeaterLoadFocusAnimator.cancel();
            repeaterLoadFocusAnimator = null;
        }
        if (btnLoadSelectedRepeater != null) {
            btnLoadSelectedRepeater.setAlpha(1f);
        }
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) { }

    @Override
    public void onDropDownVisible(boolean visible) {
        if (!visible) {
            stopActiveVfoPulse();
        } else {
            refreshChannelGroupFromRadioAsync(true);
            if (btManager.isConnected()) {
                refreshTxPowerFromRadioAsync();
            }
            if (pendingOpenToChannelControl) {
                scheduleScrollToRepeaterLoadSection();
                pendingOpenToChannelControl = false;
            }
        }
    }

    @Override
    public void disposeImpl() {
        // Unregister listeners
        btManager.removeListener(this);
        contactTracker.setListener(null);
        radioGpsAugmentController.shutdown();
        if (getMapView() != null) {
            getMapView().removeCallbacks(meshGpsAugmentRunnable);
        }
        stopActiveVfoPulse();
        stopUpdateGpsButtonPulse(true);
        stopScanConnectButtonPulse(true);
        stopInitialGroupSetupPulse(true);
        if (repeaterLoadFocusAnimator != null) {
            repeaterLoadFocusAnimator.cancel();
            repeaterLoadFocusAnimator = null;
        }
    }
}
