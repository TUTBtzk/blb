package com.example.blb.auto;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/** 区分系统开关与当前连接，并在照护者授权后安全地补回本服务。 */
public final class AccessibilityAccess {

    private static final String TAG = "BlbAuto";

    public enum State {
        DISABLED,
        ENABLED_DISCONNECTED,
        CONNECTED
    }

    public enum RestoreResult {
        ALREADY_ENABLED,
        RESTORED,
        NOT_AUTHORIZED,
        FAILED
    }

    private AccessibilityAccess() {
    }

    public static State state(Context context) {
        if (BlbAccessibilityService.isConnected()) return State.CONNECTED;
        return isSystemEnabled(context)
                ? State.ENABLED_DISCONNECTED : State.DISABLED;
    }

    public static boolean isSystemEnabled(Context context) {
        try {
            if (Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 0) return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "读取无障碍总开关失败", e);
        }
        String target = targetComponent(context);
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (manager != null) {
            List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo info : enabled) {
                ResolveInfo resolve = info == null ? null : info.getResolveInfo();
                ServiceInfo service = resolve == null ? null : resolve.serviceInfo;
                if (service != null && sameComponent(
                        service.packageName + "/" + service.name, target)) return true;
            }
        }
        try {
            return containsComponent(Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), target);
        } catch (RuntimeException e) {
            Log.w(TAG, "读取无障碍系统开关失败", e);
            return false;
        }
    }

    public static boolean canRestore(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static RestoreResult restoreIfAuthorized(Context context) {
        Context app = context.getApplicationContext();
        if (isSystemEnabled(app)) {
            ensureMasterSwitch(app);
            return RestoreResult.ALREADY_ENABLED;
        }
        if (!canRestore(app)) return RestoreResult.NOT_AUTHORIZED;
        try {
            String oldValue = Settings.Secure.getString(app.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String merged = mergeComponent(oldValue, targetComponent(app));
            boolean listWritten = Settings.Secure.putString(app.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged);
            boolean masterWritten = Settings.Secure.putInt(app.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            if (listWritten && masterWritten) {
                Log.i(TAG, "系统清理后已自动补回无障碍服务");
                return RestoreResult.RESTORED;
            }
            Log.w(TAG, "补回无障碍服务时系统拒绝写入");
            return RestoreResult.FAILED;
        } catch (SecurityException e) {
            Log.w(TAG, "没有 WRITE_SECURE_SETTINGS，不能自动补回无障碍", e);
            return RestoreResult.NOT_AUTHORIZED;
        } catch (RuntimeException e) {
            Log.e(TAG, "自动补回无障碍失败", e);
            return RestoreResult.FAILED;
        }
    }

    private static void ensureMasterSwitch(Context context) {
        if (!canRestore(context)) return;
        try {
            if (Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 0) {
                Settings.Secure.putInt(context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "无障碍服务条目仍在，但恢复总开关失败", e);
        }
    }

    static String mergeComponent(String current, String target) {
        if (containsComponent(current, target)) return current;
        if (current == null || current.isEmpty()) return target;
        return current.endsWith(":") ? current + target : current + ":" + target;
    }

    static boolean containsComponent(String list, String target) {
        if (list == null || list.isEmpty() || target == null || target.isEmpty()) return false;
        for (String entry : list.split(":", -1)) {
            if (sameComponent(entry, target)) return true;
        }
        return false;
    }

    private static boolean sameComponent(String first, String second) {
        String a = normalizeComponent(first);
        String b = normalizeComponent(second);
        return a != null && a.equals(b);
    }

    private static String normalizeComponent(String flattened) {
        if (flattened == null) return null;
        int slash = flattened.indexOf('/');
        if (slash <= 0 || slash == flattened.length() - 1
                || flattened.indexOf('/', slash + 1) >= 0) return null;
        String pkg = flattened.substring(0, slash).trim();
        String cls = flattened.substring(slash + 1).trim();
        if (pkg.isEmpty() || cls.isEmpty() || pkg.indexOf(' ') >= 0 || cls.indexOf(' ') >= 0) {
            return null;
        }
        if (cls.startsWith(".")) cls = pkg + cls;
        return pkg + "/" + cls;
    }

    public static String targetComponent(Context context) {
        return context.getPackageName() + "/" + BlbAccessibilityService.class.getName();
    }
}
