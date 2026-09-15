package com.example.blb.auto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.example.blb.data.Chapter;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.junit.Test;

/** 日志必须说明账本现算的进度；跨号漏章不能被“本轮买了几章”掩盖。 */
public class SubscribeRunNextChapterTest {
    @Test
    public void earlierGapIsReportedEvenAfterALaterChapterWasBought() {
        assertTrue(SubscribeRun.nextChapterRegressed(87, 83));
        assertFalse(SubscribeRun.nextChapterRegressed(83, 84));
        assertFalse(SubscribeRun.nextChapterRegressed(83, -1));
        assertFalse(SubscribeRun.nextChapterRegressed(-1, 83));
    }

    @Test
    public void chapterPositionAndFullTitleAreVisibleWithoutOpeningAnotherPage() {
        Chapter next = new Chapter();
        next.chapterNo = 99;
        next.title = "第6章 薯片";
        assertEquals("下一章：第 99 章「第6章 薯片」", SubscribeRun.nextChapterNote(next, 48));
    }

    @Test
    public void endOfKnownCatalogDoesNotClaimThatTheAuthorHasNoMoreChapters() {
        assertEquals("下一章：当前目录中第 48 章起已全部有归属",
                SubscribeRun.nextChapterNote(null, 48));
    }

    @Test
    public void missingTitleIsShownAsUnknown() {
        Chapter next = new Chapter();
        next.chapterNo = 83;
        assertEquals("下一章：第 83 章（标题未登记）", SubscribeRun.nextChapterNote(next, 1));
    }

    @Test
    public void paymentCanStartOnlyWhileTheFreshUnownedQueryReturnsTheSameChapter() throws Exception {
        SubscribeRun.requireStillUnowned(candidate(), candidate());
        List<Consumer<Chapter>> changes = Arrays.asList(row -> row.id++, row -> row.novelId++,
                row -> row.chapterNo++, row -> row.title += "改", row -> row.volumeTitle += "改");
        for (Consumer<Chapter> change : changes) {
            Chapter current = candidate();
            change.accept(current);
            stops(candidate(), current);
        }
        stops(candidate(), null); // 已被任意账号拥有后，数据库不再返回这个候选。
        stops(null, candidate());
        Chapter unknown = candidate();
        unknown.title = null;
        stops(unknown, unknown);
    }

    private static void stops(Chapter expected, Chapter current) {
        try {
            SubscribeRun.requireStillUnowned(expected, current);
            fail("买前现查不再证明同一章无人拥有时必须停止");
        } catch (StepRunner.StepFailure failure) {
            assertEquals(StepRunner.Kind.MONEY_UNCLEAR, failure.kind);
            assertTrue(failure.getMessage().contains("未执行购买"));
        }
    }

    private static Chapter candidate() {
        Chapter chapter = new Chapter();
        chapter.id = 6120;
        chapter.novelId = 1;
        chapter.chapterNo = 612;
        chapter.title = "番外 藏在地下室的恶鬼（上）";
        chapter.volumeTitle = "番外";
        return chapter;
    }
}
