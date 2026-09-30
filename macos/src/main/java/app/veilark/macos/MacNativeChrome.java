package app.veilark.macos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** Narrow UI-only JNI boundary. No profiles, credentials or engine configuration cross it. */
public final class MacNativeChrome {
    /** Toolbar action: start, cancel or stop the connection (same semantics as the tray item). */
    public static final int TOOLBAR_TOGGLE_CONNECTION = 0;
    /** Display preference bits reported by {@link #observeDisplayPreferences()}. */
    public static final int REDUCE_MOTION = 1;
    public static final int REDUCE_TRANSPARENCY = 2;
    public static final int INCREASE_CONTRAST = 4;

    private static volatile IntConsumer navigationHandler;
    private static volatile IntConsumer displayHandler;
    private static volatile IntConsumer toolbarHandler;
    private static volatile Runnable updateNoticeHandler;
    private static volatile int lastDisplayMask = -1;
    private static final AtomicInteger nextConfirmation = new AtomicInteger(1);
    private static final Map<Integer, IntConsumer> confirmations = new ConcurrentHashMap<>();

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
    /** Receives the live display-preference mask (see the bit constants). */
    public static void setDisplayHandler(IntConsumer handler) { displayHandler = handler; }
    public static void setToolbarHandler(IntConsumer handler) { toolbarHandler = handler; }

    /** Last mask pushed by AppKit, or -1 before the observer has reported anything. */
    public static int lastDisplayMask() { return lastDisplayMask; }

    public static void onDisplayPreferencesChanged(int mask) {
        if (mask < 0 || mask > 7) return;
        lastDisplayMask = mask;
        IntConsumer handler = displayHandler;
        if (handler != null) handler.accept(mask);
    }
    public static void onNavigate(int index) {
        IntConsumer handler = navigationHandler;
        if (handler != null && index >= 0 && index < 5) handler.accept(index);
    }
    public static void onToolbarAction(int action) {
        IntConsumer handler = toolbarHandler;
        if (handler != null && action == TOOLBAR_TOGGLE_CONNECTION) handler.accept(action);
    }

    /** Registers a one-shot answer callback; the returned id is passed to {@link #confirm}. */
    public static int registerConfirmation(IntConsumer answer) {
        int id = nextConfirmation.getAndUpdate(value -> value == Integer.MAX_VALUE ? 1 : value + 1);
        confirmations.put(id, answer);
        return id;
    }
    public static void cancelConfirmation(int requestId) { confirmations.remove(requestId); }
    /** 1 = confirmed, 0 = cancelled, -1 = sheet could not be shown (caller falls back). */
    public static void onConfirmResult(int requestId, int result) {
        if (result < -1 || result > 1) return;
        IntConsumer answer = confirmations.remove(requestId);
        if (answer != null) answer.accept(result);
    }

    public static native long install(long windowHandle, String windowTitle, int[] awtBounds,
                                      boolean unifiedTitlebar, String[] labels, byte[] monochromeLogo);
    public static native void update(long handle, int selectedIndex, String status,
                                     String connectionAction, boolean connectionActionEnabled);
    public static native void remove(long handle);
    /** 0 = opaque, 1 = legacy vibrancy, 2 = system Liquid Glass; -1 = detached. */
    public static native int materialMode(long handle);
    /** {material, toolbar, matchedBy, topInset, width, height} or null when detached. */
    public static native int[] nativeState(long handle);
    /** Current display-preference mask, or -1 when AppKit could not be queried. */
    public static native int observeDisplayPreferences();
    public static native boolean confirm(long handle, int requestId, String title, String message,
                                         String confirmTitle, String cancelTitle, boolean destructive);
}
