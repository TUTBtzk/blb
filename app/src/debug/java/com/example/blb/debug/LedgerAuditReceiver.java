package com.example.blb.debug;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.util.Log;

import com.example.blb.auto.AccountSwitcher;
import com.example.blb.auto.SelectorSet;
import com.example.blb.auto.StepRunner;
import com.example.blb.auto.SubscribeRun;
import com.example.blb.auto.SubscribedDetail;
import com.example.blb.auto.VoucherLedger;
import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.List;

/**
 * 拿「我的 → 代券 → 订阅清单」正式对一次账（<b>只存在于 debug 构建</b>）。
 *
 * <p>和 {@link VoucherLedgerProbeReceiver} 的区别：那一个是<b>取样器</b>，把几屏念进 logcat 供人
 * 校准选择器；这一个跑的是订阅队列里真正会跑的那段代码（{@link VoucherLedger#read} +
 * {@link VoucherLedger#reconcile} + {@link com.example.blb.auto.SubscribedDetail} 的逐章比对
 * + Room 里的付费记录），只是不买任何东西 —— 目的是让「账本对不对」这件事在真买之前就能单独验一遍。
 *
 * <p>核两层：先是清单那一行（按书聚合：订了几章、花了多少火券），再点<b>整行</b>进「订阅明细」
 * 逐章核（哪几章、各花了多少代券）。用户那两条硬约束是逐章的 —— 「每一章只能有一个账号订阅」、
 * 「8 个号最后拼出完整一本」，聚合那一层只答得出「总数差了几章」。
 *
 * <p>它<b>一个字都不写、一分券都不花</b>：只读 Room，不写 Room（切号那一步会由
 * {@link AccountSwitcher} 更新昵称和余额，那是它本来的职责）。
 *
 * <pre>
 * # 只对现在登着的这个号（默认，不切号 —— 切号是退登重登，最招验证码）
 * adb shell am broadcast -a com.example.blb.debug.RECONCILE -p com.example.blb
 * # 对启用账号里的第 N 个（1 起）
 * adb shell am broadcast -a com.example.blb.debug.RECONCILE -p com.example.blb --ei acct 2
 * # 8 个号挨个来（会切 8 次号，人要在旁边看着验证码）
 * adb shell am broadcast -a com.example.blb.debug.RECONCILE -p com.example.blb --ez all true
 * adb logcat -d -s BlbAudit:* BlbAuto:*
 * </pre>
 */
public class LedgerAuditReceiver extends BroadcastReceiver {

    private static final String TAG = "BlbAudit";
    private static final long NAV_TIMEOUT = 12_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        int acct = intent == null ? 0 : intent.getIntExtra("acct", 0);
        boolean all = intent != null && intent.getBooleanExtra("all", false);
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                run(app, acct, all);
            } catch (Throwable t) {
                Log.e(TAG, "对账失败", t);
            } finally {
                pending.finish();
            }
        }, "blb-ledger-audit").start();
    }

    private StepRunner.Host host() {
        return new StepRunner.Host() {
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
                // 验证码一类必须停下等人，而这里没有界面可等 —— 如实中止，绝不自动过。
                Log.w(TAG, "需要人工处理，对账中止：" + reason);
                return StepRunner.Decision.ABORT;
            }
        };
    }

    private void run(Context app, int acct, boolean all) throws Exception {
        SelectorSet selectors = SelectorSet.load(app);
        StepRunner r = new StepRunner(app, selectors, host());
        Log.i(TAG, "选择器来自 " + selectors.source());
        if (!VoucherLedger.configured(selectors)) {
            Log.w(TAG, "selectors.json 里「代券 → 订阅清单」那几条没配齐，对账跑不了 —— "
                    + "缺的是：" + selectors.missing(new String[]{
                    com.example.blb.auto.Keys.MINE_TAB,
                    com.example.blb.auto.Keys.VOUCHER_ENTRY,
                    com.example.blb.auto.Keys.SUBSCRIBED_LIST_ENTRY,
                    com.example.blb.auto.Keys.SUBSCRIBED_BOOK_TITLE,
                    com.example.blb.auto.Keys.SUBSCRIBED_BOOK_SUMMARY}));
            return;
        }

        SubscriptionDao subs = Db.get(app).subscriptionDao();
        AccountDao accountDao = Db.get(app).accountDao();
        Novel novel = SubscribeRun.resolveTarget(subs, host());
        if (novel == null || Texts.isBlank(novel.title)) {
            Log.w(TAG, "台账里没有「集中订阅目标」那本书（或者登记了多本又没标目标）—— "
                    + "不知道该核哪一本，什么都不做");
            return;
        }
        Log.i(TAG, "要核的是《" + novel.title + "》（起始章 第" + novel.startFrom() + "章）");

        List<Account> enabled = accountDao.loadEnabled();
        if (enabled.isEmpty()) {
            Log.w(TAG, "没有启用的账号");
            return;
        }

        if (all) {
            auditEveryAccount(r, accountDao, subs, novel, enabled);
        } else if (acct > 0) {
            Account a = enabled.get(Math.max(1, Math.min(enabled.size(), acct)) - 1);
            Log.w(TAG, "要切到「" + a.displayName() + "」—— 切号是退登重登，最招验证码");
            AccountSwitcher.ensureLoggedIn(r, a, accountDao, enabled.size() == 1);
            auditOne(r, subs, novel, a);
        } else {
            auditCurrentAccount(r, accountDao, subs, novel);
        }
    }

    /** 只核现在登着的那个号：靠「我的」页上的昵称在账号库里对号，对不上就如实说，绝不乱猜。 */
    private void auditCurrentAccount(StepRunner r, AccountDao accountDao,
                                     SubscriptionDao subs, Novel novel)
            throws StepRunner.StepFailure {
        r.launchTarget(NAV_TIMEOUT);
        r.ensureHome(4);
        r.click(com.example.blb.auto.Keys.MINE_TAB, NAV_TIMEOUT);
        String nickname = r.readText(com.example.blb.auto.Keys.NICKNAME, 6_000);
        if (Texts.isBlank(nickname)) {
            Log.w(TAG, "「我的」页上读不到昵称，认不出现在登的是哪个号 —— "
                    + "对账必须知道是谁，什么都不做（要指定就用 --ei acct N）");
            return;
        }
        Account who = accountDao.byNickname(nickname.trim());
        if (who == null) {
            Log.w(TAG, "现在登着的是「" + nickname.trim() + "」，但账号库里没有这个昵称 —— "
                    + "不知道该跟谁的账本对，什么都不做（要指定就用 --ei acct N）");
            return;
        }
        Log.i(TAG, "现在登着的是「" + who.displayName() + "」（按昵称对上的）");
        auditOne(r, subs, novel, who);
    }

    /**
     * 8 个号挨个核一遍 —— 「所有号拼出完整一本、不多订不漏订」是<b>全队</b>的约束，
     * 只核一个号看不出账本整体歪没歪。跑完给一张汇总。
     */
    private void auditEveryAccount(StepRunner r, AccountDao accountDao, SubscriptionDao subs,
                                   Novel novel, List<Account> enabled) {
        List<String> lines = new ArrayList<>();
        int uiTotal = 0;
        int ledgerTotal = 0;
        int unchecked = 0;
        int bad = 0;
        for (Account a : enabled) {
            try {
                AccountSwitcher.ensureLoggedIn(r, a, accountDao, enabled.size() == 1);
                VoucherLedger.Audit audit = auditOne(r, subs, novel, a);
                if (!audit.ok) bad++;
                if (!audit.checked) unchecked++;
                VoucherLedger.Reading ui = lastReading;
                if (ui != null && ui.known()) uiTotal += ui.chapters;
                ledgerTotal += subs.countPaidPurchases(a.id, novel.id);
                lines.add((audit.ok ? (audit.checked ? "  ✓ " : "  ? ") : "  ✗ ") + audit.message);
            } catch (StepRunner.StepFailure e) {
                unchecked++;
                lines.add("  ? " + a.displayName() + " 这一趟没走通：" + e.getMessage());
                Log.w(TAG, a.displayName() + " 没走通", e);
            }
        }
        Log.i(TAG, "===== 汇总：《" + novel.title + "》=====");
        for (String line : lines) Log.i(TAG, line);
        Log.i(TAG, "  界面合计 " + uiTotal + " 章、账本合计 " + ledgerTotal + " 条付费记录；"
                + "对不上的号 " + bad + " 个、没核对上的 " + unchecked + " 个");
        if (bad == 0 && unchecked == 0) {
            Log.i(TAG, "  全队对得上 —— 这一刻账本和服务器说的是同一件事");
        } else {
            Log.w(TAG, "  有号没对上／没核对上 —— 真买之前请先把上面那几条看完");
        }
    }

    /** 读到的那一行留在这儿，给汇总用（对账本身是纯函数，不需要它）。 */
    private VoucherLedger.Reading lastReading;

    private VoucherLedger.Audit auditOne(StepRunner r, SubscriptionDao subs,
                                         Novel novel, Account account)
            throws StepRunner.StepFailure {
        VoucherLedger.Located found = VoucherLedger.locate(r, novel.title);
        VoucherLedger.Reading ui = found.reading;
        lastReading = ui;
        int paid = subs.countPaidPurchases(account.id, novel.id);
        int fire = subs.sumFireSpent(account.id, novel.id);
        VoucherLedger.Audit audit = VoucherLedger.reconcile(
                account.displayName(), novel.title, ui, paid, fire);
        Log.i(TAG, "界面那一行：" + ui.describe());
        Log.i(TAG, "账本这一侧：付费记录 " + paid + " 条、火券合计 " + fire);
        if (audit.ok) {
            Log.i(TAG, (audit.checked ? "对上了：" : "没能核对：") + audit.message);
        } else {
            Log.w(TAG, "对不上（真跑订阅会在这里整趟停下）：" + audit.message);
        }
        VoucherLedger.Audit detail = auditDetail(r, subs, novel, account, found);
        // 读完停在清单页／明细页，别把手机留在那儿。
        r.ensureHome(4);
        if (detail == null) return audit;
        // 逐章那一层更严：聚合对上了但逐章对不上（比如账本把第 N 章挂错了号）照样得停。
        return audit.ok ? detail : audit;
    }

    /**
     * 逐章那一层：点<b>整行</b>进「订阅明细」，把每一条念出来，再跟账本逐章比。
     *
     * <p>这才是「每一章只能有一个账号订阅」的真正判据 —— 聚合那一行只答得出总数。
     * 返回 null＝这一趟没走（选择器没配齐，或清单里压根没有这本书那一行）。
     */
    private VoucherLedger.Audit auditDetail(StepRunner r, SubscriptionDao subs, Novel novel,
                                            Account account, VoucherLedger.Located found)
            throws StepRunner.StepFailure {
        if (!SubscribedDetail.configured(r.selectors())) {
            Log.w(TAG, "selectors.json 里没配「订阅明细」那几条，只做了按书聚合的对账");
            return null;
        }
        if (found.row == null) {
            Log.i(TAG, "清单里没有这本书那一行，逐章这一层没什么可核的");
            return null;
        }
        List<SubscribedDetail.Entry> entries = SubscribedDetail.read(r, found.row, novel.title);
        if (entries == null) {
            Log.w(TAG, "订阅明细页没打开 —— 这一趟没能逐章核对");
        } else {
            Log.i(TAG, "订阅明细 " + entries.size() + " 条（服务器端的事实）：");
            for (SubscribedDetail.Entry e : entries) Log.i(TAG, "  · " + e.describe());
        }
        VoucherLedger.Audit detail = SubscribedDetail.reconcile(
                account.displayName(), account.id, novel.title, entries,
                subs.loadPaidRowsOfNovel(novel.id), System.currentTimeMillis());
        if (detail.ok) {
            Log.i(TAG, (detail.checked ? "逐章对上了：" : "逐章没能核对：") + detail.message);
        } else {
            Log.w(TAG, "逐章对不上（真跑订阅会在这里整趟停下）：" + detail.message);
        }
        return detail;
    }
}
