package com.tyust.course.ui.screen

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.model.CourseFilter
import com.tyust.course.model.FilterOption
import com.tyust.course.ui.theme.NeuPrimary
import com.tyust.course.utils.CourseParser

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
    modifier: Modifier = Modifier
) {
    // 玻璃卡：半透白 + 细边框圆角，与整体液态风格衔接（半透面必须关 elevation 阴影）
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.White.copy(alpha = 0.55f),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.60f)),
        shadowElevation = 0.dp
    ) {
        val contentMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.52f).dp
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "筛选条件",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onClear) {
                        Text("重置", fontSize = 13.sp)
                    }
                    TextButton(onClick = onApply) {
                        Text("应用", fontSize = 13.sp, color = NeuPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Spacer(modifier = Modifier.height(6.dp))

            if (filterCategories.isEmpty()) {
                Text(
                    text = if (isLoading) "正在加载筛选条件..." else emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = contentMaxHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    filterCategories.forEach { category ->
                        val options = category.options.map { FilterOption(it.key, it.label) }
                        val selectedKeys = getSelectedKeys(filter, category.paramName)
                        val isMultiSelect = category.paramName == "skjc_list" || category.paramName == "sksj_list"

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
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(5.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { option ->
                val selected = selectedKeys.contains(option.key)
                FilterChip(
                    label = option.label,
                    selected = selected,
                    onClick = { onToggle(option.key) }
                )
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (selected) NeuPrimary.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.50f)
    val textColor = if (selected) NeuPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (selected) NeuPrimary.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.55f)

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = bgColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            fontSize = 12.sp
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