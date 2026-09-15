package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.Novel;
import org.junit.Test;
import java.util.Calendar;

public class CatalogStatusTest {
    @Test
    public void upgradedBookDoesNotPretendItsOldManualCatalogWasScanned() {
        Novel novel = new Novel();
        assertEquals("目录：还没扫过", CatalogStatus.describe(novel, System.currentTimeMillis()));
    }

    @Test
    public void summaryUsesTheScanTimeAndItsRecordedCount() {
        Calendar morning = Calendar.getInstance();
        morning.set(2026, Calendar.SEPTEMBER, 14, 9, 12, 0);
        morning.set(Calendar.MILLISECOND, 0);
        Novel novel = new Novel();
        novel.catalogScannedAt = morning.getTimeInMillis();
        novel.catalogChapterCount = 612;
        assertEquals("目录：今天 09:12 扫过 · 612 章",
                CatalogStatus.describe(novel, morning.getTimeInMillis() + 60_000));
        morning.add(Calendar.DAY_OF_MONTH, 1);
        assertTrue(CatalogStatus.describe(novel, morning.getTimeInMillis())
                .startsWith("目录：2026-09-14 09:12"));
    }
}
