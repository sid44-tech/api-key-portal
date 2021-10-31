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

1. Copy `.env.example` to `.env` and fill in the values (see [Configuration](#configuration)).
   `.env` is git-ignored; never commit it.
2. Start the app:

   ```bash
   ./mvnw spring-boot:run
   ```

3. Open <http://localhost:8080> and sign in with the username and password from your `.env`.

By default data is stored in an H2 file database under `./data`.

## Configuration

Secrets are read from environment variables, or from a `.env` file in the working directory
(loaded with [spring-dotenv](https://github.com/paulschwarz/spring-dotenv)). Real environment
variables take precedence over `.env`. The app refuses to start if the required ones are missing:

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

A local database is available through Docker Compose, which reads its credentials from the same `.env`:

```bash
docker-compose up -d
```

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

Flyway manages the schema (`src/main/resources/db/migration`).

## Docker

```bash
docker build -t api-key-portal .
```

```bash
docker run -p 8080:8080 --env-file .env -v keyportal-data:/app/data api-key-portal
```

`.env` is excluded from the build context, so secrets are never baked into the image. Add
`-e SPRING_PROFILES_ACTIVE=postgres` to use Postgres.

## Tests

```bash
./mvnw verify
```

Tests use an in-memory database and test-only credentials from `src/test/resources/application.properties`,
so they don't need a `.env`.

The coverage report is written to `target/site/jacoco/index.html`.

## Security notes

- Single user, form login, CSRF protection on all forms.
- Key values are only sent to the browser from `GET /keys/{id}/value` (with `Cache-Control: no-store`)
  when you click reveal or copy. Each reveal is logged and recorded as "last revealed" on the key.
- Strict Content-Security-Policy (no inline scripts or styles), `X-Frame-Options: DENY`,
  `Referrer-Policy: same-origin`.
- Notes are **not** encrypted. Don't store secrets in them.
- Run it behind HTTPS (e.g. a reverse proxy) if it's reachable from anywhere other than localhost.

## License

[MIT](LICENSE) © 2021 Sid Bhat
