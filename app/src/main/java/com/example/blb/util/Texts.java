package com.example.blb.util;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 纯文本/日期工具，放在这里是为了能被普通 JVM 单元测试直接覆盖。 */
public final class Texts {

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,，]*");
    /**
     * 整段文字<b>只有</b>一个数字（允许千分位和「1.2万」）。
     *
     * <p>「我的」页那一行余额的数字节点没有 id、也没有任何标签文字（实测 {@code '10'}
     * 就画在 {@code '代券'} 正上方），只能靠「这个节点整段就是个数字」把它从周围的文案里
     * 认出来 —— 所以这里必须是整段匹配，不能用 {@link #NUMBER} 那种 find 语义，
     * 否则「连签0天」「7天连签」之类都会被当成余额。
     */
    private static final Pattern WHOLE_NUMBER =
            Pattern.compile("^(\\d[\\d,，]*)(?:[.．](\\d+))?\\s*(万)?$");
    private static final Pattern FIRE = Pattern.compile("(\\d[\\d,，]*)\\s*火券");
    private static final Pattern VOUCHER = Pattern.compile("(\\d[\\d,，]*)\\s*代券");
    /** 新旧选择章节页共用的行首前缀；缺「章」时必须由空白把章号和正文隔开。 */
    private static final Pattern CHAPTER_PREFIX = Pattern.compile(
            "^(?:第\\s*([0-9]{1,5})\\s*[章张]"
                    + "|第\\s*([0-9]{1,5})\\s+(?=\\S)"
                    + "|([0-9]{1,5})(?![0-9]))\\s*");
    /** 缺「章」形式不能把「第 2 卷」这类整卷标题误认成单章。 */
    private static final Pattern VOLUME_MARKER = Pattern.compile("^[卷部册集篇季]");
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
     * 选择章节页行首标号：「67 周日工作」「第67章 周日工作」→ 67。
     *
     * <p>返回 -1 表示这一行开头没有标号 —— 实测那就是<b>卷标题行</b>
     * （「世界线的变动，学生会长的恋爱」），它同样带 {@code item_cb}，勾下去等于勾整卷，
     * 所以必须认出来并跳过。保留旧页「71留宿之夜」的无空格格式，也认新页「第 71 章」。
     * 真机还有错写「张」的「第30张 红温」和漏写「章」的「第68 投影」；后者必须有空白和正文，
     * 且正文不能以卷/部/册等卷标记开头。
     * 标号只允许出现在行首，不能从卷名或正文中间寻找数字。
     */
    public static int rowChapterNo(String rowText) {
        Matcher prefix = chapterPrefix(normalizeChapterText(rowText));
        if (prefix == null) return -1;
        for (int group = 1; group <= prefix.groupCount(); group++) {
            if (prefix.group(group) != null) return toInt(prefix.group(group));
        }
        return -1;
    }

    /**
     * 目录行去掉一次已识别的行首标号后的正文；未识别到前缀时只统一空白。
     *
     * <p>不能对已经得到的正文再调用一次：正文可能本来就叫「100天后」或「第2章的秘密」。
     */
    public static String chapterTitle(String rowText) {
        String text = normalizeChapterText(rowText);
        Matcher prefix = chapterPrefix(text);
        return prefix == null ? text : text.substring(prefix.end()).trim();
    }

    private static Matcher chapterPrefix(String text) {
        Matcher prefix = CHAPTER_PREFIX.matcher(text);
        if (!prefix.find()) return null;
        if (prefix.group(2) != null
                && VOLUME_MARKER.matcher(text.substring(prefix.end())).find()) return null;
        return prefix;
    }

    private static String normalizeChapterText(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\u00a0\\u3000]+", " ").trim();
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
     * 整段文字就是一个数字时才把它读出来（「10」「1,234」「1.2万」都认），
     * 掺了别的字一律返回 -1。给「我的」页那种没有 id 的余额数字用。
     */
    public static int parseWholeCount(String text) {
        if (text == null) return -1;
        Matcher m = WHOLE_NUMBER.matcher(text.trim());
        if (!m.matches()) return -1;
        int whole = toInt(m.group(1));
        if (whole < 0) return -1;
        if (m.group(3) == null) return whole;
        // 「1.2万」= 12000；小数位按十进制补齐，只保留到个位。
        long value = (long) whole * 10_000L;
        String frac = m.group(2);
        if (frac != null) {
            for (int i = 0; i < frac.length() && i < 4; i++) {
                value += (frac.charAt(i) - '0') * (long) Math.pow(10, 3 - i);
            }
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
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
            // 只读到一种券时，另一种写「?」而不是 0：编出来的 0 会让人以为真的读到了。
            return "火券 " + (fire >= 0 ? String.valueOf(fire) : "?")
                    + " / 代券 " + (voucher >= 0 ? String.valueOf(voucher) : "?");
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
     * 选择章节页勾上章节之后底部那句「实付」（{@code tvTips}）拆出来的两种券。
     *
     * <p>这是「只花代券」这条硬约束<b>唯一</b>的判据：菠萝包会先拿代券抵扣，抵不完的差额才
     * 从火券里扣，而这句话直接把结果写出来 —— 「实付0火券+12代券」就是「这一章不动火券」。
     * 用户不充值火券，所以 {@link #fire} 不是 0 的那一章一律放弃、换下一个号。
     */
    public static final class Payment {

        /** 实付火券；-1 表示这句话读不出来（不是 0）。 */
        public final int fire;
        /** 实付代券；-1 表示读不出来。 */
        public final int voucher;

        Payment(int fire, int voucher) {
            this.fire = fire;
            this.voucher = voucher;
        }

        public boolean known() {
            return fire >= 0 || voucher >= 0;
        }

        /** 这一章能不能只用代券买下来。读不到就是「不知道」，一律当成不能。 */
        public boolean vouchersOnly() {
            return fire == 0 && voucher >= 0;
        }

        public String describe() {
            if (!known()) return "实付读不到";
            return "实付 火券 " + (fire >= 0 ? String.valueOf(fire) : "?")
                    + " + 代券 " + (voucher >= 0 ? String.valueOf(voucher) : "?");
        }
    }

    /** 「实付」这两个字是这句话的身份证，没有它就不是实付文案。 */
    private static final Pattern PAY_PREFIX = Pattern.compile("实付");

    /**
     * 解析「实付0火券+12代券」这类文案。
     *
     * <p>为什么不复用 {@link #parseBalance}：那边在「实付15代券」上会把火券读成 -1（未知），
     * 而这里 <b>没写火券就是不花火券</b>，语义正好相反 —— 拿未知当 0 会误买，拿 0 当未知会
     * 白白放弃买得起的章。所以单独一个方法，并且只认带「实付」字样的串：
     * 「账户余额：0火券/15代券」「订阅全部 612火券」都不是实付，一律返回未知。
     */
    public static Payment parsePayment(String text) {
        if (text == null || !PAY_PREFIX.matcher(text).find()) return new Payment(-1, -1);
        int fire = group1(FIRE, text);
        int voucher = group1(VOUCHER, text);
        if (fire < 0 && voucher < 0) return new Payment(-1, -1);
        // 只写了一种券时，另一种就是 0 —— 「实付15代券」= 一张火券都不动。
        return new Payment(Math.max(fire, 0), Math.max(voucher, 0));
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
