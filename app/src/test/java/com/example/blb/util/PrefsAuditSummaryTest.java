package com.example.blb.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 常驻摘要会直接决定使用者要不要再核对，隔天不能还把旧证据说成「今天」。 */
public class PrefsAuditSummaryTest {

    @Test
    public void todaysSummaryKeepsItsAccountAndRepairCounts() {
        String summary = "最后核对 今天 09:20 · 8/8 个号 · 补记 3 · 修正 1";
        assertEquals(summary, Prefs.datedAuditSummary(summary, "2026-09-14", "2026-09-14"));
    }

    @Test
    public void anOlderSummaryShowsTheRecordedDate() {
        assertEquals("最后核对 2026-09-13 09:20 · 8/8 个号 · 补记 3 · 修正 1",
                Prefs.datedAuditSummary("最后核对 今天 09:20 · 8/8 个号 · 补记 3 · 修正 1",
                        "2026-09-13", "2026-09-14"));
    }

    @Test
    public void aMissingSummaryDoesNotInventACompletedRun() {
        assertEquals("", Prefs.datedAuditSummary(null, "2026-09-13", "2026-09-14"));
        assertEquals("", Prefs.datedAuditSummary("", "2026-09-13", "2026-09-14"));
    }

    @Test
    public void anAbsoluteDateIsNotRewritten() {
        String summary = "最后核对 2026-09-12 09:20 · 7/8 个号 · 已中止";
        assertEquals(summary, Prefs.datedAuditSummary(summary, "2026-09-13", "2026-09-14"));
    }
}
