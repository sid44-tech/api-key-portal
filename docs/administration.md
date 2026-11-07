<p align="center"><a href="../README.md"><img src="assets/banner.svg" alt="Key Management Portal" width="60%"></a></p>

<p align="center"><sub><a href="../README.md">🏠 Home</a> · <a href="configuration.md">⚙️ Configuration</a> · <a href="deployment.md">🐳 Deployment</a> · <b>👥 Administration</b> · <a href="keys.md">🔑 Keys</a> · <a href="integrations.md">🔌 Integrations</a> · <a href="app-access.md">🤖 App access</a> · <a href="security.md">🛡️ Security</a></sub></p>

# 👥 Accounts, access and administration

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
