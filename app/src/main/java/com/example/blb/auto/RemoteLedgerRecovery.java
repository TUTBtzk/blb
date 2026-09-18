package com.example.blb.auto;

import com.example.blb.data.Chapter;
import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;
import com.example.blb.util.Texts;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把服务器逐章明细变成经过完整校验的历史账，不碰数据库。
 * 2026-09-14 用户确认同章多号订阅是真实历史；补账核实当前账号事实，不能因别号已订而拒记。
 */
final class RemoteLedgerRecovery {

    static final class Resolved {
        final SubscribedDetail.Entry entry;
        final Chapter chapter;

        Resolved(SubscribedDetail.Entry entry, Chapter chapter) {
            this.entry = entry;
            this.chapter = chapter;
        }
    }

    static final class Resolution {
        final boolean ok;
        final String message;
        final List<Resolved> resolved;

        Resolution(boolean ok, String message, List<Resolved> resolved) {
            this.ok = ok;
            this.message = message;
            this.resolved = resolved;
        }
    }

    static final class Plan {
        final boolean ok;
        final String message;
        final List<Purchase> purchases;
        final List<Resolved> resolved;
        final Map<Long, String> backfillNotes;

        Plan(boolean ok, String message, List<Purchase> purchases, List<Resolved> resolved,
             Map<Long, String> backfillNotes) {
            this.ok = ok;
            this.message = message;
            this.purchases = purchases;
            this.resolved = resolved;
            this.backfillNotes = Collections.unmodifiableMap(new LinkedHashMap<>(backfillNotes));
        }
    }

    private RemoteLedgerRecovery() {
    }

    static Plan plan(String who, String book, long accountId, int expected,
                     List<SubscribedDetail.Entry> entries, List<Chapter> chapters,
                     List<PurchaseRow> ledger) {
        String head = who + " 在《" + book + "》上的远端明细不能自动补账：";
        if (entries == null) return fail(head + "订阅明细没读出来");
        if (ledger == null) return fail(head + "本地账本没读出来");
        List<SubscribedDetail.Entry> remote = new ArrayList<>();
        Map<String, SubscribedDetail.Entry> facts = new LinkedHashMap<>();
        long tomorrow = startOfTomorrow(System.currentTimeMillis());
        for (SubscribedDetail.Entry e : entries) {
            // 2026-09-16：作者留空章名（「星彩 第24章」）不是"没读到" —— 卷名+印刷章号确定时
            // 仍有唯一身份（见 SubscribedDetail.Entry.hasChapterIdentity）；而番外那种"整行原文当标题"
            // 的写法（卷名 null、标题非空）也要照旧放行，靠 unnumberedMatches 用原文去对。
            if (e == null || e.chapterNo == 0
                    || (Texts.isBlank(e.title) && !e.hasChapterIdentity())) {
                return fail(head + "有一条明细既没有可核实的章号也没有卷名，无法定位到本地目录里的哪一章");
            }
            String identity = normalized(e.volume) + "|" + e.chapterNo + "|" + normalized(e.title);
            if (facts.put(identity, e) != null) {
                return fail(head + detail(e) + "重复出现");
            }
            remote.add(e);
            if (e.amount <= 0) return fail(head + detail(e) + "花费读不到");
            if (!"代券".equals(e.currency)) {
                return fail(head + detail(e) + "不是明确的代券支付");
            }
            long purchasedAt = date(e.date);
            if (purchasedAt <= 0) return fail(head + detail(e) + "日期读不到");
            if (purchasedAt >= tomorrow) {
                return fail(head + detail(e) + "日期「" + e.date + "」在今天之后");
            }
        }
        if (expected < 0 || remote.size() != expected) {
            return fail(head + "清单说有 " + expected + " 章，逐章明细只完整读到 "
                    + remote.size() + " 章");
        }

        Resolution resolution = resolveAll(head, remote, chapters);
        if (!resolution.ok) return fail(resolution.message);
        List<Resolved> resolved = resolution.resolved;
        Map<Long, Resolved> resolvedIds = new LinkedHashMap<>();
        for (Resolved item : resolved) resolvedIds.put(item.chapter.id, item);

        Map<Long, PurchaseRow> mine = new LinkedHashMap<>();
        Map<Long, List<PurchaseRow>> others = new LinkedHashMap<>();
        Set<String> accountChapters = new HashSet<>();
        for (PurchaseRow row : ledger) {
            if (row == null) return fail(head + "本地账本有一条读不到");
            if (row.accountId == accountId && (row.costCoupons < 0 || row.costVouchers < 0)) {
                return fail(head + "账本里全书第" + row.chapterNo + "章金额读不到");
            }
            if (row.costCoupons <= 0 && row.costVouchers <= 0) continue;
            if (!accountChapters.add(row.accountId + ":" + row.chapterId)) {
                return fail(head + "账本里全书第" + row.chapterNo + "章同一账号有重复付费记录");
            }
            if (row.accountId == accountId) mine.put(row.chapterId, row);
            else {
                List<PurchaseRow> owners = others.get(row.chapterId);
                if (owners == null) {
                    owners = new ArrayList<>();
                    others.put(row.chapterId, owners);
                }
                owners.add(row);
            }
        }
        for (PurchaseRow row : mine.values()) {
            if (!resolvedIds.containsKey(row.chapterId)) {
                return fail(head + "账本已有全书第" + row.chapterNo + "章，远端明细却没有");
            }
        }

        List<Purchase> restore = new ArrayList<>();
        Map<Long, String> notes = new LinkedHashMap<>();
        for (Resolved item : resolved) {
            SubscribedDetail.Entry e = item.entry;
            Chapter chapter = item.chapter;
            PurchaseRow existing = mine.get(chapter.id);
            if (existing != null) {
                long remoteDate = date(e.date);
                if (existing.costCoupons > 0 || existing.costVouchers != e.amount
                        || !SubscribedDetail.sameChapter(existing.chapterTitle, e.title)
                        || !sameDate(existing.purchasedAt, remoteDate)) {
                    return fail(head + detail(e) + "（全书第" + chapter.chapterNo
                            + "章）现有记录的标题、金额或日期与明细不一致");
                }
                continue;
            }
            Purchase p = Purchase.of(accountId, chapter.id, 0, e.amount,
                    Purchase.SRC_REMOTE_DETAIL);
            p.purchasedAt = date(e.date);
            restore.add(p);
            notes.put(chapter.id, backfillNote(who, chapter, others.get(chapter.id)));
        }
        int missing = expected - mine.size();
        if (missing <= 0 || restore.size() != missing) {
            return fail(head + "应补 " + missing + " 章，严格校验后得到 " + restore.size() + " 章");
        }
        return new Plan(true, "远端逐章明细完整核实了 " + expected + " 章，其中账本漏记 "
                + restore.size() + " 章，可以原样补回", restore, resolved, notes);
    }

    /** 2026-09-14 的重复归属是已发生的事实；这句话只在提交成功后用于日志及留痕。 */
    private static String backfillNote(String who, Chapter chapter, List<PurchaseRow> others) {
        String fact = "第 " + chapter.chapterNo + " 章服务器明细确认「" + who + "」订过";
        if (others != null && !others.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (PurchaseRow other : others) names.add("「" + other.accountDisplayName() + "」");
            fact += "，账本中另有" + String.join("、", names) + "的订阅记录";
        }
        return fact + "，已照实补记";
    }

    static Resolution resolveAll(String head, List<SubscribedDetail.Entry> remote,
                                 List<Chapter> chapters) {
        List<Chapter> catalog = new ArrayList<>();
        if (chapters != null) {
            Map<Long, Chapter> ids = new LinkedHashMap<>();
            Map<Integer, Chapter> positions = new LinkedHashMap<>();
            for (Chapter c : chapters) {
                if (c == null) continue;
                if (c.id <= 0 || ids.put(c.id, c) != null) {
                    return resolutionFail(head + "本地目录有重复或无效的章节 id");
                }
                if (c.chapterNo <= 0 || positions.put(c.chapterNo, c) != null) {
                    return resolutionFail(head + "本地目录第" + c.chapterNo + "行重复或无效");
                }
                catalog.add(c);
            }
        }
        List<Resolved> resolved = new ArrayList<>();
        Map<Long, Resolved> resolvedIds = new LinkedHashMap<>();
        if (remote == null) {
            return resolutionFail(head + "订阅明细没读出来");
        }
        for (SubscribedDetail.Entry e : remote) {
            // 同上：空章名（作者没写）有卷名+章号就算有身份，番外那种"原文当标题"也照旧放行。
            if (e == null || e.chapterNo == 0
                    || (Texts.isBlank(e.title) && !e.hasChapterIdentity())) {
                return resolutionFail(head + "有一条明细缺少可核实的章节身份（" + detail(e) + "）");
            }
            Chapter match = resolve(e, catalog);
            if (match == null) return resolutionFail(head + resolutionFailure(e, catalog));
            // 2026-09-14 的番外没有印刷号；只在原文及目录共同证明卷名与完整标题时拆开它们，
            // 章号仍保留 -1，绝不用界面位置冒充作者编号。
            SubscribedDetail.Entry identified = e.known() ? e : new SubscribedDetail.Entry(
                    -1, match.volumeTitle, match.title, e.amount, e.currency, e.date, e.raw);
            Resolved item = new Resolved(identified, match);
            if (resolvedIds.put(match.id, item) != null) {
                return resolutionFail(head + detail(e) + "和另一条明细都指向全书第"
                        + match.chapterNo + "章，不能自动补账");
            }
            resolved.add(item);
        }
        return new Resolution(true, "", resolved);
    }

    private static Resolution resolutionFail(String message) {
        return new Resolution(false, message, new ArrayList<Resolved>());
    }

    static VoucherLedger.Audit auditResolved(String who, String book, long accountId,
                                             List<Resolved> resolved,
                                             List<PurchaseRow> ledger, long now) {
        String head = who + " 在《" + book + "》上逐章核对：";
        List<PurchaseRow> mine = new ArrayList<>();
        List<PurchaseRow> others = new ArrayList<>();
        if (ledger != null) {
            for (PurchaseRow row : ledger) {
                if (row == null || (row.costCoupons <= 0 && row.costVouchers <= 0)) continue;
                if (row.accountId == accountId) mine.add(row);
                else others.add(row);
            }
        }
        return SubscribedDetail.compareResolved(head, who, resolved, mine, others, now,
                false, true, 0, null);
    }

    private static Chapter resolve(SubscribedDetail.Entry entry, List<Chapter> catalog) {
        List<Chapter> candidates = new ArrayList<>();
        for (Chapter chapter : catalog) {
            if (Texts.isBlank(entry.title)) {
                // 作者没写章名（界面上那一行就写着「星彩 第24章」）：没有标题可比，
                // 只能用「卷名 + 印刷章号」定位。判据见 blankTitleMatches。
                if (blankTitleMatches(entry, chapter)) candidates.add(chapter);
                continue;
            }
            if (Texts.isBlank(chapter.title)) continue;
            if (entry.known() && Texts.rowChapterNo(chapter.title) == entry.chapterNo
                    && SubscribedDetail.sameChapter(chapter.title, entry.title)) {
                candidates.add(chapter);
            } else if (!entry.known() && unnumberedMatches(entry, chapter)) {
                candidates.add(chapter);
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * 作者把章名留空时的定位口径：<b>卷名和印刷章号都要确定</b>，而且本地目录里只能有一条符合。
     *
     * <p>为什么卷名是必需的：印刷章号是<b>卷内号</b>，不同卷里会重复出现（「星彩 第24章」和
     * 「铃兰花 第24章」）。少了卷名就可能把明细指到另一卷的同一号上，那比读不出来更危险。
     * 目录里那一行的标题是「第24章」（标号还在），所以按行首标号取出印刷号来比。
     */
    private static boolean blankTitleMatches(SubscribedDetail.Entry entry, Chapter chapter) {
        if (entry == null || chapter == null) return false;
        if (!entry.hasChapterIdentity() || !entry.known()) return false;
        String volume = normalizedVolume(chapter.volumeTitle);
        if (volume.isEmpty() || !volume.equals(normalizedVolume(entry.volume))) return false;
        return Texts.rowChapterNo(chapter.title) == entry.chapterNo;
    }

    /**
     * 2026-09-14 番外会被「没有第 N 章」漏掉，但标题唯一不能替代卷名证据。
     * 老库没有卷名时必须重扫；原文没有卷名前缀或同卷同名有多行时都不猜。
     */
    private static boolean unnumberedMatches(SubscribedDetail.Entry entry, Chapter chapter) {
        if (entry.chapterNo >= 0 || Texts.rowChapterNo(chapter.title) >= 0
                || Texts.isBlank(chapter.volumeTitle)) return false;
        String volume = normalizedVolume(chapter.volumeTitle);
        if (volume.isEmpty()) return false;
        if (!Texts.isBlank(entry.volume)) {
            return volume.equals(normalizedVolume(entry.volume))
                    && normalized(chapter.title).equals(normalized(entry.title));
        }
        String raw = normalized(entry.raw);
        String title = normalized(chapter.title);
        return !raw.isEmpty() && (raw.equals(volume + " " + title)
                || raw.equals("【" + volume + "】 " + title));
    }

    private static String normalizedVolume(String value) {
        String volume = normalized(value);
        if (volume.startsWith("【") && volume.endsWith("】")) {
            volume = normalized(volume.substring(1, volume.length() - 1));
        }
        return volume;
    }

    private static String resolutionFailure(SubscribedDetail.Entry entry, List<Chapter> catalog) {
        if (!entry.known()) {
            int matches = 0;
            for (Chapter chapter : catalog) if (unnumberedMatches(entry, chapter)) matches++;
            return detail(entry) + (matches > 1
                    ? "在本地目录有 " + matches + " 条同卷同标题候选，不能唯一对应"
                    : "不能按卷名和完整标题唯一对应本地目录（卷名缺失或标题不匹配），请同步目录并核对");
        }
        if (Texts.isBlank(entry.title)) {
            // 作者没写章名的那种：说清是按「卷名＋印刷章号」找的，别写得像是我们漏读了标题。
            int matches = 0;
            for (Chapter chapter : catalog) if (blankTitleMatches(entry, chapter)) matches++;
            return detail(entry) + (matches > 1
                    ? "在本地目录有 " + matches + " 条同卷同章号候选，不能唯一对应"
                    : "这一章作者没写章名，按「卷名＋印刷章号」也没能在本地目录里唯一定位"
                    + "（目录里缺卷名，或同步目录还没登记这一章）");
        }
        int numbered = 0;
        int compatible = 0;
        int compatibleElsewhere = 0;
        for (Chapter chapter : catalog) {
            boolean sameNumber = Texts.rowChapterNo(chapter.title) == entry.chapterNo;
            if (sameNumber) numbered++;
            if (Texts.isBlank(chapter.title) || Texts.isBlank(entry.title)
                    || !SubscribedDetail.sameChapter(chapter.title, entry.title)) continue;
            if (sameNumber) compatible++;
            else compatibleElsewhere++;
        }
        if (compatible > 1) {
            return detail(entry) + "在本地目录有 " + compatible
                    + " 条标题兼容的同号候选，不能唯一对应";
        }
        if (compatibleElsewhere > 0) {
            return detail(entry) + "标题对不上：印刷章号和标题分别指向不同目录行，不能自动补账";
        }
        if (numbered == 0) return "本地目录没有" + detail(entry);
        return detail(entry) + "标题对不上或不能唯一对应本地目录（同号候选 "
                + numbered + " 条）";
    }

    private static String normalized(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\u00a0\\u3000]+", " ").trim();
    }

    private static String detail(SubscribedDetail.Entry entry) {
        return (Texts.isBlank(entry.volume) ? "" : entry.volume.trim() + " ")
                + (entry.known() ? "第" + entry.chapterNo + "章" : "无印刷章号的明细")
                + (Texts.isBlank(entry.title) ? "" : "「" + entry.title.trim() + "」");
    }

    static boolean sameDate(long stored, long remote) {
        if (stored <= 0 || remote <= 0) return false;
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        a.setTimeInMillis(stored);
        b.setTimeInMillis(remote);
        return a.get(Calendar.ERA) == b.get(Calendar.ERA)
                && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static long startOfTomorrow(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static Plan fail(String message) {
        return new Plan(false, message, new ArrayList<Purchase>(), new ArrayList<Resolved>(),
                Collections.emptyMap());
    }

    static long date(String value) {
        if (Texts.isBlank(value)) return -1;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setLenient(false);
        try {
            return f.parse(value.trim()).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }
}
