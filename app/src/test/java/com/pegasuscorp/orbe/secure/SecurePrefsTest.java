package com.pegasuscorp.orbe.secure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class SecurePrefsTest {

    @Test
    public void migrate_copieTousLesTypes_puisEffaceLAncienFichier() {
        Context ctx = RuntimeEnvironment.getApplication();
        SharedPreferences from = ctx.getSharedPreferences("old", Context.MODE_PRIVATE);
        SharedPreferences to = ctx.getSharedPreferences("new", Context.MODE_PRIVATE);
        from.edit().putString("k", "secret").putLong("t", 42L).putBoolean("b", true).commit();

        SecurePrefs.migrate(from, to);

        assertEquals("secret", to.getString("k", ""));
        assertEquals(42L, to.getLong("t", 0));
        assertTrue(to.getBoolean("b", false));
        assertTrue(from.getAll().isEmpty());
    }

    @Test
    public void open_sansKeystore_retombeSurLAncienFichier() {
        Context ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("plain_x", Context.MODE_PRIVATE).edit().putString("a", "1").commit();
        SharedPreferences p = SecurePrefs.open(ctx, "plain_x", "enc_x");
        assertEquals("1", p.getString("a", ""));
    }
}
