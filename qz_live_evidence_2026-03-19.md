# StrongZhi Live Evidence

Verified against `https://jw.sdau.edu.cn/` on 2026-03-19 with real SDAU student login.

This note records runtime evidence only. It intentionally excludes account credentials.

## Verified live pages

- Login page: `GET /`
- Login submit: `POST /xk/LoginToXk`
- Round list page: `GET /xsxk/xklc_list`
- Round list data: `GET /xsxk/xklc_list_data?xkmc=`
- Disclaimer check before entering round: `POST /xsxk/mzlist.do`
- Round detail: `GET /xsxk/newXsxkzx?jx0502zbid=<ROUND_ID>&isallsc=`
- Detail frames:
  - `GET /xsxk/selectTable`
  - `GET /xsxk/selectNum?jx0502zbid=<ROUND_ID>&isallsc=`
  - `GET /xsxk/selectBottom?jx0502zbid=<ROUND_ID>&sfylxkstr=`

## Verified round list

- `小语种`
  - round id: `31CA9025BDF0482E86AFF274FA5F5C7B`
  - time: `2026-03-16 10:00 ~ 2026-03-19 10:00`
- `转专业`
  - round id: `AB793798E24646EBBD88C4D9DD049235`
  - time: `2026-03-17 00:00 ~ 2026-03-20 18:00`

Later on the same day, a live re-check showed only `转专业` as the currently visible round for this account, and the page-side status text was `只可退课`.

Round enter button uses inline JS:

```js
jrxk('1', '31CA9025BDF0482E86AFF274FA5F5C7B', '0')
```

Later live re-check:

```js
jrxk('1', 'AB793798E24646EBBD88C4D9DD049235', '0')
```

## Verified linked JS

- `/assets_newL/js/util.js`
- `/assets_newL/js/qzTable.js`
- `/assets_newL/js/qzDialog.js`
- `/assets_newL/js/qzForm.js`

All four are loaded by `newXsxkzx`, `selectTable`, `selectNum`, and `selectBottom`.

## Verified inline functions

### `jrxk`

Observed in `/xsxk/xklc_list`:

```js
function jrxk(type, id, sfxkxm) {
    document.getElementById("type").value = type;
    document.getElementById("id").value = id;
    document.getElementById("sfxkxm").value = sfxkxm;
    $.ajax({
        type: "POST",
        async: true,
        url: "/xsxk/mzlist.do",
        dataType: 'json',
        success: function (data) {
            if (data.success) {
                if (data.istc) {
                    window.qzDialog('免责声明', '/view/mzsq.htmlx?id=' + data.mzsqid + '&cs=1&isxk=1', {
                        id: 'mzsm',
                        area: ['800px', '500px'],
                        btn: [],
                        end: function (data) {
                            if (data == '1') {
                                qxk();
                            } else {
                                let obj = form.val('search')
                                window.reloadTable({tableId: 'dataTable', params: obj, curr: 1});
                            }
                        },
                    });
                } else {
                    qxk();
                }
            } else {
                qxk();
            }
        }, error: function () {
            qxk();
        }
    });
}
```

`qxk()` then navigates:

```js
window.location.href = "/xsxk/newXsxkzx?jx0502zbid=" + id + "&isallsc=";
```

### `changeKbjcms`

Observed in `/xsxk/selectTable`:

```js
function changeKbjcms(t) {
    var url = location.href;
    if (url.indexOf("?") > -1) {
        url = url.substring(0, url.indexOf("?"));
    }
    window.location.replace(url + "?kbjcmsid=" + t);
}
```

### `exitXk`

Observed in `/xsxk/selectNum`:

```js
function exitXk(){
    var rev = eval('(' + $.ajax({
        url:"/xsxk/xsxk_exit",
        data:{
            jx0404id:"1"
        },
        async:false
    }).responseText + ')');
    if(rev.success){
        window.parent.location.href="/xsxk/xklc_list?isallsc=";
    }else{
        alert(rev.message);
    }
}
```

### `xkrz`

Observed in `/xsxk/selectBottom`:

```js
function xkrz() {
    window.qzDialog('选课结果', '/xsxkjg/comeXkjglb?isktx=false', {
        full: false,
        id: '2',
        area: ['1500px', '720px'],
        end: function (){
            if('1' == '0'){
                RefreshParentwindow()
            }
        }
    });
}
```

### `txrz`

Observed in `/xsxk/selectBottom`:

```js
function txrz() {
    window.qzDialog('选课/退选日志', '/xsxkjg/getTkrzList?type=page', {
        full: false,
        id: '2',
        area: ['1400px', '720px'],
    });
}
```

### `jpbxxk`

Observed in `/xsxk/selectBottom`:

```js
function jpbxxk(type) {
    switch (type) {
        case 'xkzx':
            $("#selectTable").attr('src', "/xsxk/xsxk_tzsm");
            break;
        case 'bxxksfkf':
            $("#selectTable").attr('src', "/xsxkkc/getBxxkxx");
            break;
        case 'xxxksfkf':
            $("#selectTable").attr('src', "/xsxkkc/getXxxk");
            break;
        case 'bxqxksfkf':
            $("#selectTable").attr('src', "/xsxkkc/getBxqjxk");
            break;
        case 'ggksfkf':
            $("#selectTable").attr('src', "/xsxkkc/getGgxxk");
            break;
        case 'knjxksfkf':
            $("#selectTable").attr('src', "/xsxkkc/getKnjxk");
            break;
        case 'kzyxksfkf':
            $("#selectTable").attr('src', "/xsxkkc/getKzyxk");
            break;
        case 'fxzyxksfkf':
            $("#selectTable").attr('src', "/xsxkkc/getFxzy");
            break;
        case 'xkzxksfkf':
            $("#selectTable").attr('src', "/xsxkkc/getXkzxk");
            break;
        case 'cxxkkf':
            $("#selectTable").attr('src', "/xsxkkc/comeInCxxk?sfylxkstr=");
            break;
        case 'tyxksfkf':
            $("#selectTable").attr('src', "/xsxkkc/newcomeInTyxk?sfylxkstr=");
            break;
    }
}
```

### `RefreshParentwindow`

Observed in `/xsxk/selectBottom` and nested category pages:

```js
function RefreshParentwindow() {
    parent.document.getElementById("selectTable").src = "/xsxk/selectTable";
}
```

## Verified selectable category under `小语种`

`公共选修` loads `GET /xsxkkc/getGgxxk` and then triggers:

- Data endpoint: `POST /xsxkkc/xsxkGgxxkxk?...`
- Actual selectable rows observed:
  - `BK109014 大学俄语2 张帅臣`
  - `BK109014 大学俄语2 田娜`
  - `BK109016 大学俄语4 田娜`
  - `BK109010 大学日语2 赵娟`
  - `BK109010 大学日语2 卢永妮`
  - `BK109010 大学日语2 杨阳`
  - `BK109012 大学日语4 刘长远`
  - `BK109012 大学日语4 韩霖`

Observed row operation HTML:

```html
<div id="div_202520262014930">
  <a href="javascript:xsxkFun('202520262014930','BK109014','null','002');">选课</a>
</div>
```

## Verified list request object

Captured from live browser runtime while loading `公共选修` list:

- URL:
  - `/xsxkkc/xsxkGgxxkxk?kcxx=&skls=&skxq=&skjc=&endJc=&sfym=false&sfct=true&szjylb=&sfxx=true&skfs=&kctype=`
- Method:
  - `POST`
- Headers:
  - `content-type: application/x-www-form-urlencoded; charset=UTF-8`
  - `origin: https://jw.sdau.edu.cn`
  - `referer: https://jw.sdau.edu.cn/xsxkkc/getGgxxk`
  - `x-requested-with: XMLHttpRequest`
- Body:

```text
sEcho=1&iColumns=13&sColumns=&iDisplayStart=0&iDisplayLength=10&mDataProp_0=kch&mDataProp_1=kcmc&mDataProp_2=xf&mDataProp_3=skls&mDataProp_4=sksj&mDataProp_5=skdd&mDataProp_6=xqmc&mDataProp_7=xkrs&mDataProp_8=syrs&mDataProp_9=skfsmc&mDataProp_10=ctsm&mDataProp_11=szkcflmc&mDataProp_12=czOper
```

## Verified final submit candidate for `公共选修`

The live page function body:

```js
function xsxkOper(jx0404id,xkzy,trjf,kcid,cfbs,sfsyjc){
    var sfyzmxk = document.getElementById("sfyzmxk").value;
    if(sfyzmxk=="1"){
        $("#yzmxkJx0404id").val(jx0404id);
        $("#yzmxkXkzy").val(xkzy);
        $("#yzmxkTrjf").val(trjf);
        $("#yzmxkKcid").val(kcid);
        $("#yzmxkCfbs").val(cfbs);
        verifyCodeDiv();
    }else{
        var yxjx0404id ="";
        var yxcfbs ="";
        var param = "?kcid="+kcid+"&cfbs="+cfbs;
        var rev = eval('(' + $.ajax({
            url:"/xsxkkc/ggxxkxkOper"+param,
            data:{
                jx0404id:jx0404id,
                xkzy:xkzy,
                trjf:trjf,
                sfsyjc: sfsyjc
            },
            async:false
        }).responseText + ')');
        ...
    }
}
```

To avoid any real submission, the browser request was intercepted and aborted client-side before reaching the server. The captured request object was:

- URL:
  - `/xsxkkc/ggxxkxkOper?kcid=BK109014&cfbs=null&jx0404id=202520262014930&xkzy=&trjf=&sfsyjc=`
- Method:
  - `GET`
- Headers:
  - `referer: https://jw.sdau.edu.cn/xsxkkc/getGgxxk`
  - `x-requested-with: XMLHttpRequest`
  - session cookie present
- Body:
  - none
- Capture mode:
  - request intercepted and aborted with browser route blocking
- Result in page:
  - JS then threw because `rev` was undefined after the aborted request

## Parameter provenance for the captured `ggxxkxkOper` request

- `kcid`
  - source: row operation JS `xsxkFun(..., kcid, ...)`
  - example: `BK109014`
- `cfbs`
  - source: row operation JS `xsxkFun(..., cfbs, ...)`
  - example: `null`
- `jx0404id`
  - source: row operation JS `xsxkFun(jx0404id, ...)`
  - example: `202520262014930`
- `xkzy`
  - source: empty string in direct `xsxkFun(... xqid='002')` path, or selected in `/xsxkkc/xsxkXkzyview`
- `trjf`
  - source: function argument, currently empty string in observed `公共选修` flow
- `sfsyjc`
  - source: function argument, currently empty string in observed `公共选修` flow
- `sfyzmxk`
  - source: hidden input `#sfyzmxk`
  - observed value: `0`
  - effect: when `1`, request is not sent immediately and flow switches to captcha path

## Verified branch behavior around `公共选修` submit

### Direct same-campus path

Observed row examples use:

```js
xsxkFun('202520262014930', 'BK109014', 'null', '002')
```

When `xqid == '002'`, live page code shows this flow:

```js
window.qzMessageBox('confirm', "提示：你确认选择当前课程班级？", {
    confirm: (index, layero) => {
        var sfyzmxk = document.getElementById("sfyzmxk").value;
        if (sfyzmxk != "1") {
            $("#div_" + jx0404id).hide();
        }
        xsxkOper(jx0404id, "", "", kcid, cfbs, "");
        return true
    }
});
```

### Non-campus path

For rows where `xqid != '002'`, the page uses an extra confirm step and then still falls through to the same final operator:

```js
function xsxkOper1(jx0404id,kcid,cfbs) {
    window.qzMessageBox('confirm', "提示：你确认选择当前课程班级？", {
        confirm: (index, layero) => {
            var sfyzmxk = document.getElementById("sfyzmxk").value;
            if (sfyzmxk != "1") {
                $("#div_" + jx0404id).hide();
            }
            xsxkOper(jx0404id, "", "", kcid, cfbs, "");
            return true
        }
    });
}
```

This means the current observed `公共选修` final request candidate is still `ggxxkxkOper`, even when the row first goes through a non-campus confirmation branch.

### Optional `选课志愿` path

The same page also contains `openXkzyView` and `ts1`, which open:

- `GET /xsxkkc/xsxkXkzyview`

The live dialog content is:

- `请选择选课志愿`
- choices:
  - `1志愿`
  - `2志愿`

After dialog close, the page feeds the selected志愿 back into:

```js
xsxkOper(jx0404id, xkzy, "", kcid, cfbs, "")
```

So `xkzy` is a real runtime source, even though the directly observed `公共选修` row flow passed an empty string.

## Verified captcha branch for `公共选修`

When hidden input `#sfyzmxk == "1"`, the page does not immediately call the live operator. Instead it stages these values:

- `#yzmxkJx0404id`
- `#yzmxkXkzy`
- `#yzmxkTrjf`
- `#yzmxkKcid`
- `#yzmxkCfbs`

Then it opens:

- `GET /xsxkkc/xsxkVerifyview`

Observed `verifyCodeDiv()` save data:

```js
var data = {
    'yzmjx0404id': yzmjx0404id,
    'yzmXkzy': yzmXkzy,
    'yzmTrjf': yzmTrjf,
    'yzmKcid': yzmKcid,
    'yzmCfbs': yzmCfbs,
    'url': '/xsxkkc/ggxxkxkOper'
};
```

This is strong evidence that the captcha path still targets the same final operator endpoint, just mediated by `/xsxkkc/xsxkVerifyview`.

## Additional provenance notes for `公共选修`

- `xqid`
  - source: row operation JS `xsxkFun(..., xqid)`
  - effect:
    - `002`: direct same-campus confirm path
    - non-`002`: extra cross-campus confirm path
- `xkzy`
  - source can be either:
    - empty string in direct row flow
    - dialog result returned by `/xsxkkc/xsxkXkzyview`
- captcha payload values
  - source: hidden staging fields populated before opening `/xsxkkc/xsxkVerifyview`

## Other category operation endpoints observed in page source

- 必修: `/xsxkkc/bxxkOper`
- 任选: `/xsxkkc/xxxkOper`
- 本学期计划选课: `/xsxkkc/bxqjhxkOper`
- 公共选修: `/xsxkkc/ggxxkxkOper`
- 体育: `/xsxkkc/tyxkOper`
- 退选: `/xsxkjg/xstkOper`
- 冲突复报弹窗:
  - `/xsxkkc/xsxkBxxkCfbs`
  - `/xsxkkc/xsxkXxxkCfbs`
  - `/xsxkkc/xsxkBxqjhxkCfbs`
  - `/xsxkkc/xsxkGgxxkCfbs`
  - `/xsxkkc/xsxkTyxkCfbs`

## Verified hidden/runtime fields on `公共选修` page

- `#jx0404id`
- `#kcid`
- `#cfbs`
- `#yzmxkJx0404id`
- `#yzmxkXkzy`
- `#yzmxkTrjf`
- `#yzmxkKcid`
- `#yzmxkCfbs`
- `#sfyzmxk`
- `#sfxsjszp`
- `#kcxx`
- `#skls`
- `#skxq`
- `#skjc`
- `#endJc`
- `#szjylb`
- `#skfs`

## Runtime blocker in local workspace

Live browser reverse engineering works.

Local code inspection is currently blocked by the terminal wrapper failing to start PowerShell with:

```text
Internal Windows PowerShell error. Loading managed Windows PowerShell failed with error 8009001d.
```

Until that local shell issue is fixed, repo-wide edits cannot be applied safely.

## Later SDAU re-check: current `必修选课` chain

Verified after the round list changed to only `转专业`.

- Category page:
  - `GET /xsxkkc/getBxxkxx`
- List request:
  - `POST /xsxkkc/xsxkBxxk?1=1&kcxx=&skls=&skfs=`
- List request headers:
  - `Accept: */*`
  - `Content-Type: application/x-www-form-urlencoded; charset=UTF-8`
  - `Origin: https://jw.sdau.edu.cn`
  - `Referer: https://jw.sdau.edu.cn/xsxkkc/getBxxkxx`
  - `X-Requested-With: XMLHttpRequest`
- Final submit candidate from current page JS:
  - `GET /xsxkkc/bxxkOper`
- Captured client-constructed request shape using browser interception:
  - `/xsxkkc/bxxkOper?kcid=KCID_SAMPLE&cfbs=null&jx0404id=JX0404_SAMPLE&xkzy=&trjf=&sfsyjc=&sfkvtj=`
- Current parameter provenance:
  - `kcid`: row operation `xsxkFun(..., kcid, ...)`
  - `cfbs`: row operation `xsxkFun(..., cfbs, ...)`
  - `jx0404id`: row operation `xsxkFun(jx0404id, ...)`
  - `xkzy`: row operation or `/xsxkkc/xsxkXkzyview`
  - `trjf`: `xsxkOper` argument
  - `sfsyjc`: `xsxkOper` argument
  - `sfkvtj`: `xsxkOper` argument
- Captcha branch target:
  - `verifyCodeDiv().saveData.url = '/xsxkkc/bxxkOper'`

## March 19, 2026 re-check: page-equivalent login replay and remaining category pages

Re-verified on `2026-03-19` by replaying the current SDAU login page algorithm rather than posting an empty `encoded` value.

- Login page:
  - `GET /`
- Current page-side password flow:
  - reads `scode` and `sxh` from inline JS on `/`
  - applies `encodeInp()` to:
    - `userAccount`
    - `userPassword`
    - single-space USBKey placeholder
  - concatenates the three encoded parts with `%%%`
  - interleaves that string with `scode` by the digit sequence in `sxh`
  - posts:
    - `POST /xk/LoginToXk`
    - `userPassword=` empty
    - `encoded=<interleaved token>`
- Replay result:
  - final URL: `https://jw.sdau.edu.cn/framework/xsMainV.htmlx`
  - subsequent `GET /xsxk/xklc_list_data?xkmc=` returned JSON successfully

### Current active round at re-check time

- `xklc_mc`: `转专业`
- `jx0502zbid`: `AB793798E24646EBBD88C4D9DD049235`

### Current `selectBottom` category keys

Observed from `GET /xsxk/selectBottom?jx0502zbid=AB793798E24646EBBD88C4D9DD049235&sfylxkstr=`:

- `bxxksfkf`
- `xxxksfkf`
- `bxqxksfkf`
- `ggksfkf`
- `knjxksfkf`
- `kzyxksfkf`
- `fxzyxksfkf`
- `xkzxksfkf`
- `cxxkkf`
- `tyxksfkf`
- `xkzx`

### Remaining category operator findings

#### `kzyxksfkf`

- Page:
  - `GET /xsxkkc/getKzyxk`
- Final operator found in live inline JS:
  - `/xsxkkc/fawxkOper`
- Captcha target:
  - `verifyCodeDiv().saveData.url = '/xsxkkc/fawxkOper'`

#### `fxzyxksfkf`

- Page:
  - `GET /xsxkkc/getFxzy`
- Final operator found in live inline JS:
  - `/xsxkkc/fxxkOper`
- Captcha target:
  - `verifyCodeDiv().saveData.url = '/xsxkkc/fxxkOper'`

#### `xkzxksfkf`

- Page:
  - `GET /xsxkkc/getXkzxk`
- Final operator found in live inline JS:
  - `/xsxkkc/xkzxkOper`
- Captcha target:
  - `verifyCodeDiv().saveData.url = '/xsxkkc/xkzxkOper'`

#### `cxxkkf`

- Page:
  - `GET /xsxkkc/comeInCxxk?sfylxkstr=`
- Current response:
  - `系统功能暂未开放`
- HTML markers:
  - `<title>no-open</title>`
  - `.no-per-container`
- Result:
  - discovered category page exists
  - current round does not expose a selectable terminal operator for it

### Current `selectTable` drift for the active round

For the active `转专业` round, the default `GET /xsxk/selectTable` at round entry is no longer a visible course table.

- Observed title:
  - `选课轮次`
- Not present in the default page:
  - `<table>`
  - `<tr>`
  - `<td>`
  - `.title-p`
  - `.table-class`

This is stronger evidence than the earlier note: the current live active round can fail before category-page parsing if the implementation assumes the initial `selectTable` frame is already the visible course grid.

## StrongZhi field semantics

- StrongZhi live selection is tied to row-instance parameters rather than a stable ZhengFang-style teaching-class name.
- The strongest live identifiers observed in row actions and submit requests are:
  - `jx0404id`
  - `kcid`
  - `cfbs`
  - `xqid`
- Based on the current evidence, StrongZhi task matching should be described as:
  - course name
  - teacher
  - time
  - auxiliary keyword
- In compatibility payloads, `class_name` is still retained as a field name.
- For `system_type=qz`, `class_name` should be interpreted as an auxiliary keyword rather than a literal teaching-class name.
