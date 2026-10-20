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
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    static volatile RadioService instance;
    static volatile Listener listener;

    private static class Station {
        String id = "", name = "", tag = "";
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
    private Bitmap art;

    private final Runnable timeout = this::fail;
    private final Runnable reopen = this::openCurrent;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // inna aplikacja przejęła dźwięk na stałe (np. inny odtwarzacz)
                stopPlayback("stopped");
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                focusFactor = 0f;      // np. rozmowa telefoniczna: wycisz, ale nie rozłączaj
                applyVolume();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                focusFactor = 0.25f;   // komunikat nawigacji: przycisz
                applyVolume();
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                focusFactor = 1f;
                applyVolume();
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
        } else if (!isActive()) {
            stopSelf();
        }
        if (fg && stations.isEmpty()) stopPlayback("stopped");
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!isActive()) stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        abandonFocus();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
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
        JSONObject o = new JSONObject();
        try {
            o.put("ev", state);
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

    private boolean isActive() {
        return "playing".equals(state) || "connecting".equals(state) || "buffering".equals(state);
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
        main.removeCallbacks(timeout);
        main.removeCallbacks(reopen);
        releasePlayer();
        if (current() == null) { stopPlayback("stopped"); return; }
        urlIdx = 0;
        hadPlayed = false;
        retried = false;
        focusFactor = 1f;
        requestFocus();
        if (wifiLock != null) wifiLock.acquire();
        openCurrent();
    }

    private void openCurrent() {
        releasePlayer();
        Station st = current();
        if (st == null) { stopPlayback("stopped"); return; }
        if (urlIdx >= st.urls.size()) { stopPlayback("failed"); return; }

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
        if (current() == null) return;
        releasePlayer();
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
        main.removeCallbacks(timeout);
        main.removeCallbacks(reopen);
        releasePlayer();
        abandonFocus();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
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

    private void requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener, main)
                        .setWillPauseWhenDucked(false)
                        .build();
            }
            am.requestAudioFocus(focusRequest);
        } else {
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
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
        updateSession();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && !stations.isEmpty()) nm.notify(NOTIF_ID, buildNotification());
        Listener l = listener;
        if (l != null) l.onEvent(stateJson());
    }

    private String statusLabel() {
        switch (state) {
            case "playing": return "Na żywo";
            case "connecting": return "Łączę…";
            case "buffering": return "Buforuję…";
            case "failed": return "Brak połączenia — spróbuj ponownie";
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
            default: ps = PlaybackState.STATE_STOPPED; break;
        }
        PlaybackState.Builder b = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(ps, PlaybackState.PLAYBACK_POSITION_UNKNOWN, isActive() ? 1f : 0f);
        if ("failed".equals(state)) b.setErrorMessage("Brak połączenia");
        session.setPlaybackState(b.build());

        Station st = current();
        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, st != null ? st.name : "Radio RMF")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, statusLabel())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Radio RMF");
        if (art != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
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
        b.setSmallIcon(R.drawable.ic_stat_radio)
                .setContentTitle(st != null ? st.name : "Radio RMF")
                .setContentText(statusLabel())
                .setContentIntent(PendingIntent.getActivity(this, 0, open, piFlags()))
                .setDeleteIntent(serviceIntent(ACTION_STOP, 1, false))
                .setOngoing(active)
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
        if (art != null) b.setLargeIcon(art);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }
}
