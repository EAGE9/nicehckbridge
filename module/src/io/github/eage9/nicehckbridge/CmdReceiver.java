package io.github.eage9.nicehckbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 接收 Settings 侧（LSPosed hook）下发的控制命令，转成命令通道写入。 */
public class CmdReceiver extends BroadcastReceiver {
    public static final String ACTION = "io.github.eage9.nicehckbridge.CMD";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        String op = intent.getStringExtra("op");
        if ("noise".equals(op)) CmdSender.setNoise(context, intent.getIntExtra("mode", 0));
        else CmdSender.refresh(context);
    }
}
