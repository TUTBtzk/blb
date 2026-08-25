package com.example.blb.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.blb.auto.AccountSwitcher;
import com.example.blb.auto.BlbAccessibilityService;
import com.example.blb.auto.Keys;
import com.example.blb.auto.NodeView;
import com.example.blb.auto.SelectorSet;
import com.example.blb.auto.StepRunner;
import com.example.blb.auto.TreeDump;
import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.List;

/**
 * 「我的 → 代券 → 订阅清单」这三屏的现场取样器（<b>只存在于 debug 构建</b>）。
 *
 * <p>为什么需要它：现在「这一章归谁」只有一个来源 —— 我们自己写进 Room 的购买记录。
 * 2026-08-25 第49章被误挂成「皓平、五杯半雪碧」两个号那件事说明，账本一旦写歪，
 * App 自己是看不出来的（发现它的是用户，靠的正是菠萝包自己的「订阅清单」）。那份清单是
 * 服务器端的事实，是唯一独立于我们推断的第二来源 —— 有了它才谈得上「对账」，
 * 也才敢放开 {@code BUY_UNTIL_BROKE} 一路买下去。
 *
 * <p>而这三屏的节点结构现在<b>完全未知</b>，且这三步在界面上就是三次点按 —— 用户手指动不了，
 * 「请你点一下代券」不是兜底方案。所以让 App 自己走过去，把每一屏念进 logcat。
 *
 * <p>它<b>一个字都不写</b>：不碰 Room、不花券；默认连号都不切（切号＝退登重登，最招验证码），
 * 就用现在登着的那个号。
 *
 * <pre>
 * adb shell am broadcast -a com.example.blb.debug.PROBE_VOUCHER_LEDGER -p com.example.blb
 * adb shell am broadcast -a com.example.blb.debug.PROBE_VOUCHER_LEDGER -p com.example.blb \
 *     --ei acct 2 --ei pages 12      # acct＝启用账号里的第几个（1 起，0＝不切号）
 * adb logcat -d -s BlbProbe:* BlbTree:* BlbAuto:*
 * </pre>
 *
 * <p><b>PROBE_SUBSCRIBED_DETAIL</b>：同一条路，但在目标那本书的行上点<b>行右上角那个「&gt;」箭头</b>
 * 而不是「查看目录」——「查看目录」进去是整本书的目录列表（不是买过哪几章），用户 2026-08-25
 * 指出真正的明细入口是那个箭头。这一支先把整行的子树念出来（认出箭头到底是哪个节点），
 * 再点进去逐屏 dump。
 *
 * <pre>
 * adb shell am broadcast -a com.example.blb.debug.PROBE_SUBSCRIBED_DETAIL -p com.example.blb
 * </pre>
 */
public class VoucherLedgerProbeReceiver extends BroadcastReceiver {

    private static final String TAG = "BlbProbe";
    private static final String TREE = "BlbTree";
    private static final long NAV_TIMEOUT = 12_000L;

    /** 点行右上角那个「&gt;」箭头，而不是「查看目录」。 */
    private static final String ACTION_DETAIL = "com.example.blb.debug.PROBE_SUBSCRIBED_DETAIL";

    /** 代券页上那个入口的名字，按用户截图里的字样；认不出就把整屏念出来让人看。 */
    private static final String LIST_ENTRY = "订阅清单";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        int acct = intent == null ? 0 : intent.getIntExtra("acct", 0);
        int pages = intent == null ? 8 : intent.getIntExtra("pages", 8);
        boolean arrow = intent != null && ACTION_DETAIL.equals(intent.getAction());
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                probe(app, acct, Math.max(1, Math.min(30, pages)), arrow);
            } catch (Throwable t) {
                Log.e(TAG, "订阅清单探针失败", t);
            } finally {
                pending.finish();
            }
        }, "blb-voucher-probe").start();
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
                Log.w(TAG, "需要人工处理，探针直接结束：" + reason);
                return StepRunner.Decision.ABORT;
            }
        };
    }

    // ===== 主流程 =====（下面按屏分段，每一段都先 dump 再决定下一步点哪儿）

    private void probe(Context app, int acct, int pages, boolean arrow) throws Exception {
        SelectorSet selectors = SelectorSet.load(app);
        Log.i(TAG, "选择器来自 " + selectors.source() + "；要走的是「我的 → 代券 → " + LIST_ENTRY + "」"
                + (arrow ? "，然后点目标那本书行右上角的「>」箭头" : ""));
        StepRunner r = new StepRunner(app, selectors, host());

        if (acct > 0) {
            AccountDao dao = Db.get(app).accountDao();
            List<Account> accounts = dao.loadEnabled();
            if (accounts.isEmpty()) {
                Log.w(TAG, "没有启用的账号");
                return;
            }
            Account a = accounts.get(Math.max(1, Math.min(accounts.size(), acct)) - 1);
            Log.w(TAG, "要切到「" + a.displayName() + "」—— 切号是退登重登，最招验证码。"
                    + "只看清单结构的话用 acct 0（默认）就够了");
            AccountSwitcher.ensureLoggedIn(r, a, dao, accounts.size() == 1);
        } else {
            r.launchTarget(NAV_TIMEOUT);
        }

        r.ensureHome(4);
        r.click(Keys.MINE_TAB, NAV_TIMEOUT);
        String who = r.readText(Keys.NICKNAME, 6_000);
        Log.i(TAG, "现在登着的是「" + (Texts.isBlank(who) ? "读不到昵称" : who.trim())
                + "」，「我的」页读到的余额 " + r.readBalance(2_500).describe());
        stage("1 我的页", r);

        // ---- 第一步：点「代券」。这一行的数字和标签都没有 id（见 selectors.json 的
        // _note_coupons），所以只能按标签文本定位，再往上找最近的可点祖先。
        NodeView voucher = voucherEntry(root());
        if (voucher == null) {
            Log.w(TAG, "「我的」页上找不到「代券」那一格 —— 看上面那棵树，"
                    + "标签可能改了字样（比如「代金券」）");
            return;
        }
        NodeView press = clickableSelfOrAncestor(voucher);
        Log.i(TAG, "「代券」标签：" + describe(voucher));
        Log.i(TAG, "实际要点的（自己或最近的可点祖先）：" + describe(press));
        String before = signature(root());
        r.clickNode("我的页的「代券」", press);
        r.sleepHuman();
        r.waitMillis(2500);
        if (signature(root()).equals(before)) {
            Log.w(TAG, "点了「代券」但这一屏没变 —— 那一格大概不可点，"
                    + "要在树里找真正的入口（也许是标签正上方那个数字，或整张卡）");
        }
        stage("2 代券页", r);
        probeList(r, pages);
        probeTargetBook(app, r, arrow);
    }

    /**
     * 第三步：清单是<b>按书聚合</b>的（2026-08-25 实测），所以还要点进目标那本书才有逐章明细 ——
     * 而「一章只许一个号」这条硬约束是逐章的。
     *
     * <p>点哪儿有两条路：<b>「查看目录」</b>进去是整本书的目录列表（不是买过哪几章，2026-08-25
     * 实测），<b>行右上角那个「&gt;」箭头</b>是用户指出的真正明细入口 —— {@code arrow} 就是选它。
     */
    private void probeTargetBook(Context app, StepRunner r, boolean arrow)
            throws StepRunner.StepFailure {
        Novel novel = Db.get(app).subscriptionDao().targetNovel();
        if (novel == null || Texts.isBlank(novel.title)) {
            Log.w(TAG, "台账里没有「集中订阅目标」，不知道该点进哪本书");
            return;
        }
        String title = novel.title.trim();
        r.scrollToTop(8);
        r.waitMillis(800);
        NodeView row = null;
        for (int i = 0; i <= 8 && row == null; i++) {
            NodeView hit = firstVisibleExact(root(), title);
            if (hit != null) row = bookRow(hit);
            if (row == null && !r.scrollForward()) break;
            if (row == null) r.sleepHuman();
        }
        if (row == null) {
            Log.w(TAG, "订阅清单里翻遍了都没有《" + title + "》这一行 —— "
                    + "要么这个号一章都没订过，要么书名和台账里记的不一样");
            return;
        }
        Log.i(TAG, "《" + title + "》这一行：章节与花费=" + text(inRow(row, "tvbBookAutor"))
                + " 日期=" + text(inRow(row, "tvbBookDesc")));
        // 先把整行的子树念出来 —— 那个「>」箭头没有文字，只能靠这份子树认出它是哪个节点。
        Log.i(TREE, "----- 《" + title + "》整行的子树开始 -----");
        for (String line : TreeDump.dump(row).split("\n")) {
            if (!line.trim().isEmpty()) Log.i(TREE, line);
        }
        Log.i(TREE, "----- 《" + title + "》整行的子树结束 -----");
        Log.i(TAG, "这一行里能点的：");
        for (NodeView n : clickables(row)) Log.i(TAG, "  · " + describe(n));

        NodeView press;
        String what;
        if (arrow) {
            press = arrowTarget(row);
            what = "《" + title + "》行右上角的「>」";
            if (press == null) {
                Log.w(TAG, "这一行上找不到「查看目录」以外的可点节点 —— 那个箭头大概只是装饰，"
                        + "整行才是入口。看上面那份子树");
                return;
            }
        } else {
            NodeView mulu = inRowNode(row, "tvToMuLu");
            if (mulu == null) {
                Log.w(TAG, "这一行上没有「查看目录」，只有聚合数字能用");
                return;
            }
            press = clickableSelfOrAncestor(mulu);
            what = "《" + title + "》的「查看目录」";
        }
        Log.i(TAG, "要点的是：" + describe(press));
        r.clickNode(what, press);
        r.sleepHuman();
        r.waitMillis(2500);
        stage("4 " + what + " 点进去之后", r);
        String last = null;
        for (int page = 1; page <= 12; page++) {
            List<String> lines = textLines(root());
            Log.i(TAG, "----- 明细 第 " + page + " 屏，" + lines.size() + " 行 -----");
            for (String line : lines) Log.i(TAG, "  " + line);
            String sig = signature(root());
            if (sig.equals(last)) break;
            last = sig;
            if (!r.scrollForward()) break;
            r.sleepHuman();
        }
        Log.i(TAG, "探针结束 —— 一个字都没写、一分券都没花。"
                + "上面这份明细是服务器端的事实，接下来要用它跟 Room 里的账本逐章对账");
    }

    /** 第二步：在代券页里找「订阅清单」进去，然后一屏一屏把行念出来。 */
    private void probeList(StepRunner r, int pages) throws StepRunner.StepFailure {
        NodeView entry = null;
        for (int i = 0; i <= 4 && entry == null; i++) {
            entry = firstVisibleExact(root(), LIST_ENTRY);
            if (entry == null && !r.scrollForward()) break;
            if (entry == null) r.sleepHuman();
        }
        if (entry == null) {
            Log.w(TAG, "代券页上翻了几屏都没有「" + LIST_ENTRY + "」这四个字。"
                    + "下面把这一屏所有能点的东西列出来，真入口应该在里面：");
            for (NodeView n : clickables(root())) Log.i(TAG, "  · " + describe(n));
            return;
        }
        NodeView press = clickableSelfOrAncestor(entry);
        Log.i(TAG, "「" + LIST_ENTRY + "」入口：" + describe(entry) + " → 实际点 " + describe(press));
        r.clickNode(LIST_ENTRY, press);
        r.sleepHuman();
        r.waitMillis(2500);
        stage("3 " + LIST_ENTRY + "页", r);

        // 清单可能很长（一个号买过几十章）。第一屏已经整树 dump 过了，后面只念行文本 ——
        // 整树刷 logcat 会把前面的屏冲掉，反而看不清。
        String last = null;
        for (int page = 1; page <= pages; page++) {
            List<String> lines = textLines(root());
            Log.i(TAG, "----- " + LIST_ENTRY + " 第 " + page + " 屏，" + lines.size() + " 行 -----");
            for (String line : lines) Log.i(TAG, "  " + line);
            String sig = signature(root());
            if (sig.equals(last)) {
                Log.i(TAG, "这一屏和上一屏一样 —— 到底了，停在第 " + page + " 屏");
                break;
            }
            last = sig;
            if (!r.scrollForward()) {
                Log.i(TAG, "滚不动了 —— 清单只有这 " + page + " 屏");
                break;
            }
            r.sleepHuman();
        }
        Log.i(TAG, "探针结束 —— 一个字都没写、一分券都没花，屏幕停在" + LIST_ENTRY + "页。"
                + "接下来要从上面的行文本里认出「书名／章号／花了多少代券／什么时候买的」这四样，"
                + "才能拿它跟 Room 里的账本对账");
    }

    // ===== 清单里的一行 =====

    /** 从书名节点往上找到「整行」：判据是这一行里有「查看目录」。 */
    private static NodeView bookRow(NodeView titleNode) {
        NodeView n = titleNode;
        for (int depth = 0; n != null && depth < 6; depth++) {
            if (inRowNode(n, "tvToMuLu") != null) return n;
            n = n.parent();
        }
        return null;
    }

    /**
     * 行右上角那个「&gt;」箭头 —— 用户 2026-08-25 指出，明细入口是它，不是「查看目录」。
     *
     * <p>它没有文字、大概也没有 id，所以按<b>位置</b>认：在这一行的右半边、上半截，
     * 而且不是「查看目录」那颗（那一颗在右下）。行内找不到单独可点的节点时，
     * 退回点整行 —— 箭头很可能只是装饰，真正可点的是整行那个容器。
     */
    private static NodeView arrowTarget(NodeView row) {
        int[] rb = row.boundsInScreen();
        int midX = (rb[0] + rb[2]) / 2;
        int midY = (rb[1] + rb[3]) / 2;
        NodeView fallbackInside = null;
        for (NodeView n : clickables(row)) {
            if (inRowNode(n, "tvToMuLu") != null) continue;   // 「查看目录」自己或含着它的容器
            int[] b = n.boundsInScreen();
            if (b[0] >= midX && b[3] <= midY + 40) return n;  // 右半边、上半截＝那个箭头
            if (fallbackInside == null) fallbackInside = n;
        }
        if (fallbackInside != null) return fallbackInside;
        NodeView self = clickableSelfOrAncestor(row);
        return self != null && self.clickable() ? self : null;
    }

    /** 行内按 id 后缀找节点 —— 整屏上到处都是别的行的同名 id，只能在行内查。 */    private static NodeView inRowNode(NodeView row, String idSuffix) {
        if (row == null) return null;
        String id = row.viewId();
        if (id != null && id.endsWith(idSuffix)) return row;
        for (int i = 0; i < row.childCount(); i++) {
            NodeView hit = inRowNode(row.child(i), idSuffix);
            if (hit != null) return hit;
        }
        return null;
    }

    private static String inRow(NodeView row, String idSuffix) {
        NodeView n = inRowNode(row, idSuffix);
        return n == null ? null : n.text();
    }

    private static String text(String s) {
        return Texts.isBlank(s) ? "读不到" : s.trim();
    }

    // ===== 取样与描述 =====

    /** 把一屏完整地念出来：前台包名、窗口数、整棵节点树、以及能点的东西有哪些。 */
    private void stage(String label, StepRunner r) throws StepRunner.StepFailure {
        Log.i(TAG, "===== 第 " + label + " =====");
        Log.i(TAG, "前台包名=" + r.activePackage() + " 窗口数=" + r.windowCount());
        NodeView root = root();
        if (root == null) {
            Log.w(TAG, "取不到根节点（菠萝包不在前台？）");
            return;
        }
        Log.i(TREE, "----- 第 " + label + "，节点树开始 -----");
        for (String line : TreeDump.dump(root).split("\n")) {
            if (!line.trim().isEmpty()) Log.i(TREE, line);
        }
        Log.i(TREE, "----- 第 " + label + "，节点树结束 -----");
        List<NodeView> hits = clickables(root);
        Log.i(TAG, "这一屏能点的 " + hits.size() + " 个：");
        for (NodeView n : hits) Log.i(TAG, "  · " + describe(n));
    }

    /**
     * 「我的」页上「代券」那一格。
     *
     * <p>2026-08-23 实测：这一行的数字和标签都<b>没有 resource-id</b>，只能靠文本认标签
     * （数字画在标签正上方、同一列）。所以这里先收所有文本正好是「代券」的可见节点，
     * 排掉明显不是那一格的（比如清单行里的「代券」字样出现在长文本里，那种不会精确相等）。
     */
    private static NodeView voucherEntry(NodeView root) {
        List<NodeView> all = new ArrayList<>();
        collectExact(root, "代券", all);
        for (NodeView n : all) {
            if (n.visible()) return n;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    private static NodeView firstVisibleExact(NodeView root, String text) {
        List<NodeView> all = new ArrayList<>();
        collectExact(root, text, all);
        for (NodeView n : all) {
            if (n.visible()) return n;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    private static void collectExact(NodeView node, String text, List<NodeView> out) {
        if (node == null || out.size() >= 40) return;
        if (text.equals(node.text() == null ? null : node.text().trim())) out.add(node);
        for (int i = 0; i < node.childCount(); i++) collectExact(node.child(i), text, out);
    }

    /** 这一屏所有可点、可见的节点 —— 认不出入口时，真入口一定在这张表里。 */
    private static List<NodeView> clickables(NodeView root) {
        List<NodeView> out = new ArrayList<>();
        collectClickable(root, out);
        return out;
    }

    private static void collectClickable(NodeView node, List<NodeView> out) {
        if (node == null || out.size() >= 60) return;
        if (node.clickable() && node.visible()) out.add(node);
        for (int i = 0; i < node.childCount(); i++) collectClickable(node.child(i), out);
    }

    /** 一屏里所有带文本的节点，按「文本 + 位置」念出来 —— 清单页靠位置才能把一行拼起来。 */
    private static List<String> textLines(NodeView root) {
        List<String> out = new ArrayList<>();
        collectText(root, out);
        return out;
    }

    private static void collectText(NodeView node, List<String> out) {
        if (node == null || out.size() >= 120) return;
        String text = node.text() == null ? "" : node.text().trim();
        if (!text.isEmpty()) {
            int[] b = node.boundsInScreen();
            out.add("\"" + text + "\""
                    + (node.viewId() == null ? "" : " id=" + node.viewId())
                    + " [" + b[0] + "," + b[1] + "-" + b[2] + "," + b[3] + "]"
                    + (node.clickable() ? " CLICKABLE" : ""));
        }
        for (int i = 0; i < node.childCount(); i++) collectText(node.child(i), out);
    }

    /** 判断「点下去屏幕到底变了没有」用的指纹：把可见文本按顺序接起来。 */
    private static String signature(NodeView root) {
        StringBuilder sb = new StringBuilder();
        for (String line : textLines(root)) sb.append(line).append('|');
        return sb.toString();
    }

    private static NodeView clickableSelfOrAncestor(NodeView node) {
        NodeView n = node;
        for (int depth = 0; n != null && depth < 6; depth++) {
            if (n.clickable() && n.enabled()) return n;
            n = n.parent();
        }
        return node;
    }

    private static NodeView root() {
        BlbAccessibilityService svc = BlbAccessibilityService.peek();
        return svc == null ? null : svc.root();
    }

    private static String describe(NodeView node) {
        if (node == null) return "(空节点)";
        StringBuilder sb = new StringBuilder();
        String cn = node.className();
        sb.append(cn == null ? "?" : cn.substring(cn.lastIndexOf('.') + 1));
        if (node.text() != null && !node.text().trim().isEmpty()) {
            sb.append(" text=\"").append(node.text().trim()).append('"');
        }
        if (node.desc() != null && !node.desc().trim().isEmpty()) {
            sb.append(" desc=\"").append(node.desc().trim()).append('"');
        }
        if (node.viewId() != null) sb.append(" id=").append(node.viewId());
        if (node.clickable()) sb.append(" CLICKABLE");
        if (!node.enabled()) sb.append(" disabled");
        if (!node.visible()) sb.append(" 不可见");
        int[] b = node.boundsInScreen();
        sb.append(" [").append(b[0]).append(',').append(b[1])
                .append('-').append(b[2]).append(',').append(b[3]).append(']');
        return sb.toString();
    }
}
