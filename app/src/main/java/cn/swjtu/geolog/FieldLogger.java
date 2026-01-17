package cn.swjtu.geolog;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class FieldLogger {
    private final File logFile;
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    public FieldLogger(Context ctx, String name) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        File logs = new File(dir, "logs");
        if (!logs.exists()) logs.mkdirs();
        this.logFile = new File(logs, name + ".log");
    }

    public synchronized void write(String line) {
        try (FileWriter fw = new FileWriter(logFile, true)) {
            fw.write(sdf.format(new Date()) + " | " + line + "\n");
        } catch (Exception e) {
            Log.e("FieldLogger", "write fail", e);
        }
    }

    public String path() { return logFile.getAbsolutePath(); }
}

