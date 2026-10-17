package com.company.children.activities;

import android.annotation.SuppressLint;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.company.children.BuildConfig;
import com.company.children.R;
import com.company.children.helpers.Prefs;
import com.company.children.receivers.DeviceAdminReceiverX;
import com.company.children.services.SocketService;

/**
 * Alur aplikasi children:
 *  LANGKAH 1 (permStep) : minta SEMUA izin + device admin (tanpa jadi launcher default).
 *  LANGKAH 2 (pairStep) : tampilkan kotak 6 digit angka random dari server utk ditautkan
 *                         ke web parental.
 *  LANGKAH 3 (dashStep) : setelah ditautkan -> simpan sesi & tampilkan dashboard anak.
 */
public class MainActivity extends AppCompatActivity {

    public static final String ACTION_CODE   = "com.company.children.CODE_UPDATE";
    public static final String EXTRA_CODE    = "code";
    public static final String ACTION_PAIRED = "com.company.children.PAIRED";

    private static final int REQ_NOTIF = 100;

    private ScrollView permStep;
    private android.widget.LinearLayout pairStep, dashStep;
    private TextView tvCode, tvPairStatus, tvDashCode, tvConnState, tvServerInfo, tvLockState, tvBlockState;
    private TextView rowNotif, rowOverlay, rowUsage, rowAdmin;
    private CodeReceiver codeReceiver;

    /* ============================ SETUP ============================= */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        permStep    = findViewById(R.id.permStep);
        pairStep    = findViewById(R.id.pairStep);
        dashStep    = findViewById(R.id.dashStep);
        tvCode      = findViewById(R.id.tvCode);
        tvPairStatus= findViewById(R.id.tvPairStatus);
        tvDashCode  = findViewById(R.id.tvDashCode);
        tvConnState = findViewById(R.id.tvConnState);
        tvServerInfo= findViewById(R.id.tvServerInfo);
        tvLockState = findViewById(R.id.tvLockState);
        tvBlockState= findViewById(R.id.tvBlockState);
        rowNotif    = findViewById(R.id.rowNotif);
        rowOverlay  = findViewById(R.id.rowOverlay);
        rowUsage    = findViewById(R.id.rowUsage);
        rowAdmin    = findViewById(R.id.rowAdmin);

        Button btnGrantAll = findViewById(R.id.btnGrantAll);
        btnGrantAll.setOnClickListener(v -> requestRemainingPermissions());

        Button btnRecheck = findViewById(R.id.btnRecheck);
        btnRecheck.setOnClickListener(v -> { SocketService.start(this); refreshDashboard(); });

        // Service selalu jalan agar koneksi ke web parental tidak putus
        SocketService.start(this);

        codeReceiver = new CodeReceiver();
        IntentFilter f = new IntentFilter(ACTION_CODE);
        f.addAction(ACTION_PAIRED);
        registerReceiver(codeReceiver, f,
                Build.VERSION.SDK_INT >= 33 ? RECEIVER_NOT_EXPORTED : 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (allPermissionsGranted()) {
            if (Prefs.isPaired(this)) showDashboard();
            else showPairStep();
        } else {
            showPermStep();
        }
        refreshPermRows();
    }

    /* ========================= LANGKAH 1 ============================ */
    private void showPermStep() {
        permStep.setVisibility(VISIBLE);
        pairStep.setVisibility(GONE);
        dashStep.setVisibility(GONE);
    }

    private boolean allPermissionsGranted() {
        return notifGranted()
                && Settings.canDrawOverlays(this)
                && hasUsageAccess()
                && isDeviceAdminActive();
    }

    private boolean notifGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasUsageAccess() {
        try {
            android.app.AppOpsManager ops = (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        getApplicationInfo().uid, getPackageName());
            } else {
                mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        getApplicationInfo().uid, getPackageName());
            }
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    private boolean isDeviceAdminActive() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(adminComponent());
    }

    private ComponentName adminComponent() {
        return new ComponentName(this, DeviceAdminReceiverX.class);
    }

    /** Minta sisa izin satu per satu — LAUNCHER sengaja TIDAK diminta. */
    private void requestRemainingPermissions() {
        if (!notifGranted() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return; // lanjutkan via onRequestPermissionsResult
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Aktifkan 'Tampilan di atas aplikasi lain'", Toast.LENGTH_SHORT).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
            return;
        }
        if (!hasUsageAccess()) {
            Toast.makeText(this, "Aktifkan 'Akses penggunaan aplikasi'", Toast.LENGTH_SHORT).show();
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
            return;
        }
        if (!isDeviceAdminActive()) {
            Toast.makeText(this, "Aktifkan Device Admin (Kunci Perangkat)", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent());
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Diperlukan orang tua untuk mengunci layar HP anak dari jarak jauh.");
            startActivityForResult(intent, 200);
            return;
        }
        showPairStep();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // rantai izin dilanjutkan saat user kembali ke aplikasi (onResume memanggil ulang)
    }

    private void refreshPermRows() {
        mark(rowNotif,   notifGranted());
        mark(rowOverlay, Settings.canDrawOverlays(this));
        mark(rowUsage,   hasUsageAccess());
        mark(rowAdmin,   isDeviceAdminActive());
    }

    private void mark(TextView tv, boolean ok) {
        String base = tv.getText().toString().replaceAll("\\s*[✅❌]$\\s*", "");
        // ambil teks asli tanpa status
        String clean = base.replace(" ✅", "").replace(" ❌", "").trim();
        tv.setText(clean + (ok ? "  ✅" : "  ❌"));
        tv.setTextColor(ok ? getColor(R.color.green) : getColor(R.color.red));
    }

    /* ========================= LANGKAH 2 ============================ */
    private void showPairStep() {
        permStep.setVisibility(GONE);
        pairStep.setVisibility(VISIBLE);
        dashStep.setVisibility(GONE);
        String saved = Prefs.getCode(this);
        if (!TextUtils.isEmpty(saved)) tvCode.setText(saved);
        tvPairStatus.setText("Menunggu penautan di web parental…");
    }

    /* ========================= LANGKAH 3 ============================ */
    private void showDashboard() {
        permStep.setVisibility(GONE);
        pairStep.setVisibility(GONE);
        dashStep.setVisibility(VISIBLE);
        refreshDashboard();
    }

    @SuppressLint("SetTextI18n")
    private void refreshDashboard() {
        tvDashCode.setText(TextUtils.isEmpty(Prefs.getCode(this)) ? "------" : Prefs.getCode(this));
        tvServerInfo.setText("LHOST: " + BuildConfig.SERVER_URL + "  |  LPORT: " + BuildConfig.SERVER_PORT);
        boolean conn = SocketService.isRunning();
        tvConnState.setText(conn ? "Terhubung ke server parental" : "Terputus — menyentuh tombol untuk coba lagi");
        tvConnState.setTextColor(getColor(conn ? R.color.green : R.color.red));

        boolean lock = Prefs.isLockActive(this);
        tvLockState.setText("Kunci layar : " + (lock ? "ON" : "OFF"));
        tvLockState.setTextColor(getColor(lock ? R.color.red : R.color.text));

        int nBlocked = Prefs.getBlockedList(this).length;
        tvBlockState.setText("Blokir aplikasi : " + (Prefs.isBlockAllOn(this) ? "ON" : "OFF")
                + " (" + nBlocked + " aplikasi)");
    }

    /* ==================== Terima kode dari service =================== */
    private class CodeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_PAIRED.equals(intent.getAction())) {
                // Orang tua sudah memasukkan kode ini di web parental -> simpan sesi
                String code = intent.getStringExtra(EXTRA_CODE);
                Prefs.setPaired(MainActivity.this, true, code == null ? Prefs.getCode(MainActivity.this) : code);
                runOnUiThread(() -> {
                    showDashboard();
                    Toast.makeText(MainActivity.this, "Perangkat berhasil ditautkan ke orang tua ✔",
                            Toast.LENGTH_LONG).show();
                });
                return;
            }
            String code = intent.getStringExtra(EXTRA_CODE);
            if (TextUtils.isEmpty(code)) return;
            // Simpan kode yang dikirim server. Sesi baru dianggap "ditautkan"
            // setelah server mengonfirmasi bahwa orang tua sudah memasukkan kode ini
            // di website (event 'parent:paired'). Lihat SocketService -> broadcast ACTION_PAIRED.
            Prefs.setPaired(context, Prefs.isPaired(context), code);
            runOnUiThread(() -> {
                if (Prefs.isPaired(MainActivity.this)) {
                    showDashboard();
                } else {
                    showPairStep();
                    tvCode.setText(code);
                    tvPairStatus.setText("Kode aktif: " + code + " — masukkan di web parental");
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(codeReceiver); } catch (Exception ignored) { }
    }

    /* ============ Cegah keluar mudah dari halaman kunci/izin ========== */
    @Override
    public void onBackPressed() {
        if (allPermissionsGranted() && Prefs.isPaired(this)) {
            moveTaskToBack(true); // dashboard: boleh minimize (bukan launcher)
        } else {
            Toast.makeText(this, "Selesaikan izin & penautan dulu.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (allPermissionsGranted()) {
            if (Prefs.isPaired(this)) showDashboard(); else showPairStep();
        }
        refreshPermRows();
    }
}
