package com.pegasuscorp.orbe;

import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * Vue hôte avec repli si le widget ne peut pas s'afficher.
 */
public class OrbeAppWidgetHostView extends AppWidgetHostView {

    public OrbeAppWidgetHostView(Context context) {
        super(context);
        setBackgroundColor(Color.parseColor("#22FFFFFF"));
    }

    @Override
    protected View getDefaultView() {
        TextView fallback = new TextView(getContext());
        fallback.setText("Widget");
        fallback.setTextColor(Color.WHITE);
        fallback.setGravity(Gravity.CENTER);
        fallback.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        return fallback;
    }
}
