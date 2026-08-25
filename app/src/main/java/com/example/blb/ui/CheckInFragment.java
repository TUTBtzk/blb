package com.example.blb.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.blb.R;
import com.example.blb.auto.AutomationBus;
import com.example.blb.auto.AutomationService;
import com.example.blb.auto.BlbAccessibilityService;
import com.example.blb.auto.Keys;
import com.example.blb.auto.SelectorSet;
import com.example.blb.auto.StepRunner;
import com.example.blb.data.CheckInRow;
import com.example.blb.data.Db;
import com.example.blb.util.Texts;

import java.util.List;

/**
 * 签到页：跑队列、以及队列卡住时替它做决定。
 *
 * <p>今日各账号状态、运行日志、辅助点击说明这三样原来都挤在这一页上（列表半屏、日志
 * 锁死 120dp、说明折成两行），现在各是一行 {@link EntryRowView}，完整内容在
 * {@link DetailActivity} 里占一整屏。
 *
 * <p>每一行都带一句摘要，不点也答得出最要紧的那句 —— 使用者手指不能动，
 * 只有点开才看得到的信息对他等于不存在。
 *
 * <p>广告一律不自动播放：这里只把「哪个号还有广告奖励可领」数进摘要，让你自己去看。
 */
public class CheckInFragment extends Fragment {

    private TextView status;
    private View pauseBanner;
    private TextView pauseReason;
    private View runDot;
    private Button run;
    private Button runDaily;
    private Button stop;
    private EntryRowView entryToday;
    private EntryRowView entryLog;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_checkin, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        status = v.findViewById(R.id.status);
        pauseBanner = v.findViewById(R.id.pause_banner);
        pauseReason = v.findViewById(R.id.pause_reason);
        runDot = v.findViewById(R.id.run_dot);
        run = v.findViewById(R.id.run);
        runDaily = v.findViewById(R.id.run_daily);
        stop = v.findViewById(R.id.stop);

        entryToday = v.findViewById(R.id.entry_today);
        entryLog = v.findViewById(R.id.entry_log);
        EntryRowView entryNotice = v.findViewById(R.id.entry_notice);

        String ymd = Texts.todayYmd();
        entryToday.setTitle(getString(R.string.checkin_today_header) + "（" + ymd + "）");
        entryToday.setSummary(getString(R.string.detail_today_summary_empty));
        entryToday.setOnClickListener(b -> DetailActivity.open(
                requireContext(), DetailActivity.PAGE_TODAY));

        entryLog.setTitle(getString(R.string.checkin_log_header));
        entryLog.setSummary(getString(R.string.detail_log_summary_idle));
        entryLog.setOnClickListener(b -> DetailActivity.open(
                requireContext(), DetailActivity.PAGE_LOG));

        entryNotice.setTitle(getString(R.string.checkin_notice_header));
        entryNotice.setSummary(getString(R.string.detail_notice_summary));
        entryNotice.setOnClickListener(b -> DetailActivity.open(
                requireContext(), DetailActivity.PAGE_NOTICE));

        run.setOnClickListener(b -> start(false));
        runDaily.setOnClickListener(b -> start(true));
        stop.setOnClickListener(b -> AutomationBus.cancel());
        v.<Button>findViewById(R.id.resume).setOnClickListener(
                b -> AutomationBus.submitDecision(StepRunner.Decision.CONTINUE));
        v.<Button>findViewById(R.id.skip).setOnClickListener(
                b -> AutomationBus.submitDecision(StepRunner.Decision.SKIP));

        Db.get(requireContext()).checkInDao().observeTodayStatus(ymd)
                .observe(getViewLifecycleOwner(), this::renderToday);

        AutomationBus.status().observe(getViewLifecycleOwner(), s ->
                status.setText(Texts.isBlank(s) ? getString(R.string.checkin_idle) : s));
        AutomationBus.running().observe(getViewLifecycleOwner(), running -> {
            boolean on = Boolean.TRUE.equals(running);
            run.setEnabled(!on);
            runDaily.setEnabled(!on);
            stop.setEnabled(on);
            // 状态卡左上那颗点：跑着＝深琥珀，空闲＝灰。隔着屏幕也能一眼看出动没动。
            // （不用主色那个亮蜜黄 —— 10dp 的小圆点用亮黄在白卡上几乎看不见。）
            tint(runDot, on ? R.color.blb_secondary : R.color.blb_idle);
        });
        AutomationBus.pauseReason().observe(getViewLifecycleOwner(), reason -> {
            boolean paused = !Texts.isBlank(reason);
            pauseBanner.setVisibility(paused ? View.VISIBLE : View.GONE);
            pauseReason.setText(reason);
        });
        AutomationBus.log().observe(getViewLifecycleOwner(), this::renderLog);
    }

    /** 今日状态那一行的摘要：成了几个、哪个号没成、广告还有没有没看完的。 */
    private void renderToday(List<CheckInRow> rows) {
        String summary = CheckInRows.summary(rows);
        entryToday.setSummary(Texts.isBlank(summary)
                ? getString(R.string.detail_today_summary_empty) : summary);
    }

    /** 日志那一行的摘要＝最新那一行。要看全部得点进去。 */
    private void renderLog(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            entryLog.setSummary(getString(R.string.detail_log_summary_idle));
            return;
        }
        entryLog.setSummary(lines.get(lines.size() - 1));
    }

    private static int color(View v, @ColorRes int res) {
        return ContextCompat.getColor(v.getContext(), res);
    }

    /** 给形状 drawable 上色（那颗小圆点的底是白的，靠 tint 变颜色）。 */
    private static void tint(View v, @ColorRes int res) {
        v.setBackgroundTintList(ColorStateList.valueOf(color(v, res)));
    }

    /** @param daily true = 签到+广告+订阅 一趟走完；false = 只跑签到 */
    private void start(boolean daily) {
        Context ctx = requireContext();
        if (!BlbAccessibilityService.isReady()) {
            new AlertDialog.Builder(ctx)
                    .setMessage(R.string.checkin_need_a11y)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.settings_open_accessibility, (d, w) ->
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .show();
            return;
        }
        List<String> missing = SelectorSet.load(ctx).missing(Keys.REQUIRED_FOR_CHECKIN);
        if (!missing.isEmpty()) {
            new AlertDialog.Builder(ctx)
                    .setMessage("selectors.json 还缺签到必需的 key：" + TextUtils.join("、", missing)
                            + "\n\n先用设置页的节点探测器抓一次菠萝包签到页，把这些值填好。")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (daily) {
            AutomationService.startDaily(ctx);
        } else {
            AutomationService.startCheckIn(ctx);
        }
        Toast.makeText(ctx, "队列已启动，别去动手机屏幕", Toast.LENGTH_SHORT).show();
    }
}
