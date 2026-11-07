<p align="center"><a href="../README.md"><img src="assets/banner.svg" alt="Key Management Portal" width="60%"></a></p>

<p align="center"><sub><a href="../README.md">🏠 Home</a> · <a href="configuration.md">⚙️ Configuration</a> · <b>🐳 Deployment</b> · <a href="administration.md">👥 Administration</a> · <a href="keys.md">🔑 Keys</a> · <a href="integrations.md">🔌 Integrations</a> · <a href="app-access.md">🤖 App access</a> · <a href="security.md">🛡️ Security</a></sub></p>

# 🐳 Packaging, deployment and health checks

## Packaging and deployment

### As a Spring Boot app

```bash
./mvnw package
PORTAL_ENCRYPTION_KEY=... java -jar target/api-key-portal-1.0.0.jar
```

Runs anywhere there's a JDK 25; SQLite beside it unless pointed at PostgreSQL. Every build
attaches the jar to the GitHub Actions run.

### As a container, with your own database

```bash
docker build -t api-key-portal .
docker run -p 8080:8080 --env-file .env -v keyportal-data:/app/data api-key-portal
```

The SQLite database lives on the volume (`PORTAL_DB_PATH=/app/data/keyportal.db`). Add
`-e SPRING_PROFILES_ACTIVE=postgres` and the `PORTAL_DB_*` variables for your own PostgreSQL.
`.env` is excluded from the build context, so secrets are never baked into the image.

### As one container with the database inside it

`Dockerfile.allinone` carries the portal and PostgreSQL together. One thing to set — the key:

```bash
docker run -p 8080:8080 -v keyportal-data:/var/lib/keyportal \
    -e PORTAL_ENCRYPTION_KEY="$(openssl rand -base64 32)" \
    ghcr.io/OWNER/api-key-portal:latest-allinone
```

or put the key in `.env` and `docker compose -f docker-compose.allinone.yml up -d`. Keep a copy
of the key somewhere other than the data volume. Sign in as above.

- **Data**: everything that outlives the container — the database and its generated password —
  sits under `PORTAL_DATA_DIR` (default `/var/lib/keyportal`); mount a volume or host directory
  there. Mount nothing and it's a scratch instance.
- **The image refuses to start without `PORTAL_ENCRYPTION_KEY`** and never writes it to the
  volume, so a copied volume — backup, snapshot, stolen disk — is not enough to read what's in
  it. *Upgrading from an older image that generated a key into the volume:* the container stops
  and tells you — set `PORTAL_ENCRYPTION_KEY` to the contents of `encryption-key` in the data
  directory, store a copy elsewhere, delete the file. If you set a different key instead, add
  the old one as a named key and re-encrypt first (see
  [Rotating the encryption key](configuration.md#rotating-the-encryption-key)).
- **Inside**: root only long enough to prepare the data directory, then PostgreSQL and the
  portal as separate unprivileged users; PostgreSQL on loopback only; either process dying takes
  the container with it; `HEALTHCHECK` = the portal answers. Stopping takes longer than Docker's
  ten-second default — pass `--stop-timeout 60`, or use the Compose file, which sets it.
- **The trade**: one container, so the portal and database can't be scaled, restarted or tuned
  independently. Run them separately if you want that.

### Published images

Every push to `main` publishes both images to ghcr; a `v*` tag publishes them as a release.
`latest` / `latest-allinone` move only on release tags; `main` / `main-allinone` track the tip.
Images are `linux/amd64`; build from the Dockerfile for anything else.

## Health checks

Three probes, anonymous because monitors have no session, returning only a status and an HTTP
code to a stranger and every component with its details to a signed-in super admin:

| Endpoint | What it checks | Use it for |
|----------|----------------|------------|
| `/actuator/health/liveness` | Nothing but that the process answers | A heartbeat; restart the process if it stops |
| `/actuator/health/readiness` | The database is reachable and the encryption key encrypts and decrypts | Load balancers and orchestrators: whether this instance can serve |
| `/actuator/health` | All of the above, plus disk space, the SMTP server if one is configured, and the identity provider's discovery document if SSO is on | The deep probe for a monitoring system; external trouble makes it `503` |

The encryption check is a real round trip, not "a key is configured": a wrong key would serve
every page and fail only at the moment somebody reveals a value. The deep probe deliberately
isn't what readiness returns — an SMTP outage means reminders are late, not that the portal
should be taken out of rotation. Both container images carry a `HEALTHCHECK` against the
readiness probe (spoken over bash's `/dev/tcp`, since neither image has curl). Nothing else
from Actuator is exposed.
