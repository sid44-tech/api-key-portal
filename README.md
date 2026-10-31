# API Key Portal

A small self-hosted web portal for keeping track of the API and access keys a group of people
use across different services — a team, a company, or a family.

- See every key your group holds in one place, with the service it belongs to, who added it,
  when it was created and when it expires
- Reveal or copy a key's value on demand — but only the person who added it can.
  Values are never rendered into pages.
- Accounts, groups and roles: invitation-only sign-up, manage your profile, change your password
- Add, edit and delete your own keys
- Create keys directly in a connected service provider (Microsoft Azure AD, Google Cloud) and
  capture the generated secret automatically
- Guided walk-throughs for providers whose keys must be created by hand (GitHub, Anthropic),
  with the pasted key verified live against the provider before it's stored
- Search, filter by service, sort by expiry, and see at a glance what has expired or is about to
- Key values are encrypted at rest with AES-256-GCM
- Light and dark themes, following whatever the operating system is set to
- An admin-only history of who created what, and which keys came from which integration
- Email reminders to a key's owner a month, two weeks, a week and a day before it expires
- Single sign-on against your organisation's identity provider, with access granted by directory group

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
| `PORTAL_ADMIN_EMAIL`          | Email of the seeded super admin (default `admin@localhost`)             |
| `PORTAL_ADMIN_PASSWORD`       | Its starting password (default `changeme`), which must be changed at first sign-in |
| `PORTAL_ADMIN_PASSWORD_HASH`  | BCrypt hash to use instead of the above. Optional — see below           |
| `PORTAL_ADMIN_GROUP`          | Group the seeded super admin administers (default `Administrators`)     |
| `PORTAL_DB_PATH`              | SQLite database file location (default `./keyportal.db`)                |
| `PORTAL_BASE_URL`             | URL invitation links point at. Blank = whatever host the admin used     |
| `PORTAL_MAIL_FROM`            | From address on invitation emails (default `no-reply@localhost`)        |
| `PORTAL_INVITE_VALID_FOR`     | How long an invitation lasts (default `24h`)                            |
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

> **Keep the encryption key safe and backed up.** Key values can't be decrypted without it, and
> changing it makes every stored value unreadable.

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
through it** without ever seeing the provider credential. Only an admin of the owning group can
connect, re-test or remove one — the stored credential can create further credentials, so it is
not a member's to manage. Nobody outside the group can see it at all. An integration always
belongs to exactly one group; connecting one while you aren't in a group is refused rather than
leaving credentials nobody can use.

## History

Everything created in the portal is recorded, and administrators can read it back under
**History**: who did it, when, what they did it to, and — for keys — what kind of key it was and
which integration or walk-through it came through. A group admin sees their own group; a super
admin sees every group; members don't see the page at all.

Recorded today: keys created and deleted, integrations connected and removed, invitations sent,
accounts created from an invitation, and groups created.

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

In both cases the portal verifies the credentials against the provider before saving them,
encrypted, in the local database. Once connected, the **Add key** screen offers "create it in a
connected service": pick the integration, name the client, done. Provider-side expiry (where the
provider has one) is tracked like any other key's.

### Guided walk-throughs (GitHub, Anthropic)

Some providers have no API for creating keys — GitHub personal access tokens and Anthropic API
keys can only be created by hand in their consoles. For those, the **Add key** screen offers
guided walk-throughs instead: step-by-step instructions with deep links, then the portal verifies
the pasted key live against the provider before storing it (and, for GitHub, reads the token's
expiry and scopes from the verification response automatically). A saved key is therefore known
to have worked at least once.

> An integration credential can create new credentials in your tenant/project, which makes it the
> most sensitive value the portal stores. It is encrypted at rest and never rendered back into a page.

## Docker

```bash
docker build -t api-key-portal .
```

```bash
docker run -p 8080:8080 --env-file .env -v keyportal-data:/app/data api-key-portal
```

The image sets `PORTAL_DB_PATH=/app/data/keyportal.db`, so the SQLite database lives on the
`keyportal-data` volume and survives container rebuilds.

`.env` is excluded from the build context, so secrets are never baked into the image. Add
`-e SPRING_PROFILES_ACTIVE=postgres` to use Postgres.

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
  when you click reveal or copy. Each reveal is logged and recorded as "last revealed" on the key.
- Strict Content-Security-Policy (no inline scripts or styles), `X-Frame-Options: DENY`,
  `Referrer-Policy: same-origin`.
- Notes are **not** encrypted. Don't store secrets in them.
- Run it behind HTTPS (e.g. a reverse proxy) if it's reachable from anywhere other than localhost.

## To do

- Add 2-factor auth to login
- Add better logging and error handling to the app
- Build a deployment pipeline in GitHub
- Add more service integrations (AWS, Stripe) for generating keys from the portal
- Replace the key table with a card layout on phones (it scrolls sideways for now)

## License

[MIT](LICENSE) © 2021 Sid Bhat
