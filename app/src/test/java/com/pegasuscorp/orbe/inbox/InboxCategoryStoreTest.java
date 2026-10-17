package com.pegasuscorp.orbe.inbox;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class InboxCategoryStoreTest {

    private static InboxItem sms(String address) {
        return new InboxItem(InboxItem.Source.SMS, "Patron", "", 0, "", 0, false, address);
    }

    @Test
    public void numeroSmsMemeRangementQuelQueSoitLeFormat() {
        Context ctx = ApplicationProvider.getApplicationContext();
        InboxCategoryStore.set(ctx, sms("+33 6 12 34 56 78"), InboxCategoryStore.Category.PRO);
        assertEquals(InboxCategoryStore.Category.PRO, InboxCategoryStore.get(ctx, sms("0612345678")));
        InboxCategoryStore.set(ctx, sms("0612345678"), InboxCategoryStore.Category.PERSO);
        assertEquals(InboxCategoryStore.Category.PERSO,
                InboxCategoryStore.get(ctx, sms("+33612345678")));
    }

    @Test
    public void whatsappParNom() {
        Context ctx = ApplicationProvider.getApplicationContext();
        InboxItem wa = new InboxItem(InboxItem.Source.WHATSAPP, "Équipe 2026", "", 0, "", 0,
                true, "");
        assertEquals(InboxCategoryStore.Category.PERSO, InboxCategoryStore.get(ctx, wa));
        InboxCategoryStore.set(ctx, wa, InboxCategoryStore.Category.PRO);
        assertEquals(InboxCategoryStore.Category.PRO, InboxCategoryStore.get(ctx, wa));
    }
}
