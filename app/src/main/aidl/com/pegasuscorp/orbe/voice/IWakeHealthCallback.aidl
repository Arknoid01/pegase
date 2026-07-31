package com.pegasuscorp.orbe.voice;

/** Changement d'état wake (process {@code :voice} → launcher). */
interface IWakeHealthCallback {
    void onWakeHealthChanged(int code);
}
