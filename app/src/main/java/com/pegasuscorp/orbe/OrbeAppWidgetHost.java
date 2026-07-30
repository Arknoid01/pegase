package com.pegasuscorp.orbe;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.view.View;

/**
 * Hôte de widgets pour le tableau Orbe.
 */
public class OrbeAppWidgetHost extends AppWidgetHost {

    public OrbeAppWidgetHost(Context context, int hostId) {
        super(context, hostId);
    }

    @Override
    protected AppWidgetHostView onCreateView(Context context, int appWidgetId,
                                             AppWidgetProviderInfo appWidget) {
        return new OrbeAppWidgetHostView(context);
    }
}
