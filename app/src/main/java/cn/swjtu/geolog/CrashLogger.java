package cn.swjtu.geolog;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashLogger implements Thread.UncaughtExceptionHandler {
    private final Context appCtx;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public CrashLogger(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void install(Context ctx) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashLogger(ctx));
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            File dir = appCtx.getExternalFilesDir(null);
            if (dir == null) dir = appCtx.getFilesDir();
            File logs = new File(dir, "logs");
            if (!logs.exists()) logs.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File f = new File(logs, "crash-" + ts + ".txt");
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, false))) {
                pw.println("Thread: " + t.getName());
                pw.println("Time: " + ts);
                e.printStackTrace(pw);
            }
            Log.e("CrashLogger", "Crash saved: " + f.getAbsolutePath());
        } catch (Throwable ex) {
            Log.e("CrashLogger", "Failed to save crash", ex);
        } finally {
            if (defaultHandler != null) defaultHandler.uncaughtException(t, e);
        }
    }
}

