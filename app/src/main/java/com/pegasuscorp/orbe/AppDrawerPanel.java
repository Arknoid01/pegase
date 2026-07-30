package com.pegasuscorp.orbe;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.LruCache;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tiroir intégré : recherche + grille + rail A-Z (sans zone de dessin).
 */
public class AppDrawerPanel extends FrameLayout {

    private static final long OPEN_DURATION_MS = 280;
    private static final long CLOSE_DURATION_MS = 220;
    private static final int DEFAULT_ACCENT = Color.parseColor("#35D0DD");

    private final List<AppListCache.AppEntry> allApps = new ArrayList<>();
    private final List<AppListCache.AppEntry> filteredApps = new ArrayList<>();

    private EditText searchField;
    private GridView grid;
    private AlphabetRailView rail;
    private AppGridAdapter adapter;
    private PersonalizationPanel personalizationPanel;
    private ImageButton settingsButton;
    private TextView titleLabel;
    private TextView subtitleLabel;
    private TextView emptyState;
    private GradientDrawable searchBg;
    private GradientDrawable panelBg;

    private LinearLayout content;
    private LinearLayout headerRow;
    private LinearLayout searchRow;
    private LinearLayout listRow;
    private LinearLayout railColumn;
    private FrameLayout gridContainer;
    private LinearLayout.LayoutParams headerRowLp;
    private LinearLayout.LayoutParams searchRowLp;
    private LinearLayout.LayoutParams listRowLp;
    private LinearLayout.LayoutParams searchLp;
    private int railWidthPx;
    private int padPx;
    private int iconSizePx;
    private int lastWidthPx = -1;
    private int storedTopInset;
    private int storedBottomInset;
    private int accentMiddle = DEFAULT_ACCENT;

    private String letterFilter = null;
    private boolean open = false;
    private boolean suppressSearchEvents = false;
    private final AnimatorListenerAdapter hideEndListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            animate().setListener(null);
            if (!open) {
                setVisibility(GONE);
                setTranslationY(0f);
                setAlpha(1f);
            }
        }
    };

    public interface PersonalizationListener {
        void onPersonalizationChanged();
        void onPickSystemWallpaper();
        void onPickGalleryWallpaper();
        void onResetWallpaper();
        void onSetDefaultLauncher();
        void onResetWidgetBoard();
        void onCloudProviderSelected(String provider);
        void onCloudModelSelected(String modelId);
        void onApiKeysSaved();
        void onOpenChatTest();
        void onImportPiperZip();
        void onImportPiperFolder();
        void onDownloadPiper();
        void onDownloadPiperVoice(com.pegasuscorp.orbe.voice.PiperModelStore.Voice voice);
        void onPiperVoiceChanged();
    }

    private PersonalizationListener personalizationListener;

    public AppDrawerPanel(Context context) {
        super(context);
        init(context);
    }

    public AppDrawerPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        padPx = (int) (12 * density);

        panelBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        Color.parseColor("#F2141820"),
                        Color.parseColor("#F00B0E14"),
                        Color.parseColor("#F0080A10")
                });
        setBackground(panelBg);
        setVisibility(GONE);
        setElevation(20f);
        setClipChildren(false);
        setClipToPadding(false);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        LayoutParams contentLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        content.setLayoutParams(contentLp);
        addView(content);

        headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.VERTICAL);
        headerRowLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        content.addView(headerRow, headerRowLp);

        titleLabel = new TextView(context);
        titleLabel.setText("Applications");
        titleLabel.setTextColor(Color.WHITE);
        titleLabel.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        titleLabel.setLetterSpacing(0.04f);
        titleLabel.setTextSize(22f);
        headerRow.addView(titleLabel, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        subtitleLabel = new TextView(context);
        subtitleLabel.setTextColor(Color.parseColor("#88FFFFFF"));
        subtitleLabel.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        subtitleLabel.setTextSize(12f);
        subtitleLabel.setPadding(0, (int) (2 * density), 0, 0);
        headerRow.addView(subtitleLabel, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        View accentLine = new View(context);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                (int) (48 * density), (int) (2 * density));
        lineLp.topMargin = (int) (10 * density);
        accentLine.setBackgroundColor(accentMiddle);
        accentLine.setTag("accent_line");
        headerRow.addView(accentLine, lineLp);

        searchRow = new LinearLayout(context);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRowLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        content.addView(searchRow, searchRowLp);

        searchBg = roundedSurface(density, 16f,
                Color.parseColor("#22FFFFFF"),
                Color.argb(0x55, Color.red(accentMiddle), Color.green(accentMiddle),
                        Color.blue(accentMiddle)),
                1f);

        searchField = new EditText(context);
        searchField.setHint("Rechercher une app…");
        searchField.setHintTextColor(Color.parseColor("#66FFFFFF"));
        searchField.setTextColor(Color.WHITE);
        searchField.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        searchField.setSingleLine(true);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setBackground(searchBg);
        try {
            searchField.setHighlightColor(Color.argb(0x44, Color.red(accentMiddle),
                    Color.green(accentMiddle), Color.blue(accentMiddle)));
        } catch (Exception ignored) {
        }
        searchLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        searchRow.addView(searchField, searchLp);

        listRow = new LinearLayout(context);
        listRow.setOrientation(LinearLayout.HORIZONTAL);
        listRowLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f);
        content.addView(listRow, listRowLp);

        gridContainer = new FrameLayout(context);
        listRow.addView(gridContainer, new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1f));

        grid = new GridView(context);
        grid.setVerticalSpacing((int) (10 * density));
        grid.setHorizontalSpacing((int) (4 * density));
        grid.setSelector(android.R.color.transparent);
        grid.setClipToPadding(false);
        gridContainer.addView(grid, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        emptyState = new TextView(context);
        emptyState.setText("Aucune application");
        emptyState.setTextColor(Color.parseColor("#88FFFFFF"));
        emptyState.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        emptyState.setTextSize(15f);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setVisibility(GONE);
        FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        emptyLp.leftMargin = padPx;
        emptyLp.rightMargin = padPx;
        gridContainer.addView(emptyState, emptyLp);

        railColumn = new LinearLayout(context);
        railColumn.setOrientation(LinearLayout.VERTICAL);
        railColumn.setGravity(Gravity.CENTER_HORIZONTAL);
        listRow.addView(railColumn, new LinearLayout.LayoutParams(
                (int) (36 * density), LayoutParams.MATCH_PARENT));

        settingsButton = new ImageButton(context);
        settingsButton.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_settings));
        GradientDrawable gearBg = roundedSurface(density, 18f,
                Color.parseColor("#18FFFFFF"),
                Color.argb(0x40, Color.red(accentMiddle), Color.green(accentMiddle),
                        Color.blue(accentMiddle)),
                1f);
        settingsButton.setBackground(gearBg);
        settingsButton.setColorFilter(accentMiddle);
        settingsButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int gearSize = (int) (36 * density);
        int gearPad = (int) (7 * density);
        settingsButton.setPadding(gearPad, gearPad, gearPad, gearPad);
        settingsButton.setContentDescription("Personnalisation");
        settingsButton.setOnClickListener(v -> {
            if (personalizationPanel.isVisiblePanel()) personalizationPanel.hide();
            else personalizationPanel.show();
        });
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(gearSize, gearSize);
        gearLp.bottomMargin = (int) (6 * density);
        railColumn.addView(settingsButton, gearLp);

        rail = new AlphabetRailView(context, null);
        railColumn.addView(rail, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));

        personalizationPanel = new PersonalizationPanel(context);
        personalizationPanel.setListener(new PersonalizationPanel.Listener() {
            @Override
            public void onPersonalizationChanged() {
                if (personalizationListener != null) {
                    personalizationListener.onPersonalizationChanged();
                }
                if (adapter != null) {
                    adapter.clearIconCache();
                    adapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onPickSystemWallpaper() {
                if (personalizationListener != null) {
                    personalizationListener.onPickSystemWallpaper();
                }
            }

            @Override
            public void onPickGalleryWallpaper() {
                if (personalizationListener != null) {
                    personalizationListener.onPickGalleryWallpaper();
                }
            }

            @Override
            public void onResetWallpaper() {
                if (personalizationListener != null) {
                    personalizationListener.onResetWallpaper();
                }
            }

            @Override
            public void onSetDefaultLauncher() {
                if (personalizationListener != null) {
                    personalizationListener.onSetDefaultLauncher();
                }
            }

            @Override
            public void onResetWidgetBoard() {
                if (personalizationListener != null) {
                    personalizationListener.onResetWidgetBoard();
                }
            }

            @Override
            public void onCloudProviderSelected(String provider) {
                if (personalizationListener != null) {
                    personalizationListener.onCloudProviderSelected(provider);
                }
            }

            @Override
            public void onCloudModelSelected(String modelId) {
                if (personalizationListener != null) {
                    personalizationListener.onCloudModelSelected(modelId);
                }
            }

            @Override
            public void onApiKeysSaved() {
                if (personalizationListener != null) {
                    personalizationListener.onApiKeysSaved();
                }
            }

            @Override
            public void onOpenChatTest() {
                if (personalizationListener != null) {
                    personalizationListener.onOpenChatTest();
                }
            }

            @Override
            public void onImportPiperZip() {
                if (personalizationListener != null) {
                    personalizationListener.onImportPiperZip();
                }
            }

            @Override
            public void onImportPiperFolder() {
                if (personalizationListener != null) {
                    personalizationListener.onImportPiperFolder();
                }
            }

            @Override
            public void onDownloadPiper() {
                if (personalizationListener != null) personalizationListener.onDownloadPiper();
            }

            @Override
            public void onDownloadPiperVoice(com.pegasuscorp.orbe.voice.PiperModelStore.Voice voice) {
                if (personalizationListener != null) personalizationListener.onDownloadPiperVoice(voice);
            }

            @Override
            public void onPiperVoiceChanged() {
                if (personalizationListener != null) personalizationListener.onPiperVoiceChanged();
            }
        });
        addView(personalizationPanel, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        adapter = new AppGridAdapter();
        grid.setAdapter(adapter);

        grid.setOnItemClickListener((parent, view, pos, id) -> {
            String pkg = filteredApps.get(pos).pkg;
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                context.startActivity(launch);
                hide();
            }
        });

        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {}

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                                 int totalItemCount) {
                updateScrollSection(firstVisibleItem);
            }
        });

        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (suppressSearchEvents) return;
                letterFilter = null;
                applyFilters();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        searchField.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard();
            return true;
        });

        rail.setOnLetterSelectedListener((letter, fromDrag) -> {
            letterFilter = String.valueOf(letter).toLowerCase(Locale.ROOT);
            setSearchTextSilently("");
            applyFilters();
            rail.setScrollHighlight(letter);
        });

        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            applyLayoutMetrics(getWidth() > 0 ? getWidth() : v.getResources().getDisplayMetrics().widthPixels,
                    bars.top, bars.bottom);
            return insets;
        });

        bindApps(AppListCache.getCached());
        if (allApps.isEmpty()) {
            AppListCache.loadAsync(context, apps -> post(() -> bindApps(apps)));
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (w > 0) {
            applyLayoutMetrics(w, storedTopInset, storedBottomInset);
        }
    }

    private void applyLayoutMetrics(int widthPx, int topInset, int bottomInset) {
        if (topInset > 0) storedTopInset = topInset;
        if (bottomInset > 0) storedBottomInset = bottomInset;
        topInset = storedTopInset;
        bottomInset = storedBottomInset;
        lastWidthPx = widthPx;

        float density = getResources().getDisplayMetrics().density;
        float widthDp = widthPx / density;

        int columns = widthDp < 340 ? 3 : 4;
        int railDp = widthDp < 360 ? 36 : 44;
        int iconDp = widthDp < 360 ? 44 : (widthDp < 400 ? 50 : 54);
        float textSp = widthDp < 360 ? 10.5f : 11.5f;
        float searchSp = widthDp < 360 ? 14.5f : 15.5f;
        float titleSp = widthDp < 360 ? 20f : 22f;

        railWidthPx = (int) (railDp * density);
        iconSizePx = (int) (iconDp * density);
        padPx = (int) ((widthDp < 360 ? 12 : 16) * density);

        LayoutParams contentLp = (LayoutParams) content.getLayoutParams();
        contentLp.setMargins(0, 0, 0, 0);
        content.setLayoutParams(contentLp);

        ViewGroup.LayoutParams railColLayout = railColumn.getLayoutParams();
        railColLayout.width = railWidthPx;
        railColumn.setLayoutParams(railColLayout);

        ViewGroup.LayoutParams railLayout = rail.getLayoutParams();
        railLayout.width = LayoutParams.MATCH_PARENT;
        rail.setLayoutParams(railLayout);

        grid.setNumColumns(columns);
        grid.setPadding(padPx / 2, 4, padPx / 4, padPx + bottomInset);

        if (titleLabel != null) titleLabel.setTextSize(titleSp);
        headerRowLp.setMargins(padPx, topInset + padPx, padPx, 0);
        headerRow.setLayoutParams(headerRowLp);

        searchField.setPadding(padPx, (int) (12 * density), padPx, (int) (12 * density));
        searchField.setTextSize(searchSp);
        searchRowLp.setMargins(padPx, (int) (10 * density), padPx, (int) (6 * density));
        searchRow.setLayoutParams(searchRowLp);

        if (adapter != null) {
            adapter.setLabelTextSizeSp(textSp);
            adapter.notifyDataSetChanged();
        }
    }

    public void setPersonalizationListener(PersonalizationListener listener) {
        this.personalizationListener = listener;
    }

    public void refreshPiperStatus() {
        if (personalizationPanel != null) {
            personalizationPanel.refreshPiperStatus();
        }
    }

    public void applyAccentColor(int middle) {
        accentMiddle = middle;
        rail.setAccentColor(middle);
        if (personalizationPanel != null) personalizationPanel.setAccentColor(middle);
        if (settingsButton != null) {
            settingsButton.setColorFilter(middle);
            Drawable bg = settingsButton.getBackground();
            if (bg instanceof GradientDrawable) {
                ((GradientDrawable) bg).setStroke(
                        Math.max(1, (int) (1 * getResources().getDisplayMetrics().density)),
                        Color.argb(0x40, Color.red(middle), Color.green(middle), Color.blue(middle)));
            }
        }
        if (searchBg != null) {
            searchBg.setStroke(
                    Math.max(1, (int) (1 * getResources().getDisplayMetrics().density)),
                    Color.argb(0x55, Color.red(middle), Color.green(middle), Color.blue(middle)));
        }
        if (headerRow != null) {
            View line = headerRow.findViewWithTag("accent_line");
            if (line != null) line.setBackgroundColor(middle);
        }
        try {
            if (searchField != null) {
                searchField.setHighlightColor(Color.argb(0x44, Color.red(middle),
                        Color.green(middle), Color.blue(middle)));
            }
        } catch (Exception ignored) {
        }
        if (emptyState != null) {
            emptyState.setTextColor(Color.argb(0xAA, Color.red(middle),
                    Color.green(middle), Color.blue(middle)));
        }
    }

    private void updateScrollSection(int firstVisibleItem) {
        if (letterFilter != null && !letterFilter.isEmpty()) {
            rail.setScrollHighlight(Character.toUpperCase(letterFilter.charAt(0)));
            return;
        }
        if (filteredApps.isEmpty()) return;
        int index = firstVisibleItem;
        if (index < 0 || index >= filteredApps.size()) index = 0;
        String label = filteredApps.get(index).label;
        if (label == null || label.isEmpty()) return;
        rail.setScrollHighlight(Character.toUpperCase(label.charAt(0)));
    }

    public boolean isOpen() {
        return open;
    }

    public void show() {
        show(null);
    }

    public void show(String letter) {
        animate().cancel();
        animate().setListener(null);
        setTranslationY(0f);

        if (open) {
            if (letter != null && !letter.isEmpty()) filterByLetter(letter);
            setVisibility(VISIBLE);
            setAlpha(1f);
            return;
        }
        open = true;
        setVisibility(VISIBLE);
        bringToFront();
        if (allApps.isEmpty()) {
            AppListCache.loadAsync(getContext(), apps -> post(() -> bindApps(apps)));
        }
        clearFilters();
        if (letter != null && !letter.isEmpty()) filterByLetter(letter);

        setAlpha(0.88f);
        post(() -> {
            playOpenAnimation();
            updateScrollSection(grid.getFirstVisiblePosition());
        });
    }

    private void playOpenAnimation() {
        if (!open) return;
        float slideFrom = getHeight() > 0 ? getHeight() * 0.18f : 200f;
        setTranslationY(slideFrom);
        animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(OPEN_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .setListener(null)
                .start();
    }

    public void hide() {
        if (!open) return;
        open = false;
        hideKeyboard();
        if (personalizationPanel != null && personalizationPanel.isVisiblePanel()) {
            personalizationPanel.hide();
        }
        float slideTo = getHeight() > 0 ? getHeight() * 0.10f : 120f;
        animate().cancel();
        animate()
                .translationY(slideTo)
                .alpha(0f)
                .setDuration(CLOSE_DURATION_MS)
                .setInterpolator(new AccelerateInterpolator(1.3f))
                .setListener(hideEndListener)
                .start();
    }

    /** Ferme le tiroir et retourne à l'écran d'accueil. */
    public void closeToHome() {
        if (personalizationPanel != null && personalizationPanel.isVisiblePanel()) {
            personalizationPanel.hide();
        }
        hide();
    }

    /** Réaffiche toutes les applications (filtres effacés), tiroir ouvert. */
    public void resetToAllApps() {
        if (!open) return;
        if (personalizationPanel != null && personalizationPanel.isVisiblePanel()) {
            personalizationPanel.hide();
        }
        letterFilter = null;
        setSearchTextSilently("");
        hideKeyboard();
        applyFilters();
        grid.setSelection(0);
        grid.smoothScrollToPosition(0);
        post(() -> updateScrollSection(0));
    }

    /** @return true si le back a été consommé (raccourci legacy) */
    public boolean handleBack() {
        if (!open) return false;
        closeToHome();
        return true;
    }

    private void filterByLetter(String letter) {
        if (letter == null || letter.isEmpty()) return;
        char c = Character.toLowerCase(letter.charAt(0));
        letterFilter = String.valueOf(c);
        setSearchTextSilently("");
        applyFilters();
        rail.setScrollHighlight(c);
    }

    private void clearFilters() {
        letterFilter = null;
        setSearchTextSilently("");
        filteredApps.clear();
        filteredApps.addAll(allApps);
        adapter.notifyDataSetChanged();
        updateEmptyAndSubtitle();
    }

    private void setSearchTextSilently(String text) {
        suppressSearchEvents = true;
        searchField.setText(text);
        suppressSearchEvents = false;
    }

    private void bindApps(List<AppListCache.AppEntry> apps) {
        allApps.clear();
        if (apps != null) allApps.addAll(apps);
        applyFilters();
    }

    private void applyFilters() {
        String query = searchField.getText().toString().trim().toLowerCase(Locale.ROOT);
        filteredApps.clear();
        for (AppListCache.AppEntry app : allApps) {
            String label = app.label != null ? app.label : app.pkg;
            String labelLower = label.toLowerCase(Locale.ROOT);
            if (!query.isEmpty()) {
                if (labelLower.contains(query)) {
                    filteredApps.add(app);
                }
            } else if (letterFilter != null) {
                if (labelLower.startsWith(letterFilter)) {
                    filteredApps.add(app);
                }
            } else {
                filteredApps.add(app);
            }
        }
        adapter.notifyDataSetChanged();
        updateEmptyAndSubtitle();
        post(() -> updateScrollSection(grid.getFirstVisiblePosition()));
    }

    private void updateEmptyAndSubtitle() {
        boolean empty = filteredApps.isEmpty();
        if (emptyState != null) {
            emptyState.setVisibility(empty ? VISIBLE : GONE);
            if (empty) {
                String q = searchField != null
                        ? searchField.getText().toString().trim() : "";
                if (!q.isEmpty()) {
                    emptyState.setText("Rien pour « " + q + " »");
                } else if (letterFilter != null) {
                    emptyState.setText("Aucune app en "
                            + letterFilter.toUpperCase(Locale.ROOT));
                } else {
                    emptyState.setText("Aucune application");
                }
            }
        }
        if (subtitleLabel != null) {
            int n = filteredApps.size();
            int total = allApps.size();
            if (!searchField.getText().toString().trim().isEmpty() || letterFilter != null) {
                subtitleLabel.setText(n + " résultat" + (n > 1 ? "s" : "")
                        + " · " + total + " apps");
            } else {
                subtitleLabel.setText(total + " application" + (total > 1 ? "s" : ""));
            }
        }
        if (grid != null) grid.setVisibility(empty ? INVISIBLE : VISIBLE);
    }

    private static GradientDrawable roundedSurface(float density, float radiusDp,
            int fill, int stroke, float strokeDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(radiusDp * density);
        if (strokeDp > 0f) {
            bg.setStroke(Math.max(1, (int) (strokeDp * density)), stroke);
        }
        return bg;
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
    }

    private class AppGridAdapter extends BaseAdapter {
        private float labelTextSizeSp = 11f;
        private final LruCache<String, Drawable> iconCache = new LruCache<>(160);

        void setLabelTextSizeSp(float sp) {
            labelTextSizeSp = sp;
        }

        void clearIconCache() {
            iconCache.evictAll();
        }

        private String iconCacheKey(AppListCache.AppEntry item) {
            return item.pkg + "|" + iconSizePx + "|"
                    + PersonalizationStore.getIconTheme(getContext()) + "|"
                    + PersonalizationStore.getIconPack(getContext());
        }

        @Override public int getCount() { return filteredApps.size(); }
        @Override public Object getItem(int p) { return filteredApps.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (position < 0 || position >= filteredApps.size()) {
                return new View(getContext());
            }
            LinearLayout cell;
            ImageView icon;
            TextView label;

            if (convertView instanceof LinearLayout) {
                cell = (LinearLayout) convertView;
                icon = (ImageView) cell.getChildAt(0);
                label = (TextView) cell.getChildAt(1);
            } else {
                float d = getResources().getDisplayMetrics().density;
                cell = new LinearLayout(getContext());
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(
                        (int) (6 * d),
                        (int) (10 * d),
                        (int) (6 * d),
                        (int) (8 * d));

                GradientDrawable cellIdle = roundedSurface(d, 14f,
                        Color.parseColor("#10FFFFFF"), Color.TRANSPARENT, 0f);
                GradientDrawable cellMask = roundedSurface(d, 14f,
                        Color.WHITE, Color.TRANSPARENT, 0f);
                RippleDrawable ripple = new RippleDrawable(
                        ColorStateList.valueOf(Color.argb(0x55,
                                Color.red(accentMiddle), Color.green(accentMiddle),
                                Color.blue(accentMiddle))),
                        cellIdle, cellMask);
                cell.setBackground(ripple);
                cell.setClickable(false);
                cell.setFocusable(false);

                icon = new ImageView(getContext());
                LinearLayout.LayoutParams iconLp =
                        new LinearLayout.LayoutParams(iconSizePx, iconSizePx);
                iconLp.bottomMargin = (int) (6 * d);
                icon.setLayoutParams(iconLp);
                icon.setElevation(2f * d);

                label = new TextView(getContext());
                label.setTextColor(Color.parseColor("#CCFFFFFF"));
                label.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                label.setTextSize(labelTextSizeSp);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(1);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);

                cell.addView(icon);
                cell.addView(label);
            }

            AppListCache.AppEntry item = filteredApps.get(position);
            String appLabel = item.label != null ? item.label : item.pkg;
            try {
                String cacheKey = iconCacheKey(item);
                Drawable themed = iconCache.get(cacheKey);
                if (themed == null) {
                    themed = IconThemeHelper.resolve(getContext(), item, iconSizePx);
                    if (themed != null) iconCache.put(cacheKey, themed);
                }
                icon.setImageDrawable(themed != null ? themed : item.icon(getContext()));
            } catch (OutOfMemoryError | RuntimeException e) {
                android.util.Log.w("AppDrawer", "Icône " + item.pkg, e);
                icon.setImageDrawable(item.icon(getContext()));
            }
            ViewGroup.LayoutParams iconLp = icon.getLayoutParams();
            if (iconLp != null && (iconLp.width != iconSizePx || iconLp.height != iconSizePx)) {
                iconLp.width = iconSizePx;
                iconLp.height = iconSizePx;
                icon.setLayoutParams(iconLp);
            }
            label.setText(appLabel);
            label.setTextSize(labelTextSizeSp);
            return cell;
        }
    }
}
