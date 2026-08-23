package com.example.blb.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个通用列表适配器：给个 item 布局和绑定回调就能用。
 * 账号、签到日志、章节、订阅记录几个列表都靠它，省掉四份几乎相同的 Adapter。
 */
public class SimpleAdapter<T> extends RecyclerView.Adapter<SimpleAdapter.VH> {

    public interface Binder<T> {
        void bind(View row, T item, int position);
    }

    public interface OnItemClick<T> {
        void onClick(T item, int position);
    }

    static class VH extends RecyclerView.ViewHolder {
        VH(@NonNull View itemView) {
            super(itemView);
        }
    }

    private final int layoutRes;
    private final Binder<T> binder;
    private OnItemClick<T> onClick;
    private OnItemClick<T> onLongClick;
    private List<T> items = new ArrayList<>();

    public SimpleAdapter(int layoutRes, Binder<T> binder) {
        this.layoutRes = layoutRes;
        this.binder = binder;
    }

    public SimpleAdapter<T> onClick(OnItemClick<T> listener) {
        this.onClick = listener;
        return this;
    }

    public SimpleAdapter<T> onLongClick(OnItemClick<T> listener) {
        this.onLongClick = listener;
        return this;
    }

    public void submit(List<T> newItems) {
        items = newItems == null ? new ArrayList<>() : new ArrayList<>(newItems);
        notifyDataSetChanged();
    }

    public T itemAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
        return new VH(row);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        T item = items.get(position);
        binder.bind(holder.itemView, item, position);
        holder.itemView.setOnClickListener(onClick == null ? null
                : v -> onClick.onClick(item, position));
        holder.itemView.setOnLongClickListener(onLongClick == null ? null : v -> {
            onLongClick.onClick(item, position);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
