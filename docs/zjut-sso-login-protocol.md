# 浙江工业大学统一认证 → 正方教务 全流程登录协议

> 2026-08-16 实测整理。全链路（账密登录 → 正方教务可用 Cookie → 业务接口取数）已在真实环境验证通过。
> 对应实现：`app/src/main/java/com/tyust/course/login/ZjutSsoLoginManager.kt`、`ZjutSsoProtocol.kt`。

## 1. 概述与手动流程对照

| 手动流程（浏览器） | 程序化协议 |
|---|---|
| 打开 `https://oauth.zjut.edu.cn/cas/login` 输入学号/密码登录 | 阶段一：CAS 账密登录（RSA 加密 POST） |
| 登录后跳到综合服务网（`www.me.zjut.edu.cn/personal-center`） | **可跳过**（该跳转只是 CAS 的默认落点） |
| 点击"推荐应用 → 本科教务管理系统"，新标签页打开正方教务 | 阶段二：`cas/login?service=…/sso/zfiotlogin` 换取正方会话 |
| 在正方教务内操作 | 阶段三：带 `JSESSIONID` 调用正方接口 |

门户卡片本质就是携带 `service=http://www.gdjw.zjut.edu.cn/sso/zfiotlogin` 的 CAS 跳转，**程序化实现不需要经过门户**。

## 2. 角色与域名

| 域名 | 角色 | 协议 |
|---|---|---|
| `oauth.zjut.edu.cn` | 统一身份认证（正方定制 CAS，票据后缀 `-zfsoft.com`） | HTTPS |
| `www.me.zjut.edu.cn` | 综合服务网门户（CAS 保护） | HTTP |
| `www.gdjw.zjut.edu.cn` | 正方教务系统（`/jwglxt`） | **HTTP 明文** |

关键 Cookie：

| Cookie | 域 | 作用 |
|---|---|---|
| `JSESSIONID` | oauth.zjut.edu.cn | CAS WebFlow 会话（`Path=/cas`） |
| `iPlanetDirectoryPro` | oauth.zjut.edu.cn | **CAS SSO 会话**（OpenAM 风格，相当于 TGC；登录成功后种下） |
| `JSESSIONID` | www.gdjw.zjut.edu.cn | **正方教务会话**（最终交付物，`Path=/jwglxt`） |
| `route` | www.gdjw.zjut.edu.cn | 负载均衡粘性路由（与 JSESSIONID 一起保留） |

## 3. 阶段一：CAS 账密登录

### 3.1 GET `https://oauth.zjut.edu.cn/cas/login`

- 响应 200，`Set-Cookie: JSESSIONID=<id>.cas10X; Path=/cas; HttpOnly`
- HTML 表单 `fm1`（method=POST action=`/cas/login`），需要的隐藏域：
  - `execution`：Spring WebFlow 令牌（约 4800 字符，**每次 GET 刷新**）
  - `_eventId=submit`
- 表单字段：`username`、`password`（提交加密值）、`authcode`（验证码，需要时）、`rememberMe`

### 3.2 GET `/cas/v2/getPubKey`

响应 JSON（相对 `/cas/` 解析）：

```json
{"modulus":"b1d2af…5d","exponent":"10001"}
```

**公钥每次请求会变化，且位数不固定**（实测出现过 496/504 位），块长必须按当次公钥动态计算。

### 3.3 GET `/cas/v2/getKaptchaStatus`

- body 为 `true`/`false`。`true` 时需验证码：图片在 `GET /cas/kaptcha?time=<毫秒时间戳>`（与 JSESSIONID 绑定），提交字段 `authcode`。
- 当前实测为 `false`；短时间反复登录失败后可能开启。

### 3.4 密码加密（登录页 `js/login/security.js`，David Shapiro RSA）

```
chunkSize = 2 × (模数的16位数字个数 - 1)        // 504位密钥 → 62 字节
units     = reverse(密码) 的 UTF-16 码元序列     // 先反转！
units     补 0 至 chunkSize 整数倍
每个 chunk（chunkSize 字节）:
    digit[j] = units[2j] + units[2j+1]·256      // 两码元组成小端 16 位数字
    m        = Σ digit[j]·65536^j               // 数字按 2^16 进位（纯 ASCII 时等价于小端字节序）
    c        = m^e mod n                        // 无任何填充
输出 = hex(c)（各块以空格连接，短密码只有一块）
```

Java/Kotlin 一行核心：`BigInteger(块内小端值).modPow(e, n).toString(16)`。

### 3.5 POST `/cas/login`

```
Content-Type: application/x-www-form-urlencoded
Referer: https://oauth.zjut.edu.cn/cas/login
Origin:  https://oauth.zjut.edu.cn

username=302024571057&password=<hex>&execution=<token>&_eventId=submit
```

**成功**：`302`，`Location: http://www.me.zjut.edu.cn/personal-center`（无需跟随），响应头种下：

```
Set-Cookie: iPlanetDirectoryPro=<token>; Domain=oauth.zjut.edu.cn; Path=/
Set-Cookie: ysydOtp=<学号>; Domain=oauth.zjut.edu.cn; Path=/
Set-Cookie: _pf0=…; _pc0=…; _syz=…（辅助 cookie，可不存）
```

**失败分支**：
- 密码错误：`200` 登录页重渲染，`#errormsg` 含"用户名或密码"类文案
- **`302` → `Location: /cas/login?exception.message=Error+decoding+flow+execution`**：CAS 多节点 WebFlow 状态不同步的偶发错误（实测约 1/4 概率），**重新 GET 登录页取新 execution 再 POST 即可**，无需用户介入
- 表单过期：200 且返回了新的 execution（页面刷新），用新 token 重提交

## 4. 阶段二：换取正方教务会话（核心）

**service 必须精确为 `http://www.gdjw.zjut.edu.cn/sso/zfiotlogin`**（来源：正方登录页 `login_slogin.html` 隐藏域 `authJwglxtLoginURL`）。用 `/jwglxt/` 根路径做 service 虽然也能出票，但正方侧不会自动登录，会掉回 `login_slogin.html`。

带上 `iPlanetDirectoryPro`，手动跟随下列跳转（`followRedirects(false)`）：

```
[1] GET https://oauth.zjut.edu.cn/cas/login?service=http%3A%2F%2Fwww.gdjw.zjut.edu.cn%2Fsso%2Fzfiotlogin
    → 302 Location: http://www.gdjw.zjut.edu.cn/sso/zfiotlogin?ticket=ST-24841-JmFdTyrbGLgMaiei75hy-zfsoft.com

[2] GET /sso/zfiotlogin?ticket=ST-…
    → 302 Location: /sso/zfiotlogin
    → Set-Cookie: JSESSIONID=<中转会话>          （/sso 上下文，非最终会话）

[3] GET /sso/zfiotlogin
    → 302 Location: /jwglxt/ticketlogin?uid=302024571057&timestamp=1786878938&verify=7FB159A4B9885189247896927A246BA8
                                                   （uid=学号；verify 为服务端签名，一次性，客户端无需构造）

[4] GET /jwglxt/ticketlogin?uid=…&timestamp=…&verify=…
    → 302 Location: /jwglxt/xtgl/login_slogin.html
    → Set-Cookie: JSESSIONID=<正方会话ID>; Path=/jwglxt   ★ 最终交付物
    → Set-Cookie: route=<…>; rememberMe=deleteMe           （rememberMe 可丢弃）

[5] GET /jwglxt/xtgl/login_slogin.html
    → 302 Location: /jwglxt/xtgl/index_initMenu.html?jsdm=xs&_t=<ts>&echarts=1

[6] GET /jwglxt/xtgl/index_initMenu.html?jsdm=xs…
    → 200 已登录主页
```

交付的 Cookie 头：`JSESSIONID=<id>; route=<id>`（过滤 `rememberMe=deleteMe`）。

## 5. 阶段三：会话使用与验证（实测记录）

| 接口 | 结果 |
|---|---|
| `GET /jwglxt/xtgl/index_cxYhxxIndex.html?xt=jw&localeKey=zh_CN&_=<ts>&gnmkdm=index` | ✅ 200，返回 `h4.media-heading`（姓名 + "学生"）、学院班级、学号 —— **app 的 `validateCookie`/CookieWatchdog/`CourseParser.parseStudentName` 全部兼容** |
| `POST /jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151`（body: `xnm=2025&xqm=3`） | ✅ 200 课表 JSON，`xsxx` 含姓名/学号/班级/专业等 |
| `POST /jwglxt/xjgl/xscx/xsxxall_cxXsgrxx.html?gnmkdm=N105515` | ❌ 404（该校版本无此接口，app 未使用，仅记录） |

会话失效特征：正方将请求重定向回 `login_slogin.html`，HTML 含"用户登录"等标记 —— 与现有看门狗检测逻辑一致。

## 6. 已知坑与注意事项

1. **`Error decoding flow execution`**：CAS 集群偶发，登录实现必须自动重取 execution 重试（≤2 次）。
2. **公钥轮换且位数不定**：chunkSize 必须由当次 modulus 动态计算，不可写死 1024 位。
3. **service 精确匹配**：必须是 `…/sso/zfiotlogin`，`/jwglxt/` 或 `index_initMenu` 直连均无法自动登录。
4. **`iPlanetDirectoryPro` 是会话级 Cookie**（无 Max-Age）：进程内可复用做静默续期（直接重跑阶段二换新 JSESSIONID，无需重新输密码）；跨进程需完整重登。当前 app 实现采用 SessionRenewer 全量重登（与 TYUST 一致），续期优化留作后续。
5. **验证码开关**由 `v2/getKaptchaStatus` 服务端控制，图片绑定 JSESSIONID；`kaptcha` 接口相对 `/cas/` 解析。
6. **正方域为 HTTP 明文**，OkHttp 白名单需允许 `http://www.gdjw.zjut.edu.cn`（CAS 域仍强制 HTTPS）。
7. 短时间高频登录可能触发限流/验证码，客户端应控制登录频率（SessionRenewer 的单飞机制已覆盖）。

## 7. 客户端实现要点（zfapk 对应关系）

- `ZjutSsoProtocol.kt`：execution 解析（Jsoup）、公钥 JSON 解析、无填充 RSA（`BigInteger.modPow`，块内小端组数、块间空格连接）
- `ZjutSsoLoginManager.kt`：独立 OkHttpClient + `followRedirects(false)` + `MatchingMemoryCookieJar`；流程 = 登录页 → 公钥 → 验证码开关 →（验证码）→ POST → 校验 `iPlanetDirectoryPro` → service 登录六跳 → 提取 gdjw 域 Cookie
- 安全：`isAllowed` 白名单 = `oauth.zjut.edu.cn`(https) ∪ `www.gdjw.zjut.edu.cn`(http)；结束即 `eraseSensitiveState`（密码清零、Cookie 清空）
- 接入点：`PasswordLoginGatewayFactory.create` 中 `school.id == "zjut"` 分支；UI/SessionRenewer/CookieWatchdog/CredentialStore 零改动复用
