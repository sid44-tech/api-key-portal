# API Key Portal

A small self-hosted web portal for keeping track of the API and access keys I use across different services.

- See every key in one place, with the service it belongs to, when it was created and when it expires
- Reveal or copy a key's value on demand. Values are never rendered into pages.
- Add, edit and delete keys
- Search, filter by service, sort by expiry, and see at a glance what has expired or is about to
- Key values are encrypted at rest with AES-256-GCM

Built with Java 11, Spring Boot 2.5, Spring Security, Spring Data JPA, Flyway, Thymeleaf and Bootstrap 5.

## Running locally

Requires JDK 11.

```bash
./mvnw spring-boot:run
```

Then open <http://localhost:8080> and sign in with your configured username and password.

With no profile active the app uses the `dev` profile (`application-dev.yml`). That profile has a
**throwaway** encryption key and password and stores data in an H2 file database under `./data`.
Don't put real keys in a dev instance.

## Configuration

Outside the `dev` profile the following must be set, otherwise the app refuses to start:

| Environment variable    | Description                                                         |
|-------------------------|---------------------------------------------------------------------|
| `PORTAL_ENCRYPTION_KEY` | Base64 encoded 256-bit AES key. Generate with `openssl rand -base64 32` |
| `PORTAL_PASSWORD_HASH`  | BCrypt hash of the login password                                   |
| `PORTAL_USERNAME`       | Login username (default `admin`)                                    |

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

A local database is available through Docker Compose:

```bash
docker-compose up -d
```

```bash
PORTAL_ENCRYPTION_KEY=... PORTAL_PASSWORD_HASH=... ./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

Flyway manages the schema (`src/main/resources/db/migration`).

## Docker

```bash
docker build -t api-key-portal .
```

```bash
docker run -p 8080:8080 \
  -e PORTAL_ENCRYPTION_KEY=... \
  -e PORTAL_PASSWORD_HASH=... \
  -v keyportal-data:/app/data \
  api-key-portal
```

The image runs with the `prod` profile, so it never falls back to the dev secrets. Add
`-e SPRING_PROFILES_ACTIVE=prod,postgres` and the `PORTAL_DB_*` variables to use Postgres.

## Tests

```bash
./mvnw verify
```

The coverage report is written to `target/site/jacoco/index.html`.

## Security notes

- Single user, form login, CSRF protection on all forms.
- Key values are only sent to the browser from `GET /keys/{id}/value` (with `Cache-Control: no-store`)
  when you click reveal or copy. Each reveal is logged and recorded as "last revealed" on the key.
- Strict Content-Security-Policy (no inline scripts or styles), `X-Frame-Options: DENY`,
  `Referrer-Policy: same-origin`.
- Notes are **not** encrypted. Don't store secrets in them.
- Run it behind HTTPS (e.g. a reverse proxy) if it's reachable from anywhere other than localhost.
