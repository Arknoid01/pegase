package com.pegasuscorp.orbe;

import android.app.Activity;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Page plein écran pour placer des widgets (grille magnétique invisible).
 */
public class WidgetBoardActivity extends AppCompatActivity {

    private static final int HOST_ID = WidgetBoardHelper.HOST_ID;
    private static final int MAX_SPAN_COLS = 4;
    private static final int MAX_SPAN_ROWS = 8;

    private OrbeAppWidgetHost widgetHost;
    private AppWidgetManager widgetManager;
    private FrameLayout canvas;
    private ScrollView scrollView;
    private WidgetGrid grid;

    private int pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private int allocatedWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private AppWidgetProviderInfo pendingWidgetInfo;

    private final List<WidgetStore.Entry> entries = new ArrayList<>();
    private int touchSlop;
    private boolean widgetsLoaded;

    private ActivityResultLauncher<Intent> pickWidgetLauncher;
    private ActivityResultLauncher<Intent> bindWidgetLauncher;
    private ActivityResultLauncher<Intent> configureWidgetLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        grid = new WidgetGrid(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().density);

        widgetHost = new OrbeAppWidgetHost(getApplicationContext(), HOST_ID);
        widgetManager = AppWidgetManager.getInstance(this);

        pickWidgetLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                        cleanupPendingWidget();
                        return;
                    }
                    int id = result.getData().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                            AppWidgetManager.INVALID_APPWIDGET_ID);
                    if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
                        cleanupPendingWidget();
                        return;
                    }
                    if (allocatedWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                            && allocatedWidgetId != id) {
                        widgetHost.deleteAppWidgetId(allocatedWidgetId);
                    }
                    onWidgetPicked(id, result.getData());
                });

        bindWidgetLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    ensureHostListening();
                    // Certains OEM renvoient CANCELED même en cas de succès :
                    // on se fie à l'état réel du widget plutôt qu'au resultCode.
                    pendingWidgetInfo = widgetManager.getAppWidgetInfo(pendingWidgetId);
                    if (pendingWidgetInfo != null) {
                        continueAfterBind();
                        return;
                    }
                    if (result.getResultCode() != Activity.RESULT_OK) {
                        showBindRefusedMessage();
                    } else {
                        Toast.makeText(this, "Widget invalide après autorisation",
                                Toast.LENGTH_SHORT).show();
                    }
                    cleanupPendingWidget();
                });

        configureWidgetLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    ensureHostListening();
                    if (widgetManager.getAppWidgetInfo(pendingWidgetId) == null) {
                        cleanupPendingWidget();
                        return;
                    }
                    // Même si l'utilisateur ferme la config, on place le widget lié.
                    finishPlaceWidget();
                });

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#F00B0E14"));

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        root.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        canvas = new FrameLayout(this);
        canvas.setMinimumHeight((int) (getResources().getDisplayMetrics().heightPixels * 1.5f));
        scrollView.addView(canvas, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText("Glisser pour déplacer · Poignée en bas à droite pour redimensionner\nAppui long pour supprimer · Bouton + pour ajouter");
        hint.setTextColor(Color.parseColor("#66FFFFFF"));
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.TOP;
        hintLp.topMargin = pad;
        canvas.addView(hint, hintLp);

        TextView addBtn = new TextView(this);
        addBtn.setText("+");
        addBtn.setTextColor(Color.WHITE);
        addBtn.setTextSize(28);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setBackgroundColor(Color.parseColor("#CC35D0DD"));
        int fabSize = (int) (56 * density);
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(fabSize, fabSize);
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.setMargins(pad, pad, pad, pad);
        addBtn.setOnClickListener(v -> startPickWidget());
        root.addView(addBtn, fabLp);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            scrollView.setPadding(0, bars.top, 0, bars.bottom);
            fabLp.bottomMargin = bars.bottom + pad;
            addBtn.setLayoutParams(fabLp);
            return windowInsets;
        });

        setContentView(root);
        ensureHostListening();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ensureHostListening();
        if (!widgetsLoaded) {
            widgetsLoaded = true;
            loadSavedWidgets();
        }
    }

    @Override
    protected void onDestroy() {
        widgetHost.stopListening();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private void ensureHostListening() {
        try {
            widgetHost.startListening();
        } catch (Exception ignored) {
        }
    }

    private void loadSavedWidgets() {
        entries.clear();
        entries.addAll(WidgetStore.load(this));
        Iterator<WidgetStore.Entry> it = entries.iterator();
        while (it.hasNext()) {
            WidgetStore.Entry entry = it.next();
            AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(entry.appWidgetId);
            if (info == null) {
                it.remove();
                continue;
            }
            normalizeEntry(entry, info);
            attachWidgetView(entry, info);
        }
        WidgetStore.save(this, entries);
    }

    private void startPickWidget() {
        cleanupPendingWidget();
        allocatedWidgetId = widgetHost.allocateAppWidgetId();
        pendingWidgetId = allocatedWidgetId;
        Intent pick = new Intent(AppWidgetManager.ACTION_APPWIDGET_PICK);
        pick.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, allocatedWidgetId);
        pickWidgetLauncher.launch(pick);
    }

    private void onWidgetPicked(int appWidgetId, Intent data) {
        pendingWidgetId = appWidgetId;
        pendingWidgetInfo = widgetManager.getAppWidgetInfo(appWidgetId);
        if (pendingWidgetInfo == null) {
            pendingWidgetInfo = readProviderFromIntent(data);
        }
        if (pendingWidgetInfo == null) {
            Toast.makeText(this, "Widget introuvable", Toast.LENGTH_SHORT).show();
            cleanupPendingWidget();
            return;
        }
        requestBindIfNeeded();
    }

    @SuppressWarnings("deprecation")
    private AppWidgetProviderInfo readProviderFromIntent(Intent data) {
        if (data == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return data.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,
                    AppWidgetProviderInfo.class);
        }
        return data.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER);
    }

    private void requestBindIfNeeded() {
        if (pendingWidgetInfo == null) {
            cleanupPendingWidget();
            return;
        }
        ensureHostListening();
        Bundle bindOptions = createDefaultBindOptions(pendingWidgetInfo);
        if (widgetManager.bindAppWidgetIdIfAllowed(
                pendingWidgetId, pendingWidgetInfo.provider, bindOptions)) {
            continueAfterBind();
            return;
        }
        Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
        bind.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
        bind.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, pendingWidgetInfo.provider);
        bindWidgetLauncher.launch(bind);
    }

    private void continueAfterBind() {
        ensureHostListening();
        pendingWidgetInfo = widgetManager.getAppWidgetInfo(pendingWidgetId);
        if (pendingWidgetInfo == null) {
            Toast.makeText(this, "Impossible de lier le widget", Toast.LENGTH_SHORT).show();
            cleanupPendingWidget();
            return;
        }
        if (pendingWidgetInfo.configure != null) {
            Intent config = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
            config.setComponent(pendingWidgetInfo.configure);
            config.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
            configureWidgetLauncher.launch(config);
        } else {
            finishPlaceWidget();
        }
    }

    private Bundle createDefaultBindOptions(AppWidgetProviderInfo info) {
        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, info.minWidth);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, info.minHeight);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, info.minWidth);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, info.minHeight);
        return options;
    }

    private void finishPlaceWidget() {
        if (pendingWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        ensureHostListening();
        AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(pendingWidgetId);
        if (info == null) {
            Toast.makeText(this, "Widget perdu après liaison", Toast.LENGTH_SHORT).show();
            cleanupPendingWidget();
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        int minColSpan = grid.minSpanForPx(info.minWidth);
        int minRowSpan = grid.minSpanForPx(info.minHeight);
        final int width = grid.spanToPx(minColSpan);
        final int height = grid.spanToPx(minRowSpan);
        final int widgetId = pendingWidgetId;
        final AppWidgetProviderInfo widgetInfo = info;

        canvas.post(() -> placeWidgetOnCanvas(widgetId, widgetInfo, width, height));
    }

    private void placeWidgetOnCanvas(int widgetId, AppWidgetProviderInfo info,
                                     int width, int height) {
        int canvasW = Math.max(canvas.getWidth(), getResources().getDisplayMetrics().widthPixels);
        int x = grid.snapCoord(Math.max(0, (canvasW - width) / 2));
        x = Math.min(x, Math.max(0, grid.gridWidthPx() - width));   // jamais hors grille
        int y = grid.snapCoord((int) (80 * getResources().getDisplayMetrics().density));

        WidgetStore.Entry entry = new WidgetStore.Entry(widgetId, x, y, width, height);
        if (findCellForWidget(widgetId) == null) {
            entries.add(entry);
        }
        WidgetStore.upsert(this, entry);

        if (!attachWidgetView(entry, info)) {
            entries.remove(entry);
            WidgetStore.remove(this, widgetId);
            widgetHost.deleteAppWidgetId(widgetId);
            Toast.makeText(this, "Affichage du widget impossible", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Widget ajouté", Toast.LENGTH_SHORT).show();
        }

        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        allocatedWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        pendingWidgetInfo = null;
    }

    private boolean attachWidgetView(WidgetStore.Entry entry, AppWidgetProviderInfo info) {
        FrameLayout existing = findCellForWidget(entry.appWidgetId);
        if (existing != null) {
            canvas.removeView(existing);
        }

        float density = getResources().getDisplayMetrics().density;
        int handleSize = (int) (28 * density);

        FrameLayout cell = new FrameLayout(this);
        cell.setTag(entry);

        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(10 * density);
        border.setStroke((int) (1.2f * density), Color.parseColor("#8835D0DD"));
        border.setColor(Color.parseColor("#220B0E14"));
        cell.setBackground(border);

        try {
            ensureHostListening();
            updateWidgetOptions(entry, info);
            AppWidgetHostView hostView = widgetHost.createView(this, entry.appWidgetId, info);
            if (hostView == null) {
                hostView = new OrbeAppWidgetHostView(this);
                hostView.setAppWidget(entry.appWidgetId, info);
            }
            hostView.setPadding(0, 0, 0, 0);
            cell.addView(hostView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Exception e) {
            return false;
        }

        int minColSpan = grid.minSpanForPx(info.minWidth);
        int minRowSpan = grid.minSpanForPx(info.minHeight);

        // --- Poignées en surimpression (le widget capte sinon les touches) ---
        TextView moveHandle = new TextView(this);
        moveHandle.setText("✥");
        moveHandle.setTextColor(Color.WHITE);
        moveHandle.setTextSize(14);
        moveHandle.setGravity(Gravity.CENTER);
        moveHandle.setBackgroundColor(Color.parseColor("#CC35D0DD"));
        FrameLayout.LayoutParams moveLp = new FrameLayout.LayoutParams(handleSize, handleSize);
        moveLp.gravity = Gravity.TOP | Gravity.START;
        cell.addView(moveHandle, moveLp);

        TextView deleteBtn = new TextView(this);
        deleteBtn.setText("✕");
        deleteBtn.setTextColor(Color.WHITE);
        deleteBtn.setTextSize(14);
        deleteBtn.setGravity(Gravity.CENTER);
        deleteBtn.setBackgroundColor(Color.parseColor("#CCE0554E"));
        FrameLayout.LayoutParams deleteLp = new FrameLayout.LayoutParams(handleSize, handleSize);
        deleteLp.gravity = Gravity.TOP | Gravity.END;
        cell.addView(deleteBtn, deleteLp);

        TextView resizeHandle = new TextView(this);
        resizeHandle.setText("⤡");
        resizeHandle.setTextColor(Color.WHITE);
        resizeHandle.setTextSize(14);
        resizeHandle.setGravity(Gravity.CENTER);
        resizeHandle.setBackgroundColor(Color.parseColor("#CC35D0DD"));
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(handleSize, handleSize);
        handleLp.gravity = Gravity.BOTTOM | Gravity.END;
        cell.addView(resizeHandle, handleLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(entry.width, entry.height);
        lp.leftMargin = entry.x;
        lp.topMargin = entry.y;
        canvas.addView(cell, lp);
        cell.bringToFront();

        enableDrag(moveHandle, cell, entry);
        enableResize(resizeHandle, cell, entry, minColSpan, minRowSpan);
        deleteBtn.setOnClickListener(v -> confirmRemove(entry.appWidgetId));
        return true;
    }

    private void updateWidgetOptions(WidgetStore.Entry entry, AppWidgetProviderInfo info) {
        int spanW = Math.max(1, grid.pxToSpan(entry.width));
        int spanH = Math.max(1, grid.pxToSpan(entry.height));
        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, info.minWidth);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, info.minHeight);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, spanW * 70 - 30);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, spanH * 70 - 30);
        widgetManager.updateAppWidgetOptions(entry.appWidgetId, options);
    }

    private FrameLayout findCellForWidget(int appWidgetId) {
        for (int i = 0; i < canvas.getChildCount(); i++) {
            View child = canvas.getChildAt(i);
            if (!(child instanceof FrameLayout)) continue;
            Object tag = child.getTag();
            if (tag instanceof WidgetStore.Entry
                    && ((WidgetStore.Entry) tag).appWidgetId == appWidgetId) {
                return (FrameLayout) child;
            }
        }
        return null;
    }

    private void enableDrag(View handle, FrameLayout cell, WidgetStore.Entry entry) {
        final float[] lastRaw = new float[2];

        handle.setOnTouchListener((v, event) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cell.getLayoutParams();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastRaw[0] = event.getRawX();
                    lastRaw[1] = event.getRawY();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - lastRaw[0];
                    float dy = event.getRawY() - lastRaw[1];
                    lastRaw[0] = event.getRawX();
                    lastRaw[1] = event.getRawY();
                    int maxLeft = Math.max(0, grid.gridWidthPx() - lp.width);
                    int newLeft = grid.snapCoord(Math.max(0, lp.leftMargin + (int) dx));
                    lp.leftMargin = Math.min(newLeft, maxLeft);
                    lp.topMargin = grid.snapCoord(Math.max(0, lp.topMargin + (int) dy));
                    cell.setLayoutParams(lp);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    entry.x = lp.leftMargin;
                    entry.y = lp.topMargin;
                    persistEntry(entry);
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return false;
            }
        });
    }

    private void enableResize(View handle, FrameLayout cell, WidgetStore.Entry entry,
                              int minColSpan, int minRowSpan) {
        final float[] startRaw = new float[2];
        final int[] startSize = new int[2];

        handle.setOnTouchListener((v, event) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cell.getLayoutParams();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRaw[0] = event.getRawX();
                    startRaw[1] = event.getRawY();
                    startSize[0] = lp.width;
                    startSize[1] = lp.height;
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int totalDx = (int) (event.getRawX() - startRaw[0]);
                    int totalDy = (int) (event.getRawY() - startRaw[1]);

                    int colStart = grid.columnOf(lp.leftMargin);
                    int maxColByPos = Math.max(minColSpan, WidgetGrid.COLUMNS - colStart);
                    int colSpan = grid.clampSpan(
                            grid.pxToSpan(startSize[0] + totalDx),
                            minColSpan, Math.min(MAX_SPAN_COLS, maxColByPos));
                    int rowSpan = grid.clampSpan(
                            grid.pxToSpan(startSize[1] + totalDy),
                            minRowSpan, MAX_SPAN_ROWS);

                    lp.width = grid.spanToPx(colSpan);
                    lp.height = grid.spanToPx(rowSpan);
                    cell.setLayoutParams(lp);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    entry.width = lp.width;
                    entry.height = lp.height;
                    AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(entry.appWidgetId);
                    if (info != null) updateWidgetOptions(entry, info);
                    persistEntry(entry);
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return false;
            }
        });
    }

    private boolean isResizeHandleTarget(FrameLayout cell, MotionEvent event) {
        if (cell.getChildCount() == 0) return false;
        View handle = cell.getChildAt(cell.getChildCount() - 1);
        int[] loc = new int[2];
        handle.getLocationOnScreen(loc);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= loc[0] && x <= loc[0] + handle.getWidth()
                && y >= loc[1] && y <= loc[1] + handle.getHeight();
    }

    private void normalizeEntry(WidgetStore.Entry entry, AppWidgetProviderInfo info) {
        float density = getResources().getDisplayMetrics().density;
        int minCol = grid.minSpanForPx(info.minWidth);
        int minRow = grid.minSpanForPx(info.minHeight);

        int colSpan = grid.clampSpan(grid.pxToSpan(entry.width), minCol, MAX_SPAN_COLS);
        int rowSpan = grid.clampSpan(grid.pxToSpan(entry.height), minRow, MAX_SPAN_ROWS);

        entry.width = grid.spanToPx(colSpan);
        entry.height = grid.spanToPx(rowSpan);
        entry.x = grid.snapCoord(entry.x);
        entry.y = grid.snapCoord(entry.y);
    }

    private void persistEntry(WidgetStore.Entry entry) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).appWidgetId == entry.appWidgetId) {
                entries.set(i, entry);
                break;
            }
        }
        WidgetStore.upsert(this, entry);
    }

    private void confirmRemove(int appWidgetId) {
        new AlertDialog.Builder(this)
                .setTitle("Supprimer le widget ?")
                .setPositiveButton("Supprimer", (d, w) -> removeWidget(appWidgetId))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void removeWidget(int appWidgetId) {
        FrameLayout cell = findCellForWidget(appWidgetId);
        if (cell != null) canvas.removeView(cell);
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).appWidgetId == appWidgetId) {
                entries.remove(i);
                break;
            }
        }
        WidgetStore.save(this, entries);
        widgetHost.deleteAppWidgetId(appWidgetId);
        Toast.makeText(this, "Widget supprimé", Toast.LENGTH_SHORT).show();
    }

    private void showBindRefusedMessage() {
        String message = LauncherHelper.isDefaultLauncher(this)
                ? "Ce widget a refusé d'être ajouté. Réessaie ou choisis-en un autre."
                : "Android n'autorise l'ajout de widgets que pour l'écran d'accueil par "
                        + "défaut. Définis Orbe comme écran d'accueil pour ajouter des widgets.";
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Widget non autorisé")
                .setMessage(message)
                .setNegativeButton("Fermer", null);
        if (!LauncherHelper.isDefaultLauncher(this)) {
            builder.setPositiveButton("Définir Orbe", (d, w) ->
                    LauncherHelper.requestDefaultLauncher(this));
        }
        builder.show();
    }

    private void cleanupPendingWidget() {
        if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            widgetHost.deleteAppWidgetId(pendingWidgetId);
        } else if (allocatedWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            widgetHost.deleteAppWidgetId(allocatedWidgetId);
        }
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        allocatedWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        pendingWidgetInfo = null;
    }
}
