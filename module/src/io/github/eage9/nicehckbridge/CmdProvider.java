package io.github.eage9.nicehckbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * 命令通道（ContentProvider 版）：
 * 被 hook 的原道 App 进程用 ContentResolver 轮询本 provider，
 * 不依赖广播投递（某些 ROM 会拦跨 uid 的隐式/显式广播）。
 */
public class CmdProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.eage9.nicehckbridge.cmd";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/cmd");
    private static final String[] COLS = {"seq", "mode", "level", "refresh"};

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        SharedPreferences p = getContext().getSharedPreferences(CmdSender.PREFS, Context.MODE_PRIVATE);
        MatrixCursor c = new MatrixCursor(COLS, 1);
        c.addRow(new Object[]{
                p.getLong("seq", 0L),
                p.getInt("mode", -1),
                p.getInt("level", 0),
                p.getBoolean("refresh", false) ? 1 : 0});
        return c;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/nicehck-cmd"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
