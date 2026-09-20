package top.niunaijun.blackbox.app.configuration;

import android.app.Application;
import android.content.Context;

import java.io.File;
import java.io.FileWriter;

import top.niunaijun.blackbox.core.VMCore;

/**
 * Created by Milk on 5/5/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class AppLifecycleCallback {
    String TAG = "AppLifecycleCallback";
    public static AppLifecycleCallback EMPTY = new AppLifecycleCallback() {

    };

    //此方法在构造application之前被调用，可以做一些反检测什么的
    public void beforeCreateApplication(String packageName, String processName, Context context, Object loadedApk) {

//        重定向cmdline的内容，防止加固靠判断cmdline内的数据来检测，实际测试下来没用
//        File fakeCmdlineFile = new File(context.getFilesDir(),"cmdline");
//        try{
//            if (!fakeCmdlineFile.exists()){
//                fakeCmdlineFile.createNewFile();
//            }
//            try(FileWriter fakeFileWriter = new FileWriter(fakeCmdlineFile)){
//                fakeFileWriter.write(context.getPackageName());
//            }
//        } catch (Exception ignored) {
//
//        }
//        VMCore.hookBeforeSoLoad(fakeCmdlineFile.getAbsolutePath());
    }

    public static void hookPackageDataFile() {

    }

    public void beforeApplicationOnCreate(String packageName, String processName, Application application) {

    }

    public void afterApplicationOnCreate(String packageName, String processName, Application application) {

    }
}
