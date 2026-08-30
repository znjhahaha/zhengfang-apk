package com.tyust.course.ui.system.glass

/**
 * API 31/32 折射着色器（OpenGL ES 2.0 / GLSL ES 1.00）。
 *
 * ## 为什么不是 AGSL
 * `RuntimeShader` 是 API 33 平台类、无兼容库。但 **GLSL ES 2.0 自 API 8 就有** ——
 * 这才是 33 以下拿到逐像素着色的正路。早先尝试过用 `Canvas.drawBitmapMesh` 做
 * 几何形变来模拟，那条路走不通：网格只能位移坐标，而玻璃需要逐像素位移与
 * 逐通道色散，这些都无法用顶点位移表达。
 *
 * ## 这是 kyant `RoundedRectRefractionWithDispersion` 的逐行移植
 *
 * 不是"参考"，是**同式**。已在 API 35 上截过库自己的 Glass playground 与
 * LiquidBottomTabs 作为基准图（work/lensprobe/cat_play_view.png、
 * cat_tabs_view.png），逐条对齐过：
 *
 * - **只有位移 + 色散**。没有 Snell、没有 Fresnel、没有 rim、没有高光。
 *   曾经这里有 `u_ior` / `u_normalStrength` 驱动一套双界面 refract()，实测残量
 *   约 1px，而位移项是 11px —— 它对观感的贡献被位移完全盖住，却让方向多了一个
 *   反号的分量。轮廓光由 Compose 侧 Highlight / Shadow / InnerShadow 负责，
 *   着色器里再画一份就是过曝白壳（用户报过"外层有一个壳"）。
 * - **朝内采样**。库把 `refractionAmount` 取负后传入（Lens.kt:49），配上朝外的
 *   SDF 梯度，`coord + d * grad` 净效果是朝**内**采样：把内区内容拉出来压在边缘。
 *   朝外采样会去读元素外面的底图，边缘一圈必然带上外部内容和透明像素 ——
 *   那是之前那道硬黑边的根因。
 * - **梯度用解析式**，不是高度场有限差分。有限差分要 4 次额外 SDF 求值，且在
 *   斜坡内区梯度趋 0 时方向不稳；解析梯度（`gradSdRoundedRect`）恒为单位向量。
 * - **`gradRadius = min(radius * 1.5, min(halfSize))`**：梯度用一个比真实圆角更大
 *   的半径求，边缘方向因此更"圆"，这是库让胶囊两端不出现方向突变的手法。
 *
 * ## 取样源是一张更大的底图
 * 元素（如导航指示器）会滑动，但底图（壁纸 + 隐藏 tint 文字层）是静态的。
 * 所以底图整条上传一次，逐帧只改 [U_SRC_ORIGIN]/[U_SRC_SCALE] 决定采样哪一块，
 * 避免每帧重传纹理。
 */
internal object GlassLensShader {

    const val ATTR_POSITION = "a_pos"

    const val U_TEXTURE = "u_tex"
    const val U_RESOLUTION = "u_res"
    const val U_SRC_ORIGIN = "u_srcOrigin"
    const val U_SRC_SCALE = "u_srcScale"
    const val U_CORNER_RADIUS = "u_radius"
    /** 库的 `refractionHeight`：边缘斜坡宽度（像素） */
    const val U_THICKNESS = "u_thickness"
    /** 库的 `refractionAmount`：位移幅度（像素）。可以**大于**斜坡宽度 */
    const val U_LENS_AMOUNT = "u_lensAmount"
    /** 库的 `chromaticAberration`：无量纲倍数，0 = 关 */
    const val U_DISPERSION = "u_dispersion"
    /** 库的 `depthEffect`：0/1，把梯度混向径向 */
    const val U_DEPTH_EFFECT = "u_depthEffect"
    const val U_VIBRANCY = "u_vibrancy"

    val VERTEX = """
        attribute vec2 a_pos;
        varying vec2 v_uv;
        void main() {
            // a_pos 是 [-1,1] 的全屏四边形；v_uv 是元素自身的 [0,1] 坐标
            v_uv = a_pos * 0.5 + 0.5;
            gl_Position = vec4(a_pos, 0.0, 1.0);
        }
    """.trimIndent()

    val FRAGMENT = """
        precision highp float;

        varying vec2 v_uv;

        uniform sampler2D u_tex;
        uniform vec2 u_res;          // 元素像素尺寸
        uniform vec2 u_srcOrigin;    // 元素左上角在底图中的归一化位置
        uniform vec2 u_srcScale;     // 元素尺寸 / 底图尺寸
        uniform float u_radius;
        uniform float u_thickness;    // 库的 refractionHeight
        uniform float u_lensAmount;   // 库的 refractionAmount
        uniform float u_dispersion;   // 库的 chromaticAberration
        uniform float u_depthEffect;  // 库的 depthEffect（0/1）
        uniform float u_vibrancy;

        // 元素本地 uv -> 底图 uv
        vec2 toSrc(vec2 uv) {
            return u_srcOrigin + uv * u_srcScale;
        }

        float sdRoundedRect(vec2 p, vec2 halfSize, float r) {
            vec2 c = abs(p) - (halfSize - vec2(r));
            return length(max(c, 0.0)) - r + min(max(c.x, c.y), 0.0);
        }

        // SDF 解析梯度，恒为单位向量、**朝外**。与库的 gradSdRoundedRect 同式。
        vec2 gradSdRoundedRect(vec2 p, vec2 halfSize, float r) {
            vec2 c = abs(p) - (halfSize - vec2(r));
            if (c.x >= 0.0 || c.y >= 0.0) {
                return sign(p) * normalize(max(c, 0.0));
            } else {
                // 矩形内区：梯度指向最近的那条边
                float gx = step(c.y, c.x);
                return sign(p) * vec2(gx, 1.0 - gx);
            }
        }

        // 库的 circleMap：x=0 -> 0（轮廓），x=1 -> 1（斜坡内沿）
        float circleMap(float x) {
            return 1.0 - sqrt(max(0.0, 1.0 - x * x));
        }

        vec3 applyVibrancy(vec3 rgb, float sat) {
            float l = dot(rgb, vec3(0.213, 0.715, 0.072));
            return clamp(mix(vec3(l), rgb, sat), 0.0, 1.0);
        }

        // 曾经这里有一层程序化微纹理（12px 周期三角波），已删。
        //
        // 加它的理由是"底图取的是模糊层，折射位移一个均匀场等于没折射"。这个诊断
        // 是对的，但结论错了：真正的原因是**位移量被 clamp 到了库的 1/3**
        // （amount ≤ height×0.5，而库是 amount ≈ height×1.4）。位移够大时，模糊底图
        // 里剩下的大尺度结构（壁纸色区边界）本身就足以显形，不需要伪造高频。
        //
        // 已验证：库自己的 LiquidBottomTabs 采的也是 blur(8dp) 过的 combined
        // backdrop（LiquidBottomTabs.kt:242），它没有任何补纹理的手段。
        //
        // 不要再加回来。伪造的纹理钉在底图坐标上，元素一滑动就会与真实背景相对
        // 滑移，反而暴露破绽。

        void main() {
            vec2 px = v_uv * u_res;
            vec2 halfSize = u_res * 0.5;
            vec2 p = px - halfSize;

            float sd = sdRoundedRect(p, halfSize, u_radius);
            if (sd > 0.0) {
                // 形状外：完全透明，由 Compose 侧的 clip/描边负责轮廓
                gl_FragColor = vec4(0.0);
                return;
            }

            float tw = max(u_thickness, 1.0);

            // 静止态 u_lensAmount 为 0（与库一致：lens 参数全乘 pressProgress）。
            // 此时位移恒为 0，七次采样会读到同一个纹素、权重和为 1、结果等于原图，
            // 只是白花六次采样。直接短路。
            //
            // 注意不能让 glassLens() 在这种情况下整个退化成 no-op：调用方的
            // onDrawBackdrop 在 lensAnchor != null 时不会自己画背景，指望这一层出图。
            if (u_lensAmount <= 0.0) {
                vec3 plain = texture2D(u_tex, toSrc(v_uv)).rgb;
                gl_FragColor = vec4(applyVibrancy(plain, u_vibrancy), 1.0);
                return;
            }

            // 斜坡以内：原样透过，一个采样都不多花。与库的提前返回同构。
            if (-sd >= tw) {
                // 变量名不能叫 flat：GLSL ES 1.00 的保留字（插值限定符），
                // 用了会编译失败 "Illegal use of reserved word"，整条折射静默降级。
                vec3 inner = texture2D(u_tex, toSrc(v_uv)).rgb;
                gl_FragColor = vec4(applyVibrancy(inner, u_vibrancy), 1.0);
                return;
            }

            // d 取**负**，与库 `setFloatUniform("refractionAmount", -refractionAmount)`
            // 一致；配上朝外的梯度，净效果是朝内采样。
            float d = -circleMap(1.0 - (-sd) / tw) * u_lensAmount;

            float gradRadius = min(u_radius * 1.5, min(halfSize.x, halfSize.y));
            vec2 grad = normalize(
                gradSdRoundedRect(p, halfSize, gradRadius)
                    + u_depthEffect * normalize(p + vec2(1e-6, 1e-6))
            );

            vec2 lensOff = d * grad;
            vec2 baseUv = toSrc(v_uv) + lensOff / u_res * u_srcScale;

            // ---- 七波长色散 ----
            // 与 kyant AGSL 的 RoundedRectRefractionWithDispersion 同构：
            // 红/橙/黄/绿/青/蓝/紫各采一次，按权重合成。可见的蓝黄边来自
            // 相邻波长的**权重差**，三通道 ±push 做不出来（那只是 RGB 错位）。
            //
            // 色散量随位置调制：库用 (cx*cy)/(hx*hy)，在四角最大、在两条中轴为 0，
            // 所以蓝黄边只出现在圆角处——这正是 iOS 上看到的分布。
            // 色散量 = 位移量 × 位置调制，与库的 `d * grad * dispersionIntensity`
            // 同构：所以色散只在**有位移的地方**出现（内区位移为 0 ⇒ 无色散），
            // 不需要额外的边缘门控。u_dispersion 是无量纲倍数，不是像素。
            float dispScale = (p.x * p.y) / (halfSize.x * halfSize.y) * u_dispersion;
            vec2 disp = lensOff * dispScale / u_res * u_srcScale;

            vec3 color = vec3(0.0);

            vec3 cRed    = texture2D(u_tex, baseUv + disp).rgb;
            vec3 cOrange = texture2D(u_tex, baseUv + disp * (2.0 / 3.0)).rgb;
            vec3 cYellow = texture2D(u_tex, baseUv + disp * (1.0 / 3.0)).rgb;
            vec3 cGreen  = texture2D(u_tex, baseUv).rgb;
            vec3 cCyan   = texture2D(u_tex, baseUv - disp * (1.0 / 3.0)).rgb;
            vec3 cBlue   = texture2D(u_tex, baseUv - disp * (2.0 / 3.0)).rgb;
            vec3 cPurple = texture2D(u_tex, baseUv - disp).rgb;

            color.r += cRed.r / 3.5;
            color.r += cOrange.r / 3.5;
            color.g += cOrange.g / 7.0;
            color.r += cYellow.r / 3.5;
            color.g += cYellow.g / 3.5;
            color.g += cGreen.g / 3.5;
            color.g += cCyan.g / 3.5;
            color.b += cCyan.b / 3.0;
            color.b += cBlue.b / 3.0;
            color.r += cPurple.r / 7.0;
            color.b += cPurple.b / 3.0;

            color = applyVibrancy(color, u_vibrancy);

            // 这里**没有** rim、高光、Snell、Fresnel，这是刻意的。
            //
            // 库的折射着色器（RoundedRectRefractionWithDispersion）同样只做位移 +
            // 色散：轮廓光交给 Compose 侧的 Highlight / InnerShadow / Shadow，而
            // 调用方已经在 drawBackdrop 上挂了这三个。着色器里再加一份就是画两遍 ——
            // 表现为一圈过曝的白壳，把色散的蓝黄边整个盖掉。
            //
            // 已删除的 uniform，不要再加回来：
            //   u_specular / u_shininess / u_rimStrength / u_lightDir（那圈白壳）
            //   u_ior / u_normalStrength（双界面 Snell，残量 1px vs 位移 11px）
            //
            // 三个通道的权重各自和为 1（r: 3×1/3.5 + 1/7；g: 3×1/3.5 + 1/7；
            // b: 3×1/3.0），所以均匀场进 = 均匀场出，色散不会整体染色。
            gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
        }
    """.trimIndent()
}
