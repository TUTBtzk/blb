package com.example.blb.auto;

import android.graphics.Rect;
import android.os.Build;
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
    public boolean heading() {
        if (Build.VERSION.SDK_INT >= 28 && info.isHeading()) return true;
        AccessibilityNodeInfo.CollectionItemInfo item = info.getCollectionItemInfo();
        return item != null && item.isHeading();
    }

    @Override public int collectionRowCount() {
        try {
            AccessibilityNodeInfo.CollectionInfo value = info.getCollectionInfo();
            return value == null ? -1 : value.getRowCount();
        } catch (RuntimeException unavailable) { return -1; }
    }

    @Override public int collectionColumnCount() {
        try {
            AccessibilityNodeInfo.CollectionInfo value = info.getCollectionInfo();
            return value == null ? -1 : value.getColumnCount();
        } catch (RuntimeException unavailable) { return -1; }
    }

    @Override public int collectionRowIndex() {
        try {
            AccessibilityNodeInfo.CollectionItemInfo value = info.getCollectionItemInfo();
            return value == null ? -1 : value.getRowIndex();
        } catch (RuntimeException unavailable) { return -1; }
    }

    @Override public int collectionRowSpan() {
        try {
            AccessibilityNodeInfo.CollectionItemInfo value = info.getCollectionItemInfo();
            return value == null ? -1 : value.getRowSpan();
        } catch (RuntimeException unavailable) { return -1; }
    }

    @Override public int collectionColumnIndex() {
        try {
            AccessibilityNodeInfo.CollectionItemInfo value = info.getCollectionItemInfo();
            return value == null ? -1 : value.getColumnIndex();
        } catch (RuntimeException unavailable) { return -1; }
    }

    @Override public int collectionColumnSpan() {
        try {
            AccessibilityNodeInfo.CollectionItemInfo value = info.getCollectionItemInfo();
            return value == null ? -1 : value.getColumnSpan();
        } catch (RuntimeException unavailable) { return -1; }
    }

    @Override public boolean supportsScrollForward() {
        return supportsAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    @Override public boolean supportsScrollBackward() {
        return supportsAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    private boolean supportsAction(int actionId) {
        try {
            for (AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) {
                if (action != null && action.getId() == actionId) return true;
            }
        } catch (RuntimeException unavailable) { return false; }
        return false;
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
