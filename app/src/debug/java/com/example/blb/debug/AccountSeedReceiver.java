package com.example.blb.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.blb.crypto.KeyStoreBox;
import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.Db;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 把 {@code files/account_seed.json} 里的账号灌进库（<b>只存在于 debug 构建</b>）。
 *
 * <p>存在的理由只有一个：用户手指动不了，8 个账号没法在账号页一条条手填；而密码必须经
 * {@link KeyStoreBox} 加密才能落库，那一步只能在 App 进程里做。
 *
 * <p>用法：
 * <pre>
 * adb shell run-as com.example.blb sh -c 'cat &gt; files/account_seed.json' &lt; seed.json
 * adb shell am broadcast -a com.example.blb.debug.SEED_ACCOUNTS -n \
 *     com.example.blb/com.example.blb.debug.AccountSeedReceiver
 * adb logcat -d -s BlbSeed:I
 * </pre>
 *
 * <p>密码只在这个进程的内存里出现一次：种子文件读完立刻<b>先覆写再删除</b>，日志里只写数量和
 * 昵称，一个字的密码都不写。按 {@code loginName} 对号更新（没有就新建），所以重复跑是安全的。
 * 不在种子里的旧账号一律<b>不动</b>，只在日志里列出来 —— 删账号这种事不该由一条广播替人决定。
 */
public class AccountSeedReceiver extends BroadcastReceiver {

    private static final String TAG = "BlbSeed";
    private static final String FILE = "account_seed.json";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        // goAsync()：onReceive 一返回进程就可能被回收，那样灌库会在半路被掐断。
        PendingResult pending = goAsync();
        Db.io(() -> {
            try {
                seed(app);
            } catch (Exception e) {
                Log.e(TAG, "灌库失败", e);
            } finally {
                pending.finish();
            }
        });
    }

    private static void seed(Context app) throws Exception {
        File f = new File(app.getFilesDir(), FILE);
        if (!f.isFile()) {
            Log.w(TAG, "没有 files/" + FILE + "，什么都没做");
            return;
        }
        byte[] raw = readAll(f);
        String json = new String(raw, StandardCharsets.UTF_8);
        Arrays.fill(raw, (byte) 0);
        try {
            apply(app, json);
        } finally {
            // 先用同样长度的垃圾覆写一遍再删：明文密码不该在文件系统里留残影。
            shred(f, json.length());
        }
    }

    private static byte[] readAll(File f) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try (InputStream in = new FileInputStream(f)) {
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } finally {
            Arrays.fill(buf, (byte) 0);
        }
        return out.toByteArray();
    }

    private static void apply(Context app, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.getJSONArray("accounts");
        AccountDao dao = Db.get(app).accountDao();
        List<String> seeded = new ArrayList<>();
        int created = 0;
        int updated = 0;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String loginName = o.getString("loginName").trim();
            String nickname = o.optString("nickname", null);
            Account a = dao.byLoginName(loginName);
            if (a == null && nickname != null && !nickname.isEmpty()) {
                // 库里已经有的那几个号是在界面上手建的，登录名未必对得上（三方登录压根没有登录名
                // 可填）。昵称是菠萝包自己显示的、也是切号后用来校验的那个字符串，拿它兜一层，
                // 免得把同一个号灌成两条。
                a = dao.byNickname(nickname);
                if (a != null && !loginName.equals(a.loginName)) {
                    Log.i(TAG, "按昵称对上了库里已有的「" + nickname + "」，登录名 "
                            + a.loginName + " → " + loginName);
                    a.loginName = loginName;
                }
            }
            boolean isNew = a == null;
            if (isNew) {
                a = new Account();
                a.loginName = loginName;
            }
            a.label = o.optString("label", a.label);
            a.loginKind = o.optString("loginKind", Account.KIND_PASSWORD);
            if (nickname != null && !nickname.isEmpty()) a.nickname = nickname;
            a.enabled = o.optBoolean("enabled", true);
            // 队列顺序就是数组顺序 —— 用户给的那张队列表是有次序的，从 1 开始占位。
            a.sortOrder = i + 1;

            String password = o.optString("password", null);
            if (password != null && !password.isEmpty()) {
                KeyStoreBox.Sealed sealed = KeyStoreBox.seal(password);
                a.encPassword = sealed.cipherText;
                a.encIv = sealed.iv;
            }

            if (isNew) {
                dao.insert(a);
                created++;
            } else {
                dao.update(a);
                updated++;
            }
            seeded.add(loginName);
            Log.i(TAG, (isNew ? "新建 " : "更新 ") + (i + 1) + ". " + a.displayName()
                    + " · " + a.loginKindLabel()
                    + (a.needsPassword() ? (a.hasPassword() ? " · 已存密码" : " · 没有密码！") : ""));
        }

        Log.i(TAG, "灌库完成：新建 " + created + " 个，更新 " + updated + " 个");
        for (Account other : dao.loadAll()) {
            if (!seeded.contains(other.loginName)) {
                Log.w(TAG, "库里还有一个不在种子里的账号（没动它）："
                        + other.displayName() + " / " + other.loginName
                        + (other.enabled ? " · 仍在参与签到" : " · 已停用"));
            }
        }
    }

    /** 覆写再删。删不掉也要报出来 —— 留着一个含明文密码的文件比灌库失败更糟。 */
    private static void shred(File f, int length) {
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            byte[] junk = new byte[Math.max(length, 1)];
            Arrays.fill(junk, (byte) '0');
            out.write(junk);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "覆写种子文件失败", e);
        }
        if (!f.delete()) {
            Log.e(TAG, "种子文件删不掉，请手动删除 files/" + FILE);
        } else {
            Log.i(TAG, "种子文件已覆写并删除");
        }
    }
}
