package pl.zooolw.radiormf;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Ekran aplikacji: ten sam interfejs co wersja internetowa (z assets),
 * a dźwięk gra usługa RadioService, więc radio działa w tle, np. przy nawigacji.
 */
public class MainActivity extends Activity {

    private WebView web;
    private RadioService.Listener myListener;
    private volatile boolean fullscreen = true;

    /** Wymyślony adres, który obsługuje sama aplikacja (okładki i obrazek testowy). */
    static final String LOCAL_HOST = "radio-rmf.local";
    private String imgDefaults = "";
    private byte[] testPng;

    /**
     * Temperatura zewnętrzna z CAN. Aplikacja radia „Informacje o pojeździe” (com.kyhero.car.myhost)
     * rozsyła stan klimatyzacji broadcastem z tą akcją; pole "out_temp" to tekst typu "12.5℃".
     * Tylko odbieramy — nic nie wysyłamy do auta.
     */
    static final String ACTION_AIRCON = "canbus.aircon.conrol.rx.action";
    private BroadcastReceiver carTempReceiver;
    private volatile String carTempRaw = null;
    private volatile long carTempAt = 0;
    private volatile int carTempMsgs = 0;

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
        // niektóre radia mają obrazki w przeglądarce systemu domyślnie wyłączone — włączamy je jawnie
        imgDefaults = "obrazki " + (s.getLoadsImagesAutomatically() ? "wł." : "WYŁĄCZONE")
                + (s.getBlockNetworkImage() ? ", z sieci ZABLOKOWANE" : "")
                + (s.getBlockNetworkLoads() ? ", sieć ZABLOKOWANA" : "");
        s.setLoadsImagesAutomatically(true);
        s.setBlockNetworkImage(false);
        s.setBlockNetworkLoads(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        web.addJavascriptInterface(new Bridge(), "AndroidRadio");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u == null || !LOCAL_HOST.equals(u.getHost())) return null;
                String path = u.getPath() != null ? u.getPath() : "";
                byte[] body = null;
                String type = "image/jpeg";
                if (path.startsWith("/okladka/")) {
                    RadioService svc = RadioService.instance;
                    if (svc != null) { body = svc.coverFile(); type = svc.coverFileMime(); }
                } else if (path.startsWith("/test")) {
                    body = testImage();
                    type = "image/png";
                }
                Map<String, String> h = new HashMap<>();
                h.put("Cache-Control", "no-store");
                h.put("Access-Control-Allow-Origin", "*");
                if (body == null) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h, new ByteArrayInputStream(new byte[0]));
                }
                return new WebResourceResponse(type, null, 200, "OK", h, new ByteArrayInputStream(body));
            }

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

        startCarTemp();

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

    private void startCarTemp() {
        SharedPreferences sp = getSharedPreferences("car", MODE_PRIVATE);
        carTempRaw = sp.getString("outTemp", null);
        carTempAt = sp.getLong("outTempAt", 0);
        carTempReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                carTempMsgs++;
                Object v = null;
                try { if (i.getExtras() != null) v = i.getExtras().get("out_temp"); } catch (Exception ignored) { }
                String raw = v == null ? "" : String.valueOf(v).trim();
                if (raw.isEmpty() || raw.equalsIgnoreCase("null")) return;
                carTempRaw = raw;
                carTempAt = System.currentTimeMillis();
                getSharedPreferences("car", MODE_PRIVATE).edit()
                        .putString("outTemp", raw).putLong("outTempAt", carTempAt).apply();
                if (web != null) web.evaluateJavascript("window.__carTemp&&window.__carTemp(" + carTempJson() + ")", null);
            }
        };
        IntentFilter f = new IntentFilter(ACTION_AIRCON);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(carTempReceiver, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(carTempReceiver, f);
        } catch (Exception e) {
            carTempReceiver = null;
        }
    }

    private String carTempJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("raw", carTempRaw == null ? "" : carTempRaw);
            o.put("at", carTempAt);
            o.put("now", System.currentTimeMillis());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private synchronized byte[] testImage() {
        if (testPng == null) {
            try {
                Bitmap b = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.PNG, 100, o);
                testPng = o.toByteArray();
            } catch (Exception e) {
                testPng = new byte[0];
            }
        }
        return testPng.length > 0 ? testPng : null;
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
        if (carTempReceiver != null) {
            try { unregisterReceiver(carTempReceiver); } catch (Exception ignored) { }
            carTempReceiver = null;
        }
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
                    + "\nUrządzenie: " + Build.MANUFACTURER + " " + Build.MODEL
                    + "\nPrzeglądarka systemu (przed zmianą): " + imgDefaults
                    + "\nTemperatura z CAN: " + (carTempRaw == null ? "jeszcze nie odebrano"
                        : carTempRaw + " (" + new java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US).format(new java.util.Date(carTempAt)) + ")")
                    + ", komunikatów od startu: " + carTempMsgs;
            RadioService svc = RadioService.instance;
            return sys + "\n" + (svc != null ? svc.diagText() : "Odtwarzacz jeszcze nie uruchomiony — włącz stację.");
        }

        @JavascriptInterface
        public String carTemp() {
            return carTempJson();
        }

        @JavascriptInterface
        public String state() {
            RadioService svc = RadioService.instance;
            return svc != null ? svc.stateJson() : "{}";
        }
    }
}
