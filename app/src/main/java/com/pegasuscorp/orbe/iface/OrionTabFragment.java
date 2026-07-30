package com.pegasuscorp.orbe.iface;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * Wrapper AndroidX autour du pseudo-fragment {@link com.pegasuscorp.orbe.orion.OrionFragment}.
 * Ne réécrit pas OrionFragment — délègue build / attach / detach / résultats.
 */
public class OrionTabFragment extends Fragment {

    private PegaseInterfaceHost host;
    private com.pegasuscorp.orbe.orion.OrionFragment orion;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof PegaseInterfaceHost)) {
            throw new IllegalStateException("Host must implement PegaseInterfaceHost");
        }
        host = (PegaseInterfaceHost) context;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        FrameLayout root = new FrameLayout(requireContext());
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        orion = new com.pegasuscorp.orbe.orion.OrionFragment(requireActivity());
        root.addView(orion.build(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (orion != null) {
            orion.onAttach();
            deliverOrionPrefill();
        }
    }

    @Override
    public void onPause() {
        if (orion != null) {
            orion.onDetach();
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (orion != null) {
            orion.onDetach();
            orion = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onDetach() {
        host = null;
        super.onDetach();
    }

    public void deliverOrionPrefill() {
        if (host == null || orion == null) return;
        String prompt = host.getInterfaceViewModel().takePendingOrionPrompt();
        if (prompt == null || prompt.isEmpty()) return;
        orion.prefillPrompt(prompt);
    }

    public void onKeyboardVisible() {
        if (orion != null) orion.onKeyboardVisible();
    }

    /** Quitte le champ Orion pour ne pas voler le focus Discussion. */
    public void releaseInputFocus() {
        if (orion != null) orion.releaseInputFocus();
    }

    public void onDictateResult(String text) {
        if (orion != null) orion.onDictateResult(text);
    }

    public void onPickMdResult(Uri uri) {
        if (orion != null) orion.onPickMdResult(uri);
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (orion == null || data == null) return false;
        if (requestCode == com.pegasuscorp.orbe.orion.OrionFragment.REQ_DICTATE
                && resultCode == android.app.Activity.RESULT_OK) {
            java.util.ArrayList<String> results =
                    data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                orion.onDictateResult(results.get(0));
                return true;
            }
        }
        if (requestCode == com.pegasuscorp.orbe.orion.OrionFragment.REQ_PICK_MD
                && resultCode == android.app.Activity.RESULT_OK
                && data.getData() != null) {
            orion.onPickMdResult(data.getData());
            return true;
        }
        return false;
    }
}
