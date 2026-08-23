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
}
