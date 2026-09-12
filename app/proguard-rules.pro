# fcmself 的 R8 规则（release 开启 minify 时生效）

# LSPosed 是按 META-INF/xposed/java_init.list 里的**类名字符串**加载入口类的，
# 入口类一旦被改名或裁剪，模块会静默不加载（构建仍然成功），所以必须原样保留。
-keep class sumicya.fcmself.XposedMain { *; }

# libxposed API 是 compileOnly 依赖，运行期由 LSPosed 框架提供，R8 在编译期看不到这些类。
-dontwarn io.github.libxposed.api.**

# hook skip 日志会打印异常类名（如 Reflect.ClassNotFound）。R8 会把类名混淆成
# 「q1:」这样的形式，日志可读性归零——这里只保留类名，不保留成员。
-keepnames class sumicya.fcmself.hook.Reflect$ClassNotFound
