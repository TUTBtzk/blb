package com.example.blb.auto;

/**
 * 「选择章节」页上一行的三个标记，以及由它们得出的结论：这一行<b>要不要花券、能不能算进账本</b>。
 *
 * <p>为什么单独拎出来一个类：这几句结论有三个地方要用（{@link SubscribeTask} 判「不用买」、
 * 判「刚刚买成了」，{@link CatalogSync} 判「哪几章可以补进账本」），而 2026-08-24 那次真买
 * 正是因为三处各写一遍、并且都写错了 —— 券扣了 40 代券，账本一条都没记。
 *
 * <p><b>2026-08-24 21:21 真买 + 21:57 用户截图 + PROBE_CHAPTER 探针一起钉下来的事实</b>：
 * <ul>
 *   <li>{@code title_lock}（锁）买完<b>还在</b>：买到的行变成「打开的锁」（红边白底），
 *       没买的行是「锁上的锁」（红色实心）—— 两种锁在无障碍树里是同一个空文本节点，
 *       分不出来。所以「锁在不在」只能说明「这是付费章」，绝不能当「买没买」的判据。</li>
 *   <li>{@code title_check}＝「已下载」是<b>本机</b>的下载状态，<b>8 个号共用</b>：
 *       2026-08-25 用户核对订阅清单发现第49章只有皓平买过，账本里却多出一条五杯半雪碧 ——
 *       皓平买完下载到了这台手机，换五杯半雪碧登录之后那一行照样写着「已下载」，
 *       于是被回填成「五杯半雪碧也拥有」。所以它只能说明「这台手机上有这一章的文件」，
 *       <b>不能说明是当前这个号买的</b>（见 {@link #deviceHasIt}）。</li>
 *   <li>{@code item_cb}（右边那个圆圈）在＝这一行还能勾选、也就是当前这个号<b>能买它</b>。
 *       已下载的行右边写的是「已下载」三个字、没有圆圈 —— 那样的行点下去「已选」是 0 章，
 *       物理上买不了（不管是谁买的）。</li>
 * </ul>
 *
 * <p>所以「跨号也成立」的推断只剩一条：<b>没有锁＝免费章</b>，谁都看得到，可以放心记进账本
 * （用户要的是「8 个号拼出完整一本」，免费章不用花券）。「谁买过哪一章」只认真实购买记录
 * （{@code AUTO}／{@code MANUAL}），界面上读不出来。
 */
public final class ChapterRowState {

    /** 锁标记在不在。开锁和闭锁在树里一个样，所以它只说明「这是付费章」。 */
    public final boolean lock;
    /** 带「已下载」标记。 */
    public final boolean downloaded;
    /** 右边那个圆圈还在＝这一行还能勾选。 */
    public final boolean selectable;

    private ChapterRowState(boolean lock, boolean downloaded, boolean selectable) {
        this.lock = lock;
        this.downloaded = downloaded;
        this.selectable = selectable;
    }

    public static ChapterRowState of(boolean lock, boolean downloaded, boolean selectable) {
        return new ChapterRowState(lock, downloaded, selectable);
    }

    /** 把一行的三个标记读出来。查的是<b>行内</b>，不是整屏 —— 整屏上到处都有别的行的标记。 */
    public static ChapterRowState read(StepRunner r, NodeView row) {
        return of(r.findIn(row, Keys.CHAPTER_LOCKED) != null,
                r.findIn(row, Keys.CHAPTER_OWNED) != null,
                r.findIn(row, Keys.CHAPTER_SELECTABLE) != null);
    }

    /**
     * 免费章：没有锁。<b>唯一一条跨号也成立的推断</b> —— 谁登录都看得到，
     * 所以可以放心按当前账号补进账本（{@link com.example.blb.data.Purchase#SRC_OWNED}）。
     */
    public boolean free() {
        return !lock;
    }

    /** 付费章、而且这个号还能勾选它＝要花券买的就是这种行。 */
    public boolean buyable() {
        return lock && selectable;
    }

    /**
     * 付费章、而且这台手机上已经下载过了 —— <b>但买家是谁不知道</b>。
     *
     * <p>「已下载」是本机状态、8 个号共用（见类注释里 2026-08-25 那条）。这种行：
     * <ul>
     *   <li>绝不能回填成「当前这个号拥有」—— 那就是第49章多出一个订阅者的原因；</li>
     *   <li>也买不了：右边没有勾选圈，点下去「已选」是 0 章。</li>
     * </ul>
     * 所以只能跳过并如实报告，让账本由真实购买记录（或订阅清单核对）来决定归谁。
     */
    public boolean deviceHasIt() {
        return lock && downloaded;
    }

    /** 写进日志的一句话，出问题时能直接看出是哪个标记读错了。 */
    public String describe() {
        return (downloaded ? "有「已下载」（本机状态，8 个号共用）" : "没有「已下载」")
                + (lock ? "、有锁标记（开锁闭锁在树里一个样）" : "、没有锁标记（免费章）")
                + (selectable ? "、还有勾选圈" : "、没有勾选圈");
    }
}
