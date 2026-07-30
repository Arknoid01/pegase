package com.pegasuscorp.orbe;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.pegasuscorp.orbe.memory.MemoryGraphScene;
import com.pegasuscorp.orbe.ui.MemoryGraph3DView;
import com.pegasuscorp.orbe.ui.OrbeTokens;

/**
 * Constellation mémoire en plein écran — ciel immersif pour la démo.
 */
public class MemoryConstellationActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (insetsController != null) {
            insetsController.setAppearanceLightStatusBars(false);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#03060C"));
        setContentView(root);

        MemoryGraphScene.Scene scene = MemoryGraphScene.build(this);
        MemoryGraph3DView sky = new MemoryGraph3DView(this);
        sky.setScene(scene);
        root.addView(sky, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView focusLine = new TextView(this);
        focusLine.setText(scene.isEmpty()
                ? "Le ciel attend tes souvenirs."
                : scene.nodes.size() + " étoiles · " + scene.edges.size() + " synapses");
        focusLine.setTextColor(Color.parseColor("#99FFFFFF"));
        focusLine.setTextSize(12);
        focusLine.setTypeface(OrbeTokens.typeLight());
        focusLine.setGravity(Gravity.CENTER_HORIZONTAL);

        sky.setListener(new MemoryGraph3DView.Listener() {
            @Override
            public void onNodeFocused(MemoryGraphScene.Node node) {
                String kind = node.kind == MemoryGraphScene.NodeKind.ENTITY ? "entité" : "souvenir";
                focusLine.setText(kind + " · " + node.label);
                focusLine.setTextColor(Color.parseColor("#E6FFFFFF"));
            }

            @Override
            public void onFocusCleared() {
                focusLine.setText(scene.nodes.size() + " étoiles · " + scene.edges.size() + " synapses");
                focusLine.setTextColor(Color.parseColor("#99FFFFFF"));
            }
        });

        TextView title = new TextView(this);
        title.setText("Sa constellation");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(OrbeTokens.typeLight());

        TextView close = new TextView(this);
        close.setText("Fermer");
        close.setTextColor(Color.parseColor("#35D0DD"));
        close.setTextSize(13);
        close.setTypeface(OrbeTokens.typeMedium());
        close.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(Color.parseColor("#33101820"));
        closeBg.setCornerRadius(dp(20));
        closeBg.setStroke(dp(1), Color.parseColor("#5535D0DD"));
        close.setBackground(closeBg);
        close.setOnClickListener(v -> finish());
        close.setClickable(true);

        FrameLayout chrome = new FrameLayout(this);
        chrome.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.TOP));
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.TOP);
        chrome.addView(close, closeLp);

        FrameLayout.LayoutParams focusLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        focusLp.bottomMargin = dp(28);
        focusLp.leftMargin = dp(24);
        focusLp.rightMargin = dp(24);
        chrome.addView(focusLine, focusLp);

        root.addView(chrome, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ViewCompat.setOnApplyWindowInsetsListener(chrome, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(dp(18) + bars.left, dp(12) + bars.top,
                    dp(18) + bars.right, dp(12) + bars.bottom);
            return windowInsets;
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
