# StrongZhi Website-Parity Implementation Notes

## Summary
- Goal: align StrongZhi cloud execution with the current website-side ZhengFang experience.
- Scope: website-style `full_chain` task creation, filter semantics, task status, and dry-run submit blocking.
- Non-goal: enabling real terminal submit in this phase.

## Current Validated SDAU Flow
- Login page: `https://jw.sdau.edu.cn/`
- Login submit: `/xk/LoginToXk`
- Login fields: `userAccount`, `userPassword`, `encoded`
- Round discovery: `/xsxk/xklc_list_data`
- Round detail: `/xsxk/newXsxkzx?jx0502zbid=...&isallsc=`
- Frame pages:
  - `selectTable`
  - `selectNum`
  - `selectBottom`

## Website-Parity Rules
- Website/cloud StrongZhi jobs remain `full_chain` only.
- Website/cloud StrongZhi jobs reject swap/drop semantics.
- Website/cloud StrongZhi jobs do not expose local-script-only fields such as exact/direct/manual encrypted identifiers.
- StrongZhi execution returns website-style task lifecycle states:
  - `queued`
  - `scheduled`
  - `running`
  - `success`
  - `failed`
  - `auth_expired`
  - `dry_run_blocked`

## Python Deliverables
- Shared probe foundation in `web-app/src/app/dashboard/card-codes/qz/qz_live_probe_common.py`
- Read-only probes:
  - login
  - round list
  - round detail
  - visible courses
  - submit-chain discovery
- Dry-run prototype:
  - resolves a round
  - parses visible candidates
  - filters by website-style fields
  - reports discovered submit-chain actions
  - blocks before terminal submit

## Go Deliverables
- Request model extensions:
  - `system_type=qz`
  - `system_options.round_name`
  - `system_options.round_id`
  - `system_options.dry_run`
- Response model extensions:
  - `system_type`
  - `round_name`
  - `round_id`
- StrongZhi adapter stages:
  1. fetch rounds
  2. resolve round
  3. fetch round detail
  4. parse visible candidates
  5. match by website filters
  6. discover submit-chain actions
  7. resolve first terminal request candidate
  8. return `dry_run_blocked`

## Current Implementation Status
- Implemented
  - `cloud_orders` 列表卡片显示 `system_type` 与 StrongZhi 轮次标签
  - Web/API round probe routes under `/api/public/qz/rounds` and `/api/qz/rounds`
  - Shared StrongZhi Web types and round loader helper
  - Order-level persistence fields `system_type` and `system_options_json`
  - Public/admin order create and update routes now accept and persist StrongZhi system metadata
  - Go payload submission now forwards `system_type` and `system_options`
  - Go QZ task result now exposes `dry_run_blocked` and `qz_final_request`
  - Go QZ adapter resolves final request candidates from multiple category pages, not just `ggxxkxkOper`
- Verified but blocked
  - SDAU candidate terminal request: `GET /xsxkkc/ggxxkxkOper`
  - StrongZhi cloud execution still blocks before real submit
- In progress
  - Public submit page StrongZhi round selection UI wiring
  - Admin cloud order page StrongZhi round selection and detail echo
  - Python compatibility layer for legacy `manual_qz_*` scripts

## Validation Checklist
- Python probes still succeed against SDAU live pages.
- MCP/browser inspection still confirms the same round labels, frame pages, and visible courses.
- Go tests pass for engine and handlers after `qz` integration.
- No terminal submit request is sent in any StrongZhi path.

## Field Semantics Update
- StrongZhi should no longer be described with the ZhengFang "teaching-class" mental model.
- In compatibility payloads and storage, `class_name` remains unchanged as a field name.
- Semantic split:
  - `zf`: `class_name` means a teaching-class keyword
  - `qz`: `class_name` means an auxiliary keyword
- Current StrongZhi matching dimensions are:
  - course name
  - teacher keyword
  - time keyword
  - auxiliary keyword
- Current StrongZhi auxiliary keyword can match combined row text derived from course name, teacher, category, time, and location.
- Current StrongZhi category text is not yet a real category-page routing filter; it remains an execution grouping/circuit-breaker concept.
