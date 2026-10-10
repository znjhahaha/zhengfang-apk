package com.tyust.course.academic

import com.tyust.course.academic.plugin.AcademicProviderRegistry
import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** The same policy is consumed by the form, alarm and service. No plugin ABI changes. */
data class GrabCapabilities(
    val available: Boolean,
    val exact: Boolean,
    val fuzzy: Boolean,
    val manual: Boolean,
    val scheduling: Boolean,
    val maxConcurrency: Int,
    val reason: String = "",
    val parallelReason: String = "",
    val providerKey: String = ""
) {
    fun workers(parallel: Boolean) = if (parallel) maxConcurrency else 1
    fun validate(items: List<AcademicGrabItem>): String? = when {
        !available -> reason
        items.any { it.useExactMatch && (!exact || it.stableSectionId.isBlank()) } -> "精确匹配需要真实教学班，请从课程列表选择教学班，或改用模糊匹配"
        items.any { !it.useExactMatch && !fuzzy } -> "当前教务适配不支持模糊匹配"
        else -> null
    }

    companion object {
        // This shipped package advertises the selection group but select/drop call noMutation.
        // Restrict its reviewed digest, not every Chengfang school or future replacement plugin.
        internal const val UNVERIFIED_CHENGFANG = "f4bdd3e5e3e6fe30ba8a00f3892ad9fd65a56e9e32f50876d5cc45102d46ec1b"
        // CLI whitespace minification changes the archive digest without implementing noMutation.
        private val unverifiedChengfangDigests = setOf(UNVERIFIED_CHENGFANG,
            "ba27c7c6e8e47322f1386bfe7c907520411903f975f5f88ad6fddb8fc9c17d00",
            // Rebuilt with SDK 3.5.0; the reviewed provider still uses noMutation.
            "9caa9ef4fd0442a42c2d6a49be96fd55d1ad73712f1cc9ebb2c841150b675601")
        internal val selectionOperations = setOf("selection.catalog", "selection.courses", "selection.sections", "selection.select")

        internal fun evaluate(system: AcademicSystem?, plugin: Boolean, operations: Set<String>,
            submissionDigest: String = "", providerKey: String = "", unavailableReason: String = ""): GrabCapabilities {
            val reason = when {
                unavailableReason.isNotBlank() -> unavailableReason
                submissionDigest in unverifiedChengfangDigests -> "当前乘方适配的选课提交尚未支持，请使用学校网页；查询功能仍可使用"
                plugin && !operations.containsAll(selectionOperations) -> "当前教务插件未提供完整的选课查询与提交能力"
                !plugin && (system == null || system == AcademicSystem.AUTO) -> "请先选择并启用本校教务适配"
                else -> ""
            }
            val enabled = reason.isBlank()
            val parallel = enabled && !plugin && system in setOf(AcademicSystem.ZF, AcademicSystem.LEGACY_ZF)
            return GrabCapabilities(enabled, enabled, enabled, enabled, enabled, if (parallel) 2 else 1,
                reason, if (parallel) "" else if (plugin) "当前教务插件按账号串行执行，保护学校会话和教学班参数" else "当前教务仅支持按账号串行执行", providerKey)
        }

        fun forSchool(school: SchoolConfig, account: String = ""): GrabCapabilities {
            if (school.academicSystem == AcademicSystem.LEGACY_ZF.id && !AcademicProviderRegistry.hasBinding(school))
                return evaluate(AcademicSystem.LEGACY_ZF, false, emptySet(), providerKey = "legacy_zf")
            val pkg = runCatching { AcademicProviderRegistry.resolve(school) }.getOrNull()
            val submission = AcademicProviderRegistry.operationProvider(school, "selection.select")
            val key = listOf(pkg?.digest.orEmpty(), submission?.digest.orEmpty()).joinToString(":")
            val operations = selectionOperations.filter { AcademicProviderRegistry.hasCapability(school, it) }.toSet()
            return evaluate(AcademicSystem.fromId(school.academicSystem), true, operations,
                submission?.digest.orEmpty(), key,
                if (pkg == null) "本校教务插件未启用或不可用，请在插件中心检查适配" else GrabCapabilityFailures.reason(account, key))
        }
    }
}

/** Unsupported responses apply only to this account and these exact provider versions. */
object GrabCapabilityFailures {
    private val failures = ConcurrentHashMap<Pair<String, String>, String>()
    private val changed = MutableStateFlow(0L)
    val revision = changed.asStateFlow()
    fun reason(account: String, provider: String): String = failures[account to provider].orEmpty()
    @Synchronized fun record(account: String, provider: String, reason: String) {
        failures[account to provider] = reason.ifBlank { "当前学校尚未支持此选课操作，请使用学校网页" }
        changed.value++
    }
}
