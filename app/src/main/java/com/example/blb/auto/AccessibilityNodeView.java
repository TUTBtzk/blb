package com.example.blb.auto;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 把 AccessibilityNodeInfo 适配成 {@link NodeView}。
 *
 * <p>不做 recycle()：从 API 33 起它已经是空实现，本项目的目标机是 Android 15，
 * 低版本上只会多一点 GC 压力，换来的是遍历代码不必手工管生命周期。
 */
public final class AccessibilityNodeView implements NodeView {

    private final AccessibilityNodeInfo info;

    private AccessibilityNodeView(AccessibilityNodeInfo info) {
        this.info = info;
    }

    public static NodeView of(AccessibilityNodeInfo info) {
        return info == null ? null : new AccessibilityNodeView(info);
    }

    public AccessibilityNodeInfo raw() {
        return info;
    }

    /** 从 NodeView 取回底层节点，取不到返回 null。 */
    public static AccessibilityNodeInfo rawOf(NodeView view) {
        return view instanceof AccessibilityNodeView ? ((AccessibilityNodeView) view).info : null;
    }

    @Override
    public String text() {
        CharSequence cs = info.getText();
        return cs == null ? null : cs.toString();
    }

    @Override
    public String desc() {
        CharSequence cs = info.getContentDescription();
        return cs == null ? null : cs.toString();
    }

    @Override
    public String viewId() {
        return info.getViewIdResourceName();
    }

    @Override
    public String className() {
        CharSequence cs = info.getClassName();
        return cs == null ? null : cs.toString();
    }

    @Override
    public boolean clickable() {
        return info.isClickable();
    }

    @Override
    public boolean enabled() {
        return info.isEnabled();
    }

    @Override
    public boolean checked() {
        return info.isChecked();
    }

    @Override
    public boolean scrollable() {
        return info.isScrollable();
    }

    @Override
    public boolean visible() {
        try {
            return info.isVisibleToUser();
        } catch (Exception e) {
            // 窗口正在变化时可能抛，宁可当成看得见（漏过滤好过把真按钮过滤掉）。
            return true;
        }
    }

    @Override
    public int[] boundsInScreen() {
        Rect r = new Rect();
        try {
            info.getBoundsInScreen(r);
        } catch (Exception e) {
            return new int[]{0, 0, 0, 0};
        }
        return new int[]{r.left, r.top, r.right, r.bottom};
    }

    @Override
    public int childCount() {
        return info.getChildCount();
    }

    @Override
    public NodeView child(int index) {
        try {
            return of(info.getChild(index));
        } catch (Exception e) {
            // 窗口在遍历过程中变化时 getChild 可能抛异常，当成没有这个子节点。
            return null;
        }
    }

    @Override
    public NodeView parent() {
        try {
            return of(info.getParent());
        } catch (Exception e) {
            return null;
        }
    }
}
