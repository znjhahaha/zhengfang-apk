# StrongZhi Field Semantics

## Summary

StrongZhi should not reuse the ZhengFang "teaching class" mental model.

The current StrongZhi execution path selects row instances by identifiers such as:

- `jx0404id`
- `kcid`
- `cfbs`
- `xqid`

This means StrongZhi task creation should be described with course-level and row-level filters, not with a guaranteed teaching-class-name field.

## User-facing rules

For `system_type=zf`:

- `class_name` means a teaching-class keyword

For `system_type=qz`:

- `class_name` remains the compatibility field name
- the semantic meaning is "auxiliary keyword"
- it can be used for teacher fragments, location fragments, notes, or other row text
- it does not imply that StrongZhi exposes a stable teaching-class name

## Current StrongZhi matching dimensions

- course name
- teacher keyword
- time keyword
- auxiliary keyword

The current auxiliary keyword is matched against a combined string built from:

- course name
- teacher
- category
- time
- location

## Why this change is needed

- The current StrongZhi Go parser does not expose a stable `jxbmc` teaching-class name.
- The current UI label "教学班关键词" misleads users into expecting ZhengFang-style behavior.
- StrongZhi direct submit depends on row-instance identifiers rather than a named teaching-class object.

## Compatibility policy

- Do not rename `class_name` in public JSON yet.
- Do not migrate the database yet.
- Do reinterpret the field by `system_type`.

This keeps backward compatibility while removing the incorrect user-facing description.
