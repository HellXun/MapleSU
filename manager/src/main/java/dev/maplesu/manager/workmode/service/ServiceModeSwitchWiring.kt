package dev.maplesu.manager.workmode.service

import android.content.Context
import android.util.Log
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.ControlPaths
import dev.maplesu.manager.workmode.control.ControlRead
import dev.maplesu.manager.workmode.control.FileControlPlane
import dev.maplesu.manager.workmode.control.schema.ModesFile
import dev.maplesu.manager.workmode.detect.ProcBootIdSource
import dev.maplesu.manager.workmode.detect.WallClock
import dev.maplesu.manager.workmode.switch.DeviceBootFacts
import dev.maplesu.manager.workmode.switch.FileBaselineStore
import dev.maplesu.manager.workmode.switch.FileSwitchAuthority
import dev.maplesu.manager.workmode.switch.FileSwitchJournal
import dev.maplesu.manager.workmode.switch.ModeSwitchCodes
import dev.maplesu.manager.workmode.switch.SwitchDeviceFacts
import dev.maplesu.manager.workmode.switch.SwitchJournalFactory
import dev.maplesu.manager.installer.InstallerDeviceLock
import dev.maplesu.manager.installer.InstallerPending
import dev.maplesu.manager.installer.NoStockBaselineMaterializer
import dev.maplesu.manager.ultrakernel.UltraKernelContextAssets
import java.io.File
import java.io.IOException

/**
 * 把 W08 服务端增量状态机装进 root 服务进程。
 *
 * 依赖全部来自真实实现：`FileSwitchAuthority`/`FileSwitchJournal`/`FileBaselineStore`、
 * `LocalInstallerGateway`（installer-core 事务 + Android 分区适配）、`InstallerDeviceLock`
 * （唯一 install.lock）与 `InstallerPending`（安装事务 pending 守卫）。
 *
 * P7：`baselines` 必须带**非空** `materializer`。缺了它 `FileBaselineStore` 会走
 * manifest-only 分支（`BaselineStore.kt:327-328`），写出"声称干净但没有 `.img`"的基线——
 * 那正是 09 §5.4 批评的形态，且 manifest 有 `immutable` 保护 ⇒ 一台设备会被永久锁死。
 *
 * 2026-11-01 `55-asset-slim.md`：本构建不再随包 stock 资产，所以这里注入的是
 * [NoStockBaselineMaterializer]（显式 `BASELINE_CAPTURE_UNSUPPORTED`、零写入），
 * 而不是真实的 stock 解压实现。显式基线保留其证明；未指定时由共同恢复器处理当前 PRE。
 */
internal object ServiceModeSwitchWiring {
    val INSTALLER_ROOT = File("/data/adb/maplesu/installer")

    /**
     * @param context root 服务自己的 Context；既有恢复器从同一 APK 读取兼容资料、
     *   retained originals 和 ksud。无显式 baseline 时恢复当前载体，不创建伪原厂基线。
     */
    fun create(context: Context): ModeSwitchDeviceDelegate {
        val controlRoot = File(ControlPaths.ROOT)
        val workRoot = File(ControlPaths.WORK)
        val clock = WallClock
        val bootIds = ProcBootIdSource
        val authority = FileSwitchAuthority(File(controlRoot, "control"), clock)
        val journalFactory = SwitchJournalFactory { switchId ->
            FileSwitchJournal(File(workRoot, "$switchId/journal.jsonl"), clock, bootIds)
        }
        val gateway = LocalInstallerGateway(
            File(controlRoot, "boot/baseline"),
            authority = authority,
        ) { switchId -> journalFactory.open(switchId) }
        val sourceRecovery = ModeSwitchSourceRecovery(
            assets = UltraKernelContextAssets(context),
            receiptsDirectory = dev.maplesu.manager.compat.UltraStateStorage.flash(context.filesDir),
            retainedOriginals = listOf(File(dev.maplesu.manager.compat.UltraStateStorage.flash(context.filesDir), "base-stock.img")),
            logger = { line -> Log.i("MapleSU/ModeSwitch", line) },
        )
        val controlPlane = FileControlPlane(controlRoot, clock, bootIds)

        val deviceFacts: () -> SwitchDeviceFacts = {
            runCatching { gateway.deviceFactsNow() }.getOrElse {
                SwitchDeviceFacts("", "", "", "Enforcing")
            }
        }
        val bootFacts: () -> DeviceBootFacts = {
            val facts = deviceFacts()
            DeviceBootFacts(facts.slot, facts.firmware, facts.kernelRelease)
        }
        // 55-asset-slim：不再随包 stock 资产；materializer 仍必须非空（否则退化成
        // manifest-only 假基线），用 fail-closed 占位实现顶住。
        val baselines = FileBaselineStore(
            root = controlRoot,
            deviceFacts = bootFacts,
            clock = clock,
            materializer = NoStockBaselineMaterializer,
        )
        val modeFacts = DeviceModeFactsPort {
            val modes = when (val read = controlPlane.readModes()) {
                is ControlRead.Ok -> read.value
                else -> null
            }
            DeviceModeFacts(
                currentMode = modes?.let { WorkMode.fromSuffix(it.current) },
                modeState = modes?.let { modeStateWire(it) },
            )
        }
        val pendingGuard = DevicePendingGuard {
            try {
                InstallerPending.assertNoPendingInstall(INSTALLER_ROOT)
            } catch (error: IOException) {
                throw error
            }
        }
        val lock = object : DeviceLockPort {
            override fun <T> withLock(action: () -> T): T =
                InstallerDeviceLock.withDeviceLock(INSTALLER_ROOT, action = action)
        }
        // W08b：S4 配置交换在 root 服务进程（UID 0）内直接执行；App 侧只读结果。
        val moveStage = dev.maplesu.manager.workmode.switch.FileSwitchMoveStage(
            roots = dev.maplesu.manager.workmode.storage.MovePathRoots(
                liveRoot = File("/"),
                storeRoot = File(dev.maplesu.manager.workmode.storage.MoveSet.STORE_ROOT),
            ),
        )
        return ModeSwitchDeviceDelegate(
            controlRoot = controlRoot,
            workRoot = workRoot,
            authority = authority,
            journalFactory = journalFactory,
            baselines = baselines,
            gateway = gateway,
            modeFacts = modeFacts,
            pendingGuard = pendingGuard,
            lock = lock,
            deviceFacts = deviceFacts,
            moveStage = moveStage,
            bootIds = bootIds,
            clock = clock,
            sourceRecovery = sourceRecovery::recover,
        )
    }

    /** 服务端只保留 wire 字符串："MODE_A_ACTIVE"/"MODE_B_ACTIVE"/null。 */
    private fun modeStateWire(modes: ModesFile): String? = when (modes.current) {
        "A" -> "MODE_A_ACTIVE"
        "B" -> "MODE_B_ACTIVE"
        else -> null
    }
}
