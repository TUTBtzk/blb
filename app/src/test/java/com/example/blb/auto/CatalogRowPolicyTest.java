package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.blb.util.Texts;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** 2026-09-14 无标号行和假到底的纯判据；不把人造节点当成真机取证。 */
public class CatalogRowPolicyTest {
    @Test public void printedChapterNumbersRemainChapters() {
        assertEquals(CatalogScanner.RowKind.CHAPTER, CatalogScanner.classifyRow(23, false));
        assertEquals(CatalogScanner.RowKind.CHAPTER, CatalogScanner.classifyRow(23, true));
    }

    @Test public void sectionNamesAndHeadingFlagsCannotReplaceAppSpecificEvidence() {
        for (String title : Arrays.asList("【铃兰花】", "【番外】", "番外", "世界线的变动，学生会长的恋爱")) {
            assertEquals(title, CatalogScanner.RowKind.UNKNOWN,
                    CatalogScanner.classifyRow(Texts.rowChapterNo(title), true));
        }
    }

    @Test public void realUnnumberedTitlesNeedEvidenceRatherThanBeingSilentlySkipped() {
        for (String title : Arrays.asList("番外 藏在地下室的恶鬼（上）", "藏在地下室的恶鬼(上)",
                "藏在地下室的恶鬼 (下)", "迷信的可怖后果", "完结感言，以及反思")) {
            assertEquals(-1, Texts.rowChapterNo(title));
            assertEquals(title, CatalogScanner.RowKind.UNKNOWN,
                    CatalogScanner.classifyRow(Texts.rowChapterNo(title), false));
            assertEquals(title, CatalogScanner.RowKind.UNKNOWN,
                    CatalogScanner.classifyRow(Texts.rowChapterNo(title), true));
        }
    }

    @Test public void unresolvedRowsBlockPublishingEvenIfNumberedRowsAreContinuous() {
        CatalogScanner.Result result = new CatalogScanner.Result();
        result.chapters.add(row("第1章 正文"));
        result.unresolved.add("藏在地下室的恶鬼(上)");
        assertFalse(result.trustworthy());
    }

    @Test public void unnumberedRowsDoNotInventPrintedNumberGaps() {
        assertNull(CatalogScanner.findGap(Arrays.asList(row("第23章 正文"),
                row("藏在地下室的恶鬼(上)"), row("第99章 后续"))));
        assertNull(CatalogScanner.findGap(Arrays.asList(row("藏在地下室的恶鬼(上)"),
                row("藏在地下室的恶鬼 (下)"), row("迷信的可怖后果"))));
    }

    @Test public void adjacentNumberedRowsStillExposeGaps() {
        assertNotNull(CatalogScanner.findGap(Arrays.asList(row("藏在地下室的恶鬼(上)"),
                row("99 后续"), row("102 缺页"))));
    }

    @Test public void twoUnchangedScreensAndABlockedContainerAreBothRequired() {
        assertTrue(CatalogScanner.confirmedEnd(2, true, StepRunner.CatalogScroll.BLOCKED));
        assertFalse(CatalogScanner.confirmedEnd(1, true, StepRunner.CatalogScroll.BLOCKED));
        assertFalse(CatalogScanner.confirmedEnd(2, false, StepRunner.CatalogScroll.BLOCKED));
        assertFalse(CatalogScanner.confirmedEnd(2, true, StepRunner.CatalogScroll.ACCEPTED));
        assertFalse(CatalogScanner.confirmedEnd(2, true, StepRunner.CatalogScroll.UNAVAILABLE));
        assertFalse(CatalogScanner.confirmedEnd(2, true, null));
    }

    @Test public void capturedDirectoryShapeRecognizesExtrasAndAfterwordWithoutTitleKeywords() {
        // 字段取自 2026-09-14 用户普通目录 dump；不把选择页的同形勾选框当章节证据。
        for (String title : Arrays.asList("藏在地下室的恶鬼（上）", "藏在地下室的恶鬼（下）",
                "迷信的可怖后果", "完结感言，以及反思", "任意后续标题")) {
            assertEquals(title, CatalogScanner.RowKind.CHAPTER,
                    CatalogScanner.classifyDirectoryRow(Texts.rowChapterNo(title), facts(null, false)));
        }
    }

    @Test public void layoutRootEvidenceWinsOverWordsAndEvenPrintedChapterNumbers() {
        for (String title : Arrays.asList("番外", "【铃兰花】", "世界线的变动，学生会长的恋爱",
                "第23章 写成章节形式的卷名")) {
            assertEquals(title, CatalogScanner.RowKind.SECTION,
                    CatalogScanner.classifyDirectoryRow(Texts.rowChapterNo(title), facts("layoutRoot", false)));
        }
        assertEquals(CatalogScanner.RowKind.SECTION,
                CatalogScanner.classifyDirectoryRow(23, facts("com.sfacg:id/layoutRoot", true)));
    }

    @Test public void onlyTheMeasuredDirectoryShapeCanClassifyUnnumberedRows() {
        List<CatalogScanner.DirectoryRowEvidence> unknown = Arrays.asList(null,
                evidence("downloadRecycler", "ListView", null, "RelativeLayout", true, true, 1, true, "title", "TextView", false),
                evidence("list_view", "RecyclerView", null, "RelativeLayout", true, true, 1, true, "title", "TextView", false),
                evidence("list_view", "ListView", null, "LinearLayout", true, true, 1, true, "title", "TextView", false),
                evidence("list_view", "ListView", null, "RelativeLayout", false, true, 1, true, "title", "TextView", false),
                evidence("list_view", "ListView", null, "RelativeLayout", true, false, 1, true, "title", "TextView", false),
                evidence("list_view", "ListView", null, "RelativeLayout", true, true, 2, true, "title", "TextView", false),
                evidence("list_view", "ListView", null, "RelativeLayout", true, true, 1, false, "title", "TextView", false),
                evidence("list_view", "ListView", null, "RelativeLayout", true, true, 1, true, "other", "TextView", false),
                evidence("list_view", "ListView", null, "RelativeLayout", true, true, 1, true, "title", "View", false),
                facts("unrecognized_row", false), facts(null, true));
        for (CatalogScanner.DirectoryRowEvidence evidence : unknown) {
            assertEquals(CatalogScanner.RowKind.UNKNOWN,
                    CatalogScanner.classifyDirectoryRow(-1, evidence));
        }
        assertEquals("已有编号的章仍兼容 heading，但未知容器形状不能靠编号冒充普通目录",
                CatalogScanner.RowKind.CHAPTER, CatalogScanner.classifyDirectoryRow(23, facts(null, true)));
        assertEquals(CatalogScanner.RowKind.UNKNOWN,
                CatalogScanner.classifyDirectoryRow(23, facts("unrecognized_row", false)));
    }

    @Test public void pairedTailKeepsAfterwordAndAllExtrasInOneChapterSequence() {
        CatalogScanner.Result directory = tailDirectory();
        CatalogScanner.Result result = pair(directory, tailPicker());

        assertTrue(directory.gapNote, directory.trustworthy());
        assertTrue(directory.directoryScanned);
        assertEquals("普通目录没有付款状态，不能因此把全部章节判免费", 0, directory.freeCount());
        assertEquals(0, directory.buyableCount());
        assertTrue(result.gapNote, result.trustworthy());
        assertFalse("使用过目录证据的 picker 仍不是目录扫描", result.directoryScanned);
        assertEquals(6, result.allRows.size());
        assertEquals(Arrays.asList("番外"), result.skipped);
        assertEquals(Arrays.asList("第23章 拥有烟火和月季花香的八百个梦境", "完结感言，以及反思",
                "藏在地下室的恶鬼（上）", "藏在地下室的恶鬼（下）", "迷信的可怖后果"), titles(result));
        for (int i = 2; i < 5; i++) {
            assertEquals(-1, result.chapters.get(i).printedNo);
            assertEquals("番外", result.chapters.get(i).volumeTitle);
        }
        assertEquals(CatalogScanner.RowKind.SECTION, result.allRows.get(2).kind);
        assertEquals(3, result.freeCount());
        assertEquals(2, result.buyableCount());
    }

    @Test public void pickerShapesAloneStillCannotDistinguishTheSectionAndFreeExtra() {
        CatalogScanner.Result result = CatalogScanner.resolveRows(tailPicker(), false, false,
                false, null, null);
        assertFalse(result.trustworthy());
        assertEquals(6, result.allRows.size());
        assertTrue(result.skipped.isEmpty());
        assertTrue(result.unresolved.contains("番外"));
        assertTrue(result.unresolved.contains("藏在地下室的恶鬼（上）"));
    }

    @Test public void aSingleChapterSectionIsNotAChapterEvenIfItsSelectionWouldBeOne() {
        // 卷只剩一章时点卷也会选一章，所以判据根本不接收 selectedCount。
        CatalogScanner.Result directory = directory(Arrays.asList(
                directoryRow("番外", "layoutRoot"), directoryRow("藏在地下室的恶鬼（上）", null)));
        CatalogScanner.Result result = pair(directory, Arrays.asList(
                pickerRow("番外", false), pickerRow("藏在地下室的恶鬼（上）", false)));
        assertTrue(result.gapNote, result.trustworthy());
        assertEquals(Arrays.asList("番外"), result.skipped);
        assertEquals(Arrays.asList("藏在地下室的恶鬼（上）"), titles(result));
    }

    @Test public void theTwoListsMustMatchEveryFullTitleAndPositionWithoutNormalization() {
        CatalogScanner.Result directory = tailDirectory();
        for (String wrong : Arrays.asList("番外 藏在地下室的恶鬼（上）", "藏在地下室的恶鬼(上)",
                "藏在地下室的恶鬼 （上）", "作者已经改名")) {
            List<CatalogScanner.Row> picker = tailPicker();
            picker.set(3, pickerRow(wrong, false));
            CatalogScanner.Result result = pair(directory, picker);
            assertFalse(wrong, result.trustworthy());
            assertTrue(result.gapNote, result.gapNote.contains("第 4 行不同"));
        }
        List<CatalogScanner.Row> reordered = tailPicker();
        CatalogScanner.Row upper = reordered.set(3, reordered.get(4));
        reordered.set(4, upper);
        assertFalse(pair(directory, reordered).trustworthy());
    }

    @Test public void anOmittedSectionOrAnAddedChapterCannotBeHiddenByChapterOnlyComparison() {
        List<CatalogScanner.Row> omittedSection = tailPicker();
        omittedSection.remove(2);
        CatalogScanner.Result missing = pair(tailDirectory(), omittedSection);
        assertFalse(missing.trustworthy());
        assertTrue(missing.gapNote, missing.gapNote.contains("行数不同"));
        List<CatalogScanner.Row> appended = tailPicker();
        appended.add(pickerRow("新番外", true));
        assertFalse(pair(tailDirectory(), appended).trustworthy());
    }

    @Test public void anIncompleteOrUnknownDirectoryCannotAuthorizePickerRoles() {
        CatalogScanner.Result truncated = CatalogScanner.resolveRows(tailDirectoryRows(),
                true, false, true, null, null);
        CatalogScanner.Result withGap = CatalogScanner.resolveRows(tailDirectoryRows(),
                true, false, false, "目录翻页缺口", null);
        List<CatalogScanner.Row> unknownRows = tailDirectoryRows();
        unknownRows.set(2, directoryRow("番外", "unrecognized_row"));
        CatalogScanner.Result unknown = directory(unknownRows);
        for (CatalogScanner.Result directory : Arrays.asList(truncated, withGap, unknown)) {
            CatalogScanner.Result result = pair(directory, tailPicker());
            assertFalse(result.trustworthy());
            assertTrue(result.gapNote, result.gapNote.contains("普通目录未读完整"));
            assertTrue(result.unresolved.contains("藏在地下室的恶鬼（上）"));
        }
    }

    @Test public void aPickerResultCannotImpersonateADirectoryEvenAfterSuccessfulPairing() {
        CatalogScanner.Result picker = pair(tailDirectory(), tailPicker());
        assertTrue(picker.trustworthy());
        CatalogScanner.Result rejected = pair(picker, tailPicker());
        assertFalse(rejected.trustworthy());
        assertTrue(rejected.gapNote, rejected.gapNote.contains("不能把选择章节页当作普通目录"));
        assertFalse(pair(null, tailPicker()).trustworthy());
    }

    @Test public void anUnfinishedPickerNeverReceivesDirectoryRoles() {
        CatalogScanner.Result result = CatalogScanner.resolveRows(tailPicker(), false, true,
                true, null, tailDirectory());
        assertFalse(result.trustworthy());
        assertTrue(result.unresolved.contains("番外"));
        assertTrue(result.unresolved.contains("藏在地下室的恶鬼（上）"));
    }

    @Test public void unknownRowsClearTheVolumeInsteadOfBorrowingThePreviousSection() {
        CatalogScanner.Result result = directory(Arrays.asList(directoryRow("番外", "layoutRoot"),
                directoryRow("恶鬼上", null), directoryRow("未知分节", "unrecognized_row"),
                directoryRow("下一章", null)));
        assertFalse(result.trustworthy());
        assertEquals("番外", result.chapters.get(0).volumeTitle);
        assertNull(result.chapters.get(1).volumeTitle);
        assertEquals(Arrays.asList("未知分节"), result.unresolved);
    }

    @Test public void aChapterAndSectionWithTheSameFullTitleCannotReachExactTextLookup() {
        CatalogScanner.Result result = directory(Arrays.asList(
                directoryRow("第23章 同名", "layoutRoot"), directoryRow("第23章 同名", null)));
        assertFalse(result.trustworthy());
        assertTrue(result.gapNote, result.gapNote.contains("不同角色"));
        assertEquals(2, result.allRows.size());
        assertEquals(1, result.chapters.size());
        assertEquals(1, result.skipped.size());
    }

    @Test public void repeatedUnnumberedChaptersAreRetainedButBlockAmbiguousCrossVolumeLookup() {
        CatalogScanner.Result result = directory(Arrays.asList(directoryRow("卷一", "layoutRoot"),
                directoryRow("藏在地下室的恶鬼（上）", null), directoryRow("番外", "layoutRoot"),
                directoryRow("藏在地下室的恶鬼（上）", null)));
        assertFalse(result.trustworthy());
        assertTrue(result.gapNote, result.gapNote.contains("多个位置"));
        assertEquals(4, result.allRows.size());
        assertEquals(2, result.chapters.size());
    }

    @Test public void appendingAnExtraPreservesEveryExistingChapterPosition() {
        CatalogScanner.Result before = pair(tailDirectory(), tailPicker());
        List<CatalogScanner.Row> directoryRows = tailDirectoryRows();
        directoryRows.add(directoryRow("后来新增的番外", null));
        List<CatalogScanner.Row> pickerRows = tailPicker();
        pickerRows.add(pickerRow("后来新增的番外", true));
        CatalogScanner.Result after = pair(directory(directoryRows), pickerRows);
        assertTrue(after.gapNote, after.trustworthy());
        assertEquals(before.chapters.size() + 1, after.chapters.size());
        for (int i = 0; i < before.chapters.size(); i++) {
            assertEquals(before.chapters.get(i).title, after.chapters.get(i).title);
            assertEquals(before.chapters.get(i).volumeTitle, after.chapters.get(i).volumeTitle);
        }
        assertEquals("番外", after.chapters.get(after.chapters.size() - 1).volumeTitle);
    }

    private static CatalogScanner.DirectoryRowEvidence facts(String rowId, boolean heading) {
        return evidence("list_view", "android.widget.ListView", rowId, "android.widget.RelativeLayout",
                true, true, 1, true, "title", "android.widget.TextView", heading);
    }

    private static CatalogScanner.DirectoryRowEvidence evidence(String listId, String listClass,
            String rowId, String rowClass, boolean direct, boolean clickable, int children,
            boolean directTitle, String titleId, String titleClass, boolean heading) {
        return new CatalogScanner.DirectoryRowEvidence(listId, listClass, rowId, rowClass,
                direct, clickable, children, directTitle, titleId, titleClass, heading);
    }

    private static CatalogScanner.Row directoryRow(String title, String rowId) {
        return CatalogScanner.observedRow(title, null, true, true, facts(rowId, false));
    }

    private static CatalogScanner.Row pickerRow(String title, boolean lock) {
        return CatalogScanner.observedRow(title, ChapterRowState.of(lock, false, true), true, true, null);
    }

    private static CatalogScanner.Result directory(List<CatalogScanner.Row> rows) {
        return CatalogScanner.resolveRows(rows, true, false, false, null, null);
    }

    private static CatalogScanner.Result pair(CatalogScanner.Result directory, List<CatalogScanner.Row> picker) {
        return CatalogScanner.resolveRows(picker, false, true, false, null, directory);
    }

    private static List<CatalogScanner.Row> tailDirectoryRows() {
        return new ArrayList<>(Arrays.asList(
                directoryRow("第23章 拥有烟火和月季花香的八百个梦境", null),
                directoryRow("完结感言，以及反思", null), directoryRow("番外", "layoutRoot"),
                directoryRow("藏在地下室的恶鬼（上）", null), directoryRow("藏在地下室的恶鬼（下）", null),
                directoryRow("迷信的可怖后果", null)));
    }

    private static CatalogScanner.Result tailDirectory() { return directory(tailDirectoryRows()); }

    private static List<CatalogScanner.Row> tailPicker() {
        return new ArrayList<>(Arrays.asList(
                pickerRow("第23章 拥有烟火和月季花香的八百个梦境", true),
                pickerRow("完结感言，以及反思", false), pickerRow("番外", false),
                pickerRow("藏在地下室的恶鬼（上）", false), pickerRow("藏在地下室的恶鬼（下）", false),
                pickerRow("迷信的可怖后果", true)));
    }

    private static List<String> titles(CatalogScanner.Result result) {
        return result.chapters.stream().map(row -> row.title).collect(Collectors.toList());
    }

    private static CatalogScanner.Row row(String title) {
        return new CatalogScanner.Row(title, Texts.rowChapterNo(title), true, false, true);
    }
}
