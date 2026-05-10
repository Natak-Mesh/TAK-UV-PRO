package com.uvpro.plugin;

import android.content.Context;
import android.util.Log;

import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;
import gov.tak.api.plugin.IServiceController;

public class UVProLifecycle extends AbstractPlugin {
    public UVProLifecycle(IServiceController serviceController) {
        super(serviceController,
                new UVProTool(serviceController.getService(
                        PluginContextProvider.class).getPluginContext()),
                new UVProMapComponent());
        try {
            Context pc = serviceController.getService(PluginContextProvider.class).getPluginContext();
            UVProMapComponent.applyUpdateServerTrustEarly(pc);
        } catch (Throwable t) {
            Log.w("UVPro", "applyUpdateServerTrustEarly failed: " + t.getMessage());
        }
    }
}
