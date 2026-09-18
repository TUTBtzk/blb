package com.example.blb.ui;

import com.example.blb.data.LedgerAudit;
import com.example.blb.util.Texts;

import java.util.List;

/**
 * 「核对记录与存疑」那一行的摘要：还有几条存疑、修过几条。
 *
 * <p>为什么要单独一个类：这段文案原来只写在订阅页里，签到页也要挂同一张入口卡
 * （「账本核对（存疑 N 条）」），两处各写一遍迟早会说出不一样的话。计数规则放进 JVM 单测，
 * 免得「存疑」和「补记」被算成一类 —— 它们要求的处置完全不同（存疑要人去核对清单，
 * 补记只是把漏记的章补回账本）。
 *
 * <p>最近一条的时间与依据由调用方读好传进来：{@code LedgerAuditPayload.message} 走 org.json，
 * 那是 Android API，不能进这个类。
 */
final class SuspectSummary {

    private SuspectSummary() {
    }

    /** 签到页那一行：只有计数，够看出「要不要点进去核对」。没有任何记录时返回空串。 */
    static String shortLine(List<LedgerAudit> audits) {
        int[] counts = counts(audits);
        if (counts[0] == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("存疑 ").append(counts[1]).append(" 条");
        if (counts[2] > 0) sb.append(" · 修正 ").append(counts[2]).append(" 条");
        if (counts[3] > 0) sb.append(" · 补记 ").append(counts[3]).append(" 条");
        sb.append(" · 共 ").append(counts[0]).append(" 条核对记录");
        return sb.toString();
    }

    /** 订阅页那一行：计数 + 最近一条的时间与依据。没有任何记录时返回空串。 */
    static String detailLine(List<LedgerAudit> audits, String latestWhen, String latestMessage) {
        int[] counts = counts(audits);
        if (counts[0] == 0) return "";
        return "最近 " + counts[0] + " 条：存疑 " + counts[1] + " · 修正 " + counts[2]
                + "\n最近 " + (Texts.isBlank(latestWhen) ? "时间未记录" : latestWhen) + "："
                + (Texts.isBlank(latestMessage) ? "依据未记录" : latestMessage);
    }

    /**
     * 二级页「核对记录与存疑」标题下面那一句：「最近 20 条 · 存疑 2 · 修正 1 · 补记 17」。
     *
     * <p>为什么不是 {@link #shortLine}：那一句挂在入口卡上，为了让人决定「要不要点进去」，
     * 所以先说存疑；进到整屏里第一眼要看的是「这份留痕一共有多少、都是些什么」，
     * 所以先报总数。没有任何记录时返回空串，由调用方说空态。
     */
    static String pageLine(List<LedgerAudit> audits) {
        int[] counts = counts(audits);
        if (counts[0] == 0) return "";
        StringBuilder sb = new StringBuilder("最近 ").append(counts[0]).append(" 条 · 存疑 ")
                .append(counts[1]).append(" · 修正 ").append(counts[2]);
        if (counts[3] > 0) sb.append(" · 补记 ").append(counts[3]);
        return sb.toString();
    }

    /** @return {总数, 存疑, 修正(删除留痕), 补记} */
    private static int[] counts(List<LedgerAudit> audits) {
        int total = 0;
        int suspects = 0;
        int deletes = 0;
        int backfills = 0;
        if (audits != null) {
            for (LedgerAudit audit : audits) {
                if (audit == null) continue;
                total++;
                if (LedgerAudit.KIND_SUSPECT.equals(audit.kind)) suspects++;
                else if (LedgerAudit.KIND_DELETE.equals(audit.kind)) deletes++;
                else if (LedgerAudit.KIND_BACKFILL.equals(audit.kind)) backfills++;
            }
        }
        return new int[]{total, suspects, deletes, backfills};
    }
}
