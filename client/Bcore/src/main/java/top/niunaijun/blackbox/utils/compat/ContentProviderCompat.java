package top.niunaijun.blackbox.utils.compat;

import android.content.ContentProviderClient;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;

public class ContentProviderCompat {

    public static Bundle call(Context context, Uri uri, String method, String arg, Bundle extras, int retryCount) throws IllegalAccessException {
        if (VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return context.getContentResolver().call(uri, method, arg, extras);
        }
        ContentProviderClient client = acquireContentProviderClientRetry(context, uri, retryCount);
        try {
            if (client == null) {
                throw new IllegalAccessException();
            }
            return client.call(method, arg, extras);
        } catch (RemoteException e) {
            throw new IllegalAccessException(e.getMessage());
        } finally {
            releaseQuietly(client);
        }
    }


    private static ContentProviderClient acquireContentProviderClient(Context context, Uri uri) {
        // ⭐ v2.0 Android 16 修复：
        //   A14+ 起 acquireUnstableContentProviderClient 被严格限制（A16 更严），
        //   返回 null 且**不会拉起 Provider 进程** → :black 进程无法启动。
        //   改为：优先用「稳定客户端」acquireContentProviderClient（会正常拉起 Provider 进程）。
        if (VERSION.SDK_INT >= 34) {
            try {
                ContentProviderClient c = context.getContentResolver()
                        .acquireContentProviderClient(uri);
                if (c != null) return c;
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        // 回退：老逻辑（unstable）
        try {
            if (VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                return context.getContentResolver().acquireUnstableContentProviderClient(uri);
            }
            return context.getContentResolver().acquireContentProviderClient(uri);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    public static ContentProviderClient acquireContentProviderClientRetry(Context context, Uri uri, int retryCount) {
        ContentProviderClient client = acquireContentProviderClient(context, uri);
        if (client == null) {
            int retry = 0;
            while (retry < retryCount && client == null) {
                SystemClock.sleep(100);
                retry++;
                client = acquireContentProviderClient(context, uri);
            }
        }
        return client;
    }

    public static ContentProviderClient acquireContentProviderClientRetry(Context context, String name, int retryCount) {
        ContentProviderClient client = acquireContentProviderClient(context, name);
        if (client == null) {
            int retry = 0;
            while (retry < retryCount && client == null) {
                SystemClock.sleep(100);
                retry++;
                client = acquireContentProviderClient(context, name);
            }
        }
        return client;
    }

    private static ContentProviderClient acquireContentProviderClient(Context context, String name) {
        // ⭐ v2.0 Android 16 修复：同 Uri 版本
        if (VERSION.SDK_INT >= 34) {
            try {
                ContentProviderClient c = context.getContentResolver()
                        .acquireContentProviderClient(name);
                if (c != null) return c;
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        try {
            if (VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                return context.getContentResolver().acquireUnstableContentProviderClient(name);
            }
            return context.getContentResolver().acquireContentProviderClient(name);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    private static void releaseQuietly(ContentProviderClient client) {
        if (client != null) {
            try {
                if (VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    client.close();
                } else {
                    client.release();
                }
            } catch (Exception ignored) {
            }
        }
    }
}