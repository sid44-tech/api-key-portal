<p align="center"><a href="../README.md"><img src="assets/banner.svg" alt="Key Management Portal" width="60%"></a></p>

<p align="center"><sub><a href="../README.md">🏠 Home</a> · <b>⚙️ Configuration</b> · <a href="deployment.md">🐳 Deployment</a> · <a href="administration.md">👥 Administration</a> · <a href="keys.md">🔑 Keys</a> · <a href="integrations.md">🔌 Integrations</a> · <a href="app-access.md">🤖 App access</a> · <a href="security.md">🛡️ Security</a></sub></p>

# ⚙️ Configuration and operations

## Configuration

Data lives in a SQLite file at `./keyportal.db` by default; set `PORTAL_DB_PATH` to move it, or
use [PostgreSQL](#postgresql).

Settings come from environment variables or a `.env` file in the working directory (real
environment variables win). Only `PORTAL_ENCRYPTION_KEY` is required; the app refuses to start
without it.

| Environment variable          | Description                                                             |
|-------------------------------|-------------------------------------------------------------------------|
| `PORTAL_ENCRYPTION_KEY`       | Base64 encoded 256-bit AES key. Generate with `openssl rand -base64 32` |
| `PORTAL_ENCRYPTION_KEYS_<ID>` | Additional named keys, for rotation — see below                         |
| `PORTAL_ENCRYPTION_ACTIVE_KEY`| Which named key new values are encrypted with                           |
| `PORTAL_ADMIN_EMAIL`          | Email of the seeded super admin (default `admin@localhost`)             |
| `PORTAL_ADMIN_PASSWORD`       | Its starting password (default: random, printed once in the log), must be changed at first sign-in |
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
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

Flyway manages the schema (`src/main/resources/db/migration`).
