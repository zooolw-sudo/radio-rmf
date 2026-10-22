package pl.zooolw.radiormf;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Ekran aplikacji: ten sam interfejs co wersja internetowa (z assets),
 * a dźwięk gra usługa RadioService, więc radio działa w tle, np. przy nawigacji.
 */
public class MainActivity extends Activity {

    private WebView web;
    private RadioService.Listener myListener;
    private volatile boolean fullscreen = true;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF151C2B);
        getWindow().setNavigationBarColor(0xFF0D121B);
        fullscreen = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("fullscreen", true);
        applyFullscreen();

        web = new WebView(this);
        web.setBackgroundColor(0xFF0D121B);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "AndroidRadio");
        web.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file:")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
                return true;
            }
        });
        setContentView(web);

        myListener = json -> runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.__radio&&window.__radio(" + json + ")", null);
        });
        RadioService.listener = myListener;

        web.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        RadioService.listener = myListener;
        if (web != null) {
            // odśwież stan na ekranie (np. stację zmienioną przyciskiem na kierownicy)
            RadioService svc = RadioService.instance;
            if (svc != null) {
                String json = svc.stateJson();
                web.evaluateJavascript("window.__radio&&window.__radio(" + json + ")", null);
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // system pokazuje paski np. po powrocie z innej aplikacji — chowamy je ponownie
        if (hasFocus) applyFullscreen();
    }

    /** Pełny ekran: bez paska statusu i nawigacji; wysuwają się na chwilę po przeciągnięciu od krawędzi. */
    @SuppressWarnings("deprecation")
    private void applyFullscreen() {
        Window w = getWindow();
        boolean on = fullscreen;
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = on
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(!on);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                if (on) {
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(WindowInsets.Type.systemBars());
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            View d = w.getDecorView();
            d.setSystemUiVisibility(on
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                      | View.SYSTEM_UI_FLAG_FULLSCREEN
                      | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                      | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                      | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                      | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // radio przełączyło tryb dzień/noc (np. po włączeniu świateł) — motyw "Automatyczny" idzie za nim
        if (web != null) web.evaluateJavascript("window.__nightMode&&window.__nightMode(" + isNightMode() + ")", null);
    }

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // wstecz chowa aplikację, radio gra dalej
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (RadioService.listener == myListener) RadioService.listener = null;
        if (web != null) {
            web.removeJavascriptInterface("AndroidRadio");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Polecenia ze strony (window.AndroidRadio). Wywoływane z wątku WebView. */
    private class Bridge {
        @JavascriptInterface
        public void play(String json) {
            Intent i = new Intent(MainActivity.this, RadioService.class)
                    .setAction(RadioService.ACTION_PLAY)
                    .putExtra(RadioService.EXTRA_JSON, json);
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void stop() {
            RadioService svc = RadioService.instance;
            if (svc != null) svc.requestStop();
        }

        @JavascriptInterface
        public void setVolume(float v) {
            RadioService svc = RadioService.instance;
            if (svc != null) svc.setUserVolume(v);
        }

        @JavascriptInterface
        public boolean isNight() {
            return isNightMode();
        }

        @JavascriptInterface
        public boolean isFullscreen() {
            return fullscreen;
        }

        @JavascriptInterface
        public void setFullscreen(boolean on) {
            fullscreen = on;
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("fullscreen", on).apply();
            runOnUiThread(MainActivity.this::applyFullscreen);
        }

        @JavascriptInterface
        public String diag() {
            String ver = "?";
            try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
            String sys = "Aplikacja " + ver
                    + "\nSystem: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                    + "\nUrządzenie: " + Build.MANUFACTURER + " " + Build.MODEL;
            RadioService svc = RadioService.instance;
            return sys + "\n" + (svc != null ? svc.diagText() : "Odtwarzacz jeszcze nie uruchomiony — włącz stację.");
        }

        @JavascriptInterface
        public String state() {
            RadioService svc = RadioService.instance;
            return svc != null ? svc.stateJson() : "{}";
        }
    }
}
