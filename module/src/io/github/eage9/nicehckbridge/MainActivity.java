package io.github.eage9.nicehckbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.eage9.nicehckbridge.proto.Frames;
import io.github.eage9.nicehckbridge.ui.Md3;

/**
 * MD3 界面：
 *   TopAppBar 区（标题 + 版本） / 连接状态卡（三段电量） / 降噪分段按钮 / 操作按钮 / 页脚诊断
 */
public class MainActivity extends Activity implements EarbudsState.Listener {

    private static final String[] LABELS = {"关闭", "通透", "降噪", "深度降噪", "实验降噪", "抗风噪"};
    private static final int[] MODES = {0, 1, 2, 3, 16, 17};
    private static final int[] ANC_ICONS = {
            io.github.eage9.nicehckbridge.R.drawable.anc_off, io.github.eage9.nicehckbridge.R.drawable.anc_transparency, io.github.eage9.nicehckbridge.R.drawable.anc_on,
            io.github.eage9.nicehckbridge.R.drawable.anc_deep, io.github.eage9.nicehckbridge.R.drawable.anc_experimental, io.github.eage9.nicehckbridge.R.drawable.anc_wind};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout[] pillBox = new LinearLayout[MODES.length];
    private final TextView[] pillText = new TextView[MODES.length];
    private final android.widget.ImageView[] pillIcon = new android.widget.ImageView[MODES.length];

    private TextView statusDot;
    private TextView statusText;
    private TextView batteryTotal;
    private TextView batteryL, batteryR, batteryC;
    private TextView footer;
    private TextView hint;
    private LinearLayout root;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        EarbudsState.bind(this);
        setContentView(buildUi());
        EarbudsState.addListener(this);
        render();
    }

    /* ------------------------- UI 构建 ------------------------- */

    private View buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Md3.dp(this, 16);
        root.setPadding(p, Md3.dp(this, 28), p, Md3.dp(this, 28));
        root.setBackgroundColor(Md3.surface(this));

        root.addView(buildTopBar());
        root.addView(space(20));
        root.addView(buildStatusCard());
        root.addView(space(24));
        LinearLayout noiseHead = new LinearLayout(this);
        noiseHead.setGravity(Gravity.CENTER_VERTICAL);
        noiseHead.setPadding(Md3.dp(this, 4), 0, 0, 0);
        noiseHead.addView(icon(io.github.eage9.nicehckbridge.R.drawable.anc_on, Md3.onSurfaceVariant(this), 16));
        TextView nl = Md3.labelMedium(this, "  降噪模式", Md3.onSurfaceVariant(this));
        noiseHead.addView(nl);
        root.addView(noiseHead);
        root.addView(space(8));
        root.addView(buildPillsRow(0, 3));
        root.addView(space(8));
        root.addView(buildPillsRow(3, 6));
        root.addView(space(24));
        root.addView(buildActions());
        root.addView(space(20));
        root.addView(buildFooter());

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Md3.surface(this));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private View buildTopBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // 图标徽标（与启动器图标同源：NK）
        FrameLayout badge = new FrameLayout(this);
        int bs = Md3.dp(this, 44);
        badge.setBackground(Md3.rounded(this, Md3.primaryContainer(this), 14, Color.TRANSPARENT, 0));
        android.widget.ImageView glyph = icon(io.github.eage9.nicehckbridge.R.drawable.ic_nk_badge,
                Md3.onPrimaryContainer(this), 26);
        badge.addView(glyph, new FrameLayout.LayoutParams(Md3.dp(this, 26), Md3.dp(this, 26), Gravity.CENTER));
        row.addView(badge, new LinearLayout.LayoutParams(bs, bs));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Md3.dp(this, 14), 0, 0, 0);
        titles.addView(Md3.titleLarge(this, getString(io.github.eage9.nicehckbridge.R.string.app_name), Md3.onSurface(this)));
        titles.addView(Md3.bodySmall(this, "系统通道 · v" + BuildInfo.VERSION_NAME, Md3.onSurfaceVariant(this)));
        row.addView(titles);
        return row;
    }

    private View buildStatusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Md3.dp(this, 18);
        card.setPadding(p, p, p, p);
        card.setBackground(Md3.rounded(this, Md3.surfaceContainer(this), Md3.RADIUS_XL, Color.TRANSPARENT, 0));

        // 只保留三段电量（没有"已连接"行、没有总电量），把空间留给环
        LinearLayout bat = new LinearLayout(this);
        bat.setOrientation(LinearLayout.HORIZONTAL);
        batteryL = addBattery(bat, "左侧", 0);
        batteryC = addBattery(bat, "充电盒", 1);
        batteryR = addBattery(bat, "右侧", 2);
        card.addView(bat);
        return card;
    }

    /** 电量单元：大号百分比 + 小标签（MD3 titleLarge + bodySmall） */
    private TextView addBattery(LinearLayout parent, String label, int idx) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        int iconRes = (idx == 1) ? io.github.eage9.nicehckbridge.R.drawable.ic_case : io.github.eage9.nicehckbridge.R.drawable.ic_bud;
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setGravity(Gravity.CENTER);
        iconRow.addView(icon(iconRes, Md3.onSurfaceVariant(this), 22));
        col.addView(iconRow);
        col.addView(space(4));
        TextView v = Md3.text(this, "—", 30, Md3.onSurface(this), true);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(v.getTypeface(), Typeface.BOLD);
        TextView l = Md3.bodySmall(this, label, Md3.onSurfaceVariant(this));
        l.setGravity(Gravity.CENTER);
        col.addView(v);
        col.addView(l);
        parent.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    private View buildPillsRow(int from, int to) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = from; i < to; i++) {
            final int mode = MODES[i];

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(Md3.dp(this, 10), Md3.dp(this, 12), Md3.dp(this, 10), Md3.dp(this, 12));
            Md3.minTouch(box, this);

            android.widget.ImageView ic = icon(ANC_ICONS[i], Md3.onSurface(this), 16);
            box.addView(ic);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Md3.dp(this, 16), Md3.dp(this, 16));
            ip.setMarginEnd(Md3.dp(this, 6));
            ic.setLayoutParams(ip);

            TextView label = Md3.labelLarge(this, LABELS[i], Md3.onSurface(this));
            box.addView(label);

            pillBox[i] = box;
            pillText[i] = label;
            pillIcon[i] = ic;

            box.setOnClickListener(v -> {
                if (!EarbudsState.isConnected()) {
                    flash("未连接：请先让原道 App 连上耳机");
                    return;
                }
                CmdSender.setNoise(this, mode);
                flash("已发送：" + LABELS[modeIndex(mode)]);
                main.postDelayed(this::render, 250);
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > from) lp.setMarginStart(Md3.dp(this, 8));
            row.addView(box, lp);
        }
        return row;
    }

    private int modeIndex(int mode) {
        for (int i = 0; i < MODES.length; i++) if (MODES[i] == mode) return i;
        return 0;
    }

    private View buildActions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView refresh = Md3.labelLarge(this, "  刷新状态", Md3.onSecondaryContainer(this));
        refresh.setGravity(Gravity.CENTER);
        refresh.setPadding(Md3.dp(this, 20), Md3.dp(this, 14), Md3.dp(this, 20), Md3.dp(this, 14));
        android.graphics.drawable.Drawable rd = getDrawable(io.github.eage9.nicehckbridge.R.drawable.ic_refresh);
        if (rd != null) {
            int s3 = Md3.dp(this, 16);
            rd = rd.mutate();
            rd.setTint(Md3.onSecondaryContainer(this));
            rd.setBounds(0, 0, s3, s3);
            refresh.setCompoundDrawables(rd, null, null, null);
            refresh.setCompoundDrawablePadding(Md3.dp(this, 4));
        }
        refresh.setBackground(Md3.rounded(this, Md3.secondaryContainer(this), 20, Color.TRANSPARENT, 0));
        refresh.setOnClickListener(v -> { CmdSender.refresh(this); flash("正在刷新…"); });
        Md3.minTouch(refresh, this);
        row.addView(refresh);

        TextView open = Md3.labelLarge(this, "打开原道 App", Md3.primary(this));
        open.setGravity(Gravity.CENTER);
        open.setPadding(Md3.dp(this, 16), Md3.dp(this, 14), Md3.dp(this, 16), Md3.dp(this, 14));
        open.setOnClickListener(v -> openApp());
        Md3.minTouch(open, this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Md3.dp(this, 8));
        row.addView(open, lp);
        return row;
    }

    private View buildFooter() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        hint = Md3.bodySmall(this, "", Md3.onSurfaceVariant(this));
        footer = Md3.bodySmall(this, "", Md3.onSurfaceVariant(this));
        col.addView(hint);
        col.addView(space(6));
        col.addView(footer);
        return col;
    }

    /** 图标（按给定颜色染色），sizeDp 见调用处 */
    private android.widget.ImageView icon(int resId, int color, float sizeDp) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        android.graphics.drawable.Drawable d = getDrawable(resId);
        if (d != null) {
            d = d.mutate();
            d.setTint(color);
            iv.setImageDrawable(d);
        }
        int s = Md3.dp(this, sizeDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return iv;
    }

    private TextView sectionLabel(String s) {
        TextView t = Md3.labelMedium(this, s, Md3.onSurfaceVariant(this));
        t.setPadding(Md3.dp(this, 4), 0, 0, 0);
        return t;
    }

    private View space(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Md3.dp(this, dp)));
        return v;
    }

    private void flash(String msg) {
        hint.setText(msg);
    }

    private void openApp() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setClassName("com.yuandao.nicehck", "com.yuandao.nicehck.MainActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /* ------------------------- 渲染 ------------------------- */

    @Override protected void onDestroy() {
        EarbudsState.removeListener(this);
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            main.postDelayed(this, 3000);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        CmdSender.refresh(this);
        NoiseWidget.refreshAll(this);
        main.removeCallbacks(tick);
        main.postDelayed(this::render, 400);
        main.postDelayed(tick, 3000);
    }

    @Override protected void onPause() {
        main.removeCallbacks(tick);
        super.onPause();
    }

    @Override public void onStateChanged() { runOnUiThread(this::render); }

    private void render() {
        boolean conn = EarbudsState.isConnected();
        int mode = EarbudsState.noiseMode();

        // 电量（三段环/数字）；未连接时显示 --
        setPct(batteryL, EarbudsState.batteryL(), conn);
        setPct(batteryR, EarbudsState.batteryR(), conn);
        setPct(batteryC, EarbudsState.batteryC(), conn);

        // 分段按钮：选中 = primaryContainer 填充，未选中 = outlineVariant 描边
        for (int i = 0; i < MODES.length; i++) {
            LinearLayout box = pillBox[i];
            if (box == null) continue;
            boolean selected = (mode == MODES[i]);
            int fg = selected ? Md3.onPrimaryContainer(this) : Md3.onSurface(this);
            box.setBackground(selected
                    ? Md3.rounded(this, Md3.primaryContainer(this), 20, Color.TRANSPARENT, 0)
                    : Md3.rounded(this, Color.TRANSPARENT, 20, Md3.outlineVariant(this), 1));
            box.setAlpha(conn ? 1f : 0.45f);
            pillText[i].setTextColor(fg);
            android.graphics.drawable.Drawable d = pillIcon[i].getDrawable();
            if (d != null) {
                d = d.mutate();
                d.setTint(fg);
                pillIcon[i].setImageDrawable(d);
            }
        }

        // 页脚
        long ts = EarbudsState.lastTs();
        String connLine = conn ? "已连接" : "未连接";
        footer.setText(ts == 0
                ? connLine + " · 尚无状态更新，请确认原道 App 已连接耳机、LSPosed 作用域包含原道 App"
                : connLine + " · " + ((System.currentTimeMillis() - ts) / 1000) + " 秒前更新 · 模式 "
                  + (mode < 0 ? "未知" : Frames.modeName(mode)));
    }

    private void setPct(TextView v, int pct, boolean conn) {
        if (!conn || pct < 0) {
            v.setText("—");
            v.setTextColor(Md3.onSurfaceVariant(this));
        } else {
            v.setText(pct + "%");
            v.setTextColor(pct <= 20 ? Md3.error(this) : Md3.onSurface(this));
        }
    }
}
