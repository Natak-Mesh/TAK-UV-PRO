package com.uvpro.plugin.contacts;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import com.atakmap.android.chat.GeoChatConnector;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.IpConnector;
import com.atakmap.android.contact.PluginConnector;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.comms.NetConnectString;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.uvpro.plugin.chat.GeoChatContactListHelper;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.network.WifiContactKeepalive;
import com.uvpro.plugin.ui.SettingsFragment;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Classifies peers as GeoChat-reachable from this device and applies comms connector policy.
 * Map markers / SA are unaffected — only chat/comms default actions are restricted.
 */
public final class ContactReachability {

    private static final String TAG = "UVPro.Reachability";
    private static final String ALL_CHAT_ROOMS = "All Chat Rooms";
    private static final Pattern OPAQUE_WIFI_DEVICE_UID =
            Pattern.compile("^[0-9A-F]{16}$");

    private ContactReachability() {
    }

    public static boolean isPolicyEnabled(Context context) {
        if (context == null) {
            return true;
        }
        return SettingsFragment.isRestrictChatToReachablePeers(context);
    }

    public static boolean isGeoChatReachable(IndividualContact contact, CotBridge bridge) {
        if (contact == null) {
            return false;
        }
        return hasWifiChatPath(contact) || hasRfChatPath(contact, bridge);
    }

    public static boolean hasWifiChatPath(IndividualContact contact) {
        if (contact == null || !isLocalWifiAvailable()) {
            return false;
        }
        return resolveRoutableNetworkEndpoint(contact) != null;
    }

    public static boolean hasRfChatPath(IndividualContact contact, CotBridge bridge) {
        if (contact == null || bridge == null || !bridge.isRadioConnected()) {
            return false;
        }
        String uid = contact.getUID();
        return uid != null && bridge.isBtechContactUid(uid.trim());
    }

    public static boolean isLocalWifiAvailable() {
        try {
            String endpoint = CotMapComponent.getEndpoint();
            return endpoint != null && !endpoint.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * SA Relay (network → RF): skip Wi‑Fi-only peers; keep RF-registered / dual-net contacts.
     */
    public static boolean shouldSaRelayNetworkSa(CotEvent event, CotBridge bridge) {
        if (event == null) {
            return false;
        }
        String uid = event.getUID();
        if (uid == null || uid.trim().isEmpty()) {
            return false;
        }
        if (bridge != null && bridge.isBtechContactUid(uid.trim())) {
            return true;
        }
        String endpoint = extractCotContactEndpoint(event);
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            Log.d(TAG, "SA Relay skip WiFi-only uid=" + uid + " endpoint=" + endpoint);
            return false;
        }
        if (isOpaqueWifiDeviceUid(uid)) {
            Log.d(TAG, "SA Relay skip opaque WiFi uid=" + uid);
            return false;
        }
        return true;
    }

    /**
     * Ignore directed network GeoChat not addressed to this device (bridge overhear).
     */
    public static boolean isInboundNetworkGeoChatForLocalDevice(CotEvent event) {
        if (event == null || !"b-t-f".equals(event.getType())) {
            return false;
        }
        if (GeoChatContactListHelper.isAllChatRoomsGeoChat(event)) {
            return true;
        }
        String selfUid = safeSelfUid();
        if (selfUid.isEmpty()) {
            return true;
        }
        String senderUid = GeoChatContactListHelper.extractChatSenderUid(event);
        if (selfUid.equalsIgnoreCase(senderUid)) {
            return false;
        }
        String peerThread = extractGeoChatPeerThreadUid(event);
        if (!peerThread.isEmpty() && selfUid.equalsIgnoreCase(peerThread)) {
            return true;
        }
        String remarksTo = extractGeoChatRemarksTo(event);
        if (!remarksTo.isEmpty() && selfUid.equalsIgnoreCase(remarksTo)) {
            return true;
        }
        Log.d(TAG, "Skip overheard network GeoChat self=" + selfUid
                + " thread=" + peerThread + " to=" + remarksTo
                + " from=" + senderUid);
        return false;
    }

    public static void applyAllContactCommsPolicies(CotBridge bridge) {
        Context ctx = resolveContext();
        if (!isPolicyEnabled(ctx)) {
            return;
        }
        try {
            Contacts contacts = Contacts.getInstance();
            List<Contact> all = contacts.getAllContacts();
            if (all == null) {
                return;
            }
            int updated = 0;
            for (Contact c : all) {
                if (!(c instanceof IndividualContact)) {
                    continue;
                }
                if (applyContactCommsPolicy((IndividualContact) c, bridge)) {
                    updated++;
                }
            }
            if (updated > 0) {
                Log.i(TAG, "Applied comms reachability policy to " + updated + " contact(s)");
            }
        } catch (Exception e) {
            Log.w(TAG, "applyAllContactCommsPolicies failed", e);
        }
    }

    /**
     * @return true when connectors or prefs were changed
     */
    public static boolean applyContactCommsPolicy(IndividualContact contact, CotBridge bridge) {
        Context ctx = resolveContext();
        if (contact == null || !isPolicyEnabled(ctx)) {
            return false;
        }
        if (isGeoChatReachable(contact, bridge)) {
            return applyReachableCommsPolicy(contact, bridge);
        }
        return applyChatUnreachablePolicy(contact);
    }

    private static boolean applyReachableCommsPolicy(IndividualContact contact, CotBridge bridge) {
        if (hasWifiChatPath(contact)) {
            return com.uvpro.plugin.chat.ChatBridge.preferNativeContactActionInternal(contact);
        }
        if (hasRfChatPath(contact, bridge)) {
            return applyRfChatPolicy(contact);
        }
        return false;
    }

    private static boolean applyRfChatPolicy(IndividualContact contact) {
        try {
            MapView mv = MapView.getMapView();
            if (mv == null) {
                return false;
            }
            AtakPreferences prefs = new AtakPreferences(mv.getContext());
            String uid = contact.getUID();
            clearDefaultConnectorPref(prefs, uid);
            contact.removeConnector(GeoChatConnector.CONNECTOR_TYPE);
            contact.removeConnector(IpConnector.CONNECTOR_TYPE);
            if (contact.getConnector(PluginConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new PluginConnector(
                        com.uvpro.plugin.UVProContactHandler.PLUGIN_GEOCHAT_ACTION));
            }
            writeDefaultConnectorPref(prefs, uid, PluginConnector.CONNECTOR_TYPE);
            contact.dispatchChangeEvent();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "applyRfChatPolicy failed uid="
                    + (contact != null ? contact.getUID() : "?"), e);
            return false;
        }
    }

    static boolean applyChatUnreachablePolicy(IndividualContact contact) {
        try {
            MapView mv = MapView.getMapView();
            if (mv == null) {
                return false;
            }
            AtakPreferences prefs = new AtakPreferences(mv.getContext());
            String uid = contact.getUID();
            clearDefaultConnectorPref(prefs, uid);
            contact.removeConnector(GeoChatConnector.CONNECTOR_TYPE);
            contact.removeConnector(IpConnector.CONNECTOR_TYPE);
            if (contact.getConnector(PluginConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new PluginConnector(
                        com.uvpro.plugin.UVProContactHandler.PLUGIN_GEOCHAT_ACTION));
            }
            writeDefaultConnectorPref(prefs, uid, PluginConnector.CONNECTOR_TYPE);
            contact.dispatchChangeEvent();
            Log.d(TAG, "Chat unreachable uid=" + uid + " callsign=" + contact.getName());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "applyChatUnreachablePolicy failed uid="
                    + (contact != null ? contact.getUID() : "?"), e);
            return false;
        }
    }

    public static void handleUnreachableContactSelected(Context context, IndividualContact contact) {
        String callsign = contact != null && contact.getName() != null
                ? contact.getName().trim() : "Contact";
        Context ctx = context != null ? context : resolveContext();
        if (ctx != null) {
            Toast.makeText(ctx,
                    callsign + " — position only (not reachable for GeoChat from this device)",
                    Toast.LENGTH_LONG).show();
        }
        focusContactOnMap(contact);
    }

    private static void focusContactOnMap(IndividualContact contact) {
        if (contact == null) {
            return;
        }
        try {
            MapView mv = MapView.getMapView();
            if (mv == null || mv.getRootGroup() == null) {
                return;
            }
            MapItem item = contact.getMapItem();
            if (item == null && contact.getUID() != null) {
                item = mv.getRootGroup().deepFindUID(contact.getUID());
            }
            if (item instanceof PointMapItem) {
                mv.getMapController().panTo(((PointMapItem) item).getPoint(), true);
            }
        } catch (Exception ignored) {
        }
    }

    static NetConnectString resolveRoutableNetworkEndpoint(IndividualContact contact) {
        if (contact == null) {
            return null;
        }
        NetConnectString ncs = parseRoutableEndpoint(contact.getConnector(IpConnector.CONNECTOR_TYPE));
        if (ncs != null) {
            return ncs;
        }
        return parseRoutableEndpoint(contact.getConnector(GeoChatConnector.CONNECTOR_TYPE));
    }

    private static NetConnectString parseRoutableEndpoint(com.atakmap.android.contact.Connector connector) {
        if (connector == null) {
            return null;
        }
        String cs = connector.getConnectionString();
        if (cs == null || cs.trim().isEmpty()) {
            return null;
        }
        NetConnectString ncs = NetConnectString.fromString(cs.trim());
        return WifiContactKeepalive.isRoutableEndpoint(ncs) ? ncs : null;
    }

    static String extractCotContactEndpoint(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return null;
        }
        try {
            CotDetail contact = event.getDetail().getFirstChildByName(0, "contact");
            if (contact == null) {
                return null;
            }
            String endpoint = contact.getAttribute("endpoint");
            return endpoint != null ? endpoint.trim() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static String extractGeoChatPeerThreadUid(CotEvent event) {
        CotDetail chat = GeoChatContactListHelper.findChatDetailPublic(event);
        if (chat == null) {
            return "";
        }
        CotDetail chatgrp = chat.getFirstChildByName(0, "chatgrp");
        if (chatgrp == null) {
            chatgrp = chat.getFirstChildByName(0, "chatGroup");
        }
        if (chatgrp != null) {
            String uid1 = chatgrp.getAttribute("uid1");
            if (uid1 != null && !uid1.trim().isEmpty()) {
                return uid1.trim();
            }
        }
        String id = chat.getAttribute("id");
        return id != null ? id.trim() : "";
    }

    private static String extractGeoChatRemarksTo(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return "";
        }
        try {
            CotDetail remarks = event.getDetail().getFirstChildByName(0, "remarks");
            if (remarks == null) {
                return "";
            }
            String to = remarks.getAttribute("to");
            return to != null ? to.trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    static boolean isOpaqueWifiDeviceUid(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return false;
        }
        String upper = uid.trim().toUpperCase(Locale.US);
        if (upper.startsWith("ANDROID-")) {
            upper = upper.substring("ANDROID-".length());
        }
        return OPAQUE_WIFI_DEVICE_UID.matcher(upper).matches();
    }

    private static String safeSelfUid() {
        try {
            String uid = MapView.getDeviceUid();
            return uid != null ? uid.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static Context resolveContext() {
        try {
            MapView mv = MapView.getMapView();
            if (mv != null && mv.getContext() != null) {
                return mv.getContext();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void writeDefaultConnectorPref(AtakPreferences prefs, String contactUid,
                                                    String connectorType) {
        if (prefs == null || contactUid == null || contactUid.trim().isEmpty()) {
            return;
        }
        String trimmed = contactUid.trim();
        prefs.set("contact.connector.default." + trimmed, connectorType);
        prefs.set("contact.connector.default." + trimmed.toUpperCase(Locale.US), connectorType);
        prefs.set("contact.connector.default." + trimmed.toLowerCase(Locale.US), connectorType);
    }

    private static void clearDefaultConnectorPref(AtakPreferences prefs, String contactUid) {
        writeDefaultConnectorPref(prefs, contactUid, "");
    }
}
