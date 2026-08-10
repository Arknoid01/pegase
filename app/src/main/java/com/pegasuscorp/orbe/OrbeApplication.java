package com.pegasuscorp.orbe;

import android.app.Application;

import com.pegasuscorp.orbe.diag.PegaseDiagLog;
import com.pegasuscorp.orbe.pcshell.PcShellAppLifecycle;
import com.pegasuscorp.orbe.pcshell.PcShellCrypto;

/**
 * Point d'entrée application — handler crash global (tous processus dont :voice).
 */
public class OrbeApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        PegaseDiagLog.install(this);
        PcShellAppLifecycle.install(this);
        PcShellCrypto.ensureSecurityProviders();
    }
}
