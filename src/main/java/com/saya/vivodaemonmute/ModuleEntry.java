package com.saya.vivodaemonmute;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 在 com.vivo.daemonService 主进程里拦截 10011 / 10012 通知。 */
public class ModuleEntry implements IXposedHookLoadPackage {

    private static final String TAG = "SayaVivoNotifyMute";
    private static final String TARGET_PACKAGE = "com.vivo.daemonService";
    private static final int ID_SPACE_CORRUPT = 10011;
    private static final int ID_SPACE_CORRUPT_ALT = 10012;
    private static final int CLEANUP_MAX_ATTEMPTS = 6;
    private static final long CLEANUP_FIRST_DELAY_MS = 3000L;
    private static final long CLEANUP_RETRY_DELAY_MS = 2000L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
                return;
            }
            if (lpparam.processName != null && !TARGET_PACKAGE.equals(lpparam.processName)) {
                return;
            }

            int hooked = hookNotificationManager();
            if (hooked == 0) {
                log("ERROR: no hook installed in " + lpparam.packageName
                        + " (NotificationManager notify methods not found)");
                return;
            }
            log("loaded in " + lpparam.packageName + ", hooked " + hooked + " method(s)");
            scheduleCleanup(0);
        } catch (Throwable t) {
            log("handleLoadPackage failed: " + t);
        }
    }

    private int hookNotificationManager() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Method method = (Method) param.method;
                int id = findNotificationId(method, param.args);
                if (id == ID_SPACE_CORRUPT || id == ID_SPACE_CORRUPT_ALT) {
                    param.setResult(null);
                    cancelQuietly(param.thisObject, findTag(method, param.args), id);
                    log("suppressed notify id=" + id + " via " + method.getName());
                }
            }
        };

        int count = 0;
        for (Method method : collectNotifyMethods()) {
            try {
                XposedBridge.hookMethod(method, hook);
                count++;
                log("hooked " + method.toGenericString());
            } catch (Throwable t) {
                log("skip " + method.toGenericString() + ": " + t);
            }
        }
        return count;
    }

    private List<Method> collectNotifyMethods() {
        List<Method> methods = new ArrayList<>();
        for (Method m : NotificationManager.class.getDeclaredMethods()) {
            String name = m.getName();
            if (!"notify".equals(name) && !"notifyAsPackage".equals(name)) {
                continue;
            }
            for (Class<?> type : m.getParameterTypes()) {
                if (Notification.class.equals(type)) {
                    methods.add(m);
                    break;
                }
            }
        }
        return methods;
    }

    private int findNotificationId(Method method, Object[] args) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (!Notification.class.equals(types[i])) {
                continue;
            }
            for (int j = i - 1; j >= 0; j--) {
                if (types[j] == int.class && args[j] instanceof Integer) {
                    return (Integer) args[j];
                }
            }
            break;
        }
        return Integer.MIN_VALUE;
    }

    private String findTag(Method method, Object[] args) {
        Class<?>[] types = method.getParameterTypes();
        List<String> strings = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == String.class && args[i] instanceof String) {
                strings.add((String) args[i]);
            }
        }
        if ("notifyAsPackage".equals(method.getName())) {
            return strings.size() >= 2 ? strings.get(1) : null;
        }
        return strings.isEmpty() ? null : strings.get(0);
    }

    private void cancelQuietly(Object manager, String tag, int id) {
        if (!(manager instanceof NotificationManager)) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) manager;
            if (tag == null) {
                nm.cancel(id);
            } else {
                nm.cancel(tag, id);
            }
        } catch (Throwable t) {
            log("cancel id=" + id + " tag=" + tag + " failed: " + t);
        }
    }

    private void scheduleCleanup(final int attempt) {
        try {
            long delay = attempt == 0 ? CLEANUP_FIRST_DELAY_MS : CLEANUP_RETRY_DELAY_MS;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Application app = AndroidAppHelper.currentApplication();
                        if (app == null) {
                            if (attempt + 1 < CLEANUP_MAX_ATTEMPTS) {
                                log("cleanup retry " + (attempt + 1) + ": application not ready");
                                scheduleCleanup(attempt + 1);
                            } else {
                                log("cleanup gave up: application not ready");
                            }
                            return;
                        }
                        NotificationManager nm = (NotificationManager)
                                app.getSystemService(Context.NOTIFICATION_SERVICE);
                        if (nm == null) {
                            log("cleanup skipped: no NotificationManager");
                            return;
                        }
                        int cleared = 0;
                        for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                            int id = sbn.getId();
                            if (id == ID_SPACE_CORRUPT || id == ID_SPACE_CORRUPT_ALT) {
                                nm.cancel(sbn.getTag(), id);
                                cleared++;
                            }
                        }
                        log("cleanup done, cleared=" + cleared + " for id 10011/10012");
                    } catch (Throwable t) {
                        log("cleanup failed: " + t);
                    }
                }
            }, delay);
        } catch (Throwable t) {
            log("schedule cleanup failed: " + t);
        }
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
