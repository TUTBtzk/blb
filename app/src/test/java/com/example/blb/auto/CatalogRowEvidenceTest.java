package com.example.blb.auto;

import org.junit.Test;

import static org.junit.Assert.*;

public class CatalogRowEvidenceTest {
    @Test public void inventoryIncludesChildrenThatTitleSearchCannotFind() throws Exception {
        FakeNode missing = FakeNode.node().withId("missing_title_row").withBounds(0, 0, 0, 0);
        FakeNode titled = FakeNode.node().add(FakeNode.text("第81章 心脏").withId("title"));
        FakeNode list = list().add(missing, titled);
        String evidence = CatalogRowEvidence.dump(list, OfflineRunner.bundled());
        assertTrue(evidence.contains("直接子项数=2"));
        assertTrue(evidence.contains("当前页标题命中数=1"));
        assertTrue(evidence.contains("没有title节点"));
        assertTrue(evidence.contains("行坐标没有面积"));
        assertTrue(evidence.indexOf("当前快照直接子项 1") < evidence.indexOf("当前快照直接子项 2"));
        assertTrue(evidence.indexOf("missing_title_row") < evidence.indexOf("第81章 心脏"));
    }

    @Test public void multipleTitlesAndClippingExplainWhyTheRowIsUnusable() throws Exception {
        FakeNode child = FakeNode.node().withBounds(0, -10, 100, 90).add(
                FakeNode.text("").withId("title").withBounds(0, -5, 100, 40),
                FakeNode.text("一卷总结").withId("title").withBounds(0, 40, 100, 80));
        String evidence = CatalogRowEvidence.dump(list().add(child), OfflineRunner.bundled());
        assertTrue(evidence.contains("title数量=2"));
        assertTrue(evidence.contains("行内有多个title节点"));
        assertTrue(evidence.contains("标题为空"));
        assertTrue(evidence.contains("行越过列表边界"));
        assertTrue(evidence.contains("一卷总结"));
    }

    @Test public void floatingControlsOutsideListAreNotInventoryRows() throws Exception {
        FakeNode root = FakeNode.node().add(list().add(FakeNode.node().add(
                FakeNode.text("番外").withId("title"))), FakeNode.node().withId("goto_top"));
        String evidence = CatalogRowEvidence.dump(root, OfflineRunner.bundled());
        assertTrue(evidence.contains("直接子项数=1"));
        assertFalse(evidence.contains("当前快照直接子项 2"));
    }

    private static FakeNode list() {
        return FakeNode.node().withId("com.sfacg:id/list_view")
                .withClass("android.widget.ListView").withBounds(0, 0, 100, 500);
    }
}
