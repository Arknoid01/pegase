package com.pegasuscorp.orbe.memory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class UserProfileStoreTest {

    @Test
    public void saveProfileForm_persistsAssistantPersonality() {
        var ctx = RuntimeEnvironment.getApplication();
        UserProfileStore store = UserProfileStore.getInstance(ctx);
        String custom = "Direct, chaleureux, jamais condescendant.";
        assertTrue(store.saveProfileForm(
                "Testeur",
                custom,
                Collections.singletonList("Orbe"),
                Collections.emptyList(),
                Collections.emptyList()));
        assertEquals("Testeur", store.getUserName());
        assertEquals(custom, store.getAssistantPersonality());
    }
}
