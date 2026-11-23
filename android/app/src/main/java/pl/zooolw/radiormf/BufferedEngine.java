package pl.zooolw.radiormf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mp3.Mp3Extractor;
import androidx.media3.extractor.ts.AdtsExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;

/**
 * Odtwarzanie z zapasem na tunele.
 *
 * Wątek pobierania sam utrzymuje połączenie ze strumieniem (ponawia je po utracie zasięgu)
 * i dopisuje pełne ramki dźwięku do pamięci. ExoPlayer gra z tej pamięci, więc krótka
 * utrata zasięgu nie przerywa dźwięku, dopóki starcza zapasu.
 *
 * Zapas rośnie tylko wtedy, gdy odtwarzanie stoi, a pobieranie trwa:
 *  - po wyczerpaniu zapasu (dłuższy brak zasięgu) odtwarzanie czeka, aż uzbiera się 2 minuty
 *    (można to przerwać: {@link #playNow()}),
 *  - w czasie rozmowy telefonicznej ({@link #hold(boolean)}) — po rozmowie gra od miejsca przerwania.
 * Wszystkie metody poza wątkiem pobierania działają na wątku głównym.
 */
final class BufferedEngine {

    interface Callback {
        /** Zmienił się stan albo zapas (wołane co pół sekundy). */
        void onEngineChange();
        /** Ta stacja nie zagra w trybie z zapasem (np. nieznany format) — trzeba użyć zwykłego odtwarzacza. */
        void onEngineFatal(String reason);
        /** Bardzo długo bez danych: koniec prób. */
        void onEngineGiveUp();
    }

    interface Tls { SSLSocketFactory get(); }

    static final long RESERVE_TARGET_MS = 120_000;            // docelowy zapas: 2 minuty
    private static final int BUFFER_BYTES = 8 * 1024 * 1024;  // pamięć na strumień (kilka–kilkanaście minut)
    private static final long GIVE_UP_MS = 15 * 60_000L;      // tyle czekamy na zasięg
    private static final long REBUILD_EXTRA_MS = 45_000;      // za wolny internet: graj mimo niepełnego zapasu
    private static final long CAP_SLACK_MS = 15_000;          // tolerancja ponad 2 minuty po długiej rozmowie
    private static final long TICK_MS = 500;

    private final Context ctx;
    private final Handler main;
    private final Callback cb;
    private final String ua;
    private final Tls tls;
    private final LiveBuffer buf = new LiveBuffer(BUFFER_BYTES);
    private final List<String> urls = new ArrayList<>();

    // pobieranie (wątek "downloader"; pola czytane z wątku głównego)
    private Thread downloader;
    private volatile boolean closed = false;
    private volatile HttpURLConnection conn;
    private volatile int urlIdx = 0;
    private volatile boolean connected = false;
    private volatile long lastDataAt = 0;        // SystemClock.elapsedRealtime
    private volatile boolean everData = false;
    private volatile String format = "";
    private volatile String lastNetError = "";
    private volatile int reconnects = 0;
    private volatile int kindNow = 0;

    // odtwarzanie
    private ExoPlayer player;
    private int seg = -1;           // segment pamięci grany przez odtwarzacz
    private long anchorUs = 0;      // czas dźwięku na początku bieżącego elementu
    private boolean itemStarted = false;
    private boolean everPlayed = false;
    private boolean held = false;
    private boolean rebuilding = false;   // zapas się skończył: stoimy i zbieramy nowy
    private long rebuildFrom = 0;         // od kiedy znów płyną dane
    private long starveMark = 0;          // writePos w chwili wyczerpania zapasu
    private long startedAt;
    private float volume = 1f;
    private int playerErrors = 0;
    private long errorsFrom = 0;
    private String lastPlayerError = "";
    private int rebuilds = 0;
    private final Runnable tick = this::tick;

    BufferedEngine(Context ctx, Handler main, Callback cb, String userAgent, Tls tls) {
        this.ctx = ctx.getApplicationContext();
        this.main = main;
        this.cb = cb;
        this.ua = userAgent;
        this.tls = tls;
    }

    // ---------- sterowanie ----------

    void start(List<String> list, int firstUrl) {
        urls.clear();
        urls.addAll(list);
        if (urls.isEmpty()) { cb.onEngineFatal("brak adresów"); return; }
        urlIdx = Math.max(0, Math.min(firstUrl, urls.size() - 1));
        startedAt = SystemClock.elapsedRealtime();

        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                // odtwarzacz trzyma do 2,5 min dźwięku; start po 2 s jak zwykle
                .setBufferDurationsMs(150_000, 150_000, 2_000, 2_000)
                .setTargetBufferBytes(32 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
        player = new ExoPlayer.Builder(ctx)
                .setLooper(main.getLooper())
                .setLoadControl(lc)
                .setAudioAttributes(aa, false)        // fokus dźwięku obsługuje usługa (rozmowy, nawigacja)
                .setHandleAudioBecomingNoisy(false)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();
        player.setVolume(volume);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int st) {
                if (!closed) onPlayerState(st);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (closed) return;
                if (isPlaying) {
                    itemStarted = true;
                    everPlayed = true;
                }
                cb.onEngineChange();
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                if (!closed) onError(e);
            }
        });

        downloader = new Thread(this::downloadLoop, "radio-download");
        downloader.start();
        main.postDelayed(tick, TICK_MS);
    }

    void release() {
        closed = true;
        main.removeCallbacks(tick);
        HttpURLConnection c = conn;
        if (c != null) {
            // zamknięcie gniazda z innego wątku przerywa czekanie na dane
            new Thread(() -> { try { c.disconnect(); } catch (Exception ignored) { } }).start();
        }
        if (downloader != null) downloader.interrupt();
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        buf.close();
    }

    void setVolume(float v) {
        volume = v;
        if (player != null) player.setVolume(v);
    }

    /** Wstrzymanie na czas rozmowy: pobieranie trwa, po rozmowie gra od miejsca przerwania (najwyżej 2 min wstecz). */
    void hold(boolean on) {
        if (player == null || on == held) return;
        held = on;
        if (on) {
            player.setPlayWhenReady(false);
        } else {
            if (seg >= 0 && reserveMs() > RESERVE_TARGET_MS + CAP_SLACK_MS) {
                startAt(buf.totalUs() - RESERVE_TARGET_MS * 1000);   // za długa przerwa: zostaw 2 minuty
            } else if (!rebuilding) {
                player.setPlayWhenReady(true);
            }
        }
        cb.onEngineChange();
    }

    /** Przerwij zbieranie zapasu i graj od razu. */
    void playNow() {
        if (!rebuilding) return;
        rebuilding = false;
        if (player != null && !held) player.setPlayWhenReady(true);
        cb.onEngineChange();
    }

    /** Wróć na żywo (porzuca zapas). */
    void goLive() {
        if (player == null || seg < 0) return;
        rebuilding = false;
        startAt(buf.totalUs() - 3_000_000);
        cb.onEngineChange();
    }

    // ---------- stan dla usługi ----------

    boolean everPlayed() { return everPlayed; }
    boolean isHeld() { return held; }
    boolean isRebuilding() { return rebuilding; }
    boolean isPlayingAudio() { return player != null && player.isPlaying(); }
    int urlIndex() { return urlIdx; }
    String url() { int i = urlIdx; return i < urls.size() ? urls.get(i) : ""; }

    /** Dane płyną z serwera (ostatnie kilka sekund). */
    boolean receiving() {
        return connected && SystemClock.elapsedRealtime() - lastDataAt < 4000;
    }

    /** Dane przyszły po wyczerpaniu zapasu (zbieranie trwa, a nie tylko czeka na zasięg). */
    boolean rebuildFlowing() {
        return rebuilding && buf.writePos() > starveMark;
    }

    /** Czeka na pierwsze dane albo na zasięg (nic nie gra i nic nie przychodzi). */
    boolean starvingNoData() {
        return !receiving() && (rebuilding ? !rebuildFlowing() : !isPlayingAudio());
    }

    private long playingUs() {
        if (player == null || seg < 0) return buf.totalUs();
        return anchorUs + Math.max(0, player.getCurrentPosition()) * 1000;
    }

    /** Ile dźwięku jest pobrane, a jeszcze niezagrane (to wystarczy na tyle bez zasięgu). */
    long reserveMs() {
        if (seg < 0) return 0;
        return Math.max(0, (buf.totalUs() - playingUs()) / 1000);
    }

    /** O ile gra później niż na żywo. */
    long delayMs() {
        if (seg < 0) return 0;
        long wall = buf.wallAtUs(playingUs());
        if (wall <= 0) return 0;
        return Math.max(0, System.currentTimeMillis() - wall);
    }

    String diag() {
        long now = SystemClock.elapsedRealtime();
        String host = "";
        try { String h = Uri.parse(url()).getHost(); if (h != null) host = h; } catch (Exception ignored) { }
        StringBuilder s = new StringBuilder();
        s.append("Odtwarzacz: z zapasem na tunele (ExoPlayer)");
        s.append("\nStrumień: ").append(host).append(format.isEmpty() ? "" : " · " + format)
                .append(" · ").append(receiving() ? "dane płyną"
                        : (lastDataAt == 0 ? "jeszcze brak danych" : "brak danych od " + (now - lastDataAt) / 1000 + " s"))
                .append(", ponowne połączenia: ").append(reconnects);
        if (!lastNetError.isEmpty()) s.append("\nOstatni błąd sieci: ").append(lastNetError);
        s.append("\nZapas: ").append(mmss(reserveMs())).append(" (cel ").append(mmss(RESERVE_TARGET_MS)).append(")")
                .append(" · opóźnienie: ").append(mmss(delayMs()))
                .append(" · w pamięci ").append(String.format(Locale.ROOT, "%.1f MB",
                        Math.min(buf.writePos(), (long) buf.capacity()) / 1048576.0));
        if (rebuilding) s.append(" · zbieram zapas");
        if (held) s.append(" · wstrzymane");
        s.append("\nOdbudowy zapasu: ").append(rebuilds);
        if (!lastPlayerError.isEmpty()) s.append("\nBłąd odtwarzacza: ").append(lastPlayerError);
        return s.toString();
    }

    static String mmss(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    // ---------- odtwarzacz ----------

    /** Zaczyna grać od ramki najbliższej podanemu czasowi dźwięku (nie wcześniej niż to, co jest w pamięci). */
    private void startAt(long us) {
        if (player == null || buf.segments() == 0) return;
        long target = Math.max(buf.oldestUs(), us);
        long pos = buf.posForUs(target);
        int s = buf.segmentAt(pos);
        long segStart = buf.segmentStart(s);
        if (pos < segStart) pos = segStart;
        seg = s;
        anchorUs = buf.usAtPos(pos);
        itemStarted = false;
        final int segF = s;
        final long posF = pos;
        final int kind = buf.segmentKind(s);
        ExtractorsFactory ef = kind == FrameParser.MP3
                ? () -> new Extractor[]{new Mp3Extractor()}
                : () -> new Extractor[]{new AdtsExtractor()};
        DataSource.Factory dsf = () -> new BufferSource(buf, segF, posF);
        MediaSource src = new ProgressiveMediaSource.Factory(dsf, ef)
                .createMediaSource(MediaItem.fromUri(Uri.parse("radiobuf://segment" + s + "/" + pos)));
        player.setMediaSource(src);
        player.prepare();
        player.setPlayWhenReady(!held && !rebuilding);
    }

    private void onPlayerState(int st) {
        if (player == null) return;
        if (st == Player.STATE_ENDED) {
            // koniec segmentu (serwer z innym formatem dźwięku): graj dalej od następnego
            long next = seg >= 0 ? buf.segmentEnd(seg) : -1;
            if (next >= 0) startAt(buf.usAtPos(next));
            else startAt(buf.totalUs() - 2_000_000);
        } else if (st == Player.STATE_BUFFERING && itemStarted && player.getPlayWhenReady() && !held) {
            // zapas się skończył: zatrzymaj i zbieraj nowy, zamiast grać po kawałku
            rebuilding = true;
            rebuildFrom = 0;
            starveMark = buf.writePos();
            rebuilds++;
            player.setPlayWhenReady(false);
        }
        cb.onEngineChange();
    }

    private void onError(PlaybackException e) {
        lastPlayerError = e.getErrorCodeName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        if (!everPlayed) {
            cb.onEngineFatal("odtwarzacz nie zagrał tego strumienia (" + e.getErrorCodeName() + ")");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - errorsFrom > 60_000) { errorsFrom = now; playerErrors = 0; }
        if (++playerErrors > 3) {
            cb.onEngineFatal("powtarzające się błędy odtwarzacza (" + e.getErrorCodeName() + ")");
            return;
        }
        startAt(playingUs());   // spróbuj od miejsca, w którym grało
    }

    private void kick() {
        if (closed) return;
        main.removeCallbacks(tick);
        tick();
    }

    private void tick() {
        if (closed || player == null) return;
        long now = SystemClock.elapsedRealtime();
        if (seg < 0 && buf.segments() > 0) {
            startAt(buf.totalUs() - RESERVE_TARGET_MS * 1000);   // pierwsze dane (albo zebrane w czasie rozmowy)
        }
        if (rebuilding && !held) {
            if (rebuildFlowing() && rebuildFrom == 0) rebuildFrom = now;
            boolean enough = reserveMs() >= RESERVE_TARGET_MS;
            boolean tooSlow = rebuildFrom > 0 && now - rebuildFrom > RESERVE_TARGET_MS + REBUILD_EXTRA_MS;
            if (enough || tooSlow) {
                rebuilding = false;
                player.setPlayWhenReady(true);
            }
        }
        long last = lastDataAt != 0 ? lastDataAt : startedAt;
        boolean silent = rebuilding || !isPlayingAudio();
        if (!held && silent && now - last > GIVE_UP_MS) {
            cb.onEngineGiveUp();
            return;
        }
        cb.onEngineChange();
        main.postDelayed(tick, TICK_MS);
    }

    // ---------- pobieranie ----------

    private static final class HardFail extends IOException {
        HardFail(String m) { super(m); }
    }

    private void downloadLoop() {
        byte[] rb = new byte[16 * 1024];
        Set<Integer> hardFailed = new HashSet<>();
        int failsHere = 0;
        FrameParser fp = null;
        while (!closed) {
            boolean gotData = false;
            int idx = urlIdx;
            String url = urls.get(idx);
            HttpURLConnection c = null;
            try {
                c = open(url, 0, false);
                conn = c;
                InputStream in = c.getInputStream();
                int kind = FrameParser.kindFromType(c.getContentType());
                byte[] head = null;
                int have = 0;
                if (kind == 0) {
                    // nieznany nagłówek: rozpoznaj po pierwszych bajtach
                    head = new byte[32 * 1024];
                    while (have < head.length) {
                        int n = in.read(head, have, head.length - have);
                        if (n < 0) break;
                        have += n;
                    }
                    kind = FrameParser.sniff(head, have);
                    if (kind == 0) throw new HardFail("nieobsługiwany format (" + c.getContentType() + ")");
                }
                if (fp == null || fp.kind != kind) fp = new FrameParser(kind);
                fp.reset();
                kindNow = kind;
                final FrameParser parser = fp;
                FrameParser.Sink sink = (b, off, len, dur, key) ->
                        buf.append(b, off, len, dur, key, parser.kind, System.currentTimeMillis());
                long framesBefore = fp.frames();
                if (head != null && have > 0) fp.feed(head, 0, have, sink);
                while (!closed) {
                    int n = in.read(rb, 0, rb.length);
                    if (n < 0) throw new IOException("serwer zakończył strumień");
                    if (n == 0) continue;
                    fp.feed(rb, 0, n, sink);
                    if (fp.frames() > framesBefore) {
                        lastDataAt = SystemClock.elapsedRealtime();
                        if (!gotData) {
                            gotData = true;
                            connected = true;
                            failsHere = 0;
                            hardFailed.clear();
                            format = fp.describe();
                            if (everData) reconnects++;
                            if (!everData) {
                                everData = true;
                                main.post(this::kick);   // zacznij grać od razu, bez czekania na zegar
                            }
                        }
                        framesBefore = fp.frames();
                    }
                }
            } catch (HardFail e) {
                lastNetError = e.getMessage();
                hardFailed.add(idx);
            } catch (InterruptedIOException e) {
                lastNetError = e instanceof SocketTimeoutException ? "brak danych (przekroczony czas)" : "przerwane";
            } catch (UnknownHostException e) {
                lastNetError = "brak internetu (" + e.getMessage() + ")";
            } catch (IOException e) {
                lastNetError = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            } catch (RuntimeException e) {
                lastNetError = e.toString();
            } finally {
                connected = false;
                conn = null;
                if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
            }
            if (closed) break;

            if (!everData && !urls.isEmpty() && hardFailed.size() >= urls.size()) {
                // każdy serwer odpowiada, ale nie da się tego zagrać z zapasem
                final String why = lastNetError;
                main.post(() -> { if (!closed) cb.onEngineFatal(why); });
                return;
            }
            if (!online()) {
                sleep(2000);           // tunel: czekamy, nie zmieniając serwera
                continue;
            }
            if (!gotData) {
                failsHere++;
                if (failsHere >= 2 && urls.size() > 1) {
                    urlIdx = (idx + 1) % urls.size();
                    failsHere = 0;
                }
                sleep(Math.min(4000, 700L * Math.max(1, failsHere)));
            } else {
                sleep(300);            // zerwało w trakcie: od razu jeszcze raz
            }
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private HttpURLConnection open(String url, int hops, boolean extraTrust) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (extraTrust && c instanceof HttpsURLConnection) {
            SSLSocketFactory f = tls != null ? tls.get() : null;
            if (f == null) throw new HardFail("certyfikat serwera nieznany");
            ((HttpsURLConnection) c).setSSLSocketFactory(f);
        }
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);     // brak danych przez 8 s = połączenie zerwane (np. tunel)
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept", "*/*");
        int code;
        try {
            code = c.getResponseCode();
        } catch (SSLException e) {
            c.disconnect();
            if (!extraTrust) return open(url, hops, true);
            throw new HardFail("SSL: " + e.getMessage());
        }
        if (code >= 300 && code < 400 && hops < 4) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (loc == null) throw new HardFail("przekierowanie bez adresu");
            return open(new URL(new URL(url), loc).toString(), hops + 1, extraTrust);
        }
        if (code != 200) {
            c.disconnect();
            throw new HardFail("HTTP " + code);
        }
        return c;
    }

    @SuppressWarnings("deprecation")
    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            if (Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                if (n == null) return false;
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                return nc == null || nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            NetworkInfo i = cm.getActiveNetworkInfo();
            return i != null && i.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    // ---------- źródło danych dla ExoPlayera: czyta z pamięci ----------

    private static final class BufferSource extends BaseDataSource {
        private final LiveBuffer b;
        private final int seg;
        private final long start;
        private long pos;
        private Uri uri;
        private boolean opened;

        BufferSource(LiveBuffer b, int seg, long start) {
            super(false);
            this.b = b;
            this.seg = seg;
            this.start = start;
        }

        @Override
        public long open(DataSpec spec) throws IOException {
            uri = spec.uri;
            transferInitializing(spec);
            pos = start + spec.position;
            opened = true;
            transferStarted(spec);
            return C.LENGTH_UNSET;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (true) {
                int n = b.read(pos, seg, target, offset, length, 1000);
                if (n > 0) {
                    pos += n;
                    bytesTransferred(n);
                    return n;
                }
                if (n == -1) return C.RESULT_END_OF_INPUT;
                if (n == -2) {
                    // bardzo długa przerwa: najstarszy dźwięk już wypadł z pamięci
                    long p = b.posForUs(b.oldestUs() + 1_000_000);
                    if (b.segmentAt(p) != seg) return C.RESULT_END_OF_INPUT;
                    pos = p;
                    continue;
                }
                if (Thread.interrupted()) throw new InterruptedIOException("przerwane");
            }
        }

        @Override
        public Uri getUri() {
            return uri;
        }

        @Override
        public void close() {
            if (opened) {
                opened = false;
                transferEnded();
            }
            uri = null;
        }
    }
}
