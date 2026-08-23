package com.example.blb.auto;

import java.util.ArrayList;
import java.util.List;

/**
 * 单元测试用的假节点。链式 API 拼出一棵树，用来覆盖 {@link NodeMatcher} 的各种边界，
 * 不需要真机上的 AccessibilityNodeInfo。
 */
final class FakeNode implements NodeView {

    private String text;
    private String desc;
    private String viewId;
    private String className = "android.view.View";
    private boolean clickable;
    private boolean enabled = true;
    private boolean checked;
    private boolean scrollable;
    /** 默认「看得见、有面积」，这样老用例不受 visibleOnly／topmost 影响。 */
    private boolean visible = true;
    private int[] bounds = {0, 0, 100, 100};
    private final List<FakeNode> children = new ArrayList<>();
    private FakeNode parent;

    static FakeNode node() {
        return new FakeNode();
    }

    static FakeNode text(String text) {
        return new FakeNode().withText(text);
    }

    FakeNode withText(String v) {
        this.text = v;
        return this;
    }

    FakeNode withDesc(String v) {
        this.desc = v;
        return this;
    }

    FakeNode withId(String v) {
        this.viewId = v;
        return this;
    }

    FakeNode withClass(String v) {
        this.className = v;
        return this;
    }

    FakeNode clickable(boolean v) {
        this.clickable = v;
        return this;
    }

    FakeNode enabled(boolean v) {
        this.enabled = v;
        return this;
    }

    FakeNode checked(boolean v) {
        this.checked = v;
        return this;
    }

    FakeNode scrollable(boolean v) {
        this.scrollable = v;
        return this;
    }

    FakeNode visible(boolean v) {
        this.visible = v;
        return this;
    }

    FakeNode withBounds(int left, int top, int right, int bottom) {
        this.bounds = new int[]{left, top, right, bottom};
        return this;
    }

    FakeNode add(FakeNode... kids) {
        for (FakeNode kid : kids) {
            kid.parent = this;
            children.add(kid);
        }
        return this;
    }

    @Override
    public String text() {
        return text;
    }

    @Override
    public String desc() {
        return desc;
    }

    @Override
    public String viewId() {
        return viewId;
    }

    @Override
    public String className() {
        return className;
    }

    @Override
    public boolean clickable() {
        return clickable;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean checked() {
        return checked;
    }

    @Override
    public boolean scrollable() {
        return scrollable;
    }

    @Override
    public boolean visible() {
        return visible;
    }

    @Override
    public int[] boundsInScreen() {
        return bounds;
    }

    @Override
    public int childCount() {
        return children.size();
    }

    @Override
    public NodeView child(int index) {
        return index < 0 || index >= children.size() ? null : children.get(index);
    }

    @Override
    public NodeView parent() {
        return parent;
    }
}
