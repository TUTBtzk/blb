package com.example.blb.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.blb.R;
import com.google.android.material.color.MaterialColors;

/**
 * 列表右边那根一直露在外面的滚动条：按住能拖，在轨道上点一下就直接跳过去。
 *
 * <p>为什么不用 RecyclerView 自带的 fastScroll：它的滑块要先滚一下列表才肯冒出来、
 * 一秒半之后又自己缩回去（隐藏延时是库里的私有常量，改不动）。使用者手指不能动，
 * 「先滑一下把滚动条唤出来、再在一秒半之内准确按住它」这套动作他做不到。所以这根条常驻，
 * 而且<b>轨道上随便点一下就跳到那一处</b> —— 连按住拖都不必，一次点击就够。
 *
 * <p>90 章一屏只看得见 8 行，靠滑要蹭十一屏；滑块按 64dp 起画，手指抖一下也还抓得住。
 */
public class FastScrollBar extends View {

    /** 拖到某一行时气泡上写什么（章节页写「第83章」）。返回 null＝这一页不用气泡。 */
    public interface Labeler {
        @Nullable
        String label(int position);
    }

    /** 滑块最矮也有这么高：细滑块他按不住。 */
    private static final float MIN_THUMB_DP = 64f;
    private static final float TRACK_DP = 6f;
    private static final float THUMB_DP = 14f;
    /** 滑块右边留这么点缝，别真贴到屏幕边缘上（贴边容易和系统的边缘返回手势打架）。 */
    private static final float EDGE_DP = 4f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float minThumb;
    private final float trackW;
    private final float thumbW;
    private final float edge;
    private final int trackColor;
    private final int thumbColor;

    @Nullable
    private RecyclerView list;
    @Nullable
    private TextView bubble;
    @Nullable
    private Labeler labeler;
    private boolean dragging;
    private float touchClickY = Float.NaN;
    /** 滑块顶端和高度（px）：onDraw 和拖动折算共用。 */
    private float thumbTop;
    private float thumbH;

    public FastScrollBar(Context context) {
        this(context, null);
    }

    public FastScrollBar(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FastScrollBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        minThumb = MIN_THUMB_DP * density;
        trackW = TRACK_DP * density;
        thumbW = THUMB_DP * density;
        edge = EDGE_DP * density;
        thumbColor = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSecondary, 0xFF6D4C41);
        trackColor = ColorUtils.setAlphaComponent(thumbColor, 0x2E);
        setContentDescription(context.getString(R.string.detail_scrollbar_hint));
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /**
     * 挂到列表上。{@code bubbleView} 可以是 null（短列表不需要提示气泡）。
     *
     * <p>列表数据是 LiveData 异步来的，挂上的时候可能一行都还没有，所以除了滚动
     * 还得跟着布局变化重算一次 —— 否则第一批数据到位时滑块会停在 0 高度上。
     */
    public void attach(RecyclerView target, @Nullable TextView bubbleView) {
        list = target;
        bubble = bubbleView;
        target.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!dragging) sync();
            }
        });
        target.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> sync());
        sync();
    }

    /** 气泡上写什么。不设＝拖动时不弹气泡。 */
    public void setLabeler(@Nullable Labeler value) {
        labeler = value;
        sync();
    }

    /** 重新算滑块该多高、停在哪儿；内容不到一屏就整根收起来（没得滚，露着只是碍眼）。 */
    private void sync() {
        if (list == null) return;
        int range = list.computeVerticalScrollRange();
        int extent = list.computeVerticalScrollExtent();
        int offset = list.computeVerticalScrollOffset();
        float height = getHeight();
        if (height <= 0 || range <= extent) {
            if (getVisibility() != INVISIBLE) setVisibility(INVISIBLE);
            return;
        }
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        thumbH = Math.max(minThumb, height * extent / (float) range);
        float free = Math.max(1f, height - thumbH);
        thumbTop = free * clamp(offset / (float) (range - extent));
        updateAccessibilityPosition();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        sync();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (list == null || thumbH <= 0) return;
        // 轨道和滑块都画在整根条的最右侧（只留 4dp 不贴屏幕边）：列表自己让出了 20dp，
        // 所以这一条落在卡片外面的空白里，压不到白底，也压不到「点→切号」徽章。
        float cx = getWidth() - edge - thumbW / 2f;
        paint.setColor(trackColor);
        rect.set(cx - trackW / 2f, 0f, cx + trackW / 2f, getHeight());
        canvas.drawRoundRect(rect, trackW / 2f, trackW / 2f, paint);

        // 按住的时候滑块胖一圈：他看得出「抓住了」，不用靠手感确认。
        float w = dragging ? thumbW * 1.5f : thumbW;
        paint.setColor(thumbColor);
        rect.set(cx - w / 2f, thumbTop, cx + w / 2f, thumbTop + thumbH);
        canvas.drawRoundRect(rect, w / 2f, w / 2f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || list == null || getVisibility() != VISIBLE) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                // 手指压在这根条上的时候，别让列表把这串事件抢去当滑动。
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                jumpTo(event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) jumpTo(event.getY());
                return dragging;
            case MotionEvent.ACTION_UP:
                if (!dragging) return false;
                touchClickY = event.getY();
                try {
                    performClick();
                } finally {
                    touchClickY = Float.NaN;
                    finishDrag();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishDrag();
                return true;
            default:
                return false;
        }
    }

    private void finishDrag() {
        dragging = false;
        if (bubble != null) bubble.setVisibility(INVISIBLE);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        sync();
    }

    /** 触屏点按按位置跳转；读屏或键盘的点击没有坐标，执行有明确标签的向下翻页。 */
    @Override
    public boolean performClick() {
        boolean handled = super.performClick();
        if (!isEnabled() || list == null || getVisibility() != VISIBLE) return handled;
        if (!Float.isNaN(touchClickY)) return jumpTo(touchClickY) || handled;
        return list.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                || handled;
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return SeekBar.class.getName();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(@NonNull AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        boolean available = isEnabled() && list != null && getVisibility() == VISIBLE;
        boolean forward = available && list.canScrollVertically(1);
        boolean backward = available && list.canScrollVertically(-1);
        info.setScrollable(forward || backward);
        info.setClickable(forward);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        if (forward) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK,
                    getContext().getString(R.string.detail_scrollbar_next_page)));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        }
        if (backward) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
        if (available && list.getLayoutManager() instanceof LinearLayoutManager) {
            int travel = list.computeVerticalScrollRange() - list.computeVerticalScrollExtent();
            if (travel > 0) {
                float progress = 100f * clamp(list.computeVerticalScrollOffset() / (float) travel);
                info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 100f, progress));
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
            }
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, @Nullable Bundle arguments) {
        if (!isEnabled() || list == null || getVisibility() != VISIBLE) {
            return super.performAccessibilityAction(action, arguments);
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            return list.performAccessibilityAction(action, arguments);
        }
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) {
            if (arguments == null) return false;
            float progress = arguments.getFloat(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN);
            if (Float.isNaN(progress) || Float.isInfinite(progress)) return false;
            float free = Math.max(1f, getHeight() - thumbH);
            boolean handled = jumpTo(clamp(progress / 100f) * free + thumbH / 2f);
            if (bubble != null) bubble.setVisibility(INVISIBLE);
            sync();
            return handled;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    /** 状态独立于操作说明，读屏聚焦在滚动条上时也能知道已经到了哪一项。 */
    private void updateAccessibilityPosition() {
        if (list == null || !(list.getLayoutManager() instanceof LinearLayoutManager)) return;
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (adapter == null || adapter.getItemCount() == 0) return;
        int count = adapter.getItemCount();
        int first = ((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) return;
        int position = Math.min(first, count - 1);
        String label = labeler == null ? null : labeler.label(position);
        ViewCompat.setStateDescription(this, label == null
                ? getContext().getString(R.string.detail_scrollbar_position, position + 1, count)
                : label);
    }

    /**
     * 把手指按下的高度当成「整本的百分之几」，直接跳过去。
     *
     * <p>按<b>行数</b>折算，不按像素：卡片高度基本一致，「拖到一半＝第45章」才符合直觉，
     * 而 {@code computeVerticalScrollRange()} 对 RecyclerView 只是拿平均行高估的，
     * 用它折算越往后越偏。
     */
    private boolean jumpTo(float y) {
        if (list == null) return false;
        RecyclerView.LayoutManager lm = list.getLayoutManager();
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (!(lm instanceof LinearLayoutManager) || adapter == null) return false;
        int count = adapter.getItemCount();
        if (count <= 0) return false;

        float free = Math.max(1f, getHeight() - thumbH);
        float fraction = clamp((y - thumbH / 2f) / free);
        ((LinearLayoutManager) lm).scrollToPositionWithOffset(
                Math.round(fraction * (count - 1)), 0);
        // 拖的时候滑块跟手走，不等列表回调 —— 回调走的是估算值，会把滑块拽回去一点。
        thumbTop = fraction * free;
        invalidate();
        showBubble(Math.round(fraction * (count - 1)));
        return true;
    }

    /** 拖到哪一章要当场说出来：光看滑块位置猜不出「这是第几章」。 */
    private void showBubble(int position) {
        if (bubble == null || labeler == null) return;
        String text = labeler.label(position);
        if (text == null) return;
        bubble.setText(text);
        bubble.setVisibility(VISIBLE);
        float center = thumbTop + thumbH / 2f - bubble.getHeight() / 2f;
        float max = Math.max(0f, getHeight() - bubble.getHeight());
        bubble.setTranslationY(Math.max(0f, Math.min(max, center)));
    }

    private static float clamp(float fraction) {
        return Math.max(0f, Math.min(1f, fraction));
    }
}
