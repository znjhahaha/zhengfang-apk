package com.tyust.course.ui.system.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow

/**
 * API 31/32 折射的**区域**：一块不动的祖先节点，区域内的控件共用它的底图。
 *
 * ## 为什么要有"区域"这一层
 *
 * 底图快照一次约 5ms（见 [GlassLensFreshness]），所以它必须挂在**不随控件移动**
 * 的节点上，区域内的控件滑动时只改采样窗口。但控件自己（一枚圆钮、一个开关滑块）
 * 恰恰是会动的那个，它拿不到合适的挂点 —— 挂点只有它的某个祖先知道。
 *
 * 于是分工：
 * - 祖先调 [rememberGlassLensRegion]，拿到 anchor，挂 [Modifier.glassLensAnchor]；
 * - 控件（[Modifier.liquidChip] 等）从 [LocalGlassLensAnchor] 读，读到就折射。
 *
 * 用 CompositionLocal 而不是给每个控件加参数：`liquidChip` 有 5 个调用点、
 * `LiquidButton` 更多，逐个加参数等于让每个调用点都去回答"我的不动祖先是谁"，
 * 而它们本来就不知道。
 *
 * ## 每个区域的底图必须自己重建
 *
 * 底图要画出控件**实际压着的东西**。而"实际压着的东西"每个区域都不一样：
 * 成绩页的芯片压着 `combined(壁纸, 顶栏玻璃层)`，底栏指示器压着
 * `模糊壁纸 + 轨道色 + tint 文字`。抄邻居的配方就会得到一张错的底图 ——
 * 分段控件那次就是抄了底栏的（blur 8dp + 轨道色），滑块因此发灰发褐。
 * 所以 [rememberGlassLensRegion] 只负责生命周期与重拍时机，
 * **底图内容由调用方给**。
 */
val LocalGlassLensAnchor = staticCompositionLocalOf<GlassLensAnchor?> { null }

/**
 * 与 [LocalGlassLensAnchor] 同一块区域，但底图**预先模糊过**（Modal 档 6dp）。
 *
 * 为什么要分两张：33+ 的折射采的是它自己管线里 lens 那一步的输入。
 * 按钮/圆钮/选择器那一档 `enableBlur = false`，输入是锐利背景；模态面板
 * （弹窗、下拉菜单）是 `vibrancy → blur → lens`，输入是模糊过的背景。
 *
 * 而这层模糊只能烤进底图，不能留在屏幕上那层：屏幕上的 blur 加在 `drawBackdrop`
 * 建的图层里，折射在它**上游**就画完了，那层 blur 没有输入可吃。
 *
 * 两张底图都是壁纸的静态快照，各拍一次，不随交互重拍。
 */
val LocalGlassLensModalAnchor = staticCompositionLocalOf<GlassLensAnchor?> { null }

/**
 * 建一个折射区域。
 *
 * @param tag 诊断标签，只影响探针落盘文件名。
 * @param keys 内容标识。任一个变化就重拍底图（选中项、主题、折叠进度…）。
 * @param freshness 滚动新鲜度。给了就在滚动期间限频重拍、停下补一次。
 * @param drawSource 底图绘制。**必须复现该区域内控件实际采样到的画面**。
 */
@Composable
fun rememberGlassLensRegion(
    tag: String,
    vararg keys: Any?,
    freshness: GlassLensFreshness? = null,
    drawSource: DrawScope.(LayoutCoordinates) -> Unit
): GlassLensAnchor? {
    val anchor = rememberGlassLensAnchor(tag = tag, drawSource = drawSource)
    // keys 是 vararg（Array），直接当 LaunchedEffect 的 key 会按引用比较 ——
    // 每次组合都是新数组，于是每次组合都重拍。转成 List 走结构相等。
    val contentKey = keys.toList()
    LaunchedEffect(anchor, contentKey) { anchor?.invalidate() }
    LaunchedEffect(anchor, freshness) {
        if (anchor == null || freshness == null) return@LaunchedEffect
        // 版本号在 flow **里**读，不在组合期读：它是 mutableIntState，
        // 组合期读等于让整棵子树订阅滚动，滑动时每 throttleMs 重组一次。
        // collectLatest 让新的滚动取消上一次的 delay，只有真正停下的那次走到底。
        snapshotFlow { freshness.version }.collectLatest {
            anchor.invalidate()
            delay(140)
            anchor.invalidate()
        }
    }
    return anchor
}

/**
 * 最常见的那种底图：把一个 [Backdrop] 原样重放。
 *
 * 适用于控件采样的就是某个现成 backdrop、且控件自己不额外加 blur 的场合
 * （API33+ 上 `enableBlur` 为 false 的那些控件都属于这一类）。
 *
 * 控件另有 blur 时不能用这个 —— 底图必须连那层 blur 一起复现，
 * 否则折射里的内容比屏幕上的锐利一档。那种情形用 [drawBlurred] 包一层。
 */
fun DrawScope.drawBackdropSource(
    backdrop: Backdrop,
    density: androidx.compose.ui.unit.Density,
    coords: LayoutCoordinates
) {
    with(backdrop) { drawBackdrop(density, coords, null) }
}

