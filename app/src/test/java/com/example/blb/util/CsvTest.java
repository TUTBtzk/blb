package com.example.blb.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** CSV 往返：导出的账本必须能原样导回来，否则手动补录的记录会丢。 */
public class CsvTest {

    @Test
    public void plainRowRoundTrips() {
        String text = Csv.row("小说", "1", "第一章", "user@x.com", "20", "MANUAL", "1700000000000");
        List<List<String>> rows = Csv.parse(text);
        assertEquals(1, rows.size());
        assertEquals(Arrays.asList("小说", "1", "第一章", "user@x.com", "20", "MANUAL", "1700000000000"),
                rows.get(0));
    }

    @Test
    public void commasQuotesAndNewlinesSurviveTheRoundTrip() {
        String title = "书名, 带逗号";
        String chapter = "他说\"你好\"";
        String note = "第一行\n第二行";
        List<List<String>> rows = Csv.parse(Csv.row(title, chapter, note));
        assertEquals(1, rows.size());
        assertEquals(Arrays.asList(title, chapter, note), rows.get(0));
    }

    @Test
    public void multipleRowsAndCrlfAndBlankLines() {
        String text = "a,b\r\n\r\nc,d\n   \n";
        List<List<String>> rows = Csv.parse(text);
        assertEquals(2, rows.size());
        assertEquals(Arrays.asList("a", "b"), rows.get(0));
        assertEquals(Arrays.asList("c", "d"), rows.get(1));
    }

    @Test
    public void lastLineWithoutTrailingNewlineIsKept() {
        List<List<String>> rows = Csv.parse("a,b\nc,d");
        assertEquals(2, rows.size());
        assertEquals(Arrays.asList("c", "d"), rows.get(1));
    }

    @Test
    public void emptyInputYieldsNoRows() {
        assertTrue(Csv.parse(null).isEmpty());
        assertTrue(Csv.parse("").isEmpty());
        assertTrue(Csv.parse("\n\n").isEmpty());
    }

    @Test
    public void atAndIntAtAreForgivingAboutMissingColumns() {
        List<String> row = Arrays.asList(" x ", "", "12 券", null);
        assertEquals("x", Csv.at(row, 0));
        assertEquals("", Csv.at(row, 1));
        assertEquals("", Csv.at(row, 3));
        assertEquals("", Csv.at(row, 99));
        assertEquals("", Csv.at(null, 0));
        assertEquals(12, Csv.intAt(row, 2));
        assertEquals(-1, Csv.intAt(row, 1));
        assertEquals(-1, Csv.intAt(row, 99));
    }

    @Test
    public void emptyCellsInTheMiddleAreNotCollapsed() {
        List<List<String>> rows = Csv.parse("a,,c\n");
        assertEquals(Arrays.asList("a", "", "c"), rows.get(0));
    }
}
