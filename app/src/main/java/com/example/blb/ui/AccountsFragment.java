package com.example.blb.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.blb.R;
import com.example.blb.crypto.KeyStoreBox;
import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.Db;
import com.example.blb.util.Texts;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

/** 账号页：增删改、启用开关、火券余额。密码存进 Room 前先过 KeyStoreBox 加密。 */
public class AccountsFragment extends Fragment {

    private AccountDao dao;
    private SimpleAdapter<Account> adapter;
    private TextView empty;
    private TextView overview;
    private View overviewDot;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_accounts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        dao = Db.get(requireContext()).accountDao();
        empty = v.findViewById(R.id.empty);
        overview = v.findViewById(R.id.overview);
        overviewDot = v.findViewById(R.id.overview_dot);

        adapter = new SimpleAdapter<Account>(R.layout.item_two_line, this::bind)
                .onClick((item, pos) -> edit(item))
                .onLongClick((item, pos) -> confirmDelete(item));

        RecyclerView list = v.findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        // 行是卡片，卡片之间已经有 10dp 间距，不再画分割线。
        list.setAdapter(adapter);

        // 悬浮按钮带上了文字（「添加账号」），所以是 ExtendedFloatingActionButton
        // 而不是圆的那个 —— 这里按 View 取，换控件时不用跟着改类型。
        v.findViewById(R.id.add).setOnClickListener(b -> edit(null));

        dao.observeAll().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(List<Account> accounts) {
        adapter.submit(accounts);
        boolean none = accounts == null || accounts.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        renderOverview(accounts);
    }

    /**
     * 顶部那一行总览：几个号在跑、手上还剩多少券。
     *
     * <p>句子由 {@link AccountOverviewText} 合成（它进单测），颜色只走三档：
     * 绿＝都能自动跑、琥珀＝有号缺密码（切号会失败）、灰＝一个启用号都没有。
     */
    private void renderOverview(List<Account> accounts) {
        if (overview == null) return;
        overview.setText(AccountOverviewText.line(accounts));
        tint(overviewDot, AccountOverviewText.tone(accounts).foreground);
    }

    private void tint(View v, @ColorRes int res) {
        if (v == null) return;
        v.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), res)));
    }

    private void bind(View row, Account a, int position) {
        // 左侧色条＝这个号今晚能不能自己跑起来：停用＝灰、缺密码＝琥珀（切号会失败）、齐了＝绿。
        boolean loginable = !a.needsPassword() || a.hasPassword();
        StatusPalette tone = StatusPalette.forAccount(a.enabled, loginable);
        row.findViewById(R.id.accent).setBackgroundColor(
                ContextCompat.getColor(row.getContext(), tone.foreground));

        // 一行名字；「已停用」「缺密码」这些状态交给下面那句状态话去说，标题上不再重复。
        String title = a.displayName();
        row.<TextView>findViewById(R.id.line1).setText(title);

        // 2026-09-15 用户要的：每一行都能直接切到这个号（和「已登记的章节」页那颗一样）。
        // 行的点击仍然是「编辑」、长按仍然是「删除」，这颗按钮自己吃掉点击事件，互不影响。
        TextView switchButton = row.findViewById(R.id.badge);
        switchButton.setVisibility(View.VISIBLE);
        switchButton.setText(R.string.account_switch);
        switchButton.setTextSize(15f);
        // 切号是退登＋重登（最招验证码），所以把可点区域撑到 48dp 并写清会切到谁。
        switchButton.setMinHeight(Math.round(48 * row.getResources().getDisplayMetrics().density));
        switchButton.setGravity(Gravity.CENTER);
        switchButton.setContentDescription(getString(R.string.account_switch) + "：" + title);
        switchButton.setOnClickListener(x -> confirmSwitch(a));

        // 第二行只留一句状态：能不能自动跑 · 还有多少代券 · 上次什么时候签的（见 ui/AccountRowText）。
        // 时间在这里格式化 —— DateFormat 是 Android API，不能进那个可以进单测的类。
        String lastCheckIn = a.lastCheckInAt > 0
                ? DateFormat.format("MM-dd HH:mm", a.lastCheckInAt).toString() : "";
        row.<TextView>findViewById(R.id.line2).setText(AccountRowText.statusLine(a, lastCheckIn));
    }

    /** Spinner 的第 n 项对应哪种登录方式，顺序跟 strings.xml 里的 account_kinds 一致。 */
    private static final String[] KINDS = {
            Account.KIND_PASSWORD, Account.KIND_PHONE_ONE_TAP,
            Account.KIND_WECHAT, Account.KIND_QQ, Account.KIND_WEIBO
    };

    private static int kindIndex(String kind) {
        for (int i = 0; i < KINDS.length; i++) {
            if (KINDS[i].equals(kind)) return i;
        }
        return 0;
    }

    /** 表单填的东西。字段多，塞一个小对象比十个参数好读。 */
    private static final class Draft {
        String label;
        String login;
        String password;
        String nickname;
        String kind = Account.KIND_PASSWORD;
        String coupons;
        String vouchers;
        boolean enabled = true;
    }

    private void edit(@Nullable Account existing) {
        View form = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_account_edit, null, false);
        EditText label = form.findViewById(R.id.label);
        EditText login = form.findViewById(R.id.login);
        EditText password = form.findViewById(R.id.password);
        EditText nickname = form.findViewById(R.id.nickname);
        EditText coupons = form.findViewById(R.id.coupons);
        EditText vouchers = form.findViewById(R.id.vouchers);
        MaterialSwitch enabled = form.findViewById(R.id.enabled);
        Spinner kind = form.findViewById(R.id.kind);
        TextView kindHint = form.findViewById(R.id.kind_hint);

        ArrayAdapter<String> kinds = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.account_kinds));
        kind.setAdapter(kinds);
        kind.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                kindHint.setText(hintFor(KINDS[pos]));
                // 只有账号密码那种需要存密码，其余方式存了也用不上。
                password.setVisibility(Account.KIND_PASSWORD.equals(KINDS[pos])
                        ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        if (existing != null) {
            label.setText(existing.label);
            login.setText(existing.loginName);
            nickname.setText(existing.nickname);
            coupons.setText(existing.lastKnownCoupons >= 0
                    ? String.valueOf(existing.lastKnownCoupons) : "");
            vouchers.setText(existing.lastKnownVouchers >= 0
                    ? String.valueOf(existing.lastKnownVouchers) : "");
            enabled.setChecked(existing.enabled);
            kind.setSelection(kindIndex(existing.loginKind));
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(existing == null ? R.string.accounts_add : R.string.tab_accounts)
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    Draft draft = new Draft();
                    draft.label = label.getText().toString().trim();
                    draft.login = login.getText().toString().trim();
                    draft.password = password.getText().toString();
                    draft.nickname = nickname.getText().toString().trim();
                    draft.coupons = coupons.getText().toString().trim();
                    draft.vouchers = vouchers.getText().toString().trim();
                    draft.enabled = enabled.isChecked();
                    int pos = kind.getSelectedItemPosition();
                    draft.kind = KINDS[pos < 0 || pos >= KINDS.length ? 0 : pos];
                    save(existing, draft);
                })
                .show();
    }

    private String hintFor(String kind) {
        if (Account.KIND_PASSWORD.equals(kind)) {
            return getString(R.string.account_kind_hint_password);
        }
        if (Account.KIND_PHONE_ONE_TAP.equals(kind)) {
            return getString(R.string.account_kind_hint_one_tap);
        }
        return getString(R.string.account_kind_hint_third);
    }

    private void save(@Nullable Account existing, Draft draft) {
        if (Texts.isBlank(draft.login)) {
            toast("登录名不能为空");
            return;
        }
        boolean needsPassword = Account.KIND_PASSWORD.equals(draft.kind);
        if (needsPassword && existing == null && Texts.isBlank(draft.password)) {
            toast("账号密码方式必须填密码，切号时要用它登录");
            return;
        }

        boolean isNew = existing == null;
        long accountId = isNew ? 0 : existing.id;
        Context app = requireContext().getApplicationContext();
        // 名字要等事务里取到账号对象才算得出来（备注名优先、否则登录名），
        // 完成弹窗说「谁」比说「保存成功」有用得多。
        final String[] savedName = new String[1];
        LedgerEdits.submit(app, () -> {
            try {
                // 占用成功后再取数据库对象，不能提前修改列表正在显示的账号。
                Account account = isNew ? new Account() : dao.byId(accountId);
                if (account == null) {
                    throw new IllegalStateException("账号已被删除，请重新添加");
                }
                if (needsPassword && !Texts.isBlank(draft.password)) {
                    KeyStoreBox.Sealed sealed = KeyStoreBox.seal(draft.password);
                    account.encPassword = sealed.cipherText;
                    account.encIv = sealed.iv;
                }
                account.label = Texts.isBlank(draft.label) ? null : draft.label;
                account.loginName = draft.login;
                account.loginKind = draft.kind;
                account.enabled = draft.enabled;
                account.nickname = Texts.isBlank(draft.nickname) ? null : draft.nickname;
                int fire = Texts.parseCount(draft.coupons);
                if (fire >= 0) account.lastKnownCoupons = fire;
                int voucher = Texts.parseCount(draft.vouchers);
                if (voucher >= 0) account.lastKnownVouchers = voucher;
                if (isNew) {
                    account.sortOrder = dao.maxSortOrder() + 1;
                    dao.insert(account);
                } else {
                    dao.update(account);
                }
                savedName[0] = account.displayName();
                // 身份或启用名单一变，旧清单结论就不能再替这批账号证明购买归属。
                Db.get(app).auditDao().invalidateAll();
            } catch (KeyStoreBox.CryptoException e) {
                throw new IllegalStateException("密码加密失败：" + e.getMessage(), e);
            }
        }, () -> post(() -> DoneDialogActivity.showDone(requireContext(),
                getString(R.string.done_label_save_account),
                getString(R.string.done_saved_account,
                        savedName[0] == null ? "账号" : savedName[0]))));
    }

    private void confirmDelete(Account account) {
        Context app = requireContext().getApplicationContext();
        new AlertDialog.Builder(requireContext())
                .setTitle("删除无订阅记录的账号？")
                .setMessage(account.displayName() + "：仅允许删除没有订阅记录的账号，其签到日志会一起删除。"
                        + "已有订阅记录时请停用账号；怀疑记错时，先在订阅页点『核对订阅清单』。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("删除无账账号", (d, w) ->
                        LedgerEdits.submit(app, () -> {
                            dao.delete(account);
                            Db.get(app).auditDao().invalidateAll();
                        }, () -> post(() -> DoneDialogActivity.showDone(requireContext(),
                                getString(R.string.done_label_delete_account),
                                getString(R.string.done_deleted_account, account.displayName())))))
                .show();
    }

    /**
     * 切到这个号。先问一句再动手 —— 账号页一行一颗按钮，误触的代价是一次真的退登＋重登
     * （最招验证码），而「已登记章节」页那颗是点行触发、路径唯一，所以那边没有确认框。
     */
    private void confirmSwitch(Account account) {
        String blocked = AccountSwitchAction.blocker(requireContext());
        if (blocked != null) {
            toast(blocked);
            return;
        }
        Context app = requireContext().getApplicationContext();
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.account_switch_confirm_title, account.displayName()))
                .setMessage(R.string.account_switch_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.account_switch, (d, w) -> {
                    toast(AccountSwitchAction.startedMessage(account));
                    AccountSwitchAction.start(app, account);
                })
                .show();
    }

    private void post(Runnable action) {
        View v = getView();
        if (v != null) v.post(action);
    }

    private void toast(String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }
}
