package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.control.ControlJsonCodec
import dev.maplesu.manager.workmode.control.schema.InstalledFile
import dev.maplesu.manager.workmode.control.schema.ModesFile
import dev.maplesu.manager.workmode.control.schema.SwitchHeadViewCodec

/**
 * 编排三阶段探测 → 纯规则裁决。
 *
 * 阶段 0（本地只读）由 [localFactsProvider] 提供；阶段 1 是各非特权探针；
 * 阶段 2 只在 [DetectionRequest.allowRootProbe] 为 true 且阶段 1 结论给出**已确认通道**时执行，
 * 绝不盲试（主文档:784）。
 *
 * 本类不写控制面、不起任何特权动作；探针超时/异常一律记 UNKNOWN，不记 false。
 */
class DefaultModeDetector(
    private val ksuProbe: KsuKernelProbe = KsuKernelProbe { KsuFacts() },
    private val liteProbe: LiteCredentialProbe = LiteCredentialProbe { false to null },
    /**
     * Lite 凭据→UID0 握手。**只在 `allowLiteExecutor=true` 时调用**（启动期禁止起 root）。
     * 与 [liteProbe] 拆开：凭据存在性可在启动期只读，握手是特权动作（主文档:204/378）。
     */
    private val liteHandshakeProbe: LiteHandshakeProbe = LiteHandshakeProbe { null },
    private val ultraPort: UltraEnvironmentPort = UltraEnvironmentPort { UltraState.UNKNOWN },
    private val bootIdSource: BootIdSource = ProcBootIdSource,
    private val clock: Clock = WallClock,
    private val rootEvidenceReader: RootEvidenceReader = UnavailableRootEvidenceReader,
    private val localFactsProvider: (String) -> LocalFacts = { bootId ->
        LocalFacts(bootId = bootId, managerFirstInstallAgeMs = Long.MAX_VALUE)
    },
    private val ksuBranchUapi: Int = 7,
) : ModeDetector {

    override suspend fun detect(request: DetectionRequest): DetectionResult {
        val startedAt = clock.nowMillis()
        val bootId = bootIdSource.read()
        val trace = mutableListOf<ProbeTrace>()

        // 阶段 1：KSU 内核（非 root supercall 探针）。
        val ksuStarted = clock.nowMillis()
        val ksuResult = runCatching { ksuProbe.probe() }
        val ksu = ksuResult.getOrElse { KsuFacts() }
        trace += ProbeTrace(
            id = "S1",
            strength = Strength.S,
            ok = ksuResult.getOrNull()?.responded,
            detail = if (ksuResult.isSuccess) {
                "ksu.responded=${ksu.responded} version=${ksu.version} uapi=${ksu.uapi}"
            } else "ksu=UNKNOWN probe=${ksuResult.exceptionOrNull()?.javaClass?.simpleName}",
            elapsedMillis = clock.nowMillis() - ksuStarted,
        )
        trace += ProbeTrace("S2", Strength.S, ksuResult.getOrNull()?.isManager,
            if (ksuResult.isSuccess) "ksu.isManager=${ksu.isManager}" else "ksu.isManager=UNKNOWN", 0)

        // Lite executor 试跑前提：未排除 KSU 冲突（主文档:204）。
        val liteStarted = clock.nowMillis()
        val liteProbeResult = if (!ksu.responded) {
            runCatching { liteProbe.probe() }.getOrElse { false to null }
        } else {
            false to null
        }
        val credentialPresent = liteProbeResult.first
        val ultraStarted = clock.nowMillis()
        val ultra = runCatching { ultraPort.observe(
            allowRefresh = request.allowLiteExecutor && !ksu.responded,
            allowOwnerQuery = !ksu.responded,
        ) }
            .getOrDefault(UltraEnvironmentObservation(UltraState.UNKNOWN))
        val ultraState = ultra.state
        val ultraConfirmed = ultraState == UltraState.RUNNING || ultraState == UltraState.FAULT || ultra.kernelRootVerified
        val ultraIdentityKnown = ultraConfirmed || ultra.managerOwnerBound
        val ultraElapsed = clock.nowMillis() - ultraStarted
        // 握手是特权动作：只有在 allowLiteExecutor=true（阶段二/用户可见）且凭据在时才尝试。
        val handshakeOk = when {
            !request.allowLiteExecutor -> null
            ksu.responded -> null
            !credentialPresent -> null
            ultraConfirmed -> null
            else -> runCatching { liteHandshakeProbe.handshake() }.getOrNull()
        }
        trace += ProbeTrace("L1", Strength.A, credentialPresent, "credentialPresent=$credentialPresent", 0)
        trace += ProbeTrace(
            "L2", Strength.A, handshakeOk,
            "handshake=" + (handshakeOk?.toString() ?: "UNKNOWN"), clock.nowMillis() - liteStarted,
        )

        trace += ProbeTrace(
            "L3", Strength.A, if (ultraIdentityKnown) true else null,
            "ultra=$ultraState kernelRootVerified=${ultra.kernelRootVerified} managerOwnerBound=${ultra.managerOwnerBound}", ultraElapsed,
        )
        val lite = LiteFacts(
            credentialPresent = credentialPresent,
            handshakeOk = handshakeOk,
            ultraState = ultraState,
            ultraKeyPresent = false,
            ultraKernelRootVerified = ultra.kernelRootVerified,
            managerOwnerBound = ultra.managerOwnerBound,
        )

        // R6 already determines the mode; control-plane reads are separate recovery work.
        val ownerOnlyDecisive = !ksu.responded && !ultraConfirmed &&
            ultra.managerOwnerBound && handshakeOk != true
        val ultraIdentityDecisive = !ksu.responded && ultraConfirmed || ownerOnlyDecisive
        // 阶段 2：只在"已确认通道"上取证。
        var root: RootFacts? = null
        var channel: RootChannelKind? = null
        if (request.allowRootProbe) {
            channel = if (ownerOnlyDecisive) null else selectChannel(ksu, lite)
            if (ultraIdentityDecisive) {
                trace += ProbeTrace("M1", Strength.B, null, "ultraIdentityConfirmedControlPlaneDeferred", 0)
            } else if (channel != null) {
                val sessionStarted = clock.nowMillis()
                val session = runCatching { rootEvidenceReader.open(channel) }.getOrNull()
                if (session == null) {
                    trace += ProbeTrace(
                        "M1", Strength.B, null, "rootChannelUnavailable=$channel",
                        clock.nowMillis() - sessionStarted,
                    )
                } else {
                    session.use { openSession ->
                        root = readRootFacts(openSession, channel!!)
                    }
                    trace += ProbeTrace(
                        "M1", Strength.B, true, "rootChannel=$channel",
                        clock.nowMillis() - sessionStarted,
                    )
                }
            } else {
                trace += ProbeTrace("M1", Strength.B, null, "noChannel", 0)
            }
        } else {
            trace += ProbeTrace("M1", Strength.B, null, "rootProbeNotAllowed", 0)
        }

        val local = runCatching { localFactsProvider(bootId) }
            .getOrElse { LocalFacts(bootId = bootId, managerFirstInstallAgeMs = Long.MAX_VALUE) }
        val facts = DetectionFacts(
            local = local,
            ksu = ksu,
            lite = lite,
            root = root,
            ksuBranchUapi = ksuBranchUapi,
        )
        val decision = DetectionRules.decide(facts)
        trace += ProbeTrace(
            "RULE", Strength.B, true,
            decision.rule.name + decision.reasons.joinToString(separator = ",", prefix = "[", postfix = "]"),
            clock.nowMillis() - startedAt,
        )
        return DetectionResult(
            state = decision.state,
            group = decision.state.group,
            rule = decision.rule,
            bootId = bootId,
            observedAtMillis = clock.nowMillis(),
            trace = trace,
            recovery = if (ultraIdentityDecisive) RecoveryReadiness.NONE else decision.recovery,
            rootChannel = channel,
        )
    }

    /**
     * 通道选择由阶段 0/1 结论决定，不按顺序盲试（主文档:784、01 §10 C8）。
     * KSU 内核已响应但未认主 → 不选任何 Lite/Ultra 通道（那会把 B 设备误判为 A）。
     */
    private fun selectChannel(ksu: KsuFacts, lite: LiteFacts): RootChannelKind? = when {
        ksu.responded && ksu.isManager -> RootChannelKind.KSU_NATIVE
        ksu.responded -> null
        lite.ultraState == UltraState.RUNNING -> RootChannelKind.LITE_ULTRA
        lite.ultraState == UltraState.FAULT -> null
        lite.ultraKernelRootVerified -> RootChannelKind.LITE_ULTRA
        lite.handshakeOk == true -> RootChannelKind.LITE_DIRECT
        lite.credentialPresent -> RootChannelKind.LITE_DIRECT
        else -> null
    }

    private fun readRootFacts(session: RootEvidenceSession, channel: RootChannelKind): RootFacts {
        val modes = session.readControlFile("modes.json")
            ?.let { ControlJsonCodec.decodeOrNull<ModesFile>(it) }
        val installed = session.readControlFile("installed.json")
            ?.let { ControlJsonCodec.decodeOrNull<InstalledFile>(it) }
        val switchHead = session.readControlFile("switch.json")
            ?.let { SwitchHeadViewCodec.parse(it) }
        return RootFacts(
            channel = channel,
            modesJson = modes,
            installedJson = installed,
            switchHead = switchHead,
            aAuthorizationTable = session.hasAnyEntry("/data/adb/maplesu/authorization"),
            aInstallerRecord = session.hasAnyEntry("/data/adb/maplesu/installer"),
            aPublishedDirectory = session.hasAnyEntry("/data/adb/maplesu/published-directory"),
            ksuDirHasContent = session.hasAnyEntry("/data/adb/ksu"),
            ksudExecutable = session.isExecutable("/data/adb/ksud"),
            kmiWritablePreinit = session.exists("/data/adb/ksu/.kmi-writable"),
            foreignManagerMarkers =
                session.hasAnyEntry("/data/adb/magisk") == true ||
                    session.hasAnyEntry("/data/adb/ksu/.allowlist") == true && channel != RootChannelKind.KSU_NATIVE,
        )
    }
}
