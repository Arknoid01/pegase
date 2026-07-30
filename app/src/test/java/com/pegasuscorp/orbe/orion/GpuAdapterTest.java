package com.pegasuscorp.orbe.orion;

import android.widget.CheckBox;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GpuAdapterTest {

    @Test
    public void onBind_setsCheckedFromModel_notRecycledView() {
        AtomicInteger callbacks = new AtomicInteger();
        GpuAdapter adapter = new GpuAdapter((option, allowed) -> callbacks.incrementAndGet());

        GpuOption a = new GpuOption(new GpuOffer("a", "A100", 80, 1.2f, true), true);
        GpuOption b = new GpuOption(new GpuOffer("b", "4090", 24, 0.4f, true), false);
        adapter.submit(Arrays.asList(a, b));

        ActivityController<android.app.Activity> ctrl =
                Robolectric.buildActivity(android.app.Activity.class).setup();
        RecyclerView rv = new RecyclerView(ctrl.get());
        rv.setLayoutManager(new LinearLayoutManager(ctrl.get()));
        rv.setAdapter(adapter);
        rv.measure(0, 0);
        rv.layout(0, 0, 800, 2000);

        GpuAdapter.Holder h0 = (GpuAdapter.Holder) rv.findViewHolderForAdapterPosition(0);
        GpuAdapter.Holder h1 = (GpuAdapter.Holder) rv.findViewHolderForAdapterPosition(1);
        assertNotNull(h0);
        assertNotNull(h1);

        assertTrue(h0.checkBox.isChecked());
        assertFalse(h1.checkBox.isChecked());
        assertEquals(0, callbacks.get()); // setChecked ne doit pas notifier

        // Simule un rebind sur la même vue avec un autre modèle
        a.isAllowed = false;
        b.isAllowed = true;
        adapter.notifyDataSetChanged();
        rv.measure(0, 0);
        rv.layout(0, 0, 800, 2000);

        h0 = (GpuAdapter.Holder) rv.findViewHolderForAdapterPosition(0);
        h1 = (GpuAdapter.Holder) rv.findViewHolderForAdapterPosition(1);
        assertFalse(h0.checkBox.isChecked());
        assertTrue(h1.checkBox.isChecked());
        assertEquals(0, callbacks.get());
    }

    @Test
    public void userToggle_updatesModel() {
        GpuAdapter adapter = new GpuAdapter(null);
        GpuOption opt = new GpuOption(new GpuOffer("x", "L40", 48, 0.9f, true), false);
        adapter.submit(Arrays.asList(opt));

        ActivityController<android.app.Activity> ctrl =
                Robolectric.buildActivity(android.app.Activity.class).setup();
        RecyclerView rv = new RecyclerView(ctrl.get());
        rv.setLayoutManager(new LinearLayoutManager(ctrl.get()));
        rv.setAdapter(adapter);
        rv.measure(0, 0);
        rv.layout(0, 0, 800, 400);

        GpuAdapter.Holder h = (GpuAdapter.Holder) rv.findViewHolderForAdapterPosition(0);
        assertNotNull(h);
        CheckBox cb = h.checkBox;
        assertFalse(cb.isChecked());
        cb.setChecked(true);
        assertTrue(opt.isAllowed);
        assertEquals("x", adapter.allowedIds().get(0));
    }
}
