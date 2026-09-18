package com.example.blb.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.blb.R;
import com.example.blb.auto.AccessibilityAccess;
import com.example.blb.auto.BlbAccessibilityService;
import com.example.blb.auto.DiagnosticStore;
import com.example.blb.auto.InspectorCapture;
import com.example.blb.util.Texts;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 节点探测器：把菠萝包真实界面的节点树抓出来，selectors.json 里的每个值都靠它实测。
 *
 * <p>用法：点「打开菠萝包」→ 在菠萝包里翻到要校准的页面（签到页、登录页、目录页…）→
 * 切回本页 → 树和匹配结果就在这里。
 */
public class InspectorActivity extends AppCompatActivity {

    private TextView tree;
    private TextView info;
    private String content = "";
    private long displayedAt;
    private boolean cachedSnapshot;
    private String archivePath;
    private String pendingExportPath;
    private boolean preserveOnResume;
    private ActivityResultLauncher<String> saveLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_inspector);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.inspector_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft() + bars.left, bars.top,
                    v.getPaddingRight() + bars.right, bars.bottom);
            return insets;
        });
        setTitle(R.string.settings_inspector);

        tree = findViewById(R.id.tree);
        info = findViewById(R.id.info);

        saveLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/plain"), this::writeTo);

        Button dump = findViewById(R.id.dump);
        Button save = findViewById(R.id.save);
        Button launch = findViewById(R.id.launch_target);
        Button probe = findViewById(R.id.probe);
        Button evidence = findViewById(R.id.run_evidence);

        dump.setOnClickListener(v -> showSnapshot(true));
        save.setOnClickListener(v -> {
            if (Texts.isBlank(content)) {
                toast("还没有内容可保存");
                return;
            }
            try {
                // 2026-09-15：返回文件选择器会触发 onResume，必须导出点击保存时的正文，不能换成新快照。
                pendingExportPath = DiagnosticStore.freezeExport(this, content).getAbsolutePath();
                preserveOnResume = true;
                saveLauncher.launch(archivePath == null
                        ? "blb-dump-" + System.currentTimeMillis() + ".txt"
                        : new File(archivePath).getName());
            } catch (Exception failure) {
                pendingExportPath = null;
                preserveOnResume = false;
                toast("无法准备保存内容：" + failure.getMessage());
            }
        });
        launch.setOnClickListener(v -> openTarget());
        probe.setOnClickListener(v -> showReport());
        evidence.setOnClickListener(v -> showSavedEvidence());

        if (savedInstanceState != null) {
            archivePath = savedInstanceState.getString("archivePath");
            pendingExportPath = savedInstanceState.getString("pendingExportPath");
            displayedAt = savedInstanceState.getLong("displayedAt");
            cachedSnapshot = savedInstanceState.getBoolean("cachedSnapshot");
            preserveOnResume = savedInstanceState.getBoolean("preserveOnResume");
            if (archivePath != null) {
                showArchive(new File(archivePath));
            } else if (pendingExportPath != null) {
                try {
                    content = DiagnosticStore.readFrozenExport(this, new File(pendingExportPath));
                    renderContent();
                } catch (Exception failure) {
                    toast("待保存正文未读取，请重新抓取或选择运行取证：" + failure.getMessage());
                }
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // 大型完整树不能塞进 Bundle；仅保存私有文件路径，避免 Activity 重建时丢失导出正文。
        outState.putString("archivePath", archivePath);
        outState.putString("pendingExportPath", pendingExportPath);
        outState.putLong("displayedAt", displayedAt);
        outState.putBoolean("cachedSnapshot", cachedSnapshot);
        outState.putBoolean("preserveOnResume", preserveOnResume);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (archivePath != null || pendingExportPath != null || preserveOnResume) {
            if (pendingExportPath == null) preserveOnResume = false;
            updateInfo();
            return;
        }
        // 从菠萝包切回来时自动把刚抓到的树显示出来，省一次点击。
        if (InspectorCapture.capturedAt() > 0) {
            showSnapshot(false);
        } else {
            updateInfo();
        }
    }

    @Override
    protected void onDestroy() {
        InspectorCapture.disarm();
        super.onDestroy();
    }

    private void openTarget() {
        AccessibilityAccess.State state = AccessibilityAccess.state(this);
        if (state == AccessibilityAccess.State.DISABLED) {
            AccessibilityAccess.RestoreResult result = AccessibilityAccess.restoreIfAuthorized(this);
            if (result == AccessibilityAccess.RestoreResult.NOT_AUTHORIZED) {
                toast(getString(R.string.checkin_need_a11y));
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
        }
        if (!BlbAccessibilityService.isConnected()) {
            toast("系统无障碍开关已开启，服务正在连接，请稍候");
            return;
        }
        Intent intent = getPackageManager()
                .getLaunchIntentForPackage(BlbAccessibilityService.TARGET_PACKAGE);
        if (intent == null) {
            toast("这台手机上没装菠萝包（" + BlbAccessibilityService.TARGET_PACKAGE + "）");
            return;
        }
        archivePath = null;
        preserveOnResume = false;
        content = "";
        displayedAt = 0;
        cachedSnapshot = false;
        tree.setText("等待采集菠萝包目标页面，切回后显示快照。");
        InspectorCapture.arm(this);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        toast("翻到要校准的页面后切回本 App");
    }

    /**
     * @param live true 表示用户主动点了抓取：此时优先直接读当前窗口
     *             （分屏／悬浮窗下菠萝包可能仍在前台），读不到再退回最后一份快照。
     */
    private void showSnapshot(boolean live) {
        if (!live && archivePath != null) return;
        if (live) {
            archivePath = null;
            preserveOnResume = false;
        }
        InspectorCapture.Snapshot snapshot = null;
        if (live) {
            snapshot = InspectorCapture.captureNow(this);
            if (snapshot == null && !BlbAccessibilityService.isConnected()) {
                toast(getString(R.string.checkin_need_a11y));
            }
        }
        cachedSnapshot = snapshot == null;
        if (snapshot == null) {
            snapshot = InspectorCapture.snapshot();
        }
        if (snapshot == null || Texts.isBlank(snapshot.content)) {
            content = "";
            displayedAt = 0;
            tree.setText("还没抓到菠萝包的界面。点「打开菠萝包」，翻到目标页面，再切回来。");
            updateInfo();
            return;
        }
        // 2026-09-14 漏番外取证要同时导出各行结构和原树，不能让导出按钮只保存截短的摘要。
        content = snapshot.content;
        displayedAt = snapshot.capturedAt;
        renderContent();
        updateInfo();
    }

    private void showReport() {
        if (archivePath != null) {
            toast("运行取证已包含当时的选择器诊断。要试匹配当前页面，请先抓取。");
            return;
        }
        InspectorCapture.Snapshot snapshot = InspectorCapture.snapshot();
        if (snapshot == null || Texts.isBlank(snapshot.report)) {
            toast("先抓一次界面：点「打开菠萝包」，翻到目标页面再切回来");
            return;
        }
        content = snapshot.report;
        displayedAt = snapshot.capturedAt;
        cachedSnapshot = true;
        renderContent();
        updateInfo();
    }

    private void showSavedEvidence() {
        try {
            DiagnosticStore.Listing listing = DiagnosticStore.recent(this, DiagnosticStore.RECENT_LIMIT);
            AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                    .setTitle("运行取证 · " + listing.summary())
                    .setNegativeButton(R.string.cancel, null);
            if (listing.files.isEmpty()) {
                dialog.setMessage("还没有保存的运行现场。执行「同步目录」或「核对订阅清单」后，可在这里查看并导出。");
            } else {
                String[] labels = new String[listing.files.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = listing.files.get(i).getName();
                dialog.setItems(labels, (d, which) -> showArchive(listing.files.get(which)));
            }
            dialog.show();
        } catch (Exception failure) {
            toast("运行取证列表未读取：" + failure.getMessage());
        }
    }

    private void showArchive(File file) {
        try {
            String saved = DiagnosticStore.read(this, file);
            archivePath = file.getAbsolutePath();
            content = saved;
            displayedAt = file.lastModified();
            cachedSnapshot = false;
            preserveOnResume = true;
            renderContent();
            updateInfo();
        } catch (Exception failure) {
            toast("这份运行取证未读取：" + failure.getMessage());
        }
    }

    private void renderContent() {
        tree.setText(content);
        ScrollView scroll = findViewById(R.id.inspector_scroll);
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void updateInfo() {
        StringBuilder sb = new StringBuilder();
        AccessibilityAccess.State state = AccessibilityAccess.state(this);
        String a11y;
        if (state == AccessibilityAccess.State.CONNECTED) a11y = "已连接";
        else if (state == AccessibilityAccess.State.ENABLED_DISCONNECTED) a11y = "已开启，正在连接";
        else a11y = AccessibilityAccess.canRestore(this)
                ? "系统开关已关闭，可自动恢复" : "系统开关已关闭，未授予托管恢复权限";
        sb.append("无障碍服务：").append(a11y);
        if (archivePath != null) {
            sb.append("\n正在查看运行取证（存档）：").append(new File(archivePath).getName());
        } else if (displayedAt > 0) {
            sb.append(cachedSnapshot ? "　手动采集的缓存快照：" : "　当前手动抓取：")
                    .append(android.text.format.DateFormat.format("HH:mm:ss", displayedAt));
        }
        if (archivePath == null && InspectorCapture.isArmed()) sb.append("　（布防中）");
        info.setText(sb);
    }

    private void writeTo(@Nullable Uri uri) {
        String frozenPath = pendingExportPath;
        pendingExportPath = null;
        if (uri == null) return;
        if (frozenPath == null) {
            toast("没有对应的待保存正文，请重新选择内容后保存");
            return;
        }
        try {
            String frozen = DiagnosticStore.readFrozenExport(this, new File(frozenPath));
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("openOutputStream 返回 null");
                out.write(frozen.getBytes(StandardCharsets.UTF_8));
            }
            DoneDialogActivity.showDone(this, getString(R.string.done_label_save_forensics),
                    getString(R.string.done_forensics_saved, new File(frozenPath).getName()));
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
