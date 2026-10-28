package pl.zooolw.carprobe;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sprawdza, co system tego radia udostępnia aplikacjom: właściwości systemu,
 * zainstalowane aplikacje (w tym dostawców danych), porty, Bluetooth, USB,
 * czujniki i ustawienia. Nic nie zmienia — tylko czyta i zapisuje raport.
 */
public class MainActivity extends Activity {

    private static final String[] KEYWORDS = {
            "temp", "can", "car", "auto", "vehicle", "obd", "gps", "ambient", "outside", "outdoor", "canbus", "bus", "vin", "speed",
            "carocean", "ckx", "zlink", "mcu", "status", "adapter", "sensor"
    };

    private TextView report;
    private ScrollView scroll;
    private Button btnRun, btnShare;
    private String lastReport = "";
    private volatile boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        btnRun = new Button(this);
        btnRun.setText("Uruchom ponownie");
        btnRun.setOnClickListener(v -> runProbes());
        buttons.addView(btnRun, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnShare = new Button(this);
        btnShare.setText("Udostępnij raport");
        btnShare.setOnClickListener(v -> share());
        buttons.addView(btnShare, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttons);

        scroll = new ScrollView(this);
        report = new TextView(this);
        report.setTextIsSelectable(true);
        report.setTextSize(12f);
        report.setTypeface(android.graphics.Typeface.MONOSPACE);
        report.setText("Trwa sprawdzanie…");
        scroll.addView(report);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        runProbes();
    }

    private void runProbes() {
        if (running) return;
        running = true;
        btnRun.setEnabled(false);
        report.setText("Trwa sprawdzanie…");
        new Thread(() -> {
            String text;
            try {
                text = buildReport();
            } catch (Throwable t) {
                text = "Błąd podczas sprawdzania: " + t;
            }
            String saved = save(text);
            final String out = text + "\n\nRaport zapisany: " + saved;
            runOnUiThread(() -> {
                lastReport = out;
                report.setText(out);
                btnRun.setEnabled(true);
                running = false;
            });
        }).start();
    }

    private void share() {
        if (lastReport.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Car Probe — raport");
        i.putExtra(Intent.EXTRA_TEXT, lastReport);
        startActivity(Intent.createChooser(i, "Udostępnij raport"));
    }

    private String save(String text) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File f = new File(dir, "car-probe.txt");
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(text.getBytes("UTF-8"));
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return "nie udało się zapisać (" + e + ")";
        }
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();

        section(sb, "1. Urządzenie");
        line(sb, "Producent: " + Build.MANUFACTURER);
        line(sb, "Model: " + Build.MODEL);
        line(sb, "Urządzenie: " + Build.DEVICE + " / płyta: " + Build.BOARD + " / sprzęt: " + Build.HARDWARE);
        line(sb, "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        line(sb, "Build: " + Build.DISPLAY);
        line(sb, "Samochód (funkcja systemu): " + getPackageManager().hasSystemFeature("android.hardware.type.automotive"));
        try {
            Class.forName("android.car.Car");
            line(sb, "Klasa android.car.Car: JEST");
        } catch (Throwable t) {
            line(sb, "Klasa android.car.Car: brak");
        }

        section(sb, "2. Broadcasty z aplikacji samochodowych (skan APK + 90 s nasłuchu)");
        scanAndListen(sb);

        section(sb, "3. Właściwości systemu (getprop)");
        List<String> props = run("getprop");
        if (props.isEmpty()) line(sb, "(brak wyniku)");
        for (String p : props) {
            if (matches(p)) line(sb, "* " + p);
        }
        line(sb, "(wszystkich właściwości: " + props.size() + ", pokazane tylko pasujące do słów kluczowych)");

        section(sb, "4. Zainstalowane aplikacje");
        List<PackageInfo> pkgs = getInstalledPackages();
        Collections.sort(pkgs, (a, b) -> a.packageName.compareTo(b.packageName));
        line(sb, "(zainstalowanych aplikacji: " + pkgs.size() + ", pokazane tylko pasujące)");
        for (PackageInfo p : pkgs) {
            if (!matches(p.packageName)) continue;
            boolean sys = (p.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            String label = String.valueOf(getPackageManager().getApplicationLabel(p.applicationInfo));
            line(sb, (sys ? "[SYS] " : "[usr] ") + p.packageName + "  \"" + label + "\"  v" + p.versionName);
        }

        section(sb, "5. Dostawcy danych (content providers)");
        for (PackageInfo p : pkgs) {
            if (p.providers == null) continue;
            for (ProviderInfo pi : p.providers) {
                if (!(matches(p.packageName) || matches(pi.authority))) continue;
                String auth = pi.authority == null ? "" : pi.authority.split(";")[0];
                line(sb, "* " + p.packageName + " -> " + pi.authority + (pi.exported ? "  (exported)" : ""));
                dumpProvider(sb, auth);
            }
        }

        section(sb, "6. Usługi i odbiorniki o nazwach związanych z autem");
        for (PackageInfo p : pkgs) {
            if (p.services != null) for (android.content.pm.ServiceInfo s : p.services) {
                if (matches(s.name) || matches(p.packageName)) line(sb, "service " + s.packageName + " / " + s.name + (s.exported ? " (exported)" : ""));
            }
            if (p.receivers != null) for (android.content.pm.ActivityInfo r : p.receivers) {
                if (matches(r.name) || matches(p.packageName)) line(sb, "receiver " + r.packageName + " / " + r.name + (r.exported ? " (exported)" : ""));
            }
        }

        section(sb, "7. Ustawienia systemu (content://settings)");
        dumpSettings(sb, Settings.System.CONTENT_URI, "system");
        dumpSettings(sb, Settings.Global.CONTENT_URI, "global");
        dumpSettings(sb, Settings.Secure.CONTENT_URI, "secure");

        section(sb, "8. Bluetooth");
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null) {
                line(sb, "Brak adaptera Bluetooth");
            } else {
                line(sb, "Włączony: " + bt.isEnabled() + ", nazwa: " + bt.getName());
                for (BluetoothDevice d : bt.getBondedDevices()) {
                    line(sb, "sparowane: " + d.getName() + " " + d.getAddress());
                }
            }
        } catch (Throwable t) {
            line(sb, "Błąd Bluetooth: " + t);
        }

        section(sb, "9. USB");
        try {
            UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
            Map<String, UsbDevice> devs = um.getDeviceList();
            if (devs.isEmpty()) line(sb, "Brak podłączonych urządzeń USB");
            for (UsbDevice d : devs.values()) {
                line(sb, d.getDeviceName() + "  " + d.getProductName() + "  VID " + d.getVendorId() + " PID " + d.getProductId());
            }
        } catch (Throwable t) {
            line(sb, "Błąd USB: " + t);
        }

        section(sb, "10. Czujniki (SensorManager)");
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        List<Sensor> sensors = sm.getSensorList(Sensor.TYPE_ALL);
        for (Sensor s : sensors) {
            line(sb, "type " + s.getType() + "  " + s.getName() + "  (" + s.getVendor() + ")");
        }
        if (sensors.isEmpty()) line(sb, "Brak czujników");

        return sb.toString();
    }

    /** Skanuje pliki dex aplikacji samochodowych (tylko odczyt) i nasłuchuje pasujących broadcastów. */
    private void scanAndListen(StringBuilder sb) {
        String[] pkgs = {"com.kyhero.car.myhost", "com.kyhero.car.myhost2", "com.xygala.pvcanset", "com.zjinnova.zlink", "com.carocean.mcuserver", "com.can.activity"};
        Set<String> candidates = new LinkedHashSet<>();
        for (String pkg : pkgs) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                List<String> strs = dexStrings(ai.sourceDir);
                int before = candidates.size();
                for (String x : strs) {
                    if (isCandidate(x)) candidates.add(x);
                }
                line(sb, pkg + ": tekstów pasujących = " + (candidates.size() - before));
            } catch (Throwable t) {
                line(sb, pkg + ": nie udało się odczytać APK (" + t.getClass().getSimpleName() + " " + t.getMessage() + ")");
            }
        }
        List<String> actions = new ArrayList<>();
        for (String c : candidates) {
            if (c.contains(".") && !c.contains(" ") && c.length() <= 120 && actions.size() < 80) actions.add(c);
        }
        line(sb, "Kandydaci (" + candidates.size() + "), nasłuchuję " + actions.size() + " z nich:");
        int shown = 0;
        for (String c : candidates) { if (shown++ < 150) line(sb, "  " + c); }
        if (candidates.size() > 150) line(sb, "  (pokazano 150 z " + candidates.size() + ")");

        List<String> got = listen(actions, 90);
        line(sb, "Odebrane broadcasty w 90 s: " + got.size());
        for (String g : got) line(sb, "  " + g);
    }

    private boolean isCandidate(String x) {
        String l = x.toLowerCase();
        if (x.length() < 6) return false;
        return l.contains("temp") || l.contains("action") || l.contains("canbus") || l.contains("outside")
                || l.contains("broadcast") || l.contains("kyhero") || l.contains("can_") || l.contains(".can")
                || l.contains("cansetup") || l.contains("zlink");
    }

    private List<String> dexStrings(String apkPath) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        try (ZipFile z = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".dex")) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (java.io.InputStream is = z.getInputStream(e)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                }
                byte[] d = bo.toByteArray();
                StringBuilder cur = new StringBuilder();
                for (byte b : d) {
                    if (b >= 0x20 && b < 0x7f) {
                        cur.append((char) b);
                    } else {
                        if (cur.length() >= 6) out.add(cur.toString());
                        cur.setLength(0);
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    private List<String> listen(List<String> actions, int seconds) {
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        if (actions.isEmpty()) return log;
        BroadcastReceiver rcv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, android.content.Intent i) {
                StringBuilder sb = new StringBuilder();
                sb.append(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()));
                sb.append(' ').append(i.getAction());
                try {
                    android.os.Bundle b = i.getExtras();
                    if (b != null) for (String k : b.keySet()) {
                        String v;
                        try { v = String.valueOf(b.get(k)); } catch (Throwable t) { v = "?"; }
                        if (v.length() > 120) v = v.substring(0, 120) + "…";
                        sb.append(" | ").append(k).append('=').append(v);
                    }
                } catch (Throwable ignored) { }
                if (log.size() < 300) log.add(sb.toString());
            }
        };
        IntentFilter f = new IntentFilter();
        for (String a : actions) f.addAction(a);
        try {
            registerReceiver(rcv, f);
            Thread.sleep(seconds * 1000L);
            unregisterReceiver(rcv);
        } catch (Throwable t) {
            log.add("błąd nasłuchu: " + t);
        }
        return log;
    }

    private void section(StringBuilder sb, String title) {
        sb.append("\n==== ").append(title).append(" ====\n");
    }

    private void line(StringBuilder sb, String s) {
        sb.append(s).append('\n');
    }

    private boolean matches(String s) {
        if (s == null) return false;
        String l = s.toLowerCase();
        for (String k : KEYWORDS) if (l.contains(k)) return true;
        return false;
    }

    private List<String> run(String... cmd) {
        List<String> out = new ArrayList<>();
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String s;
                while ((s = r.readLine()) != null) out.add(s);
            }
            p.waitFor();
        } catch (Throwable t) {
            out.add("błąd: " + t);
        }
        return out;
    }

    private void listDir(StringBuilder sb, String dir, String[] prefixes) {
        File d = new File(dir);
        String[] names = d.list();
        line(sb, dir + ": " + (names == null ? "niedostępne" : ""));
        if (names == null) return;
        Collections.sort(java.util.Arrays.asList(names));
        for (String n : names) {
            boolean ok = prefixes == null;
            if (!ok) for (String pre : prefixes) if (n.startsWith(pre)) { ok = true; break; }
            if (ok) {
                File f = new File(d, n);
                line(sb, "  " + n + (f.canRead() ? "  (czytelny)" : "") + (f.canWrite() ? " (zapisywalny)" : ""));
            }
        }
    }

    private void readFile(StringBuilder sb, String path) {
        line(sb, path + ":");
        try (BufferedReader r = new BufferedReader(new java.io.FileReader(path))) {
            String s;
            int n = 0;
            while ((s = r.readLine()) != null && n++ < 200) line(sb, "  " + s);
        } catch (Throwable t) {
            line(sb, "  niedostępne (" + t.getMessage() + ")");
        }
    }

    private List<PackageInfo> getInstalledPackages() {
        int flags = PackageManager.GET_PROVIDERS | PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES;
        try {
            return getPackageManager().getInstalledPackages(flags);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /** Odpytuje dostawcę danych (tylko odczyt, bez argumentów) i zapisuje kolumny oraz pierwsze wiersze. */
    private void dumpProvider(StringBuilder sb, String authority) {
        if (authority == null || authority.isEmpty()) return;
        Uri uri = Uri.parse("content://" + authority);
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c == null) { line(sb, "    brak odpowiedzi (null)"); return; }
            line(sb, "    kolumny: " + java.util.Arrays.toString(c.getColumnNames()));
            int n = 0;
            while (c.moveToNext() && n < 15) {
                n++;
                StringBuilder row = new StringBuilder("    wiersz " + n + ": ");
                for (int i = 0; i < c.getColumnCount(); i++) {
                    String v;
                    try { v = c.getString(i); } catch (Throwable t) { v = "(" + t.getClass().getSimpleName() + ")"; }
                    if (v != null && v.length() > 200) v = v.substring(0, 200) + "…";
                    row.append(c.getColumnName(i)).append('=').append(v).append("; ");
                }
                line(sb, row.toString());
            }
            line(sb, "    (wierszy w odpowiedzi: " + (n == 15 ? "15+" : String.valueOf(n)) + ")");
        } catch (Throwable t) {
            line(sb, "    błąd odpytania: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private void dumpSettings(StringBuilder sb, Uri uri, String name) {
        line(sb, "content://settings/" + name + ":");
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c == null) { line(sb, "  brak dostępu (null)"); return; }
            int nameCol = c.getColumnIndex("name");
            int valCol = c.getColumnIndex("value");
            int n = 0;
            while (c.moveToNext()) {
                n++;
                String key = nameCol >= 0 ? c.getString(nameCol) : "?";
                String val = valCol >= 0 ? c.getString(valCol) : "?";
                if (matches(key)) line(sb, "* " + key + " = " + val);
            }
            line(sb, "  (wierszy: " + n + ", pokazane tylko pasujące)");
        } catch (Throwable t) {
            line(sb, "  błąd: " + t);
        }
    }
}
