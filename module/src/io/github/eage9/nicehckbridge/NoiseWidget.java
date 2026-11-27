package io.github.eage9.nicehckbridge;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import io.github.eage9.nicehckbridge.proto.Frames;
import io.github.eage9.nicehckbridge.ui.Md3;

/**
 * 单一自适应布局的 MD3 桌面小组件：
 *   · 按启动器报告的可用 dp 尺寸，决定显示哪些区块（标题 / 三环 / 模式 / 按钮）
 *   · 环用 match_parent + fitCenter，位图按可用高度动态渲染 → 横竖屏、任意格子都不会被裁
 */
public class NoiseWidget extends AppWidgetProvider {

    private static final String TAG = "NiceHckWidget";

    public static final String ACTION = "io.github.eage9.nicehckbridge.WIDGET";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_REFRESH = "refresh";

    /** 显示阈值（dp）：低于这个高度就不显示对应区块 */
    private static final int H_TITLE = 76;
    private static final int H_MODE = 96;
    private static final int H_ACTIONS = 132;
    private static final int W_ACTIONS = 200;

    @Override public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        EarbudsState.bind(context);
        for (int id : ids) mgr.updateAppWidget(id, build(context, mgr, id));
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr,
                                                    int id, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, mgr, id, newOptions);
        EarbudsState.bind(context);
        mgr.updateAppWidget(id, build(context, mgr, id));
    }

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION.equals(action)) {
            if (intent.getBooleanExtra(EXTRA_REFRESH, false)) CmdSender.refresh(context);
            else CmdSender.setNoise(context, intent.getIntExtra(EXTRA_MODE, Frames.NOISE_ANC));
            return;
        }
        super.onReceive(context, intent);
    }

    public static void refreshAll(Context context) {
        Context app = context.getApplicationContext();
        EarbudsState.bind(app);
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(new ComponentName(app, NoiseWidget.class));
        if (ids == null || ids.length == 0) return;
        for (int id : ids) {
            try {
                mgr.updateAppWidget(id, build(app, mgr, id));
            } catch (Throwable t) {
                Log.w(TAG, "refresh failed id=" + id, t);
            }
        }
    }

    /* ------------------------- 构建 ------------------------- */

    private static RemoteViews build(Context ctx, AppWidgetManager mgr, int id) {
        try {
            return buildInner(ctx, mgr, id);
        } catch (Throwable t) {
            Log.e(TAG, "build failed", t);
            RemoteViews fb = new RemoteViews(ctx.getPackageName(), R.layout.widget_nicehck);
            try {
                fb.setViewVisibility(R.id.w_rings, View.GONE);
                fb.setViewVisibility(R.id.w_actions, View.GONE);
                fb.setViewVisibility(R.id.w_mode, View.VISIBLE);
                fb.setTextViewText(R.id.w_mode, "—");
                fb.setTextColor(R.id.w_mode, Md3.onSurfaceVariant(ctx));
            } catch (Throwable ignored) {
            }
            return fb;
        }
    }

    private static RemoteViews buildInner(Context ctx, AppWidgetManager mgr, int id) {
        int w = 180, h = 60;
        try {
            Bundle o = mgr.getAppWidgetOptions(id);
            w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w);
            h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h);
        } catch (Throwable ignored) {
        }

        boolean conn = EarbudsState.isConnected();
        int l = EarbudsState.batteryL(), r = EarbudsState.batteryR(), c = EarbudsState.batteryC();
        int mode = EarbudsState.noiseMode();

        int onSurface = Md3.onSurface(ctx);
        int onVariant = Md3.onSurfaceVariant(ctx);
        int onPrimaryContainer = Md3.onPrimaryContainer(ctx);
        int onSecondaryContainer = Md3.onSecondaryContainer(ctx);

        boolean showTitle = h >= H_TITLE;
        boolean showMode = h >= H_MODE;
        boolean showActions = h >= H_ACTIONS && w >= W_ACTIONS;

        int reserved = 20 + (showTitle ? 18 : 0) + (showMode ? 16 : 0) + (showActions ? 50 : 0);
        int cellW = Math.max(1, (w - 20) / 3);
        int ring = Math.max(18, Math.min(48, Math.min(h - reserved, cellW)));

        Log.i(TAG, "build id=" + id + " size=" + w + "x" + h
                + " title=" + showTitle + " mode=" + showMode + " actions=" + showActions
                + " ring=" + ring);

        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_nicehck);

        // 标题
        v.setViewVisibility(R.id.w_title, showTitle ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.w_title, ctx.getString(R.string.widget_title));
        v.setTextColor(R.id.w_title, onVariant);

        // 三个环
        v.setImageViewBitmap(R.id.w_ring_l, ringBitmap(ctx, l, onSurface, onVariant, ring));
        v.setImageViewBitmap(R.id.w_ring_c, ringBitmap(ctx, c, onSurface, onVariant, ring));
        v.setImageViewBitmap(R.id.w_ring_r, ringBitmap(ctx, r, onSurface, onVariant, ring));

        // 模式文字
        v.setViewVisibility(R.id.w_mode, showMode ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.w_mode, conn && mode >= 0 ? Frames.modeName(mode) : "—");
        v.setTextColor(R.id.w_mode, conn ? onPrimaryContainer : onVariant);

        // 按钮区
        if (showActions) {
            v.setViewVisibility(R.id.w_actions, View.VISIBLE);
            styleButton(ctx, v, R.id.w_btn_off, "关闭", mode == Frames.NOISE_OFF,
                    Frames.NOISE_OFF, onPrimaryContainer, onSecondaryContainer);
            styleButton(ctx, v, R.id.w_btn_trans, "通透", mode == Frames.NOISE_TRANSPARENCY,
                    Frames.NOISE_TRANSPARENCY, onPrimaryContainer, onSecondaryContainer);
            styleButton(ctx, v, R.id.w_btn_anc, "降噪", mode == Frames.NOISE_ANC,
                    Frames.NOISE_ANC, onPrimaryContainer, onSecondaryContainer);
            v.setTextColor(R.id.w_btn_refresh, onSecondaryContainer);
            v.setOnClickPendingIntent(R.id.w_btn_refresh, cmd(ctx, 0, true, 4));
        } else {
            v.setViewVisibility(R.id.w_actions, View.GONE);
        }
        return v;
    }

    private static void styleButton(Context ctx, RemoteViews v, int viewId, String label, boolean selected,
                                    int mode, int onPrimaryContainer, int onSecondaryContainer) {
        v.setTextViewText(viewId, label);
        v.setTextColor(viewId, selected ? onPrimaryContainer : onSecondaryContainer);
        v.setInt(viewId, "setBackgroundResource",
                selected ? R.drawable.widget_pill_on : R.drawable.widget_pill);
        v.setOnClickPendingIntent(viewId, cmd(ctx, mode, false, mode & 0xFF));
    }

    /** 环形电量位图：轨道 = outlineVariant，进度 = Monet primary（≤20% 转 error） */
    private static Bitmap ringBitmap(Context ctx, int pct, int textColor, int trackColor, int sizeDp) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int size = Math.max(1, Math.round(sizeDp * d));
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float stroke = Math.max(2f, size * 0.11f);
        float pad = stroke / 2f + 1f;
        RectF oval = new RectF(pad, pad, size - pad, size - pad);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(trackColor);
        c.drawArc(oval, 0f, 360f, false, p);

        if (pct >= 0) {
            p.setColor(pct <= 20 ? Md3.error(ctx) : Md3.primary(ctx));
            c.drawArc(oval, -90f, 360f * Math.min(100, pct) / 100f, false, p);
        }

        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(textColor);
        tp.setTextSize(size * 0.32f);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setFakeBoldText(true);
        String t = pct >= 0 ? String.valueOf(pct) : "--";
        float ty = size / 2f - (tp.descent() + tp.ascent()) / 2f;
        c.drawText(t, size / 2f, ty, tp);
        return bmp;
    }

    private static PendingIntent cmd(Context ctx, int mode, boolean refresh, int code) {
        Intent i = new Intent(ctx, NoiseWidget.class);
        i.setAction(ACTION);
        i.putExtra(EXTRA_MODE, mode);
        i.putExtra(EXTRA_REFRESH, refresh);
        return PendingIntent.getBroadcast(ctx, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
