package android.util;

/**
 * JVM 单测用的 android.util.Log 影子类。
 *
 * mockable android.jar 里 Log.* 方法全部抛 "not mocked"，被测代码
 * （ChatCompletionsAPI 等 provider）内部打日志会让流程提前失败。
 * 测试 classpath 中本类排在 android.jar 之前，直接把日志调用吞掉，
 * 保持与 Robolectric 相同的 shadow 语义：断言业务行为，不断言日志
 * 副作用。与 app/src/test/java/android/util/Log.java 同步维护。
 */
public final class Log {
    private Log() {
    }

    public static int v(String tag, String msg) {
        return 0;
    }

    public static int v(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
    }

    public static int d(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int i(String tag, String msg) {
        return 0;
    }

    public static int i(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int w(String tag, String msg) {
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int w(String tag, Throwable tr) {
        return 0;
    }

    public static int e(String tag, String msg) {
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int wtf(String tag, String msg) {
        return 0;
    }

    public static int wtf(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int wtf(String tag, Throwable tr) {
        return 0;
    }

    public static boolean isLoggable(String tag, int level) {
        return false;
    }

    public static String getStackTraceString(Throwable tr) {
        return tr == null ? "" : tr.toString();
    }

    public static int println(int priority, String tag, String msg) {
        return 0;
    }
}
