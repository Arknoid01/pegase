package com.pegasuscorp.orbe.copilot;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Smoke — packageOf ne doit pas NPE. */
public class A11yRootPickerPackageTest {

    @Test
    public void packageOf_null_returnsEmpty() {
        assertEquals("", A11yRootPicker.packageOf(null));
    }
}
