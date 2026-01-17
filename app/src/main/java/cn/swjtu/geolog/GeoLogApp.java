package cn.swjtu.geolog;

import android.app.Application;

public class GeoLogApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLogger.install(this);
    }
}

