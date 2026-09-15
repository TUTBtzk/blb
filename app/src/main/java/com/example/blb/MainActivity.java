package com.example.blb;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.example.blb.auto.AccessibilityAccess;
import com.example.blb.ui.AccountsFragment;
import com.example.blb.ui.CheckInFragment;
import com.example.blb.ui.SettingsFragment;
import com.example.blb.ui.SubscriptionFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 四个页签的外壳：账号 / 签到 / 订阅 / 设置。
 * 用 FragmentManager 的 tag 缓存实例，切页签不重建，列表滚动位置和输入框内容都留着。
 */
public class MainActivity extends AppCompatActivity {

    private static final String STATE_TAB = "state_tab";
    private static final int NOTIF_REQUEST = 42;

    private int currentTab = R.id.tab_checkin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        BottomNavigationView nav = findViewById(R.id.nav);
        nav.setOnItemSelectedListener(item -> {
            show(item.getItemId());
            return true;
        });

        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt(STATE_TAB, currentTab);
        }
        nav.setSelectedItemId(currentTab);
        show(currentTab);

        AccessibilityAccess.restoreIfAuthorized(this);
        askNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // MIUI 从最近任务划掉后会清除 secure setting；只要照护者授过权，重开即自动补回。
        AccessibilityAccess.restoreIfAuthorized(this);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    /**
     * 切到某个页签。
     *
     * <p>这里有两处必须小心，出过 bug：
     * <ul>
     *   <li>用 {@code commitNow()} 而不是 {@code commit()}。{@code commit()} 是排队执行的，
     *       紧接着再调一次本方法时 {@code findFragmentByTag} 还看不到刚 add 的那个，于是会
     *       用同一个 tag <b>再加一个</b> —— 两个同样的 Fragment 叠在容器里各自滚动，界面上
     *       就是「今日状态」和「运行日志」的字互相压在一起（2026-08-23 20:42 的截图）。
     *       {@code onCreate} 里 {@code setSelectedItemId} 会触发一次监听、后面又显式调一次，
     *       正好踩中。</li>
     *   <li>顺手把「tag 相同但不是 target」的旧副本 remove 掉：进程被杀后恢复状态时，
     *       之前叠出来的那些副本会一起回来，只 hide 是不够的。</li>
     * </ul>
     */
    private void show(int tabId) {
        currentTab = tabId;
        String tag = "tab_" + tabId;
        Fragment target = getSupportFragmentManager().findFragmentByTag(tag);
        androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f == target) continue;
            if (tag.equals(f.getTag())) tx.remove(f);
            else tx.hide(f);
        }
        if (target == null) {
            tx.add(R.id.container, create(tabId), tag);
        } else {
            tx.show(target);
        }
        tx.commitNow();
    }

    private Fragment create(int tabId) {
        if (tabId == R.id.tab_accounts) {
            return new AccountsFragment();
        }
        if (tabId == R.id.tab_subscription) {
            return new SubscriptionFragment();
        }
        if (tabId == R.id.tab_settings) {
            return new SettingsFragment();
        }
        return new CheckInFragment();
    }

    /** 前台服务的进度通知要靠它，Android 13+ 得运行时申请。拒绝也不影响功能，只是看不到进度。 */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIF_REQUEST);
    }
}
