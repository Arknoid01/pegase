package com.pegasuscorp.orbe;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.orion.GpuAdapter;
import com.pegasuscorp.orbe.orion.GpuOffer;
import com.pegasuscorp.orbe.orion.GpuOption;
import com.pegasuscorp.orbe.orion.NetworkVolume;
import com.pegasuscorp.orbe.orion.OrionConfig;
import com.pegasuscorp.orbe.orion.OrionManagerActions;
import com.pegasuscorp.orbe.orion.RunPodClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Paramètres Orion / RunPod — volume fixé, GPU, budget, arrêt auto, clés.
 */
public class OrionSettingsActivity extends AppCompatActivity {

    private float density;
    private RecyclerView gpuList;
    private GpuAdapter gpuAdapter;
    private TextView budgetLabel;
    private TextView autoStopLabel;
    private TextView gpuStatus;
    private EditText runpodKeyField;
    private EditText orionTokenField;
    private EditText volumeIdField;
    private EditText dataCenterField;
    private TextView volumeStatus;
    private SeekBar budgetSeek;
    private SeekBar autoStopSeek;
    private final Set<String> selectedGpuIds = new HashSet<>();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        OrionConfig cfg = OrionConfig.load(this);
        selectedGpuIds.addAll(cfg.allowedGpuIds);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F00B0E14"));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("Orion · RunPod");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Pod GPU pour qwen3-coder — volume réseau toujours attaché");
        subtitle.setTextColor(Color.parseColor("#88FFFFFF"));
        subtitle.setTextSize(12);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, matchWrap());
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Volume (éditable — lié à une région RunPod)
        body.addView(section("Volume réseau"));
        body.addView(hint("ID RunPod Storage + région (data center). "
                + "Un volume n'existe que dans une région."));
        volumeIdField = plainField("ID volume (ex: agv6w2qcg7)");
        volumeIdField.setText(cfg.networkVolumeId);
        body.addView(volumeIdField, matchWrap());
        dataCenterField = plainField("Data center (ex: EU-RO-1) — auto si vide");
        dataCenterField.setText(cfg.dataCenterId);
        body.addView(dataCenterField, matchWrap());
        volumeStatus = hint("Appuie sur « Charger volumes » pour lister ceux du compte.");
        body.addView(volumeStatus);
        TextView loadVol = actionButton("Charger volumes du compte");
        loadVol.setOnClickListener(v -> loadVolumes());
        body.addView(loadVol, matchWrap());
        TextView saveVol = actionButton("Enregistrer volume / région");
        saveVol.setOnClickListener(v -> {
            String id = volumeIdField.getText() != null
                    ? volumeIdField.getText().toString().trim() : "";
            String dc = dataCenterField.getText() != null
                    ? dataCenterField.getText().toString().trim() : "";
            OrionConfig.saveVolumeAndDataCenter(this, id, dc);
            Toast.makeText(this, "Volume enregistré"
                            + (dc.isEmpty() ? "" : " @ " + dc),
                    Toast.LENGTH_SHORT).show();
        });
        body.addView(saveVol, matchWrap());

        // GPU list
        body.addView(section("GPU autorisés"));
        gpuStatus = hint("Chargement des types GPU RunPod…");
        body.addView(gpuStatus);
        gpuAdapter = new GpuAdapter((option, allowed) -> {
            if (option == null || option.offer == null) return;
            if (allowed) selectedGpuIds.add(option.offer.id);
            else selectedGpuIds.remove(option.offer.id);
            persistGpuSelection();
        });
        gpuList = new RecyclerView(this);
        gpuList.setLayoutManager(new LinearLayoutManager(this));
        gpuList.setAdapter(gpuAdapter);
        gpuList.setNestedScrollingEnabled(false);
        gpuList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        body.addView(gpuList, matchWrap());

        // Budget
        body.addView(section("Budget max / heure"));
        budgetLabel = new TextView(this);
        budgetLabel.setTextColor(Color.WHITE);
        budgetLabel.setTextSize(14);
        body.addView(budgetLabel);
        budgetSeek = new SeekBar(this);
        // 0.10 → 2.00 par pas de 0.01 → 190 steps
        budgetSeek.setMax(190);
        int budgetProgress = Math.round((cfg.maxBudgetPerHour - OrionConfig.MIN_BUDGET) * 100f);
        budgetSeek.setProgress(Math.max(0, Math.min(190, budgetProgress)));
        updateBudgetLabel(progressToBudget(budgetSeek.getProgress()));
        budgetSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateBudgetLabel(progressToBudget(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                OrionConfig.saveMaxBudgetPerHour(OrionSettingsActivity.this,
                        progressToBudget(seekBar.getProgress()));
            }
        });
        body.addView(budgetSeek, matchWrap());

        // Auto-stop
        body.addView(section("Arrêt auto (inactivité)"));
        autoStopLabel = new TextView(this);
        autoStopLabel.setTextColor(Color.WHITE);
        autoStopLabel.setTextSize(14);
        body.addView(autoStopLabel);
        autoStopSeek = new SeekBar(this);
        // 10 → 120
        autoStopSeek.setMax(110);
        autoStopSeek.setProgress(cfg.autoStopMinutes - OrionConfig.MIN_AUTO_STOP);
        updateAutoStopLabel(progressToAutoStop(autoStopSeek.getProgress()));
        autoStopSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateAutoStopLabel(progressToAutoStop(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                OrionConfig.saveAutoStopMinutes(OrionSettingsActivity.this,
                        progressToAutoStop(seekBar.getProgress()));
            }
        });
        body.addView(autoStopSeek, matchWrap());

        // API keys
        body.addView(section("Clés API"));
        body.addView(hint("RunPod (Bearer) + token Ollama / ORION_TOKEN transmis au pod"));
        runpodKeyField = keyField("Clé API RunPod");
        runpodKeyField.setText(ApiKeyStore.getRunpodApiKey(this));
        body.addView(runpodKeyField, matchWrap());
        orionTokenField = keyField("Token Orion / Ollama");
        orionTokenField.setText(ApiKeyStore.getOrionToken(this));
        body.addView(orionTokenField, matchWrap());

        TextView save = actionButton("Enregistrer les clés");
        save.setOnClickListener(v -> {
            ApiKeyStore.setRunpodApiKey(this, runpodKeyField.getText().toString());
            ApiKeyStore.setOrionToken(this, orionTokenField.getText().toString());
            Toast.makeText(this, "Clés Orion enregistrées", Toast.LENGTH_SHORT).show();
            loadGpus();
        });
        body.addView(save, matchWrap());

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(dp(14) + bars.left, dp(14) + bars.top,
                    dp(14) + bars.right, dp(14) + bars.bottom);
            return insets;
        });
        setContentView(root);
        loadGpus();
        loadVolumes();
    }

    private void loadVolumes() {
        String key = ApiKeyStore.getRunpodApiKey(this);
        if (TextUtils.isEmpty(key)) {
            if (volumeStatus != null) {
                volumeStatus.setText("Saisis la clé RunPod pour lister les volumes.");
            }
            return;
        }
        if (volumeStatus != null) volumeStatus.setText("Chargement des volumes…");
        final String apiKey = key;
        bg.execute(() -> {
            try {
                List<NetworkVolume> vols =
                        OrionManagerActions.client().listNetworkVolumes(apiKey);
                main.post(() -> bindVolumes(vols));
            } catch (Exception e) {
                main.post(() -> {
                    if (volumeStatus != null) {
                        volumeStatus.setText("Volumes : "
                                + (e.getMessage() == null ? "erreur" : e.getMessage()));
                    }
                });
            }
        });
    }

    private void bindVolumes(List<NetworkVolume> vols) {
        if (volumeStatus == null) return;
        if (vols == null || vols.isEmpty()) {
            volumeStatus.setText("Aucun volume sur ce compte — crée-en un dans RunPod Storage.");
            return;
        }
        StringBuilder sb = new StringBuilder("Volumes du compte :\n");
        for (NetworkVolume v : vols) {
            sb.append("• ").append(v.label()).append('\n');
        }
        sb.append("Tape l'ID ci-dessus puis Enregistrer.");
        volumeStatus.setText(sb.toString().trim());
        // Si un seul volume et champ encore sur l'ancien défaut introuvable → préremplir
        if (vols.size() == 1 && volumeIdField != null) {
            String current = volumeIdField.getText() != null
                    ? volumeIdField.getText().toString().trim() : "";
            boolean known = false;
            for (NetworkVolume v : vols) {
                if (v.id.equals(current)) { known = true; break; }
            }
            if (!known) {
                NetworkVolume only = vols.get(0);
                volumeIdField.setText(only.id);
                if (dataCenterField != null) dataCenterField.setText(only.dataCenterId);
                OrionConfig.saveVolumeAndDataCenter(this, only.id, only.dataCenterId);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        persistGpuSelection();
        if (runpodKeyField != null) {
            ApiKeyStore.setRunpodApiKey(this, runpodKeyField.getText().toString());
        }
        if (orionTokenField != null) {
            ApiKeyStore.setOrionToken(this, orionTokenField.getText().toString());
        }
        if (volumeIdField != null) {
            String id = volumeIdField.getText() != null
                    ? volumeIdField.getText().toString().trim() : "";
            String dc = dataCenterField != null && dataCenterField.getText() != null
                    ? dataCenterField.getText().toString().trim() : "";
            OrionConfig.saveVolumeAndDataCenter(this, id, dc);
        }
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }

    private void loadGpus() {
        String key = ApiKeyStore.getRunpodApiKey(this);
        if (TextUtils.isEmpty(key)) {
            gpuStatus.setText("Saisis la clé RunPod pour charger la liste GPU.");
            if (gpuAdapter != null) gpuAdapter.submit(new ArrayList<>());
            return;
        }
        gpuStatus.setText("Chargement…");
        final String apiKey = key;
        bg.execute(() -> {
            try {
                RunPodClient client = OrionManagerActions.client();
                List<GpuOffer> all = client.listGpuTypes(apiKey);
                main.post(() -> bindGpus(all));
            } catch (Exception e) {
                main.post(() -> {
                    gpuStatus.setText("Impossible de charger les GPU : "
                            + (e.getMessage() == null ? "erreur" : e.getMessage()));
                    if (gpuAdapter != null) gpuAdapter.submit(new ArrayList<>());
                });
            }
        });
    }

    private void bindGpus(List<GpuOffer> all) {
        if (all == null || all.isEmpty()) {
            gpuStatus.setText("Aucun type GPU renvoyé par RunPod.");
            gpuAdapter.submit(new ArrayList<>());
            return;
        }
        gpuStatus.setText(all.size() + " types — coche ceux autorisés");
        OrionConfig cfg = OrionConfig.load(this);
        List<GpuOption> options = new ArrayList<>();
        for (GpuOffer o : all) {
            boolean allowed = selectedGpuIds.contains(o.id)
                    || cfg.isGpuAllowed(o.id)
                    || cfg.isGpuAllowed(o.displayName);
            if (allowed) selectedGpuIds.add(o.id);
            options.add(new GpuOption(o, allowed));
        }
        gpuAdapter.submit(options);
    }

    private void persistGpuSelection() {
        List<String> ids = gpuAdapter != null
                ? gpuAdapter.allowedIds()
                : new ArrayList<>(selectedGpuIds);
        selectedGpuIds.clear();
        selectedGpuIds.addAll(ids);
        OrionConfig.saveAllowedGpuIds(this, ids);
    }

    private void updateBudgetLabel(float budget) {
        budgetLabel.setText(String.format(Locale.US, "$%.2f / h", budget));
    }

    private void updateAutoStopLabel(int minutes) {
        autoStopLabel.setText(minutes + " minutes");
    }

    private static float progressToBudget(int progress) {
        return OrionConfig.MIN_BUDGET + progress * 0.01f;
    }

    private static int progressToAutoStop(int progress) {
        return OrionConfig.MIN_AUTO_STOP + progress;
    }

    private TextView section(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.parseColor("#35D0DD"));
        t.setTextSize(13);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    private TextView hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor("#66FFFFFF"));
        t.setTextSize(11);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private EditText keyField(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(Color.parseColor("#55FFFFFF"));
        field.setTextColor(Color.WHITE);
        field.setBackgroundColor(Color.parseColor("#22FFFFFF"));
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSingleLine(true);
        return field;
    }

    private EditText plainField(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(Color.parseColor("#55FFFFFF"));
        field.setTextColor(Color.WHITE);
        field.setBackgroundColor(Color.parseColor("#22FFFFFF"));
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setSingleLine(true);
        return field;
    }

    private TextView actionButton(String label) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.parseColor("#35D0DD"));
        btn.setTextSize(14);
        btn.setPadding(dp(10), dp(12), dp(10), dp(12));
        return btn;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
