# App download distribution

App delivery; API 3 / SDK 3.2.9. Promote the verified test artifact when the user requests default-channel release. GitHub holds the official immutable APK; free Cloudflare Workers Static Assets holds verified copies. No R2 or paid fallback.

## Credentials

GitHub Actions secrets: existing APK signing secrets (build job only), `APP_UPDATE_SIGNING_KEY` (independent P-256 PEM; metadata job only), `CLOUDFLARE_API_TOKEN` (mirror job only), `GITEE_TOKEN` (explicit Gitee job only). Public variable: `CLOUDFLARE_ACCOUNT_ID`. Never put credentials in this directory or artifacts. The manifest public key and official APK signer fingerprint are public and tracked.

The pinned deployment CLI requires Node.js 22 or newer; all mirror workflows use Node 22.

Cloudflare token: account Workers Scripts Edit and Account Settings Read, zone Workers Routes Edit and Zone Read, scoped to the deployment account and `hidisiwa.xyz`. Custom domain creation may require DNS Edit if the account configuration does not allow Workers custom-domain provisioning; diagnose an actual permission failure before expanding scope. Do not use a Global API Key. The CLI uses a custom domain route and assets only. Test and stable Workers are separate projects.

## Workflows

- `release.yml`: manual signed test build; `reuse_run_id` reuses a successful original build after checking unchanged APK inputs, and `sync_test_gitee` explicitly publishes an isolated Gitee test attachment; no tag or official Release. Test mirror runs three deployment/full-download verification rounds, then publishes `test.json` to the test host and the `updates` branch. `legacy_test_bridge` is an explicit one-time opt-in to offer the verified test APK through old-client version.json with a clear test notice and forceUpdate=false. It does not promote a formal GitHub Release or publish stable signed metadata. Artifact retention seven days, APK compression disabled. Retry the failed mirror job only if build succeeded.
- `promote-release.yml`: after user acceptance and unchanged tested APK inputs are in main, promote by successful test run ID. Download the original artifact, verify receipt/signature/digest/checks, create immutable official tag and Release. No Gradle/signing. Stable mirrors and Gitee small metadata are separate jobs. Attachment upload is off.
- `repair-delivery.yml`: choose `mirrors` or `metadata` and an existing formal tag. No APK build or signing. Older metadata repairs cannot replace a newer version. Metadata repair does not upload an APK.
- `sync-gitee-source.yml`: separately fast-forward main source to Gitee `github-source`, preserving the independent metadata commits on Gitee main. Never force-push or move tags.
- `sync-gitee.yml`: optional Gitee APK attachment only, total three-minute script budget. No version JSON, announcements or source writes.

The old-client Gitee `version.json` migration entry remains on 1.0.98. New clients use signed `stable.json` / `app-update-stable.json`, which can advance independently (including 1.0.99). Promotion defaults `preserve_legacy_entry=true`, writing only the Gitee signed manifest; do not point the legacy file at a different version unless the user requests changing the migration route. Keep the immutable test98 APK while that entry references it.

Publication serializes per channel. GitHub ref updates are non-forced and enforce version/revision monotonicity. A failed/lost upload response is checked against public content before retry, at most one retry. Gitee attachment upload is attempted once. All subprocesses have deadlines; CLI output is captured and redacted, reports contain stage/time/status only.

## Mirror layout

`https://dl-test.hidisiwa.xyz/releases/<version>/<sha256>/app-release.apk`, `/test.json`, `/history.json`, `/index.html`. Stable uses `dl.hidisiwa.xyz` and `/stable.json`. Retain two test builds or three stable versions, plus the immutable test98 migration APK pinned by the public signed `legacy-migration-test.json`. A failed pin download stops staging before deployment; new tests cannot delete the Gitee migration target. Remove the pin only after the legacy migration entry has explicitly moved and old clients no longer need this URL. First stage preserves the old manifest and APK; only a fully verified new APK becomes latest. Files over 25 MiB skip CF; stable publication still requires GitHub plus one fully verified domestic candidate. Missing files return 404, never index HTML. The download site is deployed separately from the plugin portal.

Clients default to the stable channel even when installed through a legacy test migration prompt. Before enabling `legacy_test_bridge`, publish a valid stable manifest for the latest existing official release; an older stable version is a successful check, not an instruction to downgrade. The bridge now refuses migration when the default channel is unavailable. Never silently opt users into the test channel or fall back to unsigned metadata.

For an already published release missing its signed channel, dispatch `release.yml` with `repair_stable_tag=v1.0.97` (or the existing official tag). This mode skips all APK builds, reuses and verifies the official APK, publishes its CF/GitHub signed metadata and a Gitee signed-only copy, and preserves `version.json` and announcements. It can therefore repair the default channel while old clients are receiving a newer explicitly authorized test update. It neither creates a formal release nor merges source branches. This entry is available before the new standalone repair workflow has reached the default branch.

Manifest envelope: schemaVersion 2, keyId, Base64 of original UTF-8 payload, Base64 DER ECDSA signature. Sign/verify exact bytes. Payload binds channel, revision, package/version/minSdk, APK size/SHA-256, HTTPS mirrors, source SHA/build ID, notes/time/forceUpdate. Ordinary forceUpdate is false. APK signer is checked independently from manifest signature.

## Verification

`python3 -m unittest discover -s scripts/tests`; constrained JVM updater/MockWebServer tests; actionlint; portal tests/build and Wrangler dry-run. Real runner stage timings are artifacts. Deduplicated CF deployments may upload no unchanged bytes; report their actual transfer/deployment results without calling them new full uploads. Mainland full-file/evening and Android device acceptance remain separate.
