# API Key Portal

A small self-hosted web portal for keeping track of the API and access keys I use across different services.

- See every key in one place, with the service it belongs to, when it was created and when it expires
- Reveal or copy a key's value on demand. Values are never rendered into pages.
- Add, edit and delete keys
- Create keys directly in a connected service provider (Microsoft Azure AD, Google Cloud) and
  capture the generated secret automatically
- Guided walk-throughs for providers whose keys must be created by hand (GitHub, Anthropic),
  with the pasted key verified live against the provider before it's stored
- Search, filter by service, sort by expiry, and see at a glance what has expired or is about to
- Key values are encrypted at rest with AES-256-GCM

Built with Java 25, Spring Boot 4.1, Spring Security, Spring Data JPA, Flyway, Thymeleaf and Bootstrap 5.

## Running locally

Requires JDK 25.

1. Copy `.env.example` to `.env` and fill in the values (see [Configuration](#configuration)).
   `.env` is git-ignored; never commit it.
2. Start the app:

   ```bash
   ./mvnw spring-boot:run
   ```

3. Open <http://localhost:8080> and sign in with the username and password from your `.env`.

By default data is stored in a SQLite database at `./keyportal.db` (created automatically on
first start, reused on subsequent starts). Set `PORTAL_DB_PATH` to keep it somewhere else,
e.g. `PORTAL_DB_PATH=/var/lib/keyportal/keyportal.db`.

## Configuration

Secrets are read from environment variables, or from a `.env` file in the working directory
(loaded with [spring-dotenv](https://github.com/paulschwarz/spring-dotenv)). Real environment
variables take precedence over `.env`. The app refuses to start if the required ones are missing:

| Environment variable    | Description                                                         |
|-------------------------|---------------------------------------------------------------------|
| `PORTAL_ENCRYPTION_KEY` | Base64 encoded 256-bit AES key. Generate with `openssl rand -base64 32` |
| `PORTAL_PASSWORD_HASH`  | BCrypt hash of the login password                                   |
| `PORTAL_USERNAME`       | Login username (default `admin`)                                    |
| `PORTAL_DB_PATH`        | SQLite database file location (default `./keyportal.db`)            |

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

- Single user, form login, CSRF protection on all forms.
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
- Add alerts for keys about to expire
- Add more service integrations (AWS, Stripe) for generating keys from the portal
- Add a dark mode (auto) feature
- Update the UI to be more responsive on mobile

## License

[MIT](LICENSE) © 2021 Sid Bhat
