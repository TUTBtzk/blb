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

    /**
     * 用户当前是否真的看得见它。广告卡是一层盖一层的，被盖住的那层文字还在树里
     * （实测穿山甲的弹卡下面就压着一张含「立即下载」的下载卡），认了它就是空点。
     */
    boolean visible();

    /**
     * 屏幕坐标 {left, top, right, bottom}，恒返回 4 个元素、不返回 null。
     *
     * <p>广告播放页整棵树可能没有一个 clickable 节点，只能按 bounds 中心做手势点击，
     * 所以「有没有面积」是能不能点的前提，也是点不动时唯一能写进日志的线索。
     */
    int[] boundsInScreen();

    int childCount();

    /** 越界或取不到时返回 null。 */
    NodeView child(int index);

    NodeView parent();
}
