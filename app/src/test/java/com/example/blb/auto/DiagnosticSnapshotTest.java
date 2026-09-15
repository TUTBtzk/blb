package com.example.blb.auto;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.*;

public class DiagnosticSnapshotTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void changingLiveChildIsReadOnceForRowsTreeAndProbe() throws Exception {
        ChangingRoot source = new ChangingRoot();
        InspectorCapture.Snapshot result = InspectorCapture.sample(source, selectors(), 1234);

        assertEquals(1, source.childReads);
        assertTrue(result.content.contains("title.text 原文开始>>>\n第1章 首次采样"));
        assertTrue(result.content.contains("=== 当前窗口 TreeDump 原文 ==="));
        assertTrue(result.content.contains("text=\"第1章 首次采样\""));
        assertTrue(result.report.contains("chapter_row_title  → TextView text=\"第1章 首次采样\""));
        assertFalse(result.report.contains("第2章 后来画面"));
        assertTrue(result.report.contains("不是 Android 原子帧"));
        assertEquals(1234, result.capturedAt);
    }

    @Test public void automaticArchiveUsesTheSameSingleTraversalEntry() throws Exception {
        ChangingRoot source = new ChangingRoot();
        File files = temporary.newFolder("files");
        File saved = DiagnosticStore.save(files, "变化中的目录", 1234, source, selectors());
        String content = DiagnosticStore.read(files, saved);

        assertEquals(1, source.childReads);
        assertTrue(content.contains("第1章 首次采样"));
        assertFalse(content.contains("第2章 后来画面"));
        assertTrue(content.contains("=== 诊断采样副本 ==="));
    }

    @Test public void parentRelationsMetadataRawTextAndBoundsRemainReadOnly() {
        FakeNode child = FakeNode.text("  第81章 心脏（上）\n\t")
                .withDesc("原始描述\n  ").withId("com.sfacg:id/title")
                .withClass("android.widget.TextView").clickable(true).enabled(false)
                .checked(true).scrollable(true).heading(true).visible(false)
                .collection(15, 2).item(4, 1, 1, 2).scrollActions(true, false)
                .withBounds(9, 10, 1083, 147);
        DiagnosticSnapshot snapshot = DiagnosticSnapshot.capture(FakeNode.node().add(child));
        NodeView frozen = snapshot.root.child(0);

        assertSame(snapshot.root, frozen.parent());
        assertNull(snapshot.root.parent());
        assertEquals(child.text(), frozen.text());
        assertEquals(child.desc(), frozen.desc());
        assertEquals(child.viewId(), frozen.viewId());
        assertEquals(child.className(), frozen.className());
        assertTrue(frozen.clickable());
        assertFalse(frozen.enabled());
        assertTrue(frozen.checked());
        assertTrue(frozen.scrollable());
        assertTrue(frozen.heading());
        assertFalse(frozen.visible());
        assertEquals(15, frozen.collectionRowCount());
        assertEquals(2, frozen.collectionColumnCount());
        assertEquals(4, frozen.collectionRowIndex());
        assertEquals(1, frozen.collectionRowSpan());
        assertEquals(1, frozen.collectionColumnIndex());
        assertEquals(2, frozen.collectionColumnSpan());
        assertTrue(frozen.supportsScrollForward());
        assertFalse(frozen.supportsScrollBackward());
        child.withText("已变更");
        child.boundsInScreen()[0] = 300;
        frozen.boundsInScreen()[0] = 500;
        assertArrayEquals(new int[]{9, 10, 1083, 147}, frozen.boundsInScreen());
        assertEquals("  第81章 心脏（上）\n\t", frozen.text());
        assertEquals(-1, snapshot.root.collectionRowCount());
        assertTrue(snapshot.diagnostics.contains("采样问题数=0"));
    }

    @Test public void nodeLimitKeepsReportedChildCountAndExplicitlyMarksOmittedChildren() {
        FakeNode source = FakeNode.node().add(FakeNode.text("A"), FakeNode.text("B"), FakeNode.text("C"));
        DiagnosticSnapshot snapshot = DiagnosticSnapshot.capture(source, 2, 60);

        assertEquals(1, snapshot.root.childCount());
        assertEquals(3, DiagnosticSnapshot.reportedChildCount(snapshot.root));
        assertTrue(snapshot.diagnostics.contains("节点数量截断，上限=2"));
        assertTrue(TreeDump.dump(snapshot.root).contains("childCount=3"));
        assertTrue(TreeDump.dump(snapshot.root).contains("已保存子项=1"));
        assertFalse(TreeDump.dump(snapshot.root).contains("text=\"B\""));
    }

    @Test public void depthLimitAndZeroNodeBudgetNeverClaimCompleteSampling() {
        FakeNode source = FakeNode.node().add(FakeNode.node().add(FakeNode.text("未访问")));
        DiagnosticSnapshot depth = DiagnosticSnapshot.capture(source, 10, 1);
        assertTrue(depth.diagnostics.contains("树深度截断，上限=1"));
        assertTrue(TreeDump.dump(depth.root).contains("树深度截断，上限=1"));
        assertFalse(TreeDump.dump(depth.root).contains("未访问"));
        DiagnosticSnapshot empty = DiagnosticSnapshot.capture(source, 0, 1);
        assertNull(empty.root);
        assertTrue(empty.diagnostics.contains("节点数量截断，上限=0"));
    }

    @Test public void missingChildrenAndPropertyFailuresAreReportedWithoutDroppingOtherChildren() {
        NodeView source = new Forwarding(FakeNode.node()) {
            @Override public String text() { throw new IllegalStateException("不可用"); }
            @Override public int childCount() { return 2; }
            @Override public NodeView child(int index) {
                if (index == 0) throw new IllegalArgumentException("节点更新中");
                return FakeNode.text("仍读到的第二项");
            }
        };
        DiagnosticSnapshot snapshot = DiagnosticSnapshot.capture(source);

        assertEquals(2, snapshot.root.childCount());
        assertNull(snapshot.root.child(0));
        assertEquals("仍读到的第二项", snapshot.root.child(1).text());
        assertTrue(snapshot.diagnostics.contains("text 未读取：IllegalStateException"));
        assertTrue(snapshot.diagnostics.contains("child 读取异常：IllegalArgumentException"));
        assertTrue(snapshot.diagnostics.contains("子节点未读取"));
        assertTrue(TreeDump.dump(snapshot.root).contains("<子节点未读取>"));
        assertTrue(TreeDump.dump(snapshot.root).contains("未知占位"));
    }

    @Test public void nullRootIsRecordedAsMissingInsteadOfAnEmptySuccessfulTree() {
        DiagnosticSnapshot snapshot = DiagnosticSnapshot.capture(null);
        assertNull(snapshot.root);
        assertTrue(snapshot.diagnostics.contains("根节点未读取"));
        assertTrue(snapshot.diagnostics.contains("已读取节点=0"));
    }

    private static SelectorSet selectors() throws Exception {
        return new SelectorSet(SelectorSet.parse("{\"chapter_row_title\":{\"id\":\"title\"},"
                + "\"catalog_directory_list\":{\"id\":\"list_view\"}}"), "test selectors");
    }

    private static FakeNode page(String title) {
        return FakeNode.node().withId("com.sfacg:id/list_view")
                .withClass("android.widget.ListView").withBounds(0, 180, 1080, 2200)
                .add(FakeNode.node().clickable(true).withBounds(0, 200, 1080, 350)
                        .add(FakeNode.text(title).withId("com.sfacg:id/title")
                                .withClass("android.widget.TextView").withBounds(8, 208, 1060, 342)));
    }

    private static final class ChangingRoot extends Forwarding {
        int childReads;
        ChangingRoot() { super(FakeNode.node()); }
        @Override public int childCount() { return 1; }
        @Override public NodeView child(int index) {
            childReads++;
            return page(childReads == 1 ? "第1章 首次采样" : "第2章 后来画面");
        }
    }

    private static class Forwarding implements NodeView {
        final NodeView source;
        Forwarding(NodeView source) { this.source = source; }
        @Override public String text() { return source.text(); }
        @Override public String desc() { return source.desc(); }
        @Override public String viewId() { return source.viewId(); }
        @Override public String className() { return source.className(); }
        @Override public boolean clickable() { return source.clickable(); }
        @Override public boolean enabled() { return source.enabled(); }
        @Override public boolean checked() { return source.checked(); }
        @Override public boolean scrollable() { return source.scrollable(); }
        @Override public boolean visible() { return source.visible(); }
        @Override public int[] boundsInScreen() { return source.boundsInScreen(); }
        @Override public int childCount() { return source.childCount(); }
        @Override public NodeView child(int index) { return source.child(index); }
        @Override public NodeView parent() { return source.parent(); }
    }
}
