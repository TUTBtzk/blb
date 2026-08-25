package com.example.blb.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.blb.R;
import com.example.blb.auto.BlbAccessibilityService;
import com.example.blb.auto.Keys;
import com.example.blb.auto.SelectorSet;
import com.example.blb.util.Prefs;
import com.example.blb.util.Texts;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** 设置页：无障碍开关入口、选择器自检与导出、干跑与调度参数、小米保活引导。 */
public class SettingsFragment extends Fragment {

    private TextView a11yStatus;
    private TextView selectorInfo;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        a11yStatus = v.findViewById(R.id.a11y_status);
        selectorInfo = v.findViewById(R.id.selector_info);

        v.<Button>findViewById(R.id.open_a11y).setOnClickListener(b ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        v.<Button>findViewById(R.id.inspector).setOnClickListener(b ->
                startActivity(new Intent(ctx, InspectorActivity.class)));
        v.<Button>findViewById(R.id.selector_check).setOnClickListener(b -> checkSelectors());
        v.<Button>findViewById(R.id.export_selectors).setOnClickListener(b -> exportSelectors());
        v.<Button>findViewById(R.id.battery).setOnClickListener(b -> openBatterySettings());
        v.<Button>findViewById(R.id.app_details).setOnClickListener(b -> openAppDetails());

        com.google.android.material.materialswitch.MaterialSwitch dryRun = v.findViewById(R.id.dry_run);
        dryRun.setChecked(Prefs.isDryRun(ctx));
        dryRun.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                Prefs.setDryRun(ctx, true);
                return;
            }
            // 关掉干跑就是允许花真火券，必须显式确认一次。
            btn.setChecked(true);
            confirmRealBuy(dryRun);
        });

        com.google.android.material.materialswitch.MaterialSwitch daily = v.findViewById(R.id.daily);
        daily.setChecked(Prefs.isDailyEnabled(ctx));
        daily.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.setDailyEnabled(ctx, checked);
            com.example.blb.work.DailyScheduler.apply(ctx);
            toast(checked ? "已开启每日自动签到" : "已关闭每日自动签到");
        });

        bindInt(v.findViewById(R.id.daily_hour), Prefs.dailyHour(ctx), value -> {
            Prefs.setDailyHour(ctx, value);
            com.example.blb.work.DailyScheduler.apply(ctx);
        });
        bindInt(v.findViewById(R.id.max_chapters), Prefs.maxChaptersPerRun(ctx),
                value -> Prefs.setMaxChaptersPerRun(ctx, value));
        bindInt(v.findViewById(R.id.spend_cap), Prefs.dailySpendCap(ctx),
                value -> Prefs.setDailySpendCap(ctx, value));
        bindInt(v.findViewById(R.id.ads_per_account), Prefs.adsPerAccount(ctx),
                value -> Prefs.setAdsPerAccount(ctx, value));

        // 替按键不等于替观看：视频照真实时长播完，看的人始终是你。
        com.google.android.material.materialswitch.MaterialSwitch adAssist =
                v.findViewById(R.id.ad_assist);
        adAssist.setChecked(Prefs.isAdAssist(ctx));
        adAssist.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.setAdAssist(ctx, checked);
            toast(checked ? "广告按键交给我，视频照原速播给你看"
                    : "改成只提醒：每个广告都停下等你自己点");
        });

        com.google.android.material.materialswitch.MaterialSwitch adJump =
                v.findViewById(R.id.ad_jump);
        adJump.setChecked(Prefs.isAdJump(ctx));
        adJump.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.setAdJump(ctx, checked);
            toast(checked ? "跳转键也替你按，看完 12 秒我按返回带你回来"
                    : "跳转键不替你按，撞上了按返回退回来");
        });

        v.<TextView>findViewById(R.id.about).setText(
                "数据全部存在本机：账号密码用系统密钥库加密，云备份已关闭。\n"
                        + "自动化只操作菠萝包（" + BlbAccessibilityService.TARGET_PACKAGE
                        + "）的真实界面。广告视频照真实时长完整播放，不快进、不跳过。");
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean ready = BlbAccessibilityService.isReady();
        a11yStatus.setText(ready ? R.string.settings_a11y_on : R.string.settings_a11y_off);
        // 这一行是「整个 app 能不能干活」的总闸，所以给它上语义色：开＝绿，没开＝红。
        a11yStatus.setTextColor(ContextCompat.getColor(requireContext(),
                ready ? R.color.blb_ok : R.color.blb_fail));
        SelectorSet set = SelectorSet.load(requireContext());
        selectorInfo.setText("选择器来源：" + set.source() + "，共 " + set.keys().size() + " 个 key");
    }

    /** 焦点离开时保存，避免每敲一个字符就写一次。 */
    private void bindInt(EditText field, int current, IntSink sink) {
        field.setText(String.valueOf(current));
        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            String s = field.getText().toString().trim();
            if (Texts.isBlank(s)) {
                field.setText(String.valueOf(current));
                return;
            }
            try {
                sink.accept(Integer.parseInt(s));
            } catch (NumberFormatException e) {
                field.setText(String.valueOf(current));
            }
        });
    }

    private interface IntSink {
        void accept(int value);
    }

    private void checkSelectors() {
        SelectorSet set = SelectorSet.load(requireContext());
        StringBuilder sb = new StringBuilder();
        sb.append("来源：").append(set.source()).append("\n\n");
        appendGroup(sb, set, "签到", Keys.REQUIRED_FOR_CHECKIN);
        appendGroup(sb, set, "切换账号", Keys.REQUIRED_FOR_SWITCH);
        appendGroup(sb, set, "自动订阅", Keys.REQUIRED_FOR_SUBSCRIBE);
        sb.append("\n已配置的 key：\n").append(TextUtils.join("、", set.keys()));
        sb.append("\n\n注意：这里只查「有没有配」，配得对不对要用节点探测器对着真实界面试匹配。");
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_selector_check)
                .setMessage(sb)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static void appendGroup(StringBuilder sb, SelectorSet set, String name, String[] keys) {
        List<String> missing = set.missing(keys);
        sb.append(name).append('：');
        if (missing.isEmpty()) {
            sb.append("全部已配（").append(keys.length).append(" 个）\n");
        } else {
            sb.append("缺 ").append(TextUtils.join("、", missing)).append('\n');
        }
    }

    /** 把 assets 里的选择器复制到 filesDir，之后改那份就不用重装 App。 */
    private void exportSelectors() {
        Context ctx = requireContext();
        File target = SelectorSet.overrideFile(ctx);
        if (target.isFile()) {
            new AlertDialog.Builder(ctx)
                    .setMessage("已经存在可编辑的副本：\n" + target.getAbsolutePath()
                            + "\n\n覆盖会丢掉你改过的内容。")
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton("覆盖", (d, w) -> doExport(target))
                    .show();
            return;
        }
        doExport(target);
    }

    private void doExport(File target) {
        try (InputStream in = requireContext().getAssets().open(SelectorSet.FILE_NAME);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setMessage("已导出到：\n" + target.getAbsolutePath()
                        + "\n\n改完直接生效，不用重装。用 adb 拉改推回：\n"
                        + "adb pull " + target.getAbsolutePath())
                .setPositiveButton(android.R.string.ok, null)
                .show();
        refreshStatus();
    }

    private void confirmRealBuy(com.google.android.material.materialswitch.MaterialSwitch sw) {
        new AlertDialog.Builder(requireContext())
                .setTitle("关闭干跑？")
                .setMessage("关掉之后自动订阅会真的点下确认、花掉账号里的火券。"
                        + "选择器一旦错位就可能买错书或买错章。\n\n"
                        + "建议先干跑一轮，核对订阅页矩阵无误再关。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("我确认，关闭干跑", (d, w) -> {
                    Prefs.setDryRun(requireContext(), false);
                    Prefs.setRealBuyConfirmed(requireContext(), true);
                    sw.setChecked(false);
                })
                .show();
    }

    /** 小米的电池优化页各版本入口不一，走系统标准 Intent，失败就退回应用详情页。 */
    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return;
        } catch (Exception ignored) {
            // 继续试 MIUI 的自启动管理
        }
        try {
            Intent miui = new Intent().setComponent(new ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            startActivity(miui);
        } catch (Exception e) {
            openAppDetails();
        }
    }

    private void openAppDetails() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", requireContext().getPackageName(), null));
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast("打不开系统设置页");
        }
    }

    private void toast(String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }
}
