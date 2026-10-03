package com.company.parental;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Klien Firebase Realtime Database lewat REST API (berlaku sama untuk Spark plan).
 * Dipakai karena build APK memakai Steelwork (tanpa gradle) sehingga tidak ada
 * firebase-sdk — REST adalah jalur yang benar-benar berfungsi.
 *
 * Struktur data:
 *   devices/{deviceId}/state            -> ditulis anak (info + status)
 *   devices/{deviceId}/commands         -> ditulis orang tua (lock/unlock/blockApps/password)
 *   devices/{deviceId}/commands_ack     -> ditulis anak setelah command dieksekusi
 *
 * CATATAN kompatibilitas: TIDAK memakai lambda / method reference Java 8,
 * karena builder (Steelwork/APKBUILDER) mengompilasi dengan source level 1.7.
 */
public final class FireClient {

    public interface Cb { void run(Boolean ok); }
    public interface JsonCb { void run(JSONObject o); }

    private static final ExecutorService EX = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Diisi oleh Config.load(): URL root RTDB, mis. https://xxx.firebaseio.com */
    public static String dbBase = "";
    /** Diisi oleh Config.load(): id perangkat ini. */
    public static String deviceId = "device";
    /** Diisi oleh Config.load(): API key spark plan (opsional, utk query param auth). */
    public static String apiKey = "";

    /** Bangun URL child-path dengan auth key. */
    private static String url(String childPath) {
        String u = dbBase + "/devices/" + UriEnc.encode(deviceId)
                + "/" + childPath + ".json";
        return withAuth(u);
    }

    private static String withAuth(String u) {
        if (apiKey != null && !apiKey.isEmpty()
                && !apiKey.startsWith("AIzaSy_REPLACE")) {
            u += "?auth=" + apiKey;
        }
        return u;
    }

    /** PUT objek JSON ke path (mis. "state" atau "commands_ack"). */
    public static void put(final String childPath, final JSONObject body, final Cb cb) {
        EX.execute(new Runnable() {
            @Override public void run() {
                boolean ok;
                try {
                    HttpURLConnection c = open(url(childPath), "PUT");
                    OutputStream os = c.getOutputStream();
                    os.write(body.toString().getBytes("UTF-8"));
                    os.flush(); os.close();
                    ok = c.getResponseCode() < 400;
                    c.disconnect();
                } catch (Exception e) { ok = false; }
                post(cb, ok);
            }
        });
    }

    /** GET JSON dari path (mis. "commands"). */
    public static void get(final String childPath, final JsonCb cb) {
        EX.execute(new Runnable() {
            @Override public void run() {
                JSONObject out = null;
                try {
                    HttpURLConnection c = open(url(childPath), "GET");
                    int code = c.getResponseCode();
                    if (code == 200) {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(c.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        r.close();
                        String txt = sb.toString().trim();
                        if (!txt.isEmpty() && !txt.equals("null")) out = new JSONObject(txt);
                    }
                    c.disconnect();
                } catch (Exception ignored) { }
                final JSONObject f = out;
                MAIN.post(new Runnable() {
                    @Override public void run() { cb.run(f); }
                });
            }
        });
    }

    /** Daftar semua perangkat node "devices". */
    public static void getAllDevices(final JsonCb cb) {
        EX.execute(new Runnable() {
            @Override public void run() {
                JSONObject out = null;
                try {
                    HttpURLConnection c = open(withAuth(dbBase + "/devices.json"), "GET");
                    if (c.getResponseCode() == 200) {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(c.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        r.close();
                        String txt = sb.toString().trim();
                        if (!txt.isEmpty() && !txt.equals("null")) out = new JSONObject(txt);
                    }
                    c.disconnect();
                } catch (Exception ignored) { }
                final JSONObject f = out;
                MAIN.post(new Runnable() {
                    @Override public void run() { cb.run(f); }
                });
            }
        });
    }

    private static HttpURLConnection open(String u, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        // key spark plan dipakai sebagai query param auth
        c.connect();
        return c;
    }

    private static void post(final Cb cb, final boolean ok) {
        if (cb != null) MAIN.post(new Runnable() {
            @Override public void run() { cb.run(ok); }
        });
    }

    /** Encoder path-safe (firebase menolak karakter . # $ [ ] / ). */
    static final class UriEnc {
        static String encode(String s) {
            return s.replaceAll("[.#$\\[\\]*/]", "_");
        }
    }

    private FireClient() { }
}
