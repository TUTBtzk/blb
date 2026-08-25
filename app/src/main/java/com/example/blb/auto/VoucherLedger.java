package com.example.blb.auto;

import com.example.blb.util.Texts;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「我的 → 代券 → 订阅清单」那一行 —— 账本的<b>第二来源</b>，用来跟我们自己写的购买记录对账。
 *
 * <p>为什么非要有第二来源：「这一章归谁」原先只有一个来源，就是我们自己写进 Room 的购买记录。
 * 账本一旦写歪，App 自己是看不出来的 —— 2026-08-25 第49章被误挂成「皓平、五杯半雪碧」两个号，
 * 发现它的是用户，靠的正是菠萝包自己的这份订阅清单。它是服务器端的事实，
 * 独立于我们的任何推断（界面上的「已下载」是本机状态、8 个号共用，推不出买家）。
 *
 * <p>它这一层能回答什么、不能回答什么（2026-08-25 真机 dump）：
 * <ul>
 *   <li><b>能</b>：这个号在这本书上订了几章、一共花了多少火券 —— 清单是<b>按书聚合</b>的，
 *       一行就是一本书（{@code tvbBookAutor} 写着「2章节 - 0火券」）。</li>
 *   <li><b>不在这一层</b>：订的是哪几章。那要点<b>整行</b>进「订阅明细」页 ——
 *       见 {@link SubscribedDetail}，逐章那一层在那儿。<b>注意别点「查看目录」</b>：
 *       它进去是整本书的目录列表（{@code edit_title}＝「目录列表」），不是买过哪几章。</li>
 * </ul>
 */
public final class VoucherLedger {

    private static final long NAV_TIMEOUT = 12_000L;
    /** 清单实测 5 屏到底（一个号订过的书不会太多），留一倍余量。 */
    private static final int MAX_SCROLLS = 12;
    /** 从书名往上找「整行」最多走几层。 */
    private static final int MAX_ROW_DEPTH = 6;

    private static final Pattern CHAPTERS = Pattern.compile("(\\d[\\d,，]*)\\s*章节");
    private static final Pattern FIRE = Pattern.compile("(\\d[\\d,，]*)\\s*火券");
    private static final Pattern VOUCHER = Pattern.compile("(\\d[\\d,，]*)\\s*代券");

    private VoucherLedger() {
    }

    /** 清单上那一行说的话。数字一律用 -1 表示「读不到」，绝不用 0 顶替。 */
    public static final class Reading {

        /** 清单里到底有没有这本书那一行。没有那一行和「有行但读不出数字」要分开处理。 */
        public final boolean found;
        /** 服务器说这个号在这本书上订了几章；-1＝读不到。 */
        public final int chapters;
        /** 这些章一共花了多少火券；-1＝读不到。用户不充值火券，所以正常必须是 0。 */
        public final int fire;
        /** 清单实测只写火券，所以这一项通常是 -1（没写）。 */
        public final int voucher;
        /** {@code tvbBookDesc} 那个日期，只写进日志。 */
        public final String date;
        /** 原始文案，报警时原样带出去 —— 文案改了字样才看得出来。 */
        public final String raw;

        Reading(boolean found, int chapters, int fire, int voucher, String date, String raw) {
            this.found = found;
            this.chapters = chapters;
            this.fire = fire;
            this.voucher = voucher;
            this.date = date;
            this.raw = raw;
        }

        /**
         * 清单里根本没有这本书那一行。
         *
         * <p>一个号一章都没订过的时候本来就没有这一行 —— 所以这不等于「读失败」，
         * 但也不等于「订了 0 章」：清单没加载出来、停在「漫画」那个 tab、书名字样和台账里
         * 记的不一样，看起来都是这一种。判定见 {@link #reconcile}。
         */
        public static Reading missingRow() {
            return new Reading(false, -1, -1, -1, null, null);
        }

        public boolean known() {
            return chapters >= 0;
        }

        /** 花了火券 —— 用户不充值火券，这一条是硬约束被破坏的直接证据。 */
        public boolean fireSpent() {
            return fire > 0;
        }

        public String describe() {
            if (!found) return "订阅清单里没有这本书那一行";
            if (!known()) return "订阅清单读不到（原文「" + text(raw) + "」）";
            return "订阅清单：" + chapters + " 章、火券 "
                    + (fire >= 0 ? String.valueOf(fire) : "?")
                    + (voucher > 0 ? "、代券 " + voucher : "")
                    + (Texts.isBlank(date) ? "" : "、" + date.trim());
        }
    }

    private static String text(String s) {
        return Texts.isBlank(s) ? "读不到" : s.trim();
    }

    /**
     * 解析「2章节 - 0火券」。
     *
     * <p>刻意<b>不</b>复用 {@link Texts#parseBalance}／{@link Texts#parsePayment}：那两个的语义是
     * 余额和实付，而这里少写一种券不代表 0（清单实测只写火券，代券根本不出现），
     * 拿「没写＝0」去套会凭空断言「一分代券都没花」。所以没写就是 -1（不知道）。
     */
    public static Reading parseSummary(String summary, String date) {
        int chapters = group1(CHAPTERS, summary);
        if (chapters < 0) return new Reading(true, -1, -1, -1, date, summary);
        return new Reading(true, chapters, group1(FIRE, summary), group1(VOUCHER, summary),
                date, summary);
    }

    private static int group1(Pattern p, String s) {
        if (s == null) return -1;
        Matcher m = p.matcher(s);
        if (!m.find()) return -1;
        try {
            long v = Long.parseLong(m.group(1).replace(",", "").replace("，", ""));
            return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ===== 对账 =====

    /** 对账结论。 */
    public static final class Audit {

        /** 界面和账本对得上（或者压根没读到界面，那种情况不算「对不上」）。 */
        public final boolean ok;
        /** 真读到了界面上的数字 —— 只有这时候 {@link #ok} 才算一次真的核对。 */
        public final boolean checked;
        /** 该写进日志和队列小结的那句话。 */
        public final String message;

        Audit(boolean ok, boolean checked, String message) {
            this.ok = ok;
            this.checked = checked;
            this.message = message;
        }
    }

    /**
     * 拿界面上那一行去核账本。
     *
     * <p>拆成纯函数是为了能单测：这是「账本可不可信」的判据本身，而它在真机上要登一个号、
     * 走三次点按才看得到一次。
     *
     * <p>三条判据，各自都有出处：
     * <ul>
     *   <li><b>章数不一致</b>就停。界面比账本多＝有一章券扣了却没记账（2026-08-24 那次 40 代券
     *       就是这样），下一趟会有第二个号再买同一章；账本比界面多＝有一章被误挂给了这个号
     *       （2026-08-25 第49章），那一章其实没人买，会被永远跳过 —— 两种都直接违反
     *       「8 个号拼出完整一本、不多订不漏订」。</li>
     *   <li><b>界面上花了火券</b>就停：用户不充值火券，这是硬约束被破坏的直接证据。</li>
     *   <li><b>读不到</b>不算对不上：清单读不出来并不说明账本错了，把它当失败会让整个订阅
     *       功能被一句读不到的文案永久卡死。只报警。</li>
     * </ul>
     *
     * @param uiFire     界面那一行说花了多少火券
     * @param ledgerPaid 账本里这个号在这本书上「花过券」的记录条数（干跑不算，免费章不算）
     * @param ledgerFire 账本记的火券花费合计，正常是 0
     */
    public static Audit reconcile(String who, String bookTitle, Reading ui,
                                 int ledgerPaid, int ledgerFire) {
        String head = who + " 在《" + bookTitle + "》上：";
        if (ui == null || !ui.found) {
            if (ledgerPaid == 0) {
                return new Audit(true, true, head
                        + "订阅清单里没有这本书（一章都没订过），账本也是 0 条付费记录 —— 对上了");
            }
            // 「清单里没这一行」innocent 的原因太多（没加载完、停在「漫画」那个 tab、书名字样
            // 和台账里记的不一样），所以不当成「账本多记」直接停整趟，只大声报出来。
            return new Audit(true, false, head + "账本记着 " + ledgerPaid
                    + " 条付费记录，订阅清单里却没有这本书那一行 —— 这一趟没能核对。"
                    + "要么清单没加载出来／不在「轻小说」那个 tab／书名字样和台账里记的不一样，"
                    + "要么账本真的多记了，请核对");
        }
        if (!ui.known()) {
            return new Audit(true, false, head + ui.describe()
                    + "，这一趟没能核对（账本说 " + ledgerPaid + " 章）");
        }
        if (ui.fireSpent()) {
            return new Audit(false, true, head + "订阅清单说花掉了 " + ui.fire
                    + " 火券（原文「" + text(ui.raw) + "」）—— 用户不充值火券，"
                    + "这条硬约束已经被破坏，整趟停下等人核对");
        }
        if (ui.chapters != ledgerPaid) {
            String direction = ui.chapters > ledgerPaid
                    ? "账本漏记了 " + (ui.chapters - ledgerPaid) + " 章（券扣了没记账，"
                    + "下一趟会有第二个号再买同一章）"
                    : "账本多记了 " + (ledgerPaid - ui.chapters) + " 章（那些章其实没人买，"
                    + "会被永远跳过 —— 那就是漏订）";
            return new Audit(false, true, head + "订阅清单说订了 " + ui.chapters
                    + " 章、账本记着 " + ledgerPaid + " 条付费记录 —— " + direction);
        }
        if (ledgerFire > 0) {
            return new Audit(false, true, head + "界面说一分火券都没花，"
                    + "账本自己却记着花了 " + ledgerFire + " 火券 —— 有一边记错了，停下核对");
        }
        return new Audit(true, true, head + "对上了（" + ui.describe()
                + "＝账本 " + ledgerPaid + " 条付费记录，火券 0）");
    }

    // ===== 走过去读那一行 =====

    /** 这三屏的选择器配齐了吗 —— 没配齐就跳过对账，绝不因此中止订阅。 */
    public static boolean configured(SelectorSet selectors) {
        return selectors != null && selectors.missing(REQUIRED).isEmpty();
    }

    private static final String[] REQUIRED = {
            Keys.MINE_TAB, Keys.VOUCHER_ENTRY, Keys.SUBSCRIBED_LIST_ENTRY,
            Keys.SUBSCRIBED_BOOK_TITLE, Keys.SUBSCRIBED_BOOK_SUMMARY};

    /**
     * 走「我的 → 代券 → 订阅清单」，读出目标书那一行。<b>一个字都不写、一分券都不花。</b>
     *
     * <p>结束时停在订阅清单页，调用方要自己回首页（{@link StepRunner#ensureHome}）——
     * 后面搜书那一步是从首页开始的。
     */
    public static Reading read(StepRunner r, String bookTitle) throws StepRunner.StepFailure {
        return locate(r, bookTitle).reading;
    }

    /**
     * 那一行读到的话，外加<b>那一行本身</b>。
     *
     * <p>行节点是进「订阅明细」（逐章那一层，见 {@link SubscribedDetail}）唯一的入口：
     * 用户 2026-08-25 指的行右上角那个「&gt;」实测是空文本、没有 id、不可点的装饰，
     * 真正可点的是整行这个 {@code LinearLayout}。
     */
    public static final class Located {
        public final Reading reading;
        /** 清单里那一行；null＝没找到那一行，或者行结构变了。 */
        public final NodeView row;

        Located(Reading reading, NodeView row) {
            this.reading = reading;
            this.row = row;
        }
    }

    /** 同 {@link #read}，但把那一行本身也带回来（要点进「订阅明细」就得用它）。 */
    public static Located locate(StepRunner r, String bookTitle) throws StepRunner.StepFailure {
        r.ensureHome(4);
        r.click(Keys.MINE_TAB, NAV_TIMEOUT);
        // 退登重登之后「我的」页那个 ScrollView 停在底部，「代券」那一行不在可见树里。
        r.scrollToTop(4);
        r.click(Keys.VOUCHER_ENTRY, NAV_TIMEOUT);
        r.click(Keys.SUBSCRIBED_LIST_ENTRY, NAV_TIMEOUT);
        r.waitMillis(1500);
        StepRunner.Outcome hit = r.scrollToRowWithText(
                Keys.SUBSCRIBED_BOOK_TITLE, "订阅清单里《" + bookTitle + "》那一行",
                bookTitle, MAX_SCROLLS);
        if (hit == null) return new Located(Reading.missingRow(), null);
        NodeView row = rowOf(r, hit.node);
        if (row == null) return new Located(new Reading(true, -1, -1, -1, null, null), null);
        NodeView summary = r.findIn(row, Keys.SUBSCRIBED_BOOK_SUMMARY);
        NodeView date = r.findIn(row, Keys.SUBSCRIBED_BOOK_DATE);
        return new Located(parseSummary(summary == null ? null : summary.text(),
                date == null ? null : date.text()), row);
    }

    /**
     * 从书名那个节点往上走到「整行」。
     *
     * <p>判据是「这一层里有摘要（{@code tvbBookAutor}）」。必须限定在行内查：整屏上到处都是
     * 别的书的同名 id，全局找会把上一本书的「2章节 - 0火券」读成这一本的。
     */
    static NodeView rowOf(StepRunner r, NodeView title) {
        NodeView n = title;
        for (int depth = 0; n != null && depth < MAX_ROW_DEPTH; depth++) {
            if (r.findIn(n, Keys.SUBSCRIBED_BOOK_SUMMARY) != null) return n;
            n = n.parent();
        }
        return null;
    }
}
