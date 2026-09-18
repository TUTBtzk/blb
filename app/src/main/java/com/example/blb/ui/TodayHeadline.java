package com.example.blb.ui;

import com.example.blb.data.CheckInLog;
import com.example.blb.data.CheckInRow;

import java.util.List;

/**
 * 签到页最上面那一行「今天到底怎么样了」：一句人话，不点开也读得懂。
 *
 * <p>为什么要单独算一句：这一行是三个数据源合起来的（启用账号数、今日签到状态、今天新订阅的章数），
 * 而它就是使用者在签到页第一眼看到的东西 —— 「今天跑没跑、成了几个、订了几章」必须在同一行里答完。
 * 合成规则放进普通 JVM 单测（这个项目没有 Robolectric，界面代码测不了），
 * 免得改一个字就把「成了几个」算错。
 *
 * <p>语气与颜色只走三档：<b>绿＝今天该做的都成了、琥珀＝有号要人管、灰＝今天还没跑</b>。
 * 红色留给二级页面里逐个号的那一行 —— 一级页只说「要不要你管」，不再多一种颜色。
 */
final class TodayHeadline {

    private TodayHeadline() {
    }

    /**
     * @param enabledAccounts 启用中的账号数（账号页里勾了「参与自动签到」的那些）
     * @param rows            今日状态，一个启用号一行；状态为 null＝今天还没跑到它
     * @param newChapters     今天真实新订阅的章数（按章去重，免费章不算）
     * @param capped          新订阅的统计窗口被截断了，只能说「≥ N 章」
     */
    static String line(int enabledAccounts, List<CheckInRow> rows, int newChapters, boolean capped) {
        if (enabledAccounts <= 0) return "今天跑不了：没有启用中的账号";
        if (rows == null || rows.isEmpty()) return "今天还没有账号状态可读";
        int done = 0;
        int notRun = 0;
        int failed = 0;
        for (CheckInRow r : rows) {
            if (r == null || r.status == null) {
                notRun++;
            } else if (CheckInLog.OK.equals(r.status) || CheckInLog.ALREADY.equals(r.status)) {
                done++;
            } else if (!CheckInLog.SKIPPED.equals(r.status)) {
                failed++;
            }
        }
        // 一个都还没成（还没跑、或者整队都被跳过）：说「签到 0/8」像是在报失败，
        // 直接说清楚它在等谁 —— 这两种情况都不需要人去管。
        if (done == 0 && failed == 0) {
            return "今天还没跑 · " + enabledAccounts + " 个号待签到";
        }
        StringBuilder sb = new StringBuilder("今天：");
        sb.append(enabledAccounts).append(" 个号 · 签到 ").append(done).append('/').append(rows.size());
        if (failed > 0) sb.append(" · 没成 ").append(failed).append(" 个");
        if (notRun > 0) sb.append(" · 还没跑 ").append(notRun).append(" 个");
        // 一单都没订就不提「新订阅」：那句话是给「跑完这趟有收获」看的。
        if (newChapters > 0) {
            sb.append(" · 新订阅 ").append(capped ? "≥" : "").append(newChapters).append(" 章");
        }
        return sb.toString();
    }

    /** 这一行用什么颜色。判据只有三条，见类注释。 */
    static StatusPalette tone(List<CheckInRow> rows) {
        if (rows == null || rows.isEmpty()) return StatusPalette.IDLE;
        int done = 0;
        boolean bad = false;
        for (CheckInRow r : rows) {
            if (r == null || r.status == null) {
                bad = true;
            } else if (CheckInLog.OK.equals(r.status) || CheckInLog.ALREADY.equals(r.status)) {
                done++;
            } else if (!CheckInLog.SKIPPED.equals(r.status)) {
                bad = true;
            }
        }
        if (done == 0) return StatusPalette.IDLE;
        return bad ? StatusPalette.WARN : StatusPalette.OK;
    }
}
