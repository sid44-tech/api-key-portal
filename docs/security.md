<p align="center"><a href="../README.md"><img src="assets/banner.svg" alt="Key Management Portal" width="60%"></a></p>

<p align="center"><sub><a href="../README.md">🏠 Home</a> · <a href="configuration.md">⚙️ Configuration</a> · <a href="deployment.md">🐳 Deployment</a> · <a href="administration.md">👥 Administration</a> · <a href="keys.md">🔑 Keys</a> · <a href="integrations.md">🔌 Integrations</a> · <a href="app-access.md">🤖 App access</a> · <b>🛡️ Security</b></sub></p>

# 🛡️ Security, logging and the audit trail

## Security notes

- Form login with CSRF protection everywhere; invitation-only accounts; the seeded admin can do
  nothing until its default password is replaced. Invitation tokens: 256 bits, SHA-256 stored,
  24 hours, single use.
- Key values leave the database only through `POST /keys/{id}/value` (`Cache-Control:
  no-store`), owner-only, recorded every time. A POST because reveals are recorded: as a GET,
  another site could fire forged "revealed" entries into the history from a signed-in browser;
  the POST needs the CSRF token only the portal's own pages hold.
- Sign-in lockout: failures counted per account and per address, checked before any password is
  compared, with a message that never says which limit was hit or whether the account exists.
  An account locks only when its failures span `PORTAL_LOCKOUT_MIN_ADDRESSES` addresses, so one
  caller can't lock anyone else out (most damagingly the break-glass super admin) — they lock
  only their own address, while a distributed run still locks its target. A successful sign-in
  clears the account's count, never the address's: one valid login mustn't buy more guesses at
  others. Counters are in memory: a restart clears them, instances count separately.
- SSO never grants super admin, never re-enables a disabled account, and re-checks group
  membership on every sign-in.
- Two-factor authentication for password accounts — see
  [Two-factor authentication](administration.md#two-factor-authentication): replay-proof TOTP, hashed single-use
  recovery codes, wrong codes count towards the lockout, and removing it takes password *and* code.
- Behind a reverse proxy, set `PORTAL_FORWARD_HEADERS=framework` and `PORTAL_SECURE_COOKIE=true`
  — see [Behind a reverse proxy and HTTPS](configuration.md#behind-a-reverse-proxy-and-https) for what goes
  wrong without them.
- Strict Content-Security-Policy (no inline scripts or styles), `X-Frame-Options: DENY`,
  `Referrer-Policy: same-origin`.
- All-in-one image: PostgreSQL on loopback only, nothing runs as root, and it refuses to start
  without `PORTAL_ENCRYPTION_KEY` so a copied volume can't read itself.
- Notes are **not** encrypted. Don't store secrets in them.
- Run it behind HTTPS if it's reachable from anywhere other than localhost.
- Back up the database and the encryption key **separately**, and keep every key a backup was
  written under — see [Backup and restore](configuration.md#backup-and-restore).

## Logging and the audit trail

The lines that matter go to a logger of their own, `portal.audit`, so they can be shipped and
retained separately:

```
INFO [a3f9c2e1 sam@example.com 10.0.0.14] portal.audit : event=key_revealed outcome=ok actor=sam@example.com target="Stripe / Prod secret" detail="Copied to the clipboard"
```

Every line carries the request id (returned to the browser as `X-Request-Id`), who was signed in
and where they called from. Allowed actions are `INFO`, refusals `WARN` — one refusal is a stale
link, a run of them is somebody probing.

| Event | When |
|-------|------|
| `sign_in`, `sign_out`, `sso_sign_in` | Someone arrived or left; refusals say why |
| `account_locked_out`, `address_locked_out` | Too many failed passwords |
| `mfa_enrolled`, `mfa_verified`, `mfa_disabled`, `mfa_reset`, `mfa_recovery_codes_regenerated` | Two-factor authentication: set up, each code checked (refusals say why), switched off, reset by an administrator |
| `key_created`, `key_revealed`, `key_updated`, `key_deleted`, `key_revoked` | Everything that happens to a key |
| `access_denied` | Anything the portal refused |
| `password_change`, `profile_updated`, `account_administered` | Credentials and access changes |
| `invitation_sent`, `invitation_accepted`, `account_created` | How people get in |
| `integration_connected`, `integration_verified`, `integration_removed`, `guided_key_verification` | Provider credentials stored, exercised or rejected |
| `group_created`, `group_deleted`, `sso_configured` | Changes to who can see what |
| `appearance_changed` | The portal's colours changed or reset |
| `request_by_disabled_account` | A session outliving the account being switched off |
| `portal_started` | The settings this instance is running with |

**No key value, password, client secret or invitation token is ever logged, at any level.**
Every entity's `toString()` leaves them out, typed values are escaped so a newline cannot forge
a log line, the reveal endpoint writes its own response body so Spring's converters never hold a
value as something loggable, and the categories that print HTTP bodies are pinned at `INFO`.
`AuditTrailIntegrationTest` reveals a key with logging at `TRACE` and asserts the value appears
nowhere.

Logs go to the console. Standard Spring Boot knobs: `LOGGING_FILE_NAME` to also write a file,
`PORTAL_LOG_LEVEL` (default `INFO`), `PORTAL_LOG_MAX_SIZE` / `_MAX_HISTORY` / `_TOTAL_SIZE` for
rolling. To keep the audit trail somewhere the portal's own administrators cannot edit, point
the `portal.audit` logger at your own appender in a `logback-spring.xml`.
