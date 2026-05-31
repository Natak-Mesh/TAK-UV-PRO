package com.uvpro.plugin.contacts;

import com.atakmap.android.contact.Connector;
import com.uvpro.plugin.R;

/**
 * Contact-card action to favorite a MeshCore-discovered contact.
 */
public final class MeshFavoriteConnector extends Connector {

    public static final String CONNECTOR_TYPE = "connector.uvpro.mesh_favorite";
    public static final String CONTACT_ACTION =
            "com.uvpro.plugin.action.MESH_FAVORITE_CONTACT";
    private static final String PACKAGE = "com.uvpro.plugin";

    @Override
    public String getConnectionString() {
        return CONTACT_ACTION;
    }

    @Override
    public String getConnectionType() {
        return CONNECTOR_TYPE;
    }

    @Override
    public String getConnectionLabel() {
        return "Favorite";
    }

    @Override
    public String getIconUri() {
        return "android.resource://" + PACKAGE + "/" + R.drawable.ic_uvpro;
    }
}
