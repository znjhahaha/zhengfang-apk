#!/usr/bin/env python3
"""Standalone StrongZhi live probe for terminal request discovery.

This script is intentionally standalone so it can be dropped into an
existing QZ toolchain later. It supports:

- password login or cookie-backed session reuse
- round listing and round selection
- entering a selected round
- fetching frame HTML and linked JS
- extracting key inline/linked functions
- discovering list/request endpoints and submit operator candidates
- producing a provenance-oriented JSON report

It does not submit course selections.
"""

from __future__ import annotations

import argparse
import base64
import json
import re
import sys
from dataclasses import asdict, dataclass, field
from html import unescape
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple
from urllib.parse import parse_qsl, urlencode, urljoin, urlparse

import requests


DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/146.0.0.0 Safari/537.36"
)

KEY_FUNCTIONS = [
    "jrxk",
    "changeKbjcms",
    "exitXk",
    "xkrz",
    "txrz",
    "jpbxxk",
    "RefreshParentwindow",
    "xsxkFun",
    "xsxkOper",
    "xsxkOper1",
    "openXkzyView",
    "ts",
    "ts1",
    "verifyCodeDiv",
    "queryKxkcList",
    "xstkOper",
]

PRIORITY_JS = [
    "/assets_newL/js/util.js",
    "/assets_newL/js/qzTable.js",
    "/assets_newL/js/qzDialog.js",
    "/assets_newL/js/qzForm.js",
]


@dataclass
class RoundInfo:
    round_id: str
    round_name: str
    term: str = ""
    start_time: str = ""
    end_time: str = ""
    time_range: str = ""
    status_code: str = ""
    status_text: str = ""
    raw: Dict[str, Any] = field(default_factory=dict)


@dataclass
class RequestParam:
    name: str
    value: Optional[str]
    source: str


@dataclass
class RequestCandidate:
    label: str
    url: str
    method: str
    headers: Dict[str, str]
    params: List[RequestParam]
    js_source: str
    function_name: str
    frame: str
    evidence: str


@dataclass
class CategoryProbe:
    key: str
    page_url: str
    status: str
    operator_url: str = ""
    verify_target_url: str = ""
    notes: List[str] = field(default_factory=list)


@dataclass
class ProbeResult:
    auth_mode: str
    session_valid: bool
    base_url: str
    rounds: List[RoundInfo]
    selected_round: Optional[RoundInfo]
    frame_urls: Dict[str, str]
    js_sources_used: List[str]
    visible_courses: List[str]
    extracted_functions: Dict[str, Dict[str, str]]
    terminal_request_candidates: List[RequestCandidate]
    terminal_request_provenance: Dict[str, Any]
    notes: List[str] = field(default_factory=list)


class ProbeError(RuntimeError):
    pass


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="https://jw.sdau.edu.cn/", help="StrongZhi base URL")
    parser.add_argument("--username", help="Account for password mode")
    parser.add_argument("--password", help="Password for password mode")
    parser.add_argument("--cookie-header", help="Raw Cookie header for cookie mode")
    parser.add_argument("--round-id", help="Preferred round id")
    parser.add_argument("--round-name", help="Preferred round name")
    parser.add_argument("--user-agent", default=DEFAULT_USER_AGENT)
    parser.add_argument("--referer", help="Optional referer override")
    parser.add_argument("--origin", help="Optional origin override")
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--json-indent", type=int, default=2)
    return parser


def strip_text(html: str) -> str:
    html = re.sub(r"<script[\s\S]*?</script>", " ", html, flags=re.IGNORECASE)
    html = re.sub(r"<style[\s\S]*?</style>", " ", html, flags=re.IGNORECASE)
    html = re.sub(r"<[^>]+>", " ", html)
    return re.sub(r"\s+", " ", unescape(html)).strip()


def find_brace_block(text: str, start: int) -> Optional[str]:
    brace = text.find("{", start)
    if brace < 0:
        return None
    depth = 0
    in_single = False
    in_double = False
    in_backtick = False
    escaped = False
    for idx in range(brace, len(text)):
        ch = text[idx]
        if escaped:
            escaped = False
            continue
        if ch == "\\":
            escaped = True
            continue
        if not in_double and not in_backtick and ch == "'" and not in_single:
            in_single = True
            continue
        elif in_single and ch == "'":
            in_single = False
            continue
        if not in_single and not in_backtick and ch == '"' and not in_double:
            in_double = True
            continue
        elif in_double and ch == '"':
            in_double = False
            continue
        if not in_single and not in_double and ch == "`" and not in_backtick:
            in_backtick = True
            continue
        elif in_backtick and ch == "`":
            in_backtick = False
            continue
        if in_single or in_double or in_backtick:
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start : idx + 1]
    return None


def extract_function(text: str, fn_name: str) -> Optional[str]:
    patterns = [
        rf"function\s+{re.escape(fn_name)}\s*\([^)]*\)\s*\{{",
        rf"{re.escape(fn_name)}\s*=\s*function\s*\([^)]*\)\s*\{{",
    ]
    for pattern in patterns:
        match = re.search(pattern, text)
        if not match:
            continue
        block = find_brace_block(text, match.start())
        if block:
            return block
    return None


def extract_script_blocks(html: str) -> List[str]:
    return [m.group(1) for m in re.finditer(r"<script\b[^>]*>([\s\S]*?)</script>", html, re.IGNORECASE)]


def extract_script_srcs(html: str, base_url: str) -> List[str]:
    srcs = []
    for match in re.finditer(r"<script\b[^>]*\bsrc=['\"]([^'\"]+)['\"]", html, re.IGNORECASE):
        srcs.append(urljoin(base_url, unescape(match.group(1))))
    return srcs


def extract_iframe_srcs(html: str, base_url: str) -> Dict[str, str]:
    frames: Dict[str, str] = {}
    for tag_match in re.finditer(r"<iframe\b[^>]*>", html, re.IGNORECASE):
        tag = tag_match.group(0)
        id_match = re.search(r"\bid=['\"]([^'\"]+)['\"]", tag, re.IGNORECASE)
        src_match = re.search(r"\bsrc=['\"]([^'\"]+)['\"]", tag, re.IGNORECASE)
        if not id_match or not src_match:
            continue
        frames[id_match.group(1)] = urljoin(base_url, unescape(src_match.group(1)))
    return frames


def extract_visible_courses_from_select_table(html: str) -> List[str]:
    text = strip_text(html)
    courses: List[str] = []
    for match in re.finditer(r"([\u4e00-\u9fffA-Za-z0-9()（）\-·]+)\s+教师：", text):
        value = match.group(1).strip()
        if len(value) < 2:
            continue
        if value not in courses:
            courses.append(value)
    return courses


def parse_cookie_header(raw_cookie: str) -> Dict[str, str]:
    parts = [part.strip() for part in raw_cookie.split(";") if part.strip()]
    cookies: Dict[str, str] = {}
    for part in parts:
        if "=" not in part:
            continue
        key, value = part.split("=", 1)
        cookies[key.strip()] = value.strip()
    return cookies


def request_json(session: requests.Session, method: str, url: str, **kwargs: Any) -> Any:
    response = session.request(method, url, **kwargs)
    response.raise_for_status()
    return response.json()


def request_text(session: requests.Session, method: str, url: str, **kwargs: Any) -> str:
    response = session.request(method, url, **kwargs)
    response.raise_for_status()
    response.encoding = response.encoding or "utf-8"
    return response.text


def encode_inp(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def extract_login_mixins(html: str) -> Tuple[str, str]:
    scode_match = re.search(r'var\s+scode\s*=\s*"([^"]+)"', html)
    sxh_match = re.search(r'var\s+sxh\s*=\s*"([^"]+)"', html)
    if not scode_match or not sxh_match:
        raise ProbeError("StrongZhi login page no longer exposes scode/sxh")
    return scode_match.group(1), sxh_match.group(1)


def build_encoded_login_token(login_html: str, username: str, password: str, code_dog_sequence: str = " ") -> str:
    scode, sxh = extract_login_mixins(login_html)
    code = "%%%".join((encode_inp(username), encode_inp(password), encode_inp(code_dog_sequence)))
    encoded = ""
    remaining = scode
    for idx, ch in enumerate(code):
        if idx < 55 and idx < len(sxh):
            step = int(sxh[idx])
            encoded += ch + remaining[:step]
            remaining = remaining[step:]
            continue
        encoded += code[idx:]
        break
    return encoded


def resolve_jpbxxk_body(extracted_functions: Dict[str, Dict[str, str]], frame_pages: Dict[str, str]) -> Optional[str]:
    select_bottom = extracted_functions.get("selectBottom", {})
    if "jpbxxk" in select_bottom:
        return select_bottom["jpbxxk"]
    raw_html = frame_pages.get("selectBottom", "")
    if not raw_html:
        return None
    for block in extract_script_blocks(raw_html):
        fn_body = extract_function(block, "jpbxxk")
        if fn_body:
            return fn_body
    return extract_function(raw_html, "jpbxxk")


def is_unavailable_category_page(html: str) -> bool:
    lowered = html.lower()
    return "系统功能暂未开放" in html or "<title>no-open</title>" in lowered or "no-per-container" in html


def resolve_page_function(page_functions: Dict[str, str], page_html: str, fn_name: str) -> Optional[str]:
    if fn_name in page_functions:
        return page_functions[fn_name]
    return extract_function(page_html, fn_name)


class StrongZhiProbe:
    def __init__(
        self,
        base_url: str,
        user_agent: str,
        timeout: float,
        referer: Optional[str] = None,
        origin: Optional[str] = None,
    ) -> None:
        base = base_url.rstrip("/") + "/"
        self.base_url = base
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update(
            {
                "User-Agent": user_agent,
                "Accept": "*/*",
            }
        )
        if referer:
            self.session.headers["Referer"] = referer
        if origin:
            self.session.headers["Origin"] = origin
        self.raw_pages: Dict[str, str] = {}
        self.raw_js: Dict[str, str] = {}

    def set_cookie_header(self, cookie_header: str) -> None:
        for name, value in parse_cookie_header(cookie_header).items():
            self.session.cookies.set(name, value, domain=urlparse(self.base_url).hostname)

    def login_with_password(self, username: str, password: str) -> None:
        login_page_url = urljoin(self.base_url, "/")
        login_page = request_text(self.session, "GET", login_page_url, timeout=self.timeout)
        self.raw_pages["login_page"] = login_page
        login_url = urljoin(self.base_url, "/xk/LoginToXk")
        payload = {
            "loginMethod": "LoginToXk",
            "userlanguage": "0",
            "userAccount": username,
            "userPassword": "",
            "encoded": build_encoded_login_token(login_page, username, password),
        }
        response = self.session.post(login_url, data=payload, timeout=self.timeout, allow_redirects=True)
        response.raise_for_status()
        self.raw_pages["login_response"] = response.text

    def is_session_valid(self) -> bool:
        url = urljoin(self.base_url, "/xsxk/xklc_list_data?xkmc=")
        try:
            response = self.session.get(url, timeout=self.timeout, allow_redirects=True)
        except requests.RequestException:
            return False
        if response.status_code != 200:
            return False
        text = response.text
        if "请先登录系统" in text or "欢迎登录教务系统" in text:
            return False
        try:
            data = response.json()
        except ValueError:
            return False
        return isinstance(data, dict) and "data" in data

    def get_rounds(self) -> List[RoundInfo]:
        url = urljoin(self.base_url, "/xsxk/xklc_list_data?xkmc=")
        payload = request_json(self.session, "GET", url, timeout=self.timeout)
        rounds: List[RoundInfo] = []
        for item in payload.get("data", []):
            time_range = str(item.get("xksj", "")).strip()
            start_time, end_time = split_time_range(time_range)
            rounds.append(
                RoundInfo(
                    round_id=str(item.get("jx0502zbid", "")),
                    round_name=str(item.get("xklc_mc", "")),
                    term=str(item.get("xqmc", "")),
                    start_time=start_time,
                    end_time=end_time,
                    time_range=time_range,
                    status_code=str(item.get("xkzt", "")),
                    status_text=str(item.get("txkzmc", "")),
                    raw=item,
                )
            )
        return rounds

    def select_round(
        self,
        rounds: Sequence[RoundInfo],
        round_id: Optional[str],
        round_name: Optional[str],
    ) -> Optional[RoundInfo]:
        if round_id:
            for round_info in rounds:
                if round_info.round_id == round_id:
                    return round_info
            raise ProbeError(f"Round id not found: {round_id}")
        if round_name:
            for round_info in rounds:
                if round_info.round_name == round_name:
                    return round_info
            raise ProbeError(f"Round name not found: {round_name}")
        return rounds[0] if rounds else None

    def fetch_round_detail(self, round_info: RoundInfo) -> str:
        params = {"jx0502zbid": round_info.round_id, "isallsc": ""}
        url = urljoin(self.base_url, "/xsxk/newXsxkzx?" + urlencode(params))
        html = request_text(self.session, "GET", url, timeout=self.timeout)
        self.raw_pages["newXsxkzx"] = html
        return html

    def fetch_frame_html(self, frame_urls: Dict[str, str]) -> Dict[str, str]:
        pages: Dict[str, str] = {}
        for frame_name, frame_url in frame_urls.items():
            pages[frame_name] = request_text(self.session, "GET", frame_url, timeout=self.timeout)
            self.raw_pages[frame_name] = pages[frame_name]
        return pages

    def fetch_js(self, urls: Iterable[str]) -> Dict[str, str]:
        texts: Dict[str, str] = {}
        for url in urls:
            if url in texts:
                continue
            texts[url] = request_text(self.session, "GET", url, timeout=self.timeout)
        self.raw_js.update(texts)
        return texts


def split_time_range(time_range: str) -> Tuple[str, str]:
    if "~" not in time_range:
        return "", ""
    start, end = time_range.split("~", 1)
    return start.strip(), end.strip()


def collect_function_sources(
    html_pages: Dict[str, str],
    js_texts: Dict[str, str],
    target_functions: Sequence[str],
) -> Dict[str, Dict[str, str]]:
    extracted: Dict[str, Dict[str, str]] = {}
    for page_name, html in html_pages.items():
        page_functions: Dict[str, str] = {}
        for block in extract_script_blocks(html):
            for fn_name in target_functions:
                if fn_name in page_functions:
                    continue
                fn_body = extract_function(block, fn_name)
                if fn_body:
                    page_functions[fn_name] = fn_body
        if page_functions:
            extracted[page_name] = page_functions

    for js_url, text in js_texts.items():
        page_functions = extracted.setdefault(js_url, {})
        for fn_name in target_functions:
            if fn_name in page_functions:
                continue
            fn_body = extract_function(text, fn_name)
            if fn_body:
                page_functions[fn_name] = fn_body

    return {key: value for key, value in extracted.items() if value}


def discover_category_pages(jpbxxk_body: Optional[str], base_url: str) -> Dict[str, str]:
    if not jpbxxk_body:
        return {}
    categories: Dict[str, str] = {}
    for key, endpoint in re.findall(
        r"case\s+'([^']+)'\s*:\s*\$\(\"#selectTable\"\)\.attr\('src',\s*\"([^\"]+)\"",
        jpbxxk_body,
    ):
        categories[key] = urljoin(base_url, endpoint)
    return categories


def build_headers(base_url: str, referer: str, method: str) -> Dict[str, str]:
    headers = {
        "Accept": "*/*",
        "Referer": referer,
        "X-Requested-With": "XMLHttpRequest",
    }
    if method.upper() == "POST":
        headers["Origin"] = base_url.rstrip("/")
        headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
    return headers


def discover_terminal_candidates(
    base_url: str,
    frame_html: Dict[str, str],
    extracted_functions: Dict[str, Dict[str, str]],
    category_pages: Dict[str, str],
) -> List[RequestCandidate]:
    candidates: List[RequestCandidate] = []

    # Known selectBottom functions
    bottom_functions = extracted_functions.get("selectBottom", {})
    if "xkrz" in bottom_functions:
        candidates.append(
            RequestCandidate(
                label="view_result_dialog",
                url=urljoin(base_url, "/xsxkjg/comeXkjglb?isktx=false"),
                method="GET",
                headers=build_headers(base_url, urljoin(base_url, "/xsxk/selectBottom"), "GET"),
                params=[],
                js_source="selectBottom inline script",
                function_name="xkrz",
                frame="selectBottom",
                evidence="window.qzDialog('选课结果', '/xsxkjg/comeXkjglb?isktx=false', ...)",
            )
        )
    if "txrz" in bottom_functions:
        candidates.append(
            RequestCandidate(
                label="view_drop_log_dialog",
                url=urljoin(base_url, "/xsxkjg/getTkrzList?type=page"),
                method="GET",
                headers=build_headers(base_url, urljoin(base_url, "/xsxk/selectBottom"), "GET"),
                params=[],
                js_source="selectBottom inline script",
                function_name="txrz",
                frame="selectBottom",
                evidence="window.qzDialog('选课/退选日志', '/xsxkjg/getTkrzList?type=page', ...)",
            )
        )

    for category_key, page_url in category_pages.items():
        page_name = category_key
        page_functions = extracted_functions.get(page_url, {})
        page_html = frame_html.get(page_url, "")

        for fn_name, fn_body in page_functions.items():
            if fn_name == "queryKxkcList":
                ajax_source = find_ajax_source(fn_body)
                if ajax_source:
                    params = parse_query_params(ajax_source)
                    candidates.append(
                        RequestCandidate(
                            label=f"{category_key}_list_query",
                            url=urljoin(base_url, ajax_source),
                            method="POST",
                            headers=build_headers(base_url, page_url, "POST"),
                            params=[
                                RequestParam(name=name, value=value, source="queryKxkcList() filter state")
                                for name, value in params
                            ],
                            js_source=page_url,
                            function_name="queryKxkcList",
                            frame=page_name,
                            evidence=fn_body,
                        )
                    )
            if fn_name == "xsxkOper":
                oper_url = find_submit_operator(fn_body)
                if not oper_url:
                    continue
                params = discover_submit_param_sources(fn_body)
                candidates.append(
                    RequestCandidate(
                        label=f"{category_key}_submit_operator",
                        url=urljoin(base_url, oper_url),
                        method="GET",
                        headers=build_headers(base_url, page_url, "GET"),
                        params=params,
                        js_source=page_url,
                        function_name="xsxkOper",
                        frame=page_name,
                        evidence=fn_body,
                    )
                )
            if fn_name == "verifyCodeDiv":
                verify_url = find_verify_target(fn_body)
                if verify_url:
                    candidates.append(
                        RequestCandidate(
                            label=f"{category_key}_captcha_submit_target",
                            url=urljoin(base_url, verify_url),
                            method="GET",
                            headers=build_headers(base_url, page_url, "GET"),
                            params=[
                                RequestParam("yzmjx0404id", None, "#yzmxkJx0404id"),
                                RequestParam("yzmXkzy", None, "#yzmxkXkzy"),
                                RequestParam("yzmTrjf", None, "#yzmxkTrjf"),
                                RequestParam("yzmKcid", None, "#yzmxkKcid"),
                                RequestParam("yzmCfbs", None, "#yzmxkCfbs"),
                            ],
                            js_source=page_url,
                            function_name="verifyCodeDiv",
                            frame=page_name,
                            evidence=fn_body,
                        )
                    )

        # Static fallback if some functions were not extracted into page_functions keys.
        for oper_path in sorted(set(re.findall(r"/xsxk(?:kg|kkc)[^\"'\s<>]*Oper[^\"]*", page_html))):
            if not any(candidate.url.endswith(oper_path) for candidate in candidates):
                candidates.append(
                    RequestCandidate(
                        label=f"{category_key}_static_oper_reference",
                        url=urljoin(base_url, oper_path),
                        method="GET",
                        headers=build_headers(base_url, page_url, "GET"),
                        params=[],
                        js_source=page_url,
                        function_name="static_reference",
                        frame=page_name,
                        evidence=oper_path,
                    )
                )

    return dedupe_candidates(candidates)


def inspect_category_pages(
    base_url: str,
    frame_html: Dict[str, str],
    extracted_functions: Dict[str, Dict[str, str]],
    category_pages: Dict[str, str],
) -> List[CategoryProbe]:
    probes: List[CategoryProbe] = []
    for category_key, page_url in category_pages.items():
        page_functions = extracted_functions.get(page_url, {})
        page_html = frame_html.get(page_url, "")
        probe = CategoryProbe(
            key=category_key,
            page_url=page_url,
            status="no_operator_found",
        )

        if is_unavailable_category_page(page_html):
            probe.status = "unavailable"
            probe.notes.append("category page returned unavailable/no-open placeholder")
            probes.append(probe)
            continue

        xsxk_oper = resolve_page_function(page_functions, page_html, "xsxkOper")
        verify_code_div = resolve_page_function(page_functions, page_html, "verifyCodeDiv")
        operator_path = find_submit_operator_path(xsxk_oper or "")
        verify_target = find_verify_target(verify_code_div or "")

        if operator_path:
            probe.operator_url = urljoin(base_url, operator_path)
            probe.status = "resolved"
        if verify_target:
            probe.verify_target_url = urljoin(base_url, verify_target)

        if probe.status == "resolved" and probe.verify_target_url:
            if probe.verify_target_url == probe.operator_url:
                probe.notes.append("captcha target matches resolved operator")
            else:
                probe.notes.append("captcha target differs from resolved operator")
        elif probe.verify_target_url:
            probe.status = "captcha_target_only"
            probe.notes.append("captcha target discovered without resolved xsxkOper")
        else:
            probe.notes.append("no xsxkOper submit operator found in category page")

        probes.append(probe)
    return probes


def find_ajax_source(fn_body: str) -> Optional[str]:
    match = re.search(r"sAjaxSource\"\s*:\s*\"([^\"]+)\"\s*\+\s*param", fn_body)
    if match:
        return match.group(1) + build_query_placeholder(fn_body)
    match = re.search(r"sAjaxSource\"\s*:\s*\"([^\"]+)\"", fn_body)
    if match:
        return match.group(1)
    return None


def build_query_placeholder(fn_body: str) -> str:
    match = re.search(r'var\s+param\s*=\s*"(\?[^"]+)"', fn_body)
    return match.group(1) if match else ""


def find_submit_operator_path(fn_body: str) -> Optional[str]:
    match = re.search(r'url:\s*"([^"]+Oper)"\s*\+\s*param', fn_body)
    if match:
        return match.group(1)
    match = re.search(r'url:\s*"([^"]+Oper[^"]*)"', fn_body)
    return match.group(1) if match else None


def find_submit_operator(fn_body: str) -> Optional[str]:
    operator_path = find_submit_operator_path(fn_body)
    if not operator_path:
        return None
    if re.search(r'url:\s*"([^"]+Oper)"\s*\+\s*param', fn_body):
        return operator_path + "?kcid={kcid}&cfbs={cfbs}&jx0404id={jx0404id}&xkzy={xkzy}&trjf={trjf}&sfsyjc={sfsyjc}"
    return operator_path


def find_verify_target(fn_body: str) -> Optional[str]:
    match = re.search(r"'url'\s*:\s*'([^']+)'", fn_body)
    return match.group(1) if match else None


def discover_submit_param_sources(fn_body: str) -> List[RequestParam]:
    sources = {
        "kcid": "xsxkFun(..., kcid, ...)",
        "cfbs": "xsxkFun(..., cfbs, ...)",
        "jx0404id": "xsxkFun(jx0404id, ...)",
        "xkzy": "xsxkFun/openXkzyView dialog result",
        "trjf": "xsxkOper argument",
        "sfsyjc": "xsxkOper argument",
        "yxjx0404id": "rev.yxjx0404id on conflict path",
        "yxcfbs": "rev.yxcfbs on conflict path",
        "sfkvtj": "xsxkOper argument when present in category variant",
    }
    params: List[RequestParam] = []

    param_parts = re.findall(r'\?([a-zA-Z0-9_]+)=\\"?\s*\+\s*([a-zA-Z0-9_]+)', fn_body)
    seen = set()
    for name, source_var in param_parts:
        if name in seen:
            continue
        seen.add(name)
        params.append(RequestParam(name=name, value=None, source=sources.get(source_var, source_var)))

    for data_name in re.findall(r"([a-zA-Z0-9_]+)\s*:\s*([a-zA-Z0-9_]+)", fn_body):
        _, source_var = data_name
        if source_var in sources and source_var not in seen:
            seen.add(source_var)
            params.append(RequestParam(name=source_var, value=None, source=sources[source_var]))

    for required in ("kcid", "cfbs", "jx0404id", "xkzy", "trjf", "sfsyjc"):
        if required not in seen:
            params.append(RequestParam(name=required, value=None, source=sources[required]))
            seen.add(required)
    return params


def parse_query_params(url_or_path: str) -> List[Tuple[str, Optional[str]]]:
    parsed = urlparse(url_or_path)
    result: List[Tuple[str, Optional[str]]] = []
    for key, value in parse_qsl(parsed.query, keep_blank_values=True):
        result.append((key, value))
    return result


def dedupe_candidates(candidates: Sequence[RequestCandidate]) -> List[RequestCandidate]:
    unique: List[RequestCandidate] = []
    seen = set()
    for candidate in candidates:
        key = (candidate.label, candidate.url, candidate.method, candidate.function_name)
        if key in seen:
            continue
        seen.add(key)
        unique.append(candidate)
    return unique


def shape_frame_and_page_sources(
    base_url: str,
    round_html: str,
    frame_pages: Dict[str, str],
) -> Tuple[List[str], Dict[str, str], Dict[str, str]]:
    page_sources: Dict[str, str] = {"newXsxkzx": round_html}
    page_sources.update(frame_pages)

    frame_urls = extract_iframe_srcs(round_html, base_url)
    js_urls: List[str] = []
    for html in page_sources.values():
        js_urls.extend(extract_script_srcs(html, base_url))

    # Keep order stable and prioritize the known core StrongZhi JS files.
    ordered_js: List[str] = []
    seen = set()
    for priority in PRIORITY_JS:
        url = urljoin(base_url, priority)
        if url not in seen and url in js_urls:
            ordered_js.append(url)
            seen.add(url)
    for url in js_urls:
        if url not in seen:
            ordered_js.append(url)
            seen.add(url)
    return ordered_js, frame_urls, page_sources


def normalize_result(result: ProbeResult) -> Dict[str, Any]:
    data = asdict(result)
    data["rounds"] = [asdict(round_info) for round_info in result.rounds]
    data["selected_round"] = asdict(result.selected_round) if result.selected_round else None
    data["terminal_request_candidates"] = [
        {
            **asdict(candidate),
            "params": [asdict(param) for param in candidate.params],
        }
        for candidate in result.terminal_request_candidates
    ]
    return data


def run_probe(args: argparse.Namespace) -> ProbeResult:
    probe = StrongZhiProbe(
        base_url=args.base_url,
        user_agent=args.user_agent,
        timeout=args.timeout,
        referer=args.referer,
        origin=args.origin,
    )

    auth_mode = "unauthenticated"
    if args.cookie_header:
        probe.set_cookie_header(args.cookie_header)
        auth_mode = "cookie"
    elif args.username and args.password:
        probe.login_with_password(args.username, args.password)
        auth_mode = "password"
    else:
        raise ProbeError("Provide either --cookie-header or --username/--password")

    session_valid = probe.is_session_valid()
    if not session_valid:
        raise ProbeError("Session is not valid after authentication")

    rounds = probe.get_rounds()
    selected_round = probe.select_round(rounds, args.round_id, args.round_name)
    if not selected_round:
        raise ProbeError("No available rounds returned")

    round_html = probe.fetch_round_detail(selected_round)
    frame_urls = extract_iframe_srcs(round_html, probe.base_url)
    frame_pages = probe.fetch_frame_html(frame_urls)
    ordered_js, _, page_sources = shape_frame_and_page_sources(probe.base_url, round_html, frame_pages)
    js_texts = probe.fetch_js(ordered_js)

    extracted = collect_function_sources(page_sources, js_texts, KEY_FUNCTIONS)
    select_table_html = frame_pages.get("selectTable", "")
    visible_courses = extract_visible_courses_from_select_table(select_table_html)

    jpbxxk_body = resolve_jpbxxk_body(extracted, frame_pages)
    category_pages = discover_category_pages(jpbxxk_body, probe.base_url)

    fetched_category_pages: Dict[str, str] = {}
    for category_url in category_pages.values():
        try:
            fetched_category_pages[category_url] = request_text(probe.session, "GET", category_url, timeout=args.timeout)
        except requests.RequestException:
            continue

    category_functions = collect_function_sources({}, fetched_category_pages, KEY_FUNCTIONS)
    combined_extracted = dict(extracted)
    combined_extracted.update(category_functions)

    terminal_request_candidates = discover_terminal_candidates(
        probe.base_url,
        fetched_category_pages,
        combined_extracted,
        category_pages,
    )
    category_scan = inspect_category_pages(
        probe.base_url,
        fetched_category_pages,
        combined_extracted,
        category_pages,
    )

    provenance = {
        "round_list_url": urljoin(probe.base_url, "/xsxk/xklc_list_data?xkmc="),
        "round_detail_url": urljoin(
            probe.base_url,
            "/xsxk/newXsxkzx?" + urlencode({"jx0502zbid": selected_round.round_id, "isallsc": ""}),
        ),
        "frame_urls": frame_urls,
        "category_pages": category_pages,
        "category_scan": [asdict(item) for item in category_scan],
        "html_sources": sorted(page_sources.keys()),
        "js_sources": ordered_js,
        "extracted_function_locations": sorted(combined_extracted.keys()),
    }

    notes = []
    if any(candidate.label.endswith("_submit_operator") for candidate in terminal_request_candidates):
        notes.append("submit operator candidates discovered from category inline JS")
    else:
        notes.append("no submit operator discovered from fetched category pages")
    if visible_courses:
        notes.append("visible courses discovered from selectTable timetable text")

    return ProbeResult(
        auth_mode=auth_mode,
        session_valid=session_valid,
        base_url=probe.base_url,
        rounds=rounds,
        selected_round=selected_round,
        frame_urls=frame_urls,
        js_sources_used=ordered_js,
        visible_courses=visible_courses,
        extracted_functions=combined_extracted,
        terminal_request_candidates=terminal_request_candidates,
        terminal_request_provenance=provenance,
        notes=notes,
    )


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        result = run_probe(args)
    except ProbeError as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False, indent=2), file=sys.stderr)
        return 2
    except requests.RequestException as exc:
        print(json.dumps({"error": f"network request failed: {exc}"}, ensure_ascii=False, indent=2), file=sys.stderr)
        return 3

    print(json.dumps(normalize_result(result), ensure_ascii=False, indent=args.json_indent))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
