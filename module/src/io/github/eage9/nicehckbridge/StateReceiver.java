package io.github.eage9.nicehckbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 接收原道 App 进程（LSPosed 中间件）广播上来的耳机状态，并刷新桌面小组件。 */
public class StateReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !"io.github.eage9.nicehckbridge.STATE".equals(intent.getAction())) return;
        EarbudsState.apply(
                intent.getBooleanExtra("connected", false),
                intent.getIntExtra("battery", -1),
                intent.getIntExtra("mode", -1),
                intent.getIntExtra("level", 0),
                intent.getStringExtra("firmware"),
                intent.getStringExtra("addr"),
                intent.getIntExtra("bL", -1),
                intent.getIntExtra("bR", -1),
                intent.getIntExtra("bC", -1));
        try {
            NoiseWidget.refreshAll(context);
        } catch (Throwable ignored) {
        }
    }
}
