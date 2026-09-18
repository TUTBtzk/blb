package com.example.blb.ui;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
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
import com.example.blb.auto.AccessibilityAccess;
import com.example.blb.auto.AutomationBus;
import com.example.blb.auto.BlbAccessibilityService;
import com.example.blb.auto.Keys;
import com.example.blb.auto.SelectorSet;
import com.example.blb.util.Prefs;
import com.example.blb.util.Texts;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** 设置页：无障碍开关入口、选择器自检与导出、调度参数、小米保活引导。 */
public class SettingsFragment extends Fragment {

    private TextView a11yStatus;
    private TextView selectorInfo;
    /** 通知权限那一行：查得到状态，所以明写「已允许/未允许」。 */
    private TextView notifyStatus;
    private EditText catalogMaxAge;
    private MaterialSwitch catalogAutoSync;
    private MaterialSwitch alwaysDetailAudit;
    private boolean bindingSubscriptionSettings;
    private boolean subscriptionSettingsPending;

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
        // 2026-09-15：完成提示被「没通知权限 / 没后台弹出界面」整条掐掉，这两条要能自己走过去。
        notifyStatus = v.findViewById(R.id.notify_status);
        v.<Button>findViewById(R.id.open_notification_settings)
                .setOnClickListener(b -> openNotificationSettings());
        v.<Button>findViewById(R.id.open_popup_permission)
                .setOnClickListener(b -> openPopupPermissionSettings());
        refreshNotificationStatus();

        com.google.android.material.materialswitch.MaterialSwitch daily = v.findViewById(R.id.daily);
        daily.setChecked(Prefs.isDailyEnabled(ctx));
        daily.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.setDailyEnabled(ctx, checked);
            com.example.blb.work.DailyScheduler.apply(ctx);
            toast(checked ? "已开启每日自动签到并订阅" : "已关闭每日自动签到并订阅");
        });

        bindInt(v.findViewById(R.id.daily_hour), Prefs.dailyHour(ctx), value -> {
            Prefs.setDailyHour(ctx, value);
            com.example.blb.work.DailyScheduler.apply(ctx);
        });
        bindInt(v.findViewById(R.id.spend_cap), Prefs.dailySpendCap(ctx),
                value -> Prefs.setDailySpendCap(ctx, value));

        catalogMaxAge = v.findViewById(R.id.catalog_max_age_hours);
        catalogAutoSync = v.findViewById(R.id.catalog_auto_sync);
        alwaysDetailAudit = v.findViewById(R.id.always_detail_audit);
        subscriptionSettingsPending = false;
        refreshSubscriptionSettings(true);
        Context app = ctx.getApplicationContext();
        catalogMaxAge.setOnFocusChangeListener((field, hasFocus) -> {
            if (hasFocus || bindingSubscriptionSettings) return;
            int hours;
            try {
                hours = Integer.parseInt(catalogMaxAge.getText().toString().trim());
            } catch (NumberFormatException failure) {
                hours = -1;
            }
            final int requested = hours;
            if (requested == Prefs.catalogMaxAgeHours(app)) return;
            saveSubscriptionSettings(() -> Prefs.setCatalogMaxAgeHours(app, requested));
        });
        catalogAutoSync.setOnCheckedChangeListener((button, enabled) -> {
            if (!bindingSubscriptionSettings) {
                saveSubscriptionSettings(() -> Prefs.setCatalogAutoSync(app, enabled));
            }
        });
        alwaysDetailAudit.setOnCheckedChangeListener((button, enabled) -> {
            if (!bindingSubscriptionSettings) {
                saveSubscriptionSettings(() -> Prefs.setAlwaysDetailAudit(app, enabled));
            }
        });
        // 运行已经选好本轮的护栏，不能在它切号或买章时从设置页换掉判据。
        AutomationBus.busy().observe(getViewLifecycleOwner(), busy -> {
            if (!Boolean.TRUE.equals(busy)) subscriptionSettingsPending = false;
            refreshSubscriptionSettings(true);
            updateSubscriptionSettingsEnabled();
        });

        v.<TextView>findViewById(R.id.about).setText(
                "数据全部存在本机：账号密码用系统密钥库加密，云备份已关闭。\n"
                        + "自动化只操作菠萝包（" + BlbAccessibilityService.TARGET_PACKAGE
                        + "）的真实界面，完成逐号签到、目录同步和订阅记账。");
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStatus();
        refreshSubscriptionSettings(false);
        updateSubscriptionSettingsEnabled();
    }

    private void refreshSubscriptionSettings(boolean force) {
        Context context = getContext();
        if (context == null || catalogMaxAge == null) return;
        bindingSubscriptionSettings = true;
        try {
            if (force || !catalogMaxAge.hasFocus()) {
                catalogMaxAge.setText(String.valueOf(Prefs.catalogMaxAgeHours(context)));
            }
            catalogAutoSync.setChecked(Prefs.isCatalogAutoSync(context));
            alwaysDetailAudit.setChecked(Prefs.isAlwaysDetailAudit(context));
        } finally {
            bindingSubscriptionSettings = false;
        }
    }

    private void updateSubscriptionSettingsEnabled() {
        if (catalogMaxAge == null) return;
        boolean enabled = !AutomationBus.isBusy() && !subscriptionSettingsPending;
        bindingSubscriptionSettings = true;
        try {
            catalogMaxAge.setEnabled(enabled);
            catalogAutoSync.setEnabled(enabled);
            alwaysDetailAudit.setEnabled(enabled);
        } finally {
            bindingSubscriptionSettings = false;
        }
    }

    private void saveSubscriptionSettings(Runnable change) {
        Context context = getContext();
        View sourceView = getView();
        if (context == null || sourceView == null) return;
        if (!LedgerEdits.requireIdle(context)) {
            refreshSubscriptionSettings(true);
            updateSubscriptionSettingsEnabled();
            return;
        }
        subscriptionSettingsPending = true;
        refreshSubscriptionSettings(true);
        updateSubscriptionSettingsEnabled();
        LedgerEdits.submit(context, change, () -> {
            // 页面已重建时不能让旧保存回调改动新输入框。
            if (getView() != sourceView) return;
            subscriptionSettingsPending = false;
            refreshSubscriptionSettings(true);
            updateSubscriptionSettingsEnabled();
        });
    }

    private void refreshStatus() {
        AccessibilityAccess.State state = AccessibilityAccess.state(requireContext());
        int text;
        int color;
        if (state == AccessibilityAccess.State.CONNECTED) {
            text = R.string.settings_a11y_connected;
            color = R.color.blb_ok;
        } else if (state == AccessibilityAccess.State.ENABLED_DISCONNECTED) {
            text = R.string.settings_a11y_connecting;
            color = R.color.blb_warn;
        } else if (AccessibilityAccess.canRestore(requireContext())) {
            text = R.string.settings_a11y_restoring;
            color = R.color.blb_warn;
            AccessibilityAccess.restoreIfAuthorized(requireContext());
        } else {
            text = R.string.settings_a11y_unmanaged;
            color = R.color.blb_fail;
        }
        a11yStatus.setText(text);
        a11yStatus.setTextColor(ContextCompat.getColor(requireContext(), color));
        SelectorSet set = SelectorSet.load(requireContext());
        StringBuilder info = new StringBuilder("选择器来源：").append(set.source())
                .append("\n可用 ").append(set.keys().size()).append(" 个 key\n");
        appendGroup(info, set, "同步目录", Keys.REQUIRED_FOR_CATALOG);
        appendGroup(info, set, "核对订阅清单", Keys.REQUIRED_FOR_AUDIT);
        if (set.hasWarnings()) info.append(set.recoveryHint());
        selectorInfo.setText(info);
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
        sb.append("来源与校验结果：\n").append(set.diagnostics()).append("\n\n");
        appendGroup(sb, set, "签到", Keys.REQUIRED_FOR_CHECKIN);
        appendGroup(sb, set, "切换账号", Keys.REQUIRED_FOR_SWITCH);
        appendGroup(sb, set, "自动订阅", Keys.REQUIRED_FOR_SUBSCRIBE);
        appendGroup(sb, set, "同步目录", Keys.REQUIRED_FOR_CATALOG);
        appendGroup(sb, set, "核对订阅清单", Keys.REQUIRED_FOR_AUDIT);
        sb.append("\n内置补齐本地缺少的 key；同名 key 使用整组本地候选，本地无效项不会自动改用内置。\n")
                .append(set.recoveryHint());
        sb.append("\n\n这里只检查候选格式和必需项；能否命中真实界面，请用节点探测器验证。");
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
            sb.append("缺少可用项 ").append(TextUtils.join("、", missing)).append('\n');
        }
    }

    /** 把 assets 里的选择器复制到 filesDir，之后改那份就不用重装 App。 */
    private void exportSelectors() {
        Context ctx = requireContext();
        File target = SelectorSet.overrideFile(ctx);
        if (target.isFile()) {
            new AlertDialog.Builder(ctx)
                    .setTitle("恢复内置选择器？")
                    .setMessage("当前本地副本：\n" + target.getAbsolutePath()
                            + "\n\n将用当前版本的内置选择器覆盖这份文件。"
                            + "本地选择器修改会被替换，请先备份；账号和账本不会被清空。")
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton("覆盖并恢复", (d, w) -> doExport(target))
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
                .setMessage("已将当前版本的内置选择器导出到：\n" + target.getAbsolutePath()
                        + "\n\n同名 key 使用本地候选，缺少的 key 由内置补齐。"
                        + "修改后下次开始任务即可生效，不用重装；探测器请重新点「打开菠萝包」。")
                .setPositiveButton(android.R.string.ok, null)
                .show();
        refreshStatus();
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

    /**
     * 本应用的通知设置页：通知权限被拒过之后系统不再弹框（代码里也不会去纠缠），
     * 所以这里给一条自己走过去的门。
     */
    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
        try {
            startActivity(intent);
        } catch (Exception e) {
            openAppDetails();
        }
    }

    /**
     * MIUI 的「后台弹出界面」权限页。
     *
     * <p>这一条查不出状态（MIUI 没给出公开的查询接口），所以只给一句说明 + 一颗按钮：
     * App 不在前台时那个浮动完成弹窗靠的就是它，被拒时那一趟跑完屏幕上什么都不会出现。
     * 各版本入口不一，逐个试，最后退回应用详情页。
     */
    private void openPopupPermissionSettings() {
        String pkg = requireContext().getPackageName();
        for (ComponentName page : new ComponentName[]{
                new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity"),
                new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.permissions.AppPermissionsEditorActivity")}) {
            try {
                startActivity(new Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setComponent(page)
                        .putExtra("extra_pkgname", pkg));
                return;
            } catch (Exception ignored) {
                // 试下一个入口
            }
        }
        try {
            startActivity(new Intent("miui.intent.action.APP_PERM_EDITOR")
                    .putExtra("extra_pkgname", pkg));
            return;
        } catch (Exception ignored) {
            // 这台机器不是 MIUI，或者该页被改过：退回应用详情页
        }
        openAppDetails();
    }

    /** 通知权限这一条能在代码里查，状态就明写出来；「后台弹出界面」查不到，只能给说明。 */
    private void refreshNotificationStatus() {
        if (notifyStatus == null) return;
        boolean allowed;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            allowed = ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        } else {
            NotificationManager nm = (NotificationManager)
                    requireContext().getSystemService(Context.NOTIFICATION_SERVICE);
            allowed = nm == null || nm.areNotificationsEnabled();
        }
        notifyStatus.setText(allowed
                ? R.string.settings_notify_allowed : R.string.settings_notify_denied);
        notifyStatus.setTextColor(ContextCompat.getColor(requireContext(),
                allowed ? R.color.blb_ok : R.color.blb_fail));
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
