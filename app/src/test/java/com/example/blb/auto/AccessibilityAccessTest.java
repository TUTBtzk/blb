package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccessibilityAccessTest {

    private static final String TARGET =
            "com.example.blb/com.example.blb.auto.BlbAccessibilityService";

    @Test
    public void emptyListBecomesTargetOnly() {
        assertEquals(TARGET, AccessibilityAccess.mergeComponent(null, TARGET));
        assertEquals(TARGET, AccessibilityAccess.mergeComponent("", TARGET));
    }

    @Test
    public void mergePreservesEveryExistingEntry() {
        String existing = "pkg.one/.Reader:bad entry:pkg.two/pkg.two.Service";
        assertEquals(existing + ":" + TARGET,
                AccessibilityAccess.mergeComponent(existing, TARGET));
    }

    @Test
    public void mergeDoesNotDuplicateEquivalentComponent() {
        String shortName = "com.example.blb/.auto.BlbAccessibilityService";
        assertEquals(shortName, AccessibilityAccess.mergeComponent(shortName, TARGET));
        assertTrue(AccessibilityAccess.containsComponent(shortName, TARGET));
    }

    @Test
    public void containsIgnoresMalformedAndDifferentEntries() {
        String entries = "bad entry:other.pkg/.Service";
        assertFalse(AccessibilityAccess.containsComponent(entries, TARGET));
    }
}
