package com.chordshed.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Chord Shed runs as a single offline page in a WebView. The page ships in
 * assets/ along with its fonts, so the app never touches the network.
 */
public class MainActivity extends Activity {

    private static final String PAGE = "file:///android_asset/index.html";

    /** Page background, kept in step with the palette in index.html. */
    private static final int PAPER_LIGHT = Color.parseColor("#F3F2EC");
    private static final int PAPER_DARK  = Color.parseColor("#0F1513");

    private WebView web;
    private boolean loaded = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);            // localStorage: tuning, chart, preferences
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);                      // ignore system font scaling; the layout is fixed-scale
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setAllowFileAccess(false);             // asset:// loading does not need file access
        s.setAllowContentAccess(false);

        web.setBackgroundColor(paper());
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                loaded = true;
                applyTheme();
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
