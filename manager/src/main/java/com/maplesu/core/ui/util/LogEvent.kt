package com.maplesu.core.ui.util

import dev.maplesu.manager.compat.ultraPreferences

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.system.Os
import com.topjohnwu.superuser.ShellUtils
import com.maplesu.core.Natives
import com.maplesu.core.ui.screen.home.getManagerVersion
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import dev.maplesu.manager.modules.UltraRootKeyStore
import dev.maplesu.manager.runtime.ultra.UltraBugreportDiagnostics
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.runtime.ultra.UltraSuChannel

fun getBugreportFile(context: Context, includePrivileged: Boolean = true): File =
    BugreportArchive.withStagingDirectory(context.cacheDir) { bugreportDir ->

    runCatching { ProcessExitDiagnostics.collect(context, bugreportDir) }
        .onFailure { android.util.Log.w("LogEvent", "App process exit diagnostics failed", it) }

    val processFile = File(bugreportDir, "process.txt")
    val dmesgFile = File(bugreportDir, "dmesg.txt")
    val logcatFile = File(bugreportDir, "logcat.txt")
    val tombstonesFile = File(bugreportDir, "tombstones.tar.gz")
    val dropboxFile = File(bugreportDir, "dropbox.tar.gz")
    val pstoreFile = File(bugreportDir, "pstore.tar.gz")
    // Xiaomi/Readmi devices have diag in /data/vendor/diag
    val diagFile = File(bugreportDir, "diag.tar.gz")
    val oplusFile = File(bugreportDir, "oplus.tar.gz")
    val bootlogFile = File(bugreportDir, "bootlog.tar.gz")
    val mountsFile = File(bugreportDir, "mounts.txt")
    val fileSystemsFile = File(bugreportDir, "filesystems.txt")
    val adbFileTree = File(bugreportDir, "adb_tree.txt")
    val adbFileDetails = File(bugreportDir, "adb_details.txt")
    val ksuFileSize = File(bugreportDir, "ksu_size.txt")
    val appListFile = File(bugreportDir, "packages.txt")
    val propFile = File(bugreportDir, "props.txt")
    val allowListFile = File(bugreportDir, "allowlist.bin")
    val procModules = File(bugreportDir, "proc_modules.txt")
    val bootConfig = File(bugreportDir, "boot_config.txt")
    val kernelConfig = File(bugreportDir, "defconfig.gz")
    val kallsyms = File(bugreportDir, "kallsyms.txt")
    BugreportArchive.prepareCollectionFiles(listOf(processFile, dmesgFile, logcatFile, tombstonesFile,
        dropboxFile, pstoreFile, diagFile, oplusFile, bootlogFile, mountsFile, fileSystemsFile,
        adbFileTree, adbFileDetails, ksuFileSize, appListFile, propFile, allowListFile, procModules,
        bootConfig, kernelConfig, kallsyms))

    // Capture before any Root/SDK call: channel failure must not erase boot evidence.
    runCatching {
        File(bugreportDir, "ultra-boot-app.txt").writeText(buildString {
            appendLine("currentBootId=${dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup.currentBootId()}")
            appendLine("elapsedRealtimeMs=${android.os.SystemClock.elapsedRealtime()}")
            appendLine("uptimeMs=${android.os.SystemClock.uptimeMillis()}")
            append(dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup.diagnostics(context))
            val recovery = context.ultraPreferences("ultra-identity-recovery", Context.MODE_PRIVATE)
            appendLine("identityRecovery=${recovery.getString("result", "NOT_OBSERVED")}")
            appendLine("identityRecoveryBoot=${recovery.getString("boot", null)}")
            appendLine("identityRecoveryAtMs=${recovery.getLong("at", 0L)}")
            appendLine("backupPath=${dev.maplesu.manager.runtime.ultra.UltraRecoveryKeyStore.PATH}")
            appendLine("backupResult=${recovery.getString("backup", "NOT_OBSERVED")}")
            appendLine("backupAtMs=${recovery.getLong("backupAt", 0L)}")
        })
    }.onFailure { android.util.Log.w("LogEvent", "App boot diagnostics failed", it) }

    // root shell 起不来（没 root / shell 初始化失败）时不能崩：拿不到就退化成 null，后面整段跳过。
    val ultraBackend = includePrivileged && UltraEnvironmentProbe.usesUltraBackend(context)
    val shell = if (includePrivileged && !ultraBackend) runCatching { getRootShell(true) }.getOrNull()
        ?.takeIf { it.isRoot && it.isAlive } else null
    File(bugreportDir, "collection.txt").writeText("Root shell available: ${shell != null}\nPro SDK collection enabled: $ultraBackend\n")

    // ⚠️ 这些 exec()/fastCmd 都会抛异常（设备上没有 toybox/busybox、路径不存在、shell 被杀…）。
    //    整个函数是在协程（SendLogDialog 的 scope.launch）里跑的，异常不捕获 = 直接闪退；
    //    所以整段兜底：能收多少证据就收多少，缺几份文件不影响导出。
    runCatching {
        shell?.let { sh ->
            // busybox ps has very few features for embed devices
            sh.newJob().add("toybox ps -T -A -w -o PID,TID,UID,COMM,CMDLINE,CMD,LABEL,STAT,WCHAN > ${processFile.absolutePath}").exec()
            sh.newJob().add("dmesg -r > ${dmesgFile.absolutePath}").exec()
            sh.newJob().add("logcat -b all -v uid -d > ${logcatFile.absolutePath}").exec()
            sh.newJob().add("tar -czf ${tombstonesFile.absolutePath} -C /data/tombstones .").exec()
            sh.newJob().add("tar -czf ${dropboxFile.absolutePath} -C /data/system/dropbox .").exec()
            sh.newJob().add("tar -czf ${pstoreFile.absolutePath} -C /sys/fs/pstore .").exec()
            sh.newJob().add("tar -czf ${diagFile.absolutePath} -C /data/vendor/diag . --exclude=./minidump.gz").exec()
            sh.newJob().add("tar -czf ${oplusFile.absolutePath} -C /mnt/oplus/op2/media/log/boot_log/ .").exec()
            sh.newJob().add("tar -czf ${bootlogFile.absolutePath} -C /data/adb/sevenk/log .").exec()

            sh.newJob().add("cat /proc/1/mountinfo > ${mountsFile.absolutePath}").exec()
            sh.newJob().add("cat /proc/filesystems > ${fileSystemsFile.absolutePath}").exec()
            sh.newJob().add("busybox tree /data/adb > ${adbFileTree.absolutePath}").exec()
            sh.newJob().add("ls -alRZ /data/adb > ${adbFileDetails.absolutePath}").exec()
            sh.newJob().add("du -sh /data/adb/sevenk/* > ${ksuFileSize.absolutePath}").exec()
            sh.newJob().add("cat /data/system/packages.list > ${appListFile.absolutePath}").exec()
            sh.newJob().add("getprop > ${propFile.absolutePath}").exec()
            sh.newJob().add("cat /data/adb/sevenk/.allowlist > ${allowListFile.absolutePath}").exec()
            sh.newJob().add("cat /proc/modules > ${procModules.absolutePath}").exec()
            sh.newJob().add("cat /proc/bootconfig > ${bootConfig.absolutePath}").exec()
            sh.newJob().add("cat /proc/config.gz > ${kernelConfig.absolutePath}").exec()
            sh.newJob()
                .add($$"ORIG=$(cat /proc/sys/kernel/kptr_restrict); echo 1 > /proc/sys/kernel/kptr_restrict; cat /proc/kallsyms > $${kallsyms.absolutePath}; echo $ORIG > /proc/sys/kernel/kptr_restrict")
                .exec()
        }
    }.onFailure { android.util.Log.w("LogEvent", "收集部分日志失败", it) }

    if (propFile.length() == 0L) {
        runCatching {
            val process = ProcessBuilder("/system/bin/getprop")
                .redirectErrorStream(true).redirectOutput(propFile).start()
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            } finally {
                process.destroy()
            }
        }.onFailure { android.util.Log.w("LogEvent", "App boot properties failed", it) }
    }

    runCatching {
        File(bugreportDir, "ultra-upgrade.txt").writeText(
            dev.maplesu.manager.runtime.ultra.UltraEnvironmentUpgrade.diagnostics(context))
    }.onFailure { android.util.Log.w("LogEvent", "Upgrade diagnostics failed", it) }

    runCatching {
        UltraBugreportDiagnostics.collect(bugreportDir, ultraBackend) {
            val client = UltraRuntimeClient { UltraRootKeyStore.read(context) }
            object : UltraBugreportDiagnostics.Port {
                override fun sdkBuildIdentity() = client.sdkBuildIdentity()
                override fun environment() = client.environmentState()
                override fun authorizations() = client.suAuthSnapshot()
                override fun rootCommand(command: String) = client.runRootCommand(command)
                override fun installedHelperPath() = dev.maplesu.manager.runtime.ultra.UltraInstalledIdentityProbe
                    .installedHelperPath(UltraRootKeyStore.read(context))
                override fun selfSu(): Pair<Int?, String?> {
                    val channel = UltraSuChannel()
                    return channel.rootUid() to channel.lastFailure
                }
            }
        }
    }.onFailure { android.util.Log.w("LogEvent", "Ultra authorization diagnostics failed", it) }

    if (!logcatFile.isFile || logcatFile.length() == 0L) {
        runCatching {
            val process = ProcessBuilder("/system/bin/logcat", "-d", "-t", "2000", "-v", "threadtime")
                .redirectErrorStream(true).redirectOutput(logcatFile).start()
            try {
                if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly()
            } finally {
                process.destroy()
            }
        }.onFailure { android.util.Log.w("LogEvent", "收集应用日志失败", it) }
    }

    val selinux = runCatching { shell?.let { ShellUtils.fastCmd(it, "getenforce") } }.getOrNull().orEmpty()

    // basic information
    val buildInfo = File(bugreportDir, "basic.txt")
    // 写文件同样会抛 IO 异常
    runCatching {
        PrintWriter(FileWriter(buildInfo)).use { pw ->
            pw.println("Kernel: ${System.getProperty("os.version")}")
            pw.println("BRAND: " + Build.BRAND)
            pw.println("MODEL: " + Build.MODEL)
            pw.println("PRODUCT: " + Build.PRODUCT)
            pw.println("MANUFACTURER: " + Build.MANUFACTURER)
            pw.println("SDK: " + Build.VERSION.SDK_INT)
            pw.println("PREVIEW_SDK: " + Build.VERSION.PREVIEW_SDK_INT)
            pw.println("FINGERPRINT: " + Build.FINGERPRINT)
            pw.println("DEVICE: " + Build.DEVICE)
            pw.println("Manager: " + getManagerVersion(context))
            pw.println("SELinux: $selinux")

            val uname = Os.uname()
            pw.println("KernelRelease: ${uname.release}")
            pw.println("KernelVersion: ${uname.version}")
            pw.println("Machine: ${uname.machine}")
            pw.println("Nodename: ${uname.nodename}")
            pw.println("Sysname: ${uname.sysname}")

            if (includePrivileged) {
                pw.println("MapleSU Runtime API: ${Natives.version}")
                pw.println("SafeMode: ${Natives.isSafeMode}")
                pw.println("LKM: ${Natives.isLkmMode}")
            }
        }
    }.onFailure { android.util.Log.w("LogEvent", "写 basic.txt 失败", it) }

    // modules（listModules() 解析/IO 失败时留空文件即可，不能让导出整体崩）
    val modulesFile = File(bugreportDir, "modules.json")
    if (includePrivileged) runCatching { modulesFile.writeText(listModules()) }

    val deviceContext = context.createDeviceProtectedStorageContext()
    BugreportDiagnostics.collect(deviceContext.dataDir, context.filesDir, bugreportDir)

    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm")
    val current = LocalDateTime.now().format(formatter)

    val targetFile = File(context.cacheDir, "MapleSU_bugreport_${current}_${bugreportDir.name.removePrefix("bugreport-")}.tar.gz")
    val knownSecrets = listOfNotNull(runCatching { UltraRootKeyStore.read(context) }.getOrNull())
    BugreportArchive.pack(bugreportDir, targetFile, knownSecrets)
}

fun saveBugreport(context: Context, uri: Uri, includePrivileged: Boolean = true) {
    var report: File? = null
    try {
        val archive = getBugreportFile(context, includePrivileged).also { report = it }
        BugreportArchive.copy(archive) { context.contentResolver.openOutputStream(uri, "wt") }
    } catch (error: Exception) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        throw error
    } finally {
        report?.delete()
    }
}

