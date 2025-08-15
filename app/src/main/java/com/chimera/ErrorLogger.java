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
            // Get the root of the external storage.
            File logFile = new File(Environment.getExternalStorageDirectory(), LOG_FILE);

            // Use FileWriter to append to the file.
            FileWriter fw = new FileWriter(logFile, true);
            PrintWriter pw = new PrintWriter(fw);

            // Get current timestamp
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

            pw.println("====================");
            pw.println("Timestamp: " + timestamp);
            pw.println("Tag: " + tag);
            pw.println("Error:");

            // Convert the exception stack trace to a string
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            pw.println(sw.toString());
            pw.println("====================\n");

            pw.flush();
            pw.close();
            fw.close();

        } catch (Exception e) {
            // If logging to file fails, at least print to Logcat so we know something went wrong.
            android.util.Log.e("ErrorLogger", "Failed to write to error log file", e);
        }
    }
}