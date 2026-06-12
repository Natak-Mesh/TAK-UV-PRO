package com.uvpro.plugin.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.gui.PanCheckBoxPreference;
import com.atakmap.android.gui.PanEditTextPreference;
import com.atakmap.android.gui.PanPreference;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.PluginPreferenceFragment;
import com.atakmap.app.SettingsActivity;
import com.uvpro.plugin.protocol.NetSlotConfig;
import com.uvpro.plugin.protocol.UVProRadioServices;
import com.uvpro.plugin.UVProMapComponent;
import com.uvpro.plugin.beacon.SmartBeacon;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings screen for the UVPro plugin.
 *
 * Provides configuration for:
 * - Callsign
 * - Beacon interval
 * - Chat relay toggle
 * - CoT relay toggle
 * - Auto-reconnect toggle
 */
public class SettingsFragment extends PluginPreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int COLOR_STD_BLUE = 0xFF1976D2;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_CATEGORY_YELLOW = 0xFFFFEB3B;
    private static final int COLOR_VALUE_GREEN = 0xFF4CAF50;
    private static final int COLOR_DISABLED_GREY = 0xFF757575;
    private static final int COLOR_WARNING_RED = 0xFFFF5252;
    private static final int PREFERENCE_TITLE_TEXT_SP = 16;
    private static final float CATEGORY_TITLE_TEXT_SP = 18f;

    /** Beacon interval + Smart Beacon (Tool Preferences section). */
    public static final String KEY_CAT_BEACON = "uvpro_cat_beacon";
    public static final String KEY_SMART_BEACON_SECTION_HEADER = "uvpro_smart_beacon_section_header";
    private static final String BEACON_INTERVAL_DESC =
            "Sets the ATAK call sign beacon interval";
    private static final String APRS_ICON_DESC = "Map symbol for your position beacons";
    private static final String APRS_ICON_NOT_SET = "Not Set";

    /** Key registered with {@code ToolsPreferenceFragment} in {@link com.uvpro.plugin.UVProMapComponent}. */
    public static final String TOOL_SETTINGS_KEY = "uvproPreference";

    public static final String PREF_BEACON_INTERVAL = "uvpro_beacon_interval";
    public static final String PREF_ENCRYPTION_ENABLED = "uvpro_encryption_enabled";
    public static final String PREF_ENCRYPTION_PASSPHRASE = "uvpro_encryption_passphrase";
    public static final String PREF_RETRY_INTERVAL_MIN = "uvpro_retry_interval_min";
    public static final String PREF_RETRY_MAX = "uvpro_retry_max";
    public static final String PREF_SA_RELAY_ENABLED = "uvpro_sa_relay_enabled";
    public static final String PREF_RF_TO_TAK_UPLINK_ENABLED = "uvpro_rf_to_tak_uplink_enabled";
    public static final String PREF_RESTRICT_CHAT_TO_REACHABLE_PEERS =
            "uvpro_restrict_chat_to_reachable_peers";
    public static final String PREF_PING_REPLY_ENABLED = "uvpro_ping_reply_enabled";
    public static final String PREF_PING_REPLY_SAME_TRANSPORT = "uvpro_ping_reply_same_transport";
    public static final String PREF_ATAK_MESHCORE_TRANSMIT = "uvpro_atak_meshcore_transmit";
    public static final String PREF_ATAK_UVPRO_TRANSMIT = "uvpro_atak_uvpro_transmit";
  /** National-only mesh periodic beacons — hidden until administrative unlock. */
    public static final String PREF_MESH_BEACON_ENABLED = "uvpro_mesh_beacon_enabled";

    public static final String PREF_APRS_CALLSIGN = "uvpro_aprs_callsign";
    public static final String PREF_APRS_SSID = "uvpro_aprs_ssid";
    public static final String PREF_APRS_SYMBOL_TABLE = "uvpro_aprs_symbol_table";
    public static final String PREF_APRS_SYMBOL_CODE = "uvpro_aprs_symbol_code";
    public static final String PREF_APRS_ICON_SELECTED = "uvpro_aprs_icon_selected";
    public static final String PREF_APRS_MESSAGE = "uvpro_aprs_message";
    public static final String PREF_APRS_TX_ARMED = "uvpro_aprs_tx_armed";
    public static final String PREF_APRS_DISABLE_ATAK_TRAFFIC = "uvpro_aprs_disable_atak_traffic";

    public static final String KEY_CAT_APRS = "uvpro_cat_aprs";
    public static final String KEY_CAT_SECURITY = "uvpro_cat_security";
    public static final String KEY_APRS_ICON = "uvpro_aprs_icon";

    public static final String KEY_UNLOCK_ADMIN = "uvpro_admin_access";
    public static final String KEY_CAT_ADMINISTRATION = "uvpro_cat_administration";
    public static final String KEY_ADMIN_LEADERSHIP_WARNING = "uvpro_admin_leadership_warning";
    public static final String KEY_DISTRIBUTE_NET_SLOTS = "uvpro_distribute_net_slots";
    public static final String KEY_DISTRIBUTE_NET_WARNING = "uvpro_distribute_net_warning";
    private static final String DISTRIBUTE_NET_WARNING =
            "WARNING: DO NOT SEND THIS FEATURE UNLESS SPECIFICALLY DIRECTED BY HIGHER";
    public static final String KEY_ADMIN_CURRENT_SLOT_STATUS = "uvpro_admin_current_slot_status";
    public static final String KEY_MESH_BEACON_NATIONAL_WARNING = "uvpro_mesh_beacon_national_warning";
    public static final String KEY_SA_RELAY_SECTION_HEADER = "uvpro_sa_relay_section_header";

    private static final String[] ADMIN_GATED_PREF_KEYS = {
            KEY_ADMIN_LEADERSHIP_WARNING,
            KEY_MESH_BEACON_NATIONAL_WARNING,
            PREF_MESH_BEACON_ENABLED,
            KEY_SA_RELAY_SECTION_HEADER,
            PREF_SA_RELAY_ENABLED,
            PREF_RF_TO_TAK_UPLINK_ENABLED,
            NetSlotConfig.PREF_SLOT_COUNT,
            NetSlotConfig.PREF_SLOT_TIME_SEC,
            KEY_DISTRIBUTE_NET_WARNING,
            KEY_DISTRIBUTE_NET_SLOTS,
            KEY_ADMIN_CURRENT_SLOT_STATUS,
    };

    /** Injected after inflate — some ATAK builds omit custom Pan* prefs from XML. */
    public static final String KEY_BLUETOOTH_DEVICES = "uvpro_bluetooth_devices";
    public static final String KEY_CAT_RADIO = "uvpro_cat_radio";

    public static final String DEFAULT_BEACON_INTERVAL = "600";
    public static final String DEFAULT_RETRY_INTERVAL_MIN = "2";
    public static final String DEFAULT_RETRY_MAX = "3";

    private static Context staticPluginContext;

    private PreferenceCategory adminCategory;

    /**
     * Zero-arg constructor required by Android fragment system.
     * Only valid after the 1-arg constructor has been called once.
     */
    public SettingsFragment() {
        super(staticPluginContext, resolvePreferencesResourceId(staticPluginContext));
    }

    public SettingsFragment(final Context pluginContext) {
        super(pluginContext, resolvePreferencesResourceId(pluginContext));
        staticPluginContext = pluginContext;
    }

    private static int resolvePreferencesResourceId(Context ctx) {
        if (ctx == null) {
            return 0;
        }
        return ctx.getResources().getIdentifier(
                "preferences", "xml", ctx.getPackageName());
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureRequiredPreferences();
        removeObsoletePreferences();
        Context ctx = getContext();
        if (ctx == null) {
            ctx = staticPluginContext;
        }
        if (ctx != null) {
            NetSlotConfig.ensureDefaults(ctx);
        }
        ensureBluetoothDevicesPreference();
        wireBeaconPreferences();
        wireAprsPreferences();
        wireAdministrationPreferences();
        wireAdminSettingsGate();
    }

    private void wireBeaconPreferences() {
        syncSmartBeaconPreferenceValues();
    }

    private void syncSmartBeaconPreferenceValues() {
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : staticPluginContext;
        if (ctx == null) {
            return;
        }
        setEditTextPreferenceText(SmartBeacon.KEY_LOW_SPEED,
                String.valueOf(SmartBeacon.getLowSpeed(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_HIGH_SPEED,
                String.valueOf(SmartBeacon.getHighSpeed(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_SLOW_RATE,
                String.valueOf(SmartBeacon.getSlowRate(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_FAST_RATE,
                String.valueOf(SmartBeacon.getFastRate(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_MIN_TURN_TIME,
                String.valueOf(SmartBeacon.getMinTurnTime(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_TURN_THRESHOLD,
                String.valueOf(SmartBeacon.getTurnThreshold(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_TURN_SLOPE,
                String.valueOf(SmartBeacon.getTurnSlope(ctx)));
    }

    private void setEditTextPreferenceText(String key, String value) {
        Preference pref = findPreference(key);
        if (pref instanceof PanEditTextPreference) {
            ((PanEditTextPreference) pref).setText(value);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        ListView list = getPreferenceListView();
        if (list != null) {
            list.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    child.post(SettingsFragment.this::applyRowStyles);
                }

                @Override
                public void onChildViewRemoved(View parent, View child) {
                }
            });
            list.post(this::applyRowStyles);
        }
    }

    private ListView getPreferenceListView() {
        View root = getView();
        if (root == null) {
            return null;
        }
        return root.findViewById(android.R.id.list);
    }

    private void applyRowStyles() {
        ListView list = getPreferenceListView();
        if (list == null) {
            return;
        }
        List<Preference> ordered = buildOrderedPreferences();
        for (int i = 0; i < list.getChildCount(); i++) {
            try {
                View row = list.getChildAt(i);
                Preference pref = resolvePreferenceForRow(list, row, i, ordered);
                if (pref != null) {
                    stylePreferenceRow(row, pref);
                }
            } catch (Exception e) {
                android.util.Log.w("UVPro.Settings", "applyRowStyles failed for row " + i, e);
            }
        }
    }

    private List<Preference> buildOrderedPreferences() {
        List<Preference> out = new ArrayList<>();
        android.preference.PreferenceScreen screen = getPreferenceScreen();
        if (screen != null) {
            collectPreferencesInOrder(screen, out);
        }
        return out;
    }

    private void collectPreferencesInOrder(PreferenceGroup group, List<Preference> out) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            out.add(pref);
            if (pref instanceof PreferenceGroup) {
                collectPreferencesInOrder((PreferenceGroup) pref, out);
            }
        }
    }

    private Preference resolvePreferenceForRow(ListView list, View row, int childIndex,
                                               List<Preference> ordered) {
        int position = list.getFirstVisiblePosition() + childIndex;
        if (position >= 0 && position < ordered.size()) {
            Preference candidate = ordered.get(position);
            if (preferenceMatchesRow(candidate, row)) {
                return candidate;
            }
        }
        TextView titleView = row.findViewById(android.R.id.title);
        if (titleView != null && titleView.getText() != null) {
            Preference byTitle = findPreferenceByTitle(titleView.getText().toString());
            if (byTitle != null) {
                return byTitle;
            }
        }
        if (position >= 0 && position < ordered.size()) {
            return ordered.get(position);
        }
        return null;
    }

    private Preference findPreferenceByTitle(String title) {
        if (title == null || title.isEmpty()) {
            return null;
        }
        android.preference.PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return null;
        }
        return findPreferenceByTitle(screen, title);
    }

    private Preference findPreferenceByTitle(PreferenceGroup group, String title) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            if (pref.getTitle() != null && title.equals(pref.getTitle().toString())) {
                return pref;
            }
            if (pref instanceof PreferenceGroup) {
                Preference nested = findPreferenceByTitle((PreferenceGroup) pref, title);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private boolean preferenceMatchesRow(Preference pref, View row) {
        if (pref == null || row == null) {
            return false;
        }
        TextView titleView = row.findViewById(android.R.id.title);
        if (titleView == null || titleView.getText() == null
                || titleView.getText().length() == 0) {
            return true;
        }
        if (pref.getTitle() == null) {
            return false;
        }
        return titleView.getText().toString().equals(pref.getTitle().toString());
    }

    private void stylePreferenceRow(View row, Preference pref) {
        if (row == null || pref == null) {
            return;
        }
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        if (pref instanceof PreferenceCategory) {
            if (title != null) {
                styleCategoryTitle(title);
                centerTitleInRow(title);
            }
            if (summary != null) {
                summary.setVisibility(View.GONE);
            }
            return;
        }
        if (KEY_SMART_BEACON_SECTION_HEADER.equals(pref.getKey())
                || KEY_SA_RELAY_SECTION_HEADER.equals(pref.getKey())) {
            styleBlueSectionHeaderRow(row, pref);
            return;
        }
        if (KEY_DISTRIBUTE_NET_WARNING.equals(pref.getKey())) {
            styleDistributeNetWarningRow(row, pref);
            return;
        }
        if (KEY_DISTRIBUTE_NET_SLOTS.equals(pref.getKey())) {
            styleDistributeNetButtonRow(row, pref);
            return;
        }
        resetStandardPreferenceRow(row, pref);
        if (title != null) {
            resetStandardRowTitle(title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PREFERENCE_TITLE_TEXT_SP);
            title.setTypeface(Typeface.DEFAULT);
            title.setTextColor(pref.isEnabled() ? COLOR_WHITE : COLOR_DISABLED_GREY);
        }
        if (summary != null) {
            resetStandardSummary(summary);
            applySummaryText(summary, pref);
        }
    }

    private void styleBlueSectionHeaderRow(View row, Preference pref) {
        resetStandardPreferenceRow(row, pref);
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        if (title != null) {
            resetStandardRowTitle(title);
            title.setTextColor(COLOR_STD_BLUE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PREFERENCE_TITLE_TEXT_SP);
            title.setTypeface(Typeface.DEFAULT_BOLD);
        }
        if (summary != null) {
            resetStandardSummary(summary);
            summary.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            applySummaryText(summary, pref);
            summary.setVisibility(View.VISIBLE);
        }
    }

    private void resetStandardPreferenceRow(View row, Preference pref) {
        if (row == null) {
            return;
        }
        Context ctx = resolveSettingsContext();
        int padStart = dp(ctx, 16);
        int padEnd = dp(ctx, 16);
        row.setPaddingRelative(padStart, row.getPaddingTop(), padEnd, row.getPaddingBottom());

        if (row instanceof LinearLayout) {
            ((LinearLayout) row).setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        }

        View icon = row.findViewById(android.R.id.icon);
        if (icon != null) {
            icon.setVisibility(View.GONE);
            ViewGroup.LayoutParams iconLp = icon.getLayoutParams();
            if (iconLp != null) {
                iconLp.width = 0;
                icon.setLayoutParams(iconLp);
            }
        }

        TextView title = row.findViewById(android.R.id.title);
        if (title != null && title.getParent() instanceof ViewGroup) {
            ViewGroup contentCol = (ViewGroup) title.getParent();
            if (contentCol instanceof LinearLayout) {
                ((LinearLayout) contentCol).setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            }
            ViewGroup.LayoutParams colLp = contentCol.getLayoutParams();
            if (colLp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) colLp;
                llp.width = 0;
                llp.weight = 1f;
                llp.gravity = Gravity.START;
                contentCol.setLayoutParams(llp);
            }
            contentCol.setPaddingRelative(0, contentCol.getPaddingTop(), 0,
                    contentCol.getPaddingBottom());
        }

        clearRedundantListValueWidget(row, pref);
    }

    private void styleDistributeNetWarningRow(View row, Preference pref) {
        resetStandardPreferenceRow(row, pref);
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        if (title != null) {
            resetStandardRowTitle(title);
            title.setText(DISTRIBUTE_NET_WARNING);
            title.setTextColor(pref.isEnabled() ? COLOR_WARNING_RED : COLOR_DISABLED_GREY);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PREFERENCE_TITLE_TEXT_SP);
            title.setTypeface(Typeface.DEFAULT_BOLD);
        }
        if (summary != null) {
            summary.setVisibility(View.GONE);
        }
    }

    private void styleDistributeNetButtonRow(View row, Preference pref) {
        Context ctx = resolveSettingsContext();
        int hPad = dp(ctx, 16);
        int vPad = dp(ctx, 12);
        row.setPaddingRelative(hPad, vPad, hPad, vPad);
        row.setBackgroundColor(pref.isEnabled() ? COLOR_STD_BLUE : COLOR_DISABLED_GREY);

        View icon = row.findViewById(android.R.id.icon);
        if (icon != null) {
            icon.setVisibility(View.GONE);
        }
        View widgetFrame = row.findViewById(android.R.id.widget_frame);
        if (widgetFrame instanceof ViewGroup) {
            ((ViewGroup) widgetFrame).removeAllViews();
        }

        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        if (title != null) {
            title.setGravity(Gravity.CENTER);
            title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            ViewGroup.LayoutParams lp = title.getLayoutParams();
            if (lp != null) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                title.setLayoutParams(lp);
            }
            title.setText("Distribute to net");
            title.setTextColor(COLOR_WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PREFERENCE_TITLE_TEXT_SP);
            title.setTypeface(Typeface.DEFAULT_BOLD);
        }
        if (summary != null) {
            summary.setVisibility(View.GONE);
        }
    }

    private void clearRedundantListValueWidget(View row, Preference pref) {
        if (!(pref instanceof ListPreference)) {
            return;
        }
        View widgetFrame = row.findViewById(android.R.id.widget_frame);
        if (!(widgetFrame instanceof ViewGroup)) {
            return;
        }
        ViewGroup widgetGroup = (ViewGroup) widgetFrame;
        for (int i = widgetGroup.getChildCount() - 1; i >= 0; i--) {
            View child = widgetGroup.getChildAt(i);
            if (!(child instanceof CompoundButton)) {
                widgetGroup.removeViewAt(i);
            }
        }
    }

    private void centerTitleInRow(TextView title) {
        if (title == null) {
            return;
        }
        title.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        ViewGroup.LayoutParams lp = title.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            title.setLayoutParams(lp);
        }
    }

    private void resetStandardRowTitle(TextView title) {
        if (title == null) {
            return;
        }
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        ViewGroup.LayoutParams lp = title.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).setMarginStart(0);
            }
            title.setLayoutParams(lp);
        }
    }

    private void resetStandardSummary(TextView summary) {
        if (summary == null) {
            return;
        }
        summary.setGravity(Gravity.START);
        summary.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        ViewGroup.LayoutParams lp = summary.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).setMarginStart(0);
            }
            summary.setLayoutParams(lp);
        }
    }

    private void styleCategoryTitle(TextView title) {
        title.setTextColor(COLOR_CATEGORY_YELLOW);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, CATEGORY_TITLE_TEXT_SP);
        title.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private void applySummaryText(TextView summary, Preference pref) {
        CharSequence styledSummary = buildStyledSummaryForPreference(pref);
        if (styledSummary != null) {
            summary.setText(styledSummary);
            summary.setVisibility(View.VISIBLE);
        }
    }

    private void removeObsoletePreferences() {
        removePreferenceFromScreen(SmartBeacon.KEY_ENABLED);
        removePreferenceFromScreen(PREF_PING_REPLY_SAME_TRANSPORT);
        removePreferenceFromScreen(KEY_UNLOCK_ADMIN);
        removePreferenceFromScreen(PREF_ENCRYPTION_ENABLED);
        removePreferenceFromScreen("uvpro_sa_relay_header");
        removePreferenceFromScreen("uvpro_cat_sa_relay");
    }

    private void removePreferenceFromScreen(String key) {
        Preference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        PreferenceGroup parent = pref.getParent();
        if (parent != null) {
            parent.removePreference(pref);
        } else {
            android.preference.PreferenceScreen screen = getPreferenceScreen();
            if (screen != null) {
                screen.removePreference(pref);
            }
        }
    }

    /**
     * Some ATAK builds drop custom Pan* prefs from XML inflation. Inject any missing
     * required controls before preference binding runs in {@code onActivityCreated}.
     */
    private void ensureRequiredPreferences() {
        PreferenceCategory radio = (PreferenceCategory) findPreference(KEY_CAT_RADIO);
        if (radio != null) {
            ensureCheckBoxPreference(radio, PREF_PING_REPLY_ENABLED,
                    "Send Ping Reply",
                    "Automatically reply to incoming pings with your position",
                    true);
        }
        PreferenceCategory admin = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        PreferenceCategory saRelay = (PreferenceCategory) findPreference("uvpro_cat_sa_relay");
        if (saRelay != null) {
            ensureCheckBoxPreference(saRelay, PREF_SA_RELAY_ENABLED,
                    "Enable SA Relay",
                    "Re-broadcast inbound network positions over radio to off-grid users. "
                            + "Throttled to one update per contact per 30 seconds.",
                    false);
        } else if (admin != null) {
            ensureCheckBoxPreference(admin, PREF_SA_RELAY_ENABLED,
                    "Enable SA Relay",
                    "Re-broadcast inbound network positions over radio to off-grid users. "
                            + "Throttled to one update per contact per 30 seconds.",
                    false);
        }
    }

    private void ensureCheckBoxPreference(PreferenceGroup parent, String key, String title,
                                          String summary, boolean defaultValue) {
        if (parent == null || findPreference(key) != null) {
            return;
        }
        Context ctx = getActivity() != null ? getActivity() : staticPluginContext;
        if (ctx == null) {
            return;
        }
        PanCheckBoxPreference pref = new PanCheckBoxPreference(ctx);
        pref.setKey(key);
        pref.setTitle(title);
        pref.setSummary(summary);
        pref.setDefaultValue(defaultValue);
        parent.addPreference(pref);
    }

    private void updateDependentPreferences() {
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        Preference rfUplinkPref = findPreference(PREF_RF_TO_TAK_UPLINK_ENABLED);
        if (rfUplinkPref != null) {
            rfUplinkPref.setEnabled(prefs.getBoolean(PREF_SA_RELAY_ENABLED, false));
        }
    }

    /**
     * Removes the legacy "Bluetooth Devices" (favorites manager) preference if a saved
     * preference XML or older install still surfaces it. Favorites have been retired.
     */
    private void ensureBluetoothDevicesPreference() {
        try {
            Preference existing = findPreference(KEY_BLUETOOTH_DEVICES);
            if (existing == null) return;
            PreferenceGroup parent = (PreferenceGroup) findPreference("uvpro_cat_bluetooth");
            if (parent == null) parent = (PreferenceGroup) findPreference(KEY_CAT_RADIO);
            if (parent != null) {
                parent.removePreference(existing);
            } else {
                android.preference.PreferenceScreen root = getPreferenceScreen();
                if (root != null) root.removePreference(existing);
            }
        } catch (Exception e) {
            android.util.Log.w("UVPro.Settings", "Could not remove legacy Bluetooth Devices pref", e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
        styleAdminLeadershipWarning();
        syncAdminSettingsGateOnResume();
        updateAdminControlsEnabled();
        syncSmartBeaconPreferenceValues();
        updateDependentPreferences();
        updateSummaries();
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(this::applyRowStyles);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs,
                                          String key) {
        if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key)) {
            updateAdminControlsEnabled();
        }
        if (NetSlotConfig.PREF_SLOT_COUNT.equals(key)
                || NetSlotConfig.PREF_SLOT_TIME_SEC.equals(key)) {
            normalizeSlotPreferences(prefs);
        }
        if (PREF_BEACON_INTERVAL.equals(key)
                || PREF_MESH_BEACON_ENABLED.equals(key)
                || PREF_SA_RELAY_ENABLED.equals(key)
                || PREF_RF_TO_TAK_UPLINK_ENABLED.equals(key)
                || PREF_PING_REPLY_ENABLED.equals(key)
                || isSmartBeaconParamKey(key)) {
            if (isSmartBeaconParamKey(key)) {
                persistSmartBeaconFromPreferences();
            }
            notifyRuntimeSettingsChanged();
        }
        if (PREF_SA_RELAY_ENABLED.equals(key)) {
            updateDependentPreferences();
        }
        if (PREF_ENCRYPTION_ENABLED.equals(key) || PREF_ENCRYPTION_PASSPHRASE.equals(key)) {
            com.uvpro.plugin.protocol.UVProRadioServices.syncEncryptionFromSettings(
                    resolveSettingsContext());
        }
        updateSummaries();
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(this::applyRowStyles);
        }
    }

    private Context resolveSettingsContext() {
        Context ctx = getActivity() != null ? getActivity() : getContext();
        if (ctx == null && MapView.getMapView() != null) {
            ctx = MapView.getMapView().getContext();
        }
        if (ctx == null) {
            ctx = staticPluginContext;
        }
        return ctx;
    }

    /** ATAK default SharedPreferences — never the plugin package store. */
    private SharedPreferences atakPrefs() {
        return getPrefs(resolveSettingsContext());
    }

    private void wireAdminSettingsGate() {
        adminCategory = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        Preference adminToggle = findPreference(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED);
        if (!(adminToggle instanceof CheckBoxPreference)) {
            return;
        }
        CheckBoxPreference adminCheck = (CheckBoxPreference) adminToggle;
        adminCheck.setOnPreferenceChangeListener(null);
        adminCheck.setOnPreferenceClickListener(preference -> {
            Context prefsCtx = resolveSettingsContext();
            if (prefsCtx == null) {
                return false;
            }
            CheckBoxPreference checkbox = (CheckBoxPreference) preference;
            boolean turningOn = !checkbox.isChecked();

            if (!turningOn) {
                AdminAccessGate.lock(prefsCtx);
                checkbox.setChecked(false);
                refreshAdminGateUi();
                return true;
            }

            if (AdminAccessGate.isUnlocked(prefsCtx)) {
                enableAdminSettings(checkbox, prefsCtx);
                return true;
            }

            Context dialogCtx = getActivity() != null ? getActivity() : prefsCtx;
            promptAdministrativeUnlock(dialogCtx, prefsCtx, () ->
                    enableAdminSettings(checkbox, prefsCtx));
            return true;
        });
    }

    private void enableAdminSettings(CheckBoxPreference checkbox, Context prefsCtx) {
        getPrefs(prefsCtx).edit()
                .putBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, true)
                .apply();
        checkbox.setChecked(true);
        refreshAdminGateUi();
    }

    private void refreshAdminGateUi() {
        updateAdminControlsEnabled();
        updateSummaries();
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(this::applyRowStyles);
        }
    }

    private void syncAdminSettingsGateOnResume() {
        Context prefsCtx = resolveSettingsContext();
        if (prefsCtx == null) {
            return;
        }
        SharedPreferences prefs = atakPrefs();
        Preference adminToggle = findPreference(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED);
        if (!AdminAccessGate.isUnlocked(prefsCtx)) {
            if (prefs.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)) {
                prefs.edit()
                        .putBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)
                        .apply();
            }
            if (adminToggle instanceof CheckBoxPreference) {
                ((CheckBoxPreference) adminToggle).setChecked(false);
            }
        } else if (adminToggle instanceof CheckBoxPreference) {
            ((CheckBoxPreference) adminToggle).setChecked(
                    prefs.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false));
        }
    }

    private void notifyRuntimeSettingsChanged() {
        try {
            AtakBroadcast.getInstance().sendBroadcast(
                    new android.content.Intent(UVProMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
        } catch (Exception ignored) {
        }
    }

    /** Password dialog for Tools prefs and in-plugin settings. */
    public static void promptAdministrativeUnlock(Context dialogCtx, Runnable onUnlocked) {
        Context prefsCtx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : dialogCtx;
        promptAdministrativeUnlock(dialogCtx, prefsCtx, onUnlocked);
    }

    /** @param prefsCtx ATAK context used for {@link AdminAccessGate} read/write */
    public static void promptAdministrativeUnlock(Context dialogCtx, Context prefsCtx,
                                                  Runnable onUnlocked) {
        if (dialogCtx == null) {
            return;
        }
        if (prefsCtx == null) {
            prefsCtx = dialogCtx;
        }
        if (!AdminAccessGate.isConfigured()) {
            Toast.makeText(dialogCtx,
                    "Administrative password not configured in this build",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final EditText input = new EditText(dialogCtx);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final Context storeCtx = prefsCtx;
        new AlertDialog.Builder(dialogCtx)
                .setTitle("Administrative Settings")
                .setMessage("Enter password to unlock hidden settings.")
                .setView(input)
                .setPositiveButton("Unlock", (dialog, which) -> {
                    if (AdminAccessGate.unlock(storeCtx, input.getText().toString())) {
                        Toast.makeText(dialogCtx, "Administrative Settings unlocked",
                                Toast.LENGTH_SHORT).show();
                        if (onUnlocked != null) {
                            onUnlocked.run();
                        }
                    } else {
                        Toast.makeText(dialogCtx, "Incorrect password", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void wireAdministrationPreferences() {
        Preference distributeWarning = findPreference(KEY_DISTRIBUTE_NET_WARNING);
        if (distributeWarning != null) {
            distributeWarning.setTitle(DISTRIBUTE_NET_WARNING);
            distributeWarning.setSummary("");
        }
        Preference distribute = findPreference(KEY_DISTRIBUTE_NET_SLOTS);
        if (distribute != null) {
            distribute.setSummary("");
            distribute.setOnPreferenceClickListener(preference -> {
                Context ctx = getActivity() != null ? getActivity() : getContext();
                if (ctx == null && MapView.getMapView() != null) {
                    ctx = MapView.getMapView().getContext();
                }
                if (ctx == null) {
                    return true;
                }
                String error = distributeNetSlotsOrError(ctx);
                if (error == null) {
                    int slots = NetSlotConfig.getSlotCount(ctx);
                    float slotSec = NetSlotConfig.getSlotTimeSec(ctx);
                    Toast.makeText(ctx,
                            "Slot config sent (" + slots + " slots, "
                                    + slotSec + " s)",
                            Toast.LENGTH_LONG).show();
                    updateSummaries();
                } else {
                    Toast.makeText(ctx, error, Toast.LENGTH_LONG).show();
                }
                return true;
            });
        }
        updateAdminControlsEnabled();
    }

    /** @return null on success, or a user-facing error message */
    private static String distributeNetSlotsOrError(Context ctx) {
        if (ctx == null) {
            return "Unable to access settings";
        }
        SharedPreferences prefs = getPrefs(ctx);
        if (!prefs.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)
                || !AdminAccessGate.isUnlocked(ctx)) {
            return "Enable administrative settings and enter password";
        }
        normalizeSlotPreferencesStatic(ctx, prefs);
        int slots = NetSlotConfig.getSlotCount(ctx);
        float slotSec = NetSlotConfig.getSlotTimeSec(ctx);
        NetSlotConfig.saveLocalSlotSettings(ctx, slots, slotSec);
        if (!UVProRadioServices.isConnected()) {
            return "Connect to radio before distributing slot settings";
        }
        if (UVProRadioServices.distributeNetSlotConfig(ctx)) {
            return null;
        }
        return "Failed to send slot config over radio";
    }

    private static void normalizeSlotPreferencesStatic(Context ctx, SharedPreferences prefs) {
        int slots = NetSlotConfig.getSlotCount(ctx);
        float sec = NetSlotConfig.getSlotTimeSec(ctx);
        prefs.edit()
                .putString(NetSlotConfig.PREF_SLOT_COUNT, String.valueOf(slots))
                .putString(NetSlotConfig.PREF_SLOT_TIME_SEC, String.valueOf(sec))
                .apply();
    }

    private void wireAprsPreferences() {
        Preference iconPref = findPreference(KEY_APRS_ICON);
        if (iconPref != null) {
            updateAprsIconSummary(iconPref);
            iconPref.setOnPreferenceClickListener(p -> {
                Context ctx = getContext();
                if (ctx == null) {
                    ctx = staticPluginContext;
                }
                if (ctx != null) {
                    com.uvpro.plugin.aprs.AprsIconPickerDialog.show(
                            ctx, staticPluginContext,
                            () -> updateAprsIconSummary(iconPref));
                }
                return true;
            });
        }
    }

    private void updateAprsIconSummary(Preference iconPref) {
        if (iconPref == null) {
            return;
        }
        iconPref.setSummary(formatAprsIconSummary(iconPref.isEnabled()));
    }

    private CharSequence formatAprsIconSummary(boolean enabled) {
        Context mapCtx = resolveSettingsContext();
        if (mapCtx == null) {
            mapCtx = getContext();
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(APRS_ICON_DESC);
        int descriptionColor = enabled ? COLOR_WHITE : COLOR_DISABLED_GREY;
        sb.setSpan(new ForegroundColorSpan(descriptionColor), 0, APRS_ICON_DESC.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), 0, APRS_ICON_DESC.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        sb.append('\n');
        int valueStart = sb.length();

        Bitmap iconBitmap = null;
        if (mapCtx != null && isAprsIconSelected(mapCtx)) {
            iconBitmap = com.uvpro.plugin.aprs.AprsIconPreviewLoader
                    .loadSelectedIconBitmap(mapCtx, staticPluginContext);
        }

        if (iconBitmap == null) {
            sb.append(APRS_ICON_NOT_SET);
            int valueColor = enabled ? COLOR_VALUE_GREEN : COLOR_DISABLED_GREY;
            sb.setSpan(new ForegroundColorSpan(valueColor), valueStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), valueStart,
                    sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return sb;
        }

        Context drawableCtx = mapCtx != null ? mapCtx : staticPluginContext;
        if (drawableCtx == null) {
            sb.append(APRS_ICON_NOT_SET);
            int valueColor = enabled ? COLOR_VALUE_GREEN : COLOR_DISABLED_GREY;
            sb.setSpan(new ForegroundColorSpan(valueColor), valueStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), valueStart,
                    sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return sb;
        }

        sb.append('\uFFFC');
        Bitmap scaled = scaleBitmapForSummaryLine(drawableCtx, iconBitmap);
        BitmapDrawable drawable = new BitmapDrawable(drawableCtx.getResources(), scaled);
        int sizePx = dp(drawableCtx, 20);
        drawable.setBounds(0, 0, sizePx, sizePx);
        sb.setSpan(new ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), valueStart, valueStart + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static Bitmap scaleBitmapForSummaryLine(Context ctx, Bitmap src) {
        if (ctx == null || src == null) {
            return src;
        }
        int targetPx = dp(ctx, 20);
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) {
            return src;
        }
        float scale = Math.min((float) targetPx / w, (float) targetPx / h);
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        if (nw == w && nh == h) {
            return src;
        }
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    private void styleAdminLeadershipWarning() {
        Preference warning = findPreference(KEY_ADMIN_LEADERSHIP_WARNING);
        if (warning != null) {
            warning.setTitle("For Team Leadership ONLY — Do not use unless directed by higher");
        }
    }

    private void updateAdminControlsEnabled() {
        Context ctx = resolveSettingsContext();
        SharedPreferences prefs = atakPrefs();
        boolean adminOn = prefs.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false);
        boolean unlocked = ctx != null && AdminAccessGate.isUnlocked(ctx);
        boolean enableChildren = adminOn && unlocked;
        for (String key : ADMIN_GATED_PREF_KEYS) {
            setPreferenceEnabled(key, enableChildren);
        }
        setPreferenceEnabled(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, true);
        if (enableChildren) {
            updateDependentPreferences();
        }
    }

    private boolean isAdminSectionUnlocked(Context ctx) {
        if (ctx == null) {
            return false;
        }
        SharedPreferences prefs = getPrefs(ctx);
        return prefs.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)
                && AdminAccessGate.isUnlocked(ctx);
    }

    private void setPreferenceEnabled(String key, boolean enabled) {
        Preference p = findPreference(key);
        if (p != null) {
            p.setEnabled(enabled);
        }
    }

    private void normalizeSlotPreferences(SharedPreferences prefs) {
        int slots = NetSlotConfig.getSlotCount(
                MapView.getMapView() != null
                        ? MapView.getMapView().getContext()
                        : staticPluginContext);
        float sec = NetSlotConfig.getSlotTimeSec(
                MapView.getMapView() != null
                        ? MapView.getMapView().getContext()
                        : staticPluginContext);
        prefs.edit()
                .putString(NetSlotConfig.PREF_SLOT_COUNT, String.valueOf(slots))
                .putString(NetSlotConfig.PREF_SLOT_TIME_SEC, String.valueOf(sec))
                .apply();
    }

    private void updateSummaries() {
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : staticPluginContext;

        Preference beaconPref = findPreference(PREF_BEACON_INTERVAL);
        if (beaconPref != null) {
            boolean smartBeaconOn = ctx != null && SmartBeacon.isEnabled(ctx);
            beaconPref.setEnabled(!smartBeaconOn);
            String beaconValue = smartBeaconOn
                    ? "Smart Beacon active"
                    : getListPreferenceValueLabel(PREF_BEACON_INTERVAL);
            if (beaconValue == null || beaconValue.isEmpty()) {
                beaconValue = prefs.getString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL)
                        + " seconds";
            }
            beaconPref.setSummary(formatSummaryWithValue(BEACON_INTERVAL_DESC, beaconValue));
        }

        updateSmartBeaconFieldSummaries();

        setSummaryWithValue(PREF_PING_REPLY_ENABLED,
                "Automatically reply to incoming pings with your position",
                getCheckBoxPreferenceValueLabel(PREF_PING_REPLY_ENABLED, true));
        setSummaryWithValue(PREF_RETRY_INTERVAL_MIN,
                "How long to wait before retransmitting an unacknowledged message",
                getListPreferenceValueLabel(PREF_RETRY_INTERVAL_MIN));
        setSummaryWithValue(PREF_RETRY_MAX,
                "Number of retransmit attempts before declaring delivery failure",
                getListPreferenceValueLabel(PREF_RETRY_MAX));

        setSummaryWithValue(PREF_APRS_CALLSIGN,
                "Ham radio call (required for APRS TX when armed)",
                emptyToNotSet(getEditTextPreferenceValue(PREF_APRS_CALLSIGN)));
        setSummaryWithValue(PREF_APRS_SSID,
                "Standard APRS SSID 0–15",
                getListPreferenceValueLabel(PREF_APRS_SSID));
        setSummaryWithValue(PREF_APRS_MESSAGE,
                "Comment text appended to position reports",
                emptyToNotSet(getEditTextPreferenceValue(PREF_APRS_MESSAGE)));

        setSummaryWithValue(PREF_ENCRYPTION_PASSPHRASE,
                "Same value on every radio that uses RF encryption",
                maskSecret(getEditTextPreferenceValue(PREF_ENCRYPTION_PASSPHRASE)));

        setSummaryWithValue(PREF_MESH_BEACON_ENABLED,
                "Timed mesh position beacons (MeshCore transport)",
                getCheckBoxPreferenceValueLabel(PREF_MESH_BEACON_ENABLED, false));
        setSummaryWithValue(PREF_SA_RELAY_ENABLED,
                "Re-broadcast inbound network positions over radio to off-grid users. "
                        + "Throttled to one update per contact per 30 seconds.",
                getCheckBoxPreferenceValueLabel(PREF_SA_RELAY_ENABLED, false));
        setSummaryWithValue(PREF_RF_TO_TAK_UPLINK_ENABLED,
                "When SA Relay is enabled, forward inbound RF CoT to TAK network. "
                        + "Use with care to avoid unintended rebroadcast loops.",
                getCheckBoxPreferenceValueLabel(PREF_RF_TO_TAK_UPLINK_ENABLED, false));
        setSummaryWithValue(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED,
                "Unlock slot assignment controls for net leadership",
                getCheckBoxPreferenceValueLabel(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false));

        if (ctx != null) {
            setSummaryWithValue(NetSlotConfig.PREF_SLOT_COUNT,
                    "Ping-reply slots (default 20)",
                    String.valueOf(NetSlotConfig.getSlotCount(ctx)));
            setSummaryWithValue(NetSlotConfig.PREF_SLOT_TIME_SEC,
                    "Seconds between slot start times (default 2.5 s)",
                    String.format(java.util.Locale.US, "%.1f s",
                            NetSlotConfig.getSlotTimeSec(ctx)));
        } else {
            setSummaryWithValue(NetSlotConfig.PREF_SLOT_COUNT,
                    "Ping-reply slots (default 20)",
                    getEditTextPreferenceValue(NetSlotConfig.PREF_SLOT_COUNT));
            setSummaryWithValue(NetSlotConfig.PREF_SLOT_TIME_SEC,
                    "Seconds between slot start times (default 2.5 s)",
                    getEditTextPreferenceValue(NetSlotConfig.PREF_SLOT_TIME_SEC));
        }

        updateDependentPreferences();

        Preference aprsIconPref = findPreference(KEY_APRS_ICON);
        if (aprsIconPref != null) {
            updateAprsIconSummary(aprsIconPref);
        }

        if (ctx != null) {
            int slots = NetSlotConfig.getSlotCount(ctx);
            float slotSec = NetSlotConfig.getSlotTimeSec(ctx);
            Preference currentStatus = findPreference(KEY_ADMIN_CURRENT_SLOT_STATUS);
            if (currentStatus != null) {
                String status = String.format(java.util.Locale.US,
                        "Slot count: %d — Slot time: %.1f s", slots, slotSec);
                String issuer = prefs.getString(NetSlotConfig.PREF_LAST_NET_SLOT_ISSUER, "");
                int seq = prefs.getInt(NetSlotConfig.PREF_NET_SLOT_CONFIG_SEQ, 0);
                if (seq > 0 && issuer != null && !issuer.isEmpty()) {
                    status += "\nLast net update from " + issuer;
                }
                currentStatus.setSummary(status);
            }
        }
    }

    private void updateSmartBeaconFieldSummaries() {
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : staticPluginContext;
        if (ctx == null) {
            return;
        }
        setSummaryWithValue(SmartBeacon.KEY_LOW_SPEED,
                "Below this speed the slowest rate is used",
                SmartBeacon.getLowSpeed(ctx) + " mph");
        setSummaryWithValue(SmartBeacon.KEY_HIGH_SPEED,
                "Above this speed the fastest rate is used",
                SmartBeacon.getHighSpeed(ctx) + " mph");
        setSummaryWithValue(SmartBeacon.KEY_SLOW_RATE,
                "Max time between beacons when slow or stopped",
                SmartBeacon.getSlowRate(ctx) + " s");
        setSummaryWithValue(SmartBeacon.KEY_FAST_RATE,
                "Min time between beacons when moving fast",
                SmartBeacon.getFastRate(ctx) + " s");
        setSummaryWithValue(SmartBeacon.KEY_MIN_TURN_TIME,
                "Minimum delay between corner-pegging beacons",
                SmartBeacon.getMinTurnTime(ctx) + " s");
        setSummaryWithValue(SmartBeacon.KEY_TURN_THRESHOLD,
                "Heading change needed to trigger an early beacon",
                SmartBeacon.getTurnThreshold(ctx) + "°");
        setSummaryWithValue(SmartBeacon.KEY_TURN_SLOPE,
                "Scales turn sensitivity with speed (higher = less sensitive at low speed)",
                String.valueOf(SmartBeacon.getTurnSlope(ctx)));
    }

    private void setSummaryWithValue(String key, String description, String value) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setSummary(formatSummaryWithValue(description, value, pref.isEnabled()));
        }
    }

    private static CharSequence formatSummaryWithValue(String description, String value) {
        return formatSummaryWithValue(description, value, true);
    }

    private static CharSequence formatDescriptionOnly(String description) {
        if (description == null) {
            description = "";
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(description);
        sb.setSpan(new ForegroundColorSpan(COLOR_WHITE), 0, description.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), 0, description.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static CharSequence formatSummaryWithValue(String description, String value,
                                                       boolean enabled) {
        String display = value;
        if (display == null || display.trim().isEmpty()) {
            display = "(not set)";
        } else {
            display = display.trim();
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(description);
        int descriptionColor = enabled ? COLOR_WHITE : COLOR_DISABLED_GREY;
        int valueColor = enabled ? COLOR_VALUE_GREEN : COLOR_DISABLED_GREY;
        sb.setSpan(new ForegroundColorSpan(descriptionColor), 0, description.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), 0, description.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int valueStart = sb.length();
        sb.append('\n').append(display);
        sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), valueStart, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(valueColor), valueStart, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private CharSequence buildStyledSummaryForPreference(Preference pref) {
        if (pref == null || pref.getKey() == null) {
            return null;
        }
        String key = pref.getKey();
        if (KEY_SMART_BEACON_SECTION_HEADER.equals(key)
                || KEY_SA_RELAY_SECTION_HEADER.equals(key)) {
            CharSequence summary = pref.getSummary();
            return formatDescriptionOnly(summary != null ? summary.toString() : "");
        }
        if (KEY_ADMIN_LEADERSHIP_WARNING.equals(key)
                || KEY_MESH_BEACON_NATIONAL_WARNING.equals(key)
                || KEY_ADMIN_CURRENT_SLOT_STATUS.equals(key)
                || KEY_DISTRIBUTE_NET_WARNING.equals(key)
                || KEY_DISTRIBUTE_NET_SLOTS.equals(key)) {
            return pref.getSummary();
        }
        if (KEY_APRS_ICON.equals(key)) {
            return formatAprsIconSummary(pref.isEnabled());
        }
        String description = getDescriptionForPreferenceKey(key);
        if (description == null) {
            CharSequence summary = pref.getSummary();
            return summary != null ? summary : "";
        }
        return formatSummaryWithValue(description, resolvePreferenceDisplayValue(key),
                pref.isEnabled());
    }

    private String getDescriptionForPreferenceKey(String key) {
        if (PREF_BEACON_INTERVAL.equals(key)) {
            return BEACON_INTERVAL_DESC;
        }
        if (SmartBeacon.KEY_LOW_SPEED.equals(key)) {
            return "Below this speed the slowest rate is used";
        }
        if (SmartBeacon.KEY_HIGH_SPEED.equals(key)) {
            return "Above this speed the fastest rate is used";
        }
        if (SmartBeacon.KEY_SLOW_RATE.equals(key)) {
            return "Max time between beacons when slow or stopped";
        }
        if (SmartBeacon.KEY_FAST_RATE.equals(key)) {
            return "Min time between beacons when moving fast";
        }
        if (SmartBeacon.KEY_MIN_TURN_TIME.equals(key)) {
            return "Minimum delay between corner-pegging beacons";
        }
        if (SmartBeacon.KEY_TURN_THRESHOLD.equals(key)) {
            return "Heading change needed to trigger an early beacon";
        }
        if (SmartBeacon.KEY_TURN_SLOPE.equals(key)) {
            return "Scales turn sensitivity with speed (higher = less sensitive at low speed)";
        }
        if (PREF_PING_REPLY_ENABLED.equals(key)) {
            return "Automatically reply to incoming pings with your position";
        }
        if (PREF_RETRY_INTERVAL_MIN.equals(key)) {
            return "How long to wait before retransmitting an unacknowledged message";
        }
        if (PREF_RETRY_MAX.equals(key)) {
            return "Number of retransmit attempts before declaring delivery failure";
        }
        if (PREF_APRS_CALLSIGN.equals(key)) {
            return "Ham radio call (required for APRS TX when armed)";
        }
        if (PREF_APRS_SSID.equals(key)) {
            return "Standard APRS SSID 0–15";
        }
        if (KEY_APRS_ICON.equals(key)) {
            return APRS_ICON_DESC;
        }
        if (PREF_APRS_MESSAGE.equals(key)) {
            return "Comment text appended to position reports";
        }
        if (PREF_ENCRYPTION_PASSPHRASE.equals(key)) {
            return "Same value on every radio that uses RF encryption";
        }
        if (PREF_MESH_BEACON_ENABLED.equals(key)) {
            return "Timed mesh position beacons (MeshCore transport)";
        }
        if (PREF_SA_RELAY_ENABLED.equals(key)) {
            return "Re-broadcast inbound network positions over radio to off-grid users. "
                    + "Throttled to one update per contact per 30 seconds.";
        }
        if (PREF_RF_TO_TAK_UPLINK_ENABLED.equals(key)) {
            return "When SA Relay is enabled, forward inbound RF CoT to TAK network. "
                    + "Use with care to avoid unintended rebroadcast loops.";
        }
        if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key)) {
            return "Unlock slot assignment controls for net leadership";
        }
        if (NetSlotConfig.PREF_SLOT_COUNT.equals(key)) {
            return "Ping-reply slots (default 20)";
        }
        if (NetSlotConfig.PREF_SLOT_TIME_SEC.equals(key)) {
            return "Seconds between slot start times (default 2.5 s)";
        }
        return null;
    }

    private String resolvePreferenceDisplayValue(String key) {
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : staticPluginContext;
        if (PREF_BEACON_INTERVAL.equals(key)) {
            if (ctx != null && SmartBeacon.isEnabled(ctx)) {
                return "Smart Beacon active";
            }
            String label = getListPreferenceValueLabel(key);
            if (label == null || label.isEmpty()) {
                SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
                label = prefs.getString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL) + " seconds";
            }
            return label;
        }
        if (SmartBeacon.KEY_LOW_SPEED.equals(key) && ctx != null) {
            return SmartBeacon.getLowSpeed(ctx) + " mph";
        }
        if (SmartBeacon.KEY_HIGH_SPEED.equals(key) && ctx != null) {
            return SmartBeacon.getHighSpeed(ctx) + " mph";
        }
        if (SmartBeacon.KEY_SLOW_RATE.equals(key) && ctx != null) {
            return SmartBeacon.getSlowRate(ctx) + " s";
        }
        if (SmartBeacon.KEY_FAST_RATE.equals(key) && ctx != null) {
            return SmartBeacon.getFastRate(ctx) + " s";
        }
        if (SmartBeacon.KEY_MIN_TURN_TIME.equals(key) && ctx != null) {
            return SmartBeacon.getMinTurnTime(ctx) + " s";
        }
        if (SmartBeacon.KEY_TURN_THRESHOLD.equals(key) && ctx != null) {
            return SmartBeacon.getTurnThreshold(ctx) + "°";
        }
        if (SmartBeacon.KEY_TURN_SLOPE.equals(key) && ctx != null) {
            return String.valueOf(SmartBeacon.getTurnSlope(ctx));
        }
        if (PREF_PING_REPLY_ENABLED.equals(key)) {
            return getCheckBoxPreferenceValueLabel(key, true);
        }
        if (PREF_MESH_BEACON_ENABLED.equals(key)) {
            return getCheckBoxPreferenceValueLabel(key, false);
        }
        if (PREF_SA_RELAY_ENABLED.equals(key)) {
            return getCheckBoxPreferenceValueLabel(key, false);
        }
        if (PREF_RF_TO_TAK_UPLINK_ENABLED.equals(key)) {
            return getCheckBoxPreferenceValueLabel(key, false);
        }
        if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key)) {
            return getCheckBoxPreferenceValueLabel(key, false);
        }
        if (PREF_RETRY_INTERVAL_MIN.equals(key) || PREF_RETRY_MAX.equals(key)
                || PREF_APRS_SSID.equals(key)) {
            return getListPreferenceValueLabel(key);
        }
        if (PREF_APRS_CALLSIGN.equals(key) || PREF_APRS_MESSAGE.equals(key)) {
            return emptyToNotSet(getEditTextPreferenceValue(key));
        }
        if (PREF_ENCRYPTION_PASSPHRASE.equals(key)) {
            return maskSecret(getEditTextPreferenceValue(key));
        }
        if (KEY_APRS_ICON.equals(key)) {
            return APRS_ICON_NOT_SET;
        }
        if (NetSlotConfig.PREF_SLOT_COUNT.equals(key) && ctx != null) {
            return String.valueOf(NetSlotConfig.getSlotCount(ctx));
        }
        if (NetSlotConfig.PREF_SLOT_TIME_SEC.equals(key) && ctx != null) {
            return String.format(java.util.Locale.US, "%.1f s", NetSlotConfig.getSlotTimeSec(ctx));
        }
        if (NetSlotConfig.PREF_SLOT_COUNT.equals(key)) {
            return getEditTextPreferenceValue(key);
        }
        if (NetSlotConfig.PREF_SLOT_TIME_SEC.equals(key)) {
            return getEditTextPreferenceValue(key);
        }
        return "";
    }

    private static String emptyToNotSet(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "(not set)";
        }
        return value.trim();
    }

    private static String maskSecret(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "(not set)";
        }
        return "••••••••";
    }

    private String getListPreferenceValueLabel(String key) {
        Preference pref = findPreference(key);
        if (!(pref instanceof ListPreference)) {
            return "";
        }
        ListPreference listPref = (ListPreference) pref;
        CharSequence entry = listPref.getEntry();
        if (entry != null && entry.length() > 0) {
            return entry.toString();
        }
        String value = listPref.getValue();
        if (value == null || value.isEmpty()) {
            value = getPreferenceManager().getSharedPreferences().getString(key, null);
        }
        CharSequence[] entries = listPref.getEntries();
        CharSequence[] values = listPref.getEntryValues();
        if (value != null && entries != null && values != null) {
            for (int i = 0; i < values.length; i++) {
                if (value.equals(String.valueOf(values[i]))) {
                    return String.valueOf(entries[i]);
                }
            }
        }
        return value != null ? value : "";
    }

    private String getEditTextPreferenceValue(String key) {
        Preference pref = findPreference(key);
        if (pref instanceof EditTextPreference) {
            String text = ((EditTextPreference) pref).getText();
            return text != null ? text : "";
        }
        return "";
    }

    private String getCheckBoxPreferenceValueLabel(String key, boolean defaultValue) {
        Preference pref = findPreference(key);
        if (pref instanceof CheckBoxPreference) {
            return ((CheckBoxPreference) pref).isChecked() ? "On" : "Off";
        }
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        return prefs.getBoolean(key, defaultValue) ? "On" : "Off";
    }

    private static boolean isSmartBeaconParamKey(String key) {
        return SmartBeacon.KEY_LOW_SPEED.equals(key)
                || SmartBeacon.KEY_HIGH_SPEED.equals(key)
                || SmartBeacon.KEY_SLOW_RATE.equals(key)
                || SmartBeacon.KEY_FAST_RATE.equals(key)
                || SmartBeacon.KEY_MIN_TURN_TIME.equals(key)
                || SmartBeacon.KEY_TURN_THRESHOLD.equals(key)
                || SmartBeacon.KEY_TURN_SLOPE.equals(key);
    }

    private void persistSmartBeaconFromPreferences() {
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : staticPluginContext;
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = getPrefs(ctx);
        int lowSpeed = readSmartBeaconInt(prefs, SmartBeacon.KEY_LOW_SPEED,
                SmartBeacon.DEFAULT_LOW_SPEED);
        int highSpeed = readSmartBeaconInt(prefs, SmartBeacon.KEY_HIGH_SPEED,
                SmartBeacon.DEFAULT_HIGH_SPEED);
        int slowRate = readSmartBeaconInt(prefs, SmartBeacon.KEY_SLOW_RATE,
                SmartBeacon.DEFAULT_SLOW_RATE);
        int fastRate = readSmartBeaconInt(prefs, SmartBeacon.KEY_FAST_RATE,
                SmartBeacon.DEFAULT_FAST_RATE);
        int minTurnTime = readSmartBeaconInt(prefs, SmartBeacon.KEY_MIN_TURN_TIME,
                SmartBeacon.DEFAULT_MIN_TURN_TIME);
        int turnThreshold = readSmartBeaconInt(prefs, SmartBeacon.KEY_TURN_THRESHOLD,
                SmartBeacon.DEFAULT_TURN_THRESHOLD);
        int turnSlope = readSmartBeaconInt(prefs, SmartBeacon.KEY_TURN_SLOPE,
                SmartBeacon.DEFAULT_TURN_SLOPE);

        if (highSpeed <= lowSpeed) {
            highSpeed = lowSpeed + 10;
        }
        if (fastRate >= slowRate) {
            fastRate = Math.max(1, slowRate / 2);
        }
        fastRate = Math.max(1, fastRate);
        slowRate = Math.max(fastRate + 1, slowRate);
        minTurnTime = Math.max(1, minTurnTime);
        turnThreshold = Math.max(1, turnThreshold);
        turnSlope = Math.max(0, turnSlope);

        SmartBeacon.saveAll(ctx, lowSpeed, highSpeed, slowRate, fastRate,
                minTurnTime, turnThreshold, turnSlope);
        syncSmartBeaconPreferenceValues();
    }

    private static int readSmartBeaconInt(SharedPreferences prefs, String key, int fallback) {
        try {
            return prefs.getInt(key, fallback);
        } catch (ClassCastException ignored) {
            try {
                return Integer.parseInt(prefs.getString(key, String.valueOf(fallback)).trim());
            } catch (Exception ignored2) {
                return fallback;
            }
        }
    }

    @Override
    public String getSubTitle() {
        return "UV-PRO Settings";
    }

    /**
     * Convenience: Get a preference value from any context.
     */
    /**
     * Get the ATAK-process SharedPreferences (uses ATAK context, not plugin context).
     */
    private static android.content.SharedPreferences getPrefs(Context context) {
        // Plugin context can't write to its own shared_prefs dir because it
        // runs inside ATAK's process. Always use ATAK's context.
        Context ctx = com.atakmap.android.maps.MapView.getMapView() != null
                ? com.atakmap.android.maps.MapView.getMapView().getContext()
                : context;
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static String getCallsign(Context context) {
        try {
            com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
            if (mv != null && mv.getSelfMarker() != null) {
                return mv.getSelfMarker().getMetaString("callsign", "UNKNOWN");
            }
        } catch (Exception e) {
        }
        return "UNKNOWN";
    }

    public static int getBeaconIntervalSec(Context context) {
        String val = getPrefs(context)
                .getString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL);
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    public static long getRetryIntervalMs(Context context) {
        String val = getPrefs(context)
                .getString(PREF_RETRY_INTERVAL_MIN, DEFAULT_RETRY_INTERVAL_MIN);
        try {
            return Long.parseLong(val) * 60_000L;
        } catch (NumberFormatException e) {
            return 2 * 60_000L;
        }
    }

    public static int getMaxChatRetries(Context context) {
        String val = getPrefs(context)
                .getString(PREF_RETRY_MAX, DEFAULT_RETRY_MAX);
        try {
            return Math.max(1, Integer.parseInt(val));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    /**
     * Use ATAK's team color ("locationTeam") rather than a plugin-managed setting, so
     * radio contacts match the operator's configured team consistently.
     */
    public static String getAtakTeamColor(Context context) {
        try {
            String team = ChatManagerMapComponent.getTeamName();
            if (team != null && !team.trim().isEmpty()) return team.trim();
        } catch (Exception ignored) {
        }
        try {
            com.atakmap.android.preference.AtakPreferences prefs =
                    com.atakmap.android.preference.AtakPreferences.getInstance(
                            com.atakmap.android.maps.MapView.getMapView() != null
                                    ? com.atakmap.android.maps.MapView.getMapView().getContext()
                                    : context);
            String team = prefs.get("locationTeam", "Cyan");
            if (team != null && !team.trim().isEmpty()) return team.trim();
        } catch (Exception ignored) {
        }
        return "Cyan";
    }

    public static boolean isSaRelayEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_SA_RELAY_ENABLED, false);
    }

    public static boolean isPingReplyEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_PING_REPLY_ENABLED, true);
    }

    /** Ping replies always TX on the transport that received the ping. */
    public static boolean isPingReplySameTransportEnabled(Context context) {
        return true;
    }

    public static boolean isMeshTransmitEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_ATAK_MESHCORE_TRANSMIT, false);
    }

    public static boolean isUvproTransmitEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_ATAK_UVPRO_TRANSMIT, false);
    }

    public static boolean isMeshBeaconEnabled(Context context) {
        if (context == null) {
            return false;
        }
        if (!AdminAccessGate.isUnlocked(context)) {
            return false;
        }
        return getPrefs(context).getBoolean(PREF_MESH_BEACON_ENABLED, false);
    }

    public static void setMeshBeaconEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        getPrefs(context).edit().putBoolean(PREF_MESH_BEACON_ENABLED, enabled).apply();
    }

    public static boolean isRfToTakUplinkEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_RF_TO_TAK_UPLINK_ENABLED, false);
    }

    public static boolean isRestrictChatToReachablePeers(Context context) {
        return false;
    }

    public static boolean isEncryptionEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_ENCRYPTION_ENABLED, false);
    }

    public static String getEncryptionPassphrase(Context context) {
        return getPrefs(context)
                .getString(PREF_ENCRYPTION_PASSPHRASE, "");
    }

    public static String getAprsCallsign(Context context) {
        String cs = getPrefs(context).getString(PREF_APRS_CALLSIGN, "");
        if (cs == null) {
            return "";
        }
        String out = cs.trim().toUpperCase(java.util.Locale.US);
        // Accept "CALL-SSID" input in FCC field by normalizing to base callsign.
        int dash = out.indexOf('-');
        if (dash > 0) {
            out = out.substring(0, dash);
        }
        return out;
    }

    public static int getAprsSsid(Context context) {
        String val = getPrefs(context).getString(PREF_APRS_SSID, "9");
        try {
            int ssid = Integer.parseInt(val);
            if (ssid < 0) {
                return 0;
            }
            if (ssid > 15) {
                return 15;
            }
            return ssid;
        } catch (NumberFormatException e) {
            return 9;
        }
    }

    public static boolean isAprsIconSelected(Context context) {
        return getPrefs(context).getBoolean(PREF_APRS_ICON_SELECTED, false);
    }

    public static char getAprsSymbolTable(Context context) {
        String s = getPrefs(context).getString(PREF_APRS_SYMBOL_TABLE, "/");
        if (s == null || s.isEmpty()) {
            return '/';
        }
        return s.charAt(0);
    }

    public static char getAprsSymbolCode(Context context) {
        String s = getPrefs(context).getString(PREF_APRS_SYMBOL_CODE, ">");
        if (s == null || s.isEmpty()) {
            return '>';
        }
        return s.charAt(0);
    }

    public static String getAprsMessage(Context context) {
        String m = getPrefs(context).getString(PREF_APRS_MESSAGE, "");
        return m != null ? m : "";
    }

    public static boolean isAprsTxArmed(Context context) {
        return getPrefs(context).getBoolean(PREF_APRS_TX_ARMED, false);
    }

    public static void setAprsTxArmed(Context context, boolean armed) {
        getPrefs(context).edit().putBoolean(PREF_APRS_TX_ARMED, armed).apply();
    }

    public static boolean isAprsDisableAtakTraffic(Context context) {
        return getPrefs(context).getBoolean(PREF_APRS_DISABLE_ATAK_TRAFFIC, false);
    }

    public static void setAprsDisableAtakTraffic(Context context, boolean disabled) {
        getPrefs(context).edit().putBoolean(PREF_APRS_DISABLE_ATAK_TRAFFIC, disabled).apply();
    }

    /** Ham base call: 1 letter + digit + 1–3 letters, or 2 letters + digit + 1–3 letters. */
    public static boolean isValidAprsCallsign(String baseCall) {
        if (baseCall == null) {
            return false;
        }
        String c = baseCall.trim().toUpperCase(java.util.Locale.US);
        return c.matches("^[A-Z][0-9][A-Z]{1,3}$")
                || c.matches("^[A-Z]{2}[0-9][A-Z]{1,3}$");
    }

    public static String formatAprsDisplayCall(Context context) {
        String base = getAprsCallsign(context);
        if (!isValidAprsCallsign(base)) {
            return "(not set)";
        }
        int ssid = getAprsSsid(context);
        return ssid > 0 ? base + "-" + ssid : base;
    }

    /**
     * Opens ATAK Tool Preferences for this plugin (Settings → Tool Preferences → UV-PRO).
     */
    public static void openToolPreferences(Context context) {
        launchPluginSettings(TOOL_SETTINGS_KEY, null, context);
    }

    /**
     * Opens plugin Tool Preferences scrolled to Beacon Settings.
     */
    public static void openRadioSettings(Context context) {
        launchPluginSettings(TOOL_SETTINGS_KEY, KEY_CAT_BEACON, context);
    }

    /**
     * Opens plugin settings scrolled to the APRS category.
     */
    public static void openAprsSettings(Context context) {
        launchPluginSettings(TOOL_SETTINGS_KEY, KEY_CAT_APRS, context);
    }

    private static void launchPluginSettings(String toolKey, String prefKey, Context context) {
        try {
            SettingsActivity.start(toolKey, prefKey);
        } catch (Exception e) {
            android.util.Log.w("UVPro.Settings", "launchPluginSettings failed: " + e.getMessage());
            if (context != null) {
                try {
                    android.content.Intent intent = new android.content.Intent(
                            "com.atakmap.app.ADVANCED_SETTINGS");
                    intent.putExtra("toolkey", toolKey);
                    if (prefKey != null) {
                        intent.putExtra("prefkey", prefKey);
                    }
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    AtakBroadcast.getInstance().sendBroadcast(intent);
                } catch (Exception e2) {
                    Toast.makeText(context,
                            "Open Settings → Tool Preferences → UV-PRO Settings",
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /**
     * APRS outbound fields for the in-panel Plugin Settings dialog.
     */
    public static final class AprsSettingsUi {
        public EditText editCallsign;
        public Spinner spinnerSsid;
        public ImageView iconPreview;
        public TextView iconNotSet;
        public EditText editMessage;
        public String[] ssidValues;
    }

    /**
     * Adds APRS section to the Plugin Settings dialog (same window as Actions → Plugin Settings).
     */
    public static AprsSettingsUi appendAprsSettingsSection(Context mapCtx, Context pluginCtx,
                                                           LinearLayout layout) {
        AprsSettingsUi ui = new AprsSettingsUi();
        if (mapCtx == null || layout == null) {
            return ui;
        }

        TextView header = new TextView(mapCtx);
        header.setText("\nAPRS");
        header.setTextColor(0xFF00BCD4);
        header.setTextSize(14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(header);

        TextView labelCall = new TextView(mapCtx);
        labelCall.setText("FCC Call Sign");
        labelCall.setTextColor(0xFFAAAAAA);
        layout.addView(labelCall);
        ui.editCallsign = new EditText(mapCtx);
        ui.editCallsign.setText(getAprsCallsign(mapCtx));
        ui.editCallsign.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        ui.editCallsign.setTextColor(0xFFFFFFFF);
        layout.addView(ui.editCallsign);

        TextView labelSsid = new TextView(mapCtx);
        labelSsid.setText("\nAPRS suffix (SSID)");
        labelSsid.setTextColor(0xFFAAAAAA);
        layout.addView(labelSsid);
        ui.spinnerSsid = new Spinner(mapCtx);
        if (pluginCtx != null) {
            android.content.res.Resources res = pluginCtx.getResources();
            String pkg = pluginCtx.getPackageName();
            int labelsId = res.getIdentifier("aprs_ssid_labels", "array", pkg);
            int valuesId = res.getIdentifier("aprs_ssid_values", "array", pkg);
            if (labelsId != 0 && valuesId != 0) {
                String[] labels = res.getStringArray(labelsId);
                ui.ssidValues = res.getStringArray(valuesId);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(mapCtx,
                        android.R.layout.simple_spinner_item, labels) {
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        View v = super.getView(position, convertView, parent);
                        if (v instanceof TextView) {
                            TextView tv = (TextView) v;
                            tv.setTextColor(COLOR_WHITE);
                            tv.setBackgroundColor(COLOR_STD_BLUE);
                            tv.setPadding(dp(mapCtx, 10), dp(mapCtx, 8),
                                    dp(mapCtx, 10), dp(mapCtx, 8));
                        }
                        return v;
                    }

                    @Override
                    public View getDropDownView(int position, View convertView, ViewGroup parent) {
                        View v = super.getDropDownView(position, convertView, parent);
                        if (v instanceof TextView) {
                            TextView tv = (TextView) v;
                            tv.setTextColor(COLOR_WHITE);
                            tv.setBackgroundColor(COLOR_STD_BLUE);
                            tv.setPadding(dp(mapCtx, 12), dp(mapCtx, 10),
                                    dp(mapCtx, 12), dp(mapCtx, 10));
                        }
                        return v;
                    }
                };
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                ui.spinnerSsid.setAdapter(adapter);
                ui.spinnerSsid.setBackgroundColor(COLOR_STD_BLUE);
                int ssid = getAprsSsid(mapCtx);
                if (ssid >= 0 && ssid < labels.length) {
                    ui.spinnerSsid.setSelection(ssid);
                }
            }
        }
        layout.addView(ui.spinnerSsid);

        TextView labelIcon = new TextView(mapCtx);
        labelIcon.setText("\nAPRS icon");
        labelIcon.setTextColor(0xFFAAAAAA);
        layout.addView(labelIcon);

        LinearLayout iconRow = new LinearLayout(mapCtx);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        ui.iconPreview = new ImageView(mapCtx);
        int iconPx = (int) (40 * mapCtx.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconPx, iconPx);
        iconLp.setMargins(0, 0, 16, 0);
        ui.iconPreview.setLayoutParams(iconLp);
        ui.iconPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconRow.addView(ui.iconPreview);
        ui.iconNotSet = new TextView(mapCtx);
        ui.iconNotSet.setText("(not set)");
        ui.iconNotSet.setTextColor(0xFF888888);
        ui.iconNotSet.setTextSize(12);
        iconRow.addView(ui.iconNotSet);
        layout.addView(iconRow);

        Button btnPickIcon = new Button(mapCtx);
        btnPickIcon.setText("Choose APRS Icon");
        btnPickIcon.setTextColor(COLOR_WHITE);
        btnPickIcon.setBackgroundColor(COLOR_STD_BLUE);
        btnPickIcon.setOnClickListener(v ->
                com.uvpro.plugin.aprs.AprsIconPickerDialog.show(
                        mapCtx, pluginCtx, () ->
                        refreshAprsIconPreviewInDialog(mapCtx, pluginCtx, ui)));
        layout.addView(btnPickIcon);
        refreshAprsIconPreviewInDialog(mapCtx, pluginCtx, ui);

        TextView labelMsg = new TextView(mapCtx);
        labelMsg.setText("\nAPRS message (comment on position)");
        labelMsg.setTextColor(0xFFAAAAAA);
        layout.addView(labelMsg);
        ui.editMessage = new EditText(mapCtx);
        ui.editMessage.setText(getAprsMessage(mapCtx));
        ui.editMessage.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        ui.editMessage.setTextColor(0xFFFFFFFF);
        layout.addView(ui.editMessage);

        return ui;
    }

    private static void refreshAprsIconPreviewInDialog(Context mapCtx, Context pluginCtx,
                                                         AprsSettingsUi ui) {
        if (ui == null || ui.iconPreview == null || ui.iconNotSet == null) {
            return;
        }
        if (!isAprsIconSelected(mapCtx)) {
            ui.iconPreview.setVisibility(View.GONE);
            ui.iconPreview.setImageDrawable(null);
            ui.iconNotSet.setVisibility(View.VISIBLE);
            return;
        }
        android.graphics.Bitmap bmp = com.uvpro.plugin.aprs.AprsIconPreviewLoader
                .loadSelectedIconBitmap(mapCtx, pluginCtx);
        if (bmp != null) {
            ui.iconPreview.setImageBitmap(bmp);
            ui.iconPreview.setVisibility(View.VISIBLE);
            ui.iconNotSet.setVisibility(View.GONE);
        } else {
            ui.iconPreview.setVisibility(View.GONE);
            ui.iconNotSet.setVisibility(View.VISIBLE);
            ui.iconNotSet.setText(APRS_ICON_NOT_SET);
        }
    }

    public static void saveAprsSettingsFromUi(Context ctx, AprsSettingsUi ui) {
        if (ctx == null || ui == null) {
            return;
        }
        SharedPreferences.Editor editor = getPrefs(ctx).edit();
        if (ui.editCallsign != null) {
            editor.putString(PREF_APRS_CALLSIGN,
                    ui.editCallsign.getText().toString().trim().toUpperCase(java.util.Locale.US));
        }
        if (ui.spinnerSsid != null && ui.ssidValues != null) {
            int pos = ui.spinnerSsid.getSelectedItemPosition();
            if (pos >= 0 && pos < ui.ssidValues.length) {
                editor.putString(PREF_APRS_SSID, ui.ssidValues[pos]);
            }
        }
        if (ui.editMessage != null) {
            editor.putString(PREF_APRS_MESSAGE, ui.editMessage.getText().toString());
        }
        editor.apply();
    }

    /**
     * Administration block for ping-reply slot net config (Tools prefs or plugin dialog).
     */
    public static final class AdministrationUi {
        public Switch saRelayEnabled;
        public Switch rfToTakUplinkEnabled;
        public Switch adminEnabled;
        public Switch meshBeaconEnabled;
        public EditText editSlotCount;
        public EditText editSlotTime;
        public Button btnDistribute;
        public TextView currentStatus;
        View[] gatedViews;
    }

    /** Adds unlock prompt when administrative settings are locked. */
    public static void appendAdministrativeUnlockPrompt(Context ctx, LinearLayout layout,
                                                        Runnable onUnlocked) {
        TextView header = new TextView(ctx);
        header.setText("\nAdministrative Settings");
        header.setTextColor(0xFF00BCD4);
        header.setTextSize(14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(header);

        Button unlockBtn = new Button(ctx);
        unlockBtn.setText("Unlock Administrative Settings");
        unlockBtn.setTextColor(COLOR_WHITE);
        unlockBtn.setBackgroundColor(COLOR_STD_BLUE);
        unlockBtn.setOnClickListener(v ->
                promptAdministrativeUnlock(ctx, onUnlocked));
        layout.addView(unlockBtn);
    }

    /** Adds Administration section views to a scrollable dialog layout. */
    public static AdministrationUi appendAdministrationSection(Context ctx, LinearLayout layout) {
        if (!AdminAccessGate.isUnlocked(ctx)) {
            return null;
        }
        AdministrationUi ui = new AdministrationUi();

        TextView header = new TextView(ctx);
        header.setText("\nAdministrative Settings");
        header.setTextColor(0xFF00BCD4);
        header.setTextSize(14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(header);

        TextView warning = new TextView(ctx);
        warning.setText("For Team Leadership ONLY — Do not use unless directed by higher");
        warning.setTextColor(0xFFFFFFFF);
        warning.setTextSize(12);
        warning.setTypeface(Typeface.DEFAULT_BOLD);
        warning.setPadding(0, 8, 0, 8);
        layout.addView(warning);

        TextView meshBeaconWarning = new TextView(ctx);
        meshBeaconWarning.setText(
                "Mesh Beacon: activated by national only. Do not use unless directed by your team leader.");
        meshBeaconWarning.setTextColor(0xFFFFFFFF);
        meshBeaconWarning.setTextSize(12);
        meshBeaconWarning.setTypeface(Typeface.DEFAULT_BOLD);
        meshBeaconWarning.setPadding(0, 4, 0, 8);
        layout.addView(meshBeaconWarning);

        LinearLayout rowMeshBeacon = new LinearLayout(ctx);
        rowMeshBeacon.setOrientation(LinearLayout.HORIZONTAL);
        rowMeshBeacon.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelMeshBeacon = new TextView(ctx);
        labelMeshBeacon.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelMeshBeacon.setText("Enable Mesh Beacon");
        labelMeshBeacon.setTextColor(0xFFFFFFFF);
        labelMeshBeacon.setTextSize(13);
        rowMeshBeacon.addView(labelMeshBeacon);
        ui.meshBeaconEnabled = new Switch(ctx);
        ui.meshBeaconEnabled.setChecked(isMeshBeaconEnabled(ctx));
        rowMeshBeacon.addView(ui.meshBeaconEnabled);
        layout.addView(rowMeshBeacon);

        TextView headerSaRelay = new TextView(ctx);
        headerSaRelay.setText("\nSA Relay");
        headerSaRelay.setTextColor(COLOR_STD_BLUE);
        headerSaRelay.setTextSize(16);
        headerSaRelay.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(headerSaRelay);

        TextView saRelayDescription = new TextView(ctx);
        saRelayDescription.setText("Re-broadcast network positions over radio");
        saRelayDescription.setTextColor(COLOR_WHITE);
        saRelayDescription.setTextSize(13);
        saRelayDescription.setPadding(0, 2, 0, 8);
        layout.addView(saRelayDescription);

        LinearLayout rowSaRelay = new LinearLayout(ctx);
        rowSaRelay.setOrientation(LinearLayout.HORIZONTAL);
        rowSaRelay.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelSaRelay = new TextView(ctx);
        labelSaRelay.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelSaRelay.setText("Enable SA Relay");
        labelSaRelay.setTextColor(0xFFFFFFFF);
        labelSaRelay.setTextSize(13);
        rowSaRelay.addView(labelSaRelay);
        ui.saRelayEnabled = new Switch(ctx);
        ui.saRelayEnabled.setChecked(isSaRelayEnabled(ctx));
        rowSaRelay.addView(ui.saRelayEnabled);
        layout.addView(rowSaRelay);

        LinearLayout rowRfToTak = new LinearLayout(ctx);
        rowRfToTak.setOrientation(LinearLayout.HORIZONTAL);
        rowRfToTak.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelRfToTak = new TextView(ctx);
        labelRfToTak.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelRfToTak.setText("Enable RF to TAK Uplink Relay");
        labelRfToTak.setTextColor(0xFFFFFFFF);
        labelRfToTak.setTextSize(13);
        rowRfToTak.addView(labelRfToTak);
        ui.rfToTakUplinkEnabled = new Switch(ctx);
        ui.rfToTakUplinkEnabled.setChecked(isRfToTakUplinkEnabled(ctx));
        ui.rfToTakUplinkEnabled.setEnabled(ui.saRelayEnabled.isChecked());
        rowRfToTak.addView(ui.rfToTakUplinkEnabled);
        layout.addView(rowRfToTak);
        ui.saRelayEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (ui.rfToTakUplinkEnabled != null) {
                ui.rfToTakUplinkEnabled.setEnabled(isChecked);
                if (!isChecked) {
                    ui.rfToTakUplinkEnabled.setChecked(false);
                }
            }
        });

        TextView hintSaRelay = new TextView(ctx);
        hintSaRelay.setText(
                "Throttled: one update per contact per 30 s. Requires TAK server + radio connected.");
        hintSaRelay.setTextColor(0xFF888888);
        hintSaRelay.setTextSize(11);
        hintSaRelay.setPadding(0, 2, 0, 0);
        layout.addView(hintSaRelay);

        TextView hintRfToTakUplink = new TextView(ctx);
        hintRfToTakUplink.setText(
                "Forwards RF CoT to TAK network. Active only when SA Relay is enabled.");
        hintRfToTakUplink.setTextColor(0xFF888888);
        hintRfToTakUplink.setTextSize(11);
        hintRfToTakUplink.setPadding(0, 2, 0, 12);
        layout.addView(hintRfToTakUplink);

        LinearLayout rowAdmin = new LinearLayout(ctx);
        rowAdmin.setOrientation(LinearLayout.HORIZONTAL);
        rowAdmin.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelAdmin = new TextView(ctx);
        labelAdmin.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelAdmin.setText("Enable administrative settings");
        labelAdmin.setTextColor(0xFFFFFFFF);
        labelAdmin.setTextSize(13);
        rowAdmin.addView(labelAdmin);
        ui.adminEnabled = new Switch(ctx);
        ui.adminEnabled.setChecked(getPrefs(ctx).getBoolean(
                NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false));
        rowAdmin.addView(ui.adminEnabled);
        layout.addView(rowAdmin);

        TextView labelSlots = new TextView(ctx);
        labelSlots.setText("\nSlot count");
        labelSlots.setTextColor(0xFFAAAAAA);
        layout.addView(labelSlots);
        ui.editSlotCount = new EditText(ctx);
        ui.editSlotCount.setText(String.valueOf(NetSlotConfig.getSlotCount(ctx)));
        ui.editSlotCount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        ui.editSlotCount.setTextColor(0xFFFFFFFF);
        layout.addView(ui.editSlotCount);

        TextView labelTime = new TextView(ctx);
        labelTime.setText("Slot time (seconds)");
        labelTime.setTextColor(0xFFAAAAAA);
        layout.addView(labelTime);
        ui.editSlotTime = new EditText(ctx);
        ui.editSlotTime.setText(String.valueOf(NetSlotConfig.getSlotTimeSec(ctx)));
        ui.editSlotTime.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ui.editSlotTime.setTextColor(0xFFFFFFFF);
        layout.addView(ui.editSlotTime);

        TextView distributeWarning = new TextView(ctx);
        distributeWarning.setText(DISTRIBUTE_NET_WARNING);
        distributeWarning.setTextColor(COLOR_WARNING_RED);
        distributeWarning.setTextSize(13);
        distributeWarning.setTypeface(Typeface.DEFAULT_BOLD);
        distributeWarning.setPadding(0, 12, 0, 8);
        layout.addView(distributeWarning);

        ui.btnDistribute = new Button(ctx);
        ui.btnDistribute.setText("Distribute to net");
        ui.btnDistribute.setTextColor(COLOR_WHITE);
        ui.btnDistribute.setBackgroundColor(COLOR_STD_BLUE);
        ui.btnDistribute.setOnClickListener(v -> distributeNetSlotsFromUi(ctx, ui));
        layout.addView(ui.btnDistribute);

        ui.currentStatus = new TextView(ctx);
        ui.currentStatus.setTextColor(0xFF00BCD4);
        ui.currentStatus.setTextSize(12);
        ui.currentStatus.setPadding(0, 4, 0, 8);
        layout.addView(ui.currentStatus);

        ui.gatedViews = new View[]{
                ui.editSlotCount, ui.editSlotTime, ui.btnDistribute
        };
        ui.adminEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                applyAdministrationGating(ui, isChecked));
        applyAdministrationGating(ui, ui.adminEnabled.isChecked());
        refreshAdministrationStatus(ctx, ui);
        return ui;
    }

    public static void saveAdministrationFromUi(Context ctx, AdministrationUi ui) {
        if (ctx == null || ui == null) {
            return;
        }
        SharedPreferences prefs = getPrefs(ctx);
        prefs.edit()
                .putBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED,
                        ui.adminEnabled.isChecked())
                .putBoolean(PREF_MESH_BEACON_ENABLED,
                        ui.meshBeaconEnabled != null && ui.meshBeaconEnabled.isChecked())
                .putBoolean(PREF_SA_RELAY_ENABLED,
                        ui.saRelayEnabled != null && ui.saRelayEnabled.isChecked())
                .putBoolean(PREF_RF_TO_TAK_UPLINK_ENABLED,
                        ui.rfToTakUplinkEnabled != null && ui.rfToTakUplinkEnabled.isChecked())
                .apply();
        try {
            int slots = Integer.parseInt(ui.editSlotCount.getText().toString().trim());
            float sec = Float.parseFloat(ui.editSlotTime.getText().toString().trim());
            NetSlotConfig.saveLocalSlotSettings(ctx, slots, sec);
        } catch (Exception ignored) {
            NetSlotConfig.saveLocalSlotSettings(ctx,
                    NetSlotConfig.DEFAULT_SLOT_COUNT,
                    NetSlotConfig.DEFAULT_SLOT_TIME_SEC);
        }
    }

    public static void refreshAdministrationStatus(Context ctx, AdministrationUi ui) {
        if (ctx == null || ui == null || ui.currentStatus == null) {
            return;
        }
        int slots = NetSlotConfig.getSlotCount(ctx);
        float sec = NetSlotConfig.getSlotTimeSec(ctx);
        String status = String.format(java.util.Locale.US,
                "Currently set on this device: %d slots, %.1f s slot time", slots, sec);
        SharedPreferences prefs = getPrefs(ctx);
        String issuer = prefs.getString(NetSlotConfig.PREF_LAST_NET_SLOT_ISSUER, "");
        int seq = prefs.getInt(NetSlotConfig.PREF_NET_SLOT_CONFIG_SEQ, 0);
        if (seq > 0 && issuer != null && !issuer.isEmpty()) {
            status += "\nLast net update from " + issuer;
        }
        ui.currentStatus.setText(status);
    }

    private static void applyAdministrationGating(AdministrationUi ui, boolean enabled) {
        if (ui.gatedViews == null) {
            return;
        }
        float alpha = enabled ? 1f : 0.38f;
        for (View v : ui.gatedViews) {
            if (v != null) {
                v.setEnabled(enabled);
                v.setAlpha(alpha);
            }
        }
    }

    private static void distributeNetSlotsFromUi(Context ctx, AdministrationUi ui) {
        if (!ui.adminEnabled.isChecked()) {
            Toast.makeText(ctx, "Enable administrative settings first", Toast.LENGTH_LONG).show();
            return;
        }
        saveAdministrationFromUi(ctx, ui);
        String error = distributeNetSlotsOrError(ctx);
        if (error == null) {
            int slots = NetSlotConfig.getSlotCount(ctx);
            float sec = NetSlotConfig.getSlotTimeSec(ctx);
            Toast.makeText(ctx,
                    "Slot config sent (" + slots + " slots, " + sec + " s)",
                    Toast.LENGTH_LONG).show();
            refreshAdministrationStatus(ctx, ui);
        } else {
            Toast.makeText(ctx, error, Toast.LENGTH_LONG).show();
        }
    }

    private static int dp(Context ctx, int value) {
        if (ctx == null) {
            return value;
        }
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
