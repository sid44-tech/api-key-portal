<p align="center"><a href="../README.md"><img src="assets/banner.svg" alt="Key Management Portal" width="60%"></a></p>

<p align="center"><sub><a href="../README.md">🏠 Home</a> · <a href="configuration.md">⚙️ Configuration</a> · <a href="deployment.md">🐳 Deployment</a> · <a href="administration.md">👥 Administration</a> · <b>🔑 Keys</b> · <a href="integrations.md">🔌 Integrations</a> · <a href="app-access.md">🤖 App access</a> · <a href="security.md">🛡️ Security</a></sub></p>

# 🔑 Keys: dashboard, reminders, alerts and automatic replacement

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
- **SendGrid** — as Twilio: a second API key beside the old one, which is deleted once its
  date passes.
- **Twilio** — works like AWS: a second API key in the same account, and the old one deleted
  at Twilio once its date passes. Twilio has no per-account key limit, so there's no slot to
  run out of.

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
