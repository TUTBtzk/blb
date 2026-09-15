package com.example.blb.auto;

import com.example.blb.data.Chapter;
import com.example.blb.data.PurchaseRow;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * <p>为什么非要逐章：2026-09-14 用户确认多个号订过同一章是真实历史，旧「一章一个付费号」
 * 不能再当账本不变量。每个账号的账本必须和自己的服务器明细对应；聚合只看得出总数，
 * 看不出哪一笔漏记。自动购买仍在进购买页前检查全书无人拥有，绝不新增重复订阅。
 *
 * <p>它<b>一个字都不写、一分券都不花</b>：只走过去、念出来、跟 Room 里的购买记录比。
 *
 * <p>一个必须让着的事实：明细页页脚写着「清单约5分钟更新一次，可下拉刷新」。所以刚买完的那一章
 * 可能还没出现在这里 —— 那种情况只报警，<b>绝不</b>当成「账本误挂」停机（见 {@link #FRESH_MS}）。
 */
public final class SubscribedDetail {

    /** 防止目标应用的列表永远声称还能滚；到上限仍未抵达底部就拒绝把残缺结果当完整账单。 */
    private static final int MAX_SCROLLS = 100;
    /** 从 {@code tvDesc} 往上找「这一条」最多走几层。 */
    private static final int MAX_ROW_DEPTH = 4;
    /** 明细页最多等几毫秒出来。 */
    private static final long READY_TIMEOUT = 8_000L;
    /** 翻屏后最多等两秒；非空完整屏连续三次相同才开始解析。 */
    private static final long SETTLE_TIMEOUT = 2_000L;
    private static final long SETTLE_STEP = 250L;
    private static final int SETTLE_MATCHES = 3;
    /** 一屏没稳定时先原地重读几次，再决定跳过它继续往下读。 */
    private static final int UNSTABLE_RETRIES = 2;
    /** 连续这么多屏都不稳定（实在读不出证据）才按失败停下。 */
    private static final int UNSTABLE_SCREEN_LIMIT = 3;

    /**
     * 「买了多久之内的章，允许清单里还没有」。
     *
     * <p>页脚自己写着「约5分钟更新一次」，给三倍余量。买完不到这个时间就找不到那一条＝
     * 服务器还没同步，只报警；超过这个时间还找不到＝账本真的把这一章挂错了号。
     */
    public static final long FRESH_MS = 15 * 60_000L;

    /** 整段都是数字才算金额 —— 明细行里日期也含数字，但它有 id、也不是纯数字。 */
    private static final Pattern PURE_NUMBER = Pattern.compile("^\\d[\\d,，]*$");
    /**
     * 章号和章标题<b>粘在一起</b>那种节：「71留宿之夜，夏优来访」。
     *
     * <p>2026-08-31 实测：菠萝包并不保证章号后面有空格。第71章那一条原文是
     * 「世界线的变动，学生会长的恋爱  71留宿之夜，夏优来访」—— 一节都不是纯数字，
     * 于是章号被判成「认不出」，逐章对账就认为「明细里没有第71章」，
     * 而账本里 wefeef 名下确实有第71章（20 代券，真买的）→ 误报「漏订」→ 整趟订阅中止。
     * 数字后面必须紧跟<b>非数字</b>，这样它跟 {@link #PURE_NUMBER} 不会互相抢。
     */
    private static final Pattern NO_GLUED_TO_TITLE = Pattern.compile("^(\\d{1,5})(\\D.*)$");

    private SubscribedDetail() {
    }

    /**
     * 2026-08-31 的漏认章号曾把真购买报成漏订；删除更不能把一份 List 当作已经到底的证据。
     * 保留停止原因和覆盖数，核对页才说得清是账本有差异，还是这一回根本没读完整。
     */
    public static final class ReadResult {
        public final List<Entry> entries;
        public final boolean opened;
        public final boolean startedAtTop;
        public final boolean reachedEnd;
        public final boolean truncated;
        public final int unreadableRows;
        public final int collectionItems;
        public final int coveredItems;
        public final String stopReason;
        public final int expectedChapters;
        public final int screensRead;
        public final List<String> rowProblems;
        /** 不完整屏里的火券也必须留住，不能被失败重试或位置冲突吞掉。 */
        public final boolean fireObserved;

        public ReadResult(List<Entry> entries, boolean opened, boolean startedAtTop,
                          boolean reachedEnd, boolean truncated, int unreadableRows,
                          int collectionItems, int coveredItems, String stopReason) {
            this(entries, opened, startedAtTop, reachedEnd, truncated, unreadableRows,
                    collectionItems, coveredItems, stopReason, -1, -1,
                    Collections.<String>emptyList(), false);
        }

        ReadResult(List<Entry> entries, boolean opened, boolean startedAtTop,
                   boolean reachedEnd, boolean truncated, int unreadableRows,
                   int collectionItems, int coveredItems, String stopReason,
                   int expectedChapters, int screensRead, List<String> rowProblems,
                   boolean fireObserved) {
            this.entries = Collections.unmodifiableList(entries == null
                    ? new ArrayList<Entry>() : new ArrayList<>(entries));
            this.opened = opened;
            this.startedAtTop = startedAtTop;
            this.reachedEnd = reachedEnd;
            this.truncated = truncated;
            this.unreadableRows = unreadableRows;
            this.collectionItems = collectionItems;
            this.coveredItems = coveredItems;
            this.stopReason = stopReason;
            this.expectedChapters = expectedChapters;
            this.screensRead = screensRead;
            this.rowProblems = Collections.unmodifiableList(rowProblems == null
                    ? new ArrayList<String>() : new ArrayList<>(rowProblems));
            boolean fire = fireObserved;
            for (Entry entry : this.entries) fire |= entry != null && entry.fireSpent();
            this.fireObserved = fire;
        }

        public boolean complete() {
            if (!opened || !startedAtTop || !reachedEnd || truncated || unreadableRows != 0
                    || collectionItems <= 0 || coveredItems != collectionItems
                    || entries.isEmpty()
                    || (expectedChapters >= 0 && entries.size() != expectedChapters)) return false;
            Set<String> identities = new HashSet<>();
            for (Entry entry : entries) {
                if (!completeTransaction(entry)) return false;
                if (!identities.add(norm(entry.volume) + ":" + entry.chapterNo + ":" + norm(entry.title))) {
                    return false;
                }
            }
            return true;
        }

        public String describe() {
            return (complete() ? "明细完整" : "明细未完整") + "：读到 " + entries.size()
                    + " 条；已打开=" + opened + "，从顶=" + startedAtTop + "，到底=" + reachedEnd
                    + "，截断=" + truncated + "，未读全=" + knownNumber(unreadableRows)
                    + "，列表项覆盖=" + knownNumber(coveredItems) + "/"
                    + knownNumber(collectionItems)
                    + "，聚合章数=" + knownNumber(expectedChapters)
                    + "，已读屏数=" + knownNumber(screensRead)
                    + (fireObserved ? "，读取过程中发现火券证据" : "")
                    + (rowProblems.isEmpty() ? "" : "，未读全示例="
                    + String.join("；", rowProblems.subList(0, Math.min(3, rowProblems.size()))))
                    + (Texts.isBlank(stopReason) ? "" : "；" + stopReason);
        }
    }

    private static String knownNumber(int value) {
        return value < 0 ? "未知" : String.valueOf(value);
    }

    /** 相同数量可能藏着中间缺项，不能拿计数相等替代实际从第 0 项到末项的覆盖。 */
    static boolean completeCoverage(int itemCount, List<Integer> indices) {
        if (itemCount <= 0 || indices == null) return false;
        Set<Integer> unique = new HashSet<>();
        for (Integer index : indices) {
            if (index == null || index < 0 || index >= itemCount) return false;
            unique.add(index);
        }
        return unique.size() == itemCount;
    }

    // ===== 明细里的一条 =====

    /** 明细里的一条：某一章，某天，花了多少哪种券。数字读不到一律 -1，绝不用 0 顶替。 */
    public static final class Entry {

        /** 从 {@code tvDesc} 中间那个数字读出来的章号；-1＝认不出（原文留在 {@link #raw}）。 */
        public final int chapterNo;
        /** 章号前面那一段（卷名）；无印刷号时必须连同完整标题唯一对应目录。 */
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

        /** 是否认出作者的印刷章号；番外保持 -1，不能用全书位置冒充印刷号。 */
        public boolean known() {
            return chapterNo > 0;
        }

        /** 2026-09-14 番外无章号；其卷名和完整标题经目录唯一映射后也能作为章节身份。 */
        boolean hasChapterIdentity() {
            return !Texts.isBlank(title) && (known() || (chapterNo < 0 && !Texts.isBlank(volume)));
        }

        /** 明确出现「火券」就是硬错误；金额读不到不能把币种证据抹掉。 */
        public boolean fireSpent() {
            return "火券".equals(currency);
        }

        /**
         * 同一条在两屏之间会重复出现（滚动有重叠），拿完整事实去重。
         * 不能只用章号：同章若出现两条金额／币种／日期／标题不同的异常明细，必须都保留下来，
         * 让后面的恢复校验拒绝补账，而不是静默吞掉其中一条。
         */
        String key() {
            return (known() ? "#" + chapterNo : "raw")
                    + "|desc:" + norm(raw) + "|amount:" + amount
                    + "|currency:" + norm(currency) + "|date:" + norm(date);
        }

        public String describe() {
            return (Texts.isBlank(volume) ? "" : volume.trim() + " · ")
                    + (known() ? "卷内第" + chapterNo + "章" : hasChapterIdentity()
                    ? "无印刷章号" : "章号认不出")
                    + (Texts.isBlank(title) ? "" : "「" + title.trim() + "」")
                    + (amount >= 0 ? " " + amount + (currency == null ? "" : currency) : " 花费读不到")
                    + (Texts.isBlank(date) ? "" : " " + date.trim());
        }
    }

    /**
     * 解析明细里的一条。
     *
     * <p>{@code tvDesc} 实测原文：「世界线的变动，学生会长的恋爱 50 订婚事宜，梦玲失踪」
     * ＝ 卷名 + 空格 + 章号 + 空格 + 章标题。按从左到右的顺序取明确的「第N章」或独立章号，
     * 不能让正文里的数字或「第N章」抢在真实章号前面；新目录还允许「第68 投影」
     * 这种漏写章字的形式，两者都复用
     * {@link Texts#rowChapterNo} 与 {@link Texts#chapterTitle}。
     *
     * <p>2026-08-31 补的一条：章号后面那个空格<b>不保证有</b>。第71章那一条是
     * 「世界线的变动，学生会长的恋爱  71留宿之夜，夏优来访」—— 没有一节是纯数字。
     * 旧格式仍优先取纯数字节，避免卷名「2023年的番外」被当成第2023章；
     * 切不出纯数字节时再退一步，找第一个「数字紧跟着非数字」的节
     * （{@link #NO_GLUED_TO_TITLE}），数字是章号、后面是章标题。
     * 两条都不中就 {@code chapterNo=-1} —— 认不出就说认不出，绝不猜一个章号出来
     * （猜错会把「漏记」和「误挂」判反）。
     */
    public static Entry parseRow(String desc, String amountText, String currency, String date) {
        String raw = desc == null ? null : desc.trim();
        if (Texts.isBlank(raw)) {
            return new Entry(-1, null, null, parseAmount(amountText), trimToNull(currency),
                    date, raw);
        }
        String[] parts = norm(raw).split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].startsWith("第")) {
                String chapterRow = join(parts, i, parts.length);
                int no = Texts.rowChapterNo(chapterRow);
                if (no >= 0) {
                    return new Entry(no, join(parts, 0, i), Texts.chapterTitle(chapterRow),
                            parseAmount(amountText), trimToNull(currency), date, raw);
                }
            }
            if (!PURE_NUMBER.matcher(parts[i]).matches()) continue;
            // 未通过完整前缀识别的「第 68 卷」不能在这里退化成普通第68章。
            if (i > 0 && "第".equals(parts[i - 1])) continue;
            int no = Texts.parseCount(parts[i]);
            return new Entry(no, join(parts, 0, i), join(parts, i + 1, parts.length),
                    parseAmount(amountText), trimToNull(currency), date, raw);
        }
        for (int i = 0; i < parts.length; i++) {
            if (!NO_GLUED_TO_TITLE.matcher(parts[i]).matches()) continue;
            if (i > 0 && "第".equals(parts[i - 1])) continue;
            String chapterRow = join(parts, i, parts.length);
            int no = Texts.rowChapterNo(chapterRow);
            if (no < 0) continue;
            return new Entry(no, join(parts, 0, i), Texts.chapterTitle(chapterRow),
                    parseAmount(amountText), trimToNull(currency), date, raw);
        }
        return new Entry(-1, null, raw, parseAmount(amountText), trimToNull(currency),
                date, raw);
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
        return s == null ? "" : s.replaceAll("[\\s\\u00a0\\u3000]+", " ").trim();
    }

    /** 左边可能是带章号的目录行，右边已由 parseRow 提取成正文，只能精确比较，不能再次去数字。 */
    static boolean sameChapter(String ledgerTitle, String uiTitle) {
        if (Texts.isBlank(ledgerTitle) || Texts.isBlank(uiTitle)) return true; // 缺一边就谈不上对不上
        String title = norm(uiTitle);
        return norm(ledgerTitle).equals(title) || Texts.chapterTitle(ledgerTitle).equals(title);
    }

    private static String text(String s) {
        return Texts.isBlank(s) ? "读不到" : s.trim();
    }

    // ===== 逐章对账 =====

    /**
     * 拿明细页逐章去核账本。<b>纯函数</b> —— 这是「账本可不可信」的判据本身，
     * 而它在真机上要登一个号、走四次点按才看得到一次，不拆出来根本测不了。
     *
     * <p>2026-09-14 的真机误报明确了边界：按账号核实事实，真实跨号重复不再算问题。
     * <ul>
     *   <li><b>明细有、账本这个号没有</b>：本号漏记，由完整明细补回；其他号买过也不改变此事实。</li>
     *   <li><b>账本有、明细没有</b>：这一章被误挂给了这个号（2026-08-25 第49章那件事），
     *       它其实没人买，却会被永远跳过 —— 那就是漏订。<b>但</b>刚买完不到
     *       {@link #FRESH_MS} 的只报警：页脚自己写着「清单约5分钟更新一次」。</li>
     *   <li><b>号对上了名字对不上</b>：章号整体错位（作者插章／删章），接着买会买错章。</li>
     *   <li><b>明细或账本上出现火券</b>：用户不充值火券，这条硬约束被破坏就停。</li>
     *   <li><b>无印刷号</b>：必须以卷名和完整标题唯一对应目录；缺卷、同名不唯一或未读全时
     *       只报告未核实，不能签发购买凭证。</li>
     * </ul>
     *
     * @param novelRows 这本书上<b>所有号</b>的付费记录（免费章不算，它一分券都没花）；
     *                  各账号事实独立保留，跨号记录用于说明已发生的历史
     * @param now       现在的时间戳，判「刚买完还没同步」用
     */
    public static VoucherLedger.Audit reconcile(String who, long accountId, String bookTitle,
                                                List<Entry> ui, List<PurchaseRow> novelRows,
                                                long now) {
        return reconcile(who, accountId, bookTitle, ui, null, novelRows, now);
    }

    public static VoucherLedger.Audit reconcile(String who, long accountId, String bookTitle,
                                                List<Entry> ui, List<Chapter> chapters,
                                                List<PurchaseRow> novelRows, long now) {
        String head = who + " 在《" + bookTitle + "》上逐章核对：";
        List<PurchaseRow> mine = new ArrayList<>();
        List<PurchaseRow> others = new ArrayList<>();
        if (novelRows != null) {
            for (PurchaseRow p : novelRows) {
                if (p == null || !paid(p)) continue;
                if (p.accountId == accountId) mine.add(p);
                else others.add(p);
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
        if (chapters != null) {
            List<Entry> known = new ArrayList<>();
            int unknown = 0;
            String firstUnknown = null;
            for (Entry entry : ui) {
                if (entry != null && !entry.known() && entry.fireSpent()) {
                    return new VoucherLedger.Audit(false, true,
                            head + "无印刷章号的明细出现火券支付证据，必须先核实：" + entry.describe());
                }
                if (entry != null && entry.known() && !Texts.isBlank(entry.title)) {
                    known.add(entry);
                } else {
                    Entry identified = identifyUnnumbered(entry, chapters);
                    if (identified != null) known.add(identified);
                    else {
                        unknown++;
                        if (firstUnknown == null && entry != null) firstUnknown = entry.raw;
                    }
                }
            }
            RemoteLedgerRecovery.Resolution resolved = RemoteLedgerRecovery.resolveAll(head, known,
                    chapters);
            if (!resolved.ok) {
                return new VoucherLedger.Audit(true, false, head
                        + "有明细不能唯一对应本地目录，这一趟没能逐章核对：" + resolved.message);
            }
            return compareResolved(head, who, resolved.resolved, mine, others, now,
                    true, false, unknown, firstUnknown);
        }
        return compare(head, who, ui, mine, byChapterNo(others), now);
    }

    private static Map<Integer, PurchaseRow> byChapterNo(List<PurchaseRow> rows) {
        Map<Integer, PurchaseRow> result = new LinkedHashMap<>();
        for (PurchaseRow row : rows) result.put(row.chapterNo, row);
        return result;
    }

    private static boolean paid(PurchaseRow p) {
        return p.costVouchers > 0 || p.costCoupons > 0;
    }

    static VoucherLedger.Audit compareResolved(String head, String who,
                                                List<RemoteLedgerRecovery.Resolved> ui,
                                                List<PurchaseRow> mine,
                                                List<PurchaseRow> others,
                                                long now, boolean allowFreshMissing,
                                                boolean strictFacts, int unknown,
                                                String firstUnknown) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<Long, RemoteLedgerRecovery.Resolved> byId = new LinkedHashMap<>();
        if (ui == null) ui = java.util.Collections.emptyList();
        for (RemoteLedgerRecovery.Resolved item : ui) {
            if (item == null || item.entry == null || item.chapter == null
                    || item.chapter.id <= 0 || item.chapter.chapterNo <= 0) {
                problems.add("有一条明细没有解析到有效的本地章节");
                continue;
            }
            Entry e = item.entry;
            String chapter = "全书第" + item.chapter.chapterNo + "章";
            if (byId.put(item.chapter.id, item) != null) {
                problems.add(chapter + "出现了两条明细");
            }
            if (strictFacts && e.amount <= 0) {
                problems.add("明细里" + chapter + "的花费读不到");
            } else if (!strictFacts && e.amount <= 0) {
                warnings.add("明细里" + chapter + "的花费读不到");
            }
            if (!"代券".equals(e.currency)) {
                if (e.fireSpent() || strictFacts) {
                    problems.add("明细里" + chapter + "不是明确的代券支付");
                } else {
                    warnings.add("明细里" + chapter + "的币种读不到");
                }
            }
            if (RemoteLedgerRecovery.date(e.date) <= 0) {
                if (strictFacts) problems.add("明细里" + chapter + "的购买日期读不到");
                else warnings.add("明细里" + chapter + "的购买日期读不到");
            }
        }
        Map<Long, PurchaseRow> othersById = new LinkedHashMap<>();
        Set<String> otherAccountChapters = new HashSet<>();
        if (others != null) {
            for (PurchaseRow p : others) {
                if (p == null) {
                    problems.add("有一条其他账号的账本记录读不到");
                    continue;
                }
                if (!otherAccountChapters.add(p.accountId + ":" + p.chapterId)) {
                    problems.add("账本里全书第" + p.chapterNo + "章同一账号有两条付费记录");
                }
                othersById.put(p.chapterId, p);
            }
        }
        Map<Long, PurchaseRow> mineById = new LinkedHashMap<>();
        for (PurchaseRow p : mine) {
            if (mineById.put(p.chapterId, p) != null) {
                problems.add("账本里全书第" + p.chapterNo + "章有两条付费记录");
            }
        }
        for (RemoteLedgerRecovery.Resolved item : byId.values()) {
            Entry e = item.entry;
            PurchaseRow p = mineById.get(item.chapter.id);
            if (p == null) {
                PurchaseRow other = othersById.get(item.chapter.id);
                if (other != null) {
                    problems.add("菠萝包明细确认「" + who + "」订过全书第"
                            + item.chapter.chapterNo + "章，本号账本漏记（另有「"
                            + other.accountDisplayName() + "」的订阅记录）");
                } else {
                    problems.add("菠萝包说「" + who + "」买过全书第" + item.chapter.chapterNo
                            + "章（" + e.describe() + "），账本里一条都没有 —— 券扣了没记账");
                }
                continue;
            }
            if (!sameChapter(p.chapterTitle, e.title)) {
                problems.add("全书第" + p.chapterNo + "章名字对不上：账本「"
                        + text(p.chapterTitle) + "」／明细「" + text(e.title) + "」");
            }
            if (p.costCoupons > 0) problems.add("账本记着全书第" + p.chapterNo + "章花了 "
                    + p.costCoupons + " 火券");
            if (p.costVouchers <= 0) {
                if (strictFacts) problems.add("账本里全书第" + p.chapterNo + "章的代券花费读不到");
                else warnings.add("账本里全书第" + p.chapterNo + "章的代券花费读不到");
            } else if (e.amount > 0 && e.amount != p.costVouchers) {
                problems.add("全书第" + p.chapterNo + "章花费两边不一样：明细 " + e.amount
                        + " 代券／账本 " + p.costVouchers + " 代券");
            }
            long remoteDate = RemoteLedgerRecovery.date(e.date);
            if (p.purchasedAt <= 0) {
                if (strictFacts) problems.add("账本里全书第" + p.chapterNo + "章的购买日期读不到");
                else warnings.add("账本里全书第" + p.chapterNo + "章的购买日期读不到");
            } else if (strictFacts && remoteDate > 0
                    && !RemoteLedgerRecovery.sameDate(p.purchasedAt, remoteDate)) {
                problems.add("全书第" + p.chapterNo + "章购买日期两边不一样：明细 "
                        + text(e.date) + "／账本日期不是同一天");
            }
        }
        for (PurchaseRow p : mineById.values()) {
            if (byId.containsKey(p.chapterId)) continue;
            if (unknown > 0) {
                // 2026-09-14 番外身份不明时不能把「尚未对应上」说成服务器缺席，更不能据此删账。
                warnings.add("账本里全书第" + p.chapterNo + "章尚未对应明细；仍有未识别条目，不能判断是否缺席");
                continue;
            }
            long age = now - p.purchasedAt;
            if (allowFreshMissing && p.purchasedAt > 0 && age >= 0 && age < FRESH_MS) {
                warnings.add("账本里全书第" + p.chapterNo + "章是 "
                        + Math.max(1, age / 60_000) + " 分钟前买的、明细里还没有 —— "
                        + "清单约 5 分钟更新一次，先放过");
            } else {
                problems.add("账本把全书第" + p.chapterNo + "章挂在「" + who
                        + "」名下，菠萝包的订阅明细里却没有这一条 —— 会造成漏订");
            }
        }
        if (unknown > 0) {
            warnings.add("明细里有 " + unknown + " 条读不出章号，且不能按卷名和完整标题唯一对应目录（原文「"
                    + text(firstUnknown) + "」），这几条没参与比对");
        }
        if (!problems.isEmpty()) return new VoucherLedger.Audit(false, true, head + brief(problems));
        if (!warnings.isEmpty()) return new VoucherLedger.Audit(true, false,
                head + byId.size() + " 章比对完了，但" + brief(warnings));
        if (byId.isEmpty()) return new VoucherLedger.Audit(true, mineById.isEmpty(),
                mineById.isEmpty() ? head + "明细里一条都没有、账本也是 0 条付费记录 —— 对上了"
                        : head + "明细里一条都没读出来 —— 这一趟没能逐章核对");
        List<Integer> nos = new ArrayList<>();
        for (RemoteLedgerRecovery.Resolved item : byId.values()) nos.add(item.chapter.chapterNo);
        java.util.Collections.sort(nos);
        return new VoucherLedger.Audit(true, true, head + "逐章都对上了（"
                + chapterNos(nos) + "，共 " + byId.size() + " 章，全是代券）");
    }

    private static String chapterNos(List<Integer> nos) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(12, nos.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append('、');
            sb.append('第').append(nos.get(i)).append('章');
        }
        if (nos.size() > shown) sb.append("…");
        return sb.toString();
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
                    problems.add("菠萝包明细确认「" + who + "」订过第" + e.chapterNo
                            + "章，本号账本漏记（另有「" + other.accountDisplayName() + "」的订阅记录）");
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
            // 读不出章号的那几条没参与比对，所以「账本有、明细没有」有可能就是它没被认出来
            // （2026-08-31 第71章「71留宿之夜」就是这样把整趟订阅停掉的）。中止那句话必须
            // 自带这个线索，否则看日志的人会照着「漏订」去查一个根本不存在的问题。
            return new VoucherLedger.Audit(false, true, head + brief(problems)
                    + (unknown > 0 ? "（另有 " + unknown + " 条读不出章号，原文「"
                    + text(firstUnknown) + "」—— 那几条没参与比对，"
                    + "上面说的「漏订」有可能就是它）" : ""));
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

    /** 普通逐章核对只要能打开页面、识别正文；其余字段读不到时会明确警告。 */
    public static boolean configured(SelectorSet selectors) {
        return selectors != null && selectors.missing(ROUTINE_REQUIRED).isEmpty();
    }

    /** 自动补历史账必须能读到完整交易事实，少一个选择器都不写账。 */
    public static boolean configuredForRecovery(SelectorSet selectors) {
        return selectors != null && selectors.missing(RECOVERY_REQUIRED).isEmpty();
    }

    private static final String[] ROUTINE_REQUIRED = {
            Keys.SUBSCRIBED_DETAIL_READY, Keys.SUBSCRIBED_DETAIL_DESC};
    private static final String[] RECOVERY_REQUIRED = {
            Keys.SUBSCRIBED_DETAIL_READY, Keys.SUBSCRIBED_DETAIL_DESC,
            Keys.SUBSCRIBED_DETAIL_TIME, Keys.SUBSCRIBED_DETAIL_AMOUNT,
            Keys.SUBSCRIBED_DETAIL_CURRENCY};

    public static final String[] REQUIRED = RECOVERY_REQUIRED.clone();

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

    /**
     * 旧读取允许用停住的一屏结束只读核账；删除会让章节重新待购，所以这里必须拿到列表位置证据。
     * 不改变旧入口的返回语义，避免目录拆分时顺手改变已经过真机验证的普通读取。
     */
    public static ReadResult readWithEvidence(StepRunner r, NodeView bookRow, String bookTitle)
            throws StepRunner.StepFailure {
        return readWithEvidence(r, bookRow, bookTitle, null);
    }

    public static ReadResult readWithEvidence(StepRunner r, NodeView bookRow, String bookTitle,
                                               List<Chapter> chapters)
            throws StepRunner.StepFailure {
        return readWithEvidence(r, bookRow, bookTitle, chapters, -1);
    }

    public static ReadResult readWithEvidence(StepRunner r, NodeView bookRow, String bookTitle,
                                              List<Chapter> chapters, int expectedChapters)
            throws StepRunner.StepFailure {
        if (bookRow == null) return new ReadResult(null, false, false, false, false,
                -1, -1, -1, "没有可打开的目标书聚合行");
        r.clickNode("订阅清单里《" + bookTitle + "》那一整行（完整核对明细）", bookRow);
        r.sleepHuman();
        if (!opened(r)) {
            ReadResult failed = new ReadResult(null, false, false, false, false,
                    -1, -1, -1, "订阅明细页没打开，不能据此删除账本");
            r.log("  " + failed.describe());
            r.back();
            return failed;
        }
        ReadResult result = collectWithEvidence(new RunnerReader(r), chapters, expectedChapters);
        r.log("  " + result.describe());
        r.recordDiagnostic(result.complete() ? "订阅明细末屏" : "订阅明细读取未完整");
        r.back();
        return result;
    }

    private static boolean opened(StepRunner r) throws StepRunner.StepFailure {
        for (long waited = 0; waited <= READY_TIMEOUT; waited += 500) {
            if (!r.findAllOn(Keys.SUBSCRIBED_DETAIL_READY).isEmpty()) return true;
            r.waitMillis(500);
        }
        return false;
    }

    interface DetailReader {
        NodeView root() throws StepRunner.StepFailure;
        List<NodeView> findAllIn(NodeView subtree, String key);
        boolean scrollForward() throws StepRunner.StepFailure;
        /** 证据链只使用目标列表内有完成回调的短滑，默认没有这份能力。 */
        default boolean scrollWithinList(boolean forward) throws StepRunner.StepFailure { return false; }
        default StepRunner.CatalogScroll probeList(boolean forward) throws StepRunner.StepFailure {
            return StepRunner.CatalogScroll.UNAVAILABLE;
        }
        default void log(String message) { }
        default void diagnostic(String stage, NodeView root) { }
        void waitMillis(long millis) throws StepRunner.StepFailure;
        long now();
    }

    private static final class RunnerReader implements DetailReader {
        private final StepRunner runner;

        RunnerReader(StepRunner runner) {
            this.runner = runner;
        }

        @Override public NodeView root() throws StepRunner.StepFailure {
            return runner.activeRoot();
        }
        @Override public List<NodeView> findAllIn(NodeView subtree, String key) {
            return runner.findAllIn(subtree, key);
        }
        @Override public boolean scrollForward() throws StepRunner.StepFailure {
            return runner.scrollForward();
        }
        @Override public boolean scrollWithinList(boolean forward) throws StepRunner.StepFailure {
            // 只往下读：明细页也支持下拉刷新，向后的手势入口已被删除（见 StepRunner.scrollDetail）。
            if (!forward) return false;
            return runner.scrollDetailForward();
        }
        @Override public StepRunner.CatalogScroll probeList(boolean forward)
                throws StepRunner.StepFailure {
            return forward ? runner.probeDetailContainerForward() : runner.probeDetailContainerBackward();
        }
        @Override public void log(String message) { runner.log(message); }
        @Override public void diagnostic(String stage, NodeView root) {
            runner.recordDiagnostic(stage, root);
        }
        @Override public void waitMillis(long millis) throws StepRunner.StepFailure {
            runner.checkCancelled();
            runner.waitMillis(millis);
        }
        @Override public long now() {
            return runner.elapsedRealtime();
        }
    }

    private static final class CapturedRow {
        final String desc;
        final String amount;
        final String currency;
        final String bare;
        final int top;
        /** 只供本帧诊断读取原值；不参与签名、日期归属或完整性判断。 */
        final NodeView rowNode, amountNode, currencyNode;

        CapturedRow(String desc, String amount, String currency, int top,
                    NodeView rowNode, NodeView amountNode, NodeView currencyNode) {
            this.desc = desc;
            this.amount = amount;
            this.currency = currency;
            this.bare = norm(desc) + "|" + norm(amount) + "|" + norm(currency);
            this.top = top;
            this.rowNode = rowNode;
            this.amountNode = amountNode;
            this.currencyNode = currencyNode;
        }
    }

    private static final class CapturedDate {
        final String text;
        final int top;

        CapturedDate(String text, int top) {
            this.text = text;
            this.top = top;
        }
    }

    private static final class Screen {
        final List<CapturedRow> rows;
        final List<CapturedDate> dates;
        final List<String> bare;
        final List<Integer> tops;
        final String signature;

        Screen(List<CapturedRow> rows, List<CapturedDate> dates) {
            this.rows = rows;
            this.dates = dates;
            this.bare = new ArrayList<>();
            this.tops = new ArrayList<>();
            StringBuilder sig = new StringBuilder();
            for (CapturedRow row : rows) {
                bare.add(row.bare);
                tops.add(row.top);
                sig.append("row@").append(row.top).append(':').append(row.bare).append('\n');
            }
            for (CapturedDate date : dates) {
                sig.append("date@").append(date.top).append(':')
                        .append(norm(date.text)).append('\n');
            }
            this.signature = sig.toString();
        }
    }

    /** 同一次根快照里读取日期、正文、金额和币种，避免混合 RecyclerView 两个时刻的事实。 */
    private static Screen captureScreen(DetailReader r) throws StepRunner.StepFailure {
        NodeView root = r.root();
        return captureScreen(r, root);
    }

    private static Screen captureScreen(DetailReader r, NodeView root) {
        return captureScreen(r, root, false);
    }

    private static Screen captureScreen(DetailReader r, NodeView root, boolean requireInside) {
        List<NodeView> descs = visibleByTop(findAllIn(root, Keys.SUBSCRIBED_DETAIL_DESC, r));
        List<NodeView> dateNodes = visibleByTop(
                findAllIn(root, Keys.SUBSCRIBED_DETAIL_TIME, r));
        List<CapturedDate> dates = new ArrayList<>();
        for (NodeView date : dateNodes) {
            dates.add(new CapturedDate(!requireInside || inside(date, root) ? date.text() : null,
                    topOf(date)));
        }
        List<CapturedRow> rows = new ArrayList<>();
        for (NodeView desc : descs) {
            String descText = desc.text();
            int descTop = topOf(desc);
            NodeView row = rowOf(r, desc);
            NodeView amount = findIn(row, Keys.SUBSCRIBED_DETAIL_AMOUNT, r);
            NodeView currency = findIn(row, Keys.SUBSCRIBED_DETAIL_CURRENCY, r);
            rows.add(new CapturedRow(descText,
                    !requireInside || inside(amount, root) ? visibleText(amount) : null,
                    !requireInside || inside(currency, root) ? visibleText(currency) : null, descTop,
                    row, amount, currency));
        }
        return new Screen(rows, dates);
    }

    private static String visibleText(NodeView node) {
        return node != null && node.visible() && NodeMatcher.hasArea(node) ? node.text() : null;
    }

    /**
     * 同一完整屏幕指纹连续出现三次才采用。翻屏后还必须先离开旧指纹；这样不会把派发滚动后
     * 暂时没刷新的旧树，或短暂停住的空／残屏，当成已经加载完成的新屏。
     */
    private static Screen settledScreen(DetailReader r, String previousSignature)
            throws StepRunner.StepFailure {
        Screen screen = captureScreen(r);
        boolean changed = previousSignature == null
                || !previousSignature.equals(screen.signature);
        int matching = changed && complete(screen) ? 1 : 0;
        long deadline = r.now() + SETTLE_TIMEOUT;
        while (r.now() < deadline) {
            r.waitMillis(SETTLE_STEP);
            Screen again = captureScreen(r);
            boolean againChanged = previousSignature == null
                    || !previousSignature.equals(again.signature);
            boolean againComplete = complete(again);
            if (changed && againChanged && againComplete
                    && again.signature.equals(screen.signature)) {
                matching++;
            } else {
                matching = againChanged && againComplete ? 1 : 0;
            }
            screen = again;
            changed = againChanged;
            if (changed && matching >= SETTLE_MATCHES) return screen;
        }
        if (previousSignature != null && previousSignature.equals(screen.signature)) return screen;
        throw new StepRunner.StepFailure(StepRunner.Kind.TIMEOUT,
                "订阅明细屏幕在 " + SETTLE_TIMEOUT + "ms 内没有稳定下来");
    }

    /** 标题先出现时列表可能仍是短暂空树；空树不能证明服务器端确实没有交易。 */
    private static boolean complete(Screen screen) {
        return screen != null && !screen.rows.isEmpty();
    }

    private static List<NodeView> findAllIn(NodeView root, String key, DetailReader r) {
        return root == null ? new ArrayList<NodeView>() : r.findAllIn(root, key);
    }

    private static NodeView findIn(NodeView root, String key, DetailReader r) {
        if (root == null) return null;
        List<NodeView> hits = r.findAllIn(root, key);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** 一屏一屏收条目；日期是组标题，不保证位于交易卡片内部。 */
    private static List<Entry> collect(StepRunner r) throws StepRunner.StepFailure {
        return collect(new RunnerReader(r));
    }

    static List<Entry> collect(DetailReader r) throws StepRunner.StepFailure {
        List<Entry> found = new ArrayList<>();
        List<String> previousBare = new ArrayList<>();
        List<Integer> previousTops = new ArrayList<>();
        List<Entry> previousFacts = new ArrayList<>();
        String previousScreenSignature = null;
        boolean reachedEnd = false;
        for (int screen = 0; screen < MAX_SCROLLS; screen++) {
            Screen captured = settledScreen(r, previousScreenSignature);
            if (previousScreenSignature != null
                    && previousScreenSignature.equals(captured.signature)) {
                reachedEnd = true;
                break;
            }
            List<CapturedRow> rows = captured.rows;
            List<CapturedDate> dates = captured.dates;
            List<String> bare = captured.bare;
            List<Integer> tops = captured.tops;
            Overlap overlap = screen > 0
                    ? overlap(previousBare, previousTops, bare, tops) : null;
            int overlapLength = overlap == null ? 0 : overlap.length;
            String activeDate = null;
            boolean dateFromCurrentHeader = false;
            boolean dateGroupInvalid = false;
            int dateAt = 0;
            List<Entry> currentFacts = new ArrayList<>();
            for (int rowAt = 0; rowAt < rows.size(); rowAt++) {
                CapturedRow row = rows.get(rowAt);
                boolean leavingOverlap = overlap != null && rowAt == overlapLength;
                if (leavingOverlap && !dateFromCurrentHeader) {
                    activeDate = null;
                    dateGroupInvalid = false;
                }
                while (dateAt < dates.size() && dates.get(dateAt).top <= row.top) {
                    activeDate = trimToNull(dates.get(dateAt).text);
                    dateGroupInvalid = activeDate == null;
                    dateFromCurrentHeader = true;
                    dateAt++;
                }
                Entry previous = null;
                if (overlap != null && rowAt < overlapLength) {
                    int previousAt = overlap.previousStart + rowAt;
                    previous = previousAt >= 0 && previousAt < previousFacts.size()
                            ? previousFacts.get(previousAt) : null;
                }
                String date = activeDate;
                if (previous != null && !dateFromCurrentHeader && !dateGroupInvalid) {
                    date = previous.date;
                }
                Entry e = parseRow(row.desc, row.amount, row.currency, date);
                boolean sameOverlapFact = previous != null && previous.key().equals(e.key());
                currentFacts.add(sameOverlapFact ? previous : e);
                if (!sameOverlapFact) found.add(e);
            }
            previousBare = bare;
            previousTops = tops;
            previousFacts = currentFacts;
            previousScreenSignature = captured.signature;
            if (!r.scrollForward()) {
                reachedEnd = true;
                break;
            }
        }
        if (!reachedEnd) {
            throw new StepRunner.StepFailure(StepRunner.Kind.TIMEOUT,
                    "订阅明细翻了 " + MAX_SCROLLS + " 屏仍未确认到底，拒绝使用可能截断的账单");
        }
        return found;
    }

    private static final class FrameCoverage {
        int itemCount = -1;
        boolean valid;
        String reason;
        String listIdentity;
        NodeView list;
        final Set<Integer> visible = new TreeSet<>();
        final Set<Integer> classified = new TreeSet<>();
        final Map<Integer, Integer> rowIndexByTop = new LinkedHashMap<>();
        final Map<Integer, ItemEvidence> items = new TreeMap<>();
        /** 同一快照内的原始行引用，仅用于输出未分类行的可见金额／币种。 */
        final Map<Integer, NodeView> itemNodes = new TreeMap<>();
        final List<String> problems = new ArrayList<>();

        String signature() {
            return itemCount + ":" + valid + ":" + visible + ":" + classified + ":"
                    + rowIndexByTop + ":" + items + ":" + listIdentity + ":" + reason;
        }
    }

    private static final class ItemEvidence {
        final String kind, identity, date;
        final boolean whole, unknownDate;
        /**
         * 这一次观察落在列表视口的上下边缘。
         *
         * <p>2026-09-15 w9899 现场：被裁掉的行 frame 会被视口夹住 —— 行框顶边正好等于列表顶边，
         * 标题已经不在可见节点里，只剩右侧的「代券」。这种「bounds 看起来整行可见」不能当作
         * 「整行看得见却读不出来」，否则同一项在别的屏已经读全也会被抹掉覆盖证据。
         */
        final boolean clipped;
        final int top;

        ItemEvidence(String kind, String identity, String date, boolean whole,
                     boolean unknownDate, int top) {
            this(kind, identity, date, whole, unknownDate, false, top);
        }

        ItemEvidence(String kind, String identity, String date, boolean whole,
                     boolean unknownDate, boolean clipped, int top) {
            this.kind = kind;
            this.identity = identity;
            this.date = date;
            this.whole = whole;
            this.unknownDate = unknownDate;
            this.clipped = clipped;
            this.top = top;
        }

        @Override public String toString() {
            return kind + ":" + identity + ":" + date + ":" + whole + ":" + unknownDate
                    + (clipped ? ":屏边裁剪" : "") + "@" + top;
        }
    }

    private static final class EvidenceScreen {
        final Screen screen;
        final FrameCoverage coverage;
        final boolean ready;
        final boolean fireObserved;
        final String signature;
        final NodeView root;

        EvidenceScreen(Screen screen, FrameCoverage coverage, boolean ready, NodeView root,
                       boolean fireObserved) {
            this.screen = screen;
            this.coverage = coverage;
            this.ready = ready;
            this.fireObserved = fireObserved;
            this.root = root;
            this.signature = ready + ":" + screen.signature + "\n" + coverage.signature();
        }
    }

    private static final class StableEvidence {
        final EvidenceScreen captured;
        final boolean stable;
        final boolean fireObserved;

        StableEvidence(EvidenceScreen captured, boolean stable, boolean fireObserved) {
            this.captured = captured;
            this.stable = stable;
            this.fireObserved = fireObserved;
        }
    }

    private static EvidenceScreen captureEvidenceScreen(DetailReader r)
            throws StepRunner.StepFailure {
        return captureEvidenceScreen(r, r.root());
    }

    private static EvidenceScreen captureEvidenceScreen(DetailReader r, NodeView root) {
        FrameCoverage coverage = frameCoverage(r, root);
        Screen screen = captureScreen(r, coverage.list, true);
        boolean ready = !visibleByTop(findAllIn(root, Keys.SUBSCRIBED_DETAIL_READY, r)).isEmpty();
        return new EvidenceScreen(screen, coverage, ready, root,
                ready && fireInList(r, coverage.list));
    }

    /**
     * 只读传入的冻结树；探测器与运行日志复用实际读取器的同一套行分类和金额解析。
     * v1.1 的日志只有 6/15，无法说明哪个字段丢了，因此保留原节点值与被采信值的区别。
     */
    public static String evidenceSnapshot(NodeView root, SelectorSet set) {
        if (root == null || set == null) return "本快照不是订阅明细页（根节点或选择器不可用）";
        try {
            DetailReader reader = new SnapshotReader(set);
            EvidenceScreen frame = captureEvidenceScreen(reader, root);
            if (!frame.ready) return "本快照不是订阅明细页";
            return "=== 订阅明细行结构证据（只读） ===\n选择器来源=" + set.source()
                    + "\n单屏不能证明跨屏日期/完整性；以下 snapshot 行序只属于本快照，UI item index 从 0 起。\n"
                    + String.join("\n", frameEvidenceLines(reader, frame));
        } catch (RuntimeException failure) {
            return "订阅明细单屏诊断未能完整生成：" + failure.getClass().getSimpleName();
        }
    }

    /** 这个适配器没有活动窗口、滚动或等待能力，导出不可能触发一次新的页面读取。 */
    private static final class SnapshotReader implements DetailReader {
        final SelectorSet selectors;
        SnapshotReader(SelectorSet selectors) { this.selectors = selectors; }
        @Override public NodeView root() { throw new IllegalStateException("单屏导出不得另取根节点"); }
        @Override public boolean scrollForward() { throw new IllegalStateException("单屏导出不得滚动"); }
        @Override public void waitMillis(long millis) { throw new IllegalStateException("单屏导出不得等待"); }
        @Override public long now() { return 0; }
        @Override public List<NodeView> findAllIn(NodeView subtree, String key) {
            if (subtree != null) for (Selector selector : selectors.get(key)) {
                List<NodeView> hits = NodeMatcher.findAll(subtree, selector);
                if (!hits.isEmpty()) return hits;
            }
            return Collections.emptyList();
        }
    }

    private static final class DiagnosticRow {
        final int index, top;
        final CapturedRow captured;
        final ItemEvidence item;
        DiagnosticRow(int index, int top, CapturedRow captured, ItemEvidence item) {
            this.index = index;
            this.top = top;
            this.captured = captured;
            this.item = item;
        }
    }

    /** 只排列已经采到的分类结果，不能在诊断中补造另一套交易／装饰行判据。 */
    private static List<DiagnosticRow> diagnosticRows(EvidenceScreen frame) {
        List<DiagnosticRow> rows = new ArrayList<>();
        Set<Integer> represented = new HashSet<>();
        for (CapturedRow captured : frame.screen.rows) {
            Integer index = frame.coverage.rowIndexByTop.get(captured.top);
            rows.add(new DiagnosticRow(index == null ? -1 : index, captured.top, captured,
                    index == null ? null : frame.coverage.items.get(index)));
            if (index != null) represented.add(index);
        }
        for (Map.Entry<Integer, ItemEvidence> item : frame.coverage.items.entrySet()) {
            if (!represented.contains(item.getKey())) rows.add(new DiagnosticRow(item.getKey(),
                    item.getValue().top, null, item.getValue()));
        }
        Collections.sort(rows, (a, b) -> Integer.compare(a.top, b.top));
        return rows;
    }

    private static int snapshotOrdinal(EvidenceScreen frame, CapturedRow captured) {
        List<DiagnosticRow> rows = diagnosticRows(frame);
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).captured == captured) return i + 1;
        return -1;
    }

    private static List<String> frameEvidenceLines(DetailReader reader, EvidenceScreen frame) {
        List<String> lines = new ArrayList<>();
        FrameCoverage coverage = frame.coverage;
        lines.add("页面标记=" + frame.ready + "；列表项数=" + coverage.itemCount
                + "；位置证据有效=" + coverage.valid + "；可见位置=" + coverage.visible
                + "；已分类位置=" + coverage.classified + "；发现火券=" + frame.fireObserved
                + "；列表标识=" + evidenceText(coverage.listIdentity)
                + "；CollectionInfo.rowCount=" + coverage.itemCount
                + (Texts.isBlank(coverage.reason) ? "" : "；原因=" + coverage.reason));
        List<DiagnosticRow> rows = diagnosticRows(frame);
        for (int i = 0; i < rows.size(); i++) {
            DiagnosticRow row = rows.get(i);
            String prefix = "snapshot行序=" + (i + 1) + "；UI item index=" + row.index;
            try {
                ItemEvidence item = row.item;
                String kind = item == null ? "未关联到唯一列表项" : item.kind;
                NodeView itemNode = coverage.itemNodes.get(row.index);
                String raw = itemNode == null ? (row.captured == null ? null : row.captured.desc)
                        : fullText(itemNode);
                StringBuilder line = new StringBuilder(prefix).append("；classification=").append(kind)
                        .append("；行原文=").append(evidenceText(raw))
                        .append("；CollectionItemInfo.rowIndex=")
                        .append(itemNode == null ? -1 : itemNode.collectionRowIndex())
                        .append("；CollectionItemInfo.rowSpan=")
                        .append(itemNode == null ? -1 : itemNode.collectionRowSpan());
                if (row.captured != null) {
                    CapturedRow captured = row.captured;
                    Entry parsed = parseRow(captured.desc, captured.amount, captured.currency, null);
                    line.append("；rawDesc=").append(evidenceText(captured.desc))
                            .append("；rawAmount=").append(evidenceText(nodeText(captured.amountNode)))
                            .append("；parsedAmount=").append(parsed.amount)
                            .append("；金额未知原因=").append(fieldProblem(captured, coverage.list, false))
                            .append("；rawCurrency=").append(evidenceText(nodeText(captured.currencyNode)))
                            .append("；parsedCurrency=").append(evidenceText(parsed.currency))
                            .append("；币种未知原因=").append(fieldProblem(captured, coverage.list, true));
                } else {
                    line.append("；rawAmount=").append(rawMatches(reader, itemNode, Keys.SUBSCRIBED_DETAIL_AMOUNT))
                            .append("；parsedAmount=-1；rawCurrency=")
                            .append(rawMatches(reader, itemNode, Keys.SUBSCRIBED_DETAIL_CURRENCY))
                            .append("；parsedCurrency=<未知>；未解析原因=")
                            .append("unknown".equals(kind) ? "没有可读取的唯一交易标题" : "非交易行");
                }
                if (item != null) line.append("；整行可见=").append(item.whole)
                        .append("；组头日期=").append(evidenceText(item.date))
                        .append("；组头日期未读全=").append(item.unknownDate);
                String localDate = coverage.valid && row.index >= 0 && "transaction".equals(kind)
                        ? provenGroupDate(row.index, coverage.items) : null;
                line.append("；本屏连续区间日期=").append(evidenceText(localDate))
                        .append("（仅单屏，不证明跨屏日期或完整性）");
                lines.add(line.toString());
            } catch (RuntimeException failure) {
                lines.add(prefix + "；本行诊断读取异常=" + failure.getClass().getSimpleName());
            }
        }
        for (String problem : coverage.problems) lines.add("本屏结构问题=" + problem);
        return lines;
    }

    private static String fieldProblem(CapturedRow row, NodeView viewport, boolean currency) {
        NodeView node = currency ? row.currencyNode : row.amountNode;
        if (row.rowNode == null) return "未确认唯一交易行容器";
        if (node == null) return "选择器未命中";
        if (!node.visible()) return "节点不可见";
        if (!NodeMatcher.hasArea(node)) return "节点零面积";
        if (!inside(node, viewport)) return "节点被列表视口裁剪";
        if (Texts.isBlank(node.text())) return "节点文本为空";
        if (currency) return "代券".equals(trimToNull(row.currency))
                || "火券".equals(trimToNull(row.currency)) ? "无" : "币种无法确认";
        return parseAmount(row.amount) >= 0 ? "无" : "金额无法解析";
    }

    private static String nodeText(NodeView node) { return node == null ? null : node.text(); }

    private static String rawMatches(DetailReader reader, NodeView node, String key) {
        List<String> values = new ArrayList<>();
        for (NodeView hit : findAllIn(node, key, reader)) values.add(evidenceText(hit.text()));
        return values.isEmpty() ? "<未命中>" : values.size() == 1 ? values.get(0) : values.toString();
    }

    private static String evidenceText(String value) {
        return value == null ? "<未知>" : "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    private static void logFrameEvidence(DetailReader reader, EvidenceScreen frame, String stage) {
        reader.log("  " + stage + "（单屏原始观察，不证明完整性）");
        try {
            for (String line : frameEvidenceLines(reader, frame)) reader.log("    " + line);
        } catch (RuntimeException failure) {
            // 诊断不能改变读取判据；失效节点只使这一段输出不可用，不替代本次读取结果。
            reader.log("    本帧诊断未完整：" + failure.getClass().getSimpleName());
        }
    }

    private static String transactionFacts(Entry entry) {
        return entry == null ? "<未知>" : "原文=" + evidenceText(entry.raw)
                + "，金额=" + entry.amount + "，币种=" + evidenceText(entry.currency)
                + "，归属日期=" + evidenceText(entry.date);
    }

    private static String conflictingFacts(List<Entry> previous, Entry current) {
        List<String> facts = new ArrayList<>();
        if (previous != null) for (Entry entry : previous) {
            if (preferCompleteObservation(entry, current) == null) facts.add(transactionFacts(entry));
            if (facts.size() >= 3) break;
        }
        return "此前冲突观察=[" + String.join("；", facts) + "]；本次=[" + transactionFacts(current) + "]";
    }

    private static StableEvidence stableEvidenceScreen(DetailReader r, String previous)
            throws StepRunner.StepFailure {
        EvidenceScreen captured = captureEvidenceScreen(r);
        boolean fire = captured.fireObserved;
        int matching = captured.ready && complete(captured.screen) ? 1 : 0;
        long deadline = r.now() + SETTLE_TIMEOUT;
        while (r.now() < deadline) {
            r.waitMillis(SETTLE_STEP);
            EvidenceScreen again = captureEvidenceScreen(r);
            fire |= again.fireObserved;
            boolean usable = again.ready && complete(again.screen);
            matching = usable && again.signature.equals(captured.signature)
                    ? matching + 1 : usable ? 1 : 0;
            captured = again;
            if (matching >= SETTLE_MATCHES && !captured.signature.equals(previous)) {
                return new StableEvidence(captured, true, fire);
            }
        }
        // v1.1 的长明细需要在末屏做容器探测；稳定与到达末尾是两份独立证据。
        return new StableEvidence(captured, matching >= SETTLE_MATCHES, fire);
    }

    private static boolean fireInList(DetailReader reader, NodeView list) {
        // 标题或金额还没加载出来时，已显示的火券依然是直接证据，不能依赖交易行是否解析成功。
        for (NodeView currency : visibleByTop(findAllIn(list, Keys.SUBSCRIBED_DETAIL_CURRENCY, reader))) {
            if ("火券".equals(trimToNull(currency.text()))) return true;
        }
        return false;
    }

    /** 实际逐屏读取与 JVM 造树测试共用此入口，不能只测试手工设为 true 的完整性标志。 */
    static ReadResult collectWithEvidence(DetailReader r, List<Chapter> chapters, int expectedChapters)
            throws StepRunner.StepFailure {
        Map<Integer, Entry> records = new TreeMap<>();
        Map<Integer, List<Entry>> observedFacts = new TreeMap<>();
        Set<Integer> covered = new TreeSet<>();
        Set<Integer> unclassified = new TreeSet<>();
        Set<Integer> conflicting = new TreeSet<>();
        Map<Integer, ItemEvidence> itemFacts = new TreeMap<>();
        Map<Integer, String> classificationProblems = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        int itemCount = -1;
        boolean fromTop = false;
        boolean fire = false;
        boolean capturedCountChange = false;
        int stationary = 0;
        int unstableScreens = 0;
        String listIdentity = null;
        String previousSignature = null;
        for (int screenNo = 0; screenNo < MAX_SCROLLS; screenNo++) {
            StableEvidence stable = stableEvidenceScreen(r, previousSignature);
            fire |= stable.fireObserved;
            EvidenceScreen frame = stable.captured;
            logFrameEvidence(r, frame, "明细第 " + (screenNo + 1) + " 屏原始证据，稳定=" + stable.stable);
            if (!stable.stable) {
                String reason = !frame.ready ? "读取途中已不在订阅明细页"
                        : !frame.coverage.valid && !Texts.isBlank(frame.coverage.reason)
                        ? frame.coverage.reason
                        : previousSignature == null ? "明细首屏没有稳定下来，不能认作空清单"
                        : "滚动后画面尚未稳定，不能把暂时不动当作到底";
                // 2026-09-15 w9899 第二次尝试：一屏在 2 秒内没稳定就作废整轮，只读到 3 屏 10 条。
                // 首屏和「已经不在明细页」仍然当场停下；其余先原地补采两次，再不行就往前翻一屏
                // 接着读 —— 少读的项最后由「列表项覆盖」在结尾拦住，不会变成一份看似完整的清单。
                boolean firstScreen = previousSignature == null;
                int retries = 0;
                while (!stable.stable && retries < UNSTABLE_RETRIES && !firstScreen && frame.ready) {
                    retries++;
                    r.log("  明细第 " + (screenNo + 1) + " 屏未稳定，原地重读第 " + retries + " 次");
                    stable = stableEvidenceScreen(r, previousSignature);
                    fire |= stable.fireObserved;
                    frame = stable.captured;
                    logFrameEvidence(r, frame, "明细第 " + (screenNo + 1) + " 屏重读，稳定="
                            + stable.stable);
                }
                if (!stable.stable) {
                    if (firstScreen || !frame.ready || ++unstableScreens > UNSTABLE_SCREEN_LIMIT) {
                        return evidenceResult(records, fromTop, false, false, unclassified,
                                conflicting, itemCount, covered, reason, expectedChapters,
                                screenNo + 1, problems, fire);
                    }
                    r.log("  明细第 " + (screenNo + 1) + " 屏始终没稳定（" + reason
                            + "），这一屏不算证据，继续往下读");
                    if (!r.scrollWithinList(true)) {
                        return evidenceResult(records, fromTop, false, false, unclassified,
                                conflicting, itemCount, covered,
                                "列表内短滑未完成，不能据此确认到底", expectedChapters,
                                screenNo + 1, problems, fire);
                    }
                    continue;
                }
            }
            unstableScreens = 0;
            List<Entry> current = new ArrayList<>();
            for (CapturedRow row : frame.screen.rows) {
                current.add(parseRow(row.desc, row.amount, row.currency, null));
            }
            for (int i = 0; i < current.size(); i++) {
                Entry identified = identifyUnnumbered(current.get(i), chapters);
                if (identified != null) current.set(i, identified);
            }
            FrameCoverage coverage = frame.coverage;
            for (String observation : coverage.problems) r.log("  明细采集观察：" + observation);
            refreshClassificationProblems(coverage.items, covered, classificationProblems);
            problems.clear();
            problems.addAll(classificationProblems.values());
            r.log("  明细第 " + (screenNo + 1) + " 屏：集合项数 " + knownNumber(itemCount)
                    + " → " + knownNumber(coverage.itemCount) + "，可见位置=" + coverage.visible
                    + "，已分类=" + coverage.classified + "，交易=" + current.size()
                    + "，首行「" + (current.isEmpty() ? "" : text(current.get(0).raw))
                    + "」，末行「" + (current.isEmpty() ? "" : text(current.get(current.size() - 1).raw))
                    + "」；" + coverage.items);
            if (!coverage.valid || coverage.itemCount <= 0) {
                List<Entry> visible = records.isEmpty() ? current : new ArrayList<>(records.values());
                return new ReadResult(visible, true, fromTop, false, false, -1,
                        coverage.itemCount, covered.size(), Texts.isBlank(coverage.reason)
                        ? "列表没有提供可核实的完整项数和位置" : coverage.reason,
                        expectedChapters, screenNo + 1, coverage.problems, fire);
            }
            if (listIdentity != null && !listIdentity.equals(coverage.listIdentity)) {
                return evidenceResult(records, fromTop, false, false, unclassified, conflicting,
                        itemCount, covered, "读取途中明细容器改变，不能拼接两份明细",
                        expectedChapters, screenNo + 1, problems, fire);
            }
            listIdentity = coverage.listIdentity;
            if (itemCount >= 0 && itemCount != coverage.itemCount && !capturedCountChange) {
                r.diagnostic("订阅明细集合项数 " + itemCount + " 到 " + coverage.itemCount, frame.root);
                capturedCountChange = true;
            }
            if (itemCount >= 0 && coverage.itemCount < itemCount) {
                return evidenceResult(records, fromTop, false, false, unclassified, conflicting,
                        coverage.itemCount, covered, "集合项数缩小，可能换批或重排，需要独立重读",
                        expectedChapters, screenNo + 1, problems, fire);
            }
            if (itemCount >= 0 && coverage.itemCount > itemCount
                    && !hasConsistentOverlap(records, current, frame)) {
                return evidenceResult(records, fromTop, false, false, unclassified, conflicting,
                        coverage.itemCount, covered, "集合项数增长但没有可核实的交易重叠，不能确定是同一份清单",
                        expectedChapters, screenNo + 1, problems, fire);
            }
            itemCount = coverage.itemCount;
            if (screenNo == 0) {
                if (!coverage.visible.contains(0)) return new ReadResult(current, true, false,
                        false, false, -1, itemCount, 0, "首屏不是列表第 1 项，不能证明此前没有遗漏",
                        expectedChapters, 1, problems, fire);
                // 明细页明确支持下拉刷新；第 0 项已可见时不能为了验顶再派发下拉手势。
                // 原生反向动作只探这一容器，结合第 0 项与重复稳定事实确认首端。
                StepRunner.CatalogScroll probe = r.probeList(false);
                StableEvidence confirmed = stableEvidenceScreen(r, frame.signature);
                fire |= confirmed.fireObserved;
                fromTop = probe == StepRunner.CatalogScroll.BLOCKED
                        && confirmed.stable && frame.signature.equals(confirmed.captured.signature);
                if (!fromTop) {
                    logFrameEvidence(r, confirmed.captured, "明细首端核验未通过时的复核帧");
                    return new ReadResult(current, true, false, false, false,
                            -1, itemCount, 0, "第 1 项已出现，但同容器首端核验未通过（"
                            + probe + "）", expectedChapters, 1, problems, fire);
                }
                r.log("  明细首端已核实：第 1 项 + 同容器反向探测受阻 + 前后完整事实稳定");
                r.diagnostic("订阅明细首屏", frame.root);
            }
            String continuity = mergeItemEvidence(itemFacts, coverage.items);
            if (continuity != null) {
                r.log("  明细结构冲突：" + continuity);
                problems.add(continuity);
                return evidenceResult(records, fromTop, false, false, unclassified, conflicting,
                        itemCount, covered, continuity, expectedChapters, screenNo + 1, problems, fire);
            }
            for (Map.Entry<Integer, ItemEvidence> item : coverage.items.entrySet()) {
                int index = item.getKey();
                if (!"unknown".equals(item.getValue().kind)) {
                    covered.add(index);
                    unclassified.remove(index);
                } else if (item.getValue().clipped) {
                    // 2026-09-15 w9899：屏边裁剪的一次观察什么也不改 —— 它既不能证明这一项读不出来
                    // （滚到中间就能读），也不该抹掉别的屏已经取得的完整观察。
                } else if (item.getValue().whole || !covered.contains(index)) {
                    // v1.1 懒加载回归：完整可见却无法分类的现状，不能被历史覆盖数静默抹掉。
                    // 仅屏边裁剪仍可保留此前完整观察；整行未知须等后续独立可读观察恢复。
                    covered.remove(index);
                    unclassified.add(index);
                }
            }
            // 问题清单在覆盖度更新之后重算：这一屏刚补齐的项不能留着上一屏的旧账。
            refreshClassificationProblems(coverage.items, covered, classificationProblems);
            problems.clear();
            problems.addAll(classificationProblems.values());
            for (int rowAt = 0; rowAt < current.size(); rowAt++) {
                Entry entry = current.get(rowAt);
                Integer item = coverage.rowIndexByTop.get(frame.screen.rows.get(rowAt).top);
                if (item == null) {
                    return evidenceResult(records, fromTop, false, false, unclassified, conflicting,
                            itemCount, covered, "有交易行不能对应唯一的列表项位置",
                            expectedChapters, screenNo + 1, problems, fire);
                }
                // 有连续且完整分类的位置区间，才知道旧日期组头与这笔新交易之间没有漏掉另一个组头。
                // 零面积日期组头不进入可见日期列表；因此每一行都必须过这道闸，不能只检查空日期。
                String groupedDate = provenGroupDate(item, itemFacts);
                entry = new Entry(entry.chapterNo, entry.volume, entry.title,
                        entry.amount, entry.currency, groupedDate, entry.raw);
                current.set(rowAt, entry);
                r.log("    明细第 " + (screenNo + 1) + " 屏日期归属：snapshot行序="
                        + snapshotOrdinal(frame, frame.screen.rows.get(rowAt)) + "；UI item index=" + item
                        + "；" + transactionFacts(entry) + "；日期依据="
                        + (groupedDate == null ? "连续日期区间未能证实（组头不可读、裁剪或位置缺项）"
                        : "连续位置的已知组头，已通过本次读取的日期判据"));
                Entry previousEntry = records.get(item);
                Entry preferred = rememberCompatibleObservation(observedFacts, item, entry)
                        ? preferCompleteObservation(previousEntry, entry) : null;
                if (preferred == null) {
                    conflicting.add(item);
                    String conflict = "列表第 " + (item + 1) + " 项「" + text(entry.raw)
                            + "」身份、金额、币种或日期与此前冲突；"
                            + conflictingFacts(observedFacts.get(item), entry);
                    r.log("  " + conflict);
                    problems.add(conflict);
                }
                else records.put(item, preferred);
            }
            if (!conflicting.isEmpty()) {
                return evidenceResult(records, fromTop, false, false, unclassified, conflicting,
                        itemCount, covered, "同一位置的交易事实冲突，需要独立重读",
                        expectedChapters, screenNo + 1, problems, fire);
            }
            if (frame.signature.equals(previousSignature)) {
                stationary++;
                StepRunner.CatalogScroll probe = r.probeList(true);
                StableEvidence tail = stableEvidenceScreen(r, frame.signature);
                fire |= tail.fireObserved;
                if (!tail.stable) logFrameEvidence(r, tail.captured, "明细末端复核未稳定的原始帧");
                boolean unchanged = tail.stable && frame.signature.equals(tail.captured.signature);
                if (probe == StepRunner.CatalogScroll.BLOCKED && unchanged
                        && coverage.visible.contains(itemCount - 1)) {
                    String end = "已完成列表内短滑后末屏稳定，同容器向后探测受阻，末项已出现";
                    return evidenceResult(records, fromTop, true, false, unclassified, conflicting,
                            itemCount, covered, end, expectedChapters, screenNo + 1, problems, fire);
                }
                if (stationary >= 2 && unchanged) {
                    return evidenceResult(records, fromTop, false, false, unclassified, conflicting,
                            itemCount, covered, "连续两次短滑未推进，末端证据不足（容器探测=" + probe + "）",
                            expectedChapters, screenNo + 1, problems, fire);
                }
                // 探测动作本身可能推进；下一轮先读取它的新屏，不能再滑一次跳过交易。
                if (!unchanged) {
                    previousSignature = frame.signature;
                    continue;
                }
            } else {
                stationary = 0;
            }
            previousSignature = frame.signature;
            if (!r.scrollWithinList(true)) {
                return evidenceResult(records, fromTop, false, false, unclassified, conflicting,
                        itemCount, covered, "列表内短滑未完成，不能据此确认到底",
                        expectedChapters, screenNo + 1, problems, fire);
            }
        }
        return evidenceResult(records, fromTop, false, true, unclassified, conflicting,
                itemCount, covered, "明细达到 " + MAX_SCROLLS + " 屏上限，仍未完整覆盖列表",
                expectedChapters, MAX_SCROLLS, problems, fire);
    }

    private static ReadResult evidenceResult(Map<Integer, Entry> records, boolean fromTop,
                                            boolean end, boolean truncated,
                                            Set<Integer> unclassified, Set<Integer> conflicting,
                                            int itemCount, Set<Integer> covered, String reason,
                                            int expectedChapters, int screens, List<String> issues,
                                            boolean fire) {
        Set<Integer> unreadable = new HashSet<>(unclassified);
        unreadable.addAll(conflicting);
        Map<String, Integer> identities = new LinkedHashMap<>();
        for (Map.Entry<Integer, Entry> row : records.entrySet()) {
            Entry entry = row.getValue();
            if (entry.hasChapterIdentity()) {
                String identity = norm(entry.volume) + ":" + entry.chapterNo + ":" + norm(entry.title);
                Integer first = identities.put(identity, row.getKey());
                if (first != null) {
                    unreadable.add(first);
                    unreadable.add(row.getKey());
                    issues.add("列表第 " + (first + 1) + "、" + (row.getKey() + 1)
                            + " 项重复指向「" + text(entry.raw) + "」，不是同一位置的滚动重叠");
                }
            }
            if (!completeTransaction(row.getValue())) {
                unreadable.add(row.getKey());
                issues.add("列表第 " + (row.getKey() + 1) + " 项「" + text(row.getValue().raw)
                        + "」缺少 " + missingFacts(row.getValue()));
            }
        }
        for (Integer index : unclassified) issues.add("列表第 " + (index + 1) + " 项尚未确定行类型");
        if (itemCount > 0 && covered.size() != itemCount) {
            reason += "；列表项只覆盖 " + covered.size() + "/" + itemCount + "，不能证明中间无遗漏";
        }
        if (expectedChapters >= 0 && records.size() != expectedChapters) {
            reason += "；逐章读到 " + records.size() + " 条，聚合为 " + expectedChapters + " 章";
        }
        return new ReadResult(new ArrayList<>(records.values()), true, fromTop, end, truncated,
                unreadable.size(), itemCount, covered.size(), reason, expectedChapters, screens,
                new ArrayList<>(new java.util.LinkedHashSet<>(issues)), fire);
    }

    private static String missingFacts(Entry entry) {
        List<String> missing = new ArrayList<>();
        if (!entry.hasChapterIdentity()) missing.add("章节身份");
        if (entry.amount < 0) missing.add("金额");
        if (!"代券".equals(entry.currency) && !"火券".equals(entry.currency)) missing.add("币种");
        if (RemoteLedgerRecovery.date(entry.date) <= 0) missing.add("日期");
        return String.join("、", missing);
    }

    private static boolean hasConsistentOverlap(Map<Integer, Entry> records, List<Entry> current,
                                                EvidenceScreen frame) {
        int anchors = 0;
        Set<String> identities = new HashSet<>();
        for (int i = 0; i < current.size(); i++) {
            Integer index = frame.coverage.rowIndexByTop.get(frame.screen.rows.get(i).top);
            Entry before = index == null ? null : records.get(index);
            Entry after = current.get(i);
            if (before != null && before.hasChapterIdentity() && after.hasChapterIdentity()
                    && preferCompleteObservation(before, after) != null
                    && identities.add(norm(after.raw))) anchors++;
        }
        return anchors >= 2;
    }

    /**
     * 这一屏该报哪些「尚未确定行类型」。
     *
     * <p>屏边裁剪的行不算问题：它只是这一屏没看全，滚到中间就能读（见 {@link ItemEvidence#clipped}）。
     * 其余读不出来的行仍按老规矩记着，直到它被某一次完整观察覆盖。
     */
    private static void refreshClassificationProblems(Map<Integer, ItemEvidence> items,
                                                      Set<Integer> covered,
                                                      Map<Integer, String> problems) {
        for (Map.Entry<Integer, ItemEvidence> item : items.entrySet()) {
            ItemEvidence evidence = item.getValue();
            if ("unknown".equals(evidence.kind) && !evidence.clipped
                    && (evidence.whole || !covered.contains(item.getKey()))) {
                problems.put(item.getKey(), "列表第 " + (item.getKey() + 1)
                        + " 项「" + evidence.identity + "」尚未确定行类型");
            } else {
                problems.remove(item.getKey());
            }
        }
    }

    private static String mergeItemEvidence(Map<Integer, ItemEvidence> previous,
                                             Map<Integer, ItemEvidence> current) {
        for (Map.Entry<Integer, ItemEvidence> item : current.entrySet()) {
            ItemEvidence old = previous.get(item.getKey());
            ItemEvidence now = item.getValue();
            if (old != null && !"unknown".equals(old.kind) && now.clipped) {
                // 2026-09-15：屏边裁剪的重复观察不带任何新事实，不能污染已有的身份与日期链。
                continue;
            }
            if (old != null && !"unknown".equals(old.kind) && "unknown".equals(now.kind)) {
                // 已知交易→未知→日期行不能洗掉原交易身份；未知只暂时挡住覆盖和日期传递。
                if (now.whole || now.unknownDate) previous.put(item.getKey(),
                        new ItemEvidence(old.kind, old.identity, old.date, old.whole, true, now.top));
                continue;
            }
            if (old != null && !"unknown".equals(old.kind) && !"unknown".equals(now.kind)
                    && (!old.kind.equals(now.kind)
                    || (!Texts.isBlank(old.identity) && !Texts.isBlank(now.identity)
                    && !old.identity.equals(now.identity))
                    || (old.date != null && now.date != null && !old.date.equals(now.date)))) {
                return "列表第 " + (item.getKey() + 1) + " 项重排或内容变化：此前「"
                        + old.identity + "」（类型=" + old.kind + "，组头日期=" + evidenceText(old.date)
                        + "），本次「" + now.identity + "」（类型=" + now.kind
                        + "，组头日期=" + evidenceText(now.date) + "）";
            }
            if (old == null || "unknown".equals(old.kind)
                    || (now.whole && !now.unknownDate)
                    || (!old.whole && now.date != null)) {
                previous.put(item.getKey(), old != null && now.date == null && old.date != null
                        && old.kind.equals(now.kind)
                        ? new ItemEvidence(now.kind, now.identity, old.date, now.whole,
                        now.unknownDate, now.top) : now);
            } else if (now.unknownDate && old != null) {
                previous.put(item.getKey(), new ItemEvidence(old.kind, old.identity, old.date,
                        old.whole, true, now.top));
            }
        }
        return null;
    }

    private static String provenGroupDate(int item, Map<Integer, ItemEvidence> evidence) {
        for (int index = item; index >= 0; index--) {
            ItemEvidence observed = evidence.get(index);
            if (observed == null || observed.unknownDate || "unknown".equals(observed.kind)) return null;
            if (observed.date != null) return observed.date;
            if (!observed.whole || !"transaction".equals(observed.kind)) return null;
        }
        return null;
    }

    /**
     * 残缺观察不用于拼出完整交易，但它读到的已知金额仍能否定后来矛盾的观察。
     * v1.1 逐屏回归：先金额未知，再 10 券但日期未知，最后 20 券且完整，不能遗忘中间的 10。
     */
    private static boolean rememberCompatibleObservation(Map<Integer, List<Entry>> observations,
                                                          int index, Entry current) {
        List<Entry> seen = observations.get(index);
        if (seen == null) {
            seen = new ArrayList<>();
            observations.put(index, seen);
        }
        boolean repeated = false;
        for (Entry previous : seen) {
            if (preferCompleteObservation(previous, current) == null) return false;
            repeated |= previous.key().equals(current.key());
        }
        if (!repeated) seen.add(current);
        return true;
    }

    /** 同一列表项可以在下一屏完整露出，但不能把两次冲突的金额或日期拼成一笔看似完整的交易。 */
    static Entry preferCompleteObservation(Entry before, Entry after) {
        if (before == null) return after;
        if (after == null) return null;
        if (before.key().equals(after.key())) return before;
        boolean blankBefore = Texts.isBlank(before.raw) && !before.hasChapterIdentity();
        boolean blankAfter = Texts.isBlank(after.raw) && !after.hasChapterIdentity();
        if ((!blankBefore && !blankAfter && (before.chapterNo != after.chapterNo
                || !norm(before.raw).equals(norm(after.raw))
                || !norm(before.volume).equals(norm(after.volume))
                || !norm(before.title).equals(norm(after.title))))
                || (before.amount >= 0 && after.amount >= 0 && before.amount != after.amount)
                || (!Texts.isBlank(before.currency) && !Texts.isBlank(after.currency)
                && !norm(before.currency).equals(norm(after.currency)))
                || (!Texts.isBlank(before.date) && !Texts.isBlank(after.date)
                && !norm(before.date).equals(norm(after.date)))) return null;
        if (completeTransaction(before)) return before;
        if (completeTransaction(after)) return after;
        return before;
    }

    static boolean completeTransaction(Entry entry) {
        return entry != null && entry.hasChapterIdentity()
                && entry.amount >= 0 && ("代券".equals(entry.currency) || "火券".equals(entry.currency))
                && RemoteLedgerRecovery.date(entry.date) > 0;
    }

    /** 2026-09-14 番外没有印刷号；先取得目录中的卷名与完整标题证据，再让它参与完整性检查。 */
    static Entry identifyUnnumbered(Entry entry, List<Chapter> chapters) {
        if (entry == null || entry.known() || chapters == null) return null;
        RemoteLedgerRecovery.Resolution resolved = RemoteLedgerRecovery.resolveAll("",
                Collections.singletonList(entry), chapters);
        return resolved.ok && resolved.resolved.size() == 1 ? resolved.resolved.get(0).entry : null;
    }

    private static FrameCoverage frameCoverage(DetailReader r, NodeView root) {
        FrameCoverage coverage = new FrameCoverage();
        List<NodeView> lists = new ArrayList<>();
        findDetailLists(root, lists, 0);
        if (lists.size() != 1) {
            coverage.reason = "未找到唯一的明细列表容器";
            return coverage;
        }
        NodeView list = lists.get(0);
        coverage.list = list;
        coverage.listIdentity = list.viewId() + ":" + list.className() + ":"
                + java.util.Arrays.toString(list.boundsInScreen());
        try {
            coverage.itemCount = list.collectionRowCount();
            if (coverage.itemCount <= 0) {
                coverage.reason = "明细列表未提供有效总项数，不能把空树或停住的一屏当完整清单";
                return coverage;
            }
            String className = list.className();
            if (!list.enabled() || !NodeMatcher.hasArea(list) || list.collectionColumnCount() > 1
                    || className == null || !(className.endsWith("ListView")
                    || className.endsWith("RecyclerView")) || className.contains("Horizontal")) {
                coverage.reason = "明细列表区域无效或不是唯一纵向列表";
                return coverage;
            }
            coverage.valid = true;
            for (int i = 0; i < list.childCount(); i++) {
                collectIndexedItems(r, list.child(i), coverage, 0);
            }
            if (coverage.visible.isEmpty()) {
                coverage.valid = false;
                coverage.reason = "明细列表没有提供可见项的位置编号";
            }
            int previousIndex = -1;
            List<Map.Entry<Integer, ItemEvidence>> visual = new ArrayList<>(coverage.items.entrySet());
            Collections.sort(visual, (a, b) -> Integer.compare(a.getValue().top, b.getValue().top));
            for (Map.Entry<Integer, ItemEvidence> item : visual) {
                if (item.getKey() <= previousIndex) {
                    coverage.valid = false;
                    coverage.reason = "可见列表项的位置编号未随纵向顺序递增";
                    break;
                }
                previousIndex = item.getKey();
            }
        } catch (RuntimeException failure) {
            coverage.valid = false;
            coverage.reason = "读取明细列表位置失败：" + failure.getClass().getSimpleName();
        }
        return coverage;
    }

    private static void findDetailLists(NodeView node, List<NodeView> found, int depth) {
        if (node == null || depth > 40 || !node.visible()) return;
        String id = node.viewId();
        if (id != null && (id.equals("baseListView") || id.endsWith("/baseListView"))) {
            found.add(node);
            return;
        }
        for (int i = 0; i < node.childCount(); i++) findDetailLists(node.child(i), found, depth + 1);
    }

    private static void collectIndexedItems(DetailReader r, NodeView item,
                                            FrameCoverage coverage, int depth) {
        if (item == null || !item.visible() || !NodeMatcher.hasArea(item)) return;
        if (depth > 16) {
            coverage.valid = false;
            coverage.reason = "列表项层级无法完整读取";
            return;
        }
        int index = item.collectionRowIndex();
        if (index < 0) {
            // 2026-09-14 用户截图明确有这个说明；它只作装饰行，绝不拿文字本身证明到底。
            if (knownFooter(r, item)) {
                return;
            }
            int before = coverage.visible.size();
            for (int i = 0; i < item.childCount(); i++) {
                collectIndexedItems(r, item.child(i), coverage, depth + 1);
            }
            if (coverage.visible.size() == before) {
                coverage.valid = false;
                coverage.reason = "有可见列表项没有位置编号，不能确认是否漏读";
                coverage.problems.add("无位置编号的可见项「" + fullText(item) + "」");
            }
            return;
        }
        if (index >= coverage.itemCount || item.collectionRowSpan() != 1
                || !coverage.visible.add(index)) {
            coverage.valid = false;
            coverage.reason = "列表项位置越界、重复或跨度不明";
            return;
        }
        coverage.itemNodes.put(index, item);
        boolean whole = inside(item, coverage.list);
        List<NodeView> allDescs = findAllIn(item, Keys.SUBSCRIBED_DETAIL_DESC, r);
        List<NodeView> descs = visibleByTop(allDescs);
        String date = null;
        boolean unknownDate = false;
        List<NodeView> allDates = findAllIn(item, Keys.SUBSCRIBED_DETAIL_TIME, r);
        for (NodeView time : allDates) {
            if (!time.visible()) {
                // 完整可见卡片里的 GONE 日期不作来源，残留文本也不是新组头；裁剪时无法作此判断。
                if (!whole) unknownDate = true;
                continue;
            }
            if (!NodeMatcher.hasArea(time)) {
                unknownDate = true;
                continue;
            }
            String value = trimToNull(time.text());
            if (RemoteLedgerRecovery.date(value) <= 0 || !inside(time, coverage.list)
                    || (descs.size() == 1 && topOf(time) > topOf(descs.get(0)))
                    || (date != null && !date.equals(value))) unknownDate = true;
            else date = value;
        }
        // 组头必须在正文之前。零面积／次序异常的日期不能混进随后可恢复的已知事实。
        if (unknownDate) date = null;
        if (descs.size() == 1) {
            Integer previous = coverage.rowIndexByTop.put(topOf(descs.get(0)), index);
            if (previous != null) {
                coverage.valid = false;
                coverage.reason = "多条交易行的位置重叠，不能唯一对应列表项";
                return;
            }
            coverage.classified.add(index);
            coverage.items.put(index, new ItemEvidence("transaction", norm(descs.get(0).text()),
                    date, whole, unknownDate, topOf(item)));
            return;
        }
        if (allDescs.isEmpty() && date != null && !unknownDate) {
            coverage.classified.add(index);
            coverage.items.put(index, new ItemEvidence("date", date, date, whole, false, topOf(item)));
        } else if (descs.isEmpty() && knownFooter(r, item)) {
            coverage.classified.add(index);
            coverage.items.put(index, new ItemEvidence("footer", fullText(item), null,
                    whole, false, topOf(item)));
        } else {
            // v1.1 第三次修复：真机被裁掉的行 frame 会被视口夹住（行框顶边＝列表顶边），
            // 只看 bounds 会把它当成「整行可见却读不出来」，于是同一项在别的屏读全了也被抹掉。
            // 零面积标题仍按懒加载处理，不能混进「屏边裁剪」这条豁免里。
            boolean clipped = !whole || clippedAtViewportEdge(item, coverage.list);
            coverage.items.put(index, new ItemEvidence("unknown", fullText(item), null,
                    whole && !clipped, unknownDate, clipped, topOf(item)));
            coverage.problems.add("列表第 " + (index + 1) + " 项「" + fullText(item)
                    + "」未分类：" + (clipped ? "屏边裁剪，等它滚到中间再读"
                    : "交易标题=" + descs.size())
                    + "，日期未读全=" + unknownDate + "，整行可见=" + (whole && !clipped));
        }
    }

    /**
     * 行框贴着列表视口的上下边界。被视口夹过的 bounds 与整行可见无法区分，
     * 只有贴着边界的这一种情形能确认是「这一次没看全」，因此不能据此改写覆盖证据。
     */
    private static boolean clippedAtViewportEdge(NodeView item, NodeView viewport) {
        if (item == null || viewport == null
                || !NodeMatcher.hasArea(item) || !NodeMatcher.hasArea(viewport)) return false;
        int[] a = item.boundsInScreen(), b = viewport.boundsInScreen();
        return a[1] <= b[1] || a[3] >= b[3];
    }

    private static boolean inside(NodeView node, NodeView viewport) {
        if (node == null || viewport == null || !NodeMatcher.hasArea(node)) return false;
        int[] a = node.boundsInScreen(), b = viewport.boundsInScreen();
        return a[0] >= b[0] && a[1] >= b[1] && a[2] <= b[2] && a[3] <= b[3];
    }

    private static boolean knownFooter(DetailReader r, NodeView node) {
        if (!findAllIn(node, Keys.SUBSCRIBED_DETAIL_DESC, r).isEmpty()
                || !findAllIn(node, Keys.SUBSCRIBED_DETAIL_CURRENCY, r).isEmpty()) return false;
        return "清单约5分钟更新一次，可下拉刷新".equals(fullText(node).replaceAll("\\s+", ""));
    }

    private static String fullText(NodeView node) {
        StringBuilder result = new StringBuilder();
        appendFullText(node, result, 0);
        return result.toString().trim();
    }

    private static void appendFullText(NodeView node, StringBuilder result, int depth) {
        if (node == null || depth > 16 || !node.visible()) return;
        if (!Texts.isBlank(node.text())) result.append(node.text()).append(' ');
        for (int i = 0; i < node.childCount(); i++) appendFullText(node.child(i), result, depth + 1);
    }

    private static List<NodeView> visibleByTop(List<NodeView> source) {
        List<NodeView> out = new ArrayList<>();
        if (source != null) {
            for (NodeView node : source) {
                if (node != null && node.visible() && NodeMatcher.hasArea(node)) out.add(node);
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<NodeView>() {
            @Override public int compare(NodeView a, NodeView b) {
                return Integer.compare(topOf(a), topOf(b));
            }
        });
        return out;
    }

    private static int topOf(NodeView node) {
        return node.boundsInScreen()[1];
    }

    private static final class Overlap {
        final int previousStart;
        final int length;

        Overlap(int previousStart, int length) {
            this.previousStart = previousStart;
            this.length = length;
        }
    }

    /**
     * 当前屏前缀和前一屏某段至少有两条不同且各自唯一的卡片，且对应卡都确实向上移动，
     * 才把它们认作同一次列表滚动的重叠段。起点和实际匹配长度一起返回，不能把段后的新卡
     * 误当成前一屏尾部的旧卡。
     */
    private static Overlap overlap(List<String> previous, List<Integer> previousTops,
                                   List<String> current, List<Integer> currentTops) {
        int max = Math.min(previous.size(), current.size());
        for (int length = max; length >= 2; length--) {
            int match = -1;
            for (int start = 0; start + length <= previous.size(); start++) {
                boolean valid = true;
                java.util.Set<String> anchors = new java.util.HashSet<>();
                for (int i = 0; i < length; i++) {
                    String identity = previous.get(start + i);
                    if (!identity.equals(current.get(i))
                            || currentTops.get(i) >= previousTops.get(start + i)
                            || uniqueIndex(previous, identity) != start + i
                            || uniqueIndex(current, identity) != i) {
                        valid = false;
                        break;
                    }
                    anchors.add(identity);
                }
                if (!valid || anchors.size() < 2) continue;
                if (match >= 0) return null;
                match = start;
            }
            if (match >= 0) return new Overlap(match, length);
        }
        return null;
    }

    private static int uniqueIndex(List<String> values, String value) {
        int found = -1;
        for (int i = 0; i < values.size(); i++) {
            if (!value.equals(values.get(i))) continue;
            if (found >= 0) return -1;
            found = i;
        }
        return found;
    }

    /**
     * 从 {@code tvDesc} 往上走到「这一条」。
     *
     * <p>判据是「这一层里的币种 TextView 正好一个」：金额和币种都没有 resource-id，
     * 整屏上每一条都各有一个「20」和一个「代券」—— 命中好几个就说明已经走到整张列表上去了，
     * 那时候读到的金额是别人的。宁可返回 null（这一条只报「读不到花费」），也不读错。
     */
    static NodeView rowOf(StepRunner r, NodeView desc) {
        return rowOf(new RunnerReader(r), desc);
    }

    static NodeView rowOf(DetailReader r, NodeView desc) {
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
