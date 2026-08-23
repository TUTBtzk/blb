package com.example.blb.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.blb.R;
import com.example.blb.auto.BlbAccessibilityService;
import com.example.blb.auto.InspectorCapture;
import com.example.blb.auto.TreeDump;
import com.example.blb.util.Texts;

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

        dump.setOnClickListener(v -> showSnapshot(true));
        save.setOnClickListener(v -> {
            if (Texts.isBlank(content)) {
                toast("还没有内容可保存");
                return;
            }
            saveLauncher.launch("blb-dump-" + System.currentTimeMillis() + ".txt");
        });
        launch.setOnClickListener(v -> openTarget());
        probe.setOnClickListener(v -> showReport());
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        if (!BlbAccessibilityService.isReady()) {
            toast(getString(R.string.checkin_need_a11y));
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        InspectorCapture.arm(this);
        Intent intent = getPackageManager()
                .getLaunchIntentForPackage(BlbAccessibilityService.TARGET_PACKAGE);
        if (intent == null) {
            toast("这台手机上没装菠萝包（" + BlbAccessibilityService.TARGET_PACKAGE + "）");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        toast("翻到要校准的页面后切回本 App");
    }

    /**
     * @param live true 表示用户主动点了抓取：此时优先直接读当前窗口
     *             （分屏／悬浮窗下菠萝包可能仍在前台），读不到再退回最后一份快照。
     */
    private void showSnapshot(boolean live) {
        String text = null;
        if (live) {
            BlbAccessibilityService service = BlbAccessibilityService.peek();
            if (service == null) {
                toast(getString(R.string.checkin_need_a11y));
            } else if (service.isTargetForeground()) {
                text = TreeDump.dump(service.root());
            }
        }
        if (Texts.isBlank(text)) {
            text = InspectorCapture.tree();
        }
        if (Texts.isBlank(text)) {
            content = "";
            tree.setText("还没抓到菠萝包的界面。点「打开菠萝包」，翻到目标页面，再切回来。");
            updateInfo();
            return;
        }
        content = text;
        tree.setText(text);
        updateInfo();
    }

    private void showReport() {
        String r = InspectorCapture.report();
        if (Texts.isBlank(r)) {
            toast("先抓一次界面：点「打开菠萝包」，翻到目标页面再切回来");
            return;
        }
        content = r;
        tree.setText(r);
        updateInfo();
    }

    private void updateInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("无障碍服务：")
                .append(BlbAccessibilityService.isReady() ? "已连接" : "未开启");
        long at = InspectorCapture.capturedAt();
        if (at > 0) {
            sb.append("　最后抓取：")
                    .append(android.text.format.DateFormat.format("HH:mm:ss", at));
        }
        if (InspectorCapture.isArmed()) sb.append("　（布防中）");
        info.setText(sb);
    }

    private void writeTo(@Nullable Uri uri) {
        if (uri == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("openOutputStream 返回 null");
            out.write(content.getBytes(StandardCharsets.UTF_8));
            toast("已保存");
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
