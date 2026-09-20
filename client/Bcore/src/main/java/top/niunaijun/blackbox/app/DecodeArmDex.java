package top.niunaijun.blackbox.app;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import top.niunaijun.blackbox.entity.dump.DumpResult;
import top.niunaijun.blackbox.utils.FileUtils;

public class DecodeArmDex {
    private static final String TAG = "arm脱壳调试日志";
    public static boolean dumpArmStub(Object loadedApk, DumpResult dumpResule, String packageName){
        Log.d(TAG, "dumpArmStub: "+dumpResule.dir);
        try{
            //这里使用原本的dump目录会继续执行cookiedump，cookiedump时会清除解密出来的dex文件。所以这里索性直接自己创建一个dump目录来写出dex
            //小bug不算bug（）
            File parentsFile = new File(dumpResule.dir.replace(packageName,packageName+"_decode"));
            if (!parentsFile.exists()){
                FileUtils.mkdirs(parentsFile);
            }
            //通过反射LoadedApk获取安装包文件路径
            Field mAppDirField = loadedApk.getClass().getDeclaredField("mAppDir");
            mAppDirField.setAccessible(true);
            String mAppDir = (String) mAppDirField.get(loadedApk);
            Log.d(TAG, "apk路径: "+mAppDir);
            //遍历apk获取assets目录下的dex文件
            try(ZipFile targetApk = new ZipFile(new File(mAppDir))){
                Enumeration<? extends ZipEntry> entries = targetApk.entries();
                while (entries.hasMoreElements()){
                    ZipEntry entry = entries.nextElement();
                    //正则匹配assets下的dex文件
                    Pattern pattern = Pattern.compile("assets/classes.*\\.dex");
                    Matcher matcher = pattern.matcher(entry.getName());
                    if (matcher.matches()){
                        Log.d(TAG, "获取到dex文件: "+entry.getName());
                        //开始解密文件
                        InputStream classStream = targetApk.getInputStream(entry);
                        byte[] readBytes = readBytes(classStream);
                        for (int i = 0; i < readBytes.length; i++) {
                            //解密逻辑，直接从armPro的代码里抄的
                            readBytes[i] = (byte) ((~readBytes[i]) & 255);
                        }

                        File dexfile = new File(parentsFile, entry.getName().split("/")[1]);
                        Log.d(TAG, "dumpFile: "+dexfile.getAbsolutePath());
                        if (!dexfile.exists()){
                            dexfile.createNewFile();
                        }
                        FileOutputStream dexoutput = new FileOutputStream(dexfile);
                        dexoutput.write(readBytes);
                        classStream.close();
                        dexoutput.close();
                    }
                }
                //替换DumpResult的dump文件目录，不替换的话最后输出的弹窗目录指向的路径不正确
                dumpResule.dir = parentsFile.getAbsolutePath();
                return true;
            }
        }catch (Exception e){
            e.printStackTrace();
        }
        return false;
    }

    //此段代码来自armPro源码
    public static byte[] readBytes(InputStream inputStream) throws Exception {
        byte[] bArr = new byte[10240];
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        while (true) {
            int read = inputStream.read(bArr);
            if (read != -1) {
                byteArrayOutputStream.write(bArr, 0, read);
            } else {
                byte[] byteArray = byteArrayOutputStream.toByteArray();
                byteArrayOutputStream.close();
                inputStream.close();
                return byteArray;
            }
        }
    }
}
