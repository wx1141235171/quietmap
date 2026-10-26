package com.quietmap.app;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import com.quietmap.app.dexkit.DexKitLocator;
import com.quietmap.app.hooks.Hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Single Java entry class. API 102 only supports hot reload for modules that declare
 * exactly one entry, so this must stay the only class listed in java_init.list.
 *
 * <p>Nothing may be initialised before {@link #onModuleLoaded} runs: the framework attaches
 * itself to this instance after construction, and every API call before that throws.
 */
public class QuietMapModule extends XposedModule {

    private static final String FAKE_MANUFACTURER = "Xiaomi";
    private static final String FAKE_BRAND        = "Xiaomi";
    private static final String FAKE_MODEL        = "2210132C";
    private static final String FAKE_DEVICE       = "nuwa";
    private static final String FAKE_PRODUCT      = "nuwa";

    static final String TAG = "QuietMap";
    static final String TARGET_PACKAGE = "com.autonavi.minimap";

    /** 每个进程只安装一次；onPackageReady 对进程内每个已加载包都会回调。 */
    private static final AtomicBoolean sInstalled = new AtomicBoolean();

    /**
     * 进程名只在 onModuleLoaded 的 ModuleLoadedParam 上提供；
     * PackageReadyParam 没有 getProcessName()，所以在这里捕获供 onPackageReady 使用。
     */
    private static volatile String sProcessName = "";

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        final String pkg = param.getPackageName();


        // 1) 先改静态字段：早于应用任何代码读取
        spoofBuildFields();

        // 2) 再接管系统属性：有些检测绕过 Build 直接读 ro.product.*
        hookSystemProperties();

        log(Log.INFO, TAG, "spoof applied: " + pkg);
        dumpBuild();
    }

    // ================= Build 静态字段 =================

    private void spoofBuildFields() {
        setStaticField("MANUFACTURER", FAKE_MANUFACTURER);
        setStaticField("BRAND",        FAKE_BRAND);
        setStaticField("MODEL",        FAKE_MODEL);
        setStaticField("DEVICE",       FAKE_DEVICE);
        setStaticField("PRODUCT",      FAKE_PRODUCT);
    }

    private void setStaticField(String name, String value) {
        try {
            Field f = Build.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, value);              // static 字段传 null
            log(Log.DEBUG, TAG, "Build." + name + " = " + value);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Build." + name + " failed: " + t);
        }
    }

    private void dumpBuild() {
        log(Log.INFO, TAG, "now => MANUFACTURER=" + Build.MANUFACTURER
                + " BRAND=" + Build.BRAND
                + " MODEL=" + Build.MODEL
                + " DEVICE=" + Build.DEVICE
                + " PRODUCT=" + Build.PRODUCT);
    }
    /** 不依赖应用 classloader：boot classpath 类用调用方 loader 就能拿到 */
    private static Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ignored) { }

        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            try {
                return Class.forName(name, false, ctx);
            } catch (ClassNotFoundException ignored) { }
        }
        return Class.forName(name, false, ClassLoader.getSystemClassLoader());
    }

    // ================= SystemProperties =================

    private void hookSystemProperties() {
        Class<?> sp;
        try {
            sp = findClass("android.os.SystemProperties");
        } catch (Throwable t) {
            Log.e(TAG, "SystemProperties not found: " + t);
            return;
        }

        try {
            Method m = sp.getDeclaredMethod("get", String.class);
            m.setAccessible(true);
            hook(m).intercept(chain -> {
                String fake = mapProp(asString(arg0(chain)));
                return fake != null ? fake : chain.proceed();
            });
            Log.d(TAG, "hooked get(String)");
        } catch (Throwable t) {
            Log.e(TAG, "hook get(String) failed: " + t);
        }

        try {
            Method m = sp.getDeclaredMethod("get", String.class, String.class);
            m.setAccessible(true);
            hook(m).intercept(chain -> {
                String fake = mapProp(asString(arg0(chain)));
                return fake != null ? fake : chain.proceed();
            });
            Log.d(TAG, "hooked get(String,String)");
        } catch (Throwable t) {
            Log.e(TAG, "hook get(String,String) failed: " + t);
        }
    }
    /** 兼容 getArgs() 返回 List 或 Object[] 两种实现 */
    private static Object arg0(XposedInterface.Chain chain) {
        try {
            Object args = chain.getArgs();
            if (args instanceof List) {
                List<?> l = (List<?>) args;
                return l.isEmpty() ? null : l.get(0);
            }
            if (args instanceof Object[]) {
                Object[] a = (Object[]) args;
                return a.length > 0 ? a[0] : null;
            }
        } catch (Throwable ignored) { }
        return null;
    }
    private static String asString(Object o) {
        return (o instanceof String) ? (String) o : null;
    }

    /** 命中的 key 返回伪装值，未命中返回 null（表示走原逻辑） */
    private static String mapProp(String key) {
        if (key == null) return null;
        switch (key) {
            case "ro.product.manufacturer":
            case "ro.product.system.manufacturer":
            case "ro.product.vendor.manufacturer":
            case "ro.product.odm.manufacturer":
            case "ro.product.product.manufacturer":
                return FAKE_MANUFACTURER;

            case "ro.product.brand":
            case "ro.product.system.brand":
            case "ro.product.vendor.brand":
            case "ro.product.odm.brand":
            case "ro.product.product.brand":
                return FAKE_BRAND;

            case "ro.product.model":
            case "ro.product.system.model":
            case "ro.product.vendor.model":
            case "ro.product.odm.model":
            case "ro.product.product.model":
                return FAKE_MODEL;

            case "ro.product.device":
            case "ro.product.vendor.device":
                return FAKE_DEVICE;

            case "ro.product.name":
            case "ro.product.vendor.name":
                return FAKE_PRODUCT;

            default:
                return null;
        }
    }
    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        sProcessName = param.getProcessName();
        // DexKit 的 libdexkit.so 要从模块自己的 nativeLibraryDir 显式加载
        // （宿主进程的 java.library.path 里没有模块的 so 目录）。
        try {
            ApplicationInfo appInfo = getModuleApplicationInfo();
            if (appInfo != null && appInfo.nativeLibraryDir != null) {
                DexKitLocator.setModuleNativeDir(appInfo.nativeLibraryDir);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "setModuleNativeDir failed", t);
        }
        log(Log.INFO, TAG, "loaded process=" + param.getProcessName()
                + " api=" + getApiVersion()
                + " frameworkProps=0x" + Long.toHexString(getFrameworkProperties()));
    }

    /**
     * Fires once the app classloader exists, which is the earliest point a hook can resolve
     * 高德 classes. Note the framework injects us into every process of the scoped package and
     * fires this callback for <em>every</em> package loaded in that process, so filtering here
     * is mandatory, not defensive.
     */
    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        if (!sInstalled.compareAndSet(false, true)) {
            log(Log.INFO, TAG, "already installed, skip");
            return;
        }
        // 主进程名就是包名；:locationservice / :widgetProvider 等子进程不是。
        boolean mainProcess = TARGET_PACKAGE.equals(sProcessName);
        log(Log.INFO, TAG, "package ready: " + param.getPackageName()
                + " first=" + param.isFirstPackage()
                + " process=" + sProcessName
                + " mainProcess=" + mainProcess);
        // 框架只兜 RuntimeException；HookFailedError 继承 Error，必须自己兜住整段安装。
        try {
            Hooks.installAll(this, param.getClassLoader(), mainProcess);
        } catch (Throwable t) {
            // 安装失败时回滚标记：否则后续 onPackageReady 全部被 "already installed"
            // 挡掉，一次异常会让整个进程永久处于半挂载状态。
            sInstalled.set(false);
            log(Log.ERROR, TAG, "install aborted, will retry on next package ready", t);
        }
    }
}
