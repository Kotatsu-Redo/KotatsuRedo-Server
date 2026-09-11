# Deploying an instance

Everything below assumes one small VPS. The capacity work in [PLAN.md](PLAN.md) §3 sizes 25k active
users at under two requests a second, so a **2 vCPU / 8 GB box (Hetzner CX32, ~€7/month)** is the
target, not a starting point to grow from.

---

## 1. DNS and the box

Point an A record at the server before you start — Caddy asks Let's Encrypt for a certificate on
first boot and that only works once the name resolves.

```
community.example.com.  A  203.0.113.10
```

Open 80 and 443. Nothing else needs to be reachable: Postgres is on the compose network only, and
Caddy's own admin API is disabled in `deploy/Caddyfile`.

**Pick a host that will forward a complaint rather than null-route you on the first one.** This is a
discussion service that never touches manga content, but you will still get notices, and the
difference between a provider that emails you and one that pulls the plug is the difference between
an afternoon and a migration.

The official instance is on **IONOS, in their Paris datacentre**. That choice is load-bearing for the policies: under the LCEN a *non-professional*
publisher may stay unnamed publicly, provided the host holds their identity — which is what lets
[`legal/`](legal/) name the project rather than a person. Germany, by contrast, requires an Impressum
with a real name and postal address. **Whichever host you pick, its name, registered address and
telephone number must be published in your terms**, and the account you pay with must carry your real
details.

## 2. Configuration

```sh
git clone https://github.com/Kotatsu-Redo/kotatsuredo-server.git
cd kotatsuredo-server
cp .env.example .env
```

Edit `.env`. The three that matter:

```sh
SERVER_DOMAIN=community.example.com
APP_ENV=production

# Generate, don't invent:
POSTGRES_PASSWORD=$(openssl rand -base64 24)
DATABASE_PASSWORD=   # the same value
DEVICE_PEPPER=$(openssl rand -base64 32)

RULES_URL=https://community.example.com/rules
```

**`DEVICE_PEPPER` has no default and changing it invalidates every device ban.** It is what stops
someone holding a stolen database from testing a guessed `ANDROID_ID` against it. Back it up
wherever you keep the database password.

Set the bootstrap admin for the first boot only:

```sh
MOD_BOOTSTRAP_USERNAME=yourname
MOD_BOOTSTRAP_PASSWORD=   # 12 characters minimum
```

## 3. Start it

```sh
docker compose up -d --build
curl https://community.example.com/v1/health
```

Migrations run at startup and abort the process on failure — a server on an unexpected schema is
worse than a server that is plainly down, so a crash loop here means read the logs, not restart it.

```sh
docker compose logs -f api
```

You should see Flyway apply V1–V10, then `Filter loaded: … rules across … languages`.

## 4. Claim the moderation panel

Open `https://community.example.com/admin` and sign in with the bootstrap credentials.

You will be asked to set up an authenticator immediately, and **nothing else in the panel answers
until you do** — that is deliberate. Add the secret to Aegis, 1Password or Google Authenticator,
confirm the code, and sign back in.

Then:

```sh
# Remove MOD_BOOTSTRAP_* from .env and restart. The path disables itself once any moderator exists,
# so leaving them is harmless - but a standing credential in a file is a standing credential.
docker compose up -d
```

Invite the rest of the team from **Admin → Moderators**. Give out `moderator` unless someone needs
to merge works, reverse device bans or manage accounts.

## 5. Point the app at it

In Kotatsu-Redo: **Settings → Community → Server**, enter `https://community.example.com`. The
screen reports whether it can reach the instance, which is the difference between "the feature is
off" and "the feature is broken" while you are setting one up.

Turn the feature on in the same screen, then open any manga: the rating row and a **Comments**
button appear under the cover.

---

## Before you let anyone else in

These are not optional polish. They are the things that are painful to add after there are users.

### Watch the filter for the first few weeks

The lists are curated for all fifteen launch languages and load at boot, so nothing is blocking here.
What is worth your attention is **Filter health** in the panel: a language whose block rate is an
outlier means a bad list, not a rude userbase, and the per-rule false-positive rate will show you
which rules to demote before anyone complains twice.

A native-speaker pass on `id`, `vi`, `th`, `ko`, `ja`, `zh`, `pl` and `tr` is the one improvement
worth scheduling — see [the seed README](src/main/resources/filter/seed/README.md).

### Read the policies, and make them yours

[`legal/`](legal/) already holds the terms, content policy and privacy notice, and the instance serves
them at `/terms`, `/content-policy` and `/privacy`. They are written for the official instance —
France, 18+, published non-professionally by the project.

**If you are running your own instance, they are not yours until you change three things**, and each
document ends with a Self-hosters section naming them: the operator, the contact, and the law that
follows from where you host. The parts describing what the software collects and for how long are
properties of the code and stay true — with one exception you control: the claim that IP addresses are
never stored holds only while access logging stays off in `deploy/Caddyfile`.

`/rules` is the short user-facing version, linked from every filter rejection.

### Check the terms you depend on

Kitsu and MangaUpdates are consulted on demand when a work is genuinely unknown. Read their terms and
record the outcome in the README before this is public.

### Register a DMCA agent

If you host in the US. It is inexpensive and it is a precondition, not a nicety.

---

## Operating it

### Backups

```sh
docker compose exec -T postgres pg_dump -U kotatsuredo kotatsuredo | gzip > backup-$(date +%F).sql.gz
```

Rotate them on a **seven-day** cycle, because that is what the privacy notice promises — deleted
content is gone from the live database at once and gone everywhere within a week:

```sh
find /var/backups/kotatsuredo -name 'backup-*.sql.gz' -mtime +7 -delete
```

**Run a restore drill once before launch.** An untested backup is a belief, not a backup, and this is
the one thing that only gets noticed when it is already too late.

### Monitoring

Point uptime monitoring at `GET /v1/health`. It returns `503` when the database is unreachable, which
is the failure you actually care about — a process that is up and cannot read anything looks fine to a
TCP check.

### What the logs do and do not contain

Access logging is **off** in `deploy/Caddyfile`, permanently and on purpose: the privacy notice says
IP addresses are never stored, and that claim is true only while that block exists. The application
log records method, path and status — never headers, never bodies, never the bearer secret.

Do not "temporarily" turn access logs on to debug something. Use the application log.

### Upgrading

```sh
git pull && docker compose up -d --build
```

Migrations are forward-only and run at startup. Take a dump first.

---

## Testing before you point real users at it

Run the stack locally with the database and the API published, so a phone on the same network can
reach them:

```sh
docker compose -f docker-compose.yml -f docker-compose.test.yml up -d
```

The API is then plain HTTP on `8080`, because Caddy serves a self-signed certificate for `localhost`
and a phone will refuse it. Point the app at `http://<your LAN ip>:8080` and try the whole loop: post
a comment, reply to it from a second device, vote, get the reply notification, trip the filter, press
"this was wrong", and watch it arrive in the panel's **Blocked** queue.
