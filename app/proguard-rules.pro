# Dhizuku / Shizuku 的 entry 类只在 Manifest 里被引用，R8 会把它们当"无用代码"删掉 —— 真机闪退就是这个原因。
-keep class com.rosan.dhizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# Dhizuku 内部用反射访问 hidden API（ActivityThread 等），R8 只看得到引用看不到类，需要显式忽略。
-dontwarn com.rosan.dhizuku.**
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
-dontwarn android.app.ActivityThread
