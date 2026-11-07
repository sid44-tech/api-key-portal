<p align="center"><a href="../README.md"><img src="assets/banner.svg" alt="Key Management Portal" width="60%"></a></p>

<p align="center"><sub><a href="../README.md">🏠 Home</a> · <a href="configuration.md">⚙️ Configuration</a> · <a href="deployment.md">🐳 Deployment</a> · <a href="administration.md">👥 Administration</a> · <a href="keys.md">🔑 Keys</a> · <b>🔌 Integrations</b> · <a href="app-access.md">🤖 App access</a> · <a href="security.md">🛡️ Security</a></sub></p>

# 🔌 Service integrations

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
  [automatic replacement](keys.md#automatic-key-replacement). Setup: enable the IAM API, create a
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
- **Twilio** — creates a *Standard* API key (it can use Twilio's APIs but not manage keys or the
  account), stored as account SID, key SID and secret together. Expiry works as for AWS. Setup:
  a *Main* API key for the portal — Twilio lets only Main keys (or the Auth Token) manage keys,
  so this credential has full reach over the account; a subaccount limits that to one subaccount.
  Keys are created in the US1 region.
- **SendGrid** — creates an API key with either *Mail Send* only or everything the portal's key
  can do minus key management (chosen per integration). Expiry works as for AWS. Setup: a
  Restricted key with *API Keys: Full Access* plus whatever minted keys may get — SendGrid only
  lets a key grant scopes it holds. EU-pinned accounts set `portal.sendgrid.api-base-url` to
  `https://api.eu.sendgrid.com`.

### Guided walk-throughs (GitHub, Anthropic, Atlassian, OpenAI, Stripe)

Providers with no key-creation API get step-by-step instructions with deep links instead; the
pasted key is verified live against the provider before it's stored (GitHub's expiry and scopes
are read from the verification response). Atlassian's credential is *email plus token*, verified
together; only the token is stored encrypted, the email goes in the notes.

> An integration credential can create new credentials in your tenant/project — the most
> sensitive value the portal stores. Encrypted at rest, never rendered back into a page.
