package com.example.blb.auto;

import com.example.blb.data.PurchaseRow;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 「我的 → 代券 → 订阅清单 →（点整行）→ 订阅明细」—— <b>逐章</b>的第二来源。
 *
 * <p>和 {@link VoucherLedger} 的分工：清单那一层是<b>按书聚合</b>的，只答得出「这个号在这本书上
 * 订了几章、花了多少火券」；点进那一整行才是这一页，一条＝一章，写着日期、卷名＋章号＋章标题、
 * 以及花掉的「20 代券」。用户 2026-08-25 指出入口是行右上角那个「&gt;」—— 实测那个「&gt;」是
 * 空文本、没有 id、不可点的装饰节点，真正可点的是<b>整行</b>，点整行进来的就是这一页。
 * （「查看目录」进去是整本书的目录列表，不是买过哪几章。）
 *
 * <p>为什么非要逐章：用户那两条硬约束是逐章的 —— 「每一章只能有一个账号订阅」、「8 个号最后能
 * 拼出完整一本」。聚合那一层只看得出「总数差了几章」，看不出差在哪一章；而 2026-08-25 第49章
 * 被误挂成两个号那件事，只有逐章比对才能指着说「就是第49章」。
 *
 * <p>它<b>一个字都不写、一分券都不花</b>：只走过去、念出来、跟 Room 里的购买记录比。
 *
 * <p>一个必须让着的事实：明细页页脚写着「清单约5分钟更新一次，可下拉刷新」。所以刚买完的那一章
 * 可能还没出现在这里 —— 那种情况只报警，<b>绝不</b>当成「账本误挂」停机（见 {@link #FRESH_MS}）。
 */
public final class SubscribedDetail {

    /** 明细一条一条往下翻：一个号在一本书上买过的章数不会太多，留足余量。 */
    private static final int MAX_SCROLLS = 20;
    /** 从 {@code tvDesc} 往上找「这一条」最多走几层。 */
    private static final int MAX_ROW_DEPTH = 4;
    /** 明细页最多等几毫秒出来。 */
    private static final long READY_TIMEOUT = 8_000L;

    /**
     * 「买了多久之内的章，允许清单里还没有」。
     *
     * <p>页脚自己写着「约5分钟更新一次」，给三倍余量。买完不到这个时间就找不到那一条＝
     * 服务器还没同步，只报警；超过这个时间还找不到＝账本真的把这一章挂错了号。
     */
    static final long FRESH_MS = 15 * 60_000L;

    /** 整段都是数字才算金额 —— 明细行里日期也含数字，但它有 id、也不是纯数字。 */
    private static final Pattern PURE_NUMBER = Pattern.compile("^\\d[\\d,，]*$");
    /** 行首那个标号：账本里的标题是目录行的原文（「50   订婚事宜」），比名字时要先去掉它。 */
    private static final Pattern LEADING_NO = Pattern.compile("^\\d{1,5}\\s*");

    private SubscribedDetail() {
    }

    // ===== 明细里的一条 =====

    /** 明细里的一条：某一章，某天，花了多少哪种券。数字读不到一律 -1，绝不用 0 顶替。 */
    public static final class Entry {

        /** 从 {@code tvDesc} 中间那个数字读出来的章号；-1＝认不出（原文留在 {@link #raw}）。 */
        public final int chapterNo;
        /** 章号前面那一段（卷名），只写进日志。 */
        public final String volume;
        /** 章号后面那一段（章标题）—— 跟账本比名字用它，比号更可靠。 */
        public final String title;
        /** 花掉多少；-1＝读不到。 */
        public final int amount;
        /** 「代券」或「火券」；读不到是 null。正常永远是代券。 */
        public final String currency;
        /** {@code tvTime} 那个日期，只写进日志。 */
        public final String date;
        /** {@code tvDesc} 原文，报警时原样带出去 —— 文案改了字样才看得出来。 */
        public final String raw;

        Entry(int chapterNo, String volume, String title,
              int amount, String currency, String date, String raw) {
            this.chapterNo = chapterNo;
            this.volume = volume;
            this.title = title;
            this.amount = amount;
            this.currency = currency;
            this.date = date;
            this.raw = raw;
        }

        /** 认出章号了吗 —— 认不出的条目不参与逐章比对，只报出来让人看。 */
        public boolean known() {
            return chapterNo > 0;
        }

        /** 这一条花的是火券 —— 用户不充值火券，这是硬约束被破坏的直接证据。 */
        public boolean fireSpent() {
            return "火券".equals(currency) && amount > 0;
        }

        /** 同一条在两屏之间会重复出现（滚动有重叠），拿它去重。 */
        String key() {
            return known() ? "#" + chapterNo : "raw:" + norm(raw) + "@" + norm(date);
        }

        public String describe() {
            return (known() ? "第" + chapterNo + "章" : "章号认不出")
                    + (Texts.isBlank(title) ? "" : "「" + title.trim() + "」")
                    + (amount >= 0 ? " " + amount + (currency == null ? "" : currency) : " 花费读不到")
                    + (Texts.isBlank(date) ? "" : " " + date.trim());
        }
    }

    /**
     * 解析明细里的一条。
     *
     * <p>{@code tvDesc} 实测原文：「世界线的变动，学生会长的恋爱 50 订婚事宜，梦玲失踪」
     * ＝ 卷名 + 空格 + 章号 + 空格 + 章标题。所以按空白切开，取<b>第一个整段都是数字</b>的那一节
     * 当章号，它前面是卷名、后面是章标题。切不出来就 {@code chapterNo=-1} —— 认不出就说认不出，
     * 绝不猜一个章号出来（猜错会把「漏记」和「误挂」判反）。
     */
    public static Entry parseRow(String desc, String amountText, String currency, String date) {
        String raw = desc == null ? null : desc.trim();
        if (Texts.isBlank(raw)) {
            return new Entry(-1, null, null, parseAmount(amountText), trimToNull(currency),
                    date, raw);
        }
        String[] parts = raw.split("\\s+");
        int at = -1;
        for (int i = 0; i < parts.length; i++) {
            if (PURE_NUMBER.matcher(parts[i]).matches()) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return new Entry(-1, null, raw, parseAmount(amountText), trimToNull(currency),
                    date, raw);
        }
        int no = Texts.parseCount(parts[at]);
        return new Entry(no, join(parts, 0, at), join(parts, at + 1, parts.length),
                parseAmount(amountText), trimToNull(currency), date, raw);
    }

    private static String join(String[] parts, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static int parseAmount(String text) {
        if (text == null) return -1;
        String t = text.trim();
        return PURE_NUMBER.matcher(t).matches() ? Texts.parseCount(t) : -1;
    }

    private static String trimToNull(String s) {
        return Texts.isBlank(s) ? null : s.trim();
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /** 账本标题和界面标题算不算同一章：认名字、不认行首那个标号（跟 CatalogSync.sameChapter 同一套）。 */
    static boolean sameChapter(String ledgerTitle, String uiTitle) {
        if (Texts.isBlank(ledgerTitle) || Texts.isBlank(uiTitle)) return true; // 缺一边就谈不上对不上
        String a = strip(norm(ledgerTitle));
        String b = strip(norm(uiTitle));
        if (a.equals(b)) return true;
        // 明细里的章标题可能被截断（tvDesc 是两行的 TextView），所以一头包含另一头也算对上。
        return a.contains(b) || b.contains(a);
    }

    private static String strip(String s) {
        return LEADING_NO.matcher(s).replaceFirst("").trim();
    }

    private static String text(String s) {
        return Texts.isBlank(s) ? "读不到" : s.trim();
    }

    // ===== 逐章对账 =====

    /**
     * 拿明细页逐章去核账本。<b>纯函数</b> —— 这是「账本可不可信」的判据本身，
     * 而它在真机上要登一个号、走四次点按才看得到一次，不拆出来根本测不了。
     *
     * <p>五条判据，每一条都指向用户那两条硬约束里的一件事：
     * <ul>
     *   <li><b>明细有、账本这个号没有</b>：券扣了没记账。要是账本把这一章记在<b>别的号</b>名下，
     *       那就是「一章两个号」——「每一章只能有一个账号订阅」被破坏；两种都停。</li>
     *   <li><b>账本有、明细没有</b>：这一章被误挂给了这个号（2026-08-25 第49章那件事），
     *       它其实没人买，却会被永远跳过 —— 那就是漏订。<b>但</b>刚买完不到
     *       {@link #FRESH_MS} 的只报警：页脚自己写着「清单约5分钟更新一次」。</li>
     *   <li><b>号对上了名字对不上</b>：章号整体错位（作者插章／删章），接着买会买错章。</li>
     *   <li><b>明细或账本上出现火券</b>：用户不充值火券，这条硬约束被破坏就停。</li>
     *   <li><b>读不出章号／明细页没读到</b>：只报警。一句读不出来的文案不该把整个订阅功能
     *       永久卡死。</li>
     * </ul>
     *
     * @param novelRows 这本书上<b>所有号</b>的付费记录（免费章不算，它一分券都没花）；
     *                  用来认出「一章两个号」
     * @param now       现在的时间戳，判「刚买完还没同步」用
     */
    public static VoucherLedger.Audit reconcile(String who, long accountId, String bookTitle,
                                                List<Entry> ui, List<PurchaseRow> novelRows,
                                                long now) {
        String head = who + " 在《" + bookTitle + "》上逐章核对：";
        List<PurchaseRow> mine = new ArrayList<>();
        Map<Integer, PurchaseRow> othersByNo = new LinkedHashMap<>();
        if (novelRows != null) {
            for (PurchaseRow p : novelRows) {
                if (p == null || !paid(p)) continue;
                if (p.accountId == accountId) mine.add(p);
                else othersByNo.put(p.chapterNo, p);
            }
        }
        if (ui == null) {
            if (mine.isEmpty()) {
                return new VoucherLedger.Audit(true, false, head
                        + "订阅明细没读出来，账本这边也是 0 条付费记录 —— 这一趟没能逐章核对");
            }
            return new VoucherLedger.Audit(true, false, head + "订阅明细没读出来，账本这边记着 "
                    + mine.size() + " 章 —— 这一趟没能逐章核对，请核对");
        }
        return compare(head, who, ui, mine, othersByNo, now);
    }

    private static boolean paid(PurchaseRow p) {
        return p.costVouchers > 0 || p.costCoupons > 0;
    }

    private static VoucherLedger.Audit compare(String head, String who, List<Entry> ui,
                                               List<PurchaseRow> mine,
                                               Map<Integer, PurchaseRow> othersByNo, long now) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<Integer, Entry> byNo = new LinkedHashMap<>();
        int unknown = 0;
        String firstUnknown = null;
        for (Entry e : ui) {
            if (e == null) continue;
            if (!e.known()) {
                unknown++;
                if (firstUnknown == null) firstUnknown = e.raw;
                continue;
            }
            if (byNo.put(e.chapterNo, e) != null) {
                warnings.add("明细里第" + e.chapterNo + "章出现了两条");
            }
            if (e.fireSpent()) {
                problems.add("明细说第" + e.chapterNo + "章花了 " + e.amount
                        + " 火券 —— 用户不充值火券，这条硬约束已经被破坏");
            }
        }
        Map<Integer, PurchaseRow> mineByNo = new LinkedHashMap<>();
        for (PurchaseRow p : mine) {
            if (mineByNo.put(p.chapterNo, p) != null) {
                problems.add("账本里第" + p.chapterNo + "章有两条付费记录（同一个号买了两次？）");
            }
        }

        // 方向一：菠萝包说这个号买过，账本这边没有。
        for (Entry e : byNo.values()) {
            PurchaseRow p = mineByNo.get(e.chapterNo);
            if (p == null) {
                PurchaseRow other = othersByNo.get(e.chapterNo);
                if (other != null) {
                    problems.add("菠萝包说第" + e.chapterNo + "章是「" + who + "」买的，"
                            + "账本却把它记在「" + other.accountDisplayName() + "」名下 —— "
                            + "要么这一章被两个号买过，要么归属记错了");
                } else {
                    problems.add("菠萝包说「" + who + "」买过第" + e.chapterNo + "章（"
                            + e.describe() + "），账本里一条都没有 —— 券扣了没记账，"
                            + "下一趟会有第二个号再买同一章");
                }
                continue;
            }
            if (!sameChapter(p.chapterTitle, e.title)) {
                problems.add("第" + e.chapterNo + "章号对上了、名字对不上：账本「"
                        + text(p.chapterTitle) + "」／明细「" + text(e.title)
                        + "」—— 章号可能整体错位了");
            }
            if (p.costCoupons > 0) {
                problems.add("账本记着第" + e.chapterNo + "章花了 " + p.costCoupons + " 火券");
            }
            if (e.amount > 0 && p.costVouchers > 0 && e.amount != p.costVouchers) {
                problems.add("第" + e.chapterNo + "章花费两边不一样：明细 " + e.amount
                        + " 代券／账本 " + p.costVouchers + " 代券");
            }
        }

        // 方向二：账本挂在这个号名下，菠萝包的明细里没有。
        for (PurchaseRow p : mineByNo.values()) {
            if (byNo.containsKey(p.chapterNo)) continue;
            long age = now - p.purchasedAt;
            if (p.purchasedAt > 0 && age >= 0 && age < FRESH_MS) {
                warnings.add("账本里第" + p.chapterNo + "章是 " + Math.max(1, age / 60_000)
                        + " 分钟前买的、明细里还没有 —— 清单约 5 分钟更新一次，先放过");
            } else {
                problems.add("账本把第" + p.chapterNo + "章挂在「" + who + "」名下，"
                        + "菠萝包的订阅明细里却没有这一条 —— 这一章其实没人买，"
                        + "会被永远跳过（那就是漏订）");
            }
        }
        if (unknown > 0) {
            warnings.add("明细里有 " + unknown + " 条读不出章号（原文「" + text(firstUnknown)
                    + "」），这几条没参与比对");
        }

        if (!problems.isEmpty()) {
            return new VoucherLedger.Audit(false, true, head + brief(problems));
        }
        if (!warnings.isEmpty()) {
            return new VoucherLedger.Audit(true, false, head + byNo.size() + " 章比对完了，但"
                    + brief(warnings));
        }
        if (byNo.isEmpty()) {
            return new VoucherLedger.Audit(true, mineByNo.isEmpty(), mineByNo.isEmpty()
                    ? head + "明细里一条都没有、账本也是 0 条付费记录 —— 对上了"
                    : head + "明细里一条都没读出来 —— 这一趟没能逐章核对");
        }
        return new VoucherLedger.Audit(true, true, head + "逐章都对上了（"
                + chapters(byNo) + "，共 " + byNo.size() + " 章，全是代券）");
    }

    /** 报警话术：最多念 4 条，剩下的只报个数 —— 日志一行太长反而没人看得完。 */
    private static String brief(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(4, lines.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append("；");
            sb.append(lines.get(i));
        }
        if (lines.size() > shown) sb.append("；……另有 ").append(lines.size() - shown).append(" 条");
        return sb.toString();
    }

    private static String chapters(Map<Integer, Entry> byNo) {
        List<Integer> nos = new ArrayList<>(byNo.keySet());
        java.util.Collections.sort(nos);
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(12, nos.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append('、');
            sb.append('第').append(nos.get(i)).append('章');
        }
        if (nos.size() > shown) sb.append("…");
        return sb.toString();
    }

    // ===== 走过去把明细念出来 =====

    /** 明细页那几条选择器配齐了吗 —— 没配齐就跳过逐章对账，绝不因此中止订阅。 */
    public static boolean configured(SelectorSet selectors) {
        return selectors != null && selectors.missing(REQUIRED).isEmpty();
    }

    private static final String[] REQUIRED = {
            Keys.SUBSCRIBED_DETAIL_READY, Keys.SUBSCRIBED_DETAIL_DESC,
            Keys.SUBSCRIBED_DETAIL_AMOUNT, Keys.SUBSCRIBED_DETAIL_CURRENCY};

    /**
     * 从订阅清单里那一行点进「订阅明细」，一屏一屏念出来，读完<b>退回清单页</b>。
     *
     * <p>点的是<b>整行</b>：用户指的那个「&gt;」是空文本、没 id、不可点的装饰，行内可点的只有
     * 整行和「查看目录」，而「查看目录」进去是目录列表、不是买过哪几章。
     *
     * <p>返回 null＝明细页没打开。读不到不算「对不上」（见 {@link #reconcile}）。
     */
    public static List<Entry> read(StepRunner r, NodeView bookRow, String bookTitle)
            throws StepRunner.StepFailure {
        if (bookRow == null) return null;
        r.clickNode("订阅清单里《" + bookTitle + "》那一整行（进订阅明细）", bookRow);
        r.sleepHuman();
        if (!opened(r)) {
            r.log("  订阅明细页没打开 —— 这一趟不逐章核对（点的是整行；"
                    + "行右上角那个「>」是装饰，点不动）");
            r.back();
            return null;
        }
        List<Entry> out = collect(r);
        r.log("  订阅明细读到 " + out.size() + " 条");
        r.back();   // 退回订阅清单页。回首页由调用方负责 —— 后面搜书那一步是从首页开始的。
        return out;
    }

    private static boolean opened(StepRunner r) throws StepRunner.StepFailure {
        for (long waited = 0; waited <= READY_TIMEOUT; waited += 500) {
            if (!r.findAllOn(Keys.SUBSCRIBED_DETAIL_READY).isEmpty()) return true;
            r.waitMillis(500);
        }
        return false;
    }

    /**
     * 一屏一屏收条目，按章号去重（滚动有重叠，同一条会出现两次）。
     *
     * <p>金额和币种<b>没有 id</b>，整屏上每一条都各有一个「20」和一个「代券」——
     * 所以必须在<b>行内</b>查，全局找会把上一条的金额读成这一条的。
     */
    private static List<Entry> collect(StepRunner r) throws StepRunner.StepFailure {
        Map<String, Entry> found = new LinkedHashMap<>();
        String last = null;
        for (int screen = 0; screen < MAX_SCROLLS; screen++) {
            StringBuilder sig = new StringBuilder();
            for (NodeView desc : r.findAllOn(Keys.SUBSCRIBED_DETAIL_DESC)) {
                if (desc == null) continue;
                sig.append(norm(desc.text())).append('|');
                NodeView row = rowOf(r, desc);
                NodeView amount = row == null ? null : r.findIn(row, Keys.SUBSCRIBED_DETAIL_AMOUNT);
                NodeView currency = row == null
                        ? null : r.findIn(row, Keys.SUBSCRIBED_DETAIL_CURRENCY);
                NodeView time = row == null ? null : r.findIn(row, Keys.SUBSCRIBED_DETAIL_TIME);
                Entry e = parseRow(desc.text(),
                        amount == null ? null : amount.text(),
                        currency == null ? null : currency.text(),
                        time == null ? null : time.text());
                if (!found.containsKey(e.key())) found.put(e.key(), e);
            }
            String now = sig.toString();
            if (now.equals(last)) break;      // 这一屏和上一屏一样＝到底了
            last = now;
            if (!r.scrollForward()) break;
            r.sleepHuman();
        }
        return new ArrayList<>(found.values());
    }

    /**
     * 从 {@code tvDesc} 往上走到「这一条」。
     *
     * <p>判据是「这一层里的币种 TextView 正好一个」：金额和币种都没有 resource-id，
     * 整屏上每一条都各有一个「20」和一个「代券」—— 命中好几个就说明已经走到整张列表上去了，
     * 那时候读到的金额是别人的。宁可返回 null（这一条只报「读不到花费」），也不读错。
     */
    static NodeView rowOf(StepRunner r, NodeView desc) {
        NodeView n = desc;
        for (int depth = 0; n != null && depth < MAX_ROW_DEPTH; depth++) {
            int hits = r.findAllIn(n, Keys.SUBSCRIBED_DETAIL_CURRENCY).size();
            if (hits == 1) return n;
            if (hits > 1) return null;
            n = n.parent();
        }
        return null;
    }
}
