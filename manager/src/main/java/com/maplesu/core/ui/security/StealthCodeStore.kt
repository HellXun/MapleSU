package com.maplesu.core.ui.security

import android.content.Context
import com.topjohnwu.superuser.ShellUtils
import com.maplesu.core.data.repository.SettingsRepositoryImpl
import com.maplesu.core.ui.util.getRootShell
import dev.maplesu.manager.workmode.ModeRuntime

/**
 * 隐身密令的「跨卸载备份」。
 *
 * 为什么需要它(这是个真会把人锁死的 bug):
 *   · 隐身状态存在**内核侧** —— 卸载 App 也不会丢;
 *   · 而密令数字以前只存在 **App 自己的 shared_prefs** 里 —— 卸载就没了。
 *   于是:改了自定义密令 → 卸载重装 → 内核仍处于隐身(界面伪装成"未安装"),
 *   而 App 里的"期望密令"已经退回默认 70707 → 你输入自己那个自定义密令,
 *   和 70707 不匹配 → 接收器**静默忽略** → 恢复不回来,彻底锁死。
 *
 * **P1 修复（模式感知）**：A 的权威文件仍是 `/data/adb/sevenk/stealth_code`；
 * B（KSU 分支）的 ksud 读写 `/data/adb/ksu/stealth_code`，因此：
 * - B 读 ksu 优先 + 兼容读 sevenk 旧值（只读，不写）；写 ksu；
 * - A/未判定保持 sevenk 行为不变（含历史 `/data/adb/ksu` 软链只读兜底 + 自愈补写）。
 * 路径选择唯一来源 = [StealthPathPolicy]（纯函数，主机可测）。
 */
object StealthCodeStore {

    private const val TAG = "maplesu-stealth"

    /** 默认密令(和 StealthReceiver 保持一致) */
    const val DEFAULT_CODE = "70707"

    /**
     * KernelSU 自己那些文件的 SELinux 类型(和 ksud 的 `restorecon.rs` 里 `KSU_CON` 同一个值)。
     *
     * `/data/adb` 下的文件默认标签是 `u:object_r:adb_data_file:s0`,
     * ksud 自己的文件(ksud 本体、模块元数据)统一是 `u:object_r:ksu_file:s0`。
     * v2.1(审计 P1-6):密令这份也归一到 `ksu_file`,和其它落盘物一致。
     */
    private const val KSU_CON = "u:object_r:ksu_file:s0"

    private fun sanitize(raw: String?): String? =
        raw?.trim()?.filter { it.isDigit() }?.take(12)?.takeIf { it.isNotBlank() }

    private fun readFrom(path: String): String? =
        runCatching { sanitize(ShellUtils.fastCmd(getRootShell(), "cat $path 2>/dev/null")) }.getOrNull()

    /**
     * 读当前模式的主密令（需 root；失败返回 null）。
     * 顺序由 [StealthPathPolicy.readPaths] 决定：
     * - B：ksu → sevenk（旧值只读兼容，不写）；
     * - A/未判定：sevenk → 历史 ksu 软链（读到后自愈补写到 sevenk，原行为）。
     */
    fun read(): String? = runCatching {
        val mode = ModeRuntime.active
        for (entry in StealthPathPolicy.readPaths(mode)) {
            val value = readFrom(entry.path) ?: continue
            if (entry.selfHealWriteToPrimary) write(value) // 自愈:补写到当前主路径
            return value
        }
        null
    }.getOrNull()

    /**
     * 把密令写到当前模式的主路径（B=ksu / A=sevenk；未判定按 A 现有行为）。
     *
     * ⚠️ v2.1(审计 P1-6):以前只有一句
     * `mkdir -p … && printf … > … && chmod 600 …` 就返回 true ——
     * **既没人验,权限还不可靠**:真机实测这个文件是 `-rwxrwxrwx`(777),
     * 等于任何 App / 任何 uid 都能改掉隐身密令(隐身等于没隐)。
     *
     * 现在的做法(**先写临时文件、验过了才顶替真文件**):
     *   ① 写 `$PATH.new` → `chmod 600` → `chown 0:0` → `chcon ksu_file`(不行就 `restorecon`);
     *   ② **回读核验**:内容一字不差 + 权限正好 600 + 属主 0;
     *   ③ 全对才 `mv -f` 覆盖真文件,并再验一次。
     * 任何一步不对就删掉临时文件、**真文件一个字节都不动** ——
     * 密令文件坏掉 = 用户被锁死在隐身里,这是本项目最怕的事故,所以宁可这次不写。
     *
     * @return 是否**真的**落盘并且核验通过
     */
    fun write(code: String): Boolean = runCatching {
        val c = sanitize(code) ?: return@runCatching false
        val mode = ModeRuntime.active
        val dir = StealthPathPolicy.writeDir(mode)
        val path = StealthPathPolicy.writePath(mode)
        val tmp = "$path.new"
        // 用 ${'$'} 转义 shell 变量:这段是 sh 脚本,不是 Kotlin 模板
        val script = """
            mkdir -p $dir
            printf '%s' '$c' > $tmp
            chmod 600 $tmp 2>/dev/null
            chown 0:0 $tmp 2>/dev/null
            chcon $KSU_CON $tmp 2>/dev/null || restorecon $tmp 2>/dev/null || true
            got=${'$'}(cat $tmp 2>/dev/null)
            mode=${'$'}(stat -c %a $tmp 2>/dev/null)
            owner=${'$'}(stat -c %u $tmp 2>/dev/null)
            if [ "${'$'}got" = '$c' ] && [ "${'$'}mode" = '600' ] && [ "${'$'}owner" = '0' ]; then
              mv -f $tmp $path
              chmod 600 $path 2>/dev/null
              chown 0:0 $path 2>/dev/null
              got2=${'$'}(cat $path 2>/dev/null)
              if [ "${'$'}got2" = '$c' ]; then echo "7K_OK mode=${'$'}mode owner=${'$'}owner"; else echo 7K_FAIL_AFTER_MV; fi
            else
              rm -f $tmp
              echo "7K_FAIL mode=${'$'}mode owner=${'$'}owner"
            fi
        """.trimIndent()
        val out = ShellUtils.fastCmd(getRootShell(), script)
        // 🔒 2026-09-25：只记成败，不记 shell 原文 —— 万一以后脚本回显里带上密令，
        //    也不会有第二处泄露（当前脚本只回显 7K_OK/7K_FAIL + mode/owner）。
        android.util.Log.i(TAG, "密令落盘核验:${if (out.contains("7K_OK")) "OK" else "FAIL"} (mode=${modeWire(mode)})")
        out.contains("7K_OK")
    }.getOrDefault(false)

    private fun modeWire(mode: dev.maplesu.manager.workmode.WorkMode?): String = when (mode) {
        dev.maplesu.manager.workmode.WorkMode.LITE -> "lite"
        null -> "none"
    }

    /**
     * 真正生效的密令:优先当前模式的磁盘主路径(能跨卸载),其次 App 设置,最后默认值。
     * **界面显示**用它(设置页要展示"当前生效的是哪个")。
     *
     * ⚠️ 但**接收器比对不要用这个** —— 见 [acceptedCodes]。
     */
    fun effectiveCode(context: Context? = null): String {
        read()?.let { return it }
        val pref = runCatching { SettingsRepositoryImpl().stealthCode }.getOrNull()
        return sanitize(pref) ?: DEFAULT_CODE
    }

    /**
     * **可接受的密令集合(取并集)** —— 接收器比对用这个。
     *
     * 为什么必须是并集:密令存在两个地方(当前模式的磁盘主路径 + 旧路径兼容值)和 App 设置。
     * 以前比对只认"优先的那一份"(磁盘优先),于是只要两份**不一致**就会锁死。
     * 取并集之后,磁盘(当前模式 + 兼容旧路径)和设置里的**任意一个**都能开锁。
     *
     * P1 修复：B 下并集**包含 ksu 路径且优先**（[StealthPathPolicy.acceptedPaths]）；
     * A 保持 sevenk→历史 ksu 顺序不变。
     *
     * ⚠️ 有意**不**把默认值 70707 作为"万能兜底"塞进来 —— 那等于给所有人一把钥匙,
     *    会削弱隐身本身的意义。只有全部来源都**没配**时才用默认值(全新安装的正常情况)。
     */
    fun acceptedCodes(context: Context? = null): Set<String> {
        val codes = LinkedHashSet<String>()
        val mode = ModeRuntime.active
        StealthPathPolicy.acceptedPaths(mode).forEach { path ->
            readFrom(path)?.let { codes.add(it) }
        }
        runCatching { SettingsRepositoryImpl().stealthCode }.getOrNull()
            ?.let { sanitize(it) }?.let { codes.add(it) }
        return if (codes.isEmpty()) setOf(DEFAULT_CODE) else codes
    }

    /**
     * 自愈/迁移(App 启动、打开设置页时各调一次,跑在 IO 线程):
     *   · 主路径有、App 设置没有 → 用磁盘值覆盖 App 设置(卸载重装后的正路)
     *   · App 设置有、主路径没有 → 把 App 设置写进主路径(老用户补备份,以后卸载也不怕)
     * @return 是否需要界面刷新
     */
    fun sync(): Boolean {
        val repo = runCatching { SettingsRepositoryImpl() }.getOrNull() ?: return false
        val pref = sanitize(repo.stealthCode)
        val disk = read()
        return when {
            disk == null && pref != null -> {
                write(pref)
                false
            }

            disk != null && disk != pref -> {
                repo.stealthCode = disk
                true
            }

            else -> false
        }
    }
}
