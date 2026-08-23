package com.example.blb.ui;

import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.blb.R;
import com.example.blb.crypto.KeyStoreBox;
import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.Db;
import com.example.blb.util.Texts;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

/** 账号页：增删改、启用开关、火券余额。密码存进 Room 前先过 KeyStoreBox 加密。 */
public class AccountsFragment extends Fragment {

    private AccountDao dao;
    private SimpleAdapter<Account> adapter;
    private TextView empty;

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

        adapter = new SimpleAdapter<Account>(R.layout.item_two_line, this::bind)
                .onClick((item, pos) -> edit(item))
                .onLongClick((item, pos) -> confirmDelete(item));

        RecyclerView list = v.findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.addItemDecoration(new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));
        list.setAdapter(adapter);

        v.<FloatingActionButton>findViewById(R.id.add).setOnClickListener(b -> edit(null));

        dao.observeAll().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(List<Account> accounts) {
        adapter.submit(accounts);
        boolean none = accounts == null || accounts.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
    }

    private void bind(View row, Account a, int position) {
        row.<TextView>findViewById(R.id.line1).setText(
                a.displayName() + (a.enabled ? "" : "（已停用）"));

        StringBuilder sb = new StringBuilder();
        sb.append(a.loginName);
        sb.append(" · ").append(a.loginKindLabel());
        if (a.needsPassword()) {
            sb.append(a.hasPassword() ? " · 已存密码" : " · 未存密码（切号会失败）");
        }
        sb.append(" · ").append(a.balanceText());
        if (a.lastCheckInAt > 0) {
            sb.append(" · 上次签到 ").append(DateFormat.format("MM-dd HH:mm", a.lastCheckInAt));
        } else {
            sb.append(" · 还没签过");
        }
        if (!Texts.isBlank(a.nickname)) {
            sb.append("\n菠萝包昵称：").append(a.nickname);
        }
        if (!a.canAutoLogin()) {
            sb.append("\n切到这个号时要你手点一次授权");
        }
        row.<TextView>findViewById(R.id.line2).setText(sb);
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
            coupons.setText(existing.lastKnownCoupons > 0
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

        KeyStoreBox.Sealed sealed = null;
        if (needsPassword && !Texts.isBlank(draft.password)) {
            try {
                sealed = KeyStoreBox.seal(draft.password);
            } catch (KeyStoreBox.CryptoException e) {
                toast("密码加密失败：" + e.getMessage());
                return;
            }
        }

        Account account = existing == null ? new Account() : existing;
        account.label = Texts.isBlank(draft.label) ? null : draft.label;
        account.loginName = draft.login;
        account.loginKind = draft.kind;
        account.enabled = draft.enabled;
        account.nickname = Texts.isBlank(draft.nickname) ? null : draft.nickname;
        int fire = Texts.parseCount(draft.coupons);
        if (fire >= 0) account.lastKnownCoupons = fire;
        int voucher = Texts.parseCount(draft.vouchers);
        if (voucher >= 0) account.lastKnownVouchers = voucher;
        if (sealed != null) {
            account.encPassword = sealed.cipherText;
            account.encIv = sealed.iv;
        }
        // 换了登录名就说明这条记录换了个号，旧昵称会让切号校验一直判失败。
        if (existing != null && !draft.login.equals(existing.loginName)
                && Texts.isBlank(draft.nickname)) {
            account.nickname = null;
        }

        boolean isNew = existing == null;
        Db.io(() -> {
            try {
                if (isNew) {
                    account.sortOrder = dao.maxSortOrder() + 1;
                    dao.insert(account);
                } else {
                    dao.update(account);
                }
            } catch (Exception e) {
                post(() -> toast("保存失败（登录名可能已存在）：" + e.getMessage()));
            }
        });
    }

    private void confirmDelete(Account account) {
        new AlertDialog.Builder(requireContext())
                .setTitle("删除 " + account.displayName() + "？")
                .setMessage("它的签到日志和订阅记录会一起删掉，这个操作没法撤销。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> Db.io(() -> dao.delete(account)))
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
