package com.example.blb.util;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 纯文本/日期工具，放在这里是为了能被普通 JVM 单元测试直接覆盖。 */
public final class Texts {

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,，]*");
    private static final Pattern FIRE = Pattern.compile("(\\d[\\d,，]*)\\s*火券");
    private static final Pattern VOUCHER = Pattern.compile("(\\d[\\d,，]*)\\s*代券");
    private static final Pattern REMAINING = Pattern.compile("还剩\\s*(\\d+)\\s*次");
    private static final Pattern OUT_OF = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    private static final char[] CN_DIGITS = "零一二三四五六七八九".toCharArray();
    private static final char[] CN_UNITS = {0, '十', '百', '千'};
    private static final int[] CN_POW = {1, 10, 100, 1000};

    private Texts() {
    }

    /**
     * 阿拉伯数字转中文数字（1–9999，超出范围原样返回数字）。
     *
     * <p>菠萝包目录里写的是「第十一章」，我们库里存的是 {@code chapterNo=11}，
     * 不转换就永远对不上。规则按中文习惯：11 是「十一」不是「一十一」，
     * 101 是「一百零一」，1010 是「一千零一十」。
     */
    public static String cnNumber(int n) {
        if (n == 0) return "零";
        if (n < 0 || n > 9999) return String.valueOf(n);
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        boolean zeroPending = false;
        for (int unit = 3; unit >= 0; unit--) {
            int d = (n / CN_POW[unit]) % 10;
            if (d == 0) {
                if (started) zeroPending = true;
                continue;
            }
            if (zeroPending) {
                sb.append(CN_DIGITS[0]);
                zeroPending = false;
            }
            // 十位上的 1 在最高位时省掉「一」：十一、十五，而不是一十一。
            if (!(d == 1 && unit == 1 && !started)) sb.append(CN_DIGITS[d]);
            if (unit > 0) sb.append(CN_UNITS[unit]);
            started = true;
        }
        return sb.toString();
    }

    /** 目录里那一行的章节标号写法，例如 11 → 「第十一章」。 */
    public static String cnChapterLabel(int chapterNo) {
        return "第" + cnNumber(chapterNo) + "章";
    }

    /**
     * 匹配目录行的正则：同时认「第十一章」和「第11章」，且不会被「第110章」误伤。
     * 行文本形如「第十一章 久违的笑」，所以只锚开头、后面允许跟标题。
     */
    public static String chapterLabelRegex(int chapterNo) {
        return "^第\\s*(" + Pattern.quote(cnNumber(chapterNo)) + "|0*" + chapterNo
                + ")\\s*章(?![0-9])";
    }

    /**
     * 从「火券：1,234」「余额 1234 券」这类文本里抠出第一个整数。
     * 抠不出来返回 -1，调用方据此决定是否覆盖已有余额。
     */
    public static int parseCount(String text) {
        if (text == null) return -1;
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) return -1;
        return toInt(m.group());
    }

    /**
     * 批量购买页 {@code tvAccount} 上那行「账户余额：0火券/0代券」拆出来的两种券。
     * 读不到的那一项是 -1，调用方据此决定要不要覆盖库里已有的值。
     */
    public static final class Balance {

        public final int fire;
        public final int voucher;

        Balance(int fire, int voucher) {
            this.fire = fire;
            this.voucher = voucher;
        }

        public boolean known() {
            return fire >= 0 || voucher >= 0;
        }

        /**
         * 能拿来订阅的总量。菠萝包里章节费两种券都能付（代券先扣），所以按相加估算；
         * 两项都读不到时返回 -1，让调用方走「不知道就别拦」的路子。
         */
        public int usable() {
            return known() ? Math.max(0, fire) + Math.max(0, voucher) : -1;
        }

        public String describe() {
            if (!known()) return "余额读不到";
            return "火券 " + Math.max(0, fire) + " / 代券 " + Math.max(0, voucher);
        }
    }

    /**
     * 解析余额文本。带「火券」「代券」字样时分别取；都没有的旧版文案退化成
     * 「第一个数字当火券」，至少不至于把余额当成 0。
     */
    public static Balance parseBalance(String text) {
        int fire = group1(FIRE, text);
        int voucher = group1(VOUCHER, text);
        if (fire < 0 && voucher < 0) fire = parseCount(text);
        return new Balance(fire, voucher);
    }

    /** 已经分别拿到两种券时直接包一下；-1 一律表示「没读到」。 */
    public static Balance balance(int fire, int voucher) {
        return new Balance(fire, voucher);
    }

    /**
     * 从「今日还剩5次」「2/5」这类文案里读出「还能看几个广告」。
     * 读不到返回 -1 —— 宁可让调用方按配置的每日个数走，也不要当成 0 直接跳过。
     */
    public static int parseRemaining(String text) {
        if (text == null) return -1;
        Matcher m = REMAINING.matcher(text);
        if (m.find()) return toInt(m.group(1));
        m = OUT_OF.matcher(text);
        if (m.find()) {
            int done = toInt(m.group(1));
            int total = toInt(m.group(2));
            if (done >= 0 && total >= 0 && total >= done) return total - done;
        }
        return -1;
    }

    /**
     * 取文本里第一个数字，读不到返回 {@code fallback}。
     * 用来从「去浏览15秒免看此广告」这类文案里读出广告主要求的浏览秒数。
     */
    public static int firstInt(String text, int fallback) {
        if (text == null) return fallback;
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) return fallback;
        int v = toInt(m.group());
        return v < 0 ? fallback : v;
    }

    private static int group1(Pattern p, String text) {
        if (text == null) return -1;
        Matcher m = p.matcher(text);
        return m.find() ? toInt(m.group(1)) : -1;
    }

    private static int toInt(String digits) {
        if (digits == null) return -1;
        String d = digits.replace(",", "").replace("，", "");
        if (d.isEmpty()) return -1;
        try {
            long v = Long.parseLong(d);
            return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 本地时区的 yyyy-MM-dd，用作签到日志的天键。 */
    public static String todayYmd() {
        return ymd(System.currentTimeMillis());
    }

    public static String ymd(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** CSV 单元格转义：含逗号、引号、换行时加引号并把引号翻倍。 */
    public static String csvCell(String value) {
        String v = value == null ? "" : value;
        if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) {
            return v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
