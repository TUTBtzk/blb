package com.example.blb.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

public class PurchaseCsvTest {

    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");

    @Test
    public void exportedPurchaseKeepsItsOriginalInstantCostsAndSourceOnImport() {
        PurchaseRow original = new PurchaseRow();
        original.novelTitle = "书名,带逗号";
        original.chapterNo = 48;
        original.chapterTitle = "他说\"你好\"";
        original.accountLoginName = "reader@example.com";
        original.costCoupons = 3;
        original.costVouchers = 17;
        original.source = Purchase.SRC_REMOTE_DETAIL;
        original.purchasedAt = 1_692_876_543_789L;

        List<List<String>> file = Csv.parse(PurchaseCsv.HEADER + "\n" + PurchaseCsv.row(original));
        List<String> row = file.get(1);
        Purchase restored = PurchaseCsv.parsePurchase(row, TimeZone.getTimeZone("America/New_York"));

        assertNotNull(restored);
        assertEquals(original.novelTitle, Csv.at(row, 0));
        assertEquals(original.chapterNo, Csv.intAt(row, 1));
        assertEquals(original.chapterTitle, Csv.at(row, 2));
        assertEquals(original.accountLoginName, Csv.at(row, 3));
        assertEquals(original.purchasedAt, restored.purchasedAt);
        assertEquals(original.costCoupons, restored.costCoupons);
        assertEquals(original.costVouchers, restored.costVouchers);
        assertEquals(original.source, restored.source);
    }

    @Test
    public void legacyDateWithExplicitAmountsKeepsItsLocalPurchaseDay() {
        Purchase restored = PurchaseCsv.parsePurchase(Arrays.asList(
                "小说", "48", "旧章", "reader", "20", "MANUAL", "2026-08-24", "0"), SHANGHAI);

        assertNotNull(restored);
        assertEquals(localTime(2026, Calendar.AUGUST, 24, 0, 0, 0, 0), restored.purchasedAt);
        assertEquals(20, restored.costCoupons);
        assertEquals(0, restored.costVouchers);
    }

    @Test
    public void legacySevenColumnFileDoesNotInventAMissingVoucherAmount() {
        assertNull(PurchaseCsv.parsePurchase(Arrays.asList(
                "小说", "48", "旧章", "reader", "20", "MANUAL", "2026-08-24"), SHANGHAI));
    }

    @Test
    public void missingNegativeFractionalOrOverflowingAmountsAreRejected() {
        for (String invalid : Arrays.asList("", " ", "-1", "12.5", "20代券", "未知", "2147483648")) {
            assertNull(invalid, PurchaseCsv.parsePurchase(Arrays.asList(
                    "小说", "48", "旧章", "reader", invalid, "AUTO", "2026-08-24", "20"), SHANGHAI));
            assertNull(invalid, PurchaseCsv.parsePurchase(Arrays.asList(
                    "小说", "48", "旧章", "reader", "0", "AUTO", "2026-08-24", invalid), SHANGHAI));
        }
    }

    @Test
    public void legacyMillisecondTimestampIsNotReplacedWithTheImportTime() {
        Purchase restored = PurchaseCsv.parsePurchase(row("1700000000123"), SHANGHAI);
        assertNotNull(restored);
        assertEquals(1_700_000_000_123L, restored.purchasedAt);
    }

    @Test
    public void timezoneOffsetInNewFilesSurvivesImportInAnotherTimezone() {
        Purchase restored = PurchaseCsv.parsePurchase(
                row("2026-08-24T20:15:40.789+08:00"), TimeZone.getTimeZone("UTC"));
        assertNotNull(restored);
        assertEquals(localTime(2026, Calendar.AUGUST, 24, 20, 15, 40, 789), restored.purchasedAt);
    }

    @Test
    public void invalidOrMissingDatesAreRejectedWithoutCreatingAnEntryForToday() {
        for (String invalid : Arrays.asList("", "   ", "2026-02-29", "2026-02-31",
                "2026-13-01", "2026-08-24garbage", "2026-08-24T24:00:00.000Z",
                "2026-08-24T12:00:00.000+25:00", "yesterday", "-1",
                "1700000000123.5", "999999999999999999999999", "253402300800000")) {
            assertNull(invalid, PurchaseCsv.parsePurchase(row(invalid), SHANGHAI));
        }
        assertNull(PurchaseCsv.parsePurchase(Arrays.asList("小说", "48", "章", "reader",
                "20", "MANUAL"), SHANGHAI));
        assertNotNull(PurchaseCsv.parsePurchase(row("2024-02-29"), SHANGHAI));
    }

    private static List<String> row(String date) {
        return Arrays.asList("小说", "48", "章", "reader", "0", "AUTO", date, "20");
    }

    private static long localTime(int year, int month, int day, int hour, int minute,
                                  int second, int millis) {
        Calendar time = Calendar.getInstance(SHANGHAI);
        time.clear();
        time.set(year, month, day, hour, minute, second);
        time.set(Calendar.MILLISECOND, millis);
        return time.getTimeInMillis();
    }
}
