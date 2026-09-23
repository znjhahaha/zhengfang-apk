package com.tyust.course.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tyust.course.academic.*
import com.tyust.course.diagnostics.AppDiagnostics
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SchoolUrlRecognition(
    selectedType: String,
    manualType: Boolean,
    addressKey: String,
    onAddress: (AcademicAddress) -> Unit,
    onDetected: (AcademicSystem) -> Unit,
    onInvalidAddress: (String?) -> Unit
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var diagnostic by remember { mutableStateOf<String?>(null) }
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
            generation++; job?.cancel(); busy = false; input = it; result = null; diagnostic = null; onInvalidAddress(null)
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
                        withContext(Dispatchers.IO) { AppDiagnostics.markStage(context, "学校网址识别：请求与解析") }
                        val detected = AcademicDetection.detect(requestUrl)
                        if (generation != requestGeneration || latestAddress != activeAddress) return@launch
                        AppDiagnostics.markStage(context, "学校网址识别：显示结果")
                        result = detected
                        if (detected.system != null) {
                            detected.address?.let(latestOnAddress)
                            if (!latestManual) latestOnDetected(detected.system)
                        }
                        if (detected.status == AcademicDetectionStatus.NETWORK_ERROR) {
                            val report = withContext(Dispatchers.IO) {
                                AppDiagnostics.capture(context, "学校网址识别：网络失败", detected.failure)
                            }
                            if (generation != requestGeneration || latestAddress != activeAddress) return@launch
                            diagnostic = report
                            runCatching { AppDiagnostics.showReport(context, report) }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (generation != requestGeneration || latestAddress != activeAddress) return@launch
                        val report = withContext(Dispatchers.IO) { AppDiagnostics.capture(context, "学校网址识别", e) }
                        if (generation != requestGeneration || latestAddress != activeAddress) return@launch
                        result = AcademicDetectionResult(AcademicDetectionStatus.NETWORK_ERROR, parsed)
                        diagnostic = report
                        runCatching { AppDiagnostics.showReport(context, report) }
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
        diagnostic?.let { report ->
            SystemSecondaryButton("查看错误详情", { AppDiagnostics.showReport(context, report) }, Modifier.fillMaxWidth())
        }
    }
}
