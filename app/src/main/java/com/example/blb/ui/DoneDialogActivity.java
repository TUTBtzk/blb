package com.example.blb.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.blb.R;
import com.example.blb.auto.AutomationBus;
import com.example.blb.auto.AutomationService;
import com.example.blb.auto.RunReport;

import java.lang.ref.WeakReference;
import java.util.Date;

/**
 * 「这件事／这一趟做完了」的那张结论卡片：{@link #show} 决定它长在哪儿。
 *
 * <p><b>App 在前台</b>时是一个普通的应用内对话框；<b>不在前台</b>（定时那趟、锁屏、被切走）
 * 时是一个盖在屏幕正中的浮动 Activity —— 为什么需要后者：使用者拉不开通知栏，而任务通知那个
 * 频道是 {@code IMPORTANCE_LOW}（进度每几秒刷一行，不能每次都弹横幅），所以「跑完了没有、
 * 成了几个、哪个号没成」这条结论对他来说等于不存在。浮动窗口会自己盖上来，他什么都不用做。
 * 原来那条收尾通知保留 —— 那是给旁人和排查用的。
 *
 * <p>浮动窗口<b>不会自己消失</b>：他可能过一会儿才转头看屏幕，几秒后自动关掉等于这条提示没有过。
 * 但也不会一直挡着 —— 下一趟任务开跑时 {@link #dismissOpen()} 会把它收掉
 * （{@code AutomationService.startQueue} 里调），所以屏幕不会被一句旧结论永久占住。
 *
 * <p>文案由 {@link RunReport} 生成（纯逻辑、有单测）；两条路共用同一份
 * {@code activity_done.xml} 与 {@link #bind}，所以长得一模一样。
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
     * 把一趟/一件事的结论显示出来。两条路，优先应用内：
     *
     * <ol>
     *   <li><b>App 就在前台</b>（用户自己点的小动作，以及他正盯着看的整套流程）→ 直接在本 App 里
     *       弹一个普通对话框。这条路一定弹得出来，也不需要任何后台权限。</li>
     *   <li><b>App 不在前台</b>（定时那趟、锁屏、被切到别的应用）→ 还是原来那套浮动窗口：
     *       从后台启动 Activity 在 Android 10 起受限，本机靠 MIUI 的「后台弹出界面」权限放行，
     *       和 {@code SHOW_PAGE} 拉自家二级页是同一条路。万一被系统拒了也<b>绝不能</b>让整个
     *       Service 崩掉：那一趟的结果已经落库了，收尾通知也还在，弹不出来只是少一层提示。</li>
     * </ol>
     *
     * <p>为什么要分这两条：2026-09-15 用户报「跑完没有任何弹窗」—— 后台启动界面被系统/MIUI 拦掉时，
     * 连他自己点的小动作（保存账号、清理记录）都会一起看不到，而那时候 App 明明就在前台，
     * 本来根本不用冒这个险。两条路的文案、配色、「看运行日志」入口完全一致（同一份
     * {@code activity_done.xml} + 同一个 {@link RunReport}）。
     */
    public static void show(Context context, RunReport report) {
        if (report == null) return;
        Activity host = AppForeground.currentActivity();
        boolean inApp = AppForeground.routeNow() == AppForeground.Route.IN_APP && host != null;
        if (inApp) {
            // 队列跑完是在工作线程上收尾的（AutomationService 的 worker），而对话框只能在主线程建 ——
            // 所以这里一律 post 到宿主界面的主线程；建不出来（界面正在结束、系统拒绝等）再退回浮动窗口，
            // 绝不能因为一条提示把整个收尾流程崩掉。
            host.runOnUiThread(() -> {
                boolean shown = showInApp(host, report);
                if (!shown) {
                    boolean floating = showFloating(context, report);
                    report(AppForeground.delivery(true, true, false, floating));
                } else {
                    report(AppForeground.delivery(true, true, true, false));
                }
            });
            return;
        }
        boolean floating = showFloating(context, report);
        report(AppForeground.delivery(AppForeground.isForeground(), false, false, floating));
    }

    /**
     * 把「这次提示走了哪条路」写进运行日志。
     *
     * <p>2026-09-16 用户报「跑完还是没有任何弹窗」—— 上一轮只在代码里分了三条路，
     * 但日志里一个字都没有，没法判断到底走到哪一步被拦掉了。这三句话就是给下一次排查用的。
     */
    private static void report(AppForeground.Delivery delivery) {
        try {
            AutomationBus.append("完成提示：" + delivery.note);
        } catch (RuntimeException ignored) {
            // 日志本身不该把提示流程带崩；这条路已经尽力了。
        }
    }

    /**
     * App 不在前台（或应用内那一路没弹出来）时走的路：从后台启动这个浮动 Activity。
     *
     * @return 真的启动出去了返回 true；被系统/MIUI 拒掉返回 false，调用方必须补一条通知 ——
     *         用户 2026-09-16 的现场就是「什么都没看到」，那时候连一条通知都没有。
     */
    private static boolean showFloating(Context context, RunReport report) {
        Intent intent = new Intent(context, DoneDialogActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_TITLE, report.title)
                .putExtra(EXTRA_BODY, report.body())
                .putExtra(EXTRA_ALL_GOOD, report.allGood);
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "跑完的弹窗没弹出来（后台启动界面被拒？），改发通知", e);
            AutomationService.notifyDone(context, report);
            return false;
        }
    }

    /**
     * App 在前台时的那条路：普通对话框，复用浮动窗口那一份排版。
     *
     * @return 弹出去了返回 true；任何异常都返回 false，让调用方退回浮动窗口那条路，绝不吞掉结论。
     */
    private static boolean showInApp(Activity host, RunReport report) {
        try {
            LayoutInflater inflater = LayoutInflater.from(host);
            View view = inflater.inflate(R.layout.activity_done, null);
            AlertDialog dialog = new AlertDialog.Builder(host)
                    .setView(view)
                    .create();
            bind(view, report.title, report.body(), report.allGood);
            view.findViewById(R.id.done_ok).setOnClickListener(v -> dialog.dismiss());
            view.findViewById(R.id.done_log).setOnClickListener(v -> {
                dialog.dismiss();
                // 应用内不需要 NEW_TASK：日志页就在自己的 task 里。
                host.startActivity(DetailActivity.intent(host, DetailActivity.PAGE_LOG));
            });
            dialog.show();
            DoneChime.once(host);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "应用内完成弹窗没弹出来，改用浮动窗口", e);
            return false;
        }
    }

    /**
     * 「这件事做完了」的方便入口。
     *
     * <p>用户 2026-09-15 要的：所有做成了的小事（清理、导入导出、保存账号、保存取证、
     * 补录章节…）也要弹这个窗口，而不是弹一条几秒就没的短提示 —— 他会把手机放在一边，
     * 错过了就再也看不到。文案由 {@link RunReport#ofDone} 生成（纯逻辑、有单测）。
     */
    public static void showDone(Context context, String label, String detail) {
        show(context, RunReport.ofDone(label, detail));
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
        stopChime();
        WeakReference<DoneDialogActivity> ref = open;
        if (ref != null && ref.get() == this) open = null;
        super.onDestroy();
    }

    /**
     * 弹窗盖上来的同时给一声短响或一次短震。
     *
     * <p>用户 2026-09-15 要的：他会把手机放在一边干别的事，弹窗出现时人不一定在看屏幕 ——
     * 响一下／震一下，他才知道「刚才那件事做完了」，然后回头看弹窗上的字。
     *
     * <p>两条分寸：<b>静音或震动模式下只震动</b>，绝不硬把声音放出来（那是他自己选的模式）；
     * 系统不给响、这台机器没有马达，都只是少一层提示 —— 结论已经在屏幕上了，绝不能因此崩掉。
     */
    private void stopChime() {
        DoneChime.stop();
    }

    private void render(@Nullable Intent intent) {
        String title = intent == null ? null : intent.getStringExtra(EXTRA_TITLE);
        String body = intent == null ? null : intent.getStringExtra(EXTRA_BODY);
        boolean allGood = intent != null && intent.getBooleanExtra(EXTRA_ALL_GOOD, false);

        bind(findViewById(R.id.done_root), title, body, allGood);
        // 只有真的要显示一份结论时才提示：onCreate 与 onNewIntent 都经过这里。
        DoneChime.once(this);
    }

    /**
     * 标题、时间、正文、配色只写一份。
     *
     * <p>因为现在有两条路要显示同一份结论（App 前台的应用内对话框、不在前台的浮动窗口）——
     * 各写一份迟早会长得不一样，而用户看到的必须是同一个东西（同字号、同配色、同一个时间格式）。
     *
     * @param root {@code activity_done.xml} 的根视图
     */
    static void bind(View root, String title, String body, boolean allGood) {
        TextView titleView = root.findViewById(R.id.done_title);
        titleView.setText(title == null || title.isEmpty() ? "任务结束" : title);
        // 出了问题就把标题换成错误色：他隔着一段距离先看到的是颜色，不是字。
        titleView.setTextColor(ContextCompat.getColor(root.getContext(),
                allGood ? R.color.blb_on_surface : R.color.blb_error));

        // 「什么时候跑完的」很要紧：这个弹窗会一直留着，没有时间就分不清是刚跑完的还是早上那趟。
        Context context = root.getContext();
        ((TextView) root.findViewById(R.id.done_time)).setText(context.getString(R.string.done_at,
                android.text.format.DateFormat.getTimeFormat(context).format(new Date())));
        ((TextView) root.findViewById(R.id.done_body)).setText(
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
