package com.example.blb.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.regex.Pattern;

/**
 * 中文章节标号。菠萝包目录里写的是「第十一章 久违的笑」，库里存的是 chapterNo=11，
 * 转错了就会定位到别的章 —— 而定位错的下一步是花券买错章，所以边界钉细一点。
 */
public class TextsChapterLabelTest {

    @Test
    public void smallNumbersReadLikeChinese() {
        assertEquals("一", Texts.cnNumber(1));
        assertEquals("九", Texts.cnNumber(9));
        assertEquals("十", Texts.cnNumber(10));
        // 十一，不是一十一
        assertEquals("十一", Texts.cnNumber(11));
        assertEquals("十九", Texts.cnNumber(19));
        assertEquals("二十", Texts.cnNumber(20));
        assertEquals("二十一", Texts.cnNumber(21));
        assertEquals("九十九", Texts.cnNumber(99));
    }

    @Test
    public void hundredsAndThousandsInsertZeroWhereChineseDoes() {
        assertEquals("一百", Texts.cnNumber(100));
        assertEquals("一百零一", Texts.cnNumber(101));
        assertEquals("一百一十", Texts.cnNumber(110));
        assertEquals("一百一十一", Texts.cnNumber(111));
        assertEquals("二百零五", Texts.cnNumber(205));
        assertEquals("一千", Texts.cnNumber(1000));
        assertEquals("一千零一", Texts.cnNumber(1001));
        assertEquals("一千零一十", Texts.cnNumber(1010));
        assertEquals("一千一百", Texts.cnNumber(1100));
        assertEquals("一千二百三十四", Texts.cnNumber(1234));
        assertEquals("九千九百九十九", Texts.cnNumber(9999));
    }

    /** 超出范围就退回阿拉伯数字，宁可匹配不上也不要拼出一个假的标号。 */
    @Test
    public void outOfRangeFallsBackToDigits() {
        assertEquals("零", Texts.cnNumber(0));
        assertEquals("10000", Texts.cnNumber(10000));
        assertEquals("-3", Texts.cnNumber(-3));
    }

    @Test
    public void labelWrapsTheNumber() {
        assertEquals("第十一章", Texts.cnChapterLabel(11));
        assertEquals("第一百零一章", Texts.cnChapterLabel(101));
    }

    @Test
    public void regexMatchesBothWritingsOnARealCatalogRow() {
        Pattern p = Pattern.compile(Texts.chapterLabelRegex(11));
        assertTrue(p.matcher("第十一章 久违的笑").find());
        assertTrue(p.matcher("第11章 久违的笑").find());
        assertTrue(p.matcher("第 11 章").find());
        assertTrue(p.matcher("第011章").find());
    }

    /** 「第12章」不能撞上「第120章」，中文写法也不能撞上更长的标号。 */
    @Test
    public void regexDoesNotMatchLongerChapterNumbers() {
        Pattern twelve = Pattern.compile(Texts.chapterLabelRegex(12));
        assertFalse(twelve.matcher("第120章 结局").find());
        assertTrue(twelve.matcher("第12章").find());

        Pattern one = Pattern.compile(Texts.chapterLabelRegex(1));
        assertFalse(one.matcher("第十一章 久违的笑").find());
        assertFalse(one.matcher("第一百章").find());
        assertTrue(one.matcher("第一章 开始").find());
    }

    /** 标号只锚在开头，前面挂了卷名的行不认 —— 那种行要靠标题匹配。 */
    @Test
    public void regexOnlyAnchorsAtTheStart() {
        Pattern p = Pattern.compile(Texts.chapterLabelRegex(3));
        assertFalse(p.matcher("番外卷 第三章").find());
        assertTrue(p.matcher("第三章 番外").find());
    }

    @Test
    public void oldAndOrdinalCatalogRowsShareTheirNumberAndTitle() {
        for (String row : new String[]{"83 最后", "83最后", "第83章 最后",
                "第83章最后", "第 83 章 最后", " \t第0083章　最后 "}) {
            assertEquals(row, 83, Texts.rowChapterNo(row));
            assertEquals(row, "最后", Texts.chapterTitle(row));
        }
    }

    @Test
    public void aMissingChapterCharacterRequiresWhitespaceAndANonemptyTitle() {
        for (String row : new String[]{"第68 投影", "第 68 投影", "第68　投影"}) {
            assertEquals(row, 68, Texts.rowChapterNo(row));
            assertEquals(row, "投影", Texts.chapterTitle(row));
        }
        for (String row : new String[]{"第68", "第68   ", "第68投影"}) {
            assertEquals(row, -1, Texts.rowChapterNo(row));
        }
    }

    @Test
    public void theRealZhangTypoStillNamesASingleChapter() {
        assertEquals(30, Texts.rowChapterNo("第30张 红温"));
        assertEquals("红温", Texts.chapterTitle("第30张 红温"));
        assertEquals(30, Texts.rowChapterNo("第 30 张红温"));
    }

    @Test
    public void volumeHeadingsAndNumbersInTheMiddleAreNotChapterPrefixes() {
        for (String row : new String[]{"铃兰花", "第68卷", "第68 卷", "第 68卷", "第 68 卷",
                "第68部 正文", "第68 部 正文", "第68册", "第68 册", "第68 集",
                "第68 篇", "第68 季", "铃兰花 第83章 最后", "谈到第83章",
                "上架感言", "一卷总结", "第七章 回家"}) {
            assertEquals(row, -1, Texts.rowChapterNo(row));
            assertEquals(row, row, Texts.chapterTitle(row));
        }
    }

    @Test
    public void anOverlongNumberCannotBePartlyConsumedAsAPrefix() {
        for (String row : new String[]{"123456 最后", "第123456章 最后", "第123456 最后"}) {
            assertEquals(row, -1, Texts.rowChapterNo(row));
            assertEquals(row, row, Texts.chapterTitle(row));
        }
    }

    @Test
    public void strippingOnePrefixKeepsNumbersAndChapterReferencesInsideTheTitle() {
        assertEquals("100天后", Texts.chapterTitle("第83章100天后"));
        assertEquals("100天后", Texts.chapterTitle("83 100天后"));
        assertEquals("第2章的秘密", Texts.chapterTitle("第83章 第2章的秘密"));
        assertEquals("第2章的秘密", Texts.chapterTitle("第68 第2章的秘密"));
        assertEquals("第2张合影", Texts.chapterTitle("第30张 第2张合影"));
        assertEquals("最后 2026", Texts.chapterTitle("第83章 最后 2026"));
    }

    @Test
    public void chapterTitlesOnlyNormalizeWhitespaceAfterThePrefix() {
        assertEquals("最后 的 约定", Texts.chapterTitle("第83章  最后\t的　约定"));
        assertEquals("回顾 第83章 最后", Texts.chapterTitle("回顾 第83章 最后"));
        assertEquals("", Texts.chapterTitle(null));
        assertEquals(-1, Texts.rowChapterNo(null));
    }
}
