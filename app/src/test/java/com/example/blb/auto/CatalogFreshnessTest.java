package com.example.blb.auto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.Novel;

import org.junit.Test;

/** 作者可能继续更新，但这个风险只允许提醒或按开关扫一次，不能恢复成八个号各扫全书。 */
public class CatalogFreshnessTest {
    private static final long HOUR = 3_600_000L;
    private static final long NOW = 2_000_000_000_000L;

    @Test
    public void theDefaultThresholdExpiresOnlyAfterTwentyFourHours() {
        assertFalse(CatalogStatus.stale(NOW, NOW, 24));
        assertFalse(CatalogStatus.stale(NOW - 24 * HOUR + 1, NOW, 24));
        assertFalse(CatalogStatus.stale(NOW - 24 * HOUR, NOW, 24));
        assertTrue(CatalogStatus.stale(NOW - 24 * HOUR - 1, NOW, 24));
    }

    @Test
    public void invalidThresholdsFallBackToTwentyFourHours() {
        for (int invalid : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertFalse(CatalogStatus.stale(NOW - 24 * HOUR, NOW, invalid));
            assertTrue(CatalogStatus.stale(NOW - 24 * HOUR - 1, NOW, invalid));
        }
    }

    @Test
    public void theChosenThresholdIsRespectedInsteadOfAlwaysUsingTwentyFourHours() {
        assertFalse(CatalogStatus.stale(NOW - HOUR, NOW, 1));
        assertTrue(CatalogStatus.stale(NOW - HOUR - 1, NOW, 1));
        assertFalse(CatalogStatus.stale(NOW - 25 * HOUR, NOW, 48));
        assertTrue(CatalogStatus.stale(NOW - 48 * HOUR - 1, NOW, 48));
    }

    @Test
    public void unknownAndFutureScanTimesNeverPretendTheCatalogIsFresh() {
        for (long unknown : new long[]{0, -1, Long.MIN_VALUE, NOW + 1}) {
            assertTrue(CatalogStatus.stale(unknown, NOW, 24));
            assertNotNull(CatalogStatus.ageWarning(unknown, NOW, 24));
        }
    }

    @Test
    public void theDefaultDisabledChoiceNeverStartsAnAutomaticScan() {
        for (long scannedAt : new long[]{NOW - 25 * HOUR, 0, -1, NOW + 1}) {
            assertFalse(CatalogStatus.shouldAutoSync(false, 0, scannedAt, NOW, 24));
            assertNotNull("关闭自动扫描仍须说明目录风险",
                    CatalogStatus.ageWarning(scannedAt, NOW, 24));
        }
    }

    @Test
    public void anEnabledAutomaticScanIsReservedForTheFirstAccount() {
        long stale = NOW - 25 * HOUR;
        assertTrue(CatalogStatus.shouldAutoSync(true, 0, stale, NOW, 24));
        for (int later : new int[]{-1, 1, 2, 7, 8}) {
            assertFalse("后面的号不能重复扫全书，index=" + later,
                    CatalogStatus.shouldAutoSync(true, later, stale, NOW, 24));
        }
    }

    @Test
    public void aFreshCatalogIsNeverRescannedEvenWhenTheSwitchIsEnabled() {
        assertFalse(CatalogStatus.shouldAutoSync(true, 0, NOW, NOW, 24));
        assertFalse(CatalogStatus.shouldAutoSync(true, 0, NOW - 24 * HOUR, NOW, 24));
        assertFalse(CatalogStatus.shouldAutoSync(true, 1, NOW, NOW, 24));
    }

    @Test
    public void theFirstAccountCanRepairMissingOrFutureScanMetadataWhenEnabled() {
        assertTrue(CatalogStatus.shouldAutoSync(true, 0, 0, NOW, 24));
        assertTrue(CatalogStatus.shouldAutoSync(true, 0, -1, NOW, 24));
        assertTrue(CatalogStatus.shouldAutoSync(true, 0, NOW + 1, NOW, 24));
    }

    @Test
    public void oldCatalogWarningsExposeTheAgeAndTheUnscannedChapterRisk() {
        String warning = CatalogStatus.ageWarning(NOW - 25 * HOUR, NOW, 24);
        assertNotNull(warning);
        assertTrue(warning, warning.contains("25 小时前"));
        assertTrue(warning, warning.contains("作者可能有新章没进账本"));
        assertNull(CatalogStatus.ageWarning(NOW - 24 * HOUR, NOW, 24));
    }

    @Test
    public void unknownTimesAreDescribedWithoutInventingAnAge() {
        String unknown = CatalogStatus.ageWarning(-1, NOW, 24);
        assertNotNull(unknown);
        assertTrue(unknown, unknown.contains("尚未记录"));
        assertTrue(unknown, unknown.contains("同步目录"));
        assertFalse(unknown, unknown.contains("小时前"));
        String future = CatalogStatus.ageWarning(NOW + 1, NOW, 24);
        assertTrue(future, future.contains("晚于当前时间"));
        assertTrue(future, future.contains("无法判断新旧"));
        assertFalse(future, future.contains("小时前"));
    }

    @Test
    public void theRunConclusionKeepsBothTheCatalogSummaryAndItsWarning() {
        Novel novel = new Novel();
        novel.catalogScannedAt = NOW - 25 * HOUR;
        novel.catalogChapterCount = 612;
        String note = CatalogStatus.runNote(novel, NOW, 24);
        assertTrue(note, note.contains("目录："));
        assertTrue(note, note.contains("612 章"));
        assertTrue(note, note.contains("25 小时前"));
        assertTrue(note, note.contains("新章没进账本"));
    }
}
