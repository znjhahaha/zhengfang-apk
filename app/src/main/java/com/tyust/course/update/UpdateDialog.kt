package com.tyust.course.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tyust.course.ui.theme.PrimaryPurple

/**
 * 更新对话框
 */
@Composable
fun UpdateDialog(
    updateInfo: UpdateManager.UpdateInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    downloadProgress: Int = -1,  // -1 表示未开始下载
    isDownloading: Boolean = false
) {
    // 控制对话框内容的入场动画
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
    }

    // 图标动画：呼吸效果
    val infiniteTransition = rememberInfiniteTransition(label = "iconScale")
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Dialog(
        onDismissRequest = { if (!isDownloading && !updateInfo.forceUpdate) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading && !updateInfo.forceUpdate,
            dismissOnClickOutside = !isDownloading && !updateInfo.forceUpdate,
            usePlatformDefaultWidth = false // 允许自定义宽度
        )
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = scaleIn(initialScale = 0.8f, animationSpec = tween(400, easing = OvershootInterpolator(1.5f).toEasing())) + 
                    fadeIn(animationSpec = tween(400)),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 图标
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            }
                            .size(72.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(PrimaryPurple.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = PrimaryPurple
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 标题
                Text(
                    text = "发现新版本",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1A1A)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 版本信息
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "v$currentVersion",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = " → ",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = "v${updateInfo.versionName}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryPurple
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 更新说明
                if (updateInfo.releaseNotes.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp), // 限制最大高度
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F7))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState()) // 添加滚动
                        ) {
                            Text(
                                text = "更新内容",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = updateInfo.releaseNotes,
                                fontSize = 14.sp,
                                color = Color(0xFF333333),
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // 下载进度
                AnimatedVisibility(visible = isDownloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val animatedProgress by animateFloatAsState(
                            targetValue = downloadProgress / 100f,
                            label = "progress"
                        )
                        
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = PrimaryPurple,
                            trackColor = Color(0xFFE0E0E0)
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = if (downloadProgress >= 100) "下载完成，正在安装..." else "正在下载 $downloadProgress%",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                
                // 按钮
                AnimatedVisibility(visible = !isDownloading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 稍后按钮（非强制更新时显示）
                        if (!updateInfo.forceUpdate) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("稍后")
                            }
                        }
                        
                        // 更新按钮
                        Button(
                            onClick = onUpdate,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
                        ) {
                            Text("立即更新")
                        }
                    }
                }
            }
            }
        }
    }
}

// 辅助函数：将 Interpolator 转换为 Easing
private fun android.view.animation.Interpolator.toEasing() = Easing { x -> 
    getInterpolation(x) 
}

private class OvershootInterpolator(val tension: Float = 2f) : android.view.animation.Interpolator {
    override fun getInterpolation(t: Float): Float {
        var x = t - 1.0f
        return x * x * ((tension + 1) * x + tension) + 1.0f
    }
}

/**
 * 更新检查状态管理
 */
@Composable
fun rememberUpdateState(
    context: android.content.Context = LocalContext.current
): UpdateState {
    val updateManager = remember { UpdateManager.getInstance(context) }
    
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    
    return remember(updateManager) {
        UpdateState(
            updateManager = updateManager,
            updateInfo = { updateInfo },
            setUpdateInfo = { updateInfo = it },
            showDialog = { showDialog },
            setShowDialog = { showDialog = it },
            isDownloading = { isDownloading },
            setIsDownloading = { isDownloading = it },
            downloadProgress = { downloadProgress },
            setDownloadProgress = { downloadProgress = it }
        )
    }
}

class UpdateState(
    private val updateManager: UpdateManager,
    val updateInfo: () -> UpdateManager.UpdateInfo?,
    val setUpdateInfo: (UpdateManager.UpdateInfo?) -> Unit,
    val showDialog: () -> Boolean,
    val setShowDialog: (Boolean) -> Unit,
    val isDownloading: () -> Boolean,
    val setIsDownloading: (Boolean) -> Unit,
    val downloadProgress: () -> Int,
    val setDownloadProgress: (Int) -> Unit
) {
    fun checkForUpdate() {
        updateManager.checkForUpdate { info ->
            if (info != null) {
                setUpdateInfo(info)
                setShowDialog(true)
            }
        }
    }
    
    fun startDownload() {
        val info = updateInfo() ?: return
        setIsDownloading(true)
        setDownloadProgress(0)
        
        updateManager.downloadApk(
            downloadUrl = info.downloadUrl,
            onProgress = { progress ->
                setDownloadProgress(progress)
            },
            onComplete = { file ->
                setIsDownloading(false)
                if (file != null && file.exists()) {
                    updateManager.installApk(file)
                    setShowDialog(false)
                }
            }
        )
    }
    
    fun dismiss() {
        setShowDialog(false)
        setUpdateInfo(null)
    }
    
    fun getCurrentVersion(): String {
        return updateManager.getCurrentVersionName()
    }
}
