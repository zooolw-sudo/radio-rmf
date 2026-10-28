package pl.zooolw.carprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Car Probe: diagnostyka radia samochodowego.
 * Wersja skupiona na CAN: stan magistrali, interfejsy AIDL aplikacji „Informacje o pojeździe”,
 * teksty z kodu związane z temperaturą oraz paczka ZIP z plikami APK do analizy.
 * Niczego nie zmienia w aucie ani w radiu — tylko czyta.
 */
public class MainActivity extends Activity {

    private static final String HOST = "com.kyhero.car.myhost";
    private static final String[] APK_PACKAGES = {
            "com.kyhero.car.myhost", "com.kyhero.car.myhost2", "com.xygala.pvcanset"
    };
    private static final long MAX_ZIP_BYTES = 120L * 1024 * 1024;

    private TextView report;
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

        ScrollView scroll = new ScrollView(this);
        report = new TextView(this);
        report.setTextIsSelectable(true);
        report.setTextSize(12f);
        report.setTypeface(android.graphics.Typeface.MONOSPACE);
        report.setText("Trwa sprawdzanie…");
        scroll.addView(report);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // dostęp do pamięci: odczyt wtyczki CAN i zapis paczki do folderu Download
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE"}, 1);
        } else {
            runProbes();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        runProbes();
    }

    private void runProbes() {
        if (running) return;
        running = true;
        btnRun.setEnabled(false);
        report.setText("Trwa sprawdzanie… (kopiowanie plików może potrwać do minuty)");
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

    /** Zapisuje raport w folderze Download (jeśli wolno) i w folderze aplikacji. */
    private String save(String text) {
        StringBuilder where = new StringBuilder();
        for (File dir : outputDirs()) {
            try {
                File f = new File(dir, "car-probe.txt");
                try (OutputStream os = new FileOutputStream(f)) {
                    os.write(text.getBytes("UTF-8"));
                }
                if (where.length() > 0) where.append(" oraz ");
                where.append(f.getAbsolutePath());
            } catch (Exception ignored) { }
        }
        return where.length() > 0 ? where.toString() : "nie udało się zapisać";
    }

    /** Najpierw publiczny folder Download, potem prywatny folder aplikacji. */
    private List<File> outputDirs() {
        List<File> dirs = new ArrayList<>();
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dl != null && (dl.exists() || dl.mkdirs()) && dl.canWrite()) dirs.add(dl);
        } catch (Throwable ignored) { }
        File own = getExternalFilesDir(null);
        if (own == null) own = getFilesDir();
        dirs.add(own);
        return dirs;
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();

        section(sb, "1. Urządzenie");
        line(sb, "Model: " + Build.MODEL + " (" + Build.MANUFACTURER + "), Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        try {
            line(sb, "Car Probe: " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Throwable ignored) { }

        section(sb, "2. Stan CAN (ustawienia systemu)");
        canSettings(sb);

        section(sb, "3. Interfejsy AIDL aplikacji „Informacje o pojeździe” (tylko nazwa, bez wywołań)");
        probeServices(sb);

        section(sb, "4. Teksty z kodu: temperatura i moduły danych CAN");
        codeStrings(sb);

        section(sb, "5. Paczka plików do analizy");
        buildZip(sb);

        return sb.toString();
    }

    // ---------- 2. stan CAN ----------

    private void canSettings(StringBuilder sb) {
        try (Cursor c = getContentResolver().query(Settings.System.CONTENT_URI, null, null, null, null)) {
            if (c == null) { line(sb, "brak dostępu"); return; }
            int nameCol = c.getColumnIndex("name");
            int valCol = c.getColumnIndex("value");
            while (c.moveToNext()) {
                String key = nameCol >= 0 ? c.getString(nameCol) : "";
                String val = valCol >= 0 ? c.getString(valCol) : "";
                String l = key == null ? "" : key.toLowerCase(Locale.US);
                if (l.contains("canbus") || l.contains("canset") || l.contains("car_config_car_id") || l.contains("carselect") || l.contains("temp")) {
                    line(sb, key + " = " + val);
                }
            }
        } catch (Throwable t) {
            line(sb, "błąd: " + t);
        }
    }

    // ---------- 3. usługi AIDL ----------

    private void probeServices(StringBuilder sb) {
        String[] services = {
                HOST + ".Canset.AidlToOutDataService",
                HOST + ".Canset.AidlToOutData6115Service",
                HOST + ".Canset.CanbusService"
        };
        for (String cls : services) {
            final String[] result = {null};
            final CountDownLatch latch = new CountDownLatch(1);
            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    try {
                        if (binder == null) {
                            result[0] = "połączono, ale usługa nie zwróciła interfejsu";
                        } else {
                            // getInterfaceDescriptor() tylko pyta o nazwę interfejsu — nie wywołuje żadnej metody usługi
                            result[0] = "interfejs: " + binder.getInterfaceDescriptor()
                                    + (binder.pingBinder() ? " (odpowiada)" : " (nie odpowiada)");
                        }
                    } catch (Throwable t) {
                        result[0] = "błąd odczytu nazwy: " + t;
                    }
                    latch.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) { }
            };
            String shortName = cls.substring(cls.lastIndexOf('.') + 1);
            try {
                Intent i = new Intent().setComponent(new ComponentName(HOST, cls));
                boolean ok = bindService(i, conn, Context.BIND_AUTO_CREATE);
                if (!ok) {
                    line(sb, shortName + ": nie udało się połączyć");
                    continue;
                }
                boolean done = latch.await(6, TimeUnit.SECONDS);
                line(sb, shortName + ": " + (done ? result[0] : "brak odpowiedzi w 6 s"));
                try { unbindService(conn); } catch (Throwable ignored) { }
            } catch (Throwable t) {
                line(sb, shortName + ": błąd " + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        }
    }

    // ---------- 4. teksty z kodu ----------

    private void codeStrings(StringBuilder sb) {
        for (String pkg : new String[]{"com.kyhero.car.myhost", "com.kyhero.car.myhost2"}) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                Set<String> hits = new LinkedHashSet<>();
                for (String s : dexStrings(ai.sourceDir)) {
                    String c = clean(s);
                    if (isInteresting(c)) hits.add(c);
                }
                line(sb, pkg + " (" + hits.size() + "):");
                int n = 0;
                for (String h : hits) {
                    if (n++ >= 250) { line(sb, "  (pokazano 250 z " + hits.size() + ")"); break; }
                    line(sb, "  " + h);
                }
            } catch (Throwable t) {
                line(sb, pkg + ": nie udało się odczytać (" + t.getClass().getSimpleName() + ")");
            }
        }
    }

    /** Usuwa znak długości, który w plikach dex stoi tuż przed tekstem, gdy wypada w zakresie drukowalnym. */
    private String clean(String s) {
        if (s.length() >= 2 && s.charAt(0) == (char) (s.length() - 1)) return s.substring(1);
        return s;
    }

    private boolean isInteresting(String x) {
        String l = x.toLowerCase(Locale.US);
        if (l.contains("template") || l.contains("attempt") || l.contains("temporar") || l.contains("tempfile")
                || l.contains("appcompat") || l.contains("actionbar")) return false;
        return l.contains("temp") || l.contains("wendu") || l.contains("outside") || l.contains("outdoor")
                || l.contains("canbus/data") || l.contains("canbus.data") || l.contains("icanbus")
                || l.contains("aidltoout") || l.contains("/canbus/plugin");
    }

    private List<String> dexStrings(String apkPath) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        try (ZipFile z = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".dex")) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream is = z.getInputStream(e)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                }
                StringBuilder cur = new StringBuilder();
                for (byte b : bo.toByteArray()) {
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

    // ---------- 5. paczka ZIP ----------

    private void buildZip(StringBuilder sb) {
        Map<String, File> files = new LinkedHashMap<>();

        for (String pkg : APK_PACKAGES) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                files.put("apk/" + pkg + ".apk", new File(ai.sourceDir));
                if (Build.VERSION.SDK_INT >= 21 && ai.splitSourceDirs != null) {
                    int k = 0;
                    for (String sp : ai.splitSourceDirs) files.put("apk/" + pkg + ".split" + (k++) + ".apk", new File(sp));
                }
            } catch (Throwable t) {
                line(sb, pkg + ": brak (" + t.getClass().getSimpleName() + ")");
            }
        }

        // wtyczka CAN dla auta (np. P1_501.apk) — szukamy w pamięci radia i w katalogach systemowych
        List<File> roots = new ArrayList<>();
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) {
            roots.add(new File(ext, "CanBus"));
            roots.add(ext);
        }
        for (String p : new String[]{"/sdcard/CanBus", "/mnt/sdcard/CanBus", "/storage/sdcard0/CanBus",
                "/system/app", "/system/priv-app", "/system/vendor/app", "/vendor/app", "/oem", "/system/etc"}) {
            roots.add(new File(p));
        }
        Set<String> seen = new LinkedHashSet<>();
        int found = 0;
        for (File r : roots) {
            List<File> hits = new ArrayList<>();
            boolean deep = r.getName().equalsIgnoreCase("CanBus");
            findFiles(r, deep ? 5 : 3, deep, hits);
            for (File f : hits) {
                try {
                    String key = f.getCanonicalPath();
                    if (!seen.add(key)) continue;
                } catch (Throwable ignored) { }
                files.put("plugin" + f.getAbsolutePath(), f);
                found++;
            }
        }
        line(sb, "Pliki wtyczek CAN znalezione poza aplikacjami: " + found);

        File zipFile = null;
        for (File dir : outputDirs()) {
            File z = new File(dir, "car-apk.zip");
            try {
                long total = writeZip(z, files, sb);
                zipFile = z;
                line(sb, "ZIP: " + z.getAbsolutePath() + " (" + (total / 1024) + " kB przed spakowaniem, plik " + (z.length() / 1024) + " kB)");
                break;
            } catch (Throwable t) {
                line(sb, "Nie udało się zapisać w " + dir + ": " + t.getMessage());
            }
        }
        if (zipFile == null) line(sb, "Nie udało się utworzyć paczki ZIP.");
    }

    /** Szuka plików związanych z CAN; w folderze CanBus bierze wszystko, gdzie indziej tylko pasujące nazwy. */
    private void findFiles(File dir, int depth, boolean takeAll, List<File> out) {
        if (dir == null || depth < 0 || out.size() > 60) return;
        File[] list;
        try { list = dir.listFiles(); } catch (Throwable t) { return; }
        if (list == null) return;
        for (File f : list) {
            if (out.size() > 60) return;
            String n = f.getName().toLowerCase(Locale.US);
            if (f.isDirectory()) {
                if (n.equals("android") || n.equals("dcim") || n.equals("music") || n.equals("movies") || n.equals("pictures")) continue;
                boolean inCan = takeAll || n.contains("canbus") || n.contains("kyhero");
                findFiles(f, depth - 1, inCan, out);
            } else if (f.canRead()) {
                boolean match = n.contains("canbus") || n.contains("kyhero") || n.contains("pvcan") || n.matches("p\\d+_\\d+\\.apk");
                if ((takeAll && (n.endsWith(".apk") || n.endsWith(".jar") || n.endsWith(".dex") || n.endsWith(".so")
                        || n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".txt") || n.endsWith(".ini") || n.endsWith(".cfg")))
                        || match) {
                    if (f.length() < 50L * 1024 * 1024) out.add(f);
                }
            }
        }
    }

    private long writeZip(File zip, Map<String, File> files, StringBuilder sb) throws Exception {
        long total = 0;
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            byte[] buf = new byte[65536];
            for (Map.Entry<String, File> e : files.entrySet()) {
                File f = e.getValue();
                if (!f.canRead()) {
                    line(sb, "  pominięto (brak dostępu): " + f);
                    continue;
                }
                if (total + f.length() > MAX_ZIP_BYTES) {
                    line(sb, "  pominięto (limit rozmiaru): " + f);
                    continue;
                }
                try (InputStream is = new FileInputStream(f)) {
                    zos.putNextEntry(new ZipEntry(e.getKey().replaceFirst("^/", "")));
                    int n;
                    while ((n = is.read(buf)) > 0) zos.write(buf, 0, n);
                    zos.closeEntry();
                    total += f.length();
                    line(sb, "  + " + e.getKey() + " (" + (f.length() / 1024) + " kB)");
                } catch (Throwable t) {
                    line(sb, "  błąd przy " + f + ": " + t.getMessage());
                }
            }
        }
        return total;
    }

    // ---------- pomocnicze ----------

    private void section(StringBuilder sb, String title) {
        sb.append("\n==== ").append(title).append(" ====\n");
    }

    private void line(StringBuilder sb, String s) {
        sb.append(s).append('\n');
    }
}
