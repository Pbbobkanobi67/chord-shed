package com.chordshed.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

/**
 * Chord Shed runs as a single offline page in a WebView. The page ships in
 * assets/ along with its fonts, so the app never touches the network.
 *
 * Permissions:
 *  - RECORD_AUDIO, used by the tuner to detect the pitch of the string you play.
 *    Audio is analysed in the page and discarded; nothing is recorded or stored.
 *  - INTERNET, used by Options > Check for updates, which fetches one small
 *    version file when the button is pressed. The app's own pages are never
 *    loaded over the network - they are served from inside the APK.
 */
public class MainActivity extends Activity {

    /**
     * The page is served over https from a reserved, non-resolvable domain rather
     * than file:///android_asset/. getUserMedia() is gated on a secure context, and
     * file:// is not one - on file:// the WebView shows the permission prompt, takes
     * the grant, and then still refuses to open the microphone. Requests to this host
     * never leave the device; shouldInterceptRequest answers every one of them from
     * the APK's assets. (Same mechanism as androidx WebViewAssetLoader, written out
     * by hand so the build keeps needing no dependencies.)
     */
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String ASSET_PREFIX = "/assets/";
    private static final String PAGE = "https://" + ASSET_HOST + ASSET_PREFIX + "index.html";
    private static final int REQ_MIC = 1001;

    /** Page background, kept in step with the palette in index.html. */
    private static final int PAPER_LIGHT = Color.parseColor("#F3F2EC");
    private static final int PAPER_DARK  = Color.parseColor("#0F1513");

    private WebView web;
    private boolean loaded = false;

    /** A WebView mic request parked while Android's own permission dialog is up. */
    private PermissionRequest pendingMic;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);            // localStorage: charts, tuning, preferences
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);                      // ignore system font scaling; layout is fixed-scale
        s.setAllowFileAccess(false);             // assets still load; plain file:// does not
        s.setAllowContentAccess(false);

        web.setBackgroundColor(paper());
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                loaded = true;
                applyTheme();
                injectBuildInfo();
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                return serveAsset(req.getUrl());
            }

            // Anything that is not one of our own asset pages (the update download,
            // the GitHub and website links) goes to a real browser. A WebView cannot
            // download an APK, and handing it one just shows a blank page.
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u != null && ASSET_HOST.equals(u.getHost())) return false;
                return openExternally(u);
            }
        });

        // Same for anything the page treats as a download.
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String disposition,
                                        String mime, long length) {
                openExternally(Uri.parse(url));
            }
        });

        // getUserMedia() inside a WebView needs an explicit grant on top of the
        // OS-level permission. Without this the tuner silently never starts.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        boolean wantsMic = false;
                        for (String r : request.getResources()) {
                            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
                        }
                        if (!wantsMic) {
                            request.deny();
                            return;
                        }
                        if (hasMicPermission()) {
                            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                        } else {
                            pendingMic = request;
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                        }
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingMic == request) pendingMic = null;
            }
        });

        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (saved != null) {
            web.restoreState(saved);
        } else {
            web.loadUrl(PAGE);
        }
    }

    /** Hands a link to the system browser. Returns true if we consumed it. */
    private boolean openExternally(Uri u) {
        if (u == null) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    /**
     * Tells the page what build it is running inside, so Options can show real
     * version info and compare it against the published one.
     */
    private void injectBuildInfo() {
        String versionName = "?";
        int versionCode = 0;
        String installedOn = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = pi.versionName != null ? pi.versionName : "?";
            versionCode = (int) (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? pi.getLongVersionCode() : pi.versionCode);
            installedOn = DateFormat.getDateInstance().format(new Date(pi.lastUpdateTime));
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        String js = "window.__CHORDSHED_NATIVE__ = {"
                + "versionName:" + quote(versionName) + ","
                + "versionCode:" + versionCode + ","
                + "packageName:" + quote(getPackageName()) + ","
                + "sdkInt:" + Build.VERSION.SDK_INT + ","
                + "release:" + quote(Build.VERSION.RELEASE) + ","
                + "installedOn:" + quote(installedOn)
                + "}; if (window.__onNative) window.__onNative();";
        web.evaluateJavascript(js, null);
    }

    /** Minimal JS string literal - these values are all from the platform, but quote anyway. */
    private static String quote(String s) {
        if (s == null) return "''";
        StringBuilder b = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '\\') b.append('\\').append(c);
            else if (c == '\n' || c == '\r') b.append(' ');
            else b.append(c);
        }
        return b.append('\'').toString();
    }

    /**
     * Answers every request for the asset host from the APK, so nothing is ever
     * fetched over the network. Returns null for anything else, which lets the
     * WebView handle it normally (the update check and outbound links).
     */
    private WebResourceResponse serveAsset(Uri uri) {
        if (uri == null || !ASSET_HOST.equals(uri.getHost())) return null;
        if (!"https".equals(uri.getScheme())) return null;

        String path = uri.getPath();
        if (path == null || !path.startsWith(ASSET_PREFIX)) return null;

        String name = path.substring(ASSET_PREFIX.length());
        if (name.isEmpty() || name.contains("..")) return null;

        try {
            InputStream in = getAssets().open(name);
            Map<String, String> headers = Collections.singletonMap("Cache-Control", "no-store");
            WebResourceResponse res =
                    new WebResourceResponse(mimeOf(name), isText(name) ? "utf-8" : null, in);
            res.setResponseHeaders(headers);
            return res;
        } catch (IOException e) {
            // 404 rather than null: null would send the WebView to the network,
            // where this hostname does not resolve and the failure is opaque.
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                    Collections.<String, String>emptyMap(), null);
        }
    }

    private static boolean isText(String name) {
        return name.endsWith(".html") || name.endsWith(".css") || name.endsWith(".js")
                || name.endsWith(".json") || name.endsWith(".svg");
    }

    private static String mimeOf(String name) {
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".css"))  return "text/css";
        if (name.endsWith(".js"))   return "application/javascript";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".svg"))  return "image/svg+xml";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".woff")) return "font/woff";
        if (name.endsWith(".ttf"))  return "font/ttf";
        if (name.endsWith(".png"))  return "image/png";
        return "application/octet-stream";
    }

    private boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_MIC) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingMic != null) {
                if (granted) {
                    pendingMic.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    // The page handles the refusal: it falls back to reference pitches.
                    pendingMic.deny();
                }
                pendingMic = null;
            }
            return;
        }
        super.onRequestPermissionsResult(code, perms, results);
    }

    private boolean isNight() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private int paper() {
        return isNight() ? PAPER_DARK : PAPER_LIGHT;
    }

    /**
     * A WebView does not inherit the system dark-mode setting on every OEM build,
     * so stamp the page's own theme attribute instead of relying on
     * prefers-color-scheme. index.html handles data-theme="dark"/"light" explicitly.
     */
    private void applyTheme() {
        if (!loaded) return;
        String t = isNight() ? "dark" : "light";
        web.evaluateJavascript(
                "document.documentElement.setAttribute('data-theme','" + t + "');", null);
        web.setBackgroundColor(paper());
        paintSystemBars();
    }

    private void paintSystemBars() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (isNight()) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        decor.setSystemUiVisibility(flags);
        getWindow().setStatusBarColor(paper());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().setNavigationBarColor(paper());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyTheme();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        web.pauseTimers();   // silence any scheduled strum while backgrounded
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.resumeTimers();
        web.onResume();
        applyTheme();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
