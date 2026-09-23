package top.niunaijun.blackbox.utils.compat;

import android.util.Log;

import java.lang.reflect.Field;

/**
 * PackageParserFieldCompat —— Android 13+（API 33/34/36）兼容层。
 *
 * 背景：
 *   AOSP 从 Android 13 起逐步弃用 `android.content.pm.PackageParser$Package`，
 *   到 Android 14/16 时该内部类的许多字段（`baseCodePath` / `codePath` /
 *   `splitCodePaths` 等）已**不再存在**。
 *
 *   而 BlackBox（newBlackDex 移植）的 BPackage/BPackageManagerCompat/Settings
 *   在**编译期直接引用** `aPackage.baseCodePath` —— 在 A16 上加载真机的
 *   `PackageParser$Package` 时，JVM 解析该 FieldRef 会直接抛：
 *     NoSuchFieldError: No field baseCodePath of type Ljava/lang/String;
 *         in class Landroid/content/pm/PackageParser$Package;
 *   导致整个 installLocked / scanPackage 崩溃 → 脱壳流程中断 → 未产出 DEX。
 *
 * 解决方案：
 *   统一改走**运行时安全反射**读取。字段不存在时返回 null（而不是抛错），
 *   上层再回退到真机 PackageManager 解析出的 sourceDir。
 */
public final class PackageParserFieldCompat {

    private static final String TAG = "PackageParserFieldCompat";

    /** 缓存：字段名 → Field（null 表示已确认不存在，避免重复反射） */
    private static final java.util.Map<String, Field> sFieldCache = new java.util.HashMap<>();

    private PackageParserFieldCompat() {
    }

    /**
     * 安全读取 PackageParser$Package 的字符串字段。
     *
     * @param pkg   目标对象（PackageParser.Package 实例）
     * @param name  字段名（baseCodePath / codePath / ...）
     * @return 字段值；字段不存在或读取失败返回 null
     */
    public static String getString(Object pkg, String name) {
        if (pkg == null || name == null) {
            return null;
        }
        Field field = resolveField(pkg.getClass(), name);
        if (field == null) {
            return null;
        }
        try {
            Object v = field.get(pkg);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            Log.w(TAG, "getString(" + name + ") 失败: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return null;
        }
    }

    /** 该字段在当前 ROM 上是否存在（用于诊断日志） */
    public static boolean hasField(Object pkg, String name) {
        return pkg != null && resolveField(pkg.getClass(), name) != null;
    }

    /**
     * 沿着类继承链查找声明字段（PackageParser$Package 字段全在类自身，
     * 但为稳妥仍走继承链）。
     */
    private static Field resolveField(Class<?> clazz, String name) {
        String key = clazz.getName() + "#" + name;
        synchronized (sFieldCache) {
            if (sFieldCache.containsKey(key)) {
                return sFieldCache.get(key);
            }
        }
        Field found = null;
        Class<?> c = clazz;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    found = f;
                    break;
                }
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            } catch (Throwable t) {
                Log.w(TAG, "resolveField(" + c.getName() + "#" + name + ") 异常: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            c = c.getSuperclass();
        }
        synchronized (sFieldCache) {
            sFieldCache.put(key, found);
        }
        return found;
    }
}
