# API Key Portal

A small self-hosted web portal for keeping track of the API and access keys a group of people
use across different services — a team, a company, or a family.

- See every key your group holds in one place, with the service it belongs to, who added it,
  when it was created and when it expires
- Reveal or copy a key's value on demand — but only the person who added it can.
  Values are never rendered into pages.
- Accounts, groups and roles: invitation-only sign-up, manage your profile, change your password
- Add, edit and delete your own keys
- Create keys directly in a connected service provider (Microsoft Azure AD, Google Cloud,
  Amazon Web Services, GitLab, Cloudflare) and capture the generated secret automatically
- Guided walk-throughs for providers whose keys must be created by hand (GitHub, Anthropic,
  Atlassian, OpenAI, Stripe), with the pasted key verified live against the provider before it's stored
- Search, filter by service, sort by expiry, and see at a glance what has expired or is about to
- Key values are encrypted at rest with AES-256-GCM, with key rotation built in
- Light and dark themes, following whatever the operating system is set to
- An admin-only history of who created what, who revealed and edited which key, and which
  keys came from which integration, with the same events written to an audit log
- Repeated wrong passwords lock sign-in for a short while, counted per account and per address
- Email reminders to a key's owner a month, two weeks, a week and a day before it expires
- Expiring keys minted through an integration can be replaced automatically a week ahead —
  same client, fresh secret, opt-in per provider or per key — with an email when it happens
- Single sign-on against your organisation's identity provider, with access granted by directory group
- Runs as a plain Spring Boot jar, as a container against your own database, or as a single
  container with PostgreSQL inside it that needs nothing configured to start

Built with Java 25, Spring Boot 4.1, Spring Security, Spring Data JPA, Flyway, Thymeleaf and Bootstrap 5.

## Running locally

Requires JDK 25.

1. Copy `.env.example` to `.env` and fill in the values (see [Configuration](#configuration)).
   `.env` is git-ignored; never commit it.
2. Start the app:

   ```bash
   ./mvnw spring-boot:run
   ```

3. Open <http://localhost:8080> and sign in as the super admin that a fresh install creates
   for you:

   | | |
   |---------|------------------------------------------------|
   | Email    | `admin@localhost` (`PORTAL_ADMIN_EMAIL`)      |
   | Password | `changeme` (`PORTAL_ADMIN_PASSWORD`)          |

   **You'll have to replace that password before you can do anything else** — the portal
   redirects the account to the change-password page until you do. After that, invite
   everyone else from **Invitations → Invite user**; nobody can sign themselves up.

By default data is stored in a SQLite database at `./keyportal.db` (created automatically on
first start, reused on subsequent starts). Set `PORTAL_DB_PATH` to keep it somewhere else,
e.g. `PORTAL_DB_PATH=/var/lib/keyportal/keyportal.db`.

## Configuration

Secrets are read from environment variables, or from a `.env` file in the working directory
(loaded with [spring-dotenv](https://github.com/paulschwarz/spring-dotenv)). Real environment
variables take precedence over `.env`. The app refuses to start if the required ones are missing:

| Environment variable          | Description                                                             |
|-------------------------------|-------------------------------------------------------------------------|
| `PORTAL_ENCRYPTION_KEY`       | Base64 encoded 256-bit AES key. Generate with `openssl rand -base64 32` |
| `PORTAL_ENCRYPTION_KEYS_<ID>` | Additional named keys, for rotation — see [Rotating the encryption key](#rotating-the-encryption-key) |
| `PORTAL_ENCRYPTION_ACTIVE_KEY`| Which named key new values are encrypted with                           |
| `PORTAL_ADMIN_EMAIL`          | Email of the seeded super admin (default `admin@localhost`)             |
| `PORTAL_ADMIN_PASSWORD`       | Its starting password (default `changeme`), which must be changed at first sign-in |
| `PORTAL_ADMIN_PASSWORD_HASH`  | BCrypt hash to use instead of the above. Optional — see below           |
| `PORTAL_ADMIN_GROUP`          | Group the seeded super admin administers (default `Administrators`)     |
| `PORTAL_DB_PATH`              | SQLite database file location (default `./keyportal.db`)                |
| `PORTAL_DATA_DIR`             | All-in-one image only: where the database and generated secrets live (default `/var/lib/keyportal`) |
| `PORTAL_BASE_URL`             | URL invitation links point at. Blank = whatever host the admin used     |
| `PORTAL_MAIL_FROM`            | From address on invitation emails (default `no-reply@localhost`)        |
| `PORTAL_INVITE_VALID_FOR`     | How long an invitation lasts (default `24h`)                            |
| `PORTAL_LOCKOUT_MAX_ATTEMPTS` | Failed sign-ins before a lockout, per account and per address (default `5`) |
| `PORTAL_LOCKOUT_FOR`          | How long that lockout lasts (default `15m`)                             |
| `PORTAL_FORWARD_HEADERS`      | Set to `framework` behind a reverse proxy — see below (default `none`)  |
| `SPRING_MAIL_HOST` etc.       | SMTP server for invitation emails. Optional — see below                 |

Only `PORTAL_ENCRYPTION_KEY` is required; the app refuses to start without it.

The `PORTAL_ADMIN_*` values seed the super admin account, and are read **only while the portal has
no accounts at all**. Every account afterwards arrives by invitation, and passwords are changed in
the portal, so these can be dropped once you're up and running.

Because nobody can sign themselves up, a fresh install always gets that one account — otherwise
there would be no way in. It starts on `PORTAL_ADMIN_PASSWORD` and is flagged as having to replace
it: until that happens, every page redirects it to the change-password form. Setting
`PORTAL_ADMIN_PASSWORD_HASH` instead is read as a password somebody picked deliberately, so no
change is forced; use it if you'd rather not have a plaintext password in your configuration at all.

`PORTAL_ADMIN_EMAIL` and `PORTAL_ADMIN_PASSWORD_HASH` are the new names for `PORTAL_USERNAME` and
`PORTAL_PASSWORD_HASH`. The old names still work, so upgrading an existing deployment needs no
config change: the single login you already had becomes the first super admin, and every key and
integration already in the database becomes theirs.

One way to generate a BCrypt hash:

```bash
htpasswd -bnBC 10 "" 'your-password' | tr -d ':\n'
```

> **Keep the encryption key safe and backed up.** Key values can't be decrypted without it.
> To *change* it, don't swap it — rotate it (below), which keeps the old key readable while the
> portal moves every value across.

### Rotating the encryption key

A single `PORTAL_ENCRYPTION_KEY` is all a portal that never rotates needs, and values written
under it are stored exactly as they always have been. To replace it, name keys instead: every
value then records the id of the key that encrypted it, so the old key keeps opening old values
while new writes go to the new one — no downtime and no flag day.

1. Generate a new key and give it an id. Keep the old key configured:

   ```bash
   PORTAL_ENCRYPTION_KEY=<the existing key>      # keeps old values readable
   PORTAL_ENCRYPTION_KEYS_2026A=<the new key>    # openssl rand -base64 32
   PORTAL_ENCRYPTION_ACTIVE_KEY=2026a            # what new values are written with
   ```

2. Restart. The portal logs how many values are still on an older key, and warns at every
   startup until none are. Everything keeps working throughout.
3. Move the backlog across:

   ```bash
   java -jar api-key-portal.jar --portal.encryption.reencrypt=true
   ```

   It rewrites every encrypted value — key values, integration credentials, the SSO client
   secret — onto the active key in one transaction, then exits. Re-runnable and safe to
   interrupt; if any value can't be decrypted with the configured keys it changes **nothing**
   and tells you which rows and which key id are involved.
4. Once it reports everything moved (and the next startup stops warning), remove the old key
   from configuration.

Rotation only touches ciphertext: `updated_at`, the history and the audit trail aren't disturbed,
so a rotation doesn't make every key in the portal look edited. The run itself is recorded as an
`encryption_key_rotated` audit event.

### PostgreSQL

The `postgres` profile switches to PostgreSQL:

| Environment variable | Default                                     |
|----------------------|---------------------------------------------|
| `PORTAL_DB_URL`      | `jdbc:postgresql://localhost:5432/keyportal` |
| `PORTAL_DB_USERNAME` | `keyportal`                                 |
| `PORTAL_DB_PASSWORD` | none, required                              |

A local database is available through Docker Compose, which reads its credentials from the same `.env`:

```bash
docker-compose up -d
```

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

Flyway manages the schema (`src/main/resources/db/migration`).

## Accounts, groups and what each role can see

Everyone has their own account and belongs to a **group**. A group is whoever shares a view of
each other's keys: a team, a company, or a family.

| Role            | Sees                                                     |
|-----------------|----------------------------------------------------------|
| **Super admin** | Every account and group, every integration, all history   |
| **Group admin** | Their own group; roles and access, integrations, history  |
| **Member**      | The other people in their group, and the group's keys     |

Across all three roles, the same rule holds for keys: **everyone in a group sees each other's key
names, services, owners, expiry dates and notes — and nobody but the owner can see a key's value,
or edit or delete it.** Not a group admin, not a super admin. The value endpoint is the only route
to a key value and it checks ownership on every call.

A super admin can also move accounts between groups from **People → (person) → Administration**.

Service integrations belong to a group too, and that split is the point of them: **a group admin
or super admin connects a provider once, and from then on anyone in that group can mint keys
through it** without ever seeing the provider credential. Each integration also has a
group-level switch: a group admin (or, for any group, a super admin) can switch one off, and
nobody in the group can mint keys through it — the add-key page stops offering it, a direct
form post is refused, and automatic replacement skips keys minted through it — until it is
switched back on. The stored credential and existing keys are untouched, so pausing a provider
is one click rather than a delete-and-reconnect. Both flips land in the history and the audit
log. Only an admin of the owning group can
connect, re-test or remove one — the stored credential can create further credentials, so it is
not a member's to manage. Nobody outside the group can see it at all. An integration always
belongs to exactly one group; connecting one while you aren't in a group is refused rather than
leaving credentials nobody can use.

## History

Everything done in the portal is recorded, and administrators can read it back under
**History**: who did it, when, what they did it to, and — for keys — what kind of key it was and
which integration or walk-through it came through. A group admin sees their own group; a super
admin sees every group; members don't see the page at all.

Recorded today: keys created, revealed, edited and deleted, integrations connected and removed,
invitations sent, accounts created from an invitation, and groups created.

**Revealing a key is recorded every time**, as "Key revealed", against the person who did it and
noting whether the value was shown on screen or copied to the clipboard. The key itself also
carries a "last revealed" date, but that only answers when it was last needed; after a value
turns up somewhere it shouldn't, the question is who took it out of the portal and when, and only
the history can answer that. Editing a key is recorded as "Key updated", with the fields that
changed — including *that* a value was replaced, never what it was replaced with.

Records are flat snapshots with no foreign keys behind them, so they outlive what they describe:
a key minted through an integration still says so after the key is deleted, the integration
removed and the person renamed. Only names and ids are stored — never a key value.

### Invitations

**There is no public sign-up.** The only way to get an account is to be invited by someone who
already has one, from **Invitations → Invite user** (or the button on the People page):

| Who invites     | Into which group      | With which role                         |
|-----------------|-----------------------|-----------------------------------------|
| **Super admin** | Any group in the portal | Any, including another super admin     |
| **Group admin** | Their own group only  | Group admin or member                   |
| **Member**      | Cannot invite         | —                                       |

You give an email address, a role and a group. The invitee gets an email with a link that is good
for **24 hours and works once**. Following it asks them for nothing but a name and a password —
the email address, role and group are fixed by the invitation, so accepting one can only ever
produce the account the administrator described.

Only the SHA-256 of each invitation token is stored, so the link in the email is the only copy. An
invitation can be withdrawn at any time, which stops its link immediately. Once one has expired (or
been withdrawn), **Send again** issues a fresh one to the same address; while a link still works,
a second invitation to the same address is refused rather than leaving two live links for one
account.

Groups themselves are a super admin's job, under **Groups**: add one for each team that needs its
own view, and delete ones you no longer want. A group can only be deleted once nothing belongs to
it — deleting it out from under a key would leave that key visible to nobody.

### Sending the email

Invitations are emailed through whatever SMTP server you configure with the standard Spring Boot
properties — `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`.
Set `PORTAL_BASE_URL` to the address people reach the portal on, so the links in those emails point
somewhere they can actually click.

With no mail server configured — a local install, or one that hasn't got that far — invitations
still work: the portal shows the link to the administrator who issued it, once, to pass on by hand.
It does the same if the mail server rejects the message, so a broken SMTP configuration never
strands an invitation.

## Expiry reminders

The owner of a key is emailed as it approaches its expiry date: **a month, two weeks, a week and
a day before**. Each email carries everything about the key except its value — service, name,
dates, group, notes — with a link to the key's page in the portal.

A key gets at most one email per threshold, and only for the threshold it is currently inside.
A key added with five days left gets the one-week email, not a month's worth at once; a day the
portal was down catches up with a single email, not a backlog. Changing a key's expiry date
starts its reminders over for the new date, which is what rotating it for another year should do.

**Muting.** Each key has a *Mute expiration notifications* switch on its page, shown to the owner.
It is off for every key until the owner turns it on, and it affects that key alone. The reminder
email links to the page, so muting is sign in, flip the switch.

The sweep runs once a day (`PORTAL_EXPIRY_REMINDER_CRON`, default 08:00) and needs the mail
server configured with `SPRING_MAIL_HOST` plus `PORTAL_BASE_URL` for the links. Without a mail
server it logs that it can't send and marks nothing as sent, so reminders pick up as soon as one
is configured. Set `PORTAL_EXPIRY_REMINDERS=false` to switch the sweep off entirely.

## Automatic key replacement

For keys the portal minted itself through an integration, it can also mint the replacement: a
week before such a key expires, the sweep adds a fresh secret to the **same client** — no new
app registration, nothing to re-permission — stores it as a new key, and emails the owner that
their key is about to expire and a new one has been generated. Both secrets work during the
hand-over, so nothing stops at midnight; the old key stays in the list, marked "Replacement
ready", until its own expiry moves it to the expired list.

Strictly opt-in, twice over: each person switches it on per provider from their profile, and
any single key can be pinned on or off from its page regardless of that setting.

Two providers rotate, and they behave differently because their APIs do:

- **Azure AD** adds a *second* secret to the same client. Both work until the old one expires, so
  the portal rotates a week ahead, keeps both keys in the list, and marks the old one
  "Replacement ready" until it lapses.
- **GitLab** rotates through its own rotate endpoint, which issues a new value and **revokes the
  old one in the same call**. There is no overlap, so the portal does the opposite: it waits
  until the last sweep before expiry — rotating early would break the consumer early — and
  replaces the value *in place* rather than creating a second key. Same key, same name, same
  notes, new value. The email says the old value has already stopped working and the consumer
  needs updating now; the key's page says the same before you switch it on.

Google Cloud and AWS are out for now, deliberately — their keys never expire on their own, so
honest rotation there means deleting the old key at the provider too.

Expired keys are hidden from the key list by default behind a "Show expired" toggle. They are
hidden, not gone — reveal still works for the owner, and the history keeps everything.

What can go wrong is designed to be boring: a provider refusal is logged, audited and retried
on the next daily sweep; the ordinary expiry reminders keep running underneath as the safety
net (and are skipped only for a key whose replacement already exists); and a unique index on
the replacement link means no key is ever replaced twice. The new value is never emailed — the
email links to the portal, where revealing it is owner-only and recorded like any other reveal.

| Environment variable             | Default       | What it does                           |
|----------------------------------|---------------|----------------------------------------|
| `PORTAL_AUTO_ROTATION`           | `true`        | Master switch for the daily sweep      |
| `PORTAL_AUTO_ROTATION_CRON`      | `0 0 7 * * *` | When it runs (before the reminder sweep) |
| `PORTAL_AUTO_ROTATION_LEAD_DAYS` | `7`           | How far ahead of expiry to mint        |

## Look and feel

The interface is Bootstrap 5 re-skinned through a small set of design tokens in
`src/main/resources/static/css/app.css` — one accent colour, a neutral palette, soft shadows
instead of heavy borders, and the system font stack. Everything styled anywhere in the app
resolves back to those tokens, so changing the accent or the corner radius is a one-line edit.

It lives in that one stylesheet by necessity as well as taste: the Content-Security-Policy
allows no inline styles or scripts (see `SecurityConfig`), so there is nowhere else to put a
one-off rule.

Light and dark both come from the same tokens. `static/js/theme.js` sets Bootstrap's
`data-bs-theme` from the operating system's setting before first paint, so there is no flash
and no in-app toggle to explain.

### The lists on a phone

Five or six columns do not fit on a phone, and a table that scrolls sideways puts things behind
a swipe — on the key list, the value and the buttons that reveal and copy it. Below the `lg`
breakpoint the key, people and history lists are each a stack of cards instead: a head line,
the fields underneath, and on a key card its masked value with the reveal and copy buttons
beside it. Edit and delete are spelled out in words rather than left as two adjacent icons,
since one of them is destructive and the pointer is a thumb.

Both layouts are rendered and one is hidden, which is a deliberate trade: the markup is in the
page twice, and in exchange each is laid out properly rather than a table pretending to be
something else. The parts that carry behaviour — reveal, copy, edit, delete — come from
`templates/fragments/keys.html` so the two cannot drift apart, and each page's tests assert
that every row and every control appears in both layouts.

## Single sign-on

A super admin can connect the organisation's identity provider under **Single sign-on**, and
people then sign in with their work account. It speaks **OpenID Connect**, which Microsoft Entra
ID, Okta, Google Workspace and Auth0 all support.

### How access is granted

Access is a directory group, not a list kept in the portal. You nominate one group when you set
SSO up; somebody who isn't in it is refused, and **the check runs on every sign-in**, so removing
them in the directory locks them out of the portal straight away. That is the whole point of
managing it there.

| In the portal                       | Comes from                                               |
|-------------------------------------|----------------------------------------------------------|
| Can sign in at all                  | Membership of the group you nominate                      |
| Group admin                         | Membership of a second group, if you nominate one         |
| Super admin                         | **Never** from a claim — handed out inside the portal only |
| Which portal group they join        | The one you pick; people already here keep theirs         |

Super admin is deliberately not grantable from the directory: whoever administers the directory
could otherwise hand themselves the one role that administers every group in the portal.

Set an admin group and the provider owns that flag from then on — somebody dropped from it is
demoted at their next sign-in. Leave it blank and roles stay whatever the portal says.

### Setting it up

1. Register the portal as an application in your provider (an *app registration* in Entra ID, an
   *OIDC web app* elsewhere) and give it the redirect URI shown on the **Single sign-on** page.
   Set `PORTAL_BASE_URL` if the portal is behind a proxy, so that URI is right.
2. Make the provider send group membership. In Entra ID: *Token configuration → Add groups claim*,
   choosing **Groups assigned to the application** — this keeps the claim small and avoids the
   overage described below.
3. Paste the issuer URL, client ID and client secret into the portal, name the access group, pick
   the portal group new accounts should join, and switch it on.

The portal reads the provider's OpenID configuration when you save, so a wrong issuer or an
unreachable provider is caught there rather than by the first person who tries to sign in. The
client secret is encrypted at rest with the portal's own key, like an integration credential.

### Things worth knowing

- **Entra ID drops the groups claim** for anyone in more than about 200 groups, sending a pointer
  to the Graph API instead. The portal detects that and says so, rather than reporting it as "not
  in the group" and sending you hunting. Assigning the groups claim to the application avoids it.
- **Entra sends group object IDs**, not names; other providers send names. Either works — paste
  what your provider actually emits.
- **The first sign-in creates the account.** Someone already in the portal with the same email is
  linked to their provider identity instead of being duplicated, keeping the group they had.
- **Once an account is linked, its password stops working** while SSO is on. That is what makes
  the directory group the real gate: otherwise anyone invited before SSO was set up could keep
  signing in with their old password after being removed from the group, skipping any MFA your
  provider enforces. Switching SSO off hands those passwords back, so a broken provider can't
  lock everybody out.
- **The super admin is the break-glass account.** It is deliberately never linked to the provider
  and always signs in with its portal password, so whoever administers your directory cannot take
  the portal with it. Everyone else is linked on first sign-in.
- **An account already linked to one provider identity is never re-pointed at another**, even if
  both present the same email — that would hand the account to whoever signed in last. The portal
  refuses and leaves it to an administrator.
- Switching SSO off leaves accounts and settings intact; it only stops the button appearing.

## Service integrations

Instead of pasting keys in by hand, the portal can create them for you in a connected provider.

**Microsoft Azure AD (Entra ID)** — creating a key registers a new application in your tenant,
creates its service principal, generates a client secret via Microsoft Graph, and stores the
secret encrypted locally. Azure shows a generated secret exactly once; the portal captures it in
that moment, so it's never displayed or copied manually. Setup is one-time per tenant under
**Integrations → Connect Microsoft Azure AD**: register an app for the portal, grant it the
Microsoft Graph application permission `Application.ReadWrite.OwnedBy` (least privilege — it can
only manage app registrations it created itself) with admin consent, create a client secret, and
paste the tenant ID, client ID and secret into the guided form.

**Google Cloud** — creating a key makes a new service account in your project and generates a
JSON key for it via the IAM API, stored encrypted locally. Setup is one-time per project under
**Integrations → Connect Google Cloud**: enable the IAM API, create a service account for the
portal with the *Service Account Admin* and *Service Account Key Admin* roles, and paste its JSON
key into the guided form.

**Amazon Web Services** — creating a key makes a new IAM user in your account and generates an
access key pair for it, stored encrypted locally as both halves (`AWS_ACCESS_KEY_ID` and
`AWS_SECRET_ACCESS_KEY`). Setup is one-time per account under **Integrations → Connect AWS**:
the wizard has you create a policy allowing exactly `iam:CreateUser`, `iam:CreateAccessKey` and
`iam:DeleteUser` on `arn:aws:iam::*:user/keyportal/*` — every user the portal creates lives
under the `/keyportal/` path, so its credential can reach that path and nothing else in your
account — attach it to a portal IAM user, and paste that user's access key pair and the account
ID into the guided form. The connection test asks STS who the credentials belong to and refuses
a key from the wrong account. API calls are signed with SigV4 by the portal itself; no AWS SDK
is involved, and the signer is pinned by test to the worked example in AWS's documentation.

**GitLab** — creating a key mints a project access token (scope `api`, role Maintainer) in the
project the integration is connected to, including on self-hosted GitLab: each integration
carries its own base URL. Setup is one-time per project under **Integrations → Connect GitLab**:
create a project access token for the portal (Maintainer, `api` scope) and paste the URL, the
project ID or path, and the token into the guided form. A project token can only act inside that
one project, which is the least-privilege story here. GitLab requires an expiry and caps it at a
year; a longer validity choice quietly becomes a year, and the date GitLab echoes back is the
one tracked.

**Cloudflare** — token permissions are a language of their own, so the portal doesn't rebuild
them as a form: the admin crafts **one template token** in Cloudflare's own editor, and every
token the portal mints copies that template's policies with its own name and expiry. The
template is read fresh on each creation, so editing it in the dashboard changes what future keys
get. Setup is one-time under **Integrations → Connect Cloudflare**: create an API token for the
portal with only *User → API Tokens → Edit*, create the template token, and paste the portal
token's value and the template's ID into the guided form.

In each case the portal verifies the credentials against the provider before saving them,
encrypted, in the local database. Once connected, the **Add key** screen offers "create it in a
connected service": pick the integration, name the client, done. Provider-side expiry (where the
provider has one) is tracked like any other key's.

### Guided walk-throughs (GitHub, Anthropic, Atlassian, OpenAI, Stripe)

Some providers have no API for creating keys — GitHub personal access tokens, Anthropic (Claude),
OpenAI and Stripe API keys, and Atlassian API tokens can only be created by hand in their consoles. For those, the **Add key**
screen offers guided walk-throughs instead: step-by-step instructions with deep links, then the
portal verifies the pasted key live against the provider before storing it (and, for GitHub, reads
the token's expiry and scopes from the verification response automatically). A saved key is
therefore known to have worked at least once.

**Atlassian** is the one whose credential comes in two parts: a token authenticates as *email plus
token*, so the walk-through asks for both and verifies them together against `/me` — which also
means a 401 says the email is as likely to be wrong as the token, rather than sending you off to
re-create a token that was fine. Only the token is stored as the (encrypted) key value; the email
goes in the notes, since it isn't a secret and whoever uses the token will need it. One token
covers the whole suite — Jira, Confluence and the rest — and carries the full permissions of the
account that made it, which the wizard says plainly: prefer a dedicated service account for
anything shared.

> An integration credential can create new credentials in your tenant/project, which makes it the
> most sensitive value the portal stores. It is encrypted at rest and never rendered back into a page.

## Packaging and deployment

Three ways to run it, from the one that asks the most of you to the one that asks nothing.

### As a Spring Boot app

`./mvnw package` produces `target/api-key-portal-1.0.0.jar`, which runs anywhere there is a JDK
25 and nothing else:

```bash
PORTAL_ENCRYPTION_KEY=... java -jar api-key-portal-1.0.0.jar
```

That is still the primary way to deploy the portal. It keeps its data in a SQLite file beside
itself unless you point it at PostgreSQL (see [Configuration](#configuration)), and every build
attaches the jar to the GitHub Actions run as an artifact.

### As a container, with your own database

```bash
docker build -t api-key-portal .
docker run -p 8080:8080 --env-file .env -v keyportal-data:/app/data api-key-portal
```

The image sets `PORTAL_DB_PATH=/app/data/keyportal.db`, so the SQLite database lives on the
`keyportal-data` volume and survives container rebuilds. Add `-e SPRING_PROFILES_ACTIVE=postgres`
and the `PORTAL_DB_*` variables to point it at a PostgreSQL server you run instead.

`.env` is excluded from the build context, so secrets are never baked into the image.

### As one container with the database inside it

`Dockerfile.allinone` builds an image carrying both the portal and PostgreSQL. There is nothing
to set up and nothing to point it at:

```bash
docker run -p 8080:8080 -v keyportal-data:/var/lib/keyportal \
    ghcr.io/OWNER/api-key-portal:latest-allinone
```

or `docker compose -f docker-compose.allinone.yml up -d` to build and run it locally.

Stopping it shuts the portal down first and the database second, which takes longer than
Docker's ten-second default allows — pass `--stop-timeout 60` to `docker run`, or use the
Compose file, which sets it. Cut short, the database is killed rather than asked to flush.

Open <http://localhost:8080> and sign in as `admin@localhost` / `changeme`, which the portal
makes you replace before it will let you do anything else.

**Where the data goes.** Everything that has to outlive the container — the database, the
generated database password, the generated encryption key — sits under one directory, set by
`PORTAL_DATA_DIR` (default `/var/lib/keyportal`). Mount a volume or a host directory there:

```bash
# a named volume
docker run -p 8080:8080 -v keyportal-data:/var/lib/keyportal ...
# or somewhere on the host
docker run -p 8080:8080 -v /srv/keyportal:/var/lib/keyportal ...
# or somewhere else inside the container entirely
docker run -p 8080:8080 -e PORTAL_DATA_DIR=/data -v /srv/keyportal:/data ...
```

Mount nothing and it still runs — as a scratch instance that forgets everything when the
container goes.

> **Set `PORTAL_ENCRYPTION_KEY` for anything you would mind losing.** Without one the container
> generates a key on first start and keeps it in the data directory, which is what makes the
> image work out of the box — but it also means a copy of that directory holds both the
> encrypted key values and the key that decrypts them. Pass your own and store it somewhere
> else, and the volume on its own is no longer enough to read what is in it.

**How it fits together.** The container starts as root only long enough to prepare the data
directory, then runs PostgreSQL and the portal as two separate unprivileged users. PostgreSQL
listens on loopback inside the container and its port is never published, so the database is not
reachable from outside even if you publish 5432 by accident. The two processes live and die
together: if either stops, the container stops with its exit status, so a restart policy sees a
half-dead container as a dead one. A `HEALTHCHECK` reports the container healthy only once the
portal is actually answering.

It is one container running two things, which is worth being clear-eyed about: you cannot scale,
restart or upgrade the portal and its database independently, and database tuning is whatever
the defaults are. That is the trade it exists to make. Run the app and PostgreSQL separately —
the second option above — if you want either of those.

### Published images

Every push to `main` publishes both images to the GitHub container registry, and a `v*` tag
publishes them again as a release:

| Image                                             | What it is                        |
|---------------------------------------------------|-----------------------------------|
| `ghcr.io/OWNER/api-key-portal:latest`             | The portal alone, latest release  |
| `ghcr.io/OWNER/api-key-portal:latest-allinone`    | The portal and PostgreSQL together |
| `ghcr.io/OWNER/api-key-portal:v1.0.0`             | A specific release                |
| `ghcr.io/OWNER/api-key-portal:main`               | The tip of `main`                 |

`latest` only ever moves on a release tag, so pulling it never lands you on whatever was merged
an hour ago. Pull requests build both images without publishing them. The published images are
`linux/amd64`; build from the Dockerfile for anything else.

## Logging and the audit trail

Everything the portal does is written to the log, and the lines that matter go to a logger of
their own called `portal.audit` so they can be kept, shipped or alerted on separately from the
application's chatter:

```
INFO [a3f9c2e1 sam@example.com 10.0.0.14] portal.audit : event=key_revealed outcome=ok actor=sam@example.com target="Stripe / Prod secret" detail="Copied to the clipboard"
WARN [7b21d004 jo@example.com 10.0.0.52]  portal.audit : event=access_denied outcome=refused actor=jo@example.com target=/keys/7 reason="Key 7 belongs to another group"
```

Every line carries the request it belongs to, who was signed in and where they called from, so a
reveal, the sign-in before it and the refusal after it can be tied together. The request id is
returned to the browser as `X-Request-Id`, so somebody reporting a problem can quote it. Lines
are `key=value`, which greps and parses without a format per event.

**Allowed actions are `INFO`, refusals are `WARN`** — one refusal is a stale link, a run of them
from one account is somebody finding out what they can reach.

| Event | When |
|-------|------|
| `sign_in`, `sign_out`, `sso_sign_in` | Someone arrived or left, by either door; refusals say why |
| `account_locked_out`, `address_locked_out` | Too many failed passwords, per account and per address |
| `key_created`, `key_revealed`, `key_updated`, `key_deleted` | Everything that happens to a key, including who read its value and whether they copied it |
| `access_denied` | Anything the portal refused — another group's key, a role only a super admin can grant, the history page for a member |
| `password_change`, `profile_updated`, `account_administered` | Credentials and access: a password replaced, a login email changed, somebody else's role, group or access altered |
| `invitation_sent`, `invitation_accepted`, `account_created` | How people get in; a link that doesn't work is recorded too |
| `integration_connected`, `integration_verified`, `integration_removed`, `guided_key_verification` | Provider credentials being stored, exercised or rejected |
| `group_created`, `group_deleted`, `sso_configured` | Changes to who can see what |
| `request_by_disabled_account` | A session outliving the account being switched off |
| `portal_started` | The database, SSO, reminder and lockout settings this instance is running with |

### What is never logged

**No key value, password, client secret or invitation token is ever written to a log**, at any
level. The `toString()` of every entity that holds one leaves it out, the audit log is only ever
handed names and ids, and values that people type are escaped so a newline in a display name
cannot forge a second log line.

Two of those need care rather than discipline, and have it:

- The reveal endpoint **writes its own response body** instead of returning it. Spring's message
  converters log what they are about to write at `DEBUG`, so turning up `org.springframework.web`
  — the first thing anybody does when a request misbehaves — would otherwise put every revealed
  key value in the log in clear. `AuditTrailIntegrationTest` reveals a key with that logging at
  `TRACE` and asserts the value appears nowhere.
- The categories that print HTTP bodies are **pinned at `INFO`** in `application.yml` as a second
  lock, including the client used to talk to Azure, Google, GitHub and Anthropic, whose responses
  carry freshly minted secrets.

### Configuring it

Logs go to the console, which is where a container wants them. Everything is standard Spring Boot:

| Environment variable | Default | What it does |
|----------------------|---------|--------------|
| `LOGGING_FILE_NAME`  | none    | Also write to a file, e.g. `/var/lib/keyportal/logs/portal.log` |
| `PORTAL_LOG_LEVEL`   | `INFO`  | Level for the portal's own packages |
| `PORTAL_LOG_MAX_SIZE` | `50MB` | Size at which a log file is rolled |
| `PORTAL_LOG_MAX_HISTORY` | `90` | Days of rolled files to keep |
| `PORTAL_LOG_TOTAL_SIZE` | `2GB` | Cap on all rolled files together |

To keep the audit trail somewhere of its own — a different file, a different retention, or off
the box entirely — point the `portal.audit` logger at your own appender in a
`logback-spring.xml`. Send it somewhere the portal's own administrators cannot edit: a super
admin can delete a key, and the history page with it, but not a log line that has already left.

## Tests

```bash
./mvnw verify
```

Tests use a throwaway SQLite database under `target/` and test-only credentials from
`src/test/resources/application.properties`, so they don't need a `.env`.

The coverage report is written to `target/site/jacoco/index.html`.

## Security notes

- Form login, CSRF protection on all forms. Accounts are invitation-only; the sole page an
  anonymous visitor can reach is the one behind a valid invitation link.
- The seeded super admin's default password is in this README, so the portal refuses to let that
  account do anything but replace it.
- Invitation tokens are 256 bits of randomness and only their SHA-256 is stored. They expire after
  a day and work once.
- Single sign-on never grants super admin, never re-enables a disabled account, and re-checks
  directory group membership on every sign-in rather than only at provisioning.
- Key values are only sent to the browser from `GET /keys/{id}/value` (with `Cache-Control: no-store`)
  when you click reveal or copy. Each reveal is logged, recorded as "last revealed" on the key,
  and kept in the admin-only history as its own event.
- Failed password sign-ins are counted per account and per calling address, and either count
  reaching the limit refuses further attempts for 15 minutes. The check happens before any
  password is compared, so a right guess during a lockout is worth no more than a wrong one.
  The message never says whether the account exists, or which of the two limits was hit.
  Counters are held in memory, so a restart clears them and each instance of a multi-instance
  deployment counts separately.
- **If you run behind a reverse proxy, set `PORTAL_FORWARD_HEADERS=framework`.** Otherwise every
  request appears to come from the proxy, and one person fumbling their password would lock
  everyone behind it out. It is off by default on purpose: trusting `X-Forwarded-For` when there
  is no proxy in front would let a caller invent a new address for every guess.
- Strict Content-Security-Policy (no inline scripts or styles), `X-Frame-Options: DENY`,
  `Referrer-Policy: same-origin`.
- In the all-in-one image, PostgreSQL listens on loopback inside the container and its port is
  never published, so the database is unreachable from outside it. Neither the database nor the
  portal runs as root; the entrypoint is root only long enough to prepare the data directory.
- The all-in-one image generates an encryption key on first start if you don't supply one, and
  keeps it in the data directory beside the data it encrypts. That is what lets the image start
  ready to use, and it means the volume alone is enough to read everything in it. Set
  `PORTAL_ENCRYPTION_KEY` and keep it elsewhere for anything that matters.
- Every sign-in, sign-out, reveal, edit and refusal is written to an audit log, with the request
  id, user and calling address on each line. No key value, password or client secret ever reaches
  a log at any level — see [Logging and the audit trail](#logging-and-the-audit-trail).
- Notes are **not** encrypted. Don't store secrets in them.
- Run it behind HTTPS (e.g. a reverse proxy) if it's reachable from anywhere other than localhost.

## To do

- **More providers**
  - Connected: Twilio (API keys), SendGrid (API keys)
  - GitLab group access tokens, alongside today's project tokens
- **Auto-replace Google Cloud and AWS keys**: rotation for service accounts and IAM users,
  including deleting the old key at the provider, since neither expires on its own
- **Better error handling**: remove the "Not Found" banner from the 404 page

## License

[MIT](LICENSE) © 2021 Sid Bhat
