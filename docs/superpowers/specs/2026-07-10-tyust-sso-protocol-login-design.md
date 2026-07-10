# TYUST SSO Protocol Login Design

## Goal

Add password login for Taiyuan University of Science and Technology (`school.id == "tyust"`) by reproducing the university SSO/CAS protocol without WebView automation, then return the authenticated Zhengfang cookie string through the app's existing password-login callback.

Other schools continue to use the existing Zhengfang direct-login implementation.

## Observed Protocol

The protocol was verified against the live university systems with an explicitly authorized test account. No credentials, cookies, CAS tickets, OAuth codes, or per-session flow keys are stored in this document.

### SSO form

1. Start with:

   `GET https://sso1.tyust.edu.cn/login?service=https%3A%2F%2Fnewjwc.tyust.edu.cn%2Fsso%2Fjasiglogin%2Fjwglxt`

2. Preserve the SSO `SESSION` cookie.
3. Parse these values from the HTML:
   - `login-croypto`: Base64-encoded DES key.
   - `login-page-flowkey`: submitted as `execution`.
   - `recaptcha-invisible` and `captcha-url`: optional CAPTCHA state.
4. Encrypt the UTF-8 password with `DES/ECB/PKCS7Padding` using the decoded `login-croypto` key, and Base64-encode the ciphertext.
5. Submit `POST /login` as `application/x-www-form-urlencoded` with:
   - `username`
   - `password` (DES ciphertext)
   - `croypto`
   - `type=UsernamePassword`
   - `_eventId=submit`
   - `geolocation=`
   - `execution`
   - `captcha_code` (empty when not required)

### CAS to Zhengfang

The successful flow is:

1. SSO returns a redirect containing a one-time CAS service ticket.
2. Follow the redirect to `https://newjwc.tyust.edu.cn/sso/jasiglogin/jwglxt?ticket=...`.
3. The Zhengfang bridge redirects through the same service endpoint and then to `/jwglxt/ticketlogin`.
4. Follow redirects to `/jwglxt/xtgl/login_slogin.html` and finally `/jwglxt/xtgl/index_initMenu.html`.
5. Read the final `newjwc.tyust.edu.cn` cookies from the protocol CookieJar and validate them by requesting the existing student-information URL.

## Architecture

### `TyustSsoLoginManager`

A TYUST-specific manager owns one login attempt and its state:

- a standards-compliant in-memory CookieJar;
- the SSO form parser;
- DES password encryption;
- SSO form submission;
- bounded same-site/CAS redirect handling;
- final Zhengfang cookie extraction;
- CAPTCHA refresh and resubmission state.

It implements the existing `PasswordLoginCallback` contract so the UI does not need a TYUST-specific screen.

The manager must use an OkHttp client with normal Android certificate and hostname verification. It must not reuse the existing trust-all `CourseApiClient` client for SSO credentials.

### Routing

`LoginActivity` selects the password-login implementation:

- `school.id == "tyust"`: `TyustSsoLoginManager`.
- every other school: existing `PasswordLoginManager`.

The returned Zhengfang Cookie is passed through the existing validation and student-binding flow. It is persisted only after validation and binding succeed.

### Cookie isolation

SSO and Zhengfang cookies remain in the attempt-local CookieJar until login succeeds. Only cookies matching `newjwc.tyust.edu.cn` are serialized into the result string. SSO `SESSION`, CAS tickets, and portal cookies are never copied into `UserManager`.

Cookie matching must honor host/domain, path, expiry, and Secure attributes.

## Error Handling

The manager maps failures to the existing callback methods:

- invalid username/password or SSO account error: `onInvalidCredentials`;
- CAPTCHA requested: download the image and call `onCaptchaRequired`;
- incorrect CAPTCHA: `onCaptchaInvalid`;
- network, parse, TLS, redirect, or unsupported-flow errors: `onError` with a user-safe message.

Redirects are capped. The manager accepts only HTTPS redirects to the expected SSO and Zhengfang hosts. Missing form keys, missing service tickets, unexpected portal redirects, or a final page that still looks like a login page are failures.

## Security Requirements

- Never log usernames, passwords, encrypted passwords, flow keys, cookies, tickets, codes, or CAPTCHA text.
- Never disable TLS certificate or hostname verification for the SSO login client.
- Never persist the SSO password or SSO cookie.
- Do not send SSO/CAS cookies to the Zhengfang host or vice versa unless allowed by normal cookie matching.
- Do not expose the protocol client through a global singleton.
- Clear attempt state after terminal success or failure.
- Do not include live credentials or captured tokens in test fixtures.

## Testing

### Unit tests

- Parse `login-croypto`, `execution`, and CAPTCHA metadata from sanitized HTML fixtures.
- Reject missing or malformed SSO form parameters.
- Verify DES/ECB/PKCS7 output against a fixed non-secret test vector.
- Map sanitized invalid-credential and CAPTCHA responses to the correct callback result.
- Serialize only matching Zhengfang cookies.

### Mock HTTP tests

Use MockWebServer to verify:

- SSO form GET and POST field names;
- SSO `SESSION` continuity;
- CAS service-ticket redirect chain;
- redirect host allow-list and redirect limit;
- final Zhengfang cookie extraction;
- CAPTCHA request and resubmission;
- no cookie leakage between SSO and Zhengfang hosts.

### Live verification

After automated tests and a debug build pass, perform one explicitly authorized login using runtime-provided test credentials. Verify that:

1. no WebView is opened;
2. the final page is the Zhengfang authenticated index;
3. student information can be parsed;
4. the returned Cookie works with `CourseApiClient`;
5. logs contain no credential, ticket, or Cookie values.

## Non-goals

- Generic SSO support for other schools.
- Automating the Ronghe portal UI.
- Persisting the university SSO session.
- Replacing password login for non-TYUST schools.
- Refactoring unrelated course-selection, schedule, or grade code.
