package com.example.blb.auto;

/**
 * 无障碍节点的最小抽象。目的是让匹配逻辑能在普通 JVM 单元测试里跑，
 * 不必依赖 AccessibilityNodeInfo。真机上由 {@link AccessibilityNodeView} 适配。
 */
public interface NodeView {

    String text();

    String desc();

    /** viewIdResourceName，例如 com.sfacg:id/tv_sign。 */
    String viewId();

    String className();

    boolean clickable();

    boolean enabled();

    /** 复选框类控件是否已勾选；非可勾选控件恒为 false。 */
    boolean checked();

    /** 是否是可滚动容器（列表/目录页靠它翻页找章节）。 */
    boolean scrollable();

    /** 平台明确标为标题才返回 true；false 不代表已经证明这是一章。 */
    default boolean heading() { return false; }

    /** 2026-09-15 长明细会改变集合元数据；未知必须保留 -1，不能冒充空清单或第零项。 */
    default int collectionRowCount() { return -1; }

    default int collectionColumnCount() { return -1; }

    default int collectionRowIndex() { return -1; }

    default int collectionRowSpan() { return -1; }

    default int collectionColumnIndex() { return -1; }

    default int collectionColumnSpan() { return -1; }

    /** 仅表示节点公开了该方向动作，不代表动作已经执行或已到边界。 */
    default boolean supportsScrollForward() { return false; }

    default boolean supportsScrollBackward() { return false; }

    /** 用户当前是否真的看得见它；被弹窗遮住的文字也可能残留在节点树中。 */
    boolean visible();

    /**
     * 屏幕坐标 {left, top, right, bottom}，恒返回 4 个元素、不返回 null。
     *
     * <p>节点中心是手势点击的坐标来源；没有面积就不能由这个节点确定落点。
     */
    int[] boundsInScreen();

    int childCount();

    /** 越界或取不到时返回 null。 */
    NodeView child(int index);

    NodeView parent();
}
