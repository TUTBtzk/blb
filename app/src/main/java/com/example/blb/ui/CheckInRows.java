package com.example.blb.ui;

import android.content.res.ColorStateList;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.example.blb.R;
import com.example.blb.data.CheckInLog;
import com.example.blb.data.CheckInRow;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.List;

/**
 * 「今日状态」那份数据的两种说法：一整屏一行一个号（{@link #bind}），和压缩成一句
 * （{@link #summary}）。
 *
 * <p>为什么要有摘要那一句：完整列表已经搬进 {@link DetailActivity}，可这个 App 的使用者
 * 手指不能动 —— 一级页面要是只剩一个光按钮，「今天签到成功了几个」对他就等于没了，
 * 因为他没法自己点开看。所以摘要必须自己把最要紧的说完：成了几个、哪个号没成。
 *
 * <p>{@link #summary} 刻意不碰任何 Android API（连 {@code TextUtils.join} 都不用），
 * 这样它能进普通单元测试 —— 界面代码这个项目里没法测。
 */
final class CheckInRows {

    /** 失败的号最多点名几个，再多就只报个数：摘要必须留在一行里。 */
    private static final int MAX_NAMES = 2;

    private CheckInRows() {
    }

    /** 一整屏里的一行：色条 + 号名 + 状态角标 + 消息／时间。 */
    static void bind(View row, CheckInRow r) {
        StatusPalette tone = StatusPalette.forCheckIn(r.status);
        row.findViewById(R.id.accent).setBackgroundColor(color(row, tone.foreground));

        TextView badge = row.findViewById(R.id.badge);
        badge.setVisibility(View.VISIBLE);
        badge.setText(r.statusText());
        badge.setTextColor(color(row, tone.foreground));
        badge.setBackgroundTintList(ColorStateList.valueOf(color(row, tone.container)));

        row.<TextView>findViewById(R.id.line1).setText(r.accountName());

        // 行布局是三行共用的：今日状态只有两件事，第三行要显式收起（复用时会把别的页面的字留下）。
        row.findViewById(R.id.line3).setVisibility(View.GONE);

        StringBuilder sb = new StringBuilder();
        if (!Texts.isBlank(r.message)) join(sb).append(r.message);
        if (r.createdAt > 0) join(sb).append(DateFormat.format("HH:mm:ss", r.createdAt));
        row.<TextView>findViewById(R.id.line2).setText(sb);
    }

    private static StringBuilder join(StringBuilder sb) {
        if (sb.length() > 0) sb.append(" · ");
        return sb;
    }

    private static int color(View row, int res) {
        return ContextCompat.getColor(row.getContext(), res);
    }

    /**
     * 压成一句：「已签到 6/8 · 失败：备用机2 · 还没跑 1 个」。
     *
     * <p>没有数据就返回空串，让调用方自己说「还没有账号」。
     */
    static String summary(List<CheckInRow> rows) {
        if (rows == null || rows.isEmpty()) return "";
        int done = 0;
        int notRun = 0;
        List<String> bad = new ArrayList<>();
        for (CheckInRow r : rows) {
            if (r == null) continue;
            if (r.status == null) notRun++;
            else if (CheckInLog.OK.equals(r.status) || CheckInLog.ALREADY.equals(r.status)) done++;
            else if (!CheckInLog.SKIPPED.equals(r.status)) bad.add(name(r));
        }
        StringBuilder sb = new StringBuilder("已签到 ").append(done).append('/').append(rows.size());
        if (!bad.isEmpty()) join(sb).append("没成的：").append(names(bad));
        if (notRun > 0) join(sb).append("还没跑 ").append(notRun).append(" 个");
        return sb.toString();
    }

    /**
     * 二级页「今日状态」标题下面那一句：「3 个号 · 签到 2/3 · 1 个要人管」。
     *
     * <p>为什么和 {@link #summary} 分开、而不改那一句：{@code summary} 是一级页面入口卡上的
     * 摘要（主流程已经上线，改它会连带改主流程的文案）；这一句是整屏页面的开场白，
     * 先把「一共几个号」说清楚，再说成了几个、有没有要人管的 —— 点进来第一眼要能安心。
     *
     * <p>「跳过」不算要人管：那是他自己按的，和「失败」不是一回事（{@link StatusPalette} 同一条判据）。
     */
    static String pageLine(List<CheckInRow> rows) {
        if (rows == null || rows.isEmpty()) return "";
        int done = 0;
        int notRun = 0;
        int bad = 0;
        int counted = 0;
        for (CheckInRow r : rows) {
            if (r == null) continue;
            counted++;
            if (r.status == null) notRun++;
            else if (CheckInLog.OK.equals(r.status) || CheckInLog.ALREADY.equals(r.status)) done++;
            else if (!CheckInLog.SKIPPED.equals(r.status)) bad++;
        }
        StringBuilder sb = new StringBuilder().append(counted).append(" 个号");
        // 一个都还没成、也没人失败：说「签到 0/8」像在报失败，直接说它还没开始跑。
        if (done == 0 && bad == 0) return sb.append(" · 今天还没跑").toString();
        sb.append(" · 签到 ").append(done).append('/').append(counted);
        if (bad > 0) sb.append(" · ").append(bad).append(" 个要人管");
        if (notRun > 0) sb.append(" · 还没跑 ").append(notRun).append(" 个");
        return sb.toString();
    }

    private static String name(CheckInRow r) {
        String n = r.accountName();
        return Texts.isBlank(n) ? "账号#" + r.accountId : n;
    }

    private static String names(List<String> all) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size() && i < MAX_NAMES; i++) {
            if (i > 0) sb.append('、');
            sb.append(all.get(i));
        }
        if (all.size() > MAX_NAMES) sb.append("等 ").append(all.size()).append(" 个");
        return sb.toString();
    }
}
