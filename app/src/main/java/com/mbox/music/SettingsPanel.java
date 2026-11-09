package com.mbox.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 设置面板：账号、播放、后台运行、存储、关于。 */
public class SettingsPanel {

    private static final String[] KEYS = {"hires", "lossless", "exhigh", "higher", "standard"};
    private static final String[] LABEL = {
            "Hi-Res", "无损", "极高 · 320kbps", "较高 · 192kbps", "标准 · 128kbps"
    };

    private final Activity act;
    private final View root;
    private final SharedPreferences sp;
    private final TextView tvLevelValue, tvAccount, tvUid, tvVip;
    private final ImageView ivAvatar;
    private final Switch swAutoplay, swAutoScope, swNotify, swUnplug;

    public SettingsPanel(Activity a, View r) {
        act = a;
        root = r;
        sp = a.getSharedPreferences(MainActivity.SP, Activity.MODE_PRIVATE);

        tvLevelValue = r.findViewById(R.id.tvLevelValue);
        tvAccount = r.findViewById(R.id.tvAccount);
        tvUid = r.findViewById(R.id.tvUid);
        tvVip = r.findViewById(R.id.tvVip);
        ivAvatar = r.findViewById(R.id.ivAvatar);
        swAutoplay = r.findViewById(R.id.swAutoplay);
        swAutoScope = r.findViewById(R.id.swAutoScope);
        swNotify = r.findViewById(R.id.swNotify);
        swUnplug = r.findViewById(R.id.swUnplug);

        r.findViewById(R.id.rowSwitch).setOnClickListener(v -> {
            a.startActivity(new Intent(a, LoginActivity.class));
        });
        r.findViewById(R.id.rowCache).setOnClickListener(
                v -> a.startActivity(new Intent(a, CacheActivity.class)));
        r.findViewById(R.id.rowAutoStart).setOnClickListener(v -> Ui.openAutoStart(a));
        r.findViewById(R.id.rowBattery).setOnClickListener(v -> Ui.openBattery(a));
        r.findViewById(R.id.rowLevel).setOnClickListener(v -> showLevelDialog());
        r.findViewById(R.id.rowAutoplay).setOnClickListener(v -> {
            boolean next = !sp.getBoolean(MainActivity.KEY_AUTOPLAY_ALL, true);
            sp.edit().putBoolean(MainActivity.KEY_AUTOPLAY_ALL, next).apply();
            refresh();
        });
        r.findViewById(R.id.rowAutoScope).setOnClickListener(v -> {
            if (!sp.getBoolean(MainActivity.KEY_AUTOPLAY_ALL, true)) {
                Toast.makeText(a, "先打开「进入歌单自动播放」", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean next = !sp.getBoolean(MainActivity.KEY_AUTOPLAY_SCOPE, true);
            sp.edit().putBoolean(MainActivity.KEY_AUTOPLAY_SCOPE, next).apply();
            refresh();
        });
        r.findViewById(R.id.rowUnplug).setOnClickListener(v -> {
            boolean next = !sp.getBoolean(MainActivity.KEY_PAUSE_ON_UNPLUG, true);
            sp.edit().putBoolean(MainActivity.KEY_PAUSE_ON_UNPLUG, next).apply();
            refresh();
        });
        r.findViewById(R.id.rowNotify).setOnClickListener(v -> {
            boolean next = !sp.getBoolean(MainActivity.KEY_NOTIFY_CONTROLS, true);
            sp.edit().putBoolean(MainActivity.KEY_NOTIFY_CONTROLS, next).apply();
            if (next) PlayerService.start(a);
            else PlayerService.stop(a);
            refresh();
        });

        refresh();
        setupAbout();
        setupAccount();
    }

    /** 每次切回设置面板时刷新状态。 */
    public void onShown() {
        refresh();
        setupAccount();
    }

    private void showLevelDialog() {
        String cur = currentLevel();
        final android.app.Dialog d = new android.app.Dialog(act);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_level);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        android.widget.LinearLayout items = d.findViewById(R.id.levelItems);
        for (int i = 0; i < KEYS.length; i++) {
            View row = act.getLayoutInflater().inflate(R.layout.item_level, items, false);
            ((TextView) row.findViewById(R.id.tvLevelLabel)).setText(LABEL[i]);
            boolean on = KEYS[i].equals(cur);
            ((TextView) row.findViewById(R.id.tvLevelCheck)).setText(on ? "✓" : "");
            row.setAlpha(on ? 1f : 0.78f);
            final int idx = i;
            row.setOnClickListener(v -> {
                sp.edit().putString(MainActivity.KEY_LEVEL, KEYS[idx]).apply();
                d.dismiss();
                refresh();
            });
            items.addView(row);
        }
        d.show();
    }

    private String currentLevel() {
        String lv = sp.getString(MainActivity.KEY_LEVEL, "exhigh");
        return NeteaseApi.isValidLevel(lv) ? lv : "exhigh";
    }

    private void refresh() {
        tvLevelValue.setText(NeteaseApi.levelLabel(currentLevel()));
        boolean auto = sp.getBoolean(MainActivity.KEY_AUTOPLAY_ALL, true);
        swAutoplay.setChecked(auto);
        swAutoScope.setChecked(sp.getBoolean(MainActivity.KEY_AUTOPLAY_SCOPE, true));
        swAutoScope.setEnabled(auto);
        root.findViewById(R.id.rowAutoScope).setAlpha(auto ? 1f : 0.4f);
        swNotify.setChecked(sp.getBoolean(MainActivity.KEY_NOTIFY_CONTROLS, true));
        swUnplug.setChecked(sp.getBoolean(MainActivity.KEY_PAUSE_ON_UNPLUG, true));

        AudioCache ac = AudioCache.get(act);
        long used = ac.totalBytes() + ac.coverBytes();
        ((TextView) root.findViewById(R.id.tvCacheSize)).setText(AudioCache.sizeText(used));
    }

    private void setupAbout() {
        ((TextView) root.findViewById(R.id.tvVersion)).setText(BuildInfo.FULL_VERSION);
        ((TextView) root.findViewById(R.id.tvBuildTime))
                .setText("Build Time: " + BuildInfo.BUILD_TIME);

        String h = BuildInfo.COMMIT_HASH;
        String shown = h.length() > 24
                ? h.substring(0, 24) + "\n" + h.substring(24)
                : h;
        ((TextView) root.findViewById(R.id.tvCommit)).setText("Commit Hash: " + shown);

        final String repo = act.getString(R.string.repo_url);
        TextView tvRepo = root.findViewById(R.id.tvRepo);
        tvRepo.setText(repo == null || repo.isEmpty()
                ? "远程仓库：未设置" : "远程仓库：" + repo);
        if (repo != null && !repo.isEmpty()) {
            tvRepo.setTextColor(act.getResources().getColor(R.color.text_dim));
            tvRepo.setOnClickListener(v -> {
                try {
                    act.startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse(repo)));
                } catch (Throwable ignored) {
                }
            });
        }
    }

    private void setupAccount() {
        String mu = sp.getString(MainActivity.KEY_MUSIC_U, null);
        if (mu == null) {
            tvAccount.setText("未登录");
            tvUid.setText("点此登录");
            tvVip.setText("");
            ivAvatar.setImageDrawable(null);
            return;
        }
        String nick = sp.getString(MainActivity.KEY_NICK, null);
        tvAccount.setText(nick != null ? nick : "已登录");
        tvUid.setText("UID " + sp.getLong(MainActivity.KEY_UID, 0));
        String av = sp.getString(MainActivity.KEY_AVATAR, null);
        if (av != null && !av.isEmpty()) CoverLoader.into(ivAvatar, av, 200);

        new Thread(() -> {
            try {
                NeteaseApi.Profile p = new NeteaseApi(mu).account();
                act.runOnUiThread(() -> {
                    if (p == null) {
                        tvAccount.setText("登录已失效");
                        tvAccount.setTextColor(Color.parseColor("#E5484D"));
                        tvUid.setText("请重新登录");
                        tvVip.setText("");
                        sp.edit().remove(MainActivity.KEY_NICK).apply();
                        return;
                    }
                    sp.edit().putLong(MainActivity.KEY_UID, p.uid)
                            .putString(MainActivity.KEY_NICK, p.nickname)
                            .putString(MainActivity.KEY_AVATAR, p.avatarUrl).apply();
                    tvAccount.setText(p.nickname);
                    tvAccount.setTextColor(Color.parseColor("#F2F4F7"));
                    tvUid.setText("UID " + p.uid);
                    tvVip.setText(vipLabel(p.vipType));
                    tvVip.setTextColor(Color.parseColor(
                            p.vipType > 0 ? "#E5484D" : "#5C636D"));
                    if (p.avatarUrl != null && !p.avatarUrl.isEmpty()) {
                        CoverLoader.into(ivAvatar, p.avatarUrl, 200);
                    }
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    private static String vipLabel(int vipType) {
        if (vipType <= 0) return "普通用户";
        if (vipType == 11) return "黑胶VIP · 年费";
        if (vipType == 10) return "黑胶VIP";
        return "会员";
    }
}
