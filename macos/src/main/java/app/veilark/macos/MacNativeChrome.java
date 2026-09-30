package app.veilark.macos;

import java.util.function.IntConsumer;

/** Narrow UI-only JNI boundary. No profiles, credentials or engine configuration cross it. */
public final class MacNativeChrome {
    private static volatile IntConsumer navigationHandler;
    private static volatile Runnable displayHandler;
    private static volatile Runnable updateNoticeHandler;
    public static void setUpdateNoticeHandler(Runnable handler) { updateNoticeHandler = handler; }
    public static void onUpdateNoticeOpened() {
        Runnable handler = updateNoticeHandler;
        if (handler != null) handler.run();
    }
    public static void onUpdateNoticePosted(int build) {
        try {
            java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userRoot().node("app/veilark/macos/update-notices");
            prefs.putInt("last", Math.max(build, prefs.getInt("last", 0)));
        } catch (RuntimeException ignored) { /* Notification persistence must not break the client. */ }
    }
    public static native boolean postUpdateNotice(String title, String body, int build);
    private MacNativeChrome() {}

    public static void setNavigationHandler(IntConsumer handler) { navigationHandler = handler; }
    public static void setDisplayHandler(Runnable handler) { displayHandler = handler; }
    public static void onDisplayPreferencesChanged() {
        Runnable handler = displayHandler;
        if (handler != null) handler.run();
    }
    public static void onNavigate(int index) {
        IntConsumer handler = navigationHandler;
        if (handler != null && index >= 0 && index < 5) handler.accept(index);
    }

    public static native long install(String windowTitle, String[] labels, byte[] monochromeLogo);
    public static native void update(long handle, int selectedIndex, String status);
    public static native void remove(long handle);
    /** 0 = opaque, 1 = legacy vibrancy, 2 = system Liquid Glass; -1 = detached. */
    public static native int materialMode(long handle);
}
