package io.github.eage9.nicehckbridge;

import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import io.github.eage9.nicehckbridge.proto.Frames;

/** 快捷设置磁贴：点按循环 OriGin 的 关 → 降噪 → 深度降噪，副标题显示电量。 */
public class NoiseControlTile extends TileService implements EarbudsState.Listener {

    private static final int[] CYCLE = {Frames.NOISE_OFF, Frames.NOISE_ANC, Frames.NOISE_ANC_DEEP};

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onStartListening() {
        super.onStartListening();
        EarbudsState.bind(this);
        EarbudsState.addListener(this);
        CmdSender.refresh(this);
        render();
    }

    @Override public void onStopListening() {
        EarbudsState.removeListener(this);
        super.onStopListening();
    }

    @Override public void onClick() {
        super.onClick();
        int cur = EarbudsState.noiseMode();
        int next = CYCLE[0];
        for (int i = 0; i < CYCLE.length; i++) {
            if (CYCLE[i] == cur) { next = CYCLE[(i + 1) % CYCLE.length]; break; }
        }
        CmdSender.setNoise(this, next);
        render();
    }

    @Override public void onStateChanged() { main.post(this::render); }

    private void render() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean conn = EarbudsState.isConnected();
        int mode = EarbudsState.noiseMode();
        String label;
        int state;
        if (!conn) {
            label = "原道 未连接";
            state = Tile.STATE_UNAVAILABLE;
        } else {
            label = mode < 0 ? "原道 读取中" : "原道 " + Frames.modeName(mode);
            state = (mode == Frames.NOISE_ANC || mode == Frames.NOISE_ANC_DEEP) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        }
        t.setLabel(label);
        int b = EarbudsState.battery();
        if (b >= 0) t.setSubtitle(b + "%");
        t.setState(state);
        t.updateTile();
    }
}
