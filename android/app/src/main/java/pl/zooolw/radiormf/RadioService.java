package pl.zooolw.radiormf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.Html;
import android.util.Base64;
import android.webkit.WebSettings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Odtwarzanie w tle: strumień radia, powiadomienie z przyciskami,
 * przyciski na kierownicy (MediaSession) i ściszanie przy komunikatach nawigacji.
 * Jeśli serwer nie odpowiada, usługa sama przechodzi do następnego adresu stacji.
 */
public class RadioService extends Service {

    public interface Listener { void onEvent(String json); }

    public static final String ACTION_PLAY = "pl.zooolw.radiormf.PLAY";
    public static final String ACTION_STOP = "pl.zooolw.radiormf.STOP";
    public static final String ACTION_NEXT = "pl.zooolw.radiormf.NEXT";
    public static final String ACTION_PREV = "pl.zooolw.radiormf.PREV";
    public static final String ACTION_RESUME = "pl.zooolw.radiormf.RESUME";
    public static final String EXTRA_JSON = "json";

    private static final String CHANNEL = "radio";
    private static final int NOTIF_ID = 1;
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int STALL_TIMEOUT_MS = 20000;
    private static final long CALL_POLL_MS = 1500;
    private static final long LOST_WAIT_MS = 60_000;              // ile czekać, czy utrata dźwięku to rozmowa
    private static final long PAUSE_MAX_MS = 3 * 60 * 60_000L;    // najdłuższa przerwa z automatycznym wznowieniem
    private static final long NET_MAX_WAIT_MS = 15 * 60_000L;     // ile czekać na powrót internetu

    // rodzaje przerwy w odtwarzaniu
    private static final int PAUSE_NONE = 0;
    private static final int PAUSE_MUTED = 1;   // krótka przerwa: strumień gra dalej, wyciszony
    private static final int PAUSE_CALL = 2;    // rozmowa: strumień rozłączony, wznowienie po rozmowie
    private static final int PAUSE_LOST = 3;    // inna aplikacja zabrała dźwięk: sprawdzamy, czy to rozmowa

    static volatile RadioService instance;
    static volatile Listener listener;

    private static class Station {
        String id = "", name = "", tag = "";
        int rid = 0;   // numer stacji w RMF ON (do tytułu utworu)
        final List<String> urls = new ArrayList<>();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Station> stations = new ArrayList<>();
    private int stIdx = -1;
    private int urlIdx = 0;
    private boolean hadPlayed = false;
    private boolean retried = false;
    private volatile String state = "stopped";

    private MediaPlayer mp;
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focusRequest;
    private float userVolume = 1f;
    private float focusFactor = 1f;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock cpuLock;
    private Bitmap art;

    // przerwy (rozmowa) i brak internetu
    private int pauseKind = PAUSE_NONE;
    private boolean sawCall = false;
    private int normalPolls = 0;
    private long pauseStart = 0;
    private long netWaitStart = 0;
    private int netRetries = 0;
    private boolean netOffline = false;

    // teraz gra
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private int metaGen = 0;
    private String songA = "", songT = "", songC = "", nextA = "", nextT = "";
    private String coverLoaded = "";
    private String coverData = "";          // okładka jako data: URI dla ekranu aplikacji
    private SSLSocketFactory extraTls;      // tylko wątek "net"
    private String ua = "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ") RadioRMF/1.0";

    // diagnostyka (Ustawienia → Diagnostyka)
    volatile String diagPlaylist = "jeszcze nie pobierano";
    volatile String diagCover = "jeszcze nie pobierano";
    private boolean extraTlsTried = false;
    private Bitmap cover;

    private final Runnable metaPoll = this::pollMeta;
    private final Runnable timeout = this::fail;
    private final Runnable reopen = this::openCurrent;
    private final Runnable callPoll = this::checkCall;
    private final Runnable netRetry = this::retryNetwork;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // dźwięk zabrany na stałe: rozmowa (np. WhatsApp) albo inny odtwarzacz — sprawdzamy które
                if (isActive() || pauseKind != PAUSE_NONE) enterPause(PAUSE_LOST);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // chwilowa przerwa (dzwonek, rozmowa, asystent): wycisz i obserwuj, czy to rozmowa
                if (isActive() && pauseKind == PAUSE_NONE) {
                    pauseKind = PAUSE_MUTED;
                    pauseStart = SystemClock.elapsedRealtime();
                    sawCall = false;
                    normalPolls = 0;
                    focusFactor = 0f;
                    applyVolume();
                    main.removeCallbacks(callPoll);
                    main.post(callPoll);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                focusFactor = 0.25f;   // komunikat nawigacji: przycisz
                applyVolume();
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                focusFactor = 1f;
                if (pauseKind == PAUSE_MUTED) {
                    pauseKind = PAUSE_NONE;
                    main.removeCallbacks(callPoll);
                    applyVolume();
                } else if (pauseKind == PAUSE_CALL || pauseKind == PAUSE_LOST) {
                    resumeAfterPause();   // rozmowa się skończyła
                } else {
                    applyVolume();
                }
                break;
            default:
                break;
        }
    };

    // ---------- cykl życia ----------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        art = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "radiormf");
            wifiLock.setReferenceCounted(false);
        }

        // serwery RMF widzą nas jak zwykłą przeglądarkę (niektóre odrzucają nietypowe aplikacje)
        try { ua = WebSettings.getDefaultUserAgent(this); } catch (Exception ignored) { }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "radiormf:session");
            cpuLock.setReferenceCounted(false);
        }

        createChannel();

        session = new MediaSession(this, "RadioRMF");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { stopPlayback("stopped"); }
            @Override public void onStop() { stopPlayback("stopped"); }
            @Override public void onSkipToNext() { skip(1); }
            @Override public void onSkipToPrevious() { skip(-1); }
        });
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        session.setSessionActivity(PendingIntent.getActivity(this, 0, open, piFlags()));

        restoreFromPrefs();
        updateSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        boolean fg = ACTION_PLAY.equals(a) || ACTION_NEXT.equals(a) || ACTION_PREV.equals(a) || ACTION_RESUME.equals(a);
        if (fg) goForeground();   // Android wymaga tego zaraz po starcie usługi

        if (ACTION_PLAY.equals(a)) {
            String json = intent.getStringExtra(EXTRA_JSON);
            if (load(json)) {
                savePrefs(json);
                startStation();
            } else {
                stopPlayback("stopped");
            }
        } else if (ACTION_STOP.equals(a)) {
            stopPlayback("stopped");
        } else if (ACTION_NEXT.equals(a)) {
            skip(1);
        } else if (ACTION_PREV.equals(a)) {
            skip(-1);
        } else if (ACTION_RESUME.equals(a)) {
            resume();
        } else if (!busy()) {
            stopSelf();
        }
        if (fg && stations.isEmpty()) stopPlayback("stopped");
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!busy()) stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        net.shutdownNow();
        releasePlayer();
        abandonFocus();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        releaseCpu();
        if (session != null) session.release();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------- polecenia (wywoływane też z aplikacji) ----------

    void requestStop() { main.post(() -> stopPlayback("stopped")); }

    void setUserVolume(float v) {
        main.post(() -> {
            userVolume = Math.max(0f, Math.min(1f, v));
            applyVolume();
        });
    }

    String stateJson() {
        if (stations.isEmpty()) return "{}";
        return eventJson(state);
    }

    private String eventJson(String ev) {
        JSONObject o = new JSONObject();
        try {
            o.put("ev", ev);
            o.put("call", sawCall);
            o.put("offline", netOffline);
            if (songT.isEmpty()) o.put("now", JSONObject.NULL);
            else {
                JSONObject n = new JSONObject();
                n.put("a", songA); n.put("t", songT); n.put("c", songC);
                n.put("na", nextA); n.put("nt", nextT);
                if (!coverData.isEmpty()) n.put("img", coverData);
                o.put("now", n);
            }
            Station st = current();
            if (st != null) {
                o.put("id", st.id);
                o.put("cand", urlIdx);
                o.put("total", st.urls.size());
                if (urlIdx < st.urls.size()) o.put("url", st.urls.get(urlIdx));
            }
        } catch (Exception ignored) {
            // wywoływane z wątku strony; przy jednoczesnej zmianie listy zwróć to, co jest
        }
        return o.toString();
    }

    /** Gra albo za chwilę zagra (łączy się, buforuje, czeka na internet). */
    private boolean isActive() {
        return "playing".equals(state) || "connecting".equals(state) || "buffering".equals(state)
                || "waiting".equals(state);
    }

    /** Aktywne albo wstrzymane z automatycznym wznowieniem. */
    private boolean busy() {
        return isActive() || "interrupted".equals(state);
    }

    private Station current() {
        return (stIdx >= 0 && stIdx < stations.size()) ? stations.get(stIdx) : null;
    }

    private boolean load(String json) {
        if (json == null) return false;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray arr = o.getJSONArray("list");
            List<Station> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                Station st = new Station();
                st.id = s.optString("id");
                st.name = s.optString("name");
                st.tag = s.optString("tag");
                st.rid = s.optInt("rid", 0);
                JSONArray u = s.optJSONArray("urls");
                if (u != null) for (int j = 0; j < u.length(); j++) st.urls.add(u.getString(j));
                list.add(st);
            }
            if (list.isEmpty()) return false;
            stations.clear();
            stations.addAll(list);
            stIdx = Math.max(0, Math.min(o.optInt("index", 0), stations.size() - 1));
            if (o.has("volume")) userVolume = (float) Math.max(0, Math.min(1, o.optDouble("volume", 1)));
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    private SharedPreferences prefs() { return getSharedPreferences("radio", MODE_PRIVATE); }

    private void savePrefs(String json) {
        SharedPreferences.Editor e = prefs().edit();
        if (json != null) e.putString("json", json);
        e.putInt("index", stIdx).apply();
    }

    private void restoreFromPrefs() {
        String json = prefs().getString("json", null);
        if (json != null && load(json)) {
            stIdx = Math.max(0, Math.min(prefs().getInt("index", stIdx), stations.size() - 1));
        }
    }

    private void resume() {
        if ("interrupted".equals(state)) { resumeAfterPause(); return; }   // "graj" w trakcie przerwy: od razu
        if ("waiting".equals(state)) { retryNetwork(); return; }
        if (isActive()) return;
        if (stations.isEmpty()) restoreFromPrefs();
        if (stations.isEmpty()) return;
        goForeground();
        startStation();
    }

    private void skip(int dir) {
        if (stations.isEmpty()) restoreFromPrefs();
        int n = stations.size();
        if (n == 0) return;
        stIdx = ((stIdx + dir) % n + n) % n;
        savePrefs(null);
        goForeground();
        startStation();
    }

    // ---------- odtwarzanie ----------

    private void startStation() {
        haltStream();
        main.removeCallbacks(callPoll);
        pauseKind = PAUSE_NONE;
        sawCall = false;
        normalPolls = 0;
        netWaitStart = 0;
        netRetries = 0;
        netOffline = false;
        if (current() == null) { stopPlayback("stopped"); return; }
        urlIdx = 0;
        hadPlayed = false;
        retried = false;
        focusFactor = 1f;
        holdCpu();
        clearSong();
        if (requestFocus() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // trwa rozmowa: nie gramy teraz, wznowimy, gdy się skończy
            sawCall = true;
            pauseKind = PAUSE_CALL;
            pauseStart = SystemClock.elapsedRealtime();
            setState("interrupted");
            main.postDelayed(callPoll, CALL_POLL_MS);
            return;
        }
        if (wifiLock != null) wifiLock.acquire();
        openCurrent();
        main.post(metaPoll);
    }

    /** Zatrzymuje strumień i wszystkie zaplanowane próby, bez zmiany stanu. */
    private void haltStream() {
        main.removeCallbacks(timeout);
        main.removeCallbacks(reopen);
        main.removeCallbacks(netRetry);
        main.removeCallbacks(metaPoll);
        metaGen++;
        releasePlayer();
    }

    // ---------- przerwy: rozmowa telefoniczna ----------

    private void enterPause(int kind) {
        haltStream();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (pauseKind == PAUSE_NONE || pauseKind == PAUSE_MUTED) {
            if (pauseKind == PAUSE_NONE) {
                pauseStart = SystemClock.elapsedRealtime();
                sawCall = false;
            }
            normalPolls = 0;
        }
        // utrata na stałe w trakcie rozmowy nie zmienia tego, że czekamy na koniec rozmowy
        if (!(pauseKind == PAUSE_CALL && kind == PAUSE_LOST)) pauseKind = kind;
        holdCpu();
        setState("interrupted");
        main.removeCallbacks(callPoll);
        main.postDelayed(callPoll, CALL_POLL_MS);
    }

    /** Co 1,5 s: czy trwa rozmowa, czy już się skończyła. */
    private void checkCall() {
        main.removeCallbacks(callPoll);
        if (pauseKind == PAUSE_NONE) return;
        long waited = SystemClock.elapsedRealtime() - pauseStart;
        if (inCallMode()) {
            normalPolls = 0;
            if (!sawCall) {
                sawCall = true;
                if (pauseKind == PAUSE_MUTED) {
                    // to rozmowa, a nie krótki komunikat: rozłącz strumień, połączymy się na nowo po rozmowie
                    enterPause(PAUSE_CALL);
                    return;
                }
                pauseKind = PAUSE_CALL;
                publish(state);   // napis "na czas rozmowy"
            }
        } else if (sawCall && pauseKind == PAUSE_CALL) {
            if (++normalPolls >= 2) { resumeAfterPause(); return; }   // rozmowa skończona
        } else if (pauseKind == PAUSE_LOST && waited > LOST_WAIT_MS) {
            // to nie była rozmowa, tylko inna aplikacja z dźwiękiem: zostawiamy ją w spokoju
            stopPlayback("stopped");
            return;
        }
        if (waited > PAUSE_MAX_MS) { stopPlayback("stopped"); return; }
        main.postDelayed(callPoll, CALL_POLL_MS);
    }

    private void resumeAfterPause() {
        main.removeCallbacks(callPoll);
        pauseKind = PAUSE_NONE;
        startStation();   // nowe połączenie, żeby grać na żywo, a nie od miejsca sprzed rozmowy
    }

    private boolean inCallMode() {
        int m = am != null ? am.getMode() : AudioManager.MODE_NORMAL;
        return m == AudioManager.MODE_RINGTONE || m == AudioManager.MODE_IN_CALL
                || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_CALL_SCREENING
                || m == AudioManager.MODE_CALL_REDIRECT || m == AudioManager.MODE_COMMUNICATION_REDIRECT;
    }

    // ---------- brak internetu ----------

    @SuppressWarnings("deprecation")
    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            if (Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                if (n == null) return false;
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                return c == null || c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            NetworkInfo i = cm.getActiveNetworkInfo();
            return i != null && i.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    /** Żaden serwer nie odpowiedział: czekamy na internet albo próbujemy jeszcze raz. */
    private void allFailed() {
        releasePlayer();
        long now = SystemClock.elapsedRealtime();
        if (netWaitStart == 0) netWaitStart = now;
        netOffline = !isOnline();
        if (now - netWaitStart > NET_MAX_WAIT_MS || (!netOffline && netRetries >= 2)) {
            stopPlayback("failed");
            return;
        }
        holdCpu();
        setState("waiting");
        main.removeCallbacks(netRetry);
        main.postDelayed(netRetry, netOffline ? 3000 : 5000);
    }

    private void retryNetwork() {
        main.removeCallbacks(netRetry);
        if (current() == null || !"waiting".equals(state)) return;
        boolean online = isOnline();
        if (!online) {
            if (!netOffline) { netOffline = true; publish(state); }
            if (SystemClock.elapsedRealtime() - netWaitStart > NET_MAX_WAIT_MS) { stopPlayback("failed"); return; }
            main.postDelayed(netRetry, 3000);
            return;
        }
        if (netOffline) netOffline = false; else netRetries++;
        urlIdx = 0;
        hadPlayed = false;
        retried = false;
        if (wifiLock != null) wifiLock.acquire();
        openCurrent();
        main.removeCallbacks(metaPoll);
        main.post(metaPoll);
    }

    private void holdCpu() {
        try { if (cpuLock != null) cpuLock.acquire(PAUSE_MAX_MS + 60_000); } catch (Exception ignored) { }
    }

    private void releaseCpu() {
        try { if (cpuLock != null && cpuLock.isHeld()) cpuLock.release(); } catch (Exception ignored) { }
    }

    private void openCurrent() {
        releasePlayer();
        Station st = current();
        if (st == null) { stopPlayback("stopped"); return; }
        if (urlIdx >= st.urls.size()) { allFailed(); return; }

        setState("connecting");
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, CONNECT_TIMEOUT_MS);

        MediaPlayer p = new MediaPlayer();
        mp = p;
        try {
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            p.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            p.setOnPreparedListener(x -> {
                if (x != mp) return;
                main.removeCallbacks(timeout);
                applyVolume();
                x.start();
                hadPlayed = true;
                retried = false;
                netWaitStart = 0;
                netRetries = 0;
                netOffline = false;
                setState("playing");
            });
            p.setOnErrorListener((x, what, extra) -> {
                if (x == mp) fail();
                return true;
            });
            p.setOnCompletionListener(x -> {
                if (x == mp) fail();   // strumień na żywo nie powinien się kończyć: połącz ponownie
            });
            p.setOnInfoListener((x, what, extra) -> {
                if (x != mp || !hadPlayed) return false;
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    setState("buffering");
                    main.removeCallbacks(timeout);
                    main.postDelayed(timeout, STALL_TIMEOUT_MS);
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    main.removeCallbacks(timeout);
                    setState("playing");
                }
                return false;
            });
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "RadioRMF-Android/1.0");
            p.setDataSource(this, Uri.parse(st.urls.get(urlIdx)), headers);
            p.prepareAsync();
        } catch (Exception e) {
            main.post(() -> { if (p == mp) fail(); });
        }
    }

    private void fail() {
        main.removeCallbacks(timeout);
        main.removeCallbacks(reopen);
        if (current() == null || pauseKind == PAUSE_CALL || pauseKind == PAUSE_LOST) return;
        releasePlayer();
        if (!isOnline()) { allFailed(); return; }   // np. tunel albo rozmowa bez internetu: czekaj na zasięg
        if (hadPlayed && !retried) {
            // grało i przerwało: jeszcze raz ten sam serwer
            retried = true;
            hadPlayed = false;
            setState("connecting");
            main.postDelayed(reopen, 1500);
            return;
        }
        hadPlayed = false;
        retried = false;
        urlIdx++;
        openCurrent();
    }

    private void stopPlayback(String finalState) {
        haltStream();
        main.removeCallbacks(callPoll);
        pauseKind = PAUSE_NONE;
        sawCall = false;
        netOffline = false;
        clearSong();
        abandonFocus();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        releaseCpu();
        setState(finalState);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH);
        else stopForeground(false);
        if (stations.isEmpty()) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        }
    }

    private void releasePlayer() {
        MediaPlayer p = mp;
        mp = null;
        if (p != null) {
            try { p.reset(); } catch (Exception ignored) { }
            try { p.release(); } catch (Exception ignored) { }
        }
    }

    private void applyVolume() {
        MediaPlayer p = mp;
        if (p == null) return;
        float v = userVolume * focusFactor;
        try { p.setVolume(v, v); } catch (IllegalStateException ignored) { }
    }

    @SuppressWarnings("deprecation")
    private int requestFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusRequest == null) {
                    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .setOnAudioFocusChangeListener(focusListener, main)
                            .setAcceptsDelayedFocusGain(true)   // w trakcie rozmowy: dostaniemy dźwięk po jej końcu
                            .setWillPauseWhenDucked(false)
                            .build();
                }
                return am.requestAudioFocus(focusRequest);
            }
            return am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        } catch (Exception e) {
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonFocus() {
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
        } else {
            am.abandonAudioFocus(focusListener);
        }
    }

    // ---------- stan, sesja, powiadomienie ----------

    private void setState(String s) {
        state = s;
        publish(s);
    }

    /** Odświeża sesję (kierownica, ekran radia), powiadomienie i stronę. */
    private void publish(String ev) {
        updateSession();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && !stations.isEmpty()) nm.notify(NOTIF_ID, buildNotification());
        Listener l = listener;
        if (l != null && !stations.isEmpty()) l.onEvent(eventJson(ev));
    }

    // ---------- teraz gra (playlista RMF ON) ----------

    private void clearSong() {
        songA = songT = songC = nextA = nextT = "";
        cover = null;
        coverData = "";
        coverLoaded = "";
    }

    private void pollMeta() {
        Station st = current();
        if (st == null || st.rid <= 0 || !isActive()) return;
        final int gen = metaGen;
        final String url = "https://www.rmfon.pl/stacje/playlista_" + st.rid + ".json.txt";
        try {
            net.execute(() -> {
                StringBuilder log = new StringBuilder();
                final String body = httpText(url, log);
                diagPlaylist = now() + " stacja " + st.rid + ": " + log;
                main.post(() -> {
                    if (gen != metaGen) return;
                    long wait = body != null ? applyPlaylist(body) : 30000;
                    main.removeCallbacks(metaPoll);
                    if (isActive()) main.postDelayed(metaPoll, wait);
                });
            });
        } catch (Exception ignored) { }
    }

    /** Ustawia bieżący utwór; zwraca, za ile ms zapytać ponownie. */
    private long applyPlaylist(String body) {
        try {
            JSONArray rows = new JSONArray(body);
            JSONObject cur = null, next = null;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) continue;
                int order = r.optInt("order", -99);
                if (order == 0) cur = r;
                else if (order == 1) next = r;
            }
            if (cur == null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.optJSONObject(i);
                    if (r == null) continue;
                    int u = r.optInt("uptime", 1);
                    if (u <= 0 && (cur == null || u > cur.optInt("uptime", -999999))) cur = r;
                }
            }
            String t = cur != null ? clean(cur.optString("title")) : "";
            String a = cur != null ? clean(cur.optString("author")) : "";
            String c = cur != null ? cur.optString("coverBigUrl", "") : "";
            if ((c.isEmpty() || "null".equals(c)) && cur != null) c = cur.optString("coverUrl", "");
            if ("null".equals(c)) c = "";
            String na = next != null ? clean(next.optString("author")) : "";
            String nt = next != null ? clean(next.optString("title")) : "";
            boolean changed = !t.equals(songT) || !a.equals(songA) || !nt.equals(nextT);
            songT = t; songA = a; songC = c; nextA = na; nextT = nt;
            if (!c.equals(coverLoaded)) {
                // nowy utwór: stara okładka znika od razu, nowa dochodzi, gdy się pobierze
                cover = null;
                coverData = "";
                coverLoaded = c;
                if (!c.isEmpty()) loadCover(c);
                changed = true;
            }
            if (changed) publish("meta");
            int up = next != null ? next.optInt("uptime", 0) : 0;
            if (up > 0) return Math.max(8000, Math.min(180000, (up + 6) * 1000L));
        } catch (Exception ignored) { }
        return 30000;
    }

    private void loadCover(String url) {
        final int gen = metaGen;
        try {
            net.execute(() -> {
                StringBuilder log = new StringBuilder();
                Fetched f = fetch(url, 512 * 1024, log);
                Bitmap bmp = null;
                String data = "";
                if (f != null) {
                    bmp = BitmapFactory.decodeByteArray(f.body, 0, f.body.length);
                    if (bmp != null) {
                        // zapisz na nowo w zwykłym formacie: stare przeglądarki w radiach nie czytają
                        // niektórych plików JPEG (np. w kolorach do druku), które Android otwiera bez problemu
                        boolean png = bmp.hasAlpha();
                        ByteArrayOutputStream enc = new ByteArrayOutputStream();
                        if (bmp.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 88, enc)) {
                            data = "data:image/" + (png ? "png" : "jpeg") + ";base64," + Base64.encodeToString(enc.toByteArray(), Base64.NO_WRAP);
                        }
                        log.append(", ").append(bmp.getWidth()).append("×").append(bmp.getHeight()).append(" px")
                                .append(", źródło ").append(f.type).append(" ").append(f.body.length / 1024).append(" kB")
                                .append(data.isEmpty() ? ", nie udało się przekodować" : ", przekodowana");
                    } else {
                        log.append(", nie da się odczytać obrazka (").append(f.type).append(", ").append(f.body.length).append(" B)");
                    }
                }
                diagCover = now() + " " + url.replaceFirst("^https?://", "") + ": " + log;
                final Bitmap rb = bmp;
                final String rd = data;
                main.post(() -> {
                    if (gen != metaGen || !url.equals(coverLoaded)) return;
                    cover = rb;
                    coverData = rd;
                    if (rb != null) publish("meta");
                });
            });
        } catch (Exception ignored) { }
    }

    private String httpText(String url, StringBuilder log) {
        Fetched f = fetch(url, 1024 * 1024, log);
        return f != null ? new String(f.body, StandardCharsets.UTF_8) : null;
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        if (m != null && m.length() > 90) m = m.substring(0, 90) + "…";
        return e.getClass().getSimpleName() + (m != null ? " (" + m + ")" : "");
    }

    /** Tekst do sekcji Diagnostyka. */
    String diagText() {
        Station st = current();
        return "Stan: " + state + (st != null ? " · " + st.name + " (nr RMF ON " + st.rid + ")" : "")
                + "\nTeraz gra: " + (songT.isEmpty() ? "brak danych" : songA + " – " + songT)
                + "\nTytuły: " + diagPlaylist
                + "\nOkładka (aplikacja): " + diagCover
                + "\nOkładka przekazana na ekran: " + (coverData.isEmpty() ? "nie" : "tak (" + coverData.length() / 1024 + " kB)");
    }

    private static final class Fetched {
        byte[] body;
        String type;
    }

    /**
     * Pobiera plik z internetu. Starsze radia z Androidem nie znają nowszych certyfikatów
     * (np. Let's Encrypt), więc przy błędzie certyfikatu ponawiamy z dołączonymi certyfikatami
     * głównymi, a na końcu próbujemy zwykłym http.
     */
    private Fetched fetch(String url, int maxBytes, StringBuilder log) {
        log.append(url.startsWith("https://") ? "https " : "http ");
        Fetched f = fetchOnce(url, maxBytes, false, 0, log);
        if (f == null && url.startsWith("https://")) {
            log.append(" → http ");
            f = fetchOnce("http://" + url.substring(8), maxBytes, false, 0, log);
        }
        return f;
    }

    private Fetched fetchOnce(String url, int maxBytes, boolean extraTrust, int hops, StringBuilder log) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            if (extraTrust && c instanceof HttpsURLConnection) {
                SSLSocketFactory tls = extraTlsFactory();
                if (tls == null) { log.append("brak dodatkowych certyfikatów"); return null; }
                ((HttpsURLConnection) c).setSSLSocketFactory(tls);
            }
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", url.contains(".json") ? "application/json,text/plain,*/*" : "image/jpeg,image/png,image/webp,image/*;q=0.8,*/*;q=0.5");
            c.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && hops < 3) {
                // przekierowanie między http a https Android sam nie wykonuje
                String loc = c.getHeaderField("Location");
                log.append(code).append("→");
                if (loc == null) return null;
                String next = new URL(new URL(url), loc).toString();
                log.append(next.startsWith("https://") ? "https " : "http ");
                return fetchOnce(next, maxBytes, extraTrust, hops + 1, log);
            }
            if (code != 200) { log.append("HTTP ").append(code); return null; }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) { log.append("plik za duży"); return null; }
                }
                Fetched r = new Fetched();
                r.body = out.toByteArray();
                r.type = c.getContentType();
                log.append("OK").append(extraTrust ? " (z dodatkowymi certyfikatami)" : "");
                return r;
            }
        } catch (SSLException e) {
            log.append("błąd SSL: ").append(brief(e));
            if (extraTrust) return null;
            log.append(" → z dodatkowymi certyfikatami ");
            return fetchOnce(url, maxBytes, true, hops, log);
        } catch (Exception e) {
            log.append("błąd: ").append(brief(e));
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Certyfikaty systemu + dołączone nowsze certyfikaty główne (res/raw/extra_roots.pem). */
    private SSLSocketFactory extraTlsFactory() {
        if (extraTlsTried) return extraTls;
        extraTlsTried = true;
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int n = 0;
            try {
                KeyStore sys = KeyStore.getInstance("AndroidCAStore");
                sys.load(null, null);
                for (Enumeration<String> e = sys.aliases(); e.hasMoreElements(); ) {
                    Certificate cert = sys.getCertificate(e.nextElement());
                    if (cert != null) ks.setCertificateEntry("sys" + (n++), cert);
                }
            } catch (Exception ignored) { }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            try (InputStream in = getResources().openRawResource(R.raw.extra_roots)) {
                for (Certificate cert : cf.generateCertificates(in)) ks.setCertificateEntry("extra" + (n++), cert);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tmf.getTrustManagers(), null);
            extraTls = sc.getSocketFactory();
        } catch (Exception e) {
            extraTls = null;
        }
        return extraTls;
    }

    @SuppressWarnings("deprecation")
    private static String clean(String s) {
        if (s == null || s.isEmpty() || "null".equals(s)) return "";
        CharSequence d = Build.VERSION.SDK_INT >= 24 ? Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY) : Html.fromHtml(s);
        return d.toString().replaceAll("\\s+", " ").trim();
    }

    private String statusLabel() {
        switch (state) {
            case "playing": return "Na żywo";
            case "connecting": return "Łączę…";
            case "buffering": return "Buforuję…";
            case "failed": return "Brak połączenia — spróbuj ponownie";
            case "interrupted": return sawCall ? "Wstrzymane na czas rozmowy" : "Wstrzymane — wznowię automatycznie";
            case "waiting": return netOffline ? "Brak internetu — czekam na zasięg" : "Ponawiam połączenie…";
            default: return "Zatrzymane";
        }
    }

    private void updateSession() {
        if (session == null) return;
        int ps;
        switch (state) {
            case "playing": ps = PlaybackState.STATE_PLAYING; break;
            case "connecting": ps = PlaybackState.STATE_CONNECTING; break;
            case "buffering": ps = PlaybackState.STATE_BUFFERING; break;
            case "failed": ps = PlaybackState.STATE_ERROR; break;
            case "interrupted": ps = PlaybackState.STATE_PAUSED; break;
            case "waiting": ps = PlaybackState.STATE_CONNECTING; break;
            default: ps = PlaybackState.STATE_STOPPED; break;
        }
        PlaybackState.Builder b = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(ps, PlaybackState.PLAYBACK_POSITION_UNKNOWN, isActive() ? 1f : 0f);
        if ("failed".equals(state)) b.setErrorMessage("Brak połączenia");
        session.setPlaybackState(b.build());

        Station st = current();
        String stName = st != null ? st.name : "Radio RMF";
        boolean song = isActive() && !songT.isEmpty();
        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song ? songT : stName)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song ? (songA.isEmpty() ? stName : songA) : statusLabel())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, song ? stName : "Radio RMF");
        Bitmap pic = (song && cover != null) ? cover : art;
        if (pic != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, pic);
        session.setMetadata(m.build());
        // sesja zostaje aktywna po zatrzymaniu, żeby przycisk "graj" na kierownicy działał
        session.setActive(!stations.isEmpty());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Odtwarzanie radia", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private void goForeground() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF_ID, n);
        } catch (Exception ignored) {
            // niektóre systemy nie pozwalają na to w tle; radio i tak zagra
        }
    }

    private static int piFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private PendingIntent serviceIntent(String action, int req, boolean foreground) {
        Intent i = new Intent(this, RadioService.class).setAction(action);
        if (foreground && Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(this, req, i, piFlags());
        return PendingIntent.getService(this, req, i, piFlags());
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Station st = current();
        boolean active = isActive();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String stName = st != null ? st.name : "Radio RMF";
        boolean song = active && !songT.isEmpty();
        b.setSmallIcon(R.drawable.ic_stat_radio)
                .setContentTitle(song ? songT : stName)
                .setContentText(song ? (songA.isEmpty() ? stName : songA + " · " + stName) : statusLabel())
                .setContentIntent(PendingIntent.getActivity(this, 0, open, piFlags()))
                .setDeleteIntent(serviceIntent(ACTION_STOP, 1, false))
                .setOngoing(busy())
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Poprzednia", serviceIntent(ACTION_PREV, 2, true))
                .addAction(active ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        active ? "Stop" : "Graj",
                        active ? serviceIntent(ACTION_STOP, 3, false) : serviceIntent(ACTION_RESUME, 4, true))
                .addAction(android.R.drawable.ic_media_next, "Następna", serviceIntent(ACTION_NEXT, 5, true))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        Bitmap pic = (song && cover != null) ? cover : art;
        if (pic != null) b.setLargeIcon(pic);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }
}
