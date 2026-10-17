package com.company.children.activities;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.company.children.R;
import com.company.children.helpers.Prefs;
import com.company.children.receivers.DeviceAdminReceiverX;

/**
 * OVERLAY pengunci — menutupi seluruh layar saat:
 *   - orang tua mengaktifkan MODE KUNCI LAYAR, atau
 *   - aplikasi yang diblokir sedang dibuka anak.
 * Tidak bisa ditutup (back/home/recents diblokir) kecuali:
 *   - anak memasukkan password yang dibuat orang tua, atau
 *   - orang tua mematikan kunci dari web parental (service mengirim ACTION_DISMISS).
 */
public class BlockerActivity extends AppCompatActivity {

    public static final String EXTRA_LOCK_MODE   = "lock_mode";
    public static final String EXTRA_BLOCKED_PKG = "blocked_pkg";
    public static final String ACTION_DISMISS    = "com.company.children.DISMISS_BLOCKER";

    private boolean lockMode = true;
    private String blockedPkg = "";
    private TextView tvBlockTitle, tvBlockSub, tvWrong;
    private EditText etPassword;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Jaga tetap di atas layar kunci / aplikasi lain
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        setContentView(R.layout.activity_blocker);
        tvBlockTitle = findViewById(R.id.tvBlockTitle);
        tvBlockSub   = findViewById(R.id.tvBlockSub);
        tvWrong      = findViewById(R.id.tvWrong);
        etPassword   = findViewById(R.id.etPassword);
        Button btnUnlock = findViewById(R.id.btnUnlock);

        handleIntent(getIntent());

        btnUnlock.setOnClickListener(v -> tryUnlock());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Sembunyikan status & navigation bar (immersive sticky) — API 21+
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Exception ignored) { }
    }

    private void handleIntent(Intent intent) {
        if (intent != null && ACTION_DISMISS.equals(intent.getAction())) {
            finish();
            return;
        }
        if (intent != null) {
            lockMode   = intent.getBooleanExtra(EXTRA_LOCK_MODE, true);
            blockedPkg = intent.getStringExtra(EXTRA_BLOCKED_PKG) == null ? "" : intent.getStringExtra(EXTRA_BLOCKED_PKG);
        } else {
            lockMode = Prefs.isLockActive(this);
        }
        if (lockMode) {
            tvBlockTitle.setText("🔒 Layar Dikunci Orang Tua");
            tvBlockSub.setText("Masukkan password dari orang tua, atau minta orang tua membuka lewat web parental.");
        } else {
            tvBlockTitle.setText("⛔ Aplikasi Diblokir");
            tvBlockSub.setText("Aplikasi " + (TextUtils.isEmpty(blockedPkg) ? "ini" : blockedPkg)
                    + " diblokir oleh orang tua. Masukkan password untuk melanjutkan.");
        }
    }

    private void tryUnlock() {
        String entered = etPassword.getText().toString().trim();
        String correct = Prefs.getLockPassword(this);
        if (!TextUtils.isEmpty(correct) && entered.equals(correct)) {
            etPassword.setText("");
            Toast.makeText(this, "Kunci dibuka.", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            tvWrong.setVisibility(VISIBLE);
            etPassword.setText("");
            // salah password -> layarkontrak paksa bila device admin aktif
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                ComponentName admin = new ComponentName(this, DeviceAdminReceiverX.class);
                if (dpm != null && dpm.isAdminActive(admin)) dpm.lockNow();
            } catch (Exception ignored) { }
        }
    }

    /* ---------- Blokir tombol navigasi & back agar overlay tak tertutupi ---------- */
    @Override
    public void onBackPressed() { /* sengaja kosong: tidak boleh keluar */ }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME
                || keyCode == KeyEvent.KEYCODE_APP_SWITCH || keyCode == KeyEvent.KEYCODE_SEARCH) {
            return true; // konsumsi tombol
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Bila kontrol sudah dilepas orang tua dari web, tutup sendiri
        if (!Prefs.isLockActive(this)) {
            String fg = ""; // aplikasi blokir tetap menahan sampai pass/dismiss
            if (!Prefs.isBlockAllOn(this) || !Prefs.isBlockedPackage(this, currentTargetPkg())) {
                finish();
                return;
            }
        }
    }

    private String currentTargetPkg() {
        return blockedPkg == null ? "" : blockedPkg;
    }
}
