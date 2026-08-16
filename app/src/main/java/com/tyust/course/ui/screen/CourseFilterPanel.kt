package com.tyust.course.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import com.tyust.course.model.CourseFilter
import com.tyust.course.model.FilterOption
import com.tyust.course.ui.system.GlassLoadingState
import com.tyust.course.ui.system.GlassMaterialRole
import com.tyust.course.ui.system.GlassMaterials
import com.tyust.course.ui.system.GlassRecipe
import com.tyust.course.ui.system.LocalControlBackdrop
import com.tyust.course.ui.system.SystemDivider
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.system.glass.applyChipContentDeformation
import com.tyust.course.ui.system.glass.glassChip
import com.tyust.course.ui.system.glass.glassRim
import com.tyust.course.ui.system.glass.rememberInteractiveOptics
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.isRuntimeShaderTrulySupported
import com.tyust.course.ui.system.rememberGlassAccessibilityMode
import com.tyust.course.ui.theme.NeuPrimary
import com.tyust.course.utils.CourseParser

/**
 * 筛选面板：浮在课程列表之上的玻璃卡。
 *
 * 材质配方直接照 SystemUi.kt 的 SystemDialogContent——它是本仓库"悬浮模态卡"的
 * 标准件，这块面板在语义上就是同一类东西：连续曲率 squircle、
 * matchParentSize 兄弟节点承载玻璃、薄中性表面 + 边缘光，
 * 前景拿到「采样源 + 自身图层」的合成 backdrop 供嵌套控件二次采样。
 *
 * @param sampleBackdrop 「壁纸 + 课程列表」的合成采样源。必须由调用方从**面板之外**
 *        的捕获层传入：面板取包含自己的图层会让 RenderThread 死循环、直接 native 崩溃。
 * @param onDismiss 点面板外或在标题区下滑时回调。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseFilterPanel(
    filter: CourseFilter,
    onFilterChange: (CourseFilter) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    filterCategories: List<CourseParser.FilterCategory> = emptyList(),
    isLoading: Boolean = false,
    emptyMessage: String = "筛选条件加载失败，请下拉刷新重试",
    sampleBackdrop: Backdrop? = null,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accessibility = rememberGlassAccessibilityMode()
    val panelShape = RoundedRectangle(
        cornerRadius = 24.dp,
        style = RoundedCornerStyle.Continuous
    )
    val panelMaterial = remember(accessibility) {
        GlassMaterials.resolve(GlassMaterialRole.Modal, accessibility)
    }
    val glassBackdrop = sampleBackdrop?.takeIf { isBackdropSupported() }
    val surfaceColor = if (isLightTheme) {
        Color(0xFFF4F5F7).copy(alpha = 0.30f)
    } else {
        Color(0xFF1E2024).copy(alpha = 0.34f)
    }
    // 无 backdrop 时（API<31 或运行时降级）玻璃无从谈起，退回一层实面保证可读
    val fallbackSurface = if (isLightTheme) {
        Color(0xFFE9EBEF).copy(alpha = 0.94f)
    } else {
        Color(0xFF25272B).copy(alpha = 0.94f)
    }

    // 面板把自己的渲染结果导出到这一层，供面板上的按钮二次采样
    val panelLayer = rememberLayerBackdrop()
    val nestedBackdrop = if (glassBackdrop != null) {
        rememberCombinedBackdrop(glassBackdrop, panelLayer)
    } else {
        null
    }

    val selectedCount = remember(filter, filterCategories) {
        filterCategories.count { getSelectedKeys(filter, it.paramName).isNotEmpty() }
    }
    // 浮层不再把列表顶下去，于是高度上限可以放宽：原先 52% 是为了给被顶下去的
    // 列表留位置，现在只受"别遮满整屏"约束。
    val contentMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp

    Box(modifier = modifier.fillMaxWidth()) {
        if (glassBackdrop != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .layerBackdrop(panelLayer)
                    .drawBackdrop(
                        backdrop = glassBackdrop,
                        shape = { panelShape },
                        effects = {
                            vibrancy()
                            if (isRuntimeShaderTrulySupported()) {
                                blur(GlassRecipe.DialogBlurDp.dp.toPx())
                                lens(
                                    refractionHeight = GlassRecipe.DialogRefractionHeightDp.dp.toPx(),
                                    refractionAmount = GlassRecipe.DialogRefractionAmountDp.dp.toPx()
                                )
                            } else {
                                blur((GlassRecipe.DialogBlurDp * 2f).dp.toPx())
                            }
                        },
                        highlight = { null },
                        shadow = { Shadow(alpha = panelMaterial.shadowAlpha) },
                        onDrawSurface = { drawRect(surfaceColor) }
                    )
                    // 库的 Highlight shader 只认 CornerBasedShape，连续曲率 squircle 会错位，
                    // 所以边缘光按 outline 自己描
                    .glassRim(panelShape, intensity = 0.9f, isLightTheme = isLightTheme)
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(panelShape)
                    .background(fallbackSurface)
                    .glassRim(panelShape, intensity = 0.7f, isLightTheme = isLightTheme)
            )
        }

        CompositionLocalProviderOrPassThrough(nestedBackdrop) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 抓取条 + 标题：这一段承载下滑关闭。手势只挂在这里而不是整块面板上，
                // 否则会和分类区的纵向滚动抢事件。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(onDismiss) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount > 6f) onDismiss()
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .align(Alignment.CenterHorizontally)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(Capsule())
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                            )
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "筛选条件",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (selectedCount > 0) {
                            SystemStatusBadge(
                                text = "已选 $selectedCount 项",
                                tone = SystemTone.Info
                            )
                        }
                    }
                }

                SystemDivider(alpha = 0.5f)

                when {
                    isLoading && filterCategories.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            GlassLoadingState(text = "正在加载筛选条件…")
                        }
                    }

                    filterCategories.isEmpty() -> {
                        SystemEmptyState(
                            title = "暂无筛选条件",
                            message = emptyMessage,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp, horizontal = 18.dp)
                        )
                    }

                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = contentMaxHeight)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 18.dp, vertical = 4.dp)
                        ) {
                            filterCategories.forEach { category ->
                                val options = category.options.map { FilterOption(it.key, it.label) }
                                val selectedKeys = getSelectedKeys(filter, category.paramName)
                                val isMultiSelect = category.paramName == "skjc_list" ||
                                    category.paramName == "sksj_list"

                                FilterSection(
                                    title = category.name,
                                    options = options,
                                    selectedKeys = selectedKeys,
                                    onToggle = { key ->
                                        val newFilter = if (isMultiSelect) {
                                            updateFilterMulti(filter, category.paramName, key)
                                        } else {
                                            updateFilterSingle(filter, category.paramName, key)
                                        }
                                        onFilterChange(newFilter)
                                    }
                                )
                            }
                        }
                    }
                }

                SystemDivider(alpha = 0.5f)

                // 粘性操作栏：原先是两枚 13sp 的裸 TextButton，在一块玻璃卡上完全不像可点区域
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SystemSecondaryButton(
                        text = "重置",
                        onClick = onClear,
                        modifier = Modifier.weight(1f)
                    )
                    SystemPrimaryButton(
                        text = "应用",
                        onClick = onApply,
                        modifier = Modifier.weight(1.4f)
                    )
                }
            }
        }
    }
}

/** nestedBackdrop 为空时不该把 LocalControlBackdrop 覆盖成 null，那会连退回路径一起打断。 */
@Composable
private fun CompositionLocalProviderOrPassThrough(
    backdrop: Backdrop?,
    content: @Composable () -> Unit
) {
    if (backdrop != null) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalControlBackdrop provides backdrop,
            content = content
        )
    } else {
        content()
    }
}

/** 根据 paramName 获取 CourseFilter 中对应的已选 key 列表 */
private fun getSelectedKeys(filter: CourseFilter, paramName: String): List<String> {
    return when (paramName) {
        "kkbm_id_list" -> filter.kkbmIdList.orEmpty()
        "njdm_id_list" -> filter.njdmIdList.orEmpty()
        "jg_id_list" -> filter.jgIdList.orEmpty()
        "zyh_id_list" -> filter.zyhIdList.orEmpty()
        "kclb_id_list" -> filter.kclbIdList.orEmpty()
        "kcxzdm_list" -> filter.kcxzdmList.orEmpty()
        "kcgs_list" -> filter.kcgsList.orEmpty()
        "jxms_list" -> filter.jxmsList.orEmpty()
        "sksj_list" -> filter.sksjList.orEmpty()
        "skjc_list" -> filter.skjcList.orEmpty()
        "jxbmc_list" -> filter.jxbmcList.orEmpty()
        "cxbj_list" -> filter.cxbjList.orEmpty()
        "yl_list" -> filter.ylList.orEmpty()
        else -> emptyList()
    }
}

/** 单选切换：更新 CourseFilter 中对应 paramName 的字段 */
private fun updateFilterSingle(filter: CourseFilter, paramName: String, key: String): CourseFilter {
    return when (paramName) {
        "kkbm_id_list" -> filter.copy(kkbmIdList = toggleSingle(filter.kkbmIdList, key))
        "njdm_id_list" -> filter.copy(njdmIdList = toggleSingle(filter.njdmIdList, key))
        "jg_id_list" -> filter.copy(jgIdList = toggleSingle(filter.jgIdList, key))
        "zyh_id_list" -> filter.copy(zyhIdList = toggleSingle(filter.zyhIdList, key))
        "kclb_id_list" -> filter.copy(kclbIdList = toggleSingle(filter.kclbIdList, key))
        "kcxzdm_list" -> filter.copy(kcxzdmList = toggleSingle(filter.kcxzdmList, key))
        "kcgs_list" -> filter.copy(kcgsList = toggleSingle(filter.kcgsList, key))
        "jxms_list" -> filter.copy(jxmsList = toggleSingle(filter.jxmsList, key))
        "sksj_list" -> filter.copy(sksjList = toggleSingle(filter.sksjList, key))
        "skjc_list" -> filter.copy(skjcList = toggleSingle(filter.skjcList, key))
        "cxbj_list" -> filter.copy(cxbjList = toggleSingle(filter.cxbjList, key))
        "yl_list" -> filter.copy(ylList = toggleSingle(filter.ylList, key))
        else -> filter
    }
}

/** 多选切换：更新 CourseFilter 中对应 paramName 的字段 */
private fun updateFilterMulti(filter: CourseFilter, paramName: String, key: String): CourseFilter {
    return when (paramName) {
        "sksj_list" -> filter.copy(sksjList = toggleMulti(filter.sksjList, key))
        "skjc_list" -> filter.copy(skjcList = toggleMulti(filter.skjcList, key))
        else -> updateFilterSingle(filter, paramName, key)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(
    title: String,
    options: List<FilterOption>,
    selectedKeys: List<String>,
    onToggle: (String) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 7.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(7.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            options.forEach { option ->
                GlassFilterChip(
                    label = option.label,
                    selected = selectedKeys.contains(option.key),
                    onClick = { onToggle(option.key) }
                )
            }
        }
    }
}

/**
 * 筛选芯片。
 *
 * 刻意走 glassChip（纯边缘光）而不是 adaptiveGlassChip：后者有 backdrop 时走
 * liquidChip，每枚芯片一次 backdrop 采样 + 一次 lens，而一屏筛选项动辄 50+ 枚。
 * 芯片本来就贴在面板玻璃上——玻璃感该由面板负责，芯片只需要边缘光和按压形变。
 */
@Composable
fun GlassFilterChip(
    label: String,
    selected: Boolean,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false
) {
    val optics = rememberInteractiveOptics()
    val accessibility = rememberGlassAccessibilityMode()
    val interactive = onClick != null && !accessibility.reduceMotion
    val shape = Capsule()
    val textColor = if (selected) NeuPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .glassChip(
                shape = shape,
                rimIntensity = if (selected) 1.25f else 1f,
                pressProgress = { optics.pressProgress }
            )
            // 选中态在玻璃之上叠一层主题色，而不是换掉整块表面：
            // 换表面会把边缘光一起盖掉，选中的芯片反而比未选中的更平。
            .then(
                if (selected) {
                    Modifier.background(NeuPrimary.copy(alpha = 0.16f), shape)
                } else {
                    Modifier
                }
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .then(if (interactive) optics.gestureModifier else Modifier)
    ) {
        Text(
            text = label,
            modifier = Modifier
                .padding(
                    horizontal = if (compact) 9.dp else 12.dp,
                    vertical = if (compact) 4.dp else 6.dp
                )
                .graphicsLayer {
                    if (!interactive) return@graphicsLayer
                    applyChipContentDeformation(
                        optics = optics,
                        travelPx = GlassRecipe.ChipDragTravelDp.dp.toPx(),
                        stretch = GlassRecipe.ChipDragStretch,
                        pressDepth = GlassRecipe.ChipIconPressDepth,
                        damping = GlassRecipe.ChipContentDeformDamping
                    )
                },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            fontSize = if (compact) 11.sp else 12.5.sp,
            maxLines = 1
        )
    }
}

/** 单选切换：再次点击取消 */
private fun toggleSingle(current: List<String>?, key: String): List<String>? {
    val list = current.orEmpty().toMutableList()
    return if (list.contains(key)) {
        null // 取消选择
    } else {
        listOf(key) // 替换为新选择
    }
}

/** 多选切换 */
private fun toggleMulti(current: List<String>?, key: String): List<String>? {
    val list = current.orEmpty().toMutableList()
    return if (list.contains(key)) {
        list.remove(key)
        list.ifEmpty { null }
    } else {
        list.add(key)
        list
    }
}
