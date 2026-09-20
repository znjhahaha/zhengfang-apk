package com.tyust.course.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.tyust.course.academic.*
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun SchoolUrlRecognition(
    selectedType: String,
    manualType: Boolean,
    addressKey: String,
    onAddress: (AcademicAddress) -> Unit,
    onDetected: (AcademicSystem) -> Unit,
    onInvalidAddress: (String?) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<AcademicDetectionResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var activeAddress by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val latestManual by rememberUpdatedState(manualType)
    val latestOnDetected by rememberUpdatedState(onDetected)
    val latestOnAddress by rememberUpdatedState(onAddress)
    val latestAddress by rememberUpdatedState(addressKey)
    LaunchedEffect(addressKey) {
        if (busy && activeAddress != addressKey) {
            generation++; job?.cancel(); busy = false; result = null
        }
    }
    SchoolFormPanel {
        SchoolFormPanelTitle(Icons.Default.AutoAwesome, "智能识别")
        SchoolFormField("教务系统网址", input, {
            generation++; job?.cancel(); busy = false; input = it; result = null; onInvalidAddress(null)
        }, placeholder = "粘贴完整的教务登录页或首页网址",
            helper = "自动填写地址并识别教务类型，保留手动选择的类型")
        SystemPrimaryButton(if (busy) "正在识别…" else "识别网址", {
            job?.cancel()
            val requestGeneration = ++generation
            val requestUrl = input
            val parsed = AcademicAddress.parse(input)
            if (parsed == null) {
                result = AcademicDetectionResult(AcademicDetectionStatus.INVALID_ADDRESS)
                onInvalidAddress(result!!.message)
            } else {
                activeAddress = "${parsed.protocol}|${parsed.domain}|${parsed.basePath}"
                onInvalidAddress(null); onAddress(parsed); busy = true; result = null
                job = scope.launch {
                    try {
                        val detected = AcademicDetection.detect(requestUrl)
                        if (generation != requestGeneration || latestAddress != activeAddress) return@launch
                        result = detected
                        if (detected.system != null) {
                            detected.address?.let(latestOnAddress)
                            if (!latestManual) latestOnDetected(detected.system)
                        }
                    } finally { if (generation == requestGeneration) busy = false }
                }
            }
        }, Modifier.fillMaxWidth(), enabled = input.isNotBlank() && !busy)
        result?.let { detected ->
            Text(detected.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            detected.system?.takeIf { manualType && it.id != selectedType && !(selectedType == "legacy_zf" && it == AcademicSystem.ZF) }?.let { type ->
                SystemSecondaryButton("改用${AcademicCapabilities.selectionLabel(type)}", { onDetected(type) }, Modifier.fillMaxWidth())
            }
        }
    }
}
