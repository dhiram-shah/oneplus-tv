package com.dhiram.tvhome;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.dhiram.tvhome.data.ContinueStore;
import com.dhiram.tvhome.data.Profiles;
import com.dhiram.tvhome.ui.Images;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide singletons. Kept tiny: the TV has ~900 MB RAM in total. */
public class App extends Application {

    private static App instance;

    public final ExecutorService io = Executors.newFixedThreadPool(3);
    public final Handler main = new Handler(Looper.getMainLooper());
    public Profiles profiles;
    public ContinueStore continueStore;
    public Images images;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        profiles = new Profiles(this);
        continueStore = new ContinueStore(this);
        images = new Images(this);
    }

    public static App get() {
        return instance;
    }
}
