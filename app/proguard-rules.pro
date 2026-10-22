# ============================================================
# CoolStream 的 R8 混淆规则
#
# ⚠️ 这个文件是「发布包能不能跑」的命门。
#    开启 isMinifyEnabled = true 之后，R8 会把「看起来没人调用」的类删掉，
#    而本项目有四处代码恰恰是【靠反射 / 靠类名字符串】被调用的 ——
#    编译器看不见那条调用边，于是会当成死代码删掉，结果是：
#    编译通过、安装成功、一用到就崩。
#
#    下面每一组 keep 都对应一个真实功能点，删任何一组都会炸对应功能。
#    改这个文件之后，务必按 HANDOVER.md §8.2 的清单在真机上跑一遍。
# ============================================================


# ============================================================
# 1. Rhino —— PTC 沙箱的 JavaScript 引擎
#
# 为什么必须 keep：
#   Rhino 内部大量使用 Class.forName / 反射来创建解释器、作用域、原型链，
#   我们的代码里也直接继承了 ContextFactory、实现了 ClassShutter。
#   R8 一旦重命名或删除这些类，PTC 模式会直接抛
#   NoClassDefFoundError / ClassNotFoundException。
#
# 症状特征：切到 PTC 预设、让模型写一段 run_code，立刻报错或闪退；
#          标准模式和极简模式看起来完全正常（因为它们不走 Rhino）。
# ============================================================
-keep class org.mozilla.javascript.** { *; }
-keep interface org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# Rhino 用到的可选依赖：探测不到时会走降级分支，不是真缺功能
-dontwarn org.mozilla.classfile.**
-dontwarn java.beans.**

# ⚠️ 注意：PtcContextFactory 是 MainActivity.kt 里的 private object，
#   编译后叫 MainActivityKt$PtcContextFactory，而且只被本项目代码【直接调用】，
#   不靠反射查找 —— 所以不需要、也不应该为它写 keep（类名根本对不上）。
#   真正要保住的是它继承/实现的 org.mozilla.javascript 那一层，见上面的规则。


# ============================================================
# 2. Shizuku AIDL —— run_shell 的提权通道
#
# 为什么必须 keep：
#   IRemoteShell 是 AIDL 生成的接口，Stub / Proxy 依赖
#   writeInterfaceToken 里那个【写死的字符串类名】。
#   类被改名 → token 对不上 → 跨进程调用直接抛 SecurityException。
#
#   而且 RemoteShellService 是被 Shizuku 【在 shell 进程里】按类名实例化的，
#   本进程根本没有它的直接引用，R8 一定会认为它是死代码。
# ============================================================
-keep class com.example.mychat.IRemoteShell { *; }
-keep class com.example.mychat.IRemoteShell$* { *; }
-keep class com.example.mychat.RemoteShellService { *; }

# 所有 AIDL 生成的 Stub / Proxy 都遵循这个命名，一并兜住
-keep class **.Stub { *; }
-keep class **.Stub$* { *; }
-keep class **.Proxy { *; }

# AIDL 接口方法签名不能变（参数 / 返回值的类型也要能按原名找回来）
-keep interface com.example.mychat.IRemoteShell { *; }
-keepattributes Exceptions,InnerClasses,Signature


# ============================================================
# 3. Room —— 本地数据库
#
# 为什么必须 keep：
#   Room 在运行时用 Class.forName("<库名>_Impl") 找生成的实现类，
#   实体类则通过字段名反射读写。类名或字段名被改 → 启动即崩。
# ============================================================
-keep class com.example.mychat.AppDatabase_Impl { *; }
-keep class com.example.mychat.ChatDao_Impl { *; }
-keep class com.example.mychat.SessionEntity { *; }
-keep class com.example.mychat.ChatMessage { *; }
-keep class com.example.mychat.MessageType { *; }
-keep class com.example.mychat.MessageTypeConverter { *; }

# Room 官方推荐规则（覆盖所有 @Entity / @Dao / 生成的 Impl）
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**


# ============================================================
# 4. Shizuku —— 授权与 provider
#
# 为什么必须 keep：
#   ShizukuProvider 在 Manifest 里声明，由系统按类名反射实例化；
#   Shizuku 库内部也有反射调用。改名 → 授权流程永远走不通。
# ============================================================
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**


# ============================================================
# 5. Kotlin / 协程
#
# Kotlin 的 data class、lambda、协程状态机都依赖元数据；
# 元数据被裁掉后，copy() / equals() 会出现难以定位的怪问题。
# ============================================================
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**


# ============================================================
# 6. 第三方库的「编译期注解」缺失警告
#
# security-crypto 底层依赖 Tink，Tink 的源码里带了 errorprone 的注解
# （@CanIgnoreReturnValue / @CheckReturnValue / @Immutable / @RestrictedApi）。
# 这些注解只在【写代码时】给 IDE 和静态检查看的，不进 APK、运行时根本不需要。
#
# R8 扫描到「引用了但不存在的类」会报错，这里明确告诉它：这几个是预期缺失的。
# ⚠️ 这不是「掩盖真问题」—— 如果哪天真少了一个运行时类，
#    崩溃会以 NoClassDefFoundError 的形式在真机上暴露出来，而不是被这条规则吞掉。
# ============================================================
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi


# ============================================================
# 7. 崩溃堆栈可读性
#
# 混淆后堆栈里全是 a.b.c，没法定位。
# 保留行号 + 把源文件名统一改成 SourceFile，
# 这样堆栈能告诉你「MainActivity.kt 第 1234 行」，但不泄露真实文件名。
# ============================================================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# ============================================================
# 8. 明确「不要」加的规则（写给未来的自己）
#
# ❌ 不要写 -dontobfuscate：那等于关了混淆，isMinifyEnabled 白开。
# ❌ 不要写 -keep class com.example.mychat.** { *; }：
#      一行看似省事的规则会把整个 App 的混淆全废掉，
#      包体积不降反升（因为 R8 连无用代码都不能删）。
# ❌ 不要为了「怕崩」就把 minify 关回去：
#      正确做法是打完 release 包按 §8.2 跑一遍六条验证，
#      崩了就补一条针对性的 keep，而不是把整台机器拆了。
# ============================================================
