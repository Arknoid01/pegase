package com.pegasuscorp.orbe.memory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class EntityResolverTest {

    @Test
    public void resolve_assistantAliasMapsToPegase() {
        Context ctx = RuntimeEnvironment.getApplication();
        EntityResolver.Resolution r = EntityResolver.resolve(ctx, "comment va mon assistant");
        assertFalse(r.forInjection(1).isEmpty());
        assertEquals("project_pegase", r.forInjection(1).get(0).entity.id);
    }

    @Test
    public void resolve_phoneAliasMapsToDevice() {
        Context ctx = RuntimeEnvironment.getApplication();
        EntityResolver.Resolution r = EntityResolver.resolve(ctx, "sur mon téléphone");
        assertFalse(r.forInjection(1).isEmpty());
        assertEquals("device_nothing_phone", r.forInjection(1).get(0).entity.id);
    }

    @Test
    public void resolve_gameAliasMapsToFableris() {
        Context ctx = RuntimeEnvironment.getApplication();
        EntityResolver.Resolution r = EntityResolver.resolve(ctx, "mon jeu");
        assertFalse(r.forInjection(1).isEmpty());
        assertEquals("project_fableris", r.forInjection(1).get(0).entity.id);
    }

    @Test
    public void resolve_twoProjectsCanBeAmbiguous() {
        Context ctx = RuntimeEnvironment.getApplication();
        EntityResolver.Resolution r = EntityResolver.resolve(ctx,
                "mon city builder et mon assistant");
        assertTrue(r.forInjection(2).size() >= 2);
        assertFalse(r.ambiguous.isEmpty());
    }
}
