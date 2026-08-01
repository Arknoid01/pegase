package com.pegasuscorp.orbe;

import android.app.Application;

import com.pegasuscorp.orbe.diag.PegaseDiagLog;

/**
 * Point d'entrée application — handler crash global (tous processus dont :voice).
 */
public class OrbeApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        PegaseDiagLog.install(this);
    }
}
