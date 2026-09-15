package com.example.blb.util;

import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/** 订阅 CSV 的账务字段映射。日期无效时拒绝该行，不能凭空记成今天的花费。 */
public final class PurchaseCsv {

    // 代券列始终放在末尾，保留旧七列文件的位置。
    public static final String HEADER =
            "novel_title,chapter_no,chapter_title,account_login,cost_coupons,source,purchased_at,"
                    + "cost_vouchers";

    private static final String ISO_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
    private static final Pattern MILLIS = Pattern.compile("[0-9]+");
    private static final Pattern LEGACY_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern ISO_DATE = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}"
                    + "(?:Z|[+-][0-9]{2}:[0-9]{2})");
    private static final long MAX_TIME = 253_402_300_799_999L; // 9999-12-31T23:59:59.999Z

    private PurchaseCsv() {
    }

    public static String row(PurchaseRow row) {
        SimpleDateFormat format = new SimpleDateFormat(ISO_PATTERN, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return Csv.row(row.novelTitle, String.valueOf(row.chapterNo), row.chapterTitle,
                row.accountLoginName, String.valueOf(row.costCoupons), row.source,
                format.format(new Date(row.purchasedAt)), String.valueOf(row.costVouchers));
    }

    /** 旧导出曾缺代券列；缺失金额不能伪装成零元购买，日期和两种金额都明确才接收。 */
    public static Purchase parsePurchase(List<String> row) {
        return parsePurchase(row, TimeZone.getDefault());
    }

    static Purchase parsePurchase(List<String> row, TimeZone legacyTimeZone) {
        Long purchasedAt = parseTime(Csv.at(row, 6), legacyTimeZone);
        if (purchasedAt == null) return null;
        int coupons = amount(Csv.at(row, 4));
        int vouchers = amount(Csv.at(row, 7));
        if (coupons < 0 || vouchers < 0) return null;

        Purchase purchase = new Purchase();
        purchase.costCoupons = coupons;
        purchase.costVouchers = vouchers;
        purchase.source = Csv.at(row, 5);
        if (Texts.isBlank(purchase.source)) purchase.source = Purchase.SRC_MANUAL;
        purchase.purchasedAt = purchasedAt;
        return purchase;
    }

    private static int amount(String value) {
        if (!MILLIS.matcher(value).matches()) return -1;
        try {
            long amount = Long.parseLong(value);
            return amount <= Integer.MAX_VALUE ? (int) amount : -1;
        } catch (NumberFormatException failure) {
            return -1;
        }
    }

    private static Long parseTime(String value, TimeZone legacyTimeZone) {
        if (MILLIS.matcher(value).matches()) {
            try {
                long millis = Long.parseLong(value);
                return validTime(millis) ? millis : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        SimpleDateFormat format;
        if (LEGACY_DATE.matcher(value).matches()) {
            // 旧导出仅有本地日期；在当地午夜还原，不改变它所属的花费日期。
            format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            format.setTimeZone(legacyTimeZone);
        } else if (ISO_DATE.matcher(value).matches()) {
            format = new SimpleDateFormat(ISO_PATTERN, Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
        } else {
            return null;
        }
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date date = format.parse(value, position);
        if (date == null || position.getIndex() != value.length()) return null;
        long millis = date.getTime();
        return validTime(millis) ? millis : null;
    }

    private static boolean validTime(long millis) {
        return millis >= 0 && millis <= MAX_TIME;
    }
}
