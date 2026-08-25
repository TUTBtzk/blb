package com.example.blb.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.blb.R;
import com.example.blb.util.Texts;
import com.google.android.material.card.MaterialCardView;

/**
 * 「点进去看全部」那一行：标题 + 一行摘要 + 右端箭头，整行可点。
 *
 * <p>签到页和订阅页原来有五处「信息很多、窗口很小」的地方（辅助点击说明折成两行、
 * 今日状态挤在半屏列表里、日志锁死在 120dp、8 个号的累计压成一行小字、章节列表内嵌滚动）。
 * 现在它们都换成这一行，完整内容搬进 {@link DetailActivity}。
 *
 * <p><b>摘要那一行是硬要求，不是装饰。</b>这个 App 的使用者手指不能动 ——
 * 只有点开才看得到的信息对他等于不存在。所以一级页面必须在不点的前提下把最要紧的一句
 * 说出来（今天签到了几个、最新一行日志、这本书登记了多少章），二级页面才是完整的那一份。
 */
public class EntryRowView extends MaterialCardView {

    private final TextView title;
    private final TextView summary;

    public EntryRowView(@NonNull Context context) {
        this(context, null);
    }

    public EntryRowView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.view_entry_row, this, true);
        title = findViewById(R.id.entry_title);
        summary = findViewById(R.id.entry_summary);
        setClickable(true);
        setFocusable(true);
    }

    public void setTitle(CharSequence text) {
        title.setText(text);
    }

    /** 摘要为空就整行收起来 —— 留一条空白比不留更像出错了。 */
    public void setSummary(CharSequence text) {
        boolean blank = text == null || Texts.isBlank(text.toString());
        summary.setText(blank ? "" : text);
        summary.setVisibility(blank ? View.GONE : View.VISIBLE);
    }
}
