package top.niunaijun.blackbox.core.system;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import top.niunaijun.blackbox.utils.compat.BundleCompat;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class SystemCallProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return initSystem();
    }

    private boolean initSystem() {
        // ⭐ v2.0 关键修复：:black 进程的 Provider 先于 Application.onCreate 执行，
        //   此时 BlackBoxCore 的 sContext / mClientConfiguration 都是 null
        //   → BlackBoxSystem.startup() 里 getHostPkg() 会 NPE → :black 崩溃。
        //   这里用 ContentProvider 自带的 Context 直接设置 sContext（不跑 HookManager，
        //   避免在 :black 进程重复 hook）。
        try {
            android.content.Context ctx = getContext();
            if (ctx != null) {
                top.niunaijun.blackbox.BlackBoxCore.get()
                        .setContextForServer(ctx,
                                top.niunaijun.blackbox.BlackBoxCore.getDefaultClientConfiguration(ctx));
            }
        } catch (Throwable t) {
            android.util.Log.e("SystemCallProvider", "BlackBoxCore 兜底初始化失败", t);
        }
        BlackBoxSystem.getSystem().startup();
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (method.equals("VM")) {
            Bundle bundle = new Bundle();
            if (extras != null) {
                String name = extras.getString("_VM_|_server_name_");
                BundleCompat.putBinder(bundle, "_VM_|_server_", ServiceManager.getService(name));
            }
            return bundle;
        }
        return super.call(method, arg, extras);
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
