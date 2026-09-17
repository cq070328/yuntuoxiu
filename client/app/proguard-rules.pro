# 云脱修客户端：保持 Shizuku / LSPatch 相关类不被混淆
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class org.lsposed.lspatch.** { *; }
# Gson 反射模型
-keep class com.yuntuoxiu.app.data.** { *; }