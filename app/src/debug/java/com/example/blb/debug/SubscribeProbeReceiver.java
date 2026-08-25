package com.example.blb.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.blb.auto.AutomationService;
import com.example.blb.data.Account;
import com.example.blb.data.AppDatabase;
import com.example.blb.data.Chapter;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.util.Prefs;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动订阅的几个 adb 入口（<b>只存在于 debug 构建</b>）。
 *
 * <p>为什么必须有：这些事在界面上都是「按一颗按钮」，而用户手指动不了 ——「让他自己点一下」
 * 不是兜底方案。订阅页那颗「开始自动订阅」他按不着，我又不能替他去点自己的界面。
 *
 * <p>{@code LEDGER} 只读：把目标小说、章节数、每个号买过多少章打进 logcat，用来在开跑之前
 * 确认「台账里到底有没有东西」，末尾还做一次「一章只许一个号」的自检（{@link #auditOwners}）。
 * 它一个字都不写。把私有数据库拷出设备是被拒绝的动作，所以只能让 App 自己念出来。
 *
 * <p>{@code RUN_SUBSCRIBE} 启动订阅队列：<b>真的订阅</b>，按队列顺序一章一章往下买，
 * 一个号买到代券不够下一章就换下一个号，不限章数（{@code BUY_UNTIL_BROKE} 是它的别名）。
 * 干跑功能已整套删除，所以没有「只走到按钮前停下」这种模式了。
 *
 * <p>{@code FORGET_OWNED} 删掉误回填的「已拥有」记录（见 {@link #forgetOwned}），真买记录不许删。
 *
 * <pre>
 * adb shell am broadcast -a com.example.blb.debug.LEDGER -p com.example.blb
 * adb shell am broadcast -a com.example.blb.debug.RUN_SUBSCRIBE -p com.example.blb
 * adb logcat -s BlbProbe:* BlbAuto:*
 * </pre>
 */
public class SubscribeProbeReceiver extends BroadcastReceiver {

    private static final String TAG = "BlbProbe";

    private static final String ACTION_LEDGER = "com.example.blb.debug.LEDGER";
    private static final String ACTION_RUN = "com.example.blb.debug.RUN_SUBSCRIBE";
    private static final String ACTION_SEED_NOVEL = "com.example.blb.debug.SEED_NOVEL";
    private static final String ACTION_RECORD = "com.example.blb.debug.RECORD_BOUGHT";
    private static final String ACTION_FORGET = "com.example.blb.debug.FORGET_OWNED";
    /** {@link #ACTION_RUN} 的别名：以前它俩一个干跑一个真买，现在只有真买这一种。 */
    private static final String ACTION_BUY_ALL = "com.example.blb.debug.BUY_UNTIL_BROKE";

    /** 书名种子文件；用 stdin 重定向写进来，中文不经过命令行。 */
    private static final String NOVEL_SEED = "novel_seed.json";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                if (ACTION_LEDGER.equals(action)) ledger(app);
                else if (ACTION_RUN.equals(action) || ACTION_BUY_ALL.equals(action)) run(app);
                else if (ACTION_SEED_NOVEL.equals(action)) seedNovel(app);
                else if (ACTION_RECORD.equals(action)) recordBought(app, intent);
                else if (ACTION_FORGET.equals(action)) forgetOwned(app, intent);
                else Log.w(TAG, "不认识的动作：" + action);
            } catch (Throwable t) {
                Log.e(TAG, "探针失败", t);
            } finally {
                pending.finish();
            }
        }, "blb-subscribe-probe").start();
    }

    /**
     * 登记「集中订阅目标」这本书。
     *
     * <p>为什么要有：这一步在界面上是「订阅页 → 选择目标小说 → 添加」三次点按，用户按不着；
     * 而书名是中文，{@code am broadcast -e} 传中文会被命令行编码坑掉，所以走种子文件。
     *
     * <p>它<b>只新建或更新，从不删除</b>：章节由 {@code CatalogScanner} 自己扫，账本一条都不动。
     *
     * <pre>
     * adb shell run-as com.example.blb sh -c 'cat &gt; files/novel_seed.json' &lt; novel.json
     * adb shell am broadcast -a com.example.blb.debug.SEED_NOVEL -n \
     *     com.example.blb/com.example.blb.debug.SubscribeProbeReceiver
     * </pre>
     * 种子格式：{@code {"title":"书名","author":"","sfNovelId":"","startChapterNo":1}}
     */
    private void seedNovel(Context app) throws Exception {
        File f = new File(app.getFilesDir(), NOVEL_SEED);
        if (!f.isFile()) {
            Log.w(TAG, "没有 files/" + NOVEL_SEED + "，什么都没做");
            return;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) sb.append(line);
        }
        JSONObject o = new JSONObject(sb.toString());
        String title = o.getString("title").trim();
        if (title.isEmpty()) {
            Log.w(TAG, "种子里的书名是空的，什么都没做");
            return;
        }

        SubscriptionDao subs = Db.get(app).subscriptionDao();
        Novel n = subs.novelByTitle(title);
        boolean isNew = n == null;
        if (isNew) {
            n = new Novel();
            n.title = title;
        }
        String author = o.optString("author", "").trim();
        if (!author.isEmpty()) n.author = author;
        String sfId = o.optString("sfNovelId", "").trim();
        if (!sfId.isEmpty()) n.sfNovelId = sfId;
        n.startChapterNo = Math.max(1, o.optInt("startChapterNo", n.startFrom()));
        n.isTarget = true;

        long id;
        if (isNew) {
            id = subs.insertNovel(n);
        } else {
            id = n.id;
            subs.updateNovel(n);
        }
        subs.setTargetNovel(id);   // 目标只许有一本，这个 DAO 方法会先把别的清掉
        Log.i(TAG, (isNew ? "新建" : "更新") + "目标小说《" + title + "》 id=" + id
                + " 从第" + n.startFrom() + "章起买"
                + " 书号=" + (n.sfNovelId == null || n.sfNovelId.isEmpty() ? "(空，只能靠搜书名)" : n.sfNovelId)
                + " 已登记 " + subs.loadChapters(id).size() + " 章（章节由目录扫描自己补）");
        if (!f.delete()) Log.w(TAG, "种子文件删不掉：files/" + NOVEL_SEED);
    }

    /** 只读地念一遍账本。 */
    private void ledger(Context app) {
        AppDatabase db = Db.get(app);
        SubscriptionDao subs = db.subscriptionDao();

        Log.i(TAG, "订阅：按队列顺序真买，一个号买到代券不够下一章就换号，不限章数"
                + "；每号每日代券上限=" + (Prefs.dailySpendCap(app) == 0
                ? "不限" : Prefs.dailySpendCap(app) + " 代券"));

        List<Account> accounts = db.accountDao().loadAll();
        Map<Long, String> names = new HashMap<>();
        Log.i(TAG, "账号 " + accounts.size() + " 个：");
        for (Account a : accounts) {
            names.put(a.id, a.displayName());
            // id 要打出来：RECORD_BOUGHT／FORGET_OWNED 只能按 id 指名账号（昵称是中文，走不了命令行）。
            Log.i(TAG, "  id=" + a.id + (a.enabled ? " [启用] " : " [停用] ") + a.displayName()
                    + " " + a.balanceText());
        }

        List<Novel> novels = subs.loadNovels();
        if (novels.isEmpty()) {
            Log.w(TAG, "一本小说都没登记 —— 自动订阅只认「集中订阅目标」，现在跑不了");
            return;
        }
        for (Novel n : novels) {
            List<Chapter> chapters = subs.loadChapters(n.id);
            Log.i(TAG, (n.isTarget ? "★目标 " : "  ") + "《" + n.title + "》"
                    + " 登记 " + chapters.size() + " 章"
                    + " 从第" + n.startFrom() + "章起买"
                    + " 书号=" + (n.sfNovelId == null ? "?" : n.sfNovelId));
            if (!n.isTarget) continue;
            Chapter next = subs.findNextUnownedChapterFrom(n.id, n.startFrom());
            Log.i(TAG, "    全局下一章 = "
                    + (next == null ? "没有了（登记的章都已经有号拥有）"
                    : "第" + next.chapterNo + "章「" + next.title + "」"));
            auditOwners(subs, n, names);
        }

        List<PurchaseRow> rows = subs.loadAllRows();
        Log.i(TAG, "账本共 " + rows.size() + " 条记录");
        for (PurchaseRow r : rows) {
            Log.i(TAG, "  " + r.source + " " + r.accountDisplayName()
                    + " 《" + r.novelTitle + "》第" + r.chapterNo + "章"
                    + " 花 " + r.costVouchers + " 代券 / " + r.costCoupons + " 火券");
        }
    }

    /**
     * 按「订阅清单」里看到的事实，把一条<b>已经真的买过</b>的记录补进账本。
     *
     * <p>为什么必须有这个入口：2026-08-24 那趟真买券确实扣了（五杯半雪碧 53→33 买到第48章、
     * 皓平 23→3 买到第49章），但当时的判据是「锁没了＝买到了」而锁买完不会没，于是判成失败、
     * 账本一条没记。账本没记的直接后果是 {@code findUnownedChaptersFrom} 还会把第48章
     * 当成待买章 —— 下一趟会有第二个号再买一遍同一章，而用户的硬约束是「每一章只能有一个
     * 账号订阅」。补录不是补数据洁癖，是防止重复花钱。
     *
     * <p>{@code CatalogSync} 下次扫目录时也会按「已下载」自动回填，但那条路只能记 0 花费
     * （界面上看不到当初付了多少）。这里能把真实的 20 代券记准，「今天花了多少券」才算得对。
     *
     * <p>护栏：这一章已经有<b>别的号</b>真买过就拒绝写 —— 那说明我对事实的理解有错，
     * 而账本是唯一能防止重复购买的东西，宁可不写。
     *
     * <pre>
     * adb shell am broadcast -a com.example.blb.debug.RECORD_BOUGHT -p com.example.blb \
     *     --ei acct 3 --ei no 48 --ei vouchers 20
     * </pre>
     */
    private void recordBought(Context app, Intent intent) {
        int acct = intent.getIntExtra("acct", -1);
        int no = intent.getIntExtra("no", -1);
        int vouchers = intent.getIntExtra("vouchers", -1);
        if (acct <= 0 || no <= 0 || vouchers < 0) {
            Log.w(TAG, "参数不全：--ei acct <账号id> --ei no <章号> --ei vouchers <实付代券>");
            return;
        }
        AppDatabase db = Db.get(app);
        SubscriptionDao subs = db.subscriptionDao();
        Account account = null;
        for (Account a : db.accountDao().loadAll()) {
            if (a.id == acct) account = a;
        }
        if (account == null) {
            Log.w(TAG, "没有 id=" + acct + " 这个账号（先跑 LEDGER 看 id），什么都没写");
            return;
        }
        Novel novel = subs.targetNovel();
        if (novel == null) {
            Log.w(TAG, "没有集中订阅目标小说，什么都没写");
            return;
        }
        Chapter chapter = subs.chapterByNo(novel.id, no);
        if (chapter == null) {
            Log.w(TAG, "《" + novel.title + "》账本里没有第" + no + "章（章节要先扫目录才有），什么都没写");
            return;
        }
        for (Purchase p : subs.loadPurchasesOfNovel(novel.id)) {
            if (p.chapterId != chapter.id || p.accountId == account.id) continue;
            Log.e(TAG, "拒绝写：第" + no + "章已经有别的号（id=" + p.accountId + "，" + p.source
                    + "）真买过 —— 一章只许一个号，先核对清楚");
            return;
        }
        subs.upsertPurchase(Purchase.of(account.id, chapter.id, 0, vouchers, Purchase.SRC_AUTO));
        Log.i(TAG, "已补录：" + account.displayName() + " 第" + chapter.chapterNo + "章「"
                + chapter.title + "」实付 " + vouchers + " 代券（source=AUTO）");
        Chapter next = subs.findNextUnownedChapterFrom(novel.id, novel.startFrom());
        Log.i(TAG, "补录之后的全局下一章 = "
                + (next == null ? "没有了" : "第" + next.chapterNo + "章「" + next.title + "」"));
    }

    /**
     * 「每一章只能有一个账号订阅」这条硬约束的自检 —— 只读，只往 logcat 打。
     *
     * <p>为什么要有：2026-08-25 用户对着「我的 → 代券 → 订阅清单」发现第49章其实只有皓平买过，
     * App 里却写着「已订阅：皓平、五杯半雪碧」。根因是当时按界面上的「已下载」回填归属，
     * 而「已下载」是<b>本机</b>的下载状态、8 个号共用（皓平买完下载到这台手机，换五杯半雪碧
     * 登录那一行照样写着「已下载」）。判据已经改成「只有免费章才回填」，但<b>已经写歪的记录
     * 不会自己消失</b>，所以要有这一条自检把它们找出来（清除用 {@link #forgetOwned}）。
     *
     * <p>免费章 8 个号各有一条 {@code OWNED} 是<b>正常</b>的：谁登录都看得到，不算重复订阅。
     * 只有「有人真花过券（{@code AUTO}／{@code MANUAL}）却还挂着别人」才是要报警的那种。
     */
    private void auditOwners(SubscriptionDao subs, Novel novel, Map<Long, String> names) {
        Map<Long, List<Purchase>> byChapter = new HashMap<>();
        for (Purchase p : subs.loadPurchasesOfNovel(novel.id)) {
            List<Purchase> list = byChapter.get(p.chapterId);
            if (list == null) {
                list = new ArrayList<>();
                byChapter.put(p.chapterId, list);
            }
            list.add(p);
        }
        int bad = 0;
        for (Chapter c : subs.loadChapters(novel.id)) {
            List<Purchase> owners = byChapter.get(c.id);
            if (owners == null || owners.size() < 2) continue;
            boolean paid = false;
            for (Purchase p : owners) {
                if (Purchase.SRC_AUTO.equals(p.source) || Purchase.SRC_MANUAL.equals(p.source)) {
                    paid = true;
                }
            }
            if (!paid) continue;   // 免费章：8 个号各一条 OWNED，合法
            bad++;
            StringBuilder sb = new StringBuilder();
            for (Purchase p : owners) {
                if (sb.length() > 0) sb.append("、");
                String who = names.get(p.accountId);
                sb.append(who == null ? "?" : who).append("(id=").append(p.accountId)
                        .append('/').append(p.source)
                        .append('/').append(p.costVouchers).append("代券)");
            }
            Log.e(TAG, "    ✗ 第" + c.chapterNo + "章「" + c.title + "」挂了 " + owners.size()
                    + " 个归属：" + sb + " —— 一章只许一个号。"
                    + "多出来的那条 OWNED 用 FORGET_OWNED 删掉（真买记录 AUTO 不许删）");
        }
        Log.i(TAG, bad == 0 ? "    自检通过：没有「一章挂多个归属」的章"
                : "    自检：有 " + bad + " 章归属重复，见上面的 ✗ 行");
    }

    /**
     * 删掉一条<b>误回填</b>的「已拥有」记录（只删 {@code source=OWNED}）。
     *
     * <p>为什么需要它：第49章那条五杯半雪碧的记录是按「已下载」推断出来的，而那个标记是本机
     * 状态、8 个号共用 —— 推断本身是错的。判据已经修好，但写歪的记录留着有两个后果：
     * 界面上一章挂两个号（违反用户的硬约束），而且这一章会被永久算成「有人有了」。
     *
     * <p><b>绝不删真买记录</b>（{@code AUTO}／{@code MANUAL}）：那些是花过券的事实，
     * 删掉就会有第二个号再买同一章。碰到非 OWNED 的记录一律拒绝并打日志。
     *
     * <pre>
     * adb shell am broadcast -a com.example.blb.debug.FORGET_OWNED -p com.example.blb \
     *     --ei acct 3 --ei no 49            # 只删这一章
     * adb shell am broadcast -a com.example.blb.debug.FORGET_OWNED -p com.example.blb \
     *     --ei acct 3 --ei no 48 --ei to 60 # 删一段（含两端）
     * </pre>
     */
    private void forgetOwned(Context app, Intent intent) {
        int acct = intent.getIntExtra("acct", -1);
        int no = intent.getIntExtra("no", -1);
        int to = Math.max(no, intent.getIntExtra("to", no));
        if (acct <= 0 || no <= 0) {
            Log.w(TAG, "参数不全：--ei acct <账号id> --ei no <章号> [--ei to <末章号>]");
            return;
        }
        SubscriptionDao subs = Db.get(app).subscriptionDao();
        Novel novel = subs.targetNovel();
        if (novel == null) {
            Log.w(TAG, "没有集中订阅目标小说，什么都没删");
            return;
        }
        List<Purchase> all = subs.loadPurchasesOfNovel(novel.id);
        int deleted = 0;
        int refused = 0;
        for (int n = no; n <= to; n++) {
            Chapter c = subs.chapterByNo(novel.id, n);
            if (c == null) continue;
            for (Purchase p : all) {
                if (p.chapterId != c.id || p.accountId != acct) continue;
                if (!Purchase.SRC_OWNED.equals(p.source)) {
                    Log.w(TAG, "拒绝删：第" + n + "章 id=" + acct + " 那条是 " + p.source
                            + "（花过 " + p.costVouchers + " 代券的事实不许删）");
                    refused++;
                    continue;
                }
                subs.deletePurchase(p);
                deleted++;
                Log.i(TAG, "已删：第" + n + "章「" + c.title + "」的 OWNED 记录（账号 id=" + acct + "）");
            }
        }
        Log.i(TAG, "FORGET_OWNED 完成：删了 " + deleted + " 条，拒绝 " + refused + " 条");
        Chapter next = subs.findNextUnownedChapterFrom(novel.id, novel.startFrom());
        Log.i(TAG, "现在的全局下一章 = "
                + (next == null ? "没有了" : "第" + next.chapterNo + "章「" + next.title + "」"));
    }

    /**
     * 启动订阅队列 —— 真的订阅。
     *
     * <p>没有干跑、没有额度、没有章数上限：按队列顺序（所有号合起来还没买过的最小章）
     * 一章一章往下买，一个号买到代券不够下一章就换下一个号，所有号都不够才收工。
     *
     * <p>护栏一条都没松：只花代券（实付里出现火券一律当买不起，绝不替他花火券）；
     * 买哪一章看账本，所以一章只会归一个号；点了确认而结果不明＝整趟收工，绝不换号接着点；
     * 买之前拿服务器端的「订阅清单 + 订阅明细」逐章对一次账，对不上就停。
     *
     * <pre>
     * adb shell am broadcast -a com.example.blb.debug.RUN_SUBSCRIBE -p com.example.blb
     * </pre>
     */
    private void run(Context app) {
        Log.w(TAG, "启动订阅队列（真买，不限章数）：按队列顺序，一个号买到代券不够下一章就换号，"
                + "所有号都买不动为止。只花代券；结果不明立刻整趟收工。"
                + "日志同时在 App 的签到页和 logcat 的 BlbAuto 里。");
        AutomationService.startSubscribe(app);
    }
}
