package com.tyust.course.ui.screen

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tyust.course.network.SchoolAdaptationManager
import com.tyust.course.network.SchoolAdaptationRequest
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemIconButton
import com.tyust.course.ui.system.SystemTopBar
import com.tyust.course.ui.theme.SemanticWarning
import com.tyust.course.ui.theme.SemanticWarningContainer
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SchoolAdaptationFlow(
    onNavigateBack: () -> Unit
) {
    var showForm by rememberSaveable { mutableStateOf(false) }
    var refreshKey by rememberSaveable { mutableStateOf(0) }

    if (showForm) {
        SchoolAdaptationScreen(
            onNavigateBack = { showForm = false },
            onSubmitted = {
                refreshKey++
                showForm = false
            }
        )
    } else {
        SchoolAdaptationHistoryScreen(
            onNavigateBack = onNavigateBack,
            onNewRequest = { showForm = true },
            refreshKey = refreshKey
        )
    }
}

@Composable
fun SchoolAdaptationScreen(
    onNavigateBack: () -> Unit,
    onSubmitted: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var schoolName by rememberSaveable { mutableStateOf("") }
    var academicSystemUrl by rememberSaveable { mutableStateOf("") }
    var ssoUrl by rememberSaveable { mutableStateOf("") }
    var testUsername by remember { mutableStateOf("") }
    var temporaryPassword by remember { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var acceptedRisk by rememberSaveable { mutableStateOf(false) }
    var clientRequestId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var showPassword by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val requiredFieldsReady = schoolName.isNotBlank() &&
        academicSystemUrl.isNotBlank() &&
        testUsername.isNotBlank() &&
        temporaryPassword.isNotEmpty()

    fun submit() {
        val validationError = validateAdaptationRequest(
            schoolName = schoolName,
            academicSystemUrl = academicSystemUrl,
            ssoUrl = ssoUrl,
            testUsername = testUsername,
            temporaryPassword = temporaryPassword,
            contact = contact,
            notes = notes
        )
        if (validationError != null) {
            errorMessage = validationError
            return
        }

        isSubmitting = true
        errorMessage = null
        focusManager.clearFocus()
        scope.launch {
            val result = SchoolAdaptationManager.submit(
                context,
                SchoolAdaptationRequest(
                    schoolName = schoolName.trim(),
                    academicSystemUrl = academicSystemUrl.trim(),
                    ssoUrl = ssoUrl.trim(),
                    testUsername = testUsername.trim(),
                    temporaryPassword = temporaryPassword,
                    contact = contact.trim(),
                    notes = notes.trim(),
                    requestId = clientRequestId
                )
            )
            isSubmitting = false
            result.onSuccess {
                testUsername = ""
                temporaryPassword = ""
                clientRequestId = UUID.randomUUID().toString()
                showPassword = false
                onSubmitted()
            }.onFailure {
                errorMessage = "提交失败，请检查网络后重试"
            }
        }
    }

    Scaffold(
        topBar = {
            SystemTopBar(
                title = "申请统一登录适配",
                subtitle = "提交临时测试账号协助适配",
                navigationIcon = {
                    SystemIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = onNavigateBack
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = PagePadding,
                end = PagePadding,
                top = 20.dp,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = SemanticWarningContainer,
                    border = BorderStroke(1.dp, SemanticWarning.copy(alpha = 0.25f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "提交前请先设置临时密码",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = SemanticWarning
                        )
                        Text(
                            text = "不要复用其他平台密码。账号仅用于分析学校登录流程；适配完成后，应用会提醒你立即修改学校账号密码。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                AdaptationTextField(
                    value = schoolName,
                    onValueChange = { schoolName = it.take(100) },
                    label = "学校名称",
                    placeholder = "例如：某某大学",
                    imeAction = ImeAction.Next,
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            }
            item {
                AdaptationTextField(
                    value = academicSystemUrl,
                    onValueChange = { academicSystemUrl = it.take(500) },
                    label = "教务系统地址",
                    placeholder = "https://jw.example.edu.cn",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            }
            item {
                AdaptationTextField(
                    value = ssoUrl,
                    onValueChange = { ssoUrl = it.take(500) },
                    label = "统一认证 / SSO 地址（选填）",
                    placeholder = "https://sso.example.edu.cn",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            }
            item {
                AdaptationTextField(
                    value = testUsername,
                    onValueChange = { testUsername = it.take(100) },
                    label = "临时测试账号",
                    placeholder = "学号 / 工号",
                    imeAction = ImeAction.Next,
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            }
            item {
                OutlinedTextField(
                    value = temporaryPassword,
                    onValueChange = { temporaryPassword = it.take(200) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("临时测试密码") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "隐藏密码" else "显示密码"
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp)
                )
            }
            item {
                AdaptationTextField(
                    value = contact,
                    onValueChange = { contact = it.take(200) },
                    label = "联系方式（选填）",
                    placeholder = "QQ、邮箱或其他联系方式",
                    imeAction = ImeAction.Next,
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            }
            item {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(1000) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    label = { Text("补充说明（选填）") },
                    placeholder = { Text("登录入口、验证码、已知跳转流程等") },
                    shape = RoundedCornerShape(14.dp)
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = acceptedRisk,
                        onCheckedChange = { acceptedRisk = it }
                    )
                    Text(
                        text = "我已设置临时密码，并理解适配完成后需要立即修改密码",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (errorMessage != null) {
                item {
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            item {
                Spacer(modifier = Modifier.height(2.dp))
                SystemPrimaryButton(
                    text = if (isSubmitting) "正在提交…" else "提交适配申请",
                    onClick = ::submit,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = requiredFieldsReady && acceptedRisk && !isSubmitting
                )
            }
        }
    }
}

@Composable
private fun AdaptationTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction,
    onNext: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onNext = { onNext() }
        ),
        shape = RoundedCornerShape(14.dp)
    )
}

private fun validateAdaptationRequest(
    schoolName: String,
    academicSystemUrl: String,
    ssoUrl: String,
    testUsername: String,
    temporaryPassword: String,
    contact: String,
    notes: String
): String? {
    if (schoolName.isBlank()) return "请填写学校名称"
    if (!isHttpUrl(academicSystemUrl)) return "请填写有效的教务系统 HTTP(S) 地址"
    if (ssoUrl.isNotBlank() && !isHttpUrl(ssoUrl)) return "请填写有效的统一认证 HTTP(S) 地址"
    if (testUsername.isBlank()) return "请填写临时测试账号"
    if (temporaryPassword.isEmpty()) return "请填写临时测试密码"
    if (schoolName.length > 100 || testUsername.length > 100) return "学校名称或测试账号过长"
    if (academicSystemUrl.length > 500 || ssoUrl.length > 500) return "登录地址过长"
    if (temporaryPassword.length > 200 || contact.length > 200 || notes.length > 1000) return "填写内容超过长度限制"
    return null
}

private fun isHttpUrl(value: String): Boolean {
    val uri = runCatching { Uri.parse(value.trim()) }.getOrNull() ?: return false
    return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
}