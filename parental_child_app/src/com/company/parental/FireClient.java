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
 */
public final class FireClient {

    public interface Cb { void run(Boolean ok); }

    private static final ExecutorService EX = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Bangun URL child-path dengan auth key. */
    private static String url(String childPath) {
        String u = Config.databaseUrl + "/devices/" + UriEnc.encode(Config.deviceId())
                + "/" + childPath + ".json";
        return withAuth(u);
    }

    private static String withAuth(String u) {
        if (Config.apiKey != null && !Config.apiKey.isEmpty()
                && !Config.apiKey.startsWith("AIzaSy_REPLACE")) {
            u += "?auth=" + Config.apiKey;
        }
        return u;
    }

    /** PUT objek JSON ke path (mis. "state" atau "commands_ack"). */
    public static void put(final String childPath, final JSONObject body, final Cb cb) {
        EX.execute(() -> {
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
        });
    }

    /** GET JSON dari path (mis. "commands"). */
    public static void get(final String childPath, final JsonCb cb) {
        EX.execute(() -> {
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
            MAIN.post(() -> cb.run(f));
        });
    }

    /** Daftar semua perangkat (untuk web panel dicontohkan di JS; dipakai app utk self-check). */
    public static void getAllDevices(final JsonCb cb) {
        EX.execute(() -> {
            JSONObject out = null;
            try {
                HttpURLConnection c = open(withAuth(Config.databaseUrl + "/devices.json"), "GET");
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
            MAIN.post(() -> cb.run(f));
        });
    }

    public interface JsonCb { void run(JSONObject o); }

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

    private static void post(Cb cb, boolean ok) {
        if (cb != null) MAIN.post(() -> cb.run(ok));
    }

    /** Encoder path-safe (firebase menolak karakter . # $ [ ]). */
    static final class UriEnc {
        static String encode(String s) {
            return s.replaceAll("[.#$\\[\\]\\*/]", "_");
        }
    }

    private FireClient() { }
}
