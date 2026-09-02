package com.example.blb.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.blb.R;
import com.example.blb.auto.RunReport;

import java.lang.ref.WeakReference;
import java.util.Date;

/**
 * 一趟跑完之后盖在屏幕正中的那个弹窗。
 *
 * <p>为什么是一个 Activity 而不是通知：使用者是渐冻症患者，手指不能动 —— 他拉不开通知栏，
 * 而任务通知那个频道是 {@code IMPORTANCE_LOW}（进度每几秒刷一行，不能每次都弹横幅），
 * 所以「跑完了没有、成了几个、哪个号没成」这条结论对他等于不存在。做成浮动窗口的 Activity
 * 之后，它自己盖上来，他什么都不用做。原来那条收尾通知保留 —— 那是给旁人和排查用的。
 *
 * <p>它<b>不会自己消失</b>：他可能过一会儿才转头看屏幕，几秒后自动关掉等于这条提示没有过。
 * 但也不会一直挡着 —— 下一趟任务开跑时 {@link #dismissOpen()} 会把它收掉
 * （{@code AutomationService.startQueue} 里调），所以屏幕不会被一句旧结论永久占住。
 *
 * <p>文案由 {@link RunReport} 生成（纯逻辑、有单测）；这一页只管显示。
 */
public class DoneDialogActivity extends AppCompatActivity {

    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_BODY = "body";
    private static final String EXTRA_ALL_GOOD = "all_good";
    private static final String TAG = "BlbAuto";

    /**
     * 现在开着的那一个。
     *
     * <p>弱引用是为了不把 Activity 钉在内存里；{@link #onDestroy} 里也会主动清掉。
     */
    private static WeakReference<DoneDialogActivity> open;

    /**
     * 弹出来。
     *
     * <p>从后台启动 Activity 在 Android 10 起是受限的，本机靠 MIUI 的「后台弹出界面」
     * 权限（op 10021 已 allow）放行 —— 和 {@code SHOW_PAGE} 拉自家二级页是同一条路。
     * 万一被系统拒了也<b>绝不能</b>让整个 Service 崩掉：那一趟的结果已经落库了，
     * 收尾通知也还在，弹不出来只是少了一层提示。
     */
    public static void show(Context context, RunReport report) {
        if (report == null) return;
        Intent intent = new Intent(context, DoneDialogActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_TITLE, report.title)
                .putExtra(EXTRA_BODY, report.body())
                .putExtra(EXTRA_ALL_GOOD, report.allGood);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "跑完的弹窗没弹出来（后台启动界面被拒？）", e);
        }
    }

    /** 下一趟开跑时把上一趟的结论收掉，别让它挡着新任务的界面。 */
    public static void dismissOpen() {
        WeakReference<DoneDialogActivity> ref = open;
        open = null;
        DoneDialogActivity activity = ref == null ? null : ref.get();
        if (activity != null) activity.finish();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showEvenOnLockScreen();
        setContentView(R.layout.activity_done);
        findViewById(R.id.done_ok).setOnClickListener(v -> finish());
        findViewById(R.id.done_log).setOnClickListener(v -> {
            // NEW_TASK 是必要的：这个弹窗跑在自己的 task 里（manifest 里 taskAffinity=""），
            // 不带这个 flag，日志页会被塞进弹窗那个「不进多任务」的 task。
            startActivity(DetailActivity.intent(this, DetailActivity.PAGE_LOG)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
        open = new WeakReference<>(this);
        render(getIntent());
    }

    /** 同一趟里又跑了一次（singleTop）：换成新结论，不要叠第二个弹窗。 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        render(intent);
    }

    @Override
    protected void onDestroy() {
        WeakReference<DoneDialogActivity> ref = open;
        if (ref != null && ref.get() == this) open = null;
        super.onDestroy();
    }

    private void render(@Nullable Intent intent) {
        String title = intent == null ? null : intent.getStringExtra(EXTRA_TITLE);
        String body = intent == null ? null : intent.getStringExtra(EXTRA_BODY);
        boolean allGood = intent != null && intent.getBooleanExtra(EXTRA_ALL_GOOD, false);

        TextView titleView = findViewById(R.id.done_title);
        titleView.setText(title == null || title.isEmpty() ? "任务结束" : title);
        // 出了问题就把标题换成错误色：他隔着一段距离先看到的是颜色，不是字。
        titleView.setTextColor(ContextCompat.getColor(this,
                allGood ? R.color.blb_on_surface : R.color.blb_error));

        // 「什么时候跑完的」很要紧：这个弹窗会一直留着，没有时间就分不清是刚跑完的还是早上那趟。
        ((TextView) findViewById(R.id.done_time)).setText(getString(R.string.done_at,
                android.text.format.DateFormat.getTimeFormat(this).format(new Date())));
        ((TextView) findViewById(R.id.done_body)).setText(
                body == null || body.isEmpty() ? "结果不明" : body);
    }

    /**
     * 锁屏上也照样显示。
     *
     * <p>不点亮屏幕：定时那趟可能是没人在的时候跑的，为一句结论把屏幕点起来只是耗电
     * （况且<b>锁屏密码我们解不开</b>，真锁着的时候他也做不了什么）。
     */
    @SuppressWarnings("deprecation")
    private void showEvenOnLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
    }
}
