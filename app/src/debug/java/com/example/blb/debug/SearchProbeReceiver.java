package com.example.blb.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.blb.auto.BlbAccessibilityService;
import com.example.blb.auto.Keys;
import com.example.blb.auto.NodeView;
import com.example.blb.auto.SelectorSet;
import com.example.blb.auto.StepRunner;
import com.example.blb.auto.TreeDump;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;

import java.util.ArrayList;
import java.util.List;

/**
 * 「搜书 → 详情页 → 目录」这三步的现场取样器（<b>只存在于 debug 构建</b>）。
 *
 * <p>为什么需要它：干跑卡在「点了书名但没进详情页（等『目录』超时）」，而这一步只有真机能看。
 * 用 {@code uiautomator dump} 抓不到：搜索要输入中文书名，{@code adb shell input text} 送不进
 * 中文；而书名一旦要从命令行传，密码级别的引号／编码坑又会回来。所以让 App 自己走这三步，
 * 书名从台账里的「集中订阅目标」拿，一个字都不用从命令行传。
 *
 * <p>它<b>不写任何数据</b>：不碰 Room、不切号、不买章，只把每一屏的节点树打进 logcat。
 * 跑完停在最后到达的那一屏，方便再用 {@code dumpsys activity top} 核对 Activity 名。
 *
 * <pre>
 * adb shell am broadcast -a com.example.blb.debug.PROBE_SEARCH -n \
 *     com.example.blb/com.example.blb.debug.SearchProbeReceiver
 * adb logcat -d -s BlbProbe:* BlbTree:*
 * </pre>
 */
public class SearchProbeReceiver extends BroadcastReceiver {

    private static final String TAG = "BlbProbe";
    private static final String TREE = "BlbTree";

    private static final long NAV_TIMEOUT = 12_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                probe(app);
            } catch (Throwable t) {
                Log.e(TAG, "搜书探针失败", t);
            } finally {
                pending.finish();
            }
        }, "blb-search-probe").start();
    }

    private void probe(Context app) throws Exception {
        Novel target = Db.get(app).subscriptionDao().targetNovel();
        if (target == null || target.title == null || target.title.trim().isEmpty()) {
            Log.w(TAG, "台账里没有「集中订阅目标」，探针不知道该搜哪本书 —— 先在订阅页登记一本");
            return;
        }
        String title = target.title.trim();

        SelectorSet selectors = SelectorSet.load(app);
        Log.i(TAG, "选择器来自 " + selectors.source() + "，要搜的书是《" + title + "》");
        StepRunner r = new StepRunner(app, selectors, new StepRunner.Host() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void log(String message) {
                Log.i(TAG, message);
            }

            @Override
            public StepRunner.Decision awaitUser(String reason) {
                Log.w(TAG, "需要人工处理，探针直接结束：" + reason);
                return StepRunner.Decision.ABORT;
            }
        });

        r.launchTarget(NAV_TIMEOUT);
        r.ensureHome(4);
        r.click(Keys.LIBRARY_TAB, NAV_TIMEOUT);
        r.click(Keys.SEARCH_ENTRY, NAV_TIMEOUT);
        r.setText(Keys.SEARCH_FIELD, title, NAV_TIMEOUT);
        r.sleepHuman();
        r.waitMillis(1500);

        stage("1 输入书名后（搜索页 + 建议下拉）", r, title);

        // 上一版就是死在这儿：findExactText 第一个命中的是<b>搜索框自己</b>（文本正好是刚
        // 输进去的书名），点它当然什么都不会发生。所以候选必须先把输入框排掉。
        NodeView suggestion = pickTitleRow(root(), title);
        if (suggestion == null) {
            Log.w(TAG, "建议下拉里没有「文本正好等于书名」的行 —— 到此为止，看上面那棵树");
            return;
        }
        NodeView press = clickableSelfOrAncestor(suggestion);
        Log.i(TAG, "选中的书名行：" + describe(suggestion));
        Log.i(TAG, "实际要点的（自己或最近的可点祖先）：" + describe(press));
        r.clickNode("书名行 " + title, press);
        r.sleepHuman();
        r.waitMillis(2500);
        stage("2 点了建议里的书名行之后", r, title);

        // 这一屏可能已经是详情页，也可能只是搜索结果页 —— 两种都往下走一步看看。
        if (r.findAny(Keys.CATALOG_ENTRY) == null) {
            NodeView row = pickTitleRow(root(), title);
            if (row == null) {
                Log.w(TAG, "这一屏既没有「目录」，也没有能点的书名行 —— 到此为止");
                return;
            }
            NodeView press2 = clickableSelfOrAncestor(row);
            Log.i(TAG, "再点一次书名行：" + describe(row) + " → 实际点 " + describe(press2));
            r.clickNode("书名行 " + title, press2);
            r.sleepHuman();
            r.waitMillis(2500);
            stage("3 再点一次之后（应该是书籍详情页）", r, title);
        }

        StepRunner.Outcome catalog = r.findAny(Keys.CATALOG_ENTRY);
        if (catalog == null) {
            Log.w(TAG, "详情页上 catalog_entry 一条候选都不命中 —— 在上面那棵树里找「目录」的真实 id");
            NodeView mulu = findByExactText(root(), "目录");
            if (mulu != null) {
                Log.i(TAG, "但树里有文本正好是「目录」的节点：" + describe(mulu));
                Log.i(TAG, "  祖先链：" + ancestors(mulu));
            }
            return;
        }
        Log.i(TAG, "catalog_entry 命中：" + describe(catalog.node) + "，点进目录");
        r.clickNode(Keys.CATALOG_ENTRY, catalog.node);
        r.sleepHuman();
        r.waitMillis(2500);
        stage("4 点了「目录」之后（目录页）", r, title);

        Log.i(TAG, "探针结束 —— 屏幕停在最后到达的那一屏");
    }

    /**
     * 挑「书名那一行」：文本正好等于书名，<b>且不是搜索输入框</b>，且用户真的看得见。
     *
     * <p>输入框那一票是这次真机失败的全部原因 —— 它的文本就是刚输进去的书名。
     * 历史搜索那颗同名标签压在建议下拉底下（下拉的行盖住了它），所以也要求可见。
     */
    private static NodeView pickTitleRow(NodeView root, String title) {
        List<NodeView> all = new ArrayList<>();
        collectExact(root, title, all);
        NodeView best = null;
        for (NodeView n : all) {
            if (isSearchField(n)) continue;
            if (!n.visible()) continue;
            String id = n.viewId() == null ? "" : n.viewId();
            // 建议下拉的行（tv_think_text）优先：它是「输完就出来」的那一屏上唯一的书名行。
            if (id.endsWith("tv_think_text")) return n;
            if (best == null) best = n;
        }
        return best;
    }

    private static boolean isSearchField(NodeView n) {
        String cn = n.className() == null ? "" : n.className();
        String id = n.viewId() == null ? "" : n.viewId();
        return cn.endsWith("EditText") || id.endsWith("inputSearch");
    }

    private static void collectExact(NodeView node, String title, List<NodeView> out) {
        if (node == null || out.size() >= 40) return;
        if (title.equals(node.text() == null ? null : node.text().trim())) out.add(node);
        for (int i = 0; i < node.childCount(); i++) collectExact(node.child(i), title, out);
    }

    private static NodeView findByExactText(NodeView node, String text) {
        if (node == null) return null;
        if (text.equals(node.text() == null ? null : node.text().trim())) return node;
        for (int i = 0; i < node.childCount(); i++) {
            NodeView hit = findByExactText(node.child(i), text);
            if (hit != null) return hit;
        }
        return null;
    }

    private static NodeView clickableSelfOrAncestor(NodeView node) {
        NodeView n = node;
        for (int depth = 0; n != null && depth < 6; depth++) {
            if (n.clickable() && n.enabled()) return n;
            n = n.parent();
        }
        return node;
    }

    /** 把一屏完整地念出来：节点树 + 「书名出现在哪些节点上」+ 关键 key 命中情况。 */
    private void stage(String label, StepRunner r, String title) throws StepRunner.StepFailure {
        Log.i(TAG, "===== 第 " + label + " 屏 =====");
        Log.i(TAG, "前台包名=" + r.activePackage() + " 窗口数=" + r.windowCount());

        NodeView root = root();
        if (root == null) {
            Log.w(TAG, "取不到根节点（菠萝包不在前台？）");
            return;
        }

        Log.i(TREE, "----- 第 " + label + " 屏，节点树开始 -----");
        for (String line : TreeDump.dump(root).split("\n")) {
            if (!line.trim().isEmpty()) Log.i(TREE, line);
        }
        Log.i(TREE, "----- 第 " + label + " 屏，节点树结束 -----");

        List<NodeView> carriers = new ArrayList<>();
        collectCarriers(root, title, carriers);
        Log.i(TAG, "带书名的节点 " + carriers.size() + " 个：");
        for (NodeView n : carriers) {
            Log.i(TAG, "  · " + describe(n));
            Log.i(TAG, "    祖先链：" + ancestors(n));
        }

        for (String key : new String[]{Keys.SEARCH_FIELD, Keys.SEARCH_SUBMIT,
                Keys.CATALOG_ENTRY, Keys.DOWNLOAD_ENTRY}) {
            StepRunner.Outcome o = r.findAny(key);
            Log.i(TAG, "  key " + key + (o == null ? " ✘ 未命中" : " ✔ " + describe(o.node)));
        }
    }

    private static NodeView root() {
        BlbAccessibilityService svc = BlbAccessibilityService.peek();
        return svc == null ? null : svc.root();
    }

    /** 文本或描述里带书名的节点全收，不只收「完全等于」的 —— 差别正是这次要看的东西。 */
    private static void collectCarriers(NodeView node, String title, List<NodeView> out) {
        if (node == null || out.size() >= 40) return;
        String text = node.text() == null ? "" : node.text();
        String desc = node.desc() == null ? "" : node.desc();
        if (text.contains(title) || desc.contains(title)) out.add(node);
        for (int i = 0; i < node.childCount(); i++) collectCarriers(node.child(i), title, out);
    }

    private static String ancestors(NodeView node) {
        StringBuilder sb = new StringBuilder();
        NodeView p = node.parent();
        for (int depth = 0; p != null && depth < 5; depth++) {
            if (depth > 0) sb.append(" ← ");
            sb.append(describe(p));
            p = p.parent();
        }
        return sb.length() == 0 ? "(没有父节点)" : sb.toString();
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
