<p align="center"><a href="../README.md"><img src="assets/banner.svg" alt="Key Management Portal" width="60%"></a></p>

<p align="center"><sub><a href="../README.md">🏠 Home</a> · <a href="configuration.md">⚙️ Configuration</a> · <a href="deployment.md">🐳 Deployment</a> · <a href="administration.md">👥 Administration</a> · <a href="keys.md">🔑 Keys</a> · <a href="integrations.md">🔌 Integrations</a> · <a href="app-access.md">🤖 App access</a> · <a href="security.md">🛡️ Security</a> · <b>🧰 Development</b></sub></p>

# 🧰 Local development stand-ins

Two small programs in `dev/` stand in for outside services, so the Azure integration and the
portal's emails can be tried on a laptop with no Azure tenant, no mail account and no internet
access. Each is a single Java file that runs straight from source with Java 21 or newer; there is
nothing to build.

## Fake Azure AD

Answers the requests the portal makes to Azure AD: signing in with the integration's credentials,
creating app registrations, and adding, listing and removing their client secrets.

```bash
java dev/FakeAzure.java
```

It listens on port 9090. Start the portal pointing at it:

```bash
PORTAL_AZURE_LOGIN_BASE_URL=http://localhost:9090 PORTAL_AZURE_GRAPH_BASE_URL=http://localhost:9090 mvn spring-boot:run
```

Then connect an Azure AD integration under **Integrations** as usual. Any tenant ID and client ID
are accepted, and any client secret that isn't empty. Keys created through the integration get a
secret from the fake, exactly as they would from Azure.

| Option | Default | What it does |
|---|---|---|
| `--port` | `9090` | Port to listen on |
| `--delay-seconds` | `0` | Holds every reply back this long, like a slow tenant. Keep it under 30, the portal's timeout for Azure calls |
| `--max-secret-days` | none | Caps how long a new secret lasts, like a tenant's credential lifetime policy. The portal records the shorter date, so a key can be made to come up for expiry or replacement within days |

Every request is printed as one line, for example
`POST /v1.0/applications/<id>/addPassword -> 200 created secret <keyId> on <id> (secrets created: 2)`.
To see everything it holds:

```bash
curl -s localhost:9090/fake/state
```

This lists each app registration with its live secrets (never their values), plus running totals
of tokens issued and secrets created and removed. Everything is kept in memory, so stopping the
fake forgets it, and keys created through it before a restart can no longer be replaced or deleted
at the fake.

## Fake mail server

Accepts every email and saves it instead of delivering it: invitations, expiry reminders and
replacement notices.

```bash
java dev/FakeMailServer.java
```

It listens on port 2525 and saves each message as a numbered `.eml` file in `./fake-mail`
(`--port` and `--dir` change both). Start the portal with:

```bash
SPRING_MAIL_HOST=localhost SPRING_MAIL_PORT=2525 mvn spring-boot:run
```

Leave the mail username, password and STARTTLS settings unset: the fake speaks plain SMTP only.
Each message is also printed as one line with its recipients and subject, so counting what was
sent is a matter of `ls fake-mail | wc -l`.

## Teams and Slack

There is no fake for chat alerts. The portal only posts to `https://` webhook addresses on public
hosts, so a server on `localhost` is refused when the channel is saved. That rule stops the alert
settings from being used to reach machines inside your network, so it has no off switch. Expiry
reminders and replacement notices also go out by email, which the fake mail server catches.
