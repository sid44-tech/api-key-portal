# API Key Portal

A small self-hosted web portal for keeping track of the API and access keys a group of people
use across different services — a team, a company, or a family.

- Every key your group holds in one place: service, owner, created and expiry dates, notes
- Reveal or copy a key's value on demand — owner-only, never rendered into pages, always recorded
- Values encrypted at rest with AES-256-GCM, with key rotation built in
- Create keys directly in a connected provider (Azure AD, Google Cloud, AWS, GitLab, Cloudflare),
  or through guided walk-throughs for providers without a key-creation API (GitHub, Anthropic,
  Atlassian, OpenAI, Stripe), each pasted key verified live before it's stored
- Email reminders a month, two weeks, a week and a day before a key expires, and Microsoft Teams
  or Slack channels per group for expiries, failed replacements and sign-in lockouts
- Automatic replacement of expiring provisioned keys, opt-in per provider or per key; for Google
  Cloud and AWS, whose keys never expire on their own, the portal also deletes the replaced key
  at the provider — which is what makes "expired" true there
- Accounts, groups and roles; invitation-only sign-up; two-factor authentication with any
  authenticator app, optionally required for everyone; single sign-on via OpenID Connect
- A per-group dashboard of what needs attention: keys expiring soon, failed automatic
  replacements, and keys nobody has revealed in a month
- Admin-only history of who did what, mirrored to an audit log; sign-in lockout against
  password guessing
- Search, filter, sort; light and dark themes; a super admin can set the portal's name, logo and
  colours; phone-friendly lists
- Runs as a plain Spring Boot jar, as a container against your own database, or as a single
  container with PostgreSQL inside it

Built with Java 25, Spring Boot 4.1, Spring Security, Spring Data JPA, Flyway, Thymeleaf and Bootstrap 5.

## Running locally

Requires JDK 25.

1. Copy `.env.example` to `.env` and set at least `PORTAL_ENCRYPTION_KEY`
   (`openssl rand -base64 32`). `.env` is git-ignored; never commit it.
2. `./mvnw spring-boot:run`
3. Open <http://localhost:8080> and sign in as `admin@localhost` / `changeme`. The portal makes
   you replace that password before anything else works. Then invite everyone else from
   **Invitations → Invite user** — nobody can sign themselves up.

Data lives in a SQLite file at `./keyportal.db` by default; set `PORTAL_DB_PATH` to move it, or
use PostgreSQL (below).

## Configuration

Settings come from environment variables or a `.env` file in the working directory (real
environment variables win). Only `PORTAL_ENCRYPTION_KEY` is required; the app refuses to start
without it.

| Environment variable          | Description                                                             |
|-------------------------------|-------------------------------------------------------------------------|
| `PORTAL_ENCRYPTION_KEY`       | Base64 encoded 256-bit AES key. Generate with `openssl rand -base64 32` |
| `PORTAL_ENCRYPTION_KEYS_<ID>` | Additional named keys, for rotation — see below                         |
| `PORTAL_ENCRYPTION_ACTIVE_KEY`| Which named key new values are encrypted with                           |
| `PORTAL_ADMIN_EMAIL`          | Email of the seeded super admin (default `admin@localhost`)             |
| `PORTAL_ADMIN_PASSWORD`       | Its starting password (default `changeme`), must be changed at first sign-in |
| `PORTAL_ADMIN_PASSWORD_HASH`  | BCrypt hash to use instead; skips the forced change                     |
| `PORTAL_ADMIN_GROUP`          | Group the seeded super admin administers (default `Administrators`)     |
| `PORTAL_DB_PATH`              | SQLite database file location (default `./keyportal.db`)                |
| `PORTAL_DATA_DIR`             | All-in-one image only: where the database and its generated password live (default `/var/lib/keyportal`). Never the encryption key |
| `PORTAL_BASE_URL`             | URL emailed links point at. Blank = whatever host the admin used        |
| `PORTAL_MAIL_FROM`            | From address on portal emails (default `no-reply@localhost`)            |
| `PORTAL_INVITE_VALID_FOR`     | How long an invitation lasts (default `24h`)                            |
| `PORTAL_MFA_REQUIRED`         | Require two-factor authentication for every password account (default `false`) |
| `PORTAL_LOCKOUT_MAX_ATTEMPTS` | Failed sign-ins before a lockout, per account and per address (default `5`) |
| `PORTAL_LOCKOUT_FOR`          | How long that lockout lasts (default `15m`)                             |
| `PORTAL_LOCKOUT_MIN_ADDRESSES`| Distinct addresses an account's failures must span before the account itself locks (default `3`; `1` locks on the count alone) |
| `PORTAL_FORWARD_HEADERS`      | Set to `framework` behind a reverse proxy — see [Behind a reverse proxy and HTTPS](#behind-a-reverse-proxy-and-https) (default `none`) |
| `PORTAL_SECURE_COOKIE`        | Set to `true` behind HTTPS: marks the session cookie `Secure` (default `false`) |
| `SPRING_MAIL_HOST` etc.       | SMTP server for emails. Optional — invitations fall back to a link shown to the admin |

The `PORTAL_ADMIN_*` values seed the super admin and are read **only while the portal has no
accounts at all** — everyone afterwards arrives by invitation, so they can be dropped once you're
up and running. Setting `PORTAL_ADMIN_PASSWORD_HASH` (one way:
`htpasswd -bnBC 10 "" 'your-password' | tr -d ':\n'`) counts as a deliberately chosen password,
so no change is forced. The pre-multi-user names `PORTAL_USERNAME` / `PORTAL_PASSWORD_HASH` still
work: an upgraded deployment's single login becomes the first super admin and owns the existing
keys and integrations.

> **Keep the encryption key safe and backed up.** Key values can't be decrypted without it. To
> *change* it, don't swap it — rotate it (below).

### Rotating the encryption key

Every encrypted value records the id of the key that wrote it, so an old key keeps opening old
values while new writes go to a new one — no downtime.

1. Add the new key and make it active, keeping the old one configured:

   ```bash
   PORTAL_ENCRYPTION_KEY=<the existing key>      # keeps old values readable
   PORTAL_ENCRYPTION_KEYS_2026A=<the new key>    # openssl rand -base64 32
   PORTAL_ENCRYPTION_ACTIVE_KEY=2026a
   ```

2. Restart. The portal logs how many values still sit on an older key and warns at startup
   until none do.
3. Re-encrypt the backlog: `java -jar api-key-portal.jar --portal.encryption.reencrypt=true`.
   It moves every encrypted value onto the active key in one transaction, then exits.
   Re-runnable; if anything can't be decrypted it changes **nothing** and names the rows.
4. When startup stops warning, remove the old key from configuration.

Rotation touches only ciphertext — no `updated_at` changes, no history noise — and is recorded
as an `encryption_key_rotated` audit event.

### Backup and restore

A working portal is **two things kept apart**: the database, and the encryption key(s). Every
key value, provider credential, SSO secret, chat webhook and two-factor secret in the database
is ciphertext; without the key a database backup is a list of names and dates, and without the
database the key opens nothing. Keep them apart on purpose — a backup holding both is exactly
what encryption at rest exists to prevent — and test the restore before you need it.

**What to back up**

| What | Where it lives | How often |
|------|----------------|-----------|
| Every encryption key — `PORTAL_ENCRYPTION_KEY` and any `PORTAL_ENCRYPTION_KEYS_<ID>` | Your environment / secret store, never the data volume | Whenever a key is added; keep every key a backup was written under until that backup is retired |
| The database | SQLite file, PostgreSQL server, or the all-in-one volume (below) | Daily, and before every upgrade |
| Your configuration (`.env` or the environment, minus the key) | Wherever you keep it | On change |

Nothing else is state: the images are rebuilt from source, the in-memory lockout counters are
meant to be lost on restart, and uploaded logos and theme colours are rows in the database.

**SQLite** (the default, and the plain container image's `/app/data/keyportal.db`). The database
runs in WAL mode, so a plain file copy can catch it between a write and its checkpoint. Use
SQLite's own online backup, which is safe while the portal is running:

```bash
sqlite3 /path/to/keyportal.db ".backup '/backups/keyportal-$(date +%F).db'"
# plain container image: the file is on the keyportal-data volume
docker run --rm -v keyportal-data:/data -v "$PWD/backups:/backups" alpine \
    sh -c "apk add -q sqlite && sqlite3 /data/keyportal.db \".backup '/backups/keyportal-$(date +%F).db'\""
```

Restore: stop the portal, replace `keyportal.db` with the backup and delete any stale
`keyportal.db-wal` and `keyportal.db-shm` beside it, start it with the **same keys** configured.

**PostgreSQL** (the `postgres` profile). Standard tooling; nothing about the portal is special:

```bash
pg_dump --format=custom --dbname=keyportal --username=keyportal --file=keyportal-$(date +%F).dump
pg_restore --clean --if-exists --dbname=keyportal --username=keyportal keyportal-2026-11-02.dump
```

**The all-in-one image** holds its PostgreSQL inside the container, so dump it from inside:

```bash
docker exec <container> gosu postgres pg_dump --format=custom --dbname=keyportal --username=keyportal \
    > keyportal-$(date +%F).dump
# restore into a running container (the schema is Flyway's; --clean replaces it)
docker exec -i <container> gosu postgres pg_restore --clean --if-exists --dbname=keyportal --username=keyportal \
    < keyportal-2026-11-02.dump
```

A logical dump is the backup to keep. A copy of the volume itself (`/var/lib/keyportal`) also
works as a cold backup, but only if the container is stopped first — a running PostgreSQL's data
directory copied mid-write is not a database — and it carries the generated database password
with it, which the dump does not need. Restoring a volume into a fresh container simply works:
the entrypoint finds the database and its password where it left them.

**Restoring onto a new key is not possible.** The key is not derivable from anything, so a
backup written under a key you no longer have is unrecoverable; the portal starts, lists every
key, and refuses to reveal any of them. If a key must be replaced, rotate it (above) *before*
retiring the old one from your key backups — a dump taken halfway through a rotation needs both
keys configured to restore, which is exactly what the startup warning about values on older
keys tells you.

**Checking a backup is a backup.** Restore it somewhere disposable with the same keys, start the
portal, and reveal one key you know the value of. `/actuator/health` turns the second step into
a probe: its `encryption` component is a real encrypt/decrypt round trip, so a restore started
with the wrong key is `DOWN` before anybody signs in.

### Behind a reverse proxy and HTTPS

The portal serves plain HTTP on 8080 and expects a proxy (nginx, Caddy, Traefik, a load
balancer) to terminate HTTPS. Two settings make that safe; both default to off because each is
wrong for bare `http://localhost`:

- **`PORTAL_FORWARD_HEADERS=framework`** — believe `X-Forwarded-For`/`-Proto`, so the portal
  sees the real caller. Without it every request appears to come from the proxy: one person's
  five failed passwords lock the sign-in page for *everyone* behind it, and the audit log
  records the proxy's address instead of the caller's. Leave it `none` with no proxy in front —
  believing a header nothing trustworthy sets would let a caller invent a fresh address per guess.
- **`PORTAL_SECURE_COOKIE=true`** — mark the session cookie `Secure` so it never travels over
  plain HTTP. Without it a single `http://` request sends the signed-in session in clear. With
  it, signing in over plain `http://localhost` silently fails — hence the default.

The proxy must set the headers it is trusted for, and should redirect HTTP to HTTPS. For nginx:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Host              $host;
}
```

Caddy and Traefik set all three by default. Also set `PORTAL_BASE_URL` to the public `https://`
address so emailed links point at the proxy.

### PostgreSQL

The `postgres` profile switches databases. `docker-compose up -d` provides a local one, reading
credentials from the same `.env`:

| Environment variable | Default                                      |
|----------------------|----------------------------------------------|
| `PORTAL_DB_URL`      | `jdbc:postgresql://localhost:5432/keyportal` |
| `PORTAL_DB_USERNAME` | `keyportal`                                  |
| `PORTAL_DB_PASSWORD` | none, required                               |

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

Flyway manages the schema (`src/main/resources/db/migration`).

## Accounts, groups and roles

Everyone belongs to a **group** — whoever shares a view of each other's keys.

| Role            | Sees                                                      |
|-----------------|-----------------------------------------------------------|
| **Super admin** | Every account and group, every integration, all history   |
| **Group admin** | Their own group: roles and access, integrations, history  |
| **Member**      | The other people in their group, and the group's keys     |

One rule holds across all three: **everyone in a group sees each other's key metadata; nobody
but the owner can reveal, edit or delete a key** — not a group admin, not a super admin. A super
admin can move accounts between groups from **People → (person) → Administration**.

**Service integrations belong to a group.** An admin connects a provider once; from then on
anyone in the group can mint keys through it without ever seeing the provider credential. Each
integration has a group-level on/off switch — off, nobody can mint through it (the add-key page
stops offering it, a direct post is refused, automatic replacement skips its keys) while the
stored credential and existing keys stay put. Only an admin of the owning group can connect,
re-test, pause or remove one.

## Dashboard

**Dashboard** is the group's to-do list for its keys, built from the same metadata as the key
list (never a value), so everyone sees it for their own group and a super admin can pick any
group or all of them:

- **Expiring in the next 30 days** — soonest first, with the day count; a key whose replacement
  has already been minted is marked "Replacement ready" instead.
- **Automatic replacement failed** — keys whose last sweep attempt (minting or the Google/AWS
  provider-side deletion) failed, with the provider's own words and when. The failure is also
  shown on the key's page, and clears itself the next time an attempt succeeds. Until now these
  lived only in the audit log.
- **Never revealed** — live keys added more than 30 days ago that nobody has ever shown or
  copied. Possibly a replacement nobody has switched to, possibly a key to retire; the age floor
  keeps keys added last week off the list.

The counts at the top (keys, expiring, expired, failed, unused) link to the relevant list.

## History

Administrators can read everything back under **History**: who did what, when, to which key, and
which integration or walk-through it came through. Group admins see their group, super admins
everything, members nothing.

**Revealing a key is recorded every time**, against the person who did it, noting whether it was
shown on screen or copied to the clipboard — after a value turns up somewhere it shouldn't, "who
took it out, and when" is the question only this answers. Edits record which fields changed,
including *that* a value changed, never what to. Records are flat snapshots with no foreign
keys, so they outlive the keys, integrations and names they describe. Never a key value.

### Invitations

**There is no public sign-up.** Accounts come from **Invitations → Invite user**:

| Who invites     | Into which group        | With which role                     |
|-----------------|-------------------------|-------------------------------------|
| **Super admin** | Any group in the portal | Any, including another super admin  |
| **Group admin** | Their own group only    | Group admin or member               |
| **Member**      | Cannot invite           | —                                   |

The link is good for 24 hours and works once; accepting it asks only for a name and password —
email, role and group are fixed by the invitation. Only the SHA-256 of the token is stored, so
the emailed link is the only copy. Invitations can be withdrawn (stops the link immediately) and
re-sent once expired; a second invitation to an address with a live link is refused.

Groups are managed by a super admin under **Groups**; a group can only be deleted once empty.

Invitations are emailed through the `SPRING_MAIL_*` SMTP settings, with `PORTAL_BASE_URL` for
the links. With no mail server (or a rejected message), the portal shows the link to the admin
to pass on by hand, so a broken SMTP setup never strands an invitation.

## Expiry reminders

A key's owner is emailed **a month, two weeks, a week and a day** before it expires — one email
per threshold, and only the threshold the key is currently inside, so a key added with five days
left gets one email, not a backlog. Changing the expiry date starts the reminders over. Each key
has an owner-only mute switch on its page.

The sweep runs daily (`PORTAL_EXPIRY_REMINDER_CRON`, default 08:00) and needs `SPRING_MAIL_HOST`
and `PORTAL_BASE_URL`. `PORTAL_EXPIRY_REMINDERS=false` switches it off.

## Chat alerts: Microsoft Teams and Slack

A group admin can connect a **Microsoft Teams** channel, a **Slack** channel, or both, under
**Integrations**, and the group's alerts are posted there as cards alongside the emails:

- **Keys about to expire** — at the same thresholds as the reminder emails (a month, two weeks,
  a week, a day). A reminder counts as sent once it has reached the owner by email *or* a
  channel, so a portal with a channel and no mail server still announces its expiries.
- **Failed automatic replacements** — when the daily sweep could not mint a replacement or
  delete an expired Google/AWS key at the provider, with the provider's own words.
- **Sign-in lockouts** — an account or address refused after too many wrong passwords. Off by
  default: these name accounts and addresses and cover the whole portal, not just the group.

Setup is one-time per group per product. **Teams**: open the channel, choose **… → Workflows →
Post to a channel when a webhook request is received**, and paste the URL it gives you (older
*Incoming Webhook* connectors work too). **Slack**: create an app at api.slack.com/apps, switch
on *Incoming Webhooks*, add one to the channel, and paste its URL. The portal posts a test card
before saving anything, so a stored channel is known to work. Teams gets an Adaptive Card,
Slack a Block Kit message with a coloured bar; both say the same thing.

A webhook URL can post into the channel as the portal, so it is **encrypted at rest** like a
provider credential, covered by key rotation, and never shown again; it must be `https://` and
may not point at a local or private address. No key value is ever on a card — names, dates,
owners and a link back into the portal. A chat service being down never stops the thing being
announced: a failed post is logged and shown on the Integrations page.

## Automatic key replacement

For keys the portal minted through an integration, it can mint the replacement too: a week
before expiry, a fresh secret on the **same client**, stored as a new key, with an email to the
owner. The old key stays listed as "Replacement ready" until its own expiry. Strictly opt-in,
twice over: per provider on your profile, and any single key can be pinned on or off from its
page.

The providers behave differently because their APIs do:

- **Azure AD** — a second secret on the same app registration; both work until the old one
  expires, and Azure retires it itself. The portal then removes the expired secret from the
  app registration, so rotations don't pile up dead entries there.
- **Google Cloud / AWS** — a second JSON key on the same service account, a second access key on
  the same IAM user. Neither provider ever retires a key, so the expiry date is the portal's
  (chosen at creation) and the portal makes it true: the morning after a *replaced* key's date
  passes, it **deletes the old key at the provider**. The replacement email names that date. A
  key nobody asked to replace is never deleted — its date stays the reminder it always was.
  Google/AWS keys from before this feature join in once given an expiry date on the edit form.
  AWS allows two access keys per IAM user — exactly one hand-over; a second key made by hand
  makes rotation fail with a message until the slot is free.

**Deleting replaced keys is a switch on each integration** (Integrations page, group admins),
on by default. Off, a replaced key is left at the provider when it expires: for Azure that
means an expired, inert secret stays listed on the app registration; for Google and AWS it
means the key **keeps working there** until somebody deletes it in the console, and the
replacement email, the audit entry and the key's page all say so. Only keys the portal itself
replaced are ever deleted, whatever the switch says. Azure secrets minted before the portal
recorded their ids are matched by name among the registration's *expired* secrets, which is
safe because an expired secret is inert whichever one it is.
- **GitLab** — its rotate endpoint revokes the old token as it issues the new one, so there is
  no overlap: the portal waits until the last sweep before expiry and replaces the value *in
  place*. The email says the old value has already stopped working.

Failures are boring by design: any error — provider refusal or otherwise — is logged, audited
and retried next sweep, per key, with the ordinary reminders as the safety net. A unique index
on the replacement link means no key is replaced twice; a key the provider no longer holds
counts as deleted. The new value is never emailed — the email links to the portal, where
revealing it is owner-only and recorded.

| Environment variable             | Default       | What it does                             |
|----------------------------------|---------------|------------------------------------------|
| `PORTAL_AUTO_ROTATION`           | `true`        | Master switch for the daily sweep        |
| `PORTAL_AUTO_ROTATION_CRON`      | `0 0 7 * * *` | When it runs (before the reminder sweep) |
| `PORTAL_AUTO_ROTATION_LEAD_DAYS` | `7`           | How far ahead of expiry to mint          |

Expired keys are hidden behind a "Show expired" toggle — hidden, not gone: reveal still works
for the owner and the history keeps everything.

## Look and feel

Bootstrap 5 re-skinned through design tokens in `static/css/app.css`; the Content-Security-Policy
allows no inline styles or scripts, so that one stylesheet is the whole look. Light and dark
follow the operating system, set before first paint by `static/js/theme.js`. Below the `lg`
breakpoint the key, people and history lists become stacks of cards; the controls that matter
(reveal, copy, edit, delete) come from shared fragments so the two layouts cannot drift apart.

**Appearance.** A super admin can rebrand the portal for everyone under **Appearance**:

- **Name** — in the header, the page titles and "Sign in to …".
- **Logo** — PNG, JPEG, GIF, WebP or SVG up to 512 KB, replacing the key mark in the header and
  on the sign-in page and doubling as the tab icon. The type is decided from the file's bytes,
  not its name, and the logo is served (publicly, at `/theme/logo`) with a policy that keeps any
  script inside an SVG from ever running.
- **Colours** — seven per mode (accent, page and card backgrounds, text, and the
  success/warning/danger status colours), for light and dark separately, each with a colour
  picker or a typed hex value and a live preview. The hover shade, soft tints, secondary greys
  and Bootstrap's own variables are derived from those seven so the result stays coherent.
  Because the CSP forbids inline styles, the palette is served as a same-origin stylesheet,
  `/theme.css` — empty while the built-in look is in use, so `app.css` stays the one source of
  the defaults.

**Reset to defaults** returns all three to the built-in look by deleting what was saved rather
than writing the defaults in. Every change is recorded in the history as "Appearance changed".

## Two-factor authentication

Anyone with a password account can add a second factor from **Profile → Two-factor
authentication**: scan a QR code with any authenticator app (TOTP, RFC 6238 — Microsoft or
Google Authenticator, Authy, 1Password, Bitwarden), enter the code it shows, and save the ten
recovery codes shown once. From then on a correct password lands on a code page, and nothing
else is served until a code is accepted. Set `PORTAL_MFA_REQUIRED=true` and every password
account is pinned to the enrolment page at sign-in until it has one.

How it is built to hold up:

- **Nothing is stored until a code is accepted**, so an abandoned enrolment locks nobody out.
- **A code works once.** The time step it matched is remembered; that step and earlier are
  refused, so a code seen over a shoulder cannot be replayed. One step of clock drift either
  way is allowed, no more.
- **Wrong codes count as wrong passwords** towards the sign-in lockout, and five in one sign-in
  end the session. Codes are compared in constant time.
- **The secret is encrypted at rest** with the portal's key and covered by key rotation; it is
  never shown again after enrolment. **Recovery codes are stored as hashes** and deleted as
  used; the page at enrolment is the only time they exist in clear.
- **Switching off, or making new recovery codes, needs the current password *and* a code** — a
  stolen session cannot remove the second factor, nor can a stolen password alone.
- **A super admin can reset someone's second factor** for a lost phone, from **People → Administration**;
  never their own, so the break-glass account's second factor is not one click to remove. Every
  enrolment, verification, failure, reset and switch-off is in the audit log and the history.
- **SSO-linked accounts are left alone**: their second factor is the identity provider's.

Not included, deliberately for now: "remember this device" cookies and hardware keys (WebAuthn).

## Single sign-on

A super admin can connect the organisation's identity provider under **Single sign-on** —
anything that speaks **OpenID Connect** (Entra ID, Okta, Google Workspace, Auth0).

Access is a directory group, not a list in the portal, and **the check runs on every sign-in**:
removed from the group, locked out of the portal straight away.

| In the portal                | Comes from                                                  |
|------------------------------|-------------------------------------------------------------|
| Can sign in at all           | Membership of the group you nominate                        |
| Group admin                  | Membership of a second group, if you nominate one           |
| Super admin                  | **Never** from a claim — handed out inside the portal only  |
| Which portal group they join | The one you pick; people already here keep theirs           |

Setup: register the portal as an OIDC web app with the redirect URI shown on the page (set
`PORTAL_BASE_URL` behind a proxy), make the provider send a groups claim (Entra ID: *Token
configuration → Add groups claim → Groups assigned to the application*), then paste issuer URL,
client ID and client secret, name the access group, and switch it on. The provider's OpenID
configuration is read on save, so a wrong issuer fails there, not at the first sign-in.

Worth knowing:

- Entra ID drops the groups claim for anyone in ~200+ groups; the portal detects and says so.
  Assigning the claim to the application avoids it. Entra sends group object IDs, other
  providers names — paste what yours emits.
- First sign-in creates the account; an existing account with the same email is linked, not
  duplicated — but an account linked to one provider identity is never re-pointed at another.
- **Once linked, an account's password stops working** while SSO is on, so the directory group
  (and your provider's MFA) is the real gate. Switching SSO off hands passwords back, so a
  broken provider can't lock everyone out.
- **The super admin is the break-glass account**: never linked, always signs in with its portal
  password.

## Service integrations

Instead of pasting keys in by hand, the portal creates them in a connected provider. In each
case credentials are verified against the provider before being stored encrypted; once
connected, **Add key** offers "create it in a connected service".

- **Microsoft Azure AD (Entra ID)** — registers an application, creates its service principal
  and client secret via Graph, and captures the secret in the one moment Azure shows it. Setup:
  register an app for the portal with the Graph application permission
  `Application.ReadWrite.OwnedBy` (it can only manage registrations it created), admin consent,
  and paste tenant ID, client ID and secret.
- **Google Cloud** — creates a service account and JSON key. Google keys never expire, so the
  validity you pick is the portal's date, enforced via
  [automatic replacement](#automatic-key-replacement). Setup: enable the IAM API, create a
  service account with *Service Account Admin* and *Service Account Key Admin* (Key Admin covers
  the deletion), paste its JSON key.
- **Amazon Web Services** — creates an IAM user and access key pair, stored as both halves.
  Expiry works as for Google. Setup: the wizard's policy allows exactly `iam:CreateUser`,
  `iam:CreateAccessKey`, `iam:DeleteAccessKey`, `iam:DeleteUser` on
  `arn:aws:iam::*:user/keyportal/*` — everything the portal creates lives under `/keyportal/`,
  so its credential reaches that path and nothing else. An integration connected before
  `iam:DeleteAccessKey` was in the list needs it added, or the deletion step is refused (and
  retried) while everything else works. The connection test asks STS whose credentials these
  are and refuses a key from the wrong account.
- **GitLab** — mints a project access token (scope `api`, role Maintainer), self-hosted
  included: each integration carries its own base URL. GitLab caps tokens at a year; longer
  choices quietly become a year. Setup: a Maintainer `api`-scope project token for the portal.
- **Cloudflare** — token permissions are a language of their own, so the admin crafts **one
  template token** in Cloudflare's editor and every minted token copies its policies; editing
  the template changes what future keys get. Setup: a portal token with only *User → API Tokens
  → Edit*, plus the template token's ID.

### Guided walk-throughs (GitHub, Anthropic, Atlassian, OpenAI, Stripe)

Providers with no key-creation API get step-by-step instructions with deep links instead; the
pasted key is verified live against the provider before it's stored (GitHub's expiry and scopes
are read from the verification response). Atlassian's credential is *email plus token*, verified
together; only the token is stored encrypted, the email goes in the notes.

> An integration credential can create new credentials in your tenant/project — the most
> sensitive value the portal stores. Encrypted at rest, never rendered back into a page.

## Packaging and deployment

### As a Spring Boot app

```bash
./mvnw package
PORTAL_ENCRYPTION_KEY=... java -jar target/api-key-portal-1.0.0.jar
```

Runs anywhere there's a JDK 25; SQLite beside it unless pointed at PostgreSQL. Every build
attaches the jar to the GitHub Actions run.

### As a container, with your own database

```bash
docker build -t api-key-portal .
docker run -p 8080:8080 --env-file .env -v keyportal-data:/app/data api-key-portal
```

The SQLite database lives on the volume (`PORTAL_DB_PATH=/app/data/keyportal.db`). Add
`-e SPRING_PROFILES_ACTIVE=postgres` and the `PORTAL_DB_*` variables for your own PostgreSQL.
`.env` is excluded from the build context, so secrets are never baked into the image.

### As one container with the database inside it

`Dockerfile.allinone` carries the portal and PostgreSQL together. One thing to set — the key:

```bash
docker run -p 8080:8080 -v keyportal-data:/var/lib/keyportal \
    -e PORTAL_ENCRYPTION_KEY="$(openssl rand -base64 32)" \
    ghcr.io/OWNER/api-key-portal:latest-allinone
```

or put the key in `.env` and `docker compose -f docker-compose.allinone.yml up -d`. Keep a copy
of the key somewhere other than the data volume. Sign in as above.

- **Data**: everything that outlives the container — the database and its generated password —
  sits under `PORTAL_DATA_DIR` (default `/var/lib/keyportal`); mount a volume or host directory
  there. Mount nothing and it's a scratch instance.
- **The image refuses to start without `PORTAL_ENCRYPTION_KEY`** and never writes it to the
  volume, so a copied volume — backup, snapshot, stolen disk — is not enough to read what's in
  it. *Upgrading from an older image that generated a key into the volume:* the container stops
  and tells you — set `PORTAL_ENCRYPTION_KEY` to the contents of `encryption-key` in the data
  directory, store a copy elsewhere, delete the file. If you set a different key instead, add
  the old one as a named key and re-encrypt first (see
  [Rotating the encryption key](#rotating-the-encryption-key)).
- **Inside**: root only long enough to prepare the data directory, then PostgreSQL and the
  portal as separate unprivileged users; PostgreSQL on loopback only; either process dying takes
  the container with it; `HEALTHCHECK` = the portal answers. Stopping takes longer than Docker's
  ten-second default — pass `--stop-timeout 60`, or use the Compose file, which sets it.
- **The trade**: one container, so the portal and database can't be scaled, restarted or tuned
  independently. Run them separately if you want that.

### Published images

Every push to `main` publishes both images to ghcr; a `v*` tag publishes them as a release.
`latest` / `latest-allinone` move only on release tags; `main` / `main-allinone` track the tip.
Images are `linux/amd64`; build from the Dockerfile for anything else.

## Health checks

Three probes, anonymous because monitors have no session, returning only a status and an HTTP
code to a stranger and every component with its details to a signed-in super admin:

| Endpoint | What it checks | Use it for |
|----------|----------------|------------|
| `/actuator/health/liveness` | Nothing but that the process answers | A heartbeat; restart the process if it stops |
| `/actuator/health/readiness` | The database is reachable and the encryption key encrypts and decrypts | Load balancers and orchestrators: whether this instance can serve |
| `/actuator/health` | All of the above, plus disk space, the SMTP server if one is configured, and the identity provider's discovery document if SSO is on | The deep probe for a monitoring system; external trouble makes it `503` |

The encryption check is a real round trip, not "a key is configured": a wrong key would serve
every page and fail only at the moment somebody reveals a value. The deep probe deliberately
isn't what readiness returns — an SMTP outage means reminders are late, not that the portal
should be taken out of rotation. Both container images carry a `HEALTHCHECK` against the
readiness probe (spoken over bash's `/dev/tcp`, since neither image has curl). Nothing else
from Actuator is exposed.

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

## Tests

```bash
./mvnw verify
```

Tests use a throwaway SQLite database under `target/` and test-only credentials — no `.env`
needed. Coverage lands in `target/site/jacoco/index.html`.

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
  [Two-factor authentication](#two-factor-authentication): replay-proof TOTP, hashed single-use
  recovery codes, wrong codes count towards the lockout, and removing it takes password *and* code.
- Behind a reverse proxy, set `PORTAL_FORWARD_HEADERS=framework` and `PORTAL_SECURE_COOKIE=true`
  — see [Behind a reverse proxy and HTTPS](#behind-a-reverse-proxy-and-https) for what goes
  wrong without them.
- Strict Content-Security-Policy (no inline scripts or styles), `X-Frame-Options: DENY`,
  `Referrer-Policy: same-origin`.
- All-in-one image: PostgreSQL on loopback only, nothing runs as root, and it refuses to start
  without `PORTAL_ENCRYPTION_KEY` so a copied volume can't read itself.
- Notes are **not** encrypted. Don't store secrets in them.
- Run it behind HTTPS if it's reachable from anywhere other than localhost.
- Back up the database and the encryption key **separately**, and keep every key a backup was
  written under — see [Backup and restore](#backup-and-restore).

## To do

- **More providers**
  - Connected: Twilio (API keys), SendGrid (API keys)
  - GitLab group access tokens, alongside today's project tokens
- **Better error handling**: remove the "Not Found" banner from the 404 page

## License

[MIT](LICENSE) © 2021 Sid Bhat
