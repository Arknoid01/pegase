package com.pegasuscorp.orbe;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.chat.ChatSessionRegistry;
import com.pegasuscorp.orbe.chat.CloudModelStore;
import com.pegasuscorp.orbe.llm.ModelStore;
import com.pegasuscorp.orbe.spotify.SpotifyAuthHelper;
import com.pegasuscorp.orbe.spotify.SpotifyAuthStore;

/**
 * Réglages API et modèle cloud — accessible depuis la mémoire, la voix ou le tiroir apps.
 */
public class ApiSettingsActivity extends AppCompatActivity {

    private float density;
    private LinearLayout root;
    private LinearLayout modelListHost;
    private EditText groqKeyField;
    private EditText cerebrasKeyField;
    private EditText openRouterKeyField;
    private EditText geminiKeyField;
    private EditText tavilyKeyField;
    private EditText newsKeyField;
    private EditText spotifyClientIdField;
    private EditText nasaKeyField;
    private EditText githubTokenField;
    private EditText githubRepoField;
    private EditText githubBranchField;
    private EditText hostingerTokenField;
    private EditText hostingerWebhookField;
    private EditText userCityField;
    private EditText userCoordsField;
    private TextView activeSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        int pad = dp(14);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#FF0B0E14"));
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, matchWrap());

        TextView title = new TextView(this);
        title.setText("API & modèle Pégase");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title, matchWrap());

        activeSummary = new TextView(this);
        activeSummary.setTextColor(Color.parseColor("#88FFFFFF"));
        activeSummary.setTextSize(13);
        activeSummary.setPadding(0, dp(6), 0, dp(12));
        root.addView(activeSummary, matchWrap());
        refreshSummary();

        root.addView(section("Cerveau conversationnel"));
        root.addView(hint("Rotation auto : Groq → Cerebras → OpenRouter (Gemini hors chaîne)."));
        addProviderButton(CloudModelStore.PROVIDER_GROQ, "Groq (prioritaire)");
        addProviderButton(CloudModelStore.PROVIDER_GEMINI, "Gemini (manuel, hors rotation)");

        root.addView(section("Modèle actif"));
        modelListHost = new LinearLayout(this);
        modelListHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(modelListHost, matchWrap());
        rebuildModelList();

        root.addView(section("Modèle local (hors ligne)"));
        addAction(ModelStore.useLocalLlm(this)
                ? "Modèle local activé — désactiver"
                : "Activer le modèle local (GGUF)", () -> {
            ModelStore.setUseLocalLlm(this, !ModelStore.useLocalLlm(this));
            refreshSummary();
            Toast.makeText(this, ModelStore.useLocalLlm(this)
                    ? "Modèle local activé" : "Cloud activé", Toast.LENGTH_SHORT).show();
        });

        root.addView(section("Localisation (météo)"));
        userCityField = apiField("Ma ville (ex: Lyon)");
        userCoordsField = apiField("GPS lat,lon (prioritaire)");

        root.addView(section("Clés API"));
        root.addView(hint("Rotation LLM : console.groq.com · cloud.cerebras.ai · openrouter.ai"));
        groqKeyField = apiField("Clé Groq (gsk_...)");
        cerebrasKeyField = apiField("Clé Cerebras");
        openRouterKeyField = apiField("Clé OpenRouter (sk-or-...)");
        addAction("Tester Groq / Cerebras / OpenRouter", this::testLlmKeys);
        root.addView(hint("Gemini (hors rotation) : aistudio.google.com"));
        geminiKeyField = apiField("Clé Gemini (AIza...)");
        root.addView(hint("Recherche : tavily.com · Actualités : newsapi.org"));
        tavilyKeyField = apiField("Clé Tavily (tvly-...)");
        newsKeyField = apiField("Clé NewsAPI");
        root.addView(hint("Spotify : developer.spotify.com/dashboard"));
        spotifyClientIdField = apiField("Client ID Spotify");
        root.addView(hint("Redirect URI Spotify : com.pegasuscorp.orbe://spotify-callback"));
        nasaKeyField = apiField("Clé NASA (DEMO_KEY par défaut)");

        root.addView(section("GitHub & Hostinger (commit)"));
        root.addView(hint("Token GitHub (classic ou fine-grained) : github.com/settings/tokens "
                + "— scopes Contents + Metadata sur le repo."));
        githubTokenField = apiField("Token GitHub (ghp_… / github_pat_…)");
        githubRepoField = apiFieldPlain("Dépôt owner/repo (ex: yanno/orbe)");
        githubBranchField = apiFieldPlain("Branche (main par défaut)");
        root.addView(hint("Hostinger : token API developers.hostinger.com + webhook "
                + "Git Deploy (optionnel, après commit)."));
        hostingerTokenField = apiField("Token API Hostinger");
        hostingerWebhookField = apiFieldPlain("Webhook déploiement Hostinger (URL)");
        addAction("Valider GitHub / Hostinger", this::validateGitRemotes);

        loadFields();
        addAction(SpotifyAuthStoreLabel(), this::toggleSpotifyConnection);

        addAction("Enregistrer", this::saveAll);

        addAction("Fermer", this::finish);

        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(pad, bars.top + pad, pad, bars.bottom + pad);
            return insets;
        });
        setContentView(scroll);
    }

    private void refreshSummary() {
        if (activeSummary == null) return;
        if (ModelStore.useLocalLlm(this)) {
            activeSummary.setText("Actif : modèle local · "
                    + ModelStore.displayNameForPreset(ModelStore.getActivePreset(this)));
        } else {
            activeSummary.setText("Actif : " + CloudModelStore.displayNameForActive(this));
        }
    }

    private void addProviderButton(String provider, String label) {
        boolean active = provider.equals(CloudModelStore.getActiveProvider(this));
        Button b = actionButton((active ? "✓ " : "") + label);
        b.setOnClickListener(v -> {
            CloudModelStore.setActiveProvider(this, provider);
            ModelStore.setUseLocalLlm(this, false);
            rebuildModelList();
            refreshSummary();
        });
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(6);
        root.addView(b, lp);
    }

    private void rebuildModelList() {
        modelListHost.removeAllViews();
        String provider = CloudModelStore.getActiveProvider(this);
        String activeId = CloudModelStore.getActiveModelId(this);
        for (String[] entry : CloudModelStore.modelsForProvider(provider)) {
            String modelId = entry[0];
            String name = entry[1];
            boolean selected = modelId.equals(activeId);
            Button b = actionButton((selected ? "✓ " : "") + name);
            b.setOnClickListener(v -> {
                CloudModelStore.setActiveModelId(this, modelId);
                refreshSummary();
            });
            LinearLayout.LayoutParams lp = matchWrap();
            lp.bottomMargin = dp(4);
            modelListHost.addView(b, lp);
        }
    }

    private void loadFields() {
        groqKeyField.setText(ApiKeyStore.getGroqKey(this));
        cerebrasKeyField.setText(ApiKeyStore.getCerebrasKey(this));
        openRouterKeyField.setText(ApiKeyStore.getOpenRouterKey(this));
        geminiKeyField.setText(ApiKeyStore.getGeminiKey(this));
        tavilyKeyField.setText(ApiKeyStore.getTavilyKey(this));
        newsKeyField.setText(ApiKeyStore.getNewsApiKey(this));
        spotifyClientIdField.setText(ApiKeyStore.getSpotifyClientId(this));
        nasaKeyField.setText(ApiKeyStore.getNasaApiKey(this));
        githubTokenField.setText(ApiKeyStore.getGithubToken(this));
        githubRepoField.setText(ApiKeyStore.getGithubRepo(this));
        githubBranchField.setText(ApiKeyStore.getGithubBranch(this));
        hostingerTokenField.setText(ApiKeyStore.getHostingerToken(this));
        hostingerWebhookField.setText(ApiKeyStore.getHostingerWebhook(this));
        userCityField.setText(ApiKeyStore.getUserCity(this));
        userCoordsField.setText(ApiKeyStore.getUserCoords(this));
    }

    private void saveAll() {
        ApiKeyStore.setGroqKey(this, groqKeyField.getText().toString());
        ApiKeyStore.setCerebrasKey(this, cerebrasKeyField.getText().toString());
        ApiKeyStore.setOpenRouterKey(this, openRouterKeyField.getText().toString());
        ApiKeyStore.setGeminiKey(this, geminiKeyField.getText().toString());
        ApiKeyStore.setTavilyKey(this, tavilyKeyField.getText().toString());
        ApiKeyStore.setNewsApiKey(this, newsKeyField.getText().toString());
        ApiKeyStore.setSpotifyClientId(this, spotifyClientIdField.getText().toString());
        ApiKeyStore.setNasaApiKey(this, nasaKeyField.getText().toString());
        ApiKeyStore.setGithubToken(this, githubTokenField.getText().toString());
        ApiKeyStore.setGithubRepo(this, githubRepoField.getText().toString());
        ApiKeyStore.setGithubBranch(this, githubBranchField.getText().toString());
        ApiKeyStore.setHostingerToken(this, hostingerTokenField.getText().toString());
        ApiKeyStore.setHostingerWebhook(this, hostingerWebhookField.getText().toString());
        ApiKeyStore.setUserCity(this, userCityField.getText().toString());
        ApiKeyStore.setUserCoords(this, userCoordsField.getText().toString());
        boolean wasActive = ChatSessionRegistry.isActive();
        if (wasActive) ChatSessionRegistry.get(this).exit();
        ChatSessionRegistry.recreate(this);
        if (wasActive) ChatSessionRegistry.get(this).enter();
        refreshSummary();
        Toast.makeText(this, "Réglages enregistrés", Toast.LENGTH_SHORT).show();
    }

    private void testLlmKeys() {
        // Persiste d'abord les champs saisis (sinon on teste l'ancienne valeur)
        ApiKeyStore.setGroqKey(this, groqKeyField.getText().toString());
        ApiKeyStore.setCerebrasKey(this, cerebrasKeyField.getText().toString());
        ApiKeyStore.setOpenRouterKey(this, openRouterKeyField.getText().toString());

        Toast.makeText(this, "Test des clés LLM…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            java.util.List<com.pegasuscorp.orbe.chat.ProviderKeyProbe.Result> results =
                    com.pegasuscorp.orbe.chat.ProviderKeyProbe.probeChain(this);
            String report = com.pegasuscorp.orbe.chat.ProviderKeyProbe.formatReport(results);
            runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Test des clés LLM")
                    .setMessage(report)
                    .setPositiveButton("OK", null)
                    .show());
        }, "llm-key-probe").start();
    }

    private void validateGitRemotes() {
        // Persiste d'abord les champs saisis
        ApiKeyStore.setGithubToken(this, githubTokenField.getText().toString());
        ApiKeyStore.setGithubRepo(this, githubRepoField.getText().toString());
        ApiKeyStore.setGithubBranch(this, githubBranchField.getText().toString());
        ApiKeyStore.setHostingerToken(this, hostingerTokenField.getText().toString());
        ApiKeyStore.setHostingerWebhook(this, hostingerWebhookField.getText().toString());

        Toast.makeText(this, "Validation en cours…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            String gh = ApiKeyStore.getGithubToken(this);
            String repo = ApiKeyStore.getGithubRepo(this);
            if (gh.isEmpty()) {
                sb.append("GitHub : token vide.\n");
            } else if (repo.isEmpty()) {
                sb.append(com.pegasuscorp.orbe.git.GitHubApiClient.validateToken(gh).message)
                        .append('\n');
            } else {
                sb.append(com.pegasuscorp.orbe.git.GitHubApiClient
                                .validateRepoAccess(gh, repo).message)
                        .append('\n');
            }
            String hi = ApiKeyStore.getHostingerToken(this);
            if (hi.isEmpty()) {
                sb.append("Hostinger : token vide.");
            } else {
                sb.append(com.pegasuscorp.orbe.git.HostingerApiClient.validateToken(hi).message);
            }
            String msg = sb.toString().trim();
            runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        }, "git-validate").start();
    }

    private void toggleSpotifyConnection() {
        ApiKeyStore.setSpotifyClientId(this, spotifyClientIdField.getText().toString());
        if (SpotifyAuthStore.isConnected(this)) {
            SpotifyAuthStore.clear(this);
            Toast.makeText(this, "Spotify déconnecté", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ApiKeyStore.hasSpotifyClientId(this)) {
            Toast.makeText(this, "Colle d'abord ton Client ID Spotify", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            SpotifyAuthHelper.launchAuthorization(this);
            Toast.makeText(this, "Connecte-toi dans le navigateur…", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Spotify : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String SpotifyAuthStoreLabel() {
        return SpotifyAuthStore.isConnected(this)
                ? "Déconnecter Spotify"
                : "Connecter mon compte Spotify Premium";
    }

    private TextView section(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#35D0DD"));
        tv.setTextSize(14);
        tv.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#66FFFFFF"));
        tv.setTextSize(11);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private EditText apiField(String hintText) {
        return apiField(hintText, true);
    }

    private EditText apiFieldPlain(String hintText) {
        return apiField(hintText, false);
    }

    private EditText apiField(String hintText, boolean password) {
        EditText field = new EditText(this);
        field.setHint(hintText);
        field.setHintTextColor(Color.parseColor("#55FFFFFF"));
        field.setTextColor(Color.WHITE);
        field.setBackgroundColor(Color.parseColor("#22FFFFFF"));
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (password) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            field.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        field.setSingleLine(true);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(6);
        field.setLayoutParams(lp);
        root.addView(field, lp);
        return field;
    }

    private void addAction(String label, Runnable action) {
        Button b = actionButton(label);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        root.addView(b, lp);
    }

    private Button actionButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#22FFFFFF"));
        return b;
    }

    private int dp(int v) {
        return (int) (v * density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
