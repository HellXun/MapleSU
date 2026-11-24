# Protobuf
-shrinkunusedprotofields

# Commons-compress
-dontwarn com.github.luben.zstd.**

# Stable JNI surface, including compatibility entries used by older SDK callers.
-keep class dev.maplesu.manager.runtime.ultra.bridge.NativeBridge {
    public static native <methods>;
}

# app_process invokes this entry independently of Android component discovery.
-keep class dev.maplesu.manager.runtime.NativeSuBootstrap { *; }
-keep class dev.maplesu.manager.installer.BlockPartitionControl { *; }

# app_process invokes these entry points by name (root daemon / host-library patcher).
# R8 would otherwise rename or strip them in the minified release build.
-keep class dev.maplesu.manager.liteweb.daemon.LiteWebDaemonMain { *; }
-keep class dev.maplesu.manager.litehidden.HostSoPatchMain { *; }
# Hidden-mode daemon reads these through reflection-free but name-stable APIs; keep the
# classes it instantiates by name across the process boundary.
-keep class dev.maplesu.manager.liteweb.daemon.** { *; }
-keep class dev.maplesu.manager.workmode.service.ModeSwitchRootMain { *; }
-keep class dev.maplesu.manager.runtime.ultra.UltraIdentityBroker {
    public static void main(java.lang.String[]);
}

# One-shot authorization tools and the lease executor are launched by stable app_process names.
-keep class dev.maplesu.manager.runtime.authorization.AuthorizationTool {
    public static void main(java.lang.String[]);
}
-keep class dev.maplesu.manager.runtime.authorization.SuperuserTool {
    public static void main(java.lang.String[]);
}
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.server.** { *; }
