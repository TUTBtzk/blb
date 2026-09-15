package com.example.blb.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 过期阈值只允许正数，坏设置不能让目录永不过期或每一步都触发重扫。 */
public class PrefsCatalogSettingsTest {

    @Test
    public void invalidCatalogAgeFallsBackToOneDay() {
        assertEquals(24, Prefs.catalogHoursOrDefault(0));
        assertEquals(24, Prefs.catalogHoursOrDefault(-1));
        assertEquals(24, Prefs.catalogHoursOrDefault(Integer.MIN_VALUE));
    }

    @Test
    public void aPositiveCatalogAgeKeepsTheChosenInterval() {
        assertEquals(1, Prefs.catalogHoursOrDefault(1));
        assertEquals(24, Prefs.catalogHoursOrDefault(24));
        assertEquals(72, Prefs.catalogHoursOrDefault(72));
        assertEquals(Integer.MAX_VALUE, Prefs.catalogHoursOrDefault(Integer.MAX_VALUE));
    }
}
