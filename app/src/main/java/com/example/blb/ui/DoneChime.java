package com.example.blb.ui;

import android.content.Context;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

/**
 * 「这件事做完了」那一声短响＋一次短震。
 *
 * <p>为什么单独一个类：提示现在有两条路（App 前台走应用内对话框、不在前台走 {@link DoneDialogActivity}
 * 那个浮动窗口），但<b>反馈必须是同一个</b> —— 用户 2026-09-15 要的就是「不看着屏幕也知道跑完了」，
 * 两条路各响一次、或者一条响一条不响，都会让他以为没做成。所以响铃/震动与去重状态都放在这里共用。
 *
 * <p>分寸：<b>静音或震动模式下只震动</b>，绝不硬把声音放出来（那是他自己选的模式）；
 * 系统不放音、这台机器没有马达，都只是少一层提示 —— 结论已经在屏幕上了，绝不能因此崩掉。
 */
final class DoneChime {

    private static final String TAG = "BlbAuto";
    /** 震动时长：一下短震就够，长了反而像来电。 */
    private static final long CHIME_MS = 200L;
    /** 同一秒里连弹两次（系统重建界面也算）只提示一次，不然会连着响两下。 */
    private static final long DEBOUNCE_MS = 800L;

    private static long lastAt;
    /** 正在放的那声提示音；离开提示界面就停掉，别让它在后台响完。 */
    private static Ringtone playing;

    private DoneChime() {
    }

    static void once(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastAt < DEBOUNCE_MS) return;
        lastAt = now;
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            boolean silent = audio != null
                    && audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
            if (!silent) {
                Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                playing = sound == null ? null : RingtoneManager.getRingtone(context, sound);
                if (playing != null) playing.play();
            }
        } catch (Exception e) {
            Log.w(TAG, "完成提示音没放出来", e);
        }
        try {
            Vibrator vibrator = vibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(CHIME_MS,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                // API 26 以下没有 VibrationEffect；这一条只是为了老机器不静音地跳过震动。
                vibrator.vibrate(CHIME_MS);
            }
        } catch (Exception e) {
            Log.w(TAG, "完成震动没做出来", e);
        }
    }

    static void stop() {
        Ringtone ringtone = playing;
        playing = null;
        if (ringtone == null) return;
        try {
            if (ringtone.isPlaying()) ringtone.stop();
        } catch (Exception e) {
            Log.w(TAG, "完成提示音没停掉", e);
        }
    }

    /** API 31 起振动器要从 VibratorManager 拿；老系统仍然走 VIBRATOR_SERVICE。 */
    private static Vibrator vibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager =
                    (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return manager == null ? null : manager.getDefaultVibrator();
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
