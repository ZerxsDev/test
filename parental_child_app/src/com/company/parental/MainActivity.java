package com.company.parental;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aktivitas utama aplikasi anak.
 * UI kontrol dibuat dengan HTML (assets/web/index.html) yang memanggil native
 * melalui JS bridge di bawah — tombol ON/OFF kunci layar & blokir aplikasi benar-benar dari HTML.
 */
public class MainActivity extends Activity {

    private WebView web;
    private static final String APP_URL = "file:///android_asset/web/index.html";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Config.load(this);

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "ParentalAndroid");
        web.setWebViewClient(new WebViewClient());
        web.loadUrl(APP_URL);
        setContentView(web);

        startParentalServices();
    }

    private void startParentalServices() {
        Intent s = new Intent(this, ParentalService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
        try { startService(new Intent(this, BlockerService.class)); } catch (Exception ignored) { }
    }

    /* =================== JEMBATAN HTML -> ANDROID =================== */
    public class Bridge {

        /** Info dasar perangkat untuk header HTML. */
        @JavascriptInterface
        public String getInfo() throws Exception {
            JSONObject o = new JSONObject();
            o.put("device", Config.deviceId());
            o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            o.put("package", getPackageName());
            o.put("dbUrl", Config.databaseUrl);
            o.put("firebaseReady", Config.databaseUrl.startsWith("https://")
                    && !Config.apiKey.startsWith("AIzaSy_REPLACE"));
            return o.toString();
        }

        /** Status izin penting utk HTML. */
        @JavascriptInterface
        public String getStatus() throws Exception {
            JSONObject o = new JSONObject();
            o.put("adminActive", LockCtl.isAdminActive(MainActivity.this));
            o.put("overlayAllowed", BlockerService.canDraw(MainActivity.this));
            o.put("usageAllowed", hasUsageAccess());
            o.put("lockEnabled", Store.isLockEnabled(MainActivity.this));
            o.put("passwordSet", !Store.getPassword(MainActivity.this).isEmpty());
            JSONArray blocked = new JSONArray();
            for (String p : Store.getBlocked(MainActivity.this)) blocked.put(p);
            o.put("blockedPackages", blocked);
            return o.toString();
        }

        /** Pasang password kunci layar dari form HTML. */
        @JavascriptInterface
        public void setPassword(final String pass) {
            Store.setPassword(MainActivity.this, pass == null ? "" : pass);
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Password kunci disimpan", Toast.LENGTH_SHORT).show();
                pushStateToHtml();
            });
        }

        /** Verifikasi password HTML terhadap yang tersimpan (buka kunci = matikan lock). */
        @JavascriptInterface
        public String verifyPassword(String pass) {
            boolean ok = pass != null && pass.equals(Store.getPassword(MainActivity.this));
            return String.valueOf(ok);
        }

        /** ON/OFF kunci layar dari toggle HTML. Return JSON status. */
        @JavascriptInterface
        public String setLock(final boolean on) {
            Store.setLockEnabled(MainActivity.this, on);
            String res;
            if (on) {
                if (!LockCtl.isAdminActive(MainActivity.this)) {
                    requestAdmin();
                    res = "{\"ok\":false,\"reason\":\"admin\"}";
                } else {
                    boolean ok = LockCtl.lock(MainActivity.this);
                    res = "{\"ok\":" + ok + "}";
                }
            } else {
                LockCtl.unlock(MainActivity.this);
                res = "{\"ok\":true}";
            }
            final String fr = res;
            runOnUiThread(MainActivity.this::pushStateToHtml);
            return fr;
        }

        /** Kunci layar sekarang (tombol HTML). */
        @JavascriptInterface
        public String lockNow() {
            if (!LockCtl.isAdminActive(MainActivity.this)) {
                requestAdmin();
                return "{\"ok\":false,\"reason\":\"admin\"}";
            }
            return "{\"ok\":" + LockCtl.lock(MainActivity.this) + "}";
        }

        /** Buka kembali layar / hapus kebijakan paksa bila admin aktif. */
        @JavascriptInterface
        public String unlockNow() {
            Store.setLockEnabled(MainActivity.this, false);
            LockCtl.unlock(MainActivity.this);
            runOnUiThread(MainActivity.this::pushStateToHtml);
            return "{\"ok\":true}";
        }

        /** Daftar aplikasi yang bisa diblokir (launchable apps), JSON utk HTML. */
        @JavascriptInterface
        public String listApps() throws Exception {
            PackageManager pm = getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            Map<String, String> names = new HashMap<>();
            for (ResolveInfo ri : ris) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                CharSequence ls = ri.loadLabel(pm);
                names.put(pkg, ls == null ? pkg : ls.toString());
            }
            Set<String> blocked = Store.getBlocked(MainActivity.this);
            JSONArray arr = new JSONArray();
            List<String> keys = new ArrayList<>(names.keySet());
            Collections.sort(keys);
            for (String pkg : keys) {
                JSONObject o = new JSONObject();
                o.put("pkg", pkg);
                o.put("name", names.get(pkg));
                o.put("blocked", blocked.contains(pkg));
                arr.put(o);
            }
            return arr.toString();
        }

        /** Blokir / buka blokir satu aplikasi dari checkbox HTML. */
        @JavascriptInterface
        public String blockApp(final String pkg, final boolean block) {
            Set<String> s = Store.getBlocked(MainActivity.this);
            if (block) s.add(pkg); else s.remove(pkg);
            Store.setBlocked(MainActivity.this, s);
            ensureBlockerPerms();
            runOnUiThread(MainActivity.this::pushStateToHtml);
            return "{\"ok\":true,\"blocked\":" + block + ",\"count\":" + s.size() + "}";
        }

        /** Set seluruh daftar blokir sekaligus (dipakai sinkronisasi web panel). */
        @JavascriptInterface
        public void setBlockedAll(final String csvPkgs) {
            Set<String> s = new HashSet<>();
            if (csvPkgs != null && !csvPkgs.trim().isEmpty())
                for (String p : csvPkgs.split(",")) if (!p.trim().isEmpty()) s.add(p.trim());
            Store.setBlocked(MainActivity.this, s);
            runOnUiThread(MainActivity.this::pushStateToHtml);
        }

        /** Minta izin device admin lewat layar sistem (untuk force-lock). */
        @JavascriptInterface
        public void requestAdmin() {
            runOnUiThread(() -> {
                if (LockCtl.isAdminActive(MainActivity.this)) return;
                ComponentName cn = new ComponentName(MainActivity.this, AdminReceiver.class);
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Diperlukan agar orang tua dapat mengunci layar perangkat anak.");
                try { startActivityForResult(intent, 1001); } catch (Exception ignored) { }
            });
        }

        /** Buka Settings tampilkan-di-atas-aplikasi-lain bila belum diizinkan. */
        @JavascriptInterface
        public void requestOverlay() {
            runOnUiThread(() -> {
                if (!BlockerService.canDraw(MainActivity.this)) {
                    try { startActivity(BlockerService.overlaySettingsIntent(MainActivity.this)); }
                    catch (Exception ignored) { }
                }
            });
        }

        /** Buka Settings akses penggunaan (usage access) utk deteksi foreground. */
        @JavascriptInterface
        public void requestUsageAccess() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
                catch (Exception ignored) { }
            });
        }

        /** Trigger publish state ke firebase segera (sinkron dgn web panel). */
        @JavascriptInterface
        public void syncNow() {
            startParentalServices();
            FireClient.get("commands", cmds -> runOnUiThread(MainActivity.this::pushStateToHtml));
        }
    }

    /* =================== util =================== */

    private boolean hasUsageAccess() {
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents ev = usm.queryEvents(now - 1000, now);
            return ev != null; // tidak melempar exception => akses tersedia
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureBlockerPerms() {
        if (!BlockerService.canDraw(this)) {
            runOnUiThread(() -> {
                try { startActivity(BlockerService.overlaySettingsIntent(this)); }
                catch (Exception ignored) { }
            });
        }
        startParentalServices();
    }

    /** Kirim status terbaru ke javascript HTML: window.onNativeStatus(json). */
    private void pushStateToHtml() {
        try {
            JSONObject o = new JSONObject();
            o.put("adminActive", LockCtl.isAdminActive(this));
            o.put("overlayAllowed", BlockerService.canDraw(this));
            o.put("usageAllowed", hasUsageAccess());
            o.put("lockEnabled", Store.isLockEnabled(this));
            o.put("passwordSet", !Store.getPassword(this).isEmpty());
            JSONArray b = new JSONArray();
            for (String p : Store.getBlocked(this)) b.put(p);
            o.put("blockedPackages", b);
            web.evaluateJavascript("window.onNativeStatus && window.onNativeStatus("
                    + o.toString() + ")", null);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        pushStateToHtml();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
