package com.chimera;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class ProxyActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // This is the only purpose of this activity:
        // to provide a legitimate foreground context to start the service.
        Intent serviceIntent = new Intent(this, ScreenshotService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
        
        // Immediately finish so the user sees nothing.
        finishAndRemoveTask();
    }
}
