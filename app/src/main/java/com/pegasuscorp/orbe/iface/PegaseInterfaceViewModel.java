package com.pegasuscorp.orbe.iface;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.pegasuscorp.orbe.PegaseInterfaceState;
import com.pegasuscorp.orbe.session.PegaseSession;

/**
 * État partagé léger entre Activity et Fragments (onglet, envoi chat, session).
 */
public class PegaseInterfaceViewModel extends AndroidViewModel {

    private final MutableLiveData<Integer> activeTab = new MutableLiveData<>(PegaseInterfaceHost.TAB_CONV);
    private final MutableLiveData<Boolean> chatSending = new MutableLiveData<>(false);

    public PegaseInterfaceViewModel(@NonNull Application application) {
        super(application);
    }

    public PegaseSession session() {
        return PegaseSession.get(getApplication());
    }

    public LiveData<Integer> getActiveTab() {
        return activeTab;
    }

    public int getActiveTabValue() {
        Integer v = activeTab.getValue();
        return v != null ? v : PegaseInterfaceHost.TAB_CONV;
    }

    public void setActiveTab(int tab) {
        activeTab.setValue(tab);
    }

    public LiveData<Boolean> getChatSending() {
        return chatSending;
    }

    public boolean isChatSending() {
        return Boolean.TRUE.equals(chatSending.getValue());
    }

    public void setChatSending(boolean sending) {
        chatSending.setValue(sending);
    }

    public String takePendingChatPhrase() {
        return PegaseInterfaceState.takePendingChatPhrase();
    }

    public String takePendingOrionPrompt() {
        return PegaseInterfaceState.takePendingOrionPrompt();
    }

    public void setPendingOrionPrompt(String prompt) {
        PegaseInterfaceState.setPendingOrionPrompt(prompt);
    }
}
