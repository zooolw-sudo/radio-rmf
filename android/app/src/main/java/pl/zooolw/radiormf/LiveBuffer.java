package pl.zooolw.radiormf;

import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * Pamięć na ostatnie minuty strumienia (pierścień bajtów) z indeksem czasu.
 * Pobieranie dopisuje pełne ramki, odtwarzacz czyta od wskazanego miejsca.
 * Pozycje są liczone od początku strumienia (rosną bez końca), czas dźwięku
 * w mikrosekundach liczony z długości ramek, bez dziur po zerwaniach połączenia.
 * Zmiana formatu dźwięku (inny serwer) zaczyna nowy segment.
 * Bez zależności od Androida (testowane zwykłą Javą).
 */
final class LiveBuffer {

    private final byte[] data;
    private long writePos = 0;          // ile bajtów zapisano od początku
    private long mediaUs = 0;           // ile dźwięku zapisano od początku
    private boolean closed = false;

    // indeks co ~250 ms: pozycja (początek ramki), czas dźwięku, czas przyjścia
    private static final int IDX = 16384;
    private static final long IDX_STEP_US = 250_000;
    private final long[] idxPos = new long[IDX];
    private final long[] idxUs = new long[IDX];
    private final long[] idxWall = new long[IDX];
    private int idxStart = 0, idxCount = 0;
    private long lastIdxUs = -IDX_STEP_US;

    // segmenty: początek w bajtach i opis formatu
    private static final int SEG = 64;
    private final long[] segStart = new long[SEG];
    private final String[] segKey = new String[SEG];
    private final int[] segKind = new int[SEG];
    private int segCount = 0;           // numer następnego segmentu (rośnie bez końca)

    long lastWriteWall = 0;

    LiveBuffer(int capacityBytes) {
        data = new byte[capacityBytes];
    }

    synchronized int capacity() { return data.length; }
    synchronized long writePos() { return writePos; }
    synchronized long totalUs() { return mediaUs; }
    synchronized long oldestPos() { return Math.max(0, writePos - data.length); }

    synchronized int segments() { return segCount; }
    synchronized int lastSegment() { return segCount - 1; }
    synchronized int segmentKind(int s) { return valid(s) ? segKind[s % SEG] : 0; }
    synchronized String segmentKey(int s) { return valid(s) ? segKey[s % SEG] : null; }
    synchronized long segmentStart(int s) { return valid(s) ? segStart[s % SEG] : -1; }

    /** Koniec segmentu (początek następnego) albo -1, gdy segment jeszcze trwa. */
    synchronized long segmentEnd(int s) {
        if (s + 1 >= segCount) return -1;
        return segStart[(s + 1) % SEG];
    }

    /** Segment, do którego należy pozycja. */
    synchronized int segmentAt(long pos) {
        for (int s = segCount - 1; s >= Math.max(0, segCount - SEG); s--) {
            if (segStart[s % SEG] <= pos) return s;
        }
        return Math.max(0, segCount - SEG);
    }

    private boolean valid(int s) { return s >= 0 && s < segCount && s >= segCount - SEG; }

    /** Dopisuje pełną ramkę. Nowy format (klucz) zaczyna nowy segment. */
    synchronized void append(byte[] b, int off, int len, long durUs, String key, int kind, long wallMs) {
        if (closed || len <= 0) return;
        if (segCount == 0 || !key.equals(segKey[(segCount - 1) % SEG])) {
            segStart[segCount % SEG] = writePos;
            segKey[segCount % SEG] = key;
            segKind[segCount % SEG] = kind;
            segCount++;
            lastIdxUs = -IDX_STEP_US;   // nowy segment zawsze ma wpis w indeksie na początku
        }
        if (mediaUs - lastIdxUs >= IDX_STEP_US) {
            int k = (idxStart + idxCount) % IDX;
            if (idxCount == IDX) idxStart = (idxStart + 1) % IDX; else idxCount++;
            idxPos[k] = writePos;
            idxUs[k] = mediaUs;
            idxWall[k] = wallMs;
            lastIdxUs = mediaUs;
        }
        int p = (int) (writePos % data.length);
        int first = Math.min(len, data.length - p);
        System.arraycopy(b, off, data, p, first);
        if (first < len) System.arraycopy(b, off + first, data, 0, len - first);
        writePos += len;
        mediaUs += durUs;
        lastWriteWall = wallMs;
        dropOldIndex();
        notifyAll();
    }

    private void dropOldIndex() {
        long oldest = oldestPos();
        while (idxCount > 0 && idxPos[idxStart] < oldest) {
            idxStart = (idxStart + 1) % IDX;
            idxCount--;
        }
    }

    /**
     * Czyta od pozycji {@code pos}; czeka na dane do {@code waitMs}.
     * Zwraca liczbę bajtów, 0 gdy nic nie przyszło, -1 gdy segment się skończył.
     * Gdy {@code pos} wypadło już z pamięci, zwraca -2 (trzeba przeskoczyć).
     */
    synchronized int read(long pos, int segment, byte[] dst, int off, int len, long waitMs) throws IOException {
        long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            if (closed) throw new IOException("bufor zamknięty");
            if (pos < oldestPos()) return -2;
            long end = segmentEnd(segment);
            long limit = end >= 0 ? end : writePos;
            if (pos < limit) {
                int n = (int) Math.min(len, limit - pos);
                int p = (int) (pos % data.length);
                int first = Math.min(n, data.length - p);
                System.arraycopy(data, p, dst, off, first);
                if (first < n) System.arraycopy(data, 0, dst, off + first, n - first);
                return n;
            }
            if (end >= 0) return -1;
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return 0;
            try {
                wait(Math.min(left, 250));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("przerwane");
            }
        }
    }

    synchronized void close() {
        closed = true;
        notifyAll();
    }

    // ---------- czas ----------

    private int idxFloor(long us) {
        // ostatni wpis z czasem <= us (wyszukiwanie binarne w pierścieniu)
        int lo = 0, hi = idxCount - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (idxUs[(idxStart + mid) % IDX] <= us) { ans = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return ans;
    }

    private int idxFloorPos(long pos) {
        int lo = 0, hi = idxCount - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (idxPos[(idxStart + mid) % IDX] <= pos) { ans = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return ans;
    }

    /** Najstarszy czas dźwięku, który jest jeszcze w pamięci. */
    synchronized long oldestUs() {
        return idxCount == 0 ? mediaUs : idxUs[idxStart];
    }

    /** Pozycja początku ramki dla czasu dźwięku (z dokładnością ~250 ms), nie wcześniejsza niż to, co jest w pamięci. */
    synchronized long posForUs(long us) {
        if (idxCount == 0) return writePos;
        int i = idxFloor(us);
        if (i < 0) i = 0;
        return idxPos[(idxStart + i) % IDX];
    }

    /** Czas dźwięku na początku ramki w pozycji (z indeksu). */
    synchronized long usAtPos(long pos) {
        if (idxCount == 0) return mediaUs;
        int i = idxFloorPos(pos);
        if (i < 0) i = 0;
        return idxUs[(idxStart + i) % IDX];
    }

    /** Kiedy (zegar ścienny, ms) przyszedł dźwięk o danym czasie; 0 gdy nie wiadomo. */
    synchronized long wallAtUs(long us) {
        if (idxCount == 0) return 0;
        int i = idxFloor(us);
        if (i < 0) i = 0;
        return idxWall[(idxStart + i) % IDX];
    }
}
