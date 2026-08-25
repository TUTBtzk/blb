package com.example.blb.auto;

import com.example.blb.data.Chapter;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 作者更新之后，把账本里的章号重新对到界面上的位置。
 *
 * <p>这本书在连载：随时会多出新的一章。<b>加在末尾</b>的新章不需要这个类 —— 位置没动，
 * 扫一遍目录就自动登记进去了。这个类管的是另一种：作者<b>往中间插了一章</b>（或者改了标题），
 * 之后每一章在界面上的位置都整体后移一格。章号在这个 App 里就是「界面上第几行」，
 * 所以那一刻账本里记的「第 68 章」在界面上已经是第 69 行 —— 照着买会买错章。
 *
 * <p>以前撞上这种情况只能停下报告、等人来看，而这个 App 的使用者按不动屏幕，
 * 于是自动订阅会一直卡着。所以改成：<b>标题才是一章的身份，章号只是它现在排在第几</b>。
 * 按标题把账本里的每一章重新对到界面上的行，章号跟着搬，购买记录挂在 chapter.id 上、
 * 一条都不动。搬不动的情况（买过的那一章在界面上找不到了、同名行认不出是哪一行、
 * 顺序被打乱）仍然停下报告 —— 宁可这一轮什么都不买。
 */
public final class CatalogAlign {

    /** 台账里手工登记的标题可能只有「久违的笑」，界面上是「11   久违的笑」。 */
    private static final Pattern LEADING_NO = Pattern.compile("^\\d{1,5}\\s*");

    /** 该怎么把账本搬到跟界面一致。 */
    public static final class Plan {
        /** 要改章号的：chapter.id → 新章号。 */
        public final Map<Long, Integer> renumber = new LinkedHashMap<>();
        /** 界面上已经没有、又没有任何号买过的空登记，删掉。 */
        public final List<Long> dropIds = new ArrayList<>();
        /** 上面这些空登记的说明，写日志用。 */
        public final List<String> dropped = new ArrayList<>();
        /** 起始章也要跟着搬到的新章号；-1＝不用改。 */
        public int newStartChapterNo = -1;
        /** 不为 null＝对不上又搬不了，这一轮一章都不许买。 */
        public String blocked;

        public boolean nothingToDo() {
            return blocked == null && renumber.isEmpty() && dropIds.isEmpty()
                    && newStartChapterNo < 0;
        }

        /** 给日志和队列备注的一句话。 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (!renumber.isEmpty()) {
                sb.append("作者动过目录：账本里 ").append(renumber.size())
                        .append(" 章的章号跟着界面搬了位置（购买记录挂在章节上，一条都没动）");
            }
            if (!dropped.isEmpty()) {
                if (sb.length() > 0) sb.append('；');
                sb.append("删掉 ").append(dropped.size())
                        .append(" 条界面上已经没有、也没人买过的空登记（")
                        .append(first(dropped, 5)).append("）");
            }
            if (newStartChapterNo >= 0) {
                if (sb.length() > 0) sb.append('；');
                sb.append("起始章跟着搬到第").append(newStartChapterNo).append("章（还是原来那一章）");
            }
            return sb.toString();
        }

        /** 前几条拼一句话。不用 TextUtils：这个类要在纯 JVM 单测里跑。 */
        private static String first(List<String> items, int max) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size() && i < max; i++) {
                if (i > 0) sb.append('、');
                sb.append(items.get(i));
            }
            if (items.size() > max) sb.append("…");
            return sb.toString();
        }
    }

    private CatalogAlign() {
    }

    /**
     * 算出该怎么搬。纯函数，不碰数据库 —— 这条判据错一次就会买错章，必须能单测。
     *
     * @param ledger       账本里这本书的章节（顺序无所谓，内部按章号排）
     * @param rows         这次扫到的行，下标 +1 就是界面上的位置
     * @param startFrom    现在设的起始章号
     * @param realPurchased 有号真买过（或锁位回填过）的 chapter.id；这些章绝不许悄悄删掉
     */
    public static Plan plan(List<Chapter> ledger, List<CatalogScanner.Row> rows,
                            int startFrom, Set<Long> realPurchased) {
        Plan plan = new Plan();
        if (ledger == null || ledger.isEmpty() || rows == null || rows.isEmpty()) return plan;

        List<Chapter> sorted = new ArrayList<>(ledger);
        sorted.sort((a, b) -> Integer.compare(a.chapterNo, b.chapterNo));
        Index index = new Index(rows);
        Map<Integer, Chapter> claimed = new HashMap<>();
        Map<Long, Integer> target = new LinkedHashMap<>();
        Set<Long> owned = realPurchased == null ? new HashSet<Long>() : realPurchased;

        for (Chapter c : sorted) {
            int to = locate(c, index, rows.size(), owned, plan);
            if (plan.blocked != null) return plan;
            if (to <= 0) continue;   // 已经决定删掉，或者没登记标题、留在原位
            Chapter other = claimed.put(to, c);
            if (other != null) {
                plan.blocked = "账本里第" + other.chapterNo + "章和第" + c.chapterNo
                        + "章都对到了界面上第 " + to + " 行「" + rows.get(to - 1).title
                        + "」，认不出谁是谁";
                return plan;
            }
            target.put(c.id, to);
            if (to != c.chapterNo) plan.renumber.put(c.id, to);
        }

        String scrambled = checkOrder(sorted, target);
        if (scrambled != null) {
            plan.blocked = scrambled;
            plan.renumber.clear();
            plan.dropIds.clear();
            plan.dropped.clear();
            return plan;
        }
        plan.newStartChapterNo = newStart(sorted, target, startFrom);
        return plan;
    }

    /**
     * 这一章现在该排在第几行。返回 0＝不用管它（没登记标题，或者已经决定删掉）。
     *
     * <p>三种搬不动的情况直接写进 {@code plan.blocked}：买过的那一章在界面上找不到了、
     * 界面上有好几行同名认不出是哪一行、账本比界面长而这一章又买过。
     */
    private static int locate(Chapter c, Index index, int rowCount, Set<Long> owned, Plan plan) {
        boolean bought = owned.contains(c.id);
        if (Texts.isBlank(c.title)) {
            // 以前手工登记、连标题都没有的行。认不出身份，只能按老位置算；越界就得停下。
            if (c.chapterNo >= 1 && c.chapterNo <= rowCount) return c.chapterNo;
            plan.blocked = "账本里有第" + c.chapterNo + "章（没登记标题），界面上一共只有 "
                    + rowCount + " 行，认不出它是哪一章";
            return 0;
        }
        List<Integer> hits = index.find(c.title);
        if (hits.isEmpty()) {
            if (bought) {
                plan.blocked = "账本里第" + c.chapterNo + "章「" + c.title.trim()
                        + "」有号买过，界面上却找不到这一行了（作者改了标题或删了这一章）";
                return 0;
            }
            plan.dropIds.add(c.id);
            plan.dropped.add("第" + c.chapterNo + "章「" + c.title.trim() + "」");
            return 0;
        }
        if (hits.size() == 1) return hits.get(0);
        if (hits.contains(c.chapterNo)) return c.chapterNo;   // 同名多行，位置没动就按老位置
        plan.blocked = "界面上有 " + hits.size() + " 行都叫「" + c.title.trim()
                + "」，认不出账本里的第" + c.chapterNo + "章是哪一行";
        return 0;
    }

    /**
     * 搬完之后顺序必须还是原来那个顺序（章号越大、排得越后）。
     *
     * <p>顺序被打乱说明作者重排了章节，或者我们认错了行 —— 这时候「下一章该买哪一章」
     * 已经没有意义，宁可停下。
     */
    private static String checkOrder(List<Chapter> sorted, Map<Long, Integer> target) {
        Chapter prev = null;
        int prevTo = 0;
        for (Chapter c : sorted) {
            Integer to = target.get(c.id);
            if (to == null) continue;
            if (prev != null && to <= prevTo) {
                return "搬完之后顺序反了：账本里第" + prev.chapterNo + "章对到界面第 " + prevTo
                        + " 行，第" + c.chapterNo + "章却对到第 " + to + " 行";
            }
            prev = c;
            prevTo = to;
        }
        return null;
    }

    /**
     * 起始章跟着搬。「从第 48 章起买」说的是<b>那一章</b>，不是「第 48 个位置」——
     * 作者往前面插了一章，位置就该跟着 +1，否则会把已经买过的那一章又算成待买。
     */
    private static int newStart(List<Chapter> sorted, Map<Long, Integer> target, int startFrom) {
        if (startFrom <= 1) return -1;
        for (Chapter c : sorted) {
            if (c.chapterNo != startFrom) continue;
            Integer to = target.get(c.id);
            return to == null || to == startFrom ? -1 : to;
        }
        return -1;
    }

    /** 界面上的行按标题建索引：先按行文本原样对，对不上再按「去掉行首标号」对。 */
    private static final class Index {
        private final Map<String, List<Integer>> byRaw = new HashMap<>();
        private final Map<String, List<Integer>> byStripped = new HashMap<>();

        Index(List<CatalogScanner.Row> rows) {
            for (int i = 0; i < rows.size(); i++) {
                String raw = rows.get(i).title == null ? "" : rows.get(i).title.trim();
                add(byRaw, raw, i + 1);
                String stripped = LEADING_NO.matcher(raw).replaceFirst("").trim();
                if (!stripped.equals(raw)) add(byStripped, stripped, i + 1);
            }
        }

        /**
         * 账本里这一章现在排在界面第几行（可能有好几行同名）。
         *
         * <p>四步都要试，因为行文本里<b>印着标号</b>：作者往中间插了一章，「67 乙」在界面上
         * 会变成「68 乙」—— 原样对是对不上的，必须两边都去掉行首标号再对，
         * 也就是<b>认名字、不认号</b>。这正是「章号只是它现在排第几」这条规则的落地处。
         */
        List<Integer> find(String ledgerTitle) {
            String t = ledgerTitle.trim();
            List<Integer> hit = byRaw.get(t);
            if (hit != null) return hit;
            hit = byStripped.get(t);              // 账本里是手工登记的短标题，界面上带标号
            if (hit != null) return hit;
            String stripped = LEADING_NO.matcher(t).replaceFirst("").trim();
            if (!stripped.equals(t)) {
                hit = byRaw.get(stripped);        // 界面上那一行本来就没标号
                if (hit != null) return hit;
                hit = byStripped.get(stripped);   // 两边都带标号，但标号因为插章变了
                if (hit != null) return hit;
            }
            return new ArrayList<>();
        }

        private static void add(Map<String, List<Integer>> map, String key, int position) {
            if (key.isEmpty()) return;
            List<Integer> list = map.get(key);
            if (list == null) {
                list = new ArrayList<>();
                map.put(key, list);
            }
            list.add(position);
        }
    }
}
