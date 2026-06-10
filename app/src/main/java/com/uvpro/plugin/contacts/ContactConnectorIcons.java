package com.uvpro.plugin.contacts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.Log;

import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.uvpro.plugin.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Caches plugin connector and radial menu icons. ATAK's contact-list and radial GL renderers
 * cannot load {@code android.resource://} plugin drawables — use {@code file://} or
 * {@code base64://} URIs instead.
 */
public final class ContactConnectorIcons {

    private static final String TAG = "UVPro.ContactIcons";
    private static final int CONNECTOR_ICON_PX = 48;
    private static final int RADIAL_ICON_PX = 32;

    private static volatile Context pluginContextRef;
    private static volatile String uvproRadioIconUri;
    private static volatile String positionOnlyIconUri;
    private static volatile String radialPingIconDataUri;

    private ContactConnectorIcons() {
    }

    public static void warmCache(Context pluginContext) {
        if (pluginContext != null) {
            pluginContextRef = pluginContext;
        }
        Context ctx = resolvePluginContext(pluginContext);
        if (ctx == null) {
            return;
        }
        getUvproRadioIconUri(ctx);
        getPositionOnlyIconUri(ctx);
        getRadialPingIconDataUri(ctx);
    }

    /** Blue radio badge — Connectors page Ping / Send Message / Favorite. */
    public static String getUvproRadioIconUri(Context pluginContext) {
        String cached = uvproRadioIconUri;
        if (cached != null) {
            return cached;
        }
        synchronized (ContactConnectorIcons.class) {
            if (uvproRadioIconUri != null) {
                return uvproRadioIconUri;
            }
            Context ctx = resolvePluginContext(pluginContext);
            if (ctx == null) {
                return null;
            }
            uvproRadioIconUri = extractDrawableToCache(
                    ctx, R.drawable.ic_uvpro, "ic_uvpro.png", CONNECTOR_ICON_PX);
            return uvproRadioIconUri;
        }
    }

    /** Alias for Ping connector row. */
    public static String getPingConnectorIconUri(Context pluginContext) {
        return getUvproRadioIconUri(pluginContext);
    }

    /** Red position-only marker icon. */
    public static String getPositionOnlyIconUri(Context pluginContext) {
        String cached = positionOnlyIconUri;
        if (cached != null) {
            return cached;
        }
        synchronized (ContactConnectorIcons.class) {
            if (positionOnlyIconUri != null) {
                return positionOnlyIconUri;
            }
            Context ctx = resolvePluginContext(pluginContext);
            if (ctx == null) {
                return null;
            }
            positionOnlyIconUri = extractDrawableToCache(
                    ctx, R.drawable.ic_contact_position_only,
                    "ic_contact_position_only.png", CONNECTOR_ICON_PX);
            return positionOnlyIconUri;
        }
    }

    /** Radial submenu Ping button — same blue radio, encoded as base64 for map menu GL. */
    public static String getRadialPingIconDataUri(Context pluginContext) {
        String cached = radialPingIconDataUri;
        if (cached != null) {
            return cached;
        }
        synchronized (ContactConnectorIcons.class) {
            if (radialPingIconDataUri != null) {
                return radialPingIconDataUri;
            }
            Context ctx = resolvePluginContext(pluginContext);
            if (ctx == null) {
                return null;
            }
            radialPingIconDataUri = encodeDrawableToBase64Uri(
                    ctx, R.drawable.ic_uvpro, RADIAL_ICON_PX);
            if (radialPingIconDataUri != null) {
                Log.d(TAG, "Cached radial ping icon (ic_uvpro) as base64 data URI");
            }
            return radialPingIconDataUri;
        }
    }

    private static Context resolvePluginContext(Context hint) {
        if (hint != null) {
            return hint;
        }
        return pluginContextRef;
    }

    private static String encodeDrawableToBase64Uri(Context context, int resId, int sizePx) {
        if (context == null || sizePx <= 0) {
            return null;
        }
        try {
            Bitmap bitmap = rasterizeDrawable(context, resId, sizePx);
            if (bitmap == null) {
                return null;
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                return null;
            }
            byte[] encoded = Base64.encode(
                    outputStream.toByteArray(), Base64.NO_WRAP | Base64.URL_SAFE);
            return "base64://" + new String(encoded, FileSystemUtils.UTF8_CHARSET);
        } catch (Exception e) {
            Log.w(TAG, "Failed to encode icon resId=0x" + Integer.toHexString(resId), e);
            return null;
        }
    }

    private static String extractDrawableToCache(Context context, int resId, String filename,
                                                   int sizePx) {
        if (context == null) {
            return null;
        }
        try {
            File dir = new File(context.getCacheDir(), "uvpro_icons");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File out = new File(dir, filename);
            Bitmap bitmap = rasterizeDrawable(context, resId, sizePx);
            if (bitmap == null) {
                return null;
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            String uri = "file://" + out.getAbsolutePath();
            Log.d(TAG, "Cached connector icon " + filename + " -> " + uri);
            return uri;
        } catch (Exception e) {
            Log.w(TAG, "Failed to cache connector icon " + filename, e);
            return null;
        }
    }

    private static Bitmap rasterizeDrawable(Context context, int resId, int sizePx) {
        Drawable drawable = context.getResources().getDrawable(resId, context.getTheme());
        if (drawable == null) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, sizePx, sizePx);
        drawable.draw(canvas);
        return bitmap;
    }
}
