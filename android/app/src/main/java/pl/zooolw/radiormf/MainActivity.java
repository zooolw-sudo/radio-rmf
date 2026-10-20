package pl.zooolw.radiormf;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF151C2B);
        getWindow().setNavigationBarColor(0xFF0D121B);

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
        public String state() {
            RadioService svc = RadioService.instance;
            return svc != null ? svc.stateJson() : "{}";
        }
    }
}
