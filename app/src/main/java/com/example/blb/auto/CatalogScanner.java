package com.example.blb.auto;

import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把「选择章节」页整本书的章节顺序读出来。
 *
 * <p>这一步是自动订阅能不能用的前提。以前必须先在 App 里手工登记章节，章节表是空的，
 * 自动订阅每轮只会打一句「没有待订阅的章节」然后什么都不做 —— 612 章手工登记是不可能的。
 * 现在改成对着真实界面扫：行文本就是章节名，行的先后顺序就是章号。
 *
 * <p>顺带把「这个号有没有这一章」也读回来（{@link ChapterRowState}）：行上带「已下载」、
 * 或者干脆没有锁（免费章）＝已经能看；有锁又没有「已下载」＝要花券。这是唯一不用花钱就能
 * 知道各账号已订阅到哪的办法，也是「8 个号合起来拼出完整一本」这件事的账面依据。
 *
 * <p><b>刻意不按锁判断</b>：付费章买完之后锁只是从「锁上的锁」变成「打开的锁」，节点还在
 * （2026-08-24 实测），按锁数会把刚买到的章漏算成「还没买」。</p>
 *
 * <p><b>为什么敢说没漏行</b>：这一页每行都把自己的标号印在文本里（「67   周日工作」），
 * 所以扫完之后可以逐对检查「标号是不是一个接一个」。翻页要是跳过了一屏，标号链上立刻出现
 * 缺口，{@link Result#gapNote} 就会带着缺口位置返回，调用方据此拒绝写账本 ——
 * 宁可这一轮什么都不买，也不能拿一份漏了行的账本去决定「下一章该买哪一章」。
 */
public final class CatalogScanner {

    /** 默认最多往下翻多少屏。一屏 13～15 行，够扫 600 多章。 */
    public static final int DEFAULT_MAX_SCROLLS = 60;

    /**
     * 读一屏之前最多等它画完多久。
     *
     * <p>2026-08-24 15:26 实测：第 4 个号那一趟刚按完「回到顶部」就去读，那一瞬间列表只铺出
     * 4 行，翻一屏之后第一行已经是标号 13 —— {@link #findGap} 立刻报「缺 8 行」，那个号一章
     * 都没买。缺口护栏拦得对，但根因是<b>读得太早</b>，不是真的漏了行。
     */
    private static final long SETTLE_TIMEOUT = 2_000;
    private static final long SETTLE_STEP = 250;

    /** 扫到的一行。 */
    public static final class Row {
        /** 行文本原样（含行首标号），既当章节标题，也是回头在页面上找这一行的钥匙。 */
        public final String title;
        /** 行首印着的标号；-1 表示这一行没有标号。 */
        public final int printedNo;
        /** 这一行的三个标记，以及由它们得出的「要不要花券、能不能记账」那几句结论。 */
        public final ChapterRowState state;

        public Row(String title, int printedNo, boolean lock, boolean downloaded,
                   boolean selectable) {
            this(title, printedNo, ChapterRowState.of(lock, downloaded, selectable));
        }

        Row(String title, int printedNo, ChapterRowState state) {
            this.title = title;
            this.printedNo = printedNo;
            this.state = state;
        }
    }

    /** 扫描结果。 */
    public static final class Result {
        /** 章节行，按界面上从上到下的顺序；下标 +1 就是章号。 */
        public final List<Row> chapters = new ArrayList<>();
        /** 跳过的没有标号的行（卷标题、「作品相关」这类），原样留着写进日志。 */
        public final List<String> skipped = new ArrayList<>();
        /** 往下翻了几屏，调用方据此把列表滚回去。 */
        public int scrolls;
        /** 翻到 maxScrolls 还没到底＝这本书没扫完。 */
        public boolean truncated;
        /** 标号链上的缺口；null 表示逐行连续、没漏。 */
        public String gapNote;

        /** 能不能拿这份结果去写账本。 */
        public boolean trustworthy() {
            return !chapters.isEmpty() && !truncated && gapNote == null;
        }

        /** 当前这个号还能花券买的章数（付费、还有勾选圈的那些）。 */
        public int buyableCount() {
            int n = 0;
            for (Row row : chapters) {
                if (row.state.buyable()) n++;
            }
            return n;
        }

        /** 免费章行数（没有锁）—— 这些是唯一能跨号推断「谁都看得到」的行。 */
        public int freeCount() {
            int n = 0;
            for (Row row : chapters) {
                if (row.state.free()) n++;
            }
            return n;
        }
    }

    private CatalogScanner() {
    }

    public static Result scan(StepRunner r) throws StepRunner.StepFailure {
        return scan(r, DEFAULT_MAX_SCROLLS);
    }

    /**
     * 从当前位置往下扫到底。调用方要先保证列表停在顶部（{@link #toTop}）。
     *
     * <p>翻页之间靠行文本去重：相邻两屏通常会重叠一两行，重复的行不会被记两次。
     * 判断「到底了」用的是「又翻一屏但一行新的都没有」，而不是滚动动作的返回值 ——
     * 实测 RecyclerView 到底之后 {@code ACTION_SCROLL_FORWARD} 还会返回 true。
     *
     * <p>每一屏都先等它<b>画稳</b>再读（见 {@link #settledRows}）：读一屏还在铺的列表会漏行，
     * 而漏行会被缺口护栏判成「目录没扫干净」，整个号一章都不买。
     */
    public static Result scan(StepRunner r, int maxScrolls) throws StepRunner.StepFailure {
        Result out = new Result();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; ; i++) {
            r.checkCancelled();
            int before = seen.size();
            readScreen(settledRows(r), r, seen, out);
            if (i > 0 && seen.size() == before) break; // 又翻一屏，一行新的都没有＝到底了
            if (i >= maxScrolls) {
                out.truncated = true;
                break;
            }
            if (!r.scrollForward()) break;
            out.scrolls++;
            r.sleepHuman();
        }
        out.gapNote = findGap(out.chapters);
        return out;
    }

    /**
     * 等这一屏画稳，返回稳下来的那批行。
     *
     * <p>「稳」的判据是连着两次读到的行文本一模一样。列表还在铺或者还在惯性滑动时，两次读到的
     * 不会一样；等超时了也照样返回手上这一批 —— 宁可读到一屏不全（缺口护栏会拦住），
     * 也不能让「等列表」把整趟卡死。
     */
    private static List<NodeView> settledRows(StepRunner r) throws StepRunner.StepFailure {
        List<NodeView> rows = r.findAllOn(Keys.CHAPTER_ROW_TITLE);
        String last = signature(rows);
        long deadline = System.currentTimeMillis() + SETTLE_TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            r.checkCancelled();
            r.waitMillis(SETTLE_STEP);
            List<NodeView> again = r.findAllOn(Keys.CHAPTER_ROW_TITLE);
            String now = signature(again);
            rows = again;
            if (now.equals(last)) return rows;
            last = now;
        }
        return rows;
    }

    /** 一屏的指纹：所有行文本按顺序拼起来。行数或内容一变，指纹就变。 */
    private static String signature(List<NodeView> rows) {
        StringBuilder sb = new StringBuilder();
        for (NodeView row : rows) {
            sb.append(row.text()).append('\n');
        }
        return sb.toString();
    }

    /** 读当前这一屏上的行，按 top 排好序再追加。 */
    private static void readScreen(List<NodeView> titles, StepRunner r, Set<String> seen,
                                   Result out) throws StepRunner.StepFailure {
        titles.sort(Comparator.comparingInt(CatalogScanner::topOf));
        for (NodeView title : titles) {
            String text = title.text();
            if (Texts.isBlank(text)) continue;
            text = text.trim();
            if (!seen.add(text)) continue;
            int no = Texts.rowChapterNo(text);
            if (no < 0) {
                // 卷标题行也带勾选框，勾下去等于勾整卷 —— 认出来、记下来、绝不当成章节。
                out.skipped.add(text);
                continue;
            }
            NodeView row = rowOf(title);
            out.chapters.add(new Row(text, no, ChapterRowState.read(r, row)));
        }
    }

    /** 行标题的可点击祖先就是整行；没有可点击祖先时退一层父节点，至少能查到锁。 */
    static NodeView rowOf(NodeView title) {
        NodeView row = NodeMatcher.clickableAncestorOf(title);
        if (row == title && title.parent() != null) return title.parent();
        return row;
    }

    private static int topOf(NodeView node) {
        int[] b = node.boundsInScreen();
        return b != null && b.length == 4 ? b[1] : 0;
    }

    /**
     * 检查标号链有没有缺口。分卷会各自从 1 重新排号，所以「掉回更小的数」算换卷、不算缺口；
     * 只有「往前跳」（13 → 20）才是翻页漏了行。
     */
    static String findGap(List<Row> chapters) {
        for (int i = 1; i < chapters.size(); i++) {
            int prev = chapters.get(i - 1).printedNo;
            int cur = chapters.get(i).printedNo;
            if (cur > prev + 1) {
                return "第 " + prev + " 行之后直接跳到了 " + cur
                        + "（缺 " + (cur - prev - 1) + " 行），翻页漏了内容";
            }
        }
        return null;
    }

    /**
     * 把列表滚回第一行。
     *
     * <p>先按页面右下角那颗「回到顶部」（{@code goto_top}）—— 600 多章靠一屏一屏往回滚要
     * 几十次。按完之后必须核对第一行是不是真的回到了扫描时的第一行：万一那颗键不是回到顶部
     * 而是别的什么，核对这一步会失败，再退回一屏一屏往回滚。
     */
    static void toTop(StepRunner r, String firstRowTitle, int scrolls)
            throws StepRunner.StepFailure {
        StepRunner.Outcome top = r.findAny(Keys.CHAPTER_LIST_TOP);
        if (top != null && r.pressOrLog("回到顶部", top.node)) {
            if (firstRowTitle == null || r.findExactText("第一行", firstRowTitle) != null) return;
            r.log("  按了「回到顶部」但第一行不是「" + firstRowTitle + "」，改成一屏一屏往回滚");
        }
        r.scrollToTop(Math.max(scrolls + 2, 4));
    }
}
