package com.example.blb.auto;

import com.example.blb.util.Texts;

import java.util.List;

/**
 * 从一棵节点树上把火券／代券读出来。
 *
 * <p>单独拆出来是为了能在普通 JVM 单元测试里，用真机 dump 出来的 bounds 造树验证 ——
 * 这里的规则完全是靠位置猜出来的，最容易在菠萝包改版时悄悄失效。
 *
 * <p>三种页面（可靠性从高到低，见 selectors.json 的 {@code _note_coupons}）：
 * <ol>
 *   <li>批量购买页 {@code tvAccount}：「账户余额：0火券/0代券」，两种券在同一段文字里；</li>
 *   <li>我的钱包页：{@code tv_friemoney}（火券）和 {@code tv_voucher}（代券）各有 id；</li>
 *   <li>「我的」页那一行：数字和标签<b>都没有 id</b>，只能靠「数字画在标签正上方、同一列」
 *       配对，见 {@link NodeMatcher#countAbove}。</li>
 * </ol>
 */
public final class BalanceReader {

    /** 按 key 取候选条件。真机上是 {@code SelectorSet::get}，测试里直接喂解析好的 Map。 */
    public interface Selectors {
        List<Selector> get(String key);
    }

    private BalanceReader() {
    }

    /** 读不到的那一项是 -1（「不知道」），调用方据此决定要不要覆盖库里已有的值。 */
    public static Texts.Balance read(NodeView root, Selectors selectors) {
        if (root == null || selectors == null) return Texts.balance(-1, -1);
        Texts.Balance combined = Texts.parseBalance(textOf(root, selectors, Keys.COUPONS_VALUE));
        int fire = combined.fire;
        int voucher = combined.voucher;
        if (voucher < 0) {
            voucher = Texts.parseWholeCount(textOf(root, selectors, Keys.VOUCHERS_VALUE));
        }
        if (fire < 0) fire = countAbove(root, selectors, Keys.BALANCE_FIRE_LABEL);
        if (voucher < 0) voucher = countAbove(root, selectors, Keys.BALANCE_VOUCHER_LABEL);
        return Texts.balance(fire, voucher);
    }

    private static String textOf(NodeView root, Selectors selectors, String key) {
        NodeView node = find(root, selectors, key);
        return node == null ? null : node.text();
    }

    private static int countAbove(NodeView root, Selectors selectors, String key) {
        NodeView label = find(root, selectors, key);
        return label == null ? -1 : NodeMatcher.countAbove(root, label);
    }

    private static NodeView find(NodeView root, Selectors selectors, String key) {
        List<Selector> candidates = selectors.get(key);
        if (candidates == null || candidates.isEmpty()) return null;
        NodeMatcher.Hit hit = NodeMatcher.find(root, candidates);
        return hit == null ? null : hit.node;
    }
}
