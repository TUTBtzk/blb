package com.example.blb.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class LedgerAuditPayloadTest {
    private static Purchase original() {
        Purchase row = new Purchase();
        row.id = 9876543210123L;
        row.accountId = 2;
        row.chapterId = 312;
        row.costCoupons = 0;
        row.costVouchers = 27;
        row.purchasedAt = 1_700_000_000_123L;
        row.source = Purchase.SRC_REMOTE_DETAIL;
        return row;
    }

    @Test
    public void theWholePurchaseRoundTripsWithoutCredentials() throws Exception {
        Purchase original = original();
        String detail = LedgerAuditPayload.encode("原样恢复\n第3章「标题」", original, 51);
        assertTrue(LedgerWritePolicy.samePurchase(original, LedgerAuditPayload.parsedPurchase(detail)));
        assertEquals(51L, LedgerAuditPayload.deleteAuditId(detail));
        assertEquals("原样恢复\n第3章「标题」", LedgerAuditPayload.message(detail));
        assertEquals(7, new JSONObject(detail).getJSONObject("purchase").length());
        assertFalse(detail.contains("password"));
        assertFalse(detail.contains("login_name"));
    }

    @Test
    public void nullableSourceIsPreservedInsteadOfGuessed() {
        Purchase original = original();
        original.source = null;
        String detail = LedgerAuditPayload.encode("旧记录", original, 0);
        assertTrue(LedgerWritePolicy.samePurchase(original, LedgerAuditPayload.parsedPurchase(detail)));
        assertEquals(0L, LedgerAuditPayload.deleteAuditId(detail));
    }

    @Test
    public void missingColumnsUnknownVersionsAndMalformedJsonCannotRestore() throws Exception {
        JSONObject valid = new JSONObject(LedgerAuditPayload.encode("说明", original(), 0));
        for (String field : new String[]{"id", "account_id", "chapter_id", "cost_coupons",
                "cost_vouchers", "purchased_at", "source"}) {
            JSONObject broken = new JSONObject(valid.toString());
            broken.getJSONObject("purchase").remove(field);
            assertNull(field, LedgerAuditPayload.parsedPurchase(broken.toString()));
        }
        valid.put("version", 2);
        assertNull(LedgerAuditPayload.parsedPurchase(valid.toString()));
        assertEquals(-1L, LedgerAuditPayload.deleteAuditId(valid.toString()));
        assertEquals("核对记录版本不支持", LedgerAuditPayload.message(valid.toString()));
        assertNull(LedgerAuditPayload.parsedPurchase("{broken"));
        assertNull(LedgerAuditPayload.parsedPurchase(null));
    }

    @Test
    public void numericCoercionCannotChangeTheRecordedAmountOrDate() throws Exception {
        for (Object value : new Object[]{"27", 27.5, -1, 2147483648L, JSONObject.NULL}) {
            JSONObject broken = new JSONObject(LedgerAuditPayload.encode("说明", original(), 0));
            broken.getJSONObject("purchase").put("cost_vouchers", value);
            assertNull(String.valueOf(value), LedgerAuditPayload.parsedPurchase(broken.toString()));
        }
        JSONObject broken = new JSONObject(LedgerAuditPayload.encode("说明", original(), 0));
        broken.getJSONObject("purchase").put("purchased_at", "1700000000123");
        assertNull(LedgerAuditPayload.parsedPurchase(broken.toString()));
    }

    @Test
    public void zeroOrNegativeIdentityAndMissingTimeAreNotRestorable() throws Exception {
        for (String field : new String[]{"id", "account_id", "chapter_id", "purchased_at"}) {
            JSONObject broken = new JSONObject(LedgerAuditPayload.encode("说明", original(), 0));
            broken.getJSONObject("purchase").put(field, 0);
            assertNull(field, LedgerAuditPayload.parsedPurchase(broken.toString()));
        }
        JSONObject broken = new JSONObject(LedgerAuditPayload.encode("说明", original(), 0));
        broken.getJSONObject("purchase").put("source", 123);
        assertNull(LedgerAuditPayload.parsedPurchase(broken.toString()));
    }

    @Test
    public void anInvalidRestoreLinkIsUnknownRatherThanZero() throws Exception {
        for (Object value : new Object[]{"51", 51.5, -1, JSONObject.NULL}) {
            JSONObject broken = new JSONObject(LedgerAuditPayload.encode("说明", original(), 0));
            broken.put("deleteAuditId", value);
            assertEquals(-1L, LedgerAuditPayload.deleteAuditId(broken.toString()));
        }
    }

    @Test
    public void humanMessageDoesNotDumpJsonAndSupportsExistingPlainNotes() {
        assertEquals("旧说明", LedgerAuditPayload.message("旧说明"));
        assertEquals("", LedgerAuditPayload.message(null));
        assertEquals("核对记录内容损坏", LedgerAuditPayload.message("{broken"));
        assertEquals("核对记录未附说明", LedgerAuditPayload.message(
                LedgerAuditPayload.encode(null, null, 0)));
        assertNull(LedgerAuditPayload.parsedPurchase(LedgerAuditPayload.encode("存疑", null, 0)));
    }
}
