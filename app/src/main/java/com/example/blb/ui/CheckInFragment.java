package com.example.blb.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.lifecycle.LiveData;

import com.example.blb.R;
import com.example.blb.auto.AutomationBus;
import com.example.blb.auto.AutomationService;
import com.example.blb.auto.AccessibilityAccess;
import com.example.blb.auto.Keys;
import com.example.blb.auto.SelectorSet;
import com.example.blb.auto.StepRunner;
import com.example.blb.auto.SubscribeRun;
import com.example.blb.data.Account;
import com.example.blb.data.CheckInRow;
import com.example.blb.data.Db;
import com.example.blb.data.LedgerAudit;
import com.example.blb.data.Novel;
import com.example.blb.util.DayRollover;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.List;

/**
 * 签到页：跑队列、以及队列卡住时替它做决定。
 *
 * <p>今日各账号状态、运行日志原来都挤在这一页上（列表半屏、日志锁死 120dp），
 * 现在各是一行 {@link EntryRowView}，完整内容在
 * {@link DetailActivity} 里占一整屏。
 *
 * <p>每一行都带一句摘要，不点也答得出最要紧的那句 —— 使用者手指不能动，
 * 只有点开才看得到的信息对他等于不存在。
 */
public class CheckInFragment extends Fragment {

    /** 「今天新订阅了几章」的统计窗口；真被截断时由 TodayPurchases.truncated 说出来。 */
    private static final int RECENT_PURCHASE_WINDOW = 500;
    /** 账本核对那一行的窗口，跟订阅页取同一份数据。 */
    private static final int AUDIT_WINDOW = 100;

    private TextView headline;
    private TextView status;
    private View pauseBanner;
    private TextView pauseReason;
    private View runDot;
    private Button run;
    private Button runDaily;
    private Button stop;
    private EntryRowView entryToday;
    private EntryRowView entryLog;
    private EntryRowView entrySuspect;
    private DayRollover todayRollover;
    private LiveData<List<CheckInRow>> todayRows;
    private LiveData<List<LedgerAudit>> auditsLd;
    private long auditNovelId;

    /** 最上面那一行的三个输入：启用账号数、今日状态、今天新订阅的章数。 */
    private int enabledAccounts;
    private List<CheckInRow> today = new ArrayList<>();
    private int newChapters;
    private boolean newChaptersCapped;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_checkin, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        headline = v.findViewById(R.id.today_headline);
        status = v.findViewById(R.id.status);
        pauseBanner = v.findViewById(R.id.pause_banner);
        pauseReason = v.findViewById(R.id.pause_reason);
        runDot = v.findViewById(R.id.run_dot);
        run = v.findViewById(R.id.run);
        runDaily = v.findViewById(R.id.run_daily);
        stop = v.findViewById(R.id.stop);

        entryToday = v.findViewById(R.id.entry_today);
        entryLog = v.findViewById(R.id.entry_log);
        entrySuspect = v.findViewById(R.id.entry_suspect);

        entryToday.setTitle(getString(R.string.checkin_today_header));
        entryToday.setSummary(getString(R.string.detail_today_summary_empty));
        entryToday.setOnClickListener(b -> DetailActivity.open(
                requireContext(), DetailActivity.PAGE_TODAY));

        entryLog.setTitle(getString(R.string.checkin_log_header));
        entryLog.setSummary(getString(R.string.detail_log_summary_idle));
        entryLog.setOnClickListener(b -> DetailActivity.open(
                requireContext(), DetailActivity.PAGE_LOG));

        entrySuspect.setTitle(getString(R.string.detail_suspect_title));
        entrySuspect.setSummary(getString(R.string.detail_suspect_empty));
        entrySuspect.setOnClickListener(b -> DetailActivity.open(
                requireContext(), DetailActivity.PAGE_SUSPECT));

        run.setOnClickListener(b -> start(false));
        runDaily.setOnClickListener(b -> start(true));
        // 2026-09-14 两页重复入口让整套流程含义冲突；集中到这里后仍要展示真实启用数量。
        runDaily.setText(getString(R.string.checkin_run_daily) + "\n"
                + getString(R.string.sub_audit_accounts_loading));
        Db.get(requireContext()).accountDao().observeAll().observe(getViewLifecycleOwner(), accounts -> {
            int enabled = 0;
            if (accounts != null) for (Account account : accounts) if (account.enabled) enabled++;
            enabledAccounts = enabled;
            String detail = accounts == null ? getString(R.string.sub_audit_accounts_loading)
                    : enabled == 0 ? getString(R.string.sub_audit_no_accounts)
                    : getString(R.string.checkin_daily_cost, enabled);
            runDaily.setText(getString(R.string.checkin_run_daily) + "\n" + detail);
            renderHeadline();
        });
        // 「今天新订阅了几章」：在最近订阅记录里数今天的，按章去重、跳过免费章回填（见 TodayPurchases）。
        Db.get(requireContext()).subscriptionDao()
                .observeRecentRows(RECENT_PURCHASE_WINDOW)
                .observe(getViewLifecycleOwner(), rows -> {
                    long since = SubscribeRun.startOfToday();
                    newChapters = TodayPurchases.chapters(rows, since);
                    newChaptersCapped = TodayPurchases.truncated(rows, since);
                    renderHeadline();
                });
        // 账本核对那一行跟着目标小说走：换书之后不能拿上一本的存疑数说话。
        Db.get(requireContext()).subscriptionDao().observeNovels()
                .observe(getViewLifecycleOwner(), this::onNovels);
        stop.setOnClickListener(b -> AutomationBus.cancel());
        v.<Button>findViewById(R.id.resume).setOnClickListener(
                b -> AutomationBus.submitDecision(StepRunner.Decision.CONTINUE));
        v.<Button>findViewById(R.id.skip).setOnClickListener(
                b -> AutomationBus.submitDecision(StepRunner.Decision.SKIP));

        Handler handler = new Handler(Looper.getMainLooper());
        todayRollover = new DayRollover(new DayRollover.Scheduler() {
            @Override
            public void postDelayed(Runnable action, long delayMillis) {
                handler.postDelayed(action, delayMillis);
            }

            @Override
            public void removeCallbacks(Runnable action) {
                handler.removeCallbacks(action);
            }
        }, this::bindToday);

        AutomationBus.status().observe(getViewLifecycleOwner(), s ->
                status.setText(Texts.isBlank(s) ? getString(R.string.checkin_idle) : s));
        AutomationBus.busy().observe(getViewLifecycleOwner(), busy -> {
            boolean on = Boolean.TRUE.equals(busy);
            run.setEnabled(!on);
            runDaily.setEnabled(!on);
        });
        AutomationBus.running().observe(getViewLifecycleOwner(), running -> {
            boolean on = Boolean.TRUE.equals(running);
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

    @Override
    public void onResume() {
        super.onResume();
        if (todayRollover != null && !isHidden()) todayRollover.start();
    }

    @Override
    public void onPause() {
        if (todayRollover != null) todayRollover.stop();
        super.onPause();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (todayRollover == null) return;
        if (hidden) todayRollover.stop();
        else if (isResumed()) todayRollover.start();
    }

    @Override
    public void onDestroyView() {
        if (todayRollover != null) todayRollover.stop();
        todayRollover = null;
        if (todayRows != null) todayRows.removeObservers(getViewLifecycleOwner());
        todayRows = null;
        if (auditsLd != null) auditsLd.removeObservers(getViewLifecycleOwner());
        auditsLd = null;
        auditNovelId = 0;
        super.onDestroyView();
    }

    private void bindToday(String ymd) {
        if (todayRows != null) todayRows.removeObservers(getViewLifecycleOwner());
        entryToday.setTitle(getString(R.string.checkin_today_header) + "（" + ymd + "）");
        entryToday.setSummary("正在读取今日状态…");
        todayRows = Db.get(requireContext()).checkInDao().observeTodayStatus(ymd);
        todayRows.observe(getViewLifecycleOwner(), this::renderToday);
    }

    /** 今日状态那一行的摘要：成了几个、哪个号没成。 */
    private void renderToday(List<CheckInRow> rows) {
        today = rows == null ? new ArrayList<>() : rows;
        String summary = CheckInRows.summary(today);
        entryToday.setSummary(Texts.isBlank(summary)
                ? getString(R.string.detail_today_summary_empty) : summary);
        renderHeadline();
    }

    /**
     * 最上面那一行「今天怎么样了」。
     *
     * <p>三个数据源（启用账号数、今日状态、今天新订阅的章数）谁先回来都可能，
     * 所以这句话不去读数据，只把手上这三份事实交给 {@link TodayHeadline} 合成；
     * 颜色同样只走三档（绿／琥珀／灰），红色留给二级页面里逐个号那一行。
     */
    private void renderHeadline() {
        if (headline == null) return;
        headline.setText(TodayHeadline.line(enabledAccounts, today, newChapters, newChaptersCapped));
        headline.setTextColor(ContextCompat.getColor(
                requireContext(), TodayHeadline.tone(today).foreground));
    }

    /**
     * 账本核对那一行跟着目标小说走。
     *
     * <p>一本都没标目标时跟订阅页一样先认第一本：两页说的必须是同一本书，否则「存疑几条」
     * 会变成另一本书的数。换书要换观察对象，不能只改文字。
     */
    private void onNovels(List<Novel> novels) {
        long target = 0;
        if (novels != null && !novels.isEmpty()) {
            for (Novel n : novels) {
                if (n != null && n.isTarget) {
                    target = n.id;
                    break;
                }
            }
            if (target == 0 && novels.get(0) != null) target = novels.get(0).id;
        }
        if (target == auditNovelId && auditsLd != null) return;
        if (auditsLd != null) auditsLd.removeObservers(getViewLifecycleOwner());
        auditsLd = null;
        auditNovelId = target;
        if (target <= 0) {
            entrySuspect.setSummary(getString(R.string.detail_suspect_no_target));
            return;
        }
        auditsLd = Db.get(requireContext()).auditDao()
                .observeRecentLedgerAudits(target, AUDIT_WINDOW);
        auditsLd.observe(getViewLifecycleOwner(), this::renderAuditEntry);
    }

    /** 账本核对那一行的摘要：存疑几条、修过几条；逐条留痕点进去才看。 */
    private void renderAuditEntry(List<LedgerAudit> audits) {
        String summary = SuspectSummary.shortLine(audits);
        entrySuspect.setSummary(Texts.isBlank(summary)
                ? getString(R.string.detail_suspect_empty) : summary);
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

    /** @param daily true = 签到+订阅一趟走完；false = 只跑签到 */
    private void start(boolean daily) {
        Context ctx = requireContext();
        if (AutomationBus.isBusy()) {
            Toast.makeText(ctx, "有任务正在运行或账本正在更新，请稍后再开始", Toast.LENGTH_SHORT).show();
            return;
        }
        if (AccessibilityAccess.state(ctx) == AccessibilityAccess.State.DISABLED) {
            AccessibilityAccess.RestoreResult restore =
                    AccessibilityAccess.restoreIfAuthorized(ctx);
            if (restore == AccessibilityAccess.RestoreResult.NOT_AUTHORIZED
                    || restore == AccessibilityAccess.RestoreResult.FAILED) {
                new AlertDialog.Builder(ctx)
                        .setMessage(R.string.checkin_need_a11y)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.settings_open_accessibility, (d, w) ->
                                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                        .show();
                return;
            }
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
