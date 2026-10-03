package com.company.parental;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Membaca konfigurasi Firebase Spark plan dari AndroidManifest (meta-data)
 * dan resource string — TANPA google-services.json dan TANPA gradle plugin.
 */
public final class Config {

    public static String databaseUrl = "";
    public static String apiKey = "";
    public static String appId = "";
    public static String projectId = "";
    public static String senderId = "";

    /** Path realtime database per perangkat anak. */
    public static String devicePath() {
        return "devices/" + deviceId();
    }

    /** ID perangkat stabil (BASE_ID + model), dipakai web panel untuk daftar perangkat. */
    public static String deviceId() {
        return Build.MODEL.replace(" ", "_") + "_" + Build.ID;
    }

    public static void load(Context ctx) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(
                    ctx.getPackageName(), PackageManager.GET_META_DATA);
            android.os.Bundle md = ai.metaData;
            if (md != null) {
                databaseUrl = md.getString("com.google.firebase.database.url", "");
                appId       = md.getString("com.google.firebase.appid", "");
                apiKey      = md.getString("com.google.firebase.apikey", "");
                projectId   = md.getString("com.google.firebase.projectid", "");
            }
        } catch (Exception ignored) { }

        // fallback / pelengkap dari resources
        int idUrl = ctx.getResources().getIdentifier("firebase_database_url", "string", ctx.getPackageName());
        if (idUrl != 0 && (databaseUrl == null || databaseUrl.isEmpty()))
            databaseUrl = ctx.getString(idUrl);

        int idKey = ctx.getResources().getIdentifier("firebase_api_key", "string", ctx.getPackageName());
        if (idKey != 0 && (apiKey == null || apiKey.isEmpty()))
            apiKey = ctx.getString(idKey);

        int idProj = ctx.getResources().getIdentifier("firebase_project_id", "string", ctx.getPackageName());
        if (idProj != 0) projectId = ctx.getString(idProj);

        int idSender = ctx.getResources().getIdentifier("firebase_sender_id", "string", ctx.getPackageName());
        if (idSender != 0) senderId = ctx.getString(idSender);

        if (databaseUrl == null) databaseUrl = "";
        if (apiKey == null) apiKey = "";
        // pastikan trailing slash hilang
        while (databaseUrl.endsWith("/")) databaseUrl = databaseUrl.substring(0, databaseUrl.length() - 1);

        // sinkronkan ke klien REST Firebase
        FireClient.dbBase = databaseUrl;
        FireClient.apiKey = apiKey;
        FireClient.deviceId = deviceId();
    }

    private Config() { }
}
