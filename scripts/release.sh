#!/usr/bin/env bash
#
# 切一个版本，一条命令做完全部前置工作。
#
# 为什么要这个脚本
# ---------------
# 发版原先有两个必须人工保持一致的东西：git tag 和 app/version.properties。
# 两者不一致时流水线会停，而且那个校验排在 assembleRelease 之后，一错就白编译
# 一整轮。更糟的是它只在"打完 tag 推上去"之后才暴露，而这个仓库的规矩是
# 已推送的 tag 不移动（见 CHANGELOG 里 1.0.67 那条），于是一次手滑就要跳一个版本号。
#
# 现在版本号的唯一权威是 tag：CI 从 tag 反写 version.properties（release.yml 的
# Resolve Version From Tag），所以"版本与 tag 不一致"在结构上不可能再发生。
# 这个脚本负责另一半——让仓库里的文件、更新日志、tag 一次性同步好再推出去。
#
# 用法
#   scripts/release.sh 1.0.69          # 本地准备好：改文件、写 CHANGELOG、提交、打 tag
#   scripts/release.sh 1.0.69 --push   # 顺带推送（推 tag 即触发发版流水线）
#
# 不加 --push 时只在本地留下一个提交和一个 tag，撤销代价极低：
#   git tag -d v1.0.69 && git reset --hard HEAD~1
#
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="${1:-}"
PUSH="${2:-}"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '  %s\n' "$*"; }

if [ -z "$VERSION" ] || [ "$VERSION" = "-h" ] || [ "$VERSION" = "--help" ]; then
    # 打印文件开头那段连续的注释，到第一行非注释为止。
    awk 'NR > 2 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "$0"
    exit 0
fi

case "$VERSION" in
    v*) die "版本号不要带 v 前缀，直接写 ${VERSION#v}" ;;
    [0-9]*.[0-9]*.[0-9]*) ;;
    *) die "版本号形如 X.Y.Z，收到: $VERSION" ;;
esac

TAG="v${VERSION}"

# versionCode = patch 位。1.0.x 这 68 个版本一直是这么编的，沿用它对用户端无感。
# 下面那道单调性检查会在这个方案不再成立时（比如真发 1.1.0，patch 归零）当场喊停。
REST="${VERSION#*.}"
CODE="${REST#*.}"

NOTES_FILE="release-notes/${TAG}.md"
VERSION_FILE="app/version.properties"
CHANGELOG="CHANGELOG.md"

echo "== 准备发布 ${TAG}（versionCode=${CODE}）=="

# ── 1. 工作区必须干净 ────────────────────────────────────────────────
# 发版提交里只应该有版本号、更新日志这几样。混进别的改动会让 tag 指向一个
# 没人 review 过的状态。
if ! git diff --quiet || ! git diff --cached --quiet; then
    die "工作区有未提交的改动，先提交或 stash。发版提交只应包含版本号与更新日志。"
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" != "main" ]; then
    note "警告：当前在 ${BRANCH} 而不是 main"
fi

# ── 2. tag 不能已存在 ────────────────────────────────────────────────
# 已推送的 tag 不移动：Gitee 的下载地址按 tag 拼，移动 tag 会让已发出去的
# 链接指向另一个包。要重发就顺延到下一个版本号。
if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
    die "${TAG} 已存在于本地。已发布的 tag 不移动，请顺延到下一个版本号。"
fi
if git ls-remote --exit-code --tags origin "refs/tags/${TAG}" >/dev/null 2>&1; then
    die "${TAG} 已存在于远端。已发布的 tag 不移动，请顺延到下一个版本号。"
fi

# ── 3. versionCode 必须单调递增 ──────────────────────────────────────
# 应用内比较的是 versionCode。它一旦不增，用户会收到一个永远"更新不掉"的提示。
PREV_VERSION="$(
    git tag --list 'v[0-9]*.[0-9]*.[0-9]*' \
        | sed 's/^v//' \
        | sort -t. -k1,1n -k2,2n -k3,3n \
        | tail -1
)"
if [ -n "$PREV_VERSION" ]; then
    PREV_CODE="${PREV_VERSION##*.}"
    if [ "$CODE" -le "$PREV_CODE" ]; then
        die "versionCode 不增：${TAG} 算出 ${CODE}，而上一个 tag v${PREV_VERSION} 是 ${PREV_CODE}。
       versionCode = patch 位这个方案在这里失效了（跨 minor/major 时 patch 会归零）。
       改成 major*10000+minor*100+patch，并同步修改 release.yml 的 Resolve Version From Tag。"
    fi
    note "上一个版本 v${PREV_VERSION}（code ${PREV_CODE}）→ ${TAG}（code ${CODE}）"
fi

# ── 4. 更新日志：没有就生成骨架并停下 ────────────────────────────────
# 不回落到套话。缺日志就是发布准备没做完，与其静默发一版"自动构建发布"给所有
# 用户，不如现在停下来。CI 也会做同样的检查。
if [ ! -f "$NOTES_FILE" ]; then
    mkdir -p release-notes
    cat > "$NOTES_FILE" <<TEMPLATE
# ${TAG}

<!--
更新日志的唯一数据源。CI（.github/workflows/release.yml）从这里读，扇出到：
  - GitHub Release 的正文
  - Gitee version.json 的 releaseNotes 字段

## notes 区块的硬约束

APP 内的更新弹窗（UpdateDialog.kt）用纯 Text 渲染 releaseNotes，不解析 Markdown。
所以 notes 里不要写 #、*、-、\` 之类的标记，写了会原样显示给用户。
每行一条，CI 按行读取。
-->

## notes

## changelog

<!-- 这一段进 CHANGELOG.md 归档，可以用 Markdown。 -->

TEMPLATE
    die "已生成 ${NOTES_FILE}，请填好 '## notes' 区块后重新运行本脚本。"
fi

# 取出 <notes-file> 里某个 "## X" 区块，剥掉 HTML 注释。
#
# 注释剥离要分两步。只写 `/^<!--/,/-->$/d` 会把单行注释 `<!-- x -->` 当成区间开头，
# 而 sed 的区间结束模式只从下一行开始找，于是一路删到文件末尾——'## changelog'
# 区块正好以这样一行开头，整段就没了。先删单行注释，再删跨行区间。
block_body() {
    awk -v want="$1" '
        $0 ~ "^## " want "[[:space:]]*$" { inside = 1; next }
        /^## / { inside = 0 }
        inside { print }
    ' "$NOTES_FILE" \
        | sed -e '/^[[:space:]]*<!--.*-->[[:space:]]*$/d' -e '/^[[:space:]]*<!--/,/-->/d'
}

# notes：与 CI 用同一条管线（release.yml 的 Resolve Release Notes），连去空行都一样。
# 两边写法必须一致，否则本地过了 CI 还会挂。
extract_notes() {
    block_body notes | sed '/^[[:space:]]*$/d'
}

# changelog：要进 CHANGELOG.md，是 Markdown，内部空行必须留着（小标题与列表之间
# 少一行空行，归档出来的段落就和手写的历史版本长得不一样）。只掐掉首尾空行。
extract_changelog() {
    block_body changelog | awk '
        { line[NR] = $0; if ($0 ~ /[^[:space:]]/) { if (!first) first = NR; last = NR } }
        END { for (i = first; i <= last; i++) print line[i] }
    '
}

NOTES="$(extract_notes)"
[ -n "$NOTES" ] || die "${NOTES_FILE} 的 '## notes' 区块是空的。"

# notes 会被 UpdateDialog 当纯文本渲染，Markdown 标记会原样显示给用户。
if printf '%s\n' "$NOTES" | grep -qE '^[[:space:]]*[#*-]|`'; then
    die "'## notes' 里有 Markdown 标记（# * - 或反引号）。它会原样显示在应用内的更新弹窗里。
$(printf '%s\n' "$NOTES" | grep -nE '^[[:space:]]*[#*-]|`' | sed 's/^/       /')"
fi

note "更新日志 $(printf '%s\n' "$NOTES" | wc -l | tr -d ' ') 行，来自 ${NOTES_FILE}"

# ── 5. 写 version.properties ─────────────────────────────────────────
cat > "$VERSION_FILE" <<PROPS
#Release version. 唯一权威是 git tag：CI 会从 tag 反写这个文件
#（release.yml 的 Resolve Version From Tag），所以这里与 tag 不一致也发不错包。
#不要手改：跑 scripts/release.sh X.Y.Z，它会连更新日志和 tag 一起对齐。
VERSION_NAME=${VERSION}
VERSION_CODE=${CODE}
PROPS
note "已写入 ${VERSION_FILE}"

# ── 6. CHANGELOG 归档 ────────────────────────────────────────────────
# CHANGELOG 里自己写着"以 release-notes/vX.Y.Z.md 为唯一数据源，扇出到本文件"，
# 但此前没有任何东西真的做这件事，得靠人手抄。这里把它做实。
CHANGELOG_BLOCK="$(extract_changelog)"
if [ -n "$CHANGELOG_BLOCK" ] && [ -f "$CHANGELOG" ]; then
    if grep -qF "## [${VERSION}]" "$CHANGELOG"; then
        note "CHANGELOG 已有 [${VERSION}] 小节，跳过"
    else
        TODAY="$(date +%F)"
        TMP="$(mktemp)"
        # 插在第一个已存在的 "## [" 之前，也就是文件头说明之后、最新版本之上。
        awk -v ver="$VERSION" -v day="$TODAY" -v block="$CHANGELOG_BLOCK" '
            !done && /^## \[/ {
                printf "## [%s] - %s\n\n%s\n\n", ver, day, block
                done = 1
            }
            { print }
            END { if (!done) printf "\n## [%s] - %s\n\n%s\n", ver, day, block }
        ' "$CHANGELOG" > "$TMP"
        mv "$TMP" "$CHANGELOG"
        note "已在 ${CHANGELOG} 插入 [${VERSION}] - ${TODAY}"
    fi
else
    note "跳过 CHANGELOG（'## changelog' 区块为空）"
fi

# ── 7. 提交并打 tag ──────────────────────────────────────────────────
git add "$VERSION_FILE" "$NOTES_FILE" "$CHANGELOG" 2>/dev/null || git add "$VERSION_FILE" "$NOTES_FILE"
git commit -q -m "release: ${VERSION}" -m "$(printf '%s\n' "$NOTES")"
git tag -a "$TAG" -m "Release ${TAG}"
note "已提交并打好 tag ${TAG}"

if [ "$PUSH" = "--push" ]; then
    git push origin "$BRANCH"
    git push origin "refs/tags/${TAG}"
    echo "== 已推送。tag 推送即触发发版流水线 =="
else
    echo
    echo "本地已就绪，还没有推送。检查无误后："
    echo "  git push origin ${BRANCH} && git push origin refs/tags/${TAG}"
    echo
    echo "想撤销："
    echo "  git tag -d ${TAG} && git reset --hard HEAD~1"
fi
