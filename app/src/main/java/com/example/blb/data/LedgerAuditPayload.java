package com.example.blb.data;

import org.json.JSONException;
import org.json.JSONObject;

/** 删除后的账本不能凭摘要重建；保留 purchase 的每一列，并用版本号拒绝不认识的格式。 */
public final class LedgerAuditPayload {
    private static final int VERSION = 1;

    private LedgerAuditPayload() { }

    public static String encode(String message, Purchase purchase, long deleteAuditId) {
        try {
            JSONObject out = new JSONObject();
            out.put("version", VERSION);
            out.put("message", message == null ? JSONObject.NULL : message);
            out.put("deleteAuditId", deleteAuditId);
            if (purchase == null) {
                out.put("purchase", JSONObject.NULL);
            } else {
                JSONObject row = new JSONObject();
                row.put("id", purchase.id);
                row.put("account_id", purchase.accountId);
                row.put("chapter_id", purchase.chapterId);
                row.put("cost_coupons", purchase.costCoupons);
                row.put("cost_vouchers", purchase.costVouchers);
                row.put("purchased_at", purchase.purchasedAt);
                row.put("source", purchase.source == null ? JSONObject.NULL : purchase.source);
                out.put("purchase", row);
            }
            return out.toString();
        } catch (JSONException failure) {
            throw new IllegalArgumentException("不能保存完整账本留痕", failure);
        }
    }

    public static Purchase parsedPurchase(String detail) {
        try {
            JSONObject root = versionOne(detail);
            if (root == null) return null;
            JSONObject row = root.getJSONObject("purchase");
            Purchase purchase = new Purchase();
            purchase.id = integer(row, "id");
            purchase.accountId = integer(row, "account_id");
            purchase.chapterId = integer(row, "chapter_id");
            long coupons = integer(row, "cost_coupons");
            long vouchers = integer(row, "cost_vouchers");
            purchase.purchasedAt = integer(row, "purchased_at");
            Object source = row.get("source");
            if (source != JSONObject.NULL && !(source instanceof String)) return null;
            purchase.source = source == JSONObject.NULL ? null : (String) source;
            if (purchase.id <= 0 || purchase.accountId <= 0 || purchase.chapterId <= 0
                    || coupons < 0 || coupons > Integer.MAX_VALUE
                    || vouchers < 0 || vouchers > Integer.MAX_VALUE || purchase.purchasedAt <= 0) return null;
            purchase.costCoupons = (int) coupons;
            purchase.costVouchers = (int) vouchers;
            return purchase;
        } catch (JSONException | IllegalArgumentException failure) {
            return null;
        }
    }

    public static long deleteAuditId(String detail) {
        try {
            JSONObject root = versionOne(detail);
            if (root == null) return -1;
            long id = integer(root, "deleteAuditId");
            return id < 0 ? -1 : id;
        } catch (JSONException | IllegalArgumentException failure) {
            return -1;
        }
    }

    public static String message(String detail) {
        if (detail == null || detail.trim().isEmpty()) return "";
        if (!detail.trim().startsWith("{")) return detail;
        try {
            JSONObject root = versionOne(detail);
            if (root == null) return "核对记录版本不支持";
            Object message = root.opt("message");
            return message instanceof String ? (String) message : "核对记录未附说明";
        } catch (JSONException | IllegalArgumentException failure) {
            return "核对记录内容损坏";
        }
    }

    private static JSONObject versionOne(String detail) throws JSONException {
        if (detail == null) return null;
        JSONObject root = new JSONObject(detail);
        return integer(root, "version") == VERSION ? root : null;
    }

    /** getLong 会把字符串和小数强转成金额；留痕损坏时宁可拒绝撤销，不能悄悄四舍五入。 */
    private static long integer(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException("留痕的整数列不完整：" + key);
        }
        return ((Number) value).longValue();
    }
}
