package com.example.blb.auto;

import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 先读普通目录的章节/分节角色，再与「选择章节」页逐位置核对并读取状态。
 *
 * <p>这一步是自动订阅能不能用的前提。以前必须先在 App 里手工登记章节，章节表是空的，
 * 自动订阅每轮只会打一句「没有待订阅的章节」然后什么都不做 —— 612 章手工登记是不可能的。
 * 现在改成对着真实界面扫：行文本就是章节名，行的先后顺序就是章号。
 *
 * <p>顺带读取免费、付费和本机下载标记（{@link ChapterRowState}）。下载文件由账号共用，
 * 不能据此推断买家；付费归属必须由订阅明细和账本核实。
 *
 * <p><b>刻意不按锁判断</b>：付费章买完之后锁只是从「锁上的锁」变成「打开的锁」，节点还在
 * （2026-08-24 实测），按锁数会把刚买到的章漏算成「还没买」。</p>
 *
 * <p>「第81章 心脏、第81章 沙滩、第83章 最后」是作者原文，不是列表序号。完整性靠
 * 相邻屏幕的重叠行和整行位置核对；只有实际看见两行相邻，才允许作者重号、跳号。
 * 无法接上的翻页、残缺行和未确认的结尾都会由 {@link Result#gapNote} 拦住。
 */
public final class CatalogScanner {

    /** 短滑保留重叠行，因此比整屏翻页预留更多次数。 */
    public static final int DEFAULT_MAX_SCROLLS = 120;

    /**
     * 读一屏之前最多等它画完多久。
     *
     * <p>2026-08-24 15:26 实测：第 4 个号那一趟刚按完「回到顶部」就去读，那一瞬间列表只铺出
     * 4 行，翻一屏之后第一行已经是标号 13 —— {@link #findGap} 立刻报「缺 8 行」，那个号一章
     * 都没买。缺口护栏拦得对，但根因是<b>读得太早</b>，不是真的漏了行。
     */
    private static final long SETTLE_TIMEOUT = 2_000;
    private static final long SETTLE_STEP = 250;
    private static final int SETTLE_MATCHES = 3;
    private static final int END_CONFIRMATIONS = 2;
    private static final int READ_RECOVERIES = 2;
    private static final int TOP_CLICK_LIMIT = 2;

    public enum RowKind { CHAPTER, SECTION, UNKNOWN }

    /**
     * 2026-09-14 真机选择页中「番外」和「恶鬼（上）」结构相同，勾选框、锁和高度都不能分章。
     * 没有普通目录证据时只保留原有的编号判据；无标号行必须列为未知，不能静默丢掉。
     */
    static RowKind classifyRow(int printedNo, boolean heading) {
        return printedNo >= 0 ? RowKind.CHAPTER : RowKind.UNKNOWN;
    }

    /** 普通目录的一行结构快照；只保存标量，不把会被系统复用的节点留到下一屏。 */
    public static final class DirectoryRowEvidence {
        public final String listId;
        public final String listClass;
        public final String rowId;
        public final String rowClass;
        public final boolean directListChild;
        public final boolean clickable;
        public final int childCount;
        public final boolean directTitleChild;
        public final String titleId;
        public final String titleClass;
        public final boolean heading;

        DirectoryRowEvidence(String listId, String listClass, String rowId, String rowClass,
                             boolean directListChild, boolean clickable, int childCount,
                             boolean directTitleChild, String titleId, String titleClass,
                             boolean heading) {
            this.listId = listId;
            this.listClass = listClass;
            this.rowId = rowId;
            this.rowClass = rowClass;
            this.directListChild = directListChild;
            this.clickable = clickable;
            this.childCount = childCount;
            this.directTitleChild = directTitleChild;
            this.titleId = titleId;
            this.titleClass = titleClass;
            this.heading = heading;
        }

        String signature() {
            return Arrays.asList(listId, listClass, rowId, rowClass, directListChild, clickable,
                    childCount, directTitleChild, titleId, titleClass, heading).toString();
        }
    }

    /**
     * 2026-09-14 用户补交的普通目录 dump 才给出了区别：list_view 的直接 RelativeLayout 行，
     * 唯一子节点为 title；layoutRoot 是分节，章行没有 row id。选择页没有这项区别，不能套用。
     * 不认标题关键词、像素或“已选 1 章”：只剩一章可选的卷也可能选中一章。
     */
    static RowKind classifyDirectoryRow(int printedNo, DirectoryRowEvidence evidence) {
        if (evidence == null || !evidence.directListChild || !evidence.clickable
                || !NodeMatcher.idMatches(evidence.listId, "list_view")
                || !classIs(evidence.listClass, "ListView")
                || !classIs(evidence.rowClass, "RelativeLayout")
                || evidence.childCount != 1 || !evidence.directTitleChild
                || !NodeMatcher.idMatches(evidence.titleId, "title")
                || !classIs(evidence.titleClass, "TextView")) return RowKind.UNKNOWN;
        // 显式分节证据优先，不能因作者把卷名写成“第N章…”就把整卷写进逐章账本。
        if (NodeMatcher.idMatches(evidence.rowId, "layoutRoot")) return RowKind.SECTION;
        if (evidence.rowId != null && !evidence.rowId.isEmpty()) return RowKind.UNKNOWN;
        return printedNo >= 0 || !evidence.heading ? RowKind.CHAPTER : RowKind.UNKNOWN;
    }

    private static boolean classIs(String actual, String simple) {
        return actual != null && (actual.equals(simple) || actual.equals("android.widget." + simple));
    }

    /** 扫到的一行。 */
    public static final class Row {
        /** 行文本原样（含行首标号），既当章节标题，也是回头在页面上找这一行的钥匙。 */
        public final String title;
        /** 行首印着的标号；-1 表示这一行没有标号。 */
        public final int printedNo;
        /** 扫描中实际确认的所属分节；读不到时为 null，不能按章节标题猜卷名。 */
        public final String volumeTitle;
        /** 这一行的三个标记，以及由它们得出的「要不要花券、能不能记账」那几句结论。 */
        public final ChapterRowState state;
        /** 角色来自普通目录，或在无目录证据的兼容扫描中仅来自行首编号。 */
        public final RowKind kind;
        public final DirectoryRowEvidence directoryEvidence;
        /** 当前这一次出现的章节与前一章之间，所有行都曾在同屏核实相邻。 */
        private final boolean verifiedAfterPrevious;
        private final boolean stateVerified;
        private final boolean complete;

        public Row(String title, int printedNo, boolean lock, boolean downloaded,
                   boolean selectable) {
            this(title, printedNo, ChapterRowState.of(lock, downloaded, selectable));
        }

        Row(String title, int printedNo, ChapterRowState state) {
            this(title, printedNo, state, false, true, null, RowKind.CHAPTER, null, true);
        }

        Row(String title, int printedNo, ChapterRowState state, String volumeTitle) {
            this(title, printedNo, state, false, true, volumeTitle, RowKind.CHAPTER, null, true);
        }

        private Row(String title, int printedNo, ChapterRowState state,
                    boolean verifiedAfterPrevious, boolean stateVerified, String volumeTitle,
                    RowKind kind, DirectoryRowEvidence directoryEvidence, boolean complete) {
            this.title = title;
            this.printedNo = printedNo;
            this.state = state;
            this.verifiedAfterPrevious = verifiedAfterPrevious;
            this.stateVerified = stateVerified;
            this.volumeTitle = volumeTitle;
            this.kind = kind;
            this.directoryEvidence = directoryEvidence;
            this.complete = complete;
        }
    }

    /** 扫描结果。 */
    public static final class Result {
        /** 章节行，按界面上从上到下的顺序；下标 +1 就是章号。 */
        public final List<Row> chapters = new ArrayList<>();
        /** 全部行的每一次出现，含分节和未知；两页对照必须保留数量与位置，绝不能按标题去重。 */
        public final List<Row> allRows = new ArrayList<>();
        /** 只有普通目录扫描可设为 true；选择页即使已经借用了目录角色，也不能冒充目录证据。 */
        public final boolean directoryScanned;
        /** 已取得普通目录结构证据的分节标题。 */
        public final List<String> skipped = new ArrayList<>();
        /** 没有充分分类证据的行；必须逐条展示，并阻止不完整目录覆盖账本。 */
        public final List<String> unresolved = new ArrayList<>();
        /** 当前扫描仍未补齐的逐行原因；与标题分开保存，不能改写用于两页对照的原文。 */
        public final List<String> rowProblems = new ArrayList<>();
        /** 已唯一对齐的同一行撤销了已见正标记；不能用一遍新的扫描清除这份冲突证据。 */
        public String stateConflict;
        /** 往下翻了几屏，调用方据此把列表滚回去。 */
        public int scrolls;
        public int endProbes;
        /** 翻到 maxScrolls 还没到底＝这本书没扫完。 */
        public boolean truncated;
        /** 未能核实的行、翻页或结尾；null 表示这些检查通过。 */
        public String gapNote;

        public Result() { this(false); }

        private Result(boolean directoryScanned) { this.directoryScanned = directoryScanned; }

        /** 能不能拿这份结果去写账本。 */
        public boolean trustworthy() {
            return !chapters.isEmpty() && !truncated && gapNote == null && unresolved.isEmpty()
                    && stateConflict == null;
        }

        /** 当前这个号还能花券买的章数（付费、还有勾选圈的那些）。 */
        public int buyableCount() {
            int n = 0;
            for (Row row : chapters) {
                if (row.stateVerified && row.state.buyable()) n++;
            }
            return n;
        }

        /** 免费章行数（没有锁）—— 这些是唯一能跨号推断「谁都看得到」的行。 */
        public int freeCount() {
            int n = 0;
            for (Row row : chapters) {
                if (row.stateVerified && row.state.free()) n++;
            }
            return n;
        }
    }

    private CatalogScanner() {
    }

    public static Result scan(StepRunner r) throws StepRunner.StepFailure {
        return scan(r, DEFAULT_MAX_SCROLLS);
    }

    public static Result scanDirectory(StepRunner r) throws StepRunner.StepFailure {
        return scanDirectory(r, DEFAULT_MAX_SCROLLS);
    }

    public static Result scanDirectory(StepRunner r, int maxScrolls) throws StepRunner.StepFailure {
        return scanInternal(r, maxScrolls, true, false, null);
    }

    public static Result scan(StepRunner r, Result directory) throws StepRunner.StepFailure {
        return scan(r, DEFAULT_MAX_SCROLLS, directory);
    }

    public static Result scan(StepRunner r, int maxScrolls, Result directory)
            throws StepRunner.StepFailure {
        return scanInternal(r, maxScrolls, false, true, directory);
    }

    /**
     * 从当前位置往下扫到底。调用方要先保证列表停在顶部（{@link #toTop}）。
     *
     * <p>只合并相邻两屏的有序重叠，不按整本书的标题去重：不同卷可能有同名章节。
     * 2026-09-14 番外缺失反馈要求排除假到底：两次稳定内容不变后，还必须确认同一列表
     * 容器拒绝继续滚动。容器接受滚动、找不到容器、空树和手势失败都不能证明到底。
     */
    public static Result scan(StepRunner r, int maxScrolls) throws StepRunner.StepFailure {
        return scanInternal(r, maxScrolls, false, false, null);
    }

    private static Result scanInternal(StepRunner r, int maxScrolls, boolean directoryMode,
                                       boolean needsDirectory, Result directory)
            throws StepRunner.StepFailure {
        Result out = new Result(directoryMode);
        out.truncated = true;
        List<Occurrence> catalog = new ArrayList<>();
        Screen previous = recoveredScreen(r, null, directoryMode, needsDirectory, true, "扫描起始页");
        out.stateConflict = previous.stateConflict;
        merge(catalog, previous, 0);
        logScreen(r, "扫描第 1 屏", previous);
        if (!previous.scanReady()) {
            out.gapNote = previous.problem();
            r.recordDiagnostic("目录扫描起始页未读全");
            return finish(catalog, out, needsDirectory, directory);
        }
        int unchanged = 0;
        boolean reachedEnd = false;
        while (out.scrolls < Math.max(0, maxScrolls)) {
            r.checkCancelled();
            if (!r.scrollCatalogForward()) {
                out.gapNote = "目录滑动没有执行成功，无法确认是否已经到底";
                break;
            }
            out.scrolls++;
            r.sleepHuman();
            Screen current = recoveredScreen(r, previous.signature, directoryMode, needsDirectory,
                    true, "目录翻页后");
            if (current.stateConflict != null) out.stateConflict = current.stateConflict;
            logScreen(r, "扫描第 " + (out.scrolls + 1) + " 屏", current);
            if (!current.scanReady()) {
                out.gapNote = current.problem();
                break;
            }
            if (current.signature.equals(previous.signature)) {
                if (++unchanged >= END_CONFIRMATIONS) {
                    StepRunner.CatalogScroll probe = r.probeCatalogContainerForward();
                    out.endProbes++;
                    r.sleepHuman();
                    Screen checked = recoveredScreen(r, current.signature, directoryMode, needsDirectory,
                            true, "目录末尾核实");
                    if (checked.stateConflict != null) out.stateConflict = checked.stateConflict;
                    if (!checked.scanReady()) {
                        out.gapNote = checked.problem();
                        break;
                    }
                    boolean same = checked.signature.equals(current.signature);
                    if (confirmedEnd(unchanged, same, probe)) {
                        reachedEnd = true;
                        r.log("目录到底证据：连续内容不变=" + unchanged + "，同一纵向列表正向拒绝滚动，"
                                + checked.summary());
                        break;
                    }
                    if (probe == StepRunner.CatalogScroll.UNAVAILABLE) {
                        out.gapNote = "目录内容虽然不变，但没有读到可核实的列表容器，不能确认到底";
                        break;
                    }
                    if (!same && !joinScreen(r, catalog, current, checked, out)) break;
                    current = checked;
                    unchanged = 0;
                }
            } else {
                unchanged = 0;
                if (!joinScreen(r, catalog, previous, current, out)) break;
            }
            previous = current;
        }
        out.truncated = !reachedEnd;
        Result result = finish(catalog, out, needsDirectory, directory);
        if (!result.trustworthy()) r.recordDiagnostic("目录扫描未完整");
        return result;
    }

    /** 2026-09-14 不能把懒加载留下的两张旧树当结尾；内容和容器证据必须同时成立。 */
    static boolean confirmedEnd(int unchanged, boolean sameAfterProbe, StepRunner.CatalogScroll probe) {
        return unchanged >= END_CONFIRMATIONS && sameAfterProbe
                && probe == StepRunner.CatalogScroll.BLOCKED;
    }

    private static boolean joinScreen(StepRunner r, List<Occurrence> catalog, Screen previous,
                                      Screen current, Result out) {
        if (previous.rows.isEmpty() || current.rows.isEmpty()) {
            out.gapNote = "目录翻页前后缺少可读的行，不能拼接或确认到底";
            return false;
        }
        if (refreshSameScreen(catalog, previous, current, out)) return out.stateConflict == null;
        int overlap = overlap(previous.rows, current.rows, out);
        if (out.stateConflict != null) return false;
        if (overlap == 0) {
            out.gapNote = "目录翻页没有可靠的重叠行：「"
                    + previous.rows.get(previous.rows.size() - 1).title
                    + "」之后读到「" + current.rows.get(0).title + "」，无法确认是否漏行";
            return false;
        }
        merge(catalog, current, overlap);
        return true;
    }

    /**
     * 2026-09-15 拆开稳定性后，固定位置的缺标题行可能比其它行晚补齐。
     * 两个唯一且坐标不动的完整锚点、每个行框及逐位置身份一致，才可补齐原 occurrence；
     * 这只是内容更新，不追加行，也不提供任何“滚过一页”或“已经到底”的证据。
     */
    private static boolean refreshSameScreen(List<Occurrence> catalog, Screen previous, Screen current, Result out) {
        if (!samePositionFrames(previous, current, out)) return false;
        if (out.stateConflict == null) merge(catalog, current, current.rows.size());
        return true;
    }

    private static boolean samePositionFrames(Screen previous, Screen current, Result out) {
        if (previous.rows.size() != current.rows.size()) return false;
        int anchors = 0;
        String conflict = null;
        for (int i = 0; i < previous.rows.size(); i++) {
            CapturedRow before = previous.rows.get(i);
            CapturedRow after = current.rows.get(i);
            if (!hasArea(before.bounds) || !Arrays.equals(before.bounds, after.bounds)) return false;
            String lostState = before.positiveStateConflictWith(after);
            if (!before.retainsPositiveState(after) && lostState == null) return false;
            boolean same = before.sameContent(after);
            boolean completedOrEnriched = !before.complete()
                    && (after.complete() || positiveStateGained(before.state, after.state))
                    && !before.title.isEmpty() && before.title.equals(after.title)
                    && before.heading == after.heading
                    && ((before.directoryEvidence == null && after.directoryEvidence == null)
                    || (before.directoryEvidence != null && after.directoryEvidence != null
                    && before.directoryEvidence.signature().equals(after.directoryEvidence.signature())));
            if (!same && !completedOrEnriched && !placeholderPair(before, after) && lostState == null) return false;
            if (lostState != null) conflict = lostState;
            if (same && before.usableAnchor() && after.usableAnchor()
                    && Arrays.equals(before.titleBounds, after.titleBounds)
                    && unique(previous.rows, before.title) && unique(current.rows, after.title)) anchors++;
        }
        if (anchors < 2) return false;
        if (conflict != null) {
            out.stateConflict = out.gapNote = conflict;
        }
        return true;
    }

    /** 在无障碍节点复用前保存文字、行标记和坐标；不把活节点留到下一屏。 */
    private static final class CapturedRow {
        final String title;
        final int printedNo;
        final ChapterRowState state;
        final int[] bounds;
        final int[] titleBounds;
        final int[] viewport;
        final boolean singleTitle;
        final int titleCount;
        final boolean heading;
        final DirectoryRowEvidence directoryEvidence;
        final String nodeDescription;
        boolean clippedEdge;
        boolean stateClipped;

        CapturedRow(String title, int printedNo, ChapterRowState state, int[] bounds,
                    int[] titleBounds, int[] viewport, int titleCount, boolean titleVisible, boolean heading,
                    DirectoryRowEvidence directoryEvidence, String nodeDescription) {
            this.title = title;
            this.printedNo = printedNo;
            this.state = state;
            this.bounds = bounds;
            this.titleBounds = titleBounds;
            this.viewport = viewport;
            this.titleCount = titleCount;
            this.singleTitle = titleCount == 1 && titleVisible;
            this.heading = heading;
            this.directoryEvidence = directoryEvidence;
            this.nodeDescription = nodeDescription;
        }

        boolean sameContent(CapturedRow other) {
            if (!title.equals(other.title) || heading != other.heading
                    || (directoryEvidence == null) != (other.directoryEvidence == null)) return false;
            if (!retainsPositiveState(other)) return false;
            // 屏边只露出标题的行只能作顺序占位；下一屏补齐的标记不算内容冲突。
            if (!stateKnown() || !other.stateKnown()) return clippedEdge || other.clippedEdge;
            if (directoryEvidence != null
                    && !directoryEvidence.signature().equals(other.directoryEvidence.signature())) return false;
            return state.lock == other.state.lock
                    && state.downloaded == other.state.downloaded
                    && state.selectable == other.state.selectable;
        }

        boolean retainsPositiveState(CapturedRow other) {
            // 2026-09-15 补齐回归：标题尚不可见/处于屏边时也可能已经读到锁。
            // 扫描没有购买动作；新完整行不能借“旧行不完整”撤销任何已见标记而变成免费。
            return (directoryEvidence != null && other.directoryEvidence != null)
                    || positiveStateRetained(state, other.state);
        }

        String positiveStateConflictWith(CapturedRow other) {
            if (directoryEvidence != null || other.directoryEvidence != null || title.isEmpty()
                    || !title.equals(other.title) || heading != other.heading || retainsPositiveState(other)) return null;
            List<String> lost = new ArrayList<>();
            if (state.lock && !other.state.lock) lost.add("付费锁");
            if (state.downloaded && !other.state.downloaded) lost.add("已下载");
            if (state.selectable && !other.state.selectable) lost.add("可选标记");
            return "目录行「" + title + "」发生状态冲突：先前已读到的" + String.join("、", lost)
                    + "在同一行的后续快照中消失。扫描没有购买动作，不能通过重扫清除这份证据";
        }

        boolean structureReadable() {
            return !title.isEmpty() && singleTitle && hasArea(bounds) && hasArea(titleBounds);
        }

        boolean stateKnown() {
            return rowStateKnown(directoryEvidence != null, stateClipped, state);
        }

        boolean complete() {
            return structureReadable() && stateKnown();
        }

        boolean usableAnchor() {
            return complete() && (!hasArea(viewport)
                    || (titleBounds[1] > viewport[1] && titleBounds[3] < viewport[3]));
        }

        String problem() {
            List<String> reasons = new ArrayList<>();
            if (title.isEmpty()) reasons.add(titleCount == 0 ? "没有 title 节点" : "标题为空");
            if (titleCount != 1) reasons.add("title 命中数=" + titleCount);
            else if (!singleTitle) reasons.add("标题不可见");
            if (!hasArea(bounds)) reasons.add("整行坐标为空或零面积");
            if (!hasArea(titleBounds)) reasons.add("标题坐标为空或零面积");
            if (stateClipped) reasons.add("屏边裁剪，整行尚未补齐");
            if (!stateKnown() && !stateClipped) reasons.add("整行锁/下载/勾选状态未读全");
            return String.join("、", reasons);
        }

        String diagnostic(int position) {
            return diagnostic("当前快照第 " + position + " 行");
        }

        String diagnostic(String position) {
            String role = directoryEvidence == null ? "" : "；目录结构=" + directoryEvidence.signature();
            return position + "「" + displayTitle(title) + "」：" + problem()
                    + "；" + nodeDescription + "；rowBounds=" + Arrays.toString(bounds)
                    + "；titleBounds=" + Arrays.toString(titleBounds) + role;
        }
    }

    /**
     * 2026-09-14 恶鬼番外没有编号，但缺锁的半行仍可能是收费章；不能沿用旧的无编号豁免。
     * 普通目录只验证角色与整行结构，不从那里没有锁/圈推断免费或已购。
     */
    static boolean rowStateKnown(boolean directoryRow, boolean stateClipped, ChapterRowState state) {
        if (directoryRow) return !stateClipped;
        if (state == null) return false;
        // 勾选圈可能比锁更靠上。半行只有圈或「已下载」不能证明它没有锁、是免费章。
        if (stateClipped) return state.lock && (state.downloaded || state.selectable);
        return state.lock || state.downloaded || state.selectable;
    }

    static boolean positiveStateRetained(ChapterRowState before, ChapterRowState after) {
        return before != null && after != null
                && (!before.lock || after.lock)
                && (!before.downloaded || after.downloaded)
                && (!before.selectable || after.selectable);
    }

    private static boolean positiveStateGained(ChapterRowState before, ChapterRowState after) {
        return positiveStateRetained(before, after)
                && ((!before.lock && after.lock) || (!before.downloaded && after.downloaded)
                || (!before.selectable && after.selectable));
    }

    private static final class Screen {
        final List<CapturedRow> rows;
        final String signature;
        final boolean complete;
        final String captureProblem;
        String stateConflict;
        boolean stable;

        Screen(List<CapturedRow> rows) {
            this(rows, null);
        }

        Screen(List<CapturedRow> rows, String captureProblem) {
            this.rows = rows;
            this.captureProblem = captureProblem;
            StringBuilder sig = new StringBuilder().append(captureProblem).append('\n');
            boolean allComplete = !rows.isEmpty();
            for (CapturedRow row : rows) {
                sig.append(row.title.length()).append(':').append(row.title)
                        .append(Arrays.toString(row.bounds)).append(Arrays.toString(row.titleBounds))
                        .append(Arrays.toString(row.viewport)).append(row.clippedEdge).append(row.stateClipped)
                        .append(row.state.lock).append(row.state.downloaded)
                        .append(row.state.selectable).append(row.singleTitle).append(row.heading)
                        .append(row.directoryEvidence == null ? "" : row.directoryEvidence.signature())
                        .append('\n');
                allComplete &= row.complete() || (row.structureReadable() && row.clippedEdge);
            }
            signature = sig.toString();
            complete = allComplete;
        }

        String problem() {
            if (stateConflict != null) return stateConflict;
            if (captureProblem != null) return captureProblem;
            if (rows.isEmpty()) return "目录暂时没有可读的行，不能当成已经到底";
            if (!complete || readableRows() == 0) {
                return "目录标题或整行标记没有读完整，无法确认章节顺序；" + details();
            }
            return "目录仍在变化，等待后也没有读到稳定的一屏";
        }

        int readableRows() {
            int count = 0;
            for (CapturedRow row : rows) if (row.structureReadable()) count++;
            return count;
        }

        boolean navigationReady() {
            return captureProblem == null && stateConflict == null && stable && readableRows() > 0;
        }

        boolean scanReady() {
            if (!navigationReady()) return false;
            if (complete) return true;
            int anchors = 0;
            for (CapturedRow row : rows) if (row.usableAnchor()) anchors++;
            // 不完整行可保留到下一屏补齐；满屏坏行不能冒充有顺序依据的一屏。
            return anchors >= 2;
        }

        String summary() {
            return "列表项=" + rows.size() + "，可读标题=" + readableRows()
                    + "，首行「" + (rows.isEmpty() ? "未读到" : displayTitle(rows.get(0).title))
                    + "」，末行「" + (rows.isEmpty() ? "未读到" : displayTitle(rows.get(rows.size() - 1).title))
                    + "」，页面有效=" + (captureProblem == null) + "，内容稳定=" + stable
                    + "，整屏可读=" + complete;
        }

        String details() {
            List<String> problems = new ArrayList<>();
            for (int i = 0; i < rows.size() && problems.size() < 3; i++) {
                if (!rows.get(i).complete()) problems.add(rows.get(i).diagnostic(i + 1));
            }
            return summary() + (problems.isEmpty() ? "" : "；" + String.join("；", problems));
        }
    }

    /** 邻接证据绑定到这一次出现的行，不能由别卷的同名行替它作证。 */
    private static final class Occurrence {
        CapturedRow row;
        boolean joinedFromPrevious;

        Occurrence(CapturedRow row) { this.row = row; }
    }

    private static Screen captureScreen(StepRunner r, boolean directoryMode, boolean requirePickerList)
            throws StepRunner.StepFailure {
        NodeView root = r.activeRoot();
        List<CapturedRow> captured = new ArrayList<>();
        if (directoryMode) {
            if (r.findIn(root, Keys.CATALOG_DIRECTORY_READY) == null
                    || r.findIn(root, Keys.SELECTED_COUNT) != null) {
                return new Screen(captured, "当前页面不是已确认的普通目录页，不能用旧树核实边界");
            }
            List<NodeView> lists = visibleDirectoryLists(r, root);
            if (lists.size() != 1) {
                return new Screen(captured, "普通目录没有唯一可读的 list_view 列表，不能确定章节角色");
            }
            NodeView list = lists.get(0);
            for (int i = 0; i < list.childCount(); i++) {
                // 直接遍历已确认的 ListView 子项，避免把顶栏或其它列表里的无 id 容器认成章节。
                NodeView row = list.child(i);
                if (row != null && !row.visible()) continue;
                List<NodeView> titles = r.findAllIn(row, Keys.CHAPTER_ROW_TITLE);
                NodeView soleChild = row != null && row.childCount() == 1 ? row.child(0) : null;
                boolean directTitle = soleChild != null
                        && NodeMatcher.idMatches(soleChild.viewId(), "title")
                        && classIs(soleChild.className(), "TextView");
                NodeView title = directTitle ? soleChild : titles.isEmpty() ? null : titles.get(0);
                boolean heading = (title != null && title.heading()) || (row != null && row.heading());
                DirectoryRowEvidence evidence = new DirectoryRowEvidence(list.viewId(), list.className(),
                        row == null ? null : row.viewId(), row == null ? null : row.className(),
                        row != null, row != null && row.clickable(), row == null ? -1 : row.childCount(),
                        directTitle, title == null ? null : title.viewId(),
                        title == null ? null : title.className(), heading);
                String text = title == null || title.text() == null ? "" : title.text().trim();
                captured.add(new CapturedRow(text, Texts.rowChapterNo(text),
                        ChapterRowState.of(false, false, false), boundsOf(row), boundsOf(title),
                        boundsOf(list), titles.size(), title != null && title.visible(),
                        heading, evidence, nodeDescription(row)));
            }
        } else {
            NodeView scope = root;
            if (requirePickerList) {
                if (r.findIn(root, Keys.SELECTED_COUNT) == null
                        || r.findIn(root, Keys.CATALOG_DIRECTORY_READY) != null) {
                    return new Screen(captured, "当前页面不是已确认的选择章节页，不能用旧树核实边界");
                }
                List<NodeView> lists = visibleLists(r, root, Keys.CATALOG_PICKER_LIST);
                if (lists.size() != 1) {
                    return new Screen(captured, "选择章节页没有唯一可读的 downloadRecycler 列表，不能核对目录");
                }
                scope = lists.get(0);
                // 生产双页扫描保留每一个可见直接子项；缺标题是未知占位，不能静默少一行。
                for (int i = 0; i < scope.childCount(); i++) {
                    NodeView row = scope.child(i);
                    if (row != null && !row.visible()) continue;
                    List<NodeView> titles = r.findAllIn(row, Keys.CHAPTER_ROW_TITLE);
                    NodeView title = titles.isEmpty() ? null : titles.get(0);
                    captured.add(capturePickerRow(r, row, title, titles.size(), boundsOf(scope)));
                }
            } else {
                List<NodeView> titles = r.findAllIn(scope, Keys.CHAPTER_ROW_TITLE);
                for (NodeView title : titles) {
                    if (title == null || !title.visible() || !NodeMatcher.hasArea(title)) continue;
                    NodeView row = rowOf(title);
                    captured.add(capturePickerRow(r, row, title,
                            r.findAllIn(row, Keys.CHAPTER_ROW_TITLE).size(), viewportOf(title, root)));
                }
            }
        }
        // 2026-09-15 回顶残行：无 title 的占位坐标为零，按 titleBounds 排序会把它搬到首行。
        // 已确认列表的 child 顺序就是页面顺序，必须连缺失项一起保留。
        if (!directoryMode && !requirePickerList) {
            captured.sort(Comparator.comparingInt(row -> row.titleBounds[1]));
        }
        // 2026-09-15 第五次现场：选择章节页是 RecyclerView，翻页后贴着视口上下边的残行连 title
        // 节点都不在可见树里（只剩居中的锁和勾选圈，childCount=2）。这种行没有任何身份或状态证据，
        // 留着它却有两处害处：上一屏后缀↔本屏前缀的**逐位置**对齐会整体错位（第五次现场就是
        // 第二屏首行、第三屏末行各一行残行，整趟停在「没有可靠的重叠行」），它还会以空标题混进目录。
        // 滚过几屏之后同一行一定会在屏幕中间完整出现，所以这里直接丢掉它最安全。
        dropUnreadableEdgeFragments(r, captured);
        int shortestFullRow = Integer.MAX_VALUE;
        for (CapturedRow row : captured) {
            // 2026-09-14 番外可以独占整屏；无编号完整行也必须参与裁剪检查，不能失去高度参照。
            if (row.complete() && hasArea(row.viewport)
                    && row.bounds[1] > row.viewport[1] && row.bounds[3] < row.viewport[3]) {
                shortestFullRow = Math.min(shortestFullRow, row.bounds[3] - row.bounds[1]);
            }
        }
        for (int i = 0; i < captured.size(); i++) {
            CapturedRow row = captured.get(i);
            row.clippedEdge = hasArea(row.viewport)
                    && ((i == 0 && row.bounds[1] <= row.viewport[1])
                    || (i == captured.size() - 1 && row.bounds[3] >= row.viewport[3]));
            row.stateClipped = row.clippedEdge && (row.bounds[1] < row.viewport[1]
                    || row.bounds[3] > row.viewport[3]
                    || row.titleBounds[1] <= row.viewport[1] || row.titleBounds[3] >= row.viewport[3]
                    || (shortestFullRow != Integer.MAX_VALUE
                    && row.bounds[3] - row.bounds[1] < shortestFullRow));
        }
        return new Screen(captured);
    }

    /**
     * 丢掉贴着视口上下边、读不到标题、而且比同屏读全的行都矮的残行。
     *
     * <p>判据刻意收得很紧：必须是「贴着视口边」（说明它是被视口裁出来的）**而且**
     * 比同屏任何一行完整行都矮（说明它是被裁短的尾巴）。高度正常、只是读不出来的整行
     * 不在这条豁免里 —— 那种行仍然按原来的方式报「存在不能确认身份的目录行」。
     */
    private static void dropUnreadableEdgeFragments(StepRunner r, List<CapturedRow> rows) {
        int shortest = Integer.MAX_VALUE;
        for (CapturedRow row : rows) {
            if (!row.complete() || !hasArea(row.viewport)) continue;
            if (row.bounds[1] < row.viewport[1] || row.bounds[3] > row.viewport[3]) continue;
            shortest = Math.min(shortest, row.bounds[3] - row.bounds[1]);
        }
        if (shortest == Integer.MAX_VALUE) return;
        for (int i = rows.size() - 1; i >= 0; i--) {
            CapturedRow row = rows.get(i);
            if (row.structureReadable() || !hasArea(row.bounds) || !hasArea(row.viewport)) continue;
            boolean atEdge = row.bounds[1] <= row.viewport[1] || row.bounds[3] >= row.viewport[3];
            if (!atEdge || row.bounds[3] - row.bounds[1] >= shortest) continue;
            r.log("  屏边残行（读不到标题、只有 "
                    + Math.max(0, row.bounds[3] - row.bounds[1]) + "px）不算目录行，已跳过："
                    + row.nodeDescription + "；rowBounds=" + Arrays.toString(row.bounds)
                    + "；屏幕坐标=" + Arrays.toString(row.viewport));
            rows.remove(i);
        }
    }

    private static CapturedRow capturePickerRow(StepRunner r, NodeView row, NodeView title,
                                                int titleCount, int[] viewport) {
        String text = title == null || title.text() == null ? "" : title.text().trim();
        return new CapturedRow(text, Texts.rowChapterNo(text), ChapterRowState.read(r, row),
                boundsOf(row), boundsOf(title), viewport, titleCount, title != null && title.visible(),
                (title != null && title.heading()) || (row != null && row.heading()),
                null, nodeDescription(row));
    }

    private static String nodeDescription(NodeView node) {
        if (node == null) return "行节点未读取";
        return "id=" + node.viewId() + "，class=" + node.className()
                + "，clickable=" + node.clickable() + "，childCount=" + node.childCount()
                + "，text=" + node.text() + "，desc=" + node.desc();
    }

    private static String displayTitle(String title) {
        return title == null || title.isEmpty() ? "空标题/未读到 title" : title;
    }

    private static void logScreen(StepRunner r, String stage, Screen screen) {
        r.log(stage + "：" + screen.details());
    }

    private static List<NodeView> visibleDirectoryLists(StepRunner r, NodeView root) {
        return visibleLists(r, root, Keys.CATALOG_DIRECTORY_LIST);
    }

    private static List<NodeView> visibleLists(StepRunner r, NodeView root, String key) {
        List<NodeView> visible = new ArrayList<>();
        for (NodeView list : r.findAllIn(root, key)) {
            if (list != null && list.visible() && NodeMatcher.hasArea(list)) visible.add(list);
        }
        return visible;
    }

    /** 行可能被列表自己的上下边缘裁剪，不能只拿整块屏幕的边界判断。 */
    private static int[] viewportOf(NodeView title, NodeView root) {
        NodeView node = title.parent();
        for (int depth = 0; node != null && depth < 30; depth++, node = node.parent()) {
            if (NodeMatcher.idMatches(node.viewId(), "downloadRecycler")
                    || NodeMatcher.idMatches(node.viewId(), "list_view") || node.scrollable()) {
                return boundsOf(node);
            }
        }
        return boundsOf(root);
    }

    /**
     * 2026-09-15 回顶停在“目录未稳定”：固定的残行并不代表页面还在动。
     * 稳定只看签名；页面有效性、可用锚点和最终行完整性各自核实，空树稳定也不能证明边界。
     * 旧树或残行等满读取窗口，给异步手势和懒加载补齐的机会。
     */
    private static Screen settledScreen(StepRunner r, String previousSignature, boolean directoryMode,
                                        boolean requirePickerList)
            throws StepRunner.StepFailure {
        return settledScreen(r, previousSignature, directoryMode, requirePickerList, new ArrayList<>());
    }

    private static Screen settledScreen(StepRunner r, String previousSignature, boolean directoryMode,
                                        boolean requirePickerList, List<Screen> observations)
            throws StepRunner.StepFailure {
        Screen screen = captureScreen(r, directoryMode, requirePickerList);
        if (!directoryMode) screen = rememberObservation(screen, observations);
        if (screen.stateConflict != null) return screen;
        int matching = 1;
        long deadline = r.elapsedRealtime() + SETTLE_TIMEOUT;
        while (r.elapsedRealtime() < deadline) {
            r.checkCancelled();
            r.waitMillis(SETTLE_STEP);
            Screen again = captureScreen(r, directoryMode, requirePickerList);
            if (!directoryMode) {
                again = rememberObservation(again, observations);
                if (again.stateConflict != null) return again;
            }
            matching = again.signature.equals(screen.signature) ? matching + 1 : 1;
            screen = again;
            if (matching >= SETTLE_MATCHES && screen.complete && screen.captureProblem == null
                    && !screen.signature.equals(previousSignature)) {
                screen.stable = true;
                return screen;
            }
        }
        r.checkCancelled();
        screen.stable = matching >= SETTLE_MATCHES;
        return screen;
    }

    /**
     * 2026-09-15 三帧回归：无锁残行→已见锁残行→完整无锁，不能只返回最后一帧。
     * 等待/有限重读期间保留少量观察；空树只能暂时打断匹配，不能清掉已见事实。
     * 仍需两个唯一锚点确认同一行才报冲突，并返回真正读到正标记的原快照供诊断。
     */
    private static Screen rememberObservation(Screen current, List<Screen> observations) {
        for (Screen before : observations) {
            Result proof = new Result();
            if (!samePositionFrames(before, current, proof)) overlap(before.rows, current.rows, proof);
            if (proof.stateConflict != null) {
                before.stateConflict = proof.stateConflict;
                return before;
            }
        }
        if (observations.isEmpty()
                || !observations.get(observations.size() - 1).signature.equals(current.signature)) {
            observations.add(current);
        }
        return current;
    }

    private static Screen recoveredScreen(StepRunner r, String previousSignature, boolean directoryMode,
                                           boolean requirePickerList, boolean scanning, String stage)
            throws StepRunner.StepFailure {
        List<Screen> observations = new ArrayList<>();
        Screen screen = settledScreen(r, previousSignature, directoryMode, requirePickerList, observations);
        for (int retry = 0; retry < READ_RECOVERIES
                && screen.stateConflict == null
                && !(scanning ? screen.scanReady() : screen.navigationReady()); retry++) {
            logScreen(r, stage + "，有限重读 " + (retry + 1) + "/" + READ_RECOVERIES, screen);
            r.waitMillis(SETTLE_STEP);
            screen = settledScreen(r, previousSignature, directoryMode, requirePickerList, observations);
        }
        return screen;
    }

    /** 至少两条不同且唯一的锚点一同上移，才确认是前一屏尾部的重叠。 */
    private static int overlap(List<CapturedRow> previous, List<CapturedRow> current, Result out) {
        for (int length = Math.min(previous.size(), current.size()); length >= 2; length--) {
            int start = previous.size() - length;
            int anchors = 0;
            int minMove = Integer.MAX_VALUE;
            int maxMove = 0;
            boolean matches = true;
            String conflict = null;
            for (int i = 0; i < length; i++) {
                CapturedRow before = previous.get(start + i);
                CapturedRow after = current.get(i);
                String lostState = before.positiveStateConflictWith(after);
                if (!before.sameContent(after) && !placeholderPair(before, after) && lostState == null) {
                    matches = false;
                    break;
                }
                if (lostState != null) conflict = lostState;
                // 首尾标题的矩形可能被裁短；它仍参与身份拼接，但不能用裁短后的top算位移。
                boolean sameSize = before.titleBounds[3] - before.titleBounds[1]
                        == after.titleBounds[3] - after.titleBounds[1]
                        && before.titleBounds[2] - before.titleBounds[0]
                        == after.titleBounds[2] - after.titleBounds[0];
                if (lostState == null && before.usableAnchor() && after.usableAnchor() && sameSize
                        && unique(previous, before.title) && unique(current, after.title)) {
                    int move = before.titleBounds[1] - after.titleBounds[1];
                    if (move <= 0) {
                        matches = false;
                        break;
                    }
                    anchors++;
                    minMove = Math.min(minMove, move);
                    maxMove = Math.max(maxMove, move);
                }
            }
            if (matches && anchors >= 2 && maxMove - minMove <= 8) {
                // 2026-09-15 缺 title 的目录行保留原位置；只能由同屏另外两个唯一移动锚点
                // 与该行相同的位移共同补齐，绝不能把空标题当通配符按标题去重。
                for (int i = 0; i < length; i++) {
                    CapturedRow before = previous.get(start + i);
                    CapturedRow after = current.get(i);
                    if (before.sameContent(after) || before.positiveStateConflictWith(after) != null) continue;
                    int move = before.bounds[1] - after.bounds[1];
                    if (before.bounds[3] - before.bounds[1] != after.bounds[3] - after.bounds[1]
                            || move < minMove - 8 || move > maxMove + 8) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    if (conflict != null) {
                        out.stateConflict = out.gapNote = conflict;
                        return 0;
                    }
                    return length;
                }
            }
        }
        return 0;
    }

    private static boolean placeholderPair(CapturedRow before, CapturedRow after) {
        DirectoryRowEvidence a = before.directoryEvidence;
        DirectoryRowEvidence b = after.directoryEvidence;
        return (before.title.isEmpty() || after.title.isEmpty())
                && before.titleCount <= 1 && after.titleCount <= 1
                && hasArea(before.bounds) && hasArea(after.bounds)
                && a != null && b != null && a.directListChild && b.directListChild
                && a.childCount <= 1 && b.childCount <= 1
                && classIs(a.rowClass, "RelativeLayout") && classIs(b.rowClass, "RelativeLayout")
                && Objects.equals(a.rowId, b.rowId) && Objects.equals(a.listId, b.listId);
    }

    private static boolean unique(List<CapturedRow> rows, String title) {
        int count = 0;
        for (CapturedRow row : rows) if (row.title.equals(title) && ++count > 1) return false;
        return count == 1;
    }

    private static void merge(List<Occurrence> catalog, Screen screen, int overlap) {
        int start = catalog.size() - overlap;
        for (int i = 0; i < screen.rows.size(); i++) {
            CapturedRow row = screen.rows.get(i);
            Occurrence occurrence;
            if (i < overlap) {
                occurrence = catalog.get(start + i);
                // 2026-09-15 中间残行新读到的锁也必须保留；只替换为实际采到的整份快照，
                // 绝不把数次残行字段拼成一份完整可购买行。
                if (!occurrence.row.complete() && occurrence.row.retainsPositiveState(row)
                        && (row.complete() || positiveStateGained(occurrence.row.state, row.state))) {
                    occurrence.row = row;
                }
            } else {
                occurrence = new Occurrence(row);
                catalog.add(occurrence);
            }
            if (i > 0 && adjacent(screen.rows.get(i - 1), row)) occurrence.joinedFromPrevious = true;
        }
    }

    private static boolean adjacent(CapturedRow before, CapturedRow after) {
        if (!before.structureReadable() || !after.structureReadable()) return false;
        int[] a = before.bounds;
        int[] b = after.bounds;
        int tolerance = Math.max(4, Math.min(a[3] - a[1], b[3] - b[1]) / 8);
        int horizontalOverlap = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
        return horizontalOverlap > Math.min(a[2] - a[0], b[2] - b[0]) / 2
                && Math.abs(b[1] - a[3]) <= tolerance;
    }

    private static Result finish(List<Occurrence> catalog, Result progress,
                                 boolean needsDirectory, Result directory) {
        List<Row> observed = new ArrayList<>();
        for (Occurrence occurrence : catalog) {
            CapturedRow row = occurrence.row;
            observed.add(observedRow(row.title, row.state, occurrence.joinedFromPrevious,
                    row.complete(), row.directoryEvidence));
        }
        Result out = resolveRows(observed, progress.directoryScanned, needsDirectory,
                progress.truncated, progress.gapNote, directory);
        out.stateConflict = progress.stateConflict;
        for (int i = 0; i < catalog.size(); i++) {
            CapturedRow row = catalog.get(i).row;
            if (!row.complete()) {
                out.rowProblems.add(row.diagnostic("合并目录第 " + (i + 1) + " 行"));
            } else if (out.allRows.get(i).kind == RowKind.UNKNOWN) {
                out.rowProblems.add("合并目录第 " + (i + 1) + " 行「" + displayTitle(row.title)
                        + "」：角色未确认；" + row.nodeDescription
                        + (row.directoryEvidence == null ? "" : "；目录结构=" + row.directoryEvidence.signature()));
            }
        }
        out.scrolls = progress.scrolls;
        out.endProbes = progress.endProbes;
        return out;
    }

    /** 捕获后的标量事实进入纯判据；单测不需要伪造屏幕或调用扫描动作。 */
    static Row observedRow(String title, ChapterRowState state, boolean joinedFromPrevious,
                           boolean complete, DirectoryRowEvidence directoryEvidence) {
        int printedNo = Texts.rowChapterNo(title);
        RowKind kind = Texts.isBlank(title) ? RowKind.UNKNOWN
                : directoryEvidence == null ? classifyRow(printedNo, false)
                : classifyDirectoryRow(printedNo, directoryEvidence);
        boolean stateVerified = directoryEvidence == null && complete
                && rowStateKnown(false, false, state);
        return new Row(title, printedNo, state == null ? ChapterRowState.of(false, false, false) : state,
                joinedFromPrevious, stateVerified, null, kind, directoryEvidence,
                complete && (directoryEvidence != null || stateVerified));
    }

    /**
     * 2026-09-14 两份真机 dump 证明选择页的卷行和无锁番外同形；只有整份普通目录与选择页
     * 的每一次出现都逐位置同名，角色才可转移。局部同名、去重后同名或不完整目录都不算证据。
     */
    static Result resolveRows(List<Row> observed, boolean directoryScanned, boolean needsDirectory,
                              boolean truncated, String scanGap, Result directory) {
        Result out = new Result(directoryScanned);
        out.truncated = truncated;
        out.gapNote = scanGap;
        String mismatch = needsDirectory ? directoryMismatch(directory, observed) : null;
        if (out.gapNote == null) out.gapNote = mismatch;
        boolean transferRoles = needsDirectory && mismatch == null && !truncated && scanGap == null;
        boolean joinedSinceChapter = true;
        String missingRow = null;
        String volumeTitle = null;
        for (int i = 0; i < observed.size(); i++) {
            Row row = observed.get(i);
            if (!row.complete && out.gapNote == null) {
                out.gapNote = "目录「" + displayTitle(row.title) + "」整行未读完整，不能写账本";
            }
            if (i > 0) {
                joinedSinceChapter &= row.verifiedAfterPrevious;
                if (!row.verifiedAfterPrevious && missingRow == null) {
                    missingRow = "目录中「" + observed.get(i - 1).title + "」与「"
                            + row.title + "」之间的行没有读完整，无法确认章节顺序";
                }
            }
            DirectoryRowEvidence evidence = transferRoles
                    ? directory.allRows.get(i).directoryEvidence : row.directoryEvidence;
            RowKind kind = Texts.isBlank(row.title) ? RowKind.UNKNOWN
                    : directoryScanned ? classifyDirectoryRow(row.printedNo, evidence)
                    : transferRoles ? directory.allRows.get(i).kind : classifyRow(row.printedNo, false);
            if (kind == RowKind.SECTION) {
                out.skipped.add(row.title);
                volumeTitle = row.title;
            } else if (kind == RowKind.UNKNOWN) {
                out.unresolved.add(displayTitle(row.title));
                // 未判定行也可能是换卷；不能让后续章继承一个没有证据的旧卷名。
                volumeTitle = null;
            }
            if (!row.complete && kind != RowKind.UNKNOWN) out.unresolved.add(displayTitle(row.title));
            Row resolved = new Row(row.title, row.printedNo, row.state, joinedSinceChapter,
                    !directoryScanned && row.stateVerified, volumeTitle, kind, evidence, row.complete);
            out.allRows.add(resolved);
            if (kind == RowKind.CHAPTER) {
                out.chapters.add(resolved);
                joinedSinceChapter = true;
            }
        }
        if (out.gapNote == null) out.gapNote = findGap(out.chapters);
        if (out.gapNote == null) out.gapNote = missingRow;
        if (out.gapNote == null) out.gapNote = ambiguousTitles(out.allRows);
        return out;
    }

    static String directoryMismatch(Result directory, List<Row> pickerRows) {
        if (directory == null || !directory.directoryScanned) {
            return "没有普通目录的角色证据，不能把选择章节页当作普通目录";
        }
        if (!directory.trustworthy() || directory.allRows.isEmpty()) {
            return "普通目录未读完整或仍有未知行，不能据此判定选择章节页";
        }
        for (Row row : directory.allRows) {
            if (row.kind == RowKind.UNKNOWN || !row.complete || row.directoryEvidence == null
                    || classifyDirectoryRow(row.printedNo, row.directoryEvidence) != row.kind) {
                return "普通目录的「" + row.title + "」没有完整且一致的角色证据";
            }
        }
        String ambiguity = ambiguousTitles(directory.allRows);
        if (ambiguity != null) return ambiguity;
        if (directory.allRows.size() != pickerRows.size()) {
            return "普通目录与选择章节页行数不同（" + directory.allRows.size() + " / "
                    + pickerRows.size() + "，含分节），不能猜测漏掉或新增了哪一行";
        }
        for (int i = 0; i < pickerRows.size(); i++) {
            if (!Objects.equals(directory.allRows.get(i).title, pickerRows.get(i).title)) {
                return "普通目录与选择章节页第 " + (i + 1) + " 行不同：「"
                        + directory.allRows.get(i).title + "」/「" + pickerRows.get(i).title
                        + "」，不能转移章节角色";
            }
            if (!pickerRows.get(i).complete) {
                return "选择章节页的「" + pickerRows.get(i).title
                        + "」整行状态未读完整，不能转移角色或判断免费";
            }
        }
        return null;
    }

    /** 重复检查只用于拒绝歧义，不删除、合并或移动任何一次出现。 */
    private static String ambiguousTitles(List<Row> rows) {
        Map<String, Row> first = new HashMap<>();
        for (Row row : rows) {
            Row previous = first.putIfAbsent(row.title, row);
            if (previous == null) continue;
            if (previous.kind != row.kind) {
                return "目录标题「" + row.title + "」同时用于不同角色，精确定位可能命中卷标题，不能写账本";
            }
            if (row.kind == RowKind.CHAPTER && row.printedNo < 0) {
                return "无标号章节「" + row.title + "」在多个位置出现，卷内或跨卷均不能唯一定位";
            }
        }
        return null;
    }

    /** 行标题的可点击祖先就是整行；没有可点击祖先时退一层父节点，至少能查到锁。 */
    static NodeView rowOf(NodeView title) {
        NodeView row = NodeMatcher.clickableAncestorOf(title);
        if (row == title && title.parent() != null) return title.parent();
        return row;
    }

    private static int[] boundsOf(NodeView node) {
        int[] bounds = node == null ? null : node.boundsInScreen();
        return bounds == null || bounds.length != 4 ? new int[4] : bounds.clone();
    }

    private static boolean hasArea(int[] bounds) {
        return bounds[2] > bounds[0] && bounds[3] > bounds[1];
    }

    /**
     * 裸数字行序仍检查连续性。「第N章」是作者自拟标题，只有核实了实际行相邻，才允许跳号。
     * 章号下降可以是新卷；翻页是否漏过卷尾和新卷开头，由重叠和整行位置另外核对。
     */
    static String findGap(List<Row> chapters) {
        for (int i = 1; i < chapters.size(); i++) {
            int prev = chapters.get(i - 1).printedNo;
            int cur = chapters.get(i).printedNo;
            // 2026-09-14 番外没有印刷号；-1 不是第零章，不能据此算出一个虚构缺口。
            if (prev < 0 || cur < 0) continue;
            Row before = chapters.get(i - 1);
            Row after = chapters.get(i);
            boolean authoredNeighbours = before.title != null && after.title != null
                    && before.title.trim().startsWith("第") && after.title.trim().startsWith("第")
                    && after.verifiedAfterPrevious;
            if (cur > prev + 1 && !authoredNeighbours) {
                return "第 " + prev + " 行之后直接跳到了 " + cur
                        + "（缺 " + (cur - prev - 1) + " 行），翻页漏了内容";
            }
        }
        return null;
    }

    /**
     * 把列表滚回第一行。
     *
     * <p>2026-09-14 普通目录 dump 上方还有横向标签，旧的“第一个可滚动容器”可能只在滑标签。
     * goto_top 只是加速；即使第一次还不知道首行，也要证明纵向章节列表反向滚不动且内容稳定，
     * 否则从恢复的中段开始扫描会把漏掉的前半本当成完整目录。
     */
    static void toTop(StepRunner r, String firstRowTitle, int scrolls)
            throws StepRunner.StepFailure {
        TopProgress progress = new TopProgress();
        int page = catalogPage(r, r.activeRoot());
        long pageDeadline = r.elapsedRealtime() + SETTLE_TIMEOUT;
        while (page == 0 && r.elapsedRealtime() < pageDeadline) {
            r.waitMillis(SETTLE_STEP);
            r.checkCancelled();
            page = catalogPage(r, r.activeRoot());
        }
        if (page == 0) throw topFailure(r, progress,
                "没有与当前页面一致的唯一纵向章节列表，不能点击回顶或确认顶部");
        boolean directoryMode = page == 1;
        Screen entered = settledScreen(r, null, directoryMode, true);
        progress.entered = entered.summary();
        progress.last = entered;
        logScreen(r, "目录回顶进入时", entered);
        r.recordDiagnostic("目录回顶前");
        clickTop(r, page, progress);
        Screen previous = recoveredScreen(r, null, directoryMode, true, false, "点击回顶后");
        progress.last = previous;
        logScreen(r, "回顶点击后的页面", previous);
        if (!previous.navigationReady() && clickTop(r, page, progress)) {
            previous = recoveredScreen(r, null, directoryMode, true, false, "再次点击回顶后");
            progress.last = previous;
        }
        if (!previous.navigationReady()) throw topFailure(r, progress, previous.problem());
        int unchanged = 0;
        int stalledProbes = 0;
        boolean everMoved = false;
        // 按钮按下去之后页面确实换了一屏：这是「该按钮真的能把列表带到顶部」的现场证据。
        boolean clickedMoved = !entered.signature.equals(previous.signature);
        int limit = Math.max(4, Math.min(DEFAULT_MAX_SCROLLS, Math.max(0, scrolls)) + 2);
        for (int i = 0; i < limit; i++) {
            r.checkCancelled();
            progress.scrolls++;
            // 2026-09-15 现场：这里原来派发向下手势，到顶后每次都被菠萝包当成下拉刷新，页面跳回
            // 已读章节，同一个循环跑了 122 次仍没确认顶部。回顶只许用两样东西：应用自己的
            // 「回到顶部」按钮，和列表容器的原生反向滚动动作 —— 后者不产生触摸事件，
            // 不会触发下拉刷新，到顶时容器自己会拒绝它（BLOCKED），边界证据和原来一样强。
            StepRunner.CatalogScroll step = r.probeCatalogContainerBackward();
            progress.probes++;
            if (step == StepRunner.CatalogScroll.UNAVAILABLE) {
                throw topFailure(r, progress, "读不到可核实的纵向目录容器，不能确认顶部");
            }
            r.sleepHuman();
            Screen current = recoveredScreen(r, previous.signature, directoryMode, true, false, "回顶途中");
            progress.last = current;
            if (!current.complete || !current.signature.equals(previous.signature)) {
                logScreen(r, "回顶反向动作第 " + progress.scrolls + " 次后", current);
            }
            if (!current.navigationReady()) {
                if (clickTop(r, page, progress)) {
                    current = recoveredScreen(r, null, directoryMode, true, false, "回顶恢复点击后");
                    progress.last = current;
                }
                if (!current.navigationReady()) throw topFailure(r, progress, current.problem());
                unchanged = 0;
                previous = current;
                continue;
            }
            unchanged = current.signature.equals(previous.signature) ? unchanged + 1 : 0;
            everMoved |= unchanged == 0;
            if (unchanged >= END_CONFIRMATIONS) {
                // 强证据：容器自己拒绝反向滚动。弱证据只在容器动作从未推动过这个列表、
                // 「回到顶部」按钮确实按下去过、而且按下去之后页面真的换了位置时才启用 ——
                // 这时唯一可用的证据就是那条已被证实的按钮路径＋连续几次内容不变。
                if (confirmedEnd(unchanged, true, step)
                        || (!everMoved && progress.pressed > 0 && clickedMoved)) {
                    // 第一次扫描不知道全书首章标题；用物理边界建立首行证据，不猜“第1章”。
                    // 复扫必须仍是首行，不能仅在首屏任意位置找到旧标题就算通过。
                    if (firstRowTitle == null || Objects.equals(firstRowTitle, current.rows.get(0).title)) {
                        r.log("目录回顶已确认：" + (step == StepRunner.CatalogScroll.BLOCKED
                                ? "同一纵向列表反向拒绝滚动"
                                : "该列表的原生反向动作不返回拒绝证据（" + step
                                + "），以已证实能换屏的「回到顶部」按钮＋内容连续不变为准")
                                + "，连续内容不变=" + unchanged
                                + "，全程未派发向下手势；" + progress.summary());
                        r.recordDiagnostic("目录回顶已确认");
                        return;
                    }
                    throw topFailure(r, progress, "目录物理位置已到顶部，但当前首行不是先前首行「"
                            + firstRowTitle + "」，目录可能已经变化");
                }
                // 容器动过这个列表（说明它确实能滚），却又不肯拒绝：不能用“暂时不动”冒充顶部。
                if (++stalledProbes > READ_RECOVERIES || !clickTop(r, page, progress)) {
                    throw topFailure(r, progress, "目录内容虽不变，但列表仍接受反向滚动，不能确认顶部");
                }
                current = recoveredScreen(r, null, directoryMode, true, false, "边界未确认后再次回顶");
                progress.last = current;
                if (!current.navigationReady()) throw topFailure(r, progress, current.problem());
                unchanged = 0;
            }
            previous = current;
        }
        throw topFailure(r, progress, "目录回顶达到次数上限，仍未同时确认内容稳定和容器已到顶部");
    }

    /** 只认当前窗口的页面标题/选中计数与唯一列表，不能被背后另一页的同 id 按钮带走。 */
    private static int catalogPage(StepRunner r, NodeView root) {
        boolean directory = r.findIn(root, Keys.CATALOG_DIRECTORY_READY) != null;
        boolean picker = r.findIn(root, Keys.SELECTED_COUNT) != null;
        if (directory == picker) return 0;
        List<NodeView> directories = visibleDirectoryLists(r, root);
        List<NodeView> pickers = visibleLists(r, root, Keys.CATALOG_PICKER_LIST);
        if (directories.size() + pickers.size() != 1) return 0;
        return directory && directories.size() == 1 ? 1 : picker && pickers.size() == 1 ? 2 : 0;
    }

    private static boolean clickTop(StepRunner r, int page, TopProgress progress) throws StepRunner.StepFailure {
        if (progress.clicks >= TOP_CLICK_LIMIT) return false;
        NodeView root = r.activeRoot();
        if (catalogPage(r, root) != page) {
            r.log("回顶按钮未点击：当前页面或唯一目录列表已变化");
            return false;
        }
        List<NodeView> matches = new ArrayList<>();
        for (NodeView node : r.findAllIn(root, Keys.CHAPTER_LIST_TOP)) {
            if (node == null || !node.visible() || !node.enabled() || !node.clickable()
                    || !NodeMatcher.hasArea(node) || !NodeMatcher.idMatches(node.viewId(), "goto_top")
                    || !classIs(node.className(), "ImageView") || insideCatalogList(node)) continue;
            matches.add(node);
        }
        if (matches.size() != 1) {
            r.log("回顶按钮未点击：当前页可核实的 goto_top 数量=" + matches.size()
                    + "，改用已确认的纵向列表反向短滑");
            return false;
        }
        NodeView node = matches.get(0);
        progress.clicks++;
        r.log("目录回顶按钮第 " + progress.clicks + "/" + TOP_CLICK_LIMIT + " 次："
                + nodeDescription(node) + "，bounds=" + Arrays.toString(boundsOf(node)));
        boolean pressed = r.pressOrLog("回到顶部", node);
        if (pressed) progress.pressed++;
        return pressed;
    }

    private static boolean insideCatalogList(NodeView node) {
        for (int depth = 0; node != null && depth < 40; depth++, node = node.parent()) {
            if (NodeMatcher.idMatches(node.viewId(), "list_view")
                    || NodeMatcher.idMatches(node.viewId(), "downloadRecycler")) return true;
        }
        return false;
    }

    private static final class TopProgress {
        int clicks;
        /** 真正按下去了几次（手势被拒或节点点不动时不算）。 */
        int pressed;
        int scrolls;
        int probes;
        String entered = "未读到";
        Screen last;

        String summary() {
            return "回顶点击=" + clicks + "（按下 " + pressed + "），反向动作=" + scrolls
                    + "，边界探测=" + probes
                    + "；进入时：" + entered + "；当前：" + (last == null ? "未读到" : last.details());
        }
    }

    private static StepRunner.StepFailure topFailure(StepRunner r, TopProgress progress, String reason) {
        String message = "目录回顶未完成：" + reason + "；" + progress.summary()
                + "。请在目录页保存完整节点快照后重试同步；这次不写账本。";
        r.log(message);
        r.recordDiagnostic("目录回顶未完成");
        return new StepRunner.StepFailure(StepRunner.Kind.TIMEOUT, message);
    }
}
