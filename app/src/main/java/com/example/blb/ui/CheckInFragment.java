package com.example.blb.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
 * 签到页：跑队列、看今日各账号状态、看实时日志、以及队列卡住时替它做决定。
 *
 * <p>广告一律不自动播放：这里只把「哪个号还有广告奖励可领」标出来，让你自己去看。
 */
public class CheckInFragment extends Fragment {

    private TextView status;
    private TextView log;
    private TextView todayHeader;
    private ScrollView logScroll;
    private View pauseBanner;
    private TextView pauseReason;
    private Button run;
    private Button runDaily;
    private Button stop;
    private SimpleAdapter<CheckInRow> adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_checkin, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        status = v.findViewById(R.id.status);
        log = v.findViewById(R.id.log);
        logScroll = v.findViewById(R.id.log_scroll);
        todayHeader = v.findViewById(R.id.today_header);
        pauseBanner = v.findViewById(R.id.pause_banner);
        pauseReason = v.findViewById(R.id.pause_reason);
        run = v.findViewById(R.id.run);
        runDaily = v.findViewById(R.id.run_daily);
        stop = v.findViewById(R.id.stop);

        adapter = new SimpleAdapter<>(R.layout.item_two_line, this::bind);
        RecyclerView list = v.findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.addItemDecoration(new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));
        list.setAdapter(adapter);

        run.setOnClickListener(b -> start(false));
        runDaily.setOnClickListener(b -> start(true));
        stop.setOnClickListener(b -> AutomationBus.cancel());
        v.<Button>findViewById(R.id.resume).setOnClickListener(
                b -> AutomationBus.submitDecision(StepRunner.Decision.CONTINUE));
        v.<Button>findViewById(R.id.skip).setOnClickListener(
                b -> AutomationBus.submitDecision(StepRunner.Decision.SKIP));

        String ymd = Texts.todayYmd();
        todayHeader.setText(getString(R.string.checkin_today_header) + "（" + ymd + "）");
        Db.get(requireContext()).checkInDao().observeTodayStatus(ymd)
                .observe(getViewLifecycleOwner(), this::renderToday);

        AutomationBus.status().observe(getViewLifecycleOwner(), s ->
                status.setText(Texts.isBlank(s) ? getString(R.string.checkin_idle) : s));
        AutomationBus.running().observe(getViewLifecycleOwner(), running -> {
            boolean on = Boolean.TRUE.equals(running);
            run.setEnabled(!on);
            runDaily.setEnabled(!on);
            stop.setEnabled(on);
        });
        AutomationBus.pauseReason().observe(getViewLifecycleOwner(), reason -> {
            boolean paused = !Texts.isBlank(reason);
            pauseBanner.setVisibility(paused ? View.VISIBLE : View.GONE);
            pauseReason.setText(reason);
        });
        AutomationBus.log().observe(getViewLifecycleOwner(), this::renderLog);
    }

    private void renderToday(List<CheckInRow> rows) {
        adapter.submit(rows);
    }

    private void renderLog(List<String> lines) {
        log.setText(lines == null || lines.isEmpty() ? "（还没有日志）" : TextUtils.join("\n", lines));
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void bind(View row, CheckInRow r, int position) {
        row.<TextView>findViewById(R.id.line1).setText(r.accountName() + " · " + r.statusText());
        StringBuilder sb = new StringBuilder();
        String ads = r.adsText();
        if (ads != null) sb.append(ads);
        if (r.adAvailable) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("还有广告没看完");
        }
        if (!Texts.isBlank(r.message)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(r.message);
        }
        if (r.createdAt > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(DateFormat.format("HH:mm:ss", r.createdAt));
        }
        row.<TextView>findViewById(R.id.line2).setText(sb);
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
