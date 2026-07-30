package com.pegasuscorp.orbe.iface;

import android.app.Activity;

/**
 * Pont Activity ↔ onglets (discussion / fichiers / Orion / outils).
 * Évite les casts durs vers {@link com.pegasuscorp.orbe.PegaseInterfaceActivity}.
 */
public interface PegaseInterfaceHost {

    int TAB_CONV = 0;
    int TAB_NOTEPAD = 1;
    int TAB_ORION = 2;
    int TAB_TOOLS = 3;
    int TAB_FILES = 4;

    Activity getHostActivity();

    PegaseInterfaceViewModel getInterfaceViewModel();

    void openDiscussionTab();

    void openFilesTab();

    void showTab(int tab);

    void showNasaImage(String reply);

    void updateSubtitle();

    void resumeChatListening();

    /** True si l'onglet Discussion est celui affiché. */
    boolean isDiscussionTabVisible();

    void refreshDiscussionIfNeeded();

    void refreshFilesTab();

    void onKeyboardVisible();
}
