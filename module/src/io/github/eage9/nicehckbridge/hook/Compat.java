package io.github.eage9.nicehckbridge.hook;

import io.github.libxposed.api.XposedInterface.HookBuilder;

import java.lang.reflect.Method;

/**
 * API 101 与 102 的差异隔离：HookBuilder.setId(String) 是 102 才有的。
 * 这里用反射调用，保证同一份源码能分别对着 api101 / api102 编译。
 */
public final class Compat {

    /** 构建号：每次发版改这里，日志里能直接看到装的是哪一版 */

    private Compat() {}

    static HookBuilder id(HookBuilder builder, String id) {
        if (builder == null) return null;
        try {
            Method m = builder.getClass().getMethod("setId", String.class);
            return (HookBuilder) m.invoke(builder, id);
        } catch (Throwable ignored) {
        }
        try {
            for (Method m : builder.getClass().getMethods()) {
                if (m.getName().equals("setId") && m.getParameterCount() == 1) {
                    return (HookBuilder) m.invoke(builder, id);
                }
            }
        } catch (Throwable ignored) {
        }
        return builder;
    }
}
