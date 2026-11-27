package io.github.eage9.nicehckbridge.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import io.github.eage9.nicehckbridge.R;

/**
 * MD3 设计令牌。
 * · surface / onSurface / outline 等用 values(values-night) 里定义的 MD3 基线色（保证日/夜正确）
 * · primary / primaryContainer / secondaryContainer 等"强调色"优先取系统 Monet 调色板
 *   （android.R.color.system_accent1_* / system_accent2_*），取不到时用基线色兜底
 */
public final class Md3 {

    public static final float RADIUS_XS = 4, RADIUS_S = 8, RADIUS_M = 12, RADIUS_L = 16, RADIUS_XL = 28;

    private Md3() {}

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static boolean night(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private static Integer sys(Context c, int resId) {
        try { return c.getColor(resId); } catch (Throwable t) { return null; }
    }

    private static int pick(Context c, int monetRes, int fallbackRes) {
        Integer v = sys(c, monetRes);
        return v != null ? v : c.getColor(fallbackRes);
    }

    /* ---------- 强调色：Monet 优先 ---------- */

    public static int primary(Context c) {
        return pick(c, night(c) ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600,
                R.color.ui_primary_fallback);
    }

    public static int primaryContainer(Context c) {
        return pick(c, night(c) ? android.R.color.system_accent1_700 : android.R.color.system_accent1_100,
                R.color.ui_primary_container_fallback);
    }

    public static int onPrimaryContainer(Context c) {
        return pick(c, night(c) ? android.R.color.system_accent1_100 : android.R.color.system_accent1_900,
                R.color.ui_on_primary_container_fallback);
    }

    public static int secondaryContainer(Context c) {
        return pick(c, night(c) ? android.R.color.system_accent2_700 : android.R.color.system_accent2_100,
                R.color.ui_secondary_container_fallback);
    }

    public static int onSecondaryContainer(Context c) {
        return pick(c, night(c) ? android.R.color.system_accent2_100 : android.R.color.system_accent2_900,
                R.color.ui_on_secondary_container_fallback);
    }

    /* ---------- 表面/文字：基线色（日/夜资源） ---------- */

    public static int surface(Context c) { return c.getColor(R.color.ui_surface); }
    public static int surfaceContainer(Context c) { return c.getColor(R.color.ui_surface_container); }
    public static int surfaceContainerHigh(Context c) { return c.getColor(R.color.ui_surface_container_high); }
    public static int onSurface(Context c) { return c.getColor(R.color.ui_on_surface); }
    public static int onSurfaceVariant(Context c) { return c.getColor(R.color.ui_on_surface_variant); }
    public static int outline(Context c) { return c.getColor(R.color.ui_outline); }
    public static int outlineVariant(Context c) { return c.getColor(R.color.ui_outline_variant); }
    public static int error(Context c) { return c.getColor(R.color.ui_error); }

    public static int withAlpha(int color, float alpha) {
        return Color.argb((int) (255 * alpha), Color.red(color), Color.green(color), Color.blue(color));
    }

    /* ---------- 形状 ---------- */

    public static GradientDrawable rounded(Context c, int fill, float radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(c, radiusDp));
        d.setColor(fill);
        if (strokeColor != Color.TRANSPARENT) d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    /* ---------- 字阶 ---------- */

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    public static TextView titleLarge(Context c, CharSequence s, int color) { return text(c, s, 22, color, false); }
    public static TextView titleMedium(Context c, CharSequence s, int color) { return text(c, s, 16, color, true); }
    public static TextView bodyMedium(Context c, CharSequence s, int color) { return text(c, s, 14, color, false); }
    public static TextView bodySmall(Context c, CharSequence s, int color) { return text(c, s, 12, color, false); }
    public static TextView labelLarge(Context c, CharSequence s, int color) { return text(c, s, 14, color, true); }
    public static TextView labelMedium(Context c, CharSequence s, int color) { return text(c, s, 12, color, true); }

    public static void minTouch(View v, Context c) { v.setMinimumHeight(dp(c, 48)); }
}
