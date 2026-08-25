package com.example.blb.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.blb.auto.AccountSwitcher;
import com.example.blb.auto.Keys;
import com.example.blb.auto.NodeView;
import com.example.blb.auto.SelectorSet;
import com.example.blb.auto.StepRunner;
import com.example.blb.auto.SubscribeTask;
import com.example.blb.auto.TreeDump;
import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.Chapter;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.util.Texts;

import java.util.List;

/**
 * 「这一行到底是买过了还是没买过」的现场取样器（<b>只存在于 debug 构建</b>）。
 *
 * <p>为什么必须有：2026-08-24 21:21 第一次真买，五杯半雪碧的代券从 53 掉到 33、皓平从 23 掉到 3
 * —— 钱确实付了，可是选择章节页上那两行的<b>锁一直没消失</b>，于是 {@code SubscribeTask} 判成
 * FAILED、账本一条都没记。更要命的是随后的干跑发现：再点这两行，「已选」是 <b>0 章</b>
 * （其他没买过的章照样能勾上）。所以「有没有锁」并不等于「买没买」，而账本和现实已经对不上了。
 *
 * <p>这个探针只看不点钱：它把<b>买过的那一行</b>和<b>没买过的那一行</b>在同一个号、同一屏上
 * 各自的节点树打出来，差别就是「已购买」的真正标记。它<b>绝不点「立即下载」</b>。
 *
 * <pre>
 * adb shell am broadcast -a com.example.blb.debug.PROBE_CHAPTER -p com.example.blb \
 *     --ei acct 1 --ei no 48 --ei no2 51
 * adb logcat -s BlbProbe:* BlbTree:* BlbAuto:*
 * </pre>
 * {@code acct} 是启用账号里的第几个（1 起，默认 1）；{@code no} 是要看的章号（默认全局下一章）；
 * {@code no2} 是用来对照的「没买过」的章号（默认 no+3，传 0 表示不看对照行）。
 */
public class ChapterStateProbeReceiver extends BroadcastReceiver {

    private static final String TAG = "BlbProbe";
    private static final String TREE = "BlbTree";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        int acct = intent == null ? 1 : intent.getIntExtra("acct", 1);
        int no = intent == null ? 0 : intent.getIntExtra("no", 0);
        int no2 = intent == null ? -1 : intent.getIntExtra("no2", -1);
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                probe(app, acct, no, no2);
            } catch (Throwable t) {
                Log.e(TAG, "章节状态探针失败", t);
            } finally {
                pending.finish();
            }
        }, "blb-chapter-probe").start();
    }

    private void probe(Context app, int acct, int no, int no2) throws Exception {
        SubscriptionDao subs = Db.get(app).subscriptionDao();
        AccountDao accountDao = Db.get(app).accountDao();
        Novel novel = subs.targetNovel();
        if (novel == null) {
            Log.w(TAG, "台账里没有「集中订阅目标」，探针不知道该看哪本书");
            return;
        }
        List<Account> accounts = accountDao.loadEnabled();
        if (accounts.isEmpty()) {
            Log.w(TAG, "没有启用的账号");
            return;
        }
        Account account = accounts.get(Math.max(1, Math.min(accounts.size(), acct)) - 1);

        if (no <= 0) {
            Chapter next = subs.findNextUnownedChapterFrom(novel.id, novel.startFrom());
            no = next == null ? novel.startFrom() : next.chapterNo;
        }
        if (no2 < 0) no2 = no + 3;

        StepRunner r = new StepRunner(app, SelectorSet.load(app), new StepRunner.Host() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void log(String message) {
                Log.i("BlbAuto", message);
            }

            @Override
            public StepRunner.Decision awaitUser(String reason) {
                Log.w(TAG, "需要人工处理，探针直接结束：" + reason);
                return StepRunner.Decision.ABORT;
            }
        });

        Log.i(TAG, "要看的是「" + account.displayName() + "」在《" + novel.title
                + "》里的第" + no + "章"
                + (no2 > 0 ? "，对照行是第" + no2 + "章（应该是没买过的）" : "（不看对照行）"));

        AccountSwitcher.ensureLoggedIn(r, account, accountDao, accounts.size() == 1);
        Texts.Balance mine = r.readBalanceFromMine();
        Log.i(TAG, "「我的」页上的余额：" + mine.describe());

        SubscribeTask.openChapterPicker(r, novel);
        Log.i(TAG, "已在选择章节页，进来时「已选」= " + text(r.readText(Keys.SELECTED_COUNT, 3_000)));

        inspect(r, subs, novel, no, "①");
        if (no2 > 0) inspect(r, subs, novel, no2, "②");

        Log.i(TAG, "探针结束 —— 一次都没点「立即下载」，一分券都没花。"
                + "对着上面两棵树找差别：那就是「已购买」的真正标记。");
    }

    /** 把一行的现场完整念出来：点之前的树、点一下之后「已选」几章、然后原样取消掉。 */
    private void inspect(StepRunner r, SubscriptionDao subs, Novel novel, int no, String mark)
            throws StepRunner.StepFailure {
        Chapter chapter = chapterByNo(subs, novel, no);
        if (chapter == null) {
            Log.w(TAG, mark + " 账本里没有第" + no + "章，跳过");
            return;
        }
        String label = "第" + no + "章「" + chapter.title + "」";
        Log.i(TAG, "===== " + mark + " " + label + " =====");

        StepRunner.Outcome row = locate(r, chapter.title);
        if (row == null) {
            Log.w(TAG, mark + " 翻遍选择章节页都没找到这一行（账本里记的标题是「"
                    + chapter.title + "」）");
            return;
        }

        boolean locked = r.findIn(row.node, Keys.CHAPTER_LOCKED) != null;
        boolean owned = r.findIn(row.node, Keys.CHAPTER_OWNED) != null;
        Log.i(TAG, mark + " chapter_locked=" + locked + " chapter_owned=" + owned
                + "（现在的判据只看 chapter_locked，锁在就当成「还没买」）");
        dumpTree(mark + " 点之前", row.node);

        r.clickNode("探针点 " + label, row.node);
        r.waitMillis(1200);
        String selected = r.readText(Keys.SELECTED_COUNT, 3_000);
        int count = Texts.parseCount(selected);
        Log.i(TAG, mark + " 点了这一行之后「已选」= " + text(selected) + "（读成 " + count + " 章）");
        Log.i(TAG, mark + " 实付=" + text(r.readText(Keys.PAY_DETAIL, 2_000))
                + " 标价=" + text(r.readText(Keys.PRICE_HINT, 2_000))
                + " 余额=" + r.readBalance(2_000).describe()
                + " 余额不足提示=" + (r.findAny(Keys.INSUFFICIENT_COUPONS) != null)
                + " 立即下载在不在=" + (r.findAny(Keys.SUBSCRIBE_BUTTON) != null));

        StepRunner.Outcome after = locate(r, chapter.title);
        if (after != null) dumpTree(mark + " 点之后", after.node);

        // 原样退回去：勾上了就取消掉，免得给下一趟留下多余的勾。
        if (count > 0 && after != null) {
            r.clickNode("探针取消勾选 " + label, after.node);
            r.waitMillis(1000);
            Log.i(TAG, mark + " 取消之后「已选」= " + text(r.readText(Keys.SELECTED_COUNT, 3_000)));
        }
    }

    /** 章号→章。DAO 里按章号取的那个方法是包内可见的，debug 包够不着，就自己在列表里找。 */
    private static Chapter chapterByNo(SubscriptionDao subs, Novel novel, int no) {
        for (Chapter c : subs.loadChapters(novel.id)) {
            if (c.chapterNo == no) return c;
        }
        return null;
    }

    private static StepRunner.Outcome locate(StepRunner r, String title)
            throws StepRunner.StepFailure {
        if (Texts.isBlank(title)) return null;
        for (int i = 0; i <= 40; i++) {
            StepRunner.Outcome hit = r.findExactText("行 " + title, title.trim());
            if (hit != null) return hit;
            if (!r.scrollForward()) return null;
            r.sleepHuman();
        }
        return null;
    }

    private static void dumpTree(String label, NodeView node) {
        Log.i(TREE, "----- " + label + "，这一行的节点树开始 -----");
        for (String line : TreeDump.dump(node).split("\n")) {
            if (!line.trim().isEmpty()) Log.i(TREE, line);
        }
        Log.i(TREE, "----- " + label + "，这一行的节点树结束 -----");
    }

    private static String text(String s) {
        return Texts.isBlank(s) ? "读不到" : s.trim();
    }
}
