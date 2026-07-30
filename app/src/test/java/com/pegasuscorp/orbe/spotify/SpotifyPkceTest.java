package com.pegasuscorp.orbe.spotify;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SpotifyPkceTest {

    @Test
    public void challenge_isDeterministicForVerifier() throws Exception {
        String verifier = "test-verifier-1234567890abcdefghijklmnopqrstuvwxyz";
        assertEquals(SpotifyPkce.challenge(verifier), SpotifyPkce.challenge(verifier));
    }

    @Test
    public void generateVerifier_hasExpectedLength() {
        String verifier = SpotifyPkce.generateVerifier();
        assertEquals(64, verifier.length());
        assertNotEquals(SpotifyPkce.generateVerifier(), verifier);
    }

    @Test
    public void challenge_hasNoPadding() throws Exception {
        String challenge = SpotifyPkce.challenge(SpotifyPkce.generateVerifier());
        assertTrue(challenge.length() > 20);
        assertTrue(!challenge.contains("="));
    }
}
