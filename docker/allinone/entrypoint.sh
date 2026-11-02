#!/usr/bin/env bash
#
# Starts PostgreSQL and the portal in one container, in that order, and keeps
# them together: if either stops, so does the other, and the container exits
# with that process's status rather than sitting there half-dead.
#
# Everything that has to survive a restart lives under one directory - the
# database itself, the generated database password, and the generated encryption
# key. Mount a volume there (PORTAL_DATA_DIR) and the portal picks up exactly
# where it left off; mount nothing and the container is a scratch instance that
# forgets itself when it goes.

set -Eeuo pipefail

log() {
    printf '%s  keyportal  %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"
}

# ---------------------------------------------------------------- directories

DATA_DIR=${PORTAL_DATA_DIR:-/var/lib/keyportal}
export PGDATA="$DATA_DIR/pgdata"

if [ "$(id -u)" != "0" ]; then
    log "FATAL: this image has to start as root so it can prepare $DATA_DIR and"
    log "       then drop to unprivileged users. Neither process runs as root."
    exit 1
fi

mkdir -p "$PGDATA"
# A mounted host directory arrives owned by whoever owns it on the host
chown -R postgres:postgres "$DATA_DIR"
chmod 700 "$PGDATA"

# ------------------------------------------------------------ the DB password
#
# Nothing outside the container can reach this database - the server listens on
# loopback only and the port is never published - so the password is a second
# lock on an inside door rather than the thing keeping anyone out. It still gets
# one: "trust everything on localhost" would mean any other process that ends up
# in this container is a superuser.
#
# Generated on first start and kept beside the data it protects, because the two
# have to stay in step: the role is created once, at initdb, and a password
# handed in later would simply not be the one the server has.

db_password_file="$DATA_DIR/db-password"
db_user=${PORTAL_DB_USERNAME:-keyportal}
db_name=keyportal

if [ -n "${PORTAL_DB_PASSWORD:-}" ]; then
    db_password=$PORTAL_DB_PASSWORD
    log "Using the database password from PORTAL_DB_PASSWORD."
elif [ -s "$db_password_file" ]; then
    db_password=$(cat "$db_password_file")
else
    db_password=$(head -c 32 /dev/urandom | base64 | tr -d '\n')
    log "Generated a database password; keeping it in $db_password_file."
fi

printf '%s' "$db_password" > "$db_password_file"
chown postgres:postgres "$db_password_file"
chmod 600 "$db_password_file"

# ---------------------------------------------------------- the portal's key
#
# Key values are encrypted with this, and without it the portal refuses to
# start. An image that cannot run until you have generated a key by hand is not
# "ready to run", so one is generated here if none was given.
#
# That is a real trade and worth being plain about: a generated key lives in the
# same directory as the ciphertext it protects, so anyone holding a copy of that
# directory - a backup, a snapshot, a stolen disk - holds both halves. Set
# PORTAL_ENCRYPTION_KEY for anything you would mind losing.

encryption_key_file="$DATA_DIR/encryption-key"

if [ -z "${PORTAL_ENCRYPTION_KEY:-}" ]; then
    if [ -s "$encryption_key_file" ]; then
        PORTAL_ENCRYPTION_KEY=$(cat "$encryption_key_file")
    else
        PORTAL_ENCRYPTION_KEY=$(head -c 32 /dev/urandom | base64 | tr -d '\n')
        printf '%s' "$PORTAL_ENCRYPTION_KEY" > "$encryption_key_file"
        log "Generated an encryption key; keeping it in $encryption_key_file."
    fi
    chown portal:portal "$encryption_key_file"
    chmod 600 "$encryption_key_file"
    log "WARNING: the encryption key is stored next to the data it encrypts."
    log "WARNING: back it up separately and set PORTAL_ENCRYPTION_KEY instead -"
    log "WARNING: lose this file and every stored key value is unreadable."
    export PORTAL_ENCRYPTION_KEY
fi

# ------------------------------------------------------------------- postgres

postgres_pid=
app_pid=
shutting_down=

start_postgres() {
    log "Starting PostgreSQL in $PGDATA"
    # The official entrypoint does the first-run initdb and the drop from root
    # to the postgres user. listen_addresses keeps the server on loopback: this
    # database belongs to this container, and publishing 5432 by accident should
    # not be enough to reach it.
    POSTGRES_USER=$db_user \
    POSTGRES_PASSWORD=$db_password \
    POSTGRES_DB=$db_name \
        docker-entrypoint.sh postgres -c listen_addresses=127.0.0.1 &
    postgres_pid=$!
}

wait_for_postgres() {
    local waited=0
    until gosu postgres pg_isready --quiet --host=127.0.0.1 --username="$db_user" --dbname="$db_name"; do
        if ! kill -0 "$postgres_pid" 2>/dev/null; then
            log "FATAL: PostgreSQL stopped before it was ready."
            wait "$postgres_pid" || exit $?
            exit 1
        fi
        if [ "$waited" -ge "${PORTAL_DB_START_TIMEOUT:-120}" ]; then
            log "FATAL: PostgreSQL was not ready after ${waited}s."
            stop_all
            exit 1
        fi
        sleep 1
        waited=$((waited + 1))
    done
    log "PostgreSQL is ready after ${waited}s."
}

# Keeps the role's password as whatever this start resolved it to. Without this
# an existing volume would keep the password from the day it was created, and
# setting PORTAL_DB_PASSWORD on an upgrade would quietly lock the portal out of
# its own database. :'...' leaves the quoting to psql rather than to us.
sync_db_password() {
    printf "SELECT format('ALTER ROLE %%I WITH PASSWORD %%L', :'role', :'password');\n\\gexec\n" \
        | gosu postgres psql --quiet --no-align --tuples-only --no-psqlrc \
            --set=ON_ERROR_STOP=1 \
            --set=role="$db_user" --set=password="$db_password" \
            --username="$db_user" --dbname=postgres > /dev/null
}

# ---------------------------------------------------------------- the portal

start_app() {
    log "Starting the portal"
    SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-postgres} \
    PORTAL_DB_URL="jdbc:postgresql://127.0.0.1:5432/$db_name" \
    PORTAL_DB_USERNAME="$db_user" \
    PORTAL_DB_PASSWORD="$db_password" \
    HOME=/tmp \
        gosu portal java ${JAVA_OPTS:-} -jar /app/app.jar &
    app_pid=$!
}

# ---------------------------------------------------------------- shutting down

# Signals one process and waits a bounded time for it to go, rather than until
# it feels like it: Docker will SIGKILL the whole container ten seconds after it
# asked us to stop, and the database deserves to be told to flush before that
# happens to it. A process still there at the end of its grace period is killed,
# so the order below - portal first, then database - is always honoured.
stop_process() {
    local pid=$1 signal=$2 name=$3 grace=$4 waited=0

    [ -n "$pid" ] || return 0
    kill -0 "$pid" 2>/dev/null || return 0

    log "Stopping $name"
    kill "-$signal" "$pid" 2>/dev/null || true
    while kill -0 "$pid" 2>/dev/null; do
        if [ "$waited" -ge "$grace" ]; then
            log "$name did not stop within ${grace}s; killing it."
            kill -KILL "$pid" 2>/dev/null || true
            break
        fi
        sleep 1 || true
        waited=$((waited + 1))
    done
    wait "$pid" 2>/dev/null || true
}

stop_all() {
    [ -n "$shutting_down" ] && return 0
    shutting_down=yes

    # The portal first, so the database is not closing connections out from
    # under a request that is still being served.
    stop_process "$app_pid" TERM "the portal" "${PORTAL_STOP_TIMEOUT:-10}"
    # SIGINT is PostgreSQL's fast shutdown: roll back open transactions and
    # close down now. SIGTERM would wait for clients that have already gone.
    stop_process "$postgres_pid" INT "PostgreSQL" "${PORTAL_DB_STOP_TIMEOUT:-15}"
    log "Stopped."
}

trap 'stop_all; exit 0' TERM INT

start_postgres
wait_for_postgres
sync_db_password
start_app

log "The portal is at http://localhost:8080 (inside the container)."

# Whichever of the two goes first takes the container with it, so a crashed
# portal or a crashed database is something the restart policy can see rather
# than a container that is still "up" with half of it missing. Polling rather
# than `wait -n`, which cannot be handed specific pids before bash 5.1.
status=0
while :; do
    if ! kill -0 "$postgres_pid" 2>/dev/null; then
        wait "$postgres_pid" 2>/dev/null || status=$?
        log "PostgreSQL exited with status $status; shutting the container down."
        break
    fi
    if ! kill -0 "$app_pid" 2>/dev/null; then
        wait "$app_pid" 2>/dev/null || status=$?
        log "The portal exited with status $status; shutting the container down."
        break
    fi
    sleep 1 || true
done

stop_all
exit "$status"
