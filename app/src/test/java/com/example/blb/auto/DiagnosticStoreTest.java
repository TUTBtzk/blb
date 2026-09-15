package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DiagnosticStoreTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void runtimeArchiveContainsSharedEvidenceFullTextAndMetadata() throws Exception {
        File files = temporary.newFolder("files");
        String title = "第81章  " + String.join("", Collections.nCopies(80, "正文")) + "（末尾保留）";
        FakeNode root = page(title);
        SelectorSet selectors = selectors();
        InspectorCapture.Snapshot before = InspectorCapture.snapshot();
        String shared = InspectorCapture.evidenceContent(root, selectors);

        File saved = DiagnosticStore.save(files, "目录-首屏", 1789423750499L, root, selectors);
        String content = DiagnosticStore.read(files, saved);

        assertEquals(new File(files, "diagnostics").getCanonicalFile(), saved.getCanonicalFile().getParentFile());
        assertTrue(saved.getName().startsWith("目录-首屏-"));
        assertTrue(content.contains("运行取证阶段=目录-首屏"));
        assertTrue(content.contains("采集时间（Unix 毫秒）=1789423750499"));
        assertTrue(content.contains(shared));
        assertTrue(content.contains(title));
        assertTrue(content.contains("=== 当前选择器加载诊断 ==="));
        assertTrue(content.contains("=== 当前窗口 TreeDump 原文 ==="));
        assertTrue(content.contains("collectionRows=15"));
        assertTrue(content.contains("itemRowIndex=4"));
        assertTrue(content.contains("scrollForwardAction=true"));
        assertTrue(content.endsWith("=== 运行取证文件结束 ===\n"));
        assertSame("运行存档不能把手动探测器缓存替换成别的现场", before, InspectorCapture.snapshot());
    }

    @Test
    public void repeatedStageAndTimestampNeverOverwriteOlderEvidenceOrAppData() throws Exception {
        File files = temporary.newFolder("files");
        File accountData = new File(files, "accounts.txt");
        Files.write(accountData.toPath(), "保留账号和账本".getBytes(StandardCharsets.UTF_8));

        File first = DiagnosticStore.save(files, "核对-失败屏", 1000, page("第一屏"), selectors());
        String original = DiagnosticStore.read(files, first);
        File second = DiagnosticStore.save(files, "核对-失败屏", 1000, page("第二屏"), selectors());

        assertNotEquals(first.getName(), second.getName());
        assertEquals(original, DiagnosticStore.read(files, first));
        assertTrue(DiagnosticStore.read(files, second).contains("第二屏"));
        assertEquals("保留账号和账本", new String(Files.readAllBytes(accountData.toPath()), StandardCharsets.UTF_8));
        assertEquals(2, DiagnosticStore.recent(files, 30).totalCount);
    }

    @Test
    public void stageTextCannotEscapeThePrivateDiagnosticsDirectory() throws Exception {
        File files = temporary.newFolder("files");
        File saved = DiagnosticStore.save(files, "../../别处\\失败\n末屏", 1234, page("标题"), selectors());

        assertEquals(new File(files, "diagnostics").getCanonicalFile(), saved.getCanonicalFile().getParentFile());
        assertFalse(saved.getName().contains("/"));
        assertFalse(saved.getName().contains("\\"));
        assertFalse(saved.getName().contains("\n"));
        assertTrue(DiagnosticStore.read(files, saved).contains("失败\\n末屏"));
    }

    @Test
    public void recentListingStatesVisibleAndTotalCountsWithoutDeletingOlderFiles() throws Exception {
        File files = temporary.newFolder("files");
        List<File> saved = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            File file = DiagnosticStore.save(files, "屏-" + i, 1000 + i, page("第" + i + "屏"), selectors());
            assertTrue(file.setLastModified(1_000_000L + i * 1000L));
            saved.add(file);
        }
        File directory = new File(files, "diagnostics");
        Files.write(new File(directory, "未写完.pending").toPath(), "半份正文".getBytes(StandardCharsets.UTF_8));
        assertTrue(new File(directory, "目录不是记录.txt").mkdir());

        DiagnosticStore.Listing recent = DiagnosticStore.recent(files, 2);

        assertEquals(5, recent.totalCount);
        assertEquals(2, recent.files.size());
        assertEquals(saved.get(4), recent.files.get(0));
        assertEquals(saved.get(3), recent.files.get(1));
        assertEquals("显示最近 2 份／共 5 份", recent.summary());
        for (File file : saved) assertTrue(file.isFile());
        assertThrows(UnsupportedOperationException.class, () -> recent.files.clear());
    }

    @Test
    public void openingAnEmptyEvidenceListDoesNotCreateFiles() throws Exception {
        File files = temporary.newFolder("files");
        DiagnosticStore.Listing recent = DiagnosticStore.recent(files, DiagnosticStore.RECENT_LIMIT);

        assertEquals(0, recent.totalCount);
        assertEquals("显示最近 0 份／共 0 份", recent.summary());
        assertFalse(new File(files, "diagnostics").exists());
    }

    @Test
    public void absentRootDoesNotCreateAnArchiveOrBorrowCachedEvidence() throws Exception {
        File files = temporary.newFolder("files");
        java.io.IOException actual = assertThrows(java.io.IOException.class,
                () -> DiagnosticStore.save(files, "失败屏", 1000, null, selectors()));

        assertTrue(actual.getMessage().contains("目标窗口根节点未读取"));
        assertFalse(new File(files, "diagnostics").exists());
    }

    @Test
    public void readingEvidenceRejectsOtherDirectoriesAndUnfinishedFiles() throws Exception {
        File files = temporary.newFolder("files");
        File other = temporary.newFile("outside.txt");
        Files.write(other.toPath(), "其它内容".getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> DiagnosticStore.read(files, other));

        File directory = new File(files, "diagnostics");
        assertTrue(directory.mkdir());
        File pending = new File(directory, "现场.pending");
        Files.write(pending.toPath(), "未写完".getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> DiagnosticStore.read(files, pending));
    }

    @Test
    public void frozenExportKeepsChosenArchiveWhenANewerSnapshotArrives() throws Exception {
        File files = temporary.newFolder("files");
        File cache = temporary.newFolder("cache");
        File selected = DiagnosticStore.save(files, "选中的首屏", 1000, page("原文\n  首屏"), selectors());
        String chosen = DiagnosticStore.read(files, selected);
        File frozen = DiagnosticStore.freezeExport(cache, chosen);

        DiagnosticStore.save(files, "后来到达的末屏", 2000, page("不能替换导出内容"), selectors());

        assertEquals(chosen, DiagnosticStore.readFrozenExport(cache, frozen));
        assertFalse(DiagnosticStore.readFrozenExport(cache, frozen).contains("不能替换导出内容"));
        assertEquals("导出缓存不能混进运行取证列表", 2, DiagnosticStore.recent(files, 30).totalCount);
    }

    @Test
    public void frozenExportCanBeRestoredFromOnlyItsPathAfterActivityRecreation() throws Exception {
        File cache = temporary.newFolder("cache");
        String chosen = "保存时正文\r\n  保留空格与全角（括号）\n";
        String path = DiagnosticStore.freezeExport(cache, chosen).getAbsolutePath();

        assertEquals(chosen, DiagnosticStore.readFrozenExport(cache, new File(path)));
        File other = temporary.newFile("blb-inspector-export-other.txt");
        assertThrows(java.io.IOException.class, () -> DiagnosticStore.readFrozenExport(cache, other));
    }

    private static SelectorSet selectors() throws Exception {
        return new SelectorSet(SelectorSet.parse("{\"chapter_row_title\":{\"id\":\"title\"},"
                + "\"catalog_directory_list\":{\"id\":\"list_view\"}}"), "test selectors");
    }

    private static FakeNode page(String title) {
        FakeNode row = FakeNode.node().withClass("android.widget.RelativeLayout")
                .withBounds(0, 200, 1080, 350).clickable(true).item(4)
                .add(FakeNode.text(title).withId("com.sfacg:id/title").withBounds(8, 208, 1060, 342));
        FakeNode list = FakeNode.node().withId("com.sfacg:id/list_view").withClass("android.widget.ListView")
                .withBounds(0, 180, 1080, 2200).collection(15).scrollable(true).scrollActions(true, true).add(row);
        return FakeNode.node().withBounds(0, 0, 1080, 2400).add(
                FakeNode.text("目录列表").withId("com.sfacg:id/edit_title").withBounds(400, 90, 680, 160), list);
    }
}
