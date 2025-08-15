package com.chimera;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ErrorLogger {

    private static final String LOG_FILE = "chimeraerror.txt";

    public static void logError(Context context, String tag, Throwable throwable) {
        try {
            // Use the public Documents directory which is better for modern Android
            File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (!documentsDir.exists()) {
                documentsDir.mkdirs(); // Create the directory if it doesn't exist
            }

            File logFile = new File(documentsDir, LOG_FILE);

            // Use FileWriter to append to the file
            FileWriter fw = new FileWriter(logFile, true);
            PrintWriter pw = new PrintWriter(fw);

            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

            pw.println("====================");
            pw.println("Timestamp: " + timestamp);
            pw.println("Tag: " + tag);
            pw.println("Error:");

            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            pw.println(sw.toString());
            pw.println("====================\n");

            pw.flush();
            pw.close();
            fw.close();

        } catch (Exception e) {
            // If logging to file fails, fall back to Logcat
            android.util.Log.e("ErrorLogger", "Failed to write to error log file", e);
        }
    }
}