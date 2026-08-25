package com.example.blb.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.blb.auto.SelectorSet;
import com.example.blb.auto.StepRunner;
import com.example.blb.util.Texts;

/**
 * 只读地验一次「余额读得出来吗」（<b>只存在于 debug 构建</b>）。
 *
 * <p>存在的理由：菠萝包「我的」页那一行余额的数字节点没有 resource-id，只能靠「数字画在标签
 * 正上方」配对读出来（{@code NodeMatcher.countAbove}）。这条路的离线部分有单元测试钉着，
 * 但「从签到面板按返回 → 切到我的 → 真的读到数字」这一段只有真机能验。而跑一趟正式队列会
 * 退登重登 8 个号 —— 那是最招验证码的动作，为了看一眼余额不值得。
 *
 * <p>它<b>不写任何数据</b>：不碰 Room、不改签到日志，只把读到的数字打进 logcat。
 *
 * <pre>
 * adb shell am broadcast -a com.example.blb.debug.READ_BALANCE -n \
 *     com.example.blb/com.example.blb.debug.BalanceProbeReceiver
 * adb logcat -d -s BlbProbe:*
 * </pre>
 */
public class BalanceProbeReceiver extends BroadcastReceiver {

    private static final String TAG = "BlbProbe";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                probe(app);
            } catch (Throwable t) {
                Log.e(TAG, "读余额失败", t);
            } finally {
                pending.finish();
            }
        }, "blb-balance-probe").start();
    }

    private void probe(Context app) throws Exception {
        SelectorSet selectors = SelectorSet.load(app);
        Log.i(TAG, "选择器来自 " + selectors.source());
        StepRunner runner = new StepRunner(app, selectors, new StepRunner.Host() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void log(String message) {
                Log.i(TAG, message);
            }

            @Override
            public StepRunner.Decision awaitUser(String reason) {
                // 探针不许把人拦在这儿等：直接放弃这一次。
                Log.w(TAG, "需要人工处理，探针直接结束：" + reason);
                return StepRunner.Decision.ABORT;
            }
        });
        Texts.Balance balance = runner.readBalanceFromMine();
        Log.i(TAG, "读到的余额：" + balance.describe()
                + "（火券=" + balance.fire + " 代券=" + balance.voucher + "）");
    }
}
