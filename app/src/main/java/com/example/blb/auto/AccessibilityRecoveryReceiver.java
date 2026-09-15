package com.example.blb.auto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机或覆盖更新后，在系统允许本进程运行时补回被 MIUI 清掉的无障碍开关。 */
public class AccessibilityRecoveryReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        AccessibilityAccess.RestoreResult result =
                AccessibilityAccess.restoreIfAuthorized(context);
        Log.i("BlbAuto", "无障碍恢复广播 "
                + (intent == null ? "null" : intent.getAction()) + "：" + result);
    }
}
