# UI105: stable overlays and plugin browser glass

UI105 supersedes the UI104 browser layering described in UI104-COMPONENTS.md. App-internal only; SDK 3.2.8 / API 3 is unchanged.

## Ownership

`GlassWindowHost` owns wallpaper and page sources. `AnchoredGlassOverlay` keeps the trigger in its original layout and mounts only the optical body in the same-window overlay host. The body is never composed inline, including the first open frame, reversal, closing and disposal. The existing connected `SystemPicker` portal and its UI103 recipe/width are retained.

The overlay waits for positive host/anchor/body geometry. Its outside-dismiss regions exclude the live toolbar; the browser header consumes empty toolbar hits. With no host, an ordinary dialog supplies a solid surface and explicitly clears all borrowed backdrop/lens locals. Stable controls and overlays therefore cannot sample their own page subtree.

More, search and filters use one `BrowserPanel` selection. `SystemActionMenu` now supports controlled expanded state and per-action enabled flags with backward-compatible defaults. Accepted actions run once after removal, not during closing. Pending callbacks are not saved across activity recreation.

## Browser layers

The list is captured alone and continues behind the pinned toolbar. The toolbar's sampled slab is a sibling of its foreground. Only the rounded slab and the status-bar frost draw a backing: there is no full-width parent mask. The selected tabs have no extra enclosing track. List state and filtering semantics remain owned by the caller.

`GlassFilterCapsule` provides single-choice, toggle and action semantics. It uses the existing glass edge treatment without creating a separate page readback per chip. Selection changes use local spatial/effect springs; system reduced motion snaps. Labels wrap with font scaling; minimum height is 48 dp. It is an App component, not a new plugin UiNode.

## Segmented controls

Grades and plugin tabs keep `refractLabels=false`. No empty label source is created. The foreground owns exactly one set of text/semantics; the selected capsule retains a visible tint and outline. On API31/32 `GlassLensDrawState` reports actual background painting, including the safe live-source fallback. A pending/detached/failed anchor cannot suppress the ordinary background. The status is observable to invalidate a child display list when the optical frame becomes usable; it only changes on state transitions.

## Diagnostics and limits

Only fixed panel/phase/render-path labels and bounded pixel dimensions are added to local crash metadata at state transitions. No query, URL, plugin data or credentials are recorded. These breadcrumbs do not replace a native stack trace. SIGSEGV cannot be caught with a Kotlin try/catch.

Local tests cover composition ownership, geometry, lifecycle, state and software foreground pixels. They do not establish native GPU stability on HUAWEI PLR-AL00/API31, API32 or Android17. Device acceptance remains required for the reported crash and optical appearance.
