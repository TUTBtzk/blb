package com.example.blb.auto;

import org.junit.Test;

import static org.junit.Assert.*;

/** 只验证取证输出；业务读取仍经原有多屏管线，不另外建立一套行分类。 */
public class SubscribedDetailDiagnosticTest {
    private static final String DATE = "2026-09-08";

    @Test public void frozenSnapshotRetainsRawMoneyAndTheExistingRowClassifications() throws Exception {
        FakeNode heading = FakeNode.text(DATE).withId("com.sfacg:id/tvTime").item(0)
                .withBounds(400, 240, 680, 280);
        FakeNode first = SubscribedDetailPipelineTest.transaction(1, 1,
                "卷一 第1章  甲（测试）", "10", " 代券 ", null);
        FakeNode clipped = SubscribedDetailPipelineTest.transaction(2, 2,
                "卷一 第2章 乙", "12", "代券", null);
        ((FakeNode) clipped.child(1)).withBounds(810, 2300, 950, 2360);
        FakeNode unknown = FakeNode.node().item(3).withBounds(20, 1010, 1060, 1240).add(
                FakeNode.text("标题节点未提供").withBounds(60, 1080, 750, 1170),
                FakeNode.text("9").withBounds(810, 1075, 950, 1120),
                FakeNode.text(" 火券 ").withBounds(810, 1125, 950, 1170));
        FakeNode footer = FakeNode.text("清单约5分钟更新一次，可下拉刷新").item(4)
                .withBounds(60, 1250, 1020, 1310);
        FakeNode root = SubscribedDetailPipelineTest.page(5, heading, first, clipped, unknown, footer);
        SelectorSet selectors = new SelectorSet(new SubscribedDetailPipelineTest.Reader(root).selectors,
                "测试冻结快照选择器");
        NodeView frozen = DiagnosticSnapshot.capture(root).root;
        ((FakeNode) first.child(1)).withText("999");
        String report = SubscribedDetail.evidenceSnapshot(frozen, selectors);
        assertTrue(report, report.contains("单屏不能证明跨屏日期/完整性"));
        assertTrue(report, report.contains("列表标识=\"com.sfacg:id/baseListView:"));
        assertTrue(report, report.contains("CollectionInfo.rowCount=5"));
        assertTrue(report, report.contains("CollectionItemInfo.rowIndex=1；CollectionItemInfo.rowSpan=1"));
        assertTrue(report, report.contains("snapshot行序=2；UI item index=1；classification=transaction"));
        assertTrue(report, report.contains("rawDesc=\"卷一 第1章  甲（测试）\""));
        assertTrue(report, report.contains("rawAmount=\"10\"；parsedAmount=10"));
        assertTrue(report, report.contains("rawAmount=\"12\"；parsedAmount=-1；金额未知原因=节点被列表视口裁剪"));
        assertTrue(report, report.contains("rawCurrency=\" 代券 \"；parsedCurrency=\"代券\""));
        assertTrue(report, report.contains("classification=date"));
        assertTrue(report, report.contains("classification=unknown"));
        assertTrue(report, report.contains("classification=footer"));
        assertTrue(report, report.contains("rawAmount=\"9\"；parsedAmount=-1；rawCurrency=\" 火券 \""));
        assertTrue(report, report.contains("发现火券=true"));
        assertFalse(report.contains("999"));

        FakeNode middle = SubscribedDetailPipelineTest.window(8, 3, 5);
        String middleReport = SubscribedDetail.evidenceSnapshot(DiagnosticSnapshot.capture(middle).root, selectors);
        assertTrue(middleReport, middleReport.contains("本屏连续区间日期=<未知>"));
        ((FakeNode) middle.child(0)).withText("目录列表");
        assertEquals("本快照不是订阅明细页", SubscribedDetail.evidenceSnapshot(middle, selectors));
    }

    @Test public void perScreenLogsSeparateRawValuesFromProvenDatesAndKeepFailedFrames() throws Exception {
        SubscribedDetailPipelineTest.Reader reader = SubscribedDetailPipelineTest.twenty();
        SubscribedDetail.ReadResult result = SubscribedDetailPipelineTest.read(reader, 20);
        assertTrue(result.describe(), result.complete());
        String logs = String.join("\n", reader.logs);
        assertTrue(logs.contains("rawAmount=\"10\"；parsedAmount=10"));
        assertTrue(logs.contains("CollectionInfo.rowCount=15"));
        assertTrue(logs.contains("CollectionInfo.rowCount=20"));
        assertTrue(logs.contains("CollectionItemInfo.rowSpan=1"));
        assertTrue(logs.contains("明细第 2 屏日期归属：snapshot行序=1；UI item index=3"));
        assertTrue(logs.contains("归属日期=\"" + DATE + "\"；日期依据=连续位置的已知组头"));

        FakeNode invalid = SubscribedDetailPipelineTest.window(3, 0, 2);
        ((FakeNode) invalid.child(1)).collection(-1);
        SubscribedDetailPipelineTest.Reader failed = new SubscribedDetailPipelineTest.Reader(invalid);
        assertFalse(SubscribedDetailPipelineTest.read(failed, 3).complete());
        String failedLogs = String.join("\n", failed.logs);
        assertTrue(failedLogs.contains("位置证据有效=false"));
        assertTrue(failedLogs.contains("UI item index=-1"));
        assertTrue(failedLogs.contains("rawAmount=\"10\"；parsedAmount=10"));
        assertFalse(failedLogs.contains("屏日期归属"));
    }

    @Test public void conflictLogShowsTheActualEarlierAndCurrentMonetaryFacts() throws Exception {
        FakeNode first = SubscribedDetailPipelineTest.transaction(0, 0,
                "卷一 第1章 甲", null, "代券", DATE);
        FakeNode middle = SubscribedDetailPipelineTest.transaction(0, 0,
                "卷一 第1章 甲", "10", "代券", DATE);
        ((FakeNode) middle.child(0)).withBounds(0, 0, 0, 0);
        FakeNode last = SubscribedDetailPipelineTest.transaction(0, 0,
                "卷一 第1章 甲", "20", "代券", DATE);
        SubscribedDetailPipelineTest.Reader reader = new SubscribedDetailPipelineTest.Reader(
                SubscribedDetailPipelineTest.page(1, first),
                SubscribedDetailPipelineTest.page(1, middle),
                SubscribedDetailPipelineTest.page(1, last));
        assertFalse(SubscribedDetailPipelineTest.read(reader, 1).complete());
        String conflict = null;
        for (String line : reader.logs) if (line.contains("此前冲突观察")) conflict = line;
        assertNotNull(conflict);
        assertTrue(conflict, conflict.contains("金额=10，币种=\"代券\"，归属日期=<未知>"));
        assertTrue(conflict, conflict.contains("金额=20，币种=\"代券\"，归属日期=\"" + DATE + "\""));
    }
}
