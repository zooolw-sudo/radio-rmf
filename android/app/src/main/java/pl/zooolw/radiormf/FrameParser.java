package pl.zooolw.radiormf;

/**
 * Tnie strumień radia na pełne ramki dźwięku (AAC w ADTS albo MP3).
 * Dzięki temu w buforze nie ma urwanych kawałków: po zerwaniu połączenia
 * niedokończona ramka jest wyrzucana, a nowe połączenie zaczyna się od pełnej ramki.
 * Bez zależności od Androida (testowane zwykłą Javą).
 */
final class FrameParser {

    interface Sink {
        /** Pełna ramka; {@code key} opisuje parametry dźwięku (zmiana = inny format). */
        void frame(byte[] buf, int off, int len, long durationUs, String key);
    }

    static final int ADTS = 1;
    static final int MP3 = 2;

    private static final int[] AAC_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    // MP3: kb/s dla [wersja 1|2][warstwa 1..3][indeks]
    private static final int[][] BR_V1 = {
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448},   // warstwa I
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},      // warstwa II
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}};      // warstwa III
    private static final int[][] BR_V2 = {
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}};
    private static final int[][] MP3_RATES = {
            {11025, 12000, 8000},    // MPEG 2.5
            {0, 0, 0},
            {22050, 24000, 16000},   // MPEG 2
            {44100, 48000, 32000}};  // MPEG 1

    final int kind;
    private byte[] carry = new byte[64 * 1024];
    private int len = 0;
    private boolean synced = false;
    private String streamKey = null;    // parametry bieżącego połączenia
    private int skipId3 = 0;
    private long framesOut = 0;
    private long droppedBytes = 0;

    // ostatnio odczytany nagłówek
    private int hLen;
    private long hDurUs;
    private String hKey;
    private int hRate;
    private int hBitrate;

    FrameParser(int kind) { this.kind = kind; }

    /** Nowe połączenie: niedokończona ramka z poprzedniego jest wyrzucana. */
    void reset() {
        droppedBytes += len;
        len = 0;
        synced = false;
        streamKey = null;
        skipId3 = 0;
    }

    long frames() { return framesOut; }
    long dropped() { return droppedBytes; }
    String key() { return streamKey; }
    int sampleRate() { return hRate; }
    int bitrateKbps() { return hBitrate; }

    String describe() {
        if (streamKey == null) return kind == ADTS ? "AAC" : "MP3";
        if (kind == ADTS) return "AAC " + (hRate / 1000) + " kHz (ADTS)";
        return "MP3 " + (hBitrate > 0 ? hBitrate + " kb/s " : "") + (hRate / 1000) + " kHz";
    }

    void feed(byte[] b, int off, int n, Sink sink) {
        if (n <= 0) return;
        if (len + n > carry.length) {
            if (len + n > 4 * 1024 * 1024) {   // coś jest bardzo nie tak: zacznij od nowa
                droppedBytes += len;
                len = 0;
                synced = false;
            }
            byte[] bigger = new byte[Math.max(carry.length * 2, len + n)];
            System.arraycopy(carry, 0, bigger, 0, len);
            carry = bigger;
        }
        System.arraycopy(b, off, carry, len, n);
        len += n;
        process(sink);
    }

    private void process(Sink sink) {
        int pos = 0;
        while (true) {
            if (skipId3 > 0) {
                int s = Math.min(skipId3, len - pos);
                pos += s;
                skipId3 -= s;
                if (skipId3 > 0) break;
            }
            if (!synced) {
                int found = findSync(pos);
                if (found == -2) break;              // za mało danych, czekamy
                if (found < 0) {                      // nic w tym kawałku: zostaw ogon na nagłówek
                    int keep = Math.min(len - pos, 16);
                    droppedBytes += (len - pos) - keep;
                    pos = len - keep;
                    break;
                }
                if (found >= 1_000_000_000) {         // znacznik ID3 na początku strumienia
                    int at = found - 1_000_000_000;
                    droppedBytes += at - pos;
                    skipId3 = id3Size(at);
                    pos = at;
                    continue;
                }
                droppedBytes += found - pos;
                pos = found;
                synced = true;
            }
            if (len - pos < 10) break;
            if (!header(pos) || (streamKey != null && !hKey.equals(streamKey))) {
                synced = false;                       // zgubiony rytm ramek: szukamy dalej
                pos++;
                droppedBytes++;
                continue;
            }
            if (len - pos < hLen) break;              // ramka jeszcze niepełna
            streamKey = hKey;
            framesOut++;
            sink.frame(carry, pos, hLen, hDurUs, hKey);
            pos += hLen;
        }
        if (pos > 0) {
            System.arraycopy(carry, pos, carry, 0, len - pos);
            len -= pos;
        }
    }

    /**
     * Szuka początku ramki: nagłówek poprawny i kolejny nagłówek tuż za ramką zgodny.
     * Zwraca pozycję, -1 gdy brak, -2 gdy trzeba więcej danych, 1e9+pozycja dla znacznika ID3.
     */
    private int findSync(int from) {
        for (int i = from; i + 10 <= len; i++) {
            // znacznik ID3 bywa tylko na samym początku połączenia
            if (i == from && streamKey == null && carry[i] == 'I' && carry[i + 1] == 'D' && carry[i + 2] == '3'
                    && (carry[i + 3] & 0xFF) < 0xFF && (carry[i + 6] & 0x80) == 0) {
                return 1_000_000_000 + i;
            }
            if ((carry[i] & 0xFF) != 0xFF) continue;
            if (!header(i)) continue;
            int l1 = hLen;
            String k1 = hKey;
            if (i + l1 + 10 > len) return -2;
            if (header(i + l1) && hKey.equals(k1)) {
                header(i);   // przywróć dane pierwszej ramki
                return i;
            }
        }
        return -1;
    }

    private int id3Size(int at) {
        int s = ((carry[at + 6] & 0x7F) << 21) | ((carry[at + 7] & 0x7F) << 14)
                | ((carry[at + 8] & 0x7F) << 7) | (carry[at + 9] & 0x7F);
        boolean footer = (carry[at + 5] & 0x10) != 0;
        return 10 + s + (footer ? 10 : 0);
    }

    private boolean header(int p) {
        return kind == ADTS ? adts(p) : mp3(p);
    }

    private boolean adts(int p) {
        int b0 = carry[p] & 0xFF, b1 = carry[p + 1] & 0xFF, b2 = carry[p + 2] & 0xFF,
                b3 = carry[p + 3] & 0xFF, b4 = carry[p + 4] & 0xFF, b5 = carry[p + 5] & 0xFF,
                b6 = carry[p + 6] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xF6) != 0xF0) return false;
        int profile = (b2 >> 6) & 3;
        int sf = (b2 >> 2) & 0xF;
        if (sf >= AAC_RATES.length) return false;
        int ch = ((b2 & 1) << 2) | ((b3 >> 6) & 3);
        int flen = ((b3 & 3) << 11) | (b4 << 3) | ((b5 >> 5) & 7);
        int hdr = (b1 & 1) == 1 ? 7 : 9;
        if (flen <= hdr || flen > 8192) return false;
        int blocks = (b6 & 3) + 1;
        hLen = flen;
        hRate = AAC_RATES[sf];
        hDurUs = 1024L * blocks * 1_000_000L / hRate;
        hKey = "adts/" + profile + "/" + sf + "/" + ch;
        hBitrate = 0;
        return true;
    }

    private boolean mp3(int p) {
        int b0 = carry[p] & 0xFF, b1 = carry[p + 1] & 0xFF, b2 = carry[p + 2] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) return false;
        int ver = (b1 >> 3) & 3;       // 0: 2.5, 2: 2, 3: 1
        int layerBits = (b1 >> 1) & 3; // 3: I, 2: II, 1: III
        if (ver == 1 || layerBits == 0) return false;
        int layer = 4 - layerBits;     // 1..3
        int bri = (b2 >> 4) & 0xF;
        int sri = (b2 >> 2) & 3;
        if (bri == 0 || bri == 15 || sri == 3) return false;
        int pad = (b2 >> 1) & 1;
        int rate = MP3_RATES[ver][sri];
        int kbps = (ver == 3 ? BR_V1 : BR_V2)[layer - 1][bri];
        int flen;
        int samples;
        if (layer == 1) {
            flen = (12 * kbps * 1000 / rate + pad) * 4;
            samples = 384;
        } else if (layer == 2) {
            flen = 144 * kbps * 1000 / rate + pad;
            samples = 1152;
        } else {
            boolean v1 = ver == 3;
            flen = (v1 ? 144 : 72) * kbps * 1000 / rate + pad;
            samples = v1 ? 1152 : 576;
        }
        if (flen < 21 || flen > 8192) return false;
        hLen = flen;
        hRate = rate;
        hBitrate = kbps;
        hDurUs = samples * 1_000_000L / rate;
        hKey = "mp3/" + ver + "/" + layer + "/" + sri;
        return true;
    }

    /** Rozpoznaje format z nagłówka Content-Type; 0 gdy nieznany. */
    static int kindFromType(String type) {
        if (type == null) return 0;
        String t = type.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("aac") || t.contains("mp4a")) return ADTS;
        if (t.contains("mpeg") || t.contains("mp3")) return MP3;
        return 0;
    }

    /** Rozpoznaje format po pierwszych bajtach (dwie zgodne ramki pod rząd); 0 gdy nieznany. */
    static int sniff(byte[] b, int n) {
        for (int kind : new int[]{ADTS, MP3}) {
            FrameParser fp = new FrameParser(kind);
            final int[] count = {0};
            fp.feed(b, 0, n, (buf, off, len, dur, key) -> count[0]++);
            if (count[0] >= 2) return kind;
        }
        return 0;
    }
}
