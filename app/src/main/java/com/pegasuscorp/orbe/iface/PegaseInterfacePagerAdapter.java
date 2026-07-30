package com.pegasuscorp.orbe.iface;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/** 5 onglets : Discussion, Bloc-notes, Orion, Outils, Fichiers. */
public class PegaseInterfacePagerAdapter extends FragmentStateAdapter {

    public static final int TAB_COUNT = 5;

    public PegaseInterfacePagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case PegaseInterfaceHost.TAB_NOTEPAD:
                return new NotepadFragment();
            case PegaseInterfaceHost.TAB_ORION:
                return new OrionTabFragment();
            case PegaseInterfaceHost.TAB_TOOLS:
                return new ToolsFragment();
            case PegaseInterfaceHost.TAB_FILES:
                return new FilesFragment();
            case PegaseInterfaceHost.TAB_CONV:
            default:
                return new DiscussionFragment();
        }
    }

    @Override
    public int getItemCount() {
        return TAB_COUNT;
    }
}
