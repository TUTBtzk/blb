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

        askNotificationPermission();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    private void show(int tabId) {
        currentTab = tabId;
        String tag = "tab_" + tabId;
        Fragment target = getSupportFragmentManager().findFragmentByTag(tag);
        androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f != target) {
                tx.hide(f);
            }
        }
        if (target == null) {
            tx.add(R.id.container, create(tabId), tag);
        } else {
            tx.show(target);
        }
        tx.commit();
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
