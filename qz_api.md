# StrongZhi Academic System API Technical Notes

This document records a generic StrongZhi-oriented API view for this project. Each section is labeled by confidence level:
- `Verified on SDAU`
- `Observed but not yet generalized`
- `Inferred StrongZhi variant`

## 1. Base Concepts

### 1.1 Session Model
- Confidence: `Verified on SDAU`
- Session is maintained by browser-style cookies.
- Current SDAU cookies observed after login include:
  - `SERVERID`
  - `bzb_jsxsd`
- The Go cloud service expects browser-derived `session_headers`, not raw username/password.
- Current project supports two authentication entry styles at different layers:
  - Python tooling can use password login or cookie-backed session reuse.
  - Go cloud execution consumes uploaded cookie-backed session headers only.

### 1.2 Authentication Modes

#### Password Login
- Confidence: `Verified on SDAU`
- Login page is rendered at `/`.
- The form posts to `/xk/LoginToXk`.
- Current SDAU visible fields:
  - `userAccount`
  - `userPassword`
  - `encoded`
- Current SDAU page-side encoding model:
  1. `encodeInp(userAccount)`
  2. `encodeInp(userPassword)`
  3. `encodeInp(" ")` for the USBKey placeholder path
  4. concatenate with `%%%`
  5. interleave with dynamic page variables `scode` and `sxh`
- USBKey failure at local `127.0.0.1:1300` does not block standard account login on SDAU.
- In this project, password login is currently implemented in the Python `qz` tooling only.

#### Cookie-Backed Session Reuse
- Confidence: `Verified on SDAU`
- A raw browser `Cookie` header can be reused to access the logged-in StrongZhi pages as long as the session is still valid.
- Current project behavior:
  - Python `qz` tooling automatically switches to cookie mode when `QZ_COOKIE_HEADER` is provided.
  - Optional companion headers can be supplied via `QZ_USER_AGENT`, `QZ_REFERER`, and `QZ_ORIGIN`.
  - The Go service continues to use `session_headers` and `/api/cloud-grab/jobs/update-session` for session refresh.
- Failure indicator:
  - cookie-auth requests return login-like HTML instead of `/framework/xsMainV.htmlx`

### 1.3 Frame-Based Page Composition
- Confidence: `Verified on SDAU`
- After entering a round, StrongZhi uses a frame-oriented page rather than a single JSON-heavy course page.
- Current SDAU frame pages include:
  - `selectTable`: visible course grid
  - `selectNum`: round info and exit/round-switch actions
  - `selectBottom`: result/log/info actions

### 1.4 Common Request Headers
- Confidence: `Verified on SDAU`
- Browser-like headers work for SDAU probing:
  - `User-Agent: Mozilla/5.0 ...`
  - `Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8`
- AJAX round-list calls additionally benefit from:
  - `X-Requested-With: XMLHttpRequest`
- Login submit uses:
  - `Content-Type: application/x-www-form-urlencoded`
  - `Origin`
  - `Referer`

## 2. Endpoint Groups

### 2.1 Login Page
- Confidence: `Verified on SDAU`
- URL pattern: `/`
- Method: `GET`
- Response shape:
  - HTML page containing the login form and current `scode` / `sxh` JavaScript variables
- Extraction rules:
  - parse the form action
  - parse `loginMethod`
  - parse `userlanguage`
  - parse `scode`
  - parse `sxh`

### 2.2 Login Submit
- Confidence: `Verified on SDAU`
- URL pattern: `/xk/LoginToXk`
- Method: `POST`
- Request body:
  - `loginMethod`
  - `userlanguage`
  - `userAccount`
  - `userPassword` (cleared before submit on SDAU page)
  - `encoded`
- Success indicator on SDAU:
  - final URL reaches `/framework/xsMainV.htmlx`
- Failure indicator:
  - response remains on login page or returns login-like HTML

### 2.3 Round Discovery
- Confidence: `Verified on SDAU`
- URL pattern: `/xsxk/xklc_list_data`
- Method: `GET`
- Useful header:
  - `X-Requested-With: XMLHttpRequest`
- Response format:
  - JSON envelope with `code` and `data`
- Verified SDAU fields in `data[]`:
  - `xklc_mc`: round name
  - `jx0502zbid`: round id
  - `xnxq01id`: term
  - `xkkssj`: start time
  - `xkjzsj`: end time
  - `xkzt`: round status

### 2.4 Round Entry
- Confidence: `Verified on SDAU`
- URL pattern: `/xsxk/newXsxkzx?jx0502zbid={round_id}&isallsc=`
- Method: `GET`
- Response format:
  - HTML page embedding StrongZhi frame URLs
- Extraction rules:
  - locate `src=".../xsxk/..."` frame URLs
  - normalize `&amp;` to `&`
  - map frame names by the URL basename

### 2.5 Visible Course Table
- Confidence: `Verified on SDAU`
- URL pattern: `/xsxk/selectTable`
- Method: `GET`
- Response format:
  - HTML table
- Verified SDAU extraction rules:
  - parse rows from `<tr>`
  - first cell is the period label
  - following cells represent weekdays in order
  - course cells use `.table-class` with `.title-p` for course name
  - teacher text appears in the same cell
- Verified SDAU normalized fields:
  - `course_name`
  - `teacher`
  - `category`
  - `period`
  - `weekday`

### 2.6 Round Status and Utility Pages
- Confidence: `Verified on SDAU`

#### `selectNum`
- URL pattern: `/xsxk/selectNum?jx0502zbid={round_id}&isallsc=`
- Method: `GET`
- Current SDAU actions observed:
  - button text: `切换选课轮次`
  - button text: `安全退出选课`
  - function: `exitXk`
- Current SDAU paths observed:
  - `/xsxk/xklc_list?isallsc=`
  - `/xsxk/xsxk_exit`

#### `selectBottom`
- URL pattern: `/xsxk/selectBottom?jx0502zbid={round_id}&sfylxkstr=`
- Method: `GET`
- Current SDAU actions observed:
  - button text: `选课结果`
  - button text: `选课/退选日志`
  - functions: `xkrz`, `txrz`, `jpbxxk`, `RefreshParentwindow`
- Current SDAU paths observed:
  - `/xsxk/selectTable`
  - `/xsxk/xsxk_tzsm`

### 2.7 Terminal Submit Chain
- Confidence: `Observed but not yet generalized`
- The current project can discover pre-submit actions and button/function/page structure.
- A first verified SDAU terminal submit request candidate is now modeled in code, but still blocked by dry-run.
- Current project behavior:
  - inspect submit-chain pages
  - capture visible actions and candidate terminal path position
  - resolve the first terminal request candidate as `qz_final_request`
  - stop with `blocked_by_dry_run`

#### SDAU Candidate Final Request
- Confidence: `Verified on SDAU`
- Current modeled terminal request candidates include:
  - `GET /xsxkkc/bxxkOper`
  - `GET /xsxkkc/ggxxkxkOper`
- Current modeled parameter sources:
  - `kcid`: row operation `xsxkFun(..., kcid, ...)`
  - `cfbs`: row operation `xsxkFun(..., cfbs, ...)`
  - `jx0404id`: row operation `xsxkFun(jx0404id, ...)`
  - `xkzy`: row operation or `/xsxkkc/xsxkXkzyview`
  - `trjf`: `xsxkOper` argument
  - `sfsyjc`: `xsxkOper` argument
  - `sfkvtj`: `xsxkOper` argument when present
  - `yxjx0404id`: conflict dialog return
  - `yxcfbs`: conflict dialog return
- Current modeled request headers:
  - `Accept: */*`
  - `Referer: category page URL`
  - `X-Requested-With: XMLHttpRequest`
  - `Origin` and `Content-Type` only when the page function explicitly sets `type: "POST"`

## 3. Reference Alignment Answer

### `JWSystemLib`
- Confidence: `Verified on SDAU` for the comparison, not for its endpoint set
- Reusable idea:
  - same vendor family and similar course-selection intent
- Not aligned on SDAU:
  - older `/jsxsd/...` path assumptions do not match the currently verified SDAU routes
  - current SDAU truth is `/xk/LoginToXk`, `/xsxk/xklc_list_data`, `/xsxk/newXsxkzx?...`
- Conclusion:
  - partially aligned at workflow level
  - not aligned at concrete endpoint-path level

### `CsuftSpiderBackend`
- Confidence: `Verified on SDAU` for the mismatch conclusion
- Its login assumptions center on a different auth chain and deployment model.
- It is not aligned with current SDAU login mechanics.
- Conclusion:
  - useful for reverse-engineering patterns
  - not SDAU-compatible as a direct implementation reference

### CSDN Article
- Confidence: `Verified on SDAU` for the mismatch conclusion
- The article is useful as methodology for tracing hidden fields, redirects, and frontend login logic.
- It is not aligned with SDAU’s currently verified login mechanics or endpoint set.
- Conclusion:
  - useful as reverse-engineering methodology
  - not a literal SDAU API map

### Current SDAU-Compatible Anchors
- Confidence: `Verified on SDAU`
- `GET /`
- `POST /xk/LoginToXk`
- `GET /xsxk/xklc_list_data`
- `GET /xsxk/newXsxkzx?jx0502zbid=...&isallsc=`
- frame pages:
  - `selectTable`
  - `selectNum`
  - `selectBottom`

## 4. Extraction Confidence by Area

### Verified on SDAU
- login page and encoding variables
- login destination and cookie-backed session
- round list endpoint and core round fields
- round detail entry page
- frame names and current utility actions
- visible course parsing from `selectTable`
- cookie-backed session reuse for the current logged-in flow

### Observed but not yet generalized
- exact set of submit-chain pages beyond current SDAU pages
- whether deeper hidden APIs exist behind the visible course grid on other StrongZhi schools
- how many schools reuse the same `xklc_mc` / `jx0502zbid` naming

### Inferred StrongZhi Variant
- some schools may expose different login wrappers, SSO layers, or additional anti-bot gates
- some schools may require extra drill-down pages before a terminal submit action is available
- visible course parsing may need additional category or teacher-label handling on other deployments

## 5. ZhengFang vs StrongZhi Comparison

### Flow Shape
- ZhengFang: largely parameter-driven and JSON-heavy once session is established.
- StrongZhi: frame-oriented page flow with more HTML extraction steps.

### Parameter Discovery
- ZhengFang: dynamic hidden params and encrypted class identifiers are central to final submit.
- StrongZhi: round discovery and frame discovery are the current entry anchors; visible-course parsing is the first stable source.

### Course Matching
- ZhengFang: matching usually depends on course list plus class-detail JSON.
- StrongZhi: current implementation starts from visible grid cells and website-style filters.

### Website-Parity Constraints in This Project
- Both website-side flows are `full_chain` only.
- Both flows use uploaded session headers.
- StrongZhi currently remains dry-run blocked at the terminal submit boundary.

## 6. Open Unknowns
- Full school-wide coverage of every StrongZhi category submit operator beyond the first SDAU candidate `ggxxkxkOper`.
- Whether SDAU exposes an intermediate JSON/class-detail API analogous to ZhengFang detail fetches.
- Whether visible course cells alone are sufficient for all website-side filtering needs on StrongZhi.
- Which parts of the SDAU flow generalize cleanly across other StrongZhi deployments.
