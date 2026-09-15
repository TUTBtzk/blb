package com.example.blb.auto;

import org.junit.Test;

import static org.junit.Assert.*;

public class TreeDumpTest {
    @Test public void collectionAndItemEvidenceSurvivesExport() {
        FakeNode root = FakeNode.node().withId("com.sfacg:id/baseListView")
                .withClass("android.widget.ListView").collection(15).scrollable(true)
                .scrollActions(true, false).add(FakeNode.text("2026-09-08").item(0).heading(true));
        String dump = TreeDump.dump(root);
        assertTrue(dump.contains("collectionRows=15 collectionColumns=1"));
        assertTrue(dump.contains("itemRowIndex=0 itemRowSpan=1"));
        assertTrue(dump.contains("heading=true"));
        assertTrue(dump.contains("scrollForwardAction=true scrollBackwardAction=false"));
        assertTrue(dump.contains("com.sfacg:id/baseListView"));
    }

    @Test public void fullTextWhitespaceAndEscapesAreNotSilentlyLost() {
        String longTitle = "  " + new String(new char[90]).replace('\0', '章') + "（上）\n\t\"引号\"  ";
        String dump = TreeDump.dump(FakeNode.text(longTitle));
        assertTrue(dump.contains(new String(new char[90]).replace('\0', '章')));
        assertTrue(dump.contains("text=\"  "));
        assertTrue(dump.contains("（上）\\n\\t\\\"引号\\\"  \""));
        assertFalse(dump.contains("…"));
    }

    @Test public void depthAndNodeTruncationAreExplicitAndExactBudgetIsNotTruncation() {
        FakeNode chain = FakeNode.text("root").add(FakeNode.text("child").add(FakeNode.text("grandchild")));
        assertTrue(TreeDump.dump(chain, 10, 1).contains("树深度截断，上限=1"));
        assertTrue(TreeDump.dump(chain, 2, 10).contains("节点数量截断，上限=2"));
        assertFalse(TreeDump.dump(chain, 3, 10).contains("截断"));
    }

    @Test public void zeroBoundsAndUnknownMetadataRemainVisible() {
        String dump = TreeDump.dump(FakeNode.text("").withBounds(0, 0, 0, 0).visible(false));
        assertTrue(dump.contains("text=\"\""));
        assertTrue(dump.contains("visible=false"));
        assertTrue(dump.contains("[0,0-0,0]"));
        assertTrue(dump.contains("collectionRows=-1"));
        assertTrue(dump.contains("itemRowIndex=-1"));
    }
}
