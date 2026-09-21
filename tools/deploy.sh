#!/usr/bin/env bash
#
# Builds the image here and puts it on the server, over SSH, in one command.
#
#     tools/deploy.sh root@203.0.113.10
#     tools/deploy.sh root@203.0.113.10 --dir /root/kotatsuserver
#     tools/deploy.sh root@203.0.113.10 --rollback     # back to the previous image
#
# Why not build on the server: the Dockerfile's build stage is a full Gradle run against a JDK image,
# which on a small VPS is slow at best and an OOM at worst. The box stays a runtime-only box; nothing
# but Docker is ever installed on it.
#
# Why not a registry: this is a one-server project with no CI yet, and `docker save | ssh docker load`
# needs no account, no credentials on the box, and no public image of a service whose whole design is
# about not leaking anything. Graduate to GHCR when there is more than one instance to feed.
#
# What it does, in order: build, back up the database, stream the image over, point .env at the new
# tag, restart, health check - and put the old tag back automatically if the health check fails.
set -euo pipefail

HOST=""
DIR="/root/kotatsuserver"
PROXY="nginx"
ROLLBACK=0
SKIP_BUILD=0

while [ $# -gt 0 ]; do
	case "$1" in
		--dir) DIR="$2"; shift 2 ;;
		--proxy) PROXY="$2"; shift 2 ;;
		--rollback) ROLLBACK=1; shift ;;
		--skip-build) SKIP_BUILD=1; shift ;;
		-*) echo "unknown option: $1" >&2; exit 2 ;;
		*) HOST="$1"; shift ;;
	esac
done

if [ -z "$HOST" ]; then
	echo "usage: tools/deploy.sh user@host [--dir /path] [--proxy nginx|caddy] [--rollback] [--skip-build]" >&2
	exit 2
fi

cd "$(dirname "$0")/.."

say() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

# ------------------------------------------------------------------------------------------------
if [ "$ROLLBACK" = 1 ]; then
	say "rolling back on $HOST"
	ssh "$HOST" "cd '$DIR' && \
		test -f .env.previous || { echo 'no .env.previous - nothing to roll back to'; exit 1; }; \
		cp .env .env.failed && cp .env.previous .env && \
		if test -f docker-compose.yml.previous; then cp docker-compose.yml.previous docker-compose.yml; fi && \
		docker compose up -d"
	echo "Rolled back. The image that failed is still loaded, so a re-deploy is cheap."
	exit 0
fi

TAG="$(git rev-parse --short HEAD 2>/dev/null || echo local)"
if [ -n "$(git status --porcelain 2>/dev/null || true)" ]; then
	# A mutable `commit-dirty` tag makes rollback point at whichever image was built most recently,
	# not the image that was actually running. Keep dirty deployments unique just like commits are.
	TAG="$TAG-dirty-$(date -u +%Y%m%d%H%M%S)"
fi
IMAGE="kotatsuredo-server:$TAG"

if [ "$SKIP_BUILD" = 0 ]; then
	say "building $IMAGE for linux/amd64"
	docker build --platform linux/amd64 --provenance=false --sbom=false -t "$IMAGE" .
fi

say "backing up the database first"
# Migrations run at startup, so the dump has to predate the new image rather than follow it.
ssh "$HOST" "cd '$DIR' && mkdir -p backups && \
	dump=backups/pre-deploy-\$(date +%F-%H%M).sql.gz && \
	docker compose exec -T postgres pg_dump -U kotatsuredo kotatsuredo | gzip > \"\$dump\" && \
	ls -lh \"\$dump\""

say "streaming the image over ($(docker image inspect "$IMAGE" --format '{{.Size}}' | awk '{printf "%.0f MB", $1/1000000}') uncompressed)"
docker save "$IMAGE" | gzip -1 | ssh "$HOST" "gunzip | docker load"

say "syncing docker-compose.yml"
# The compose file is generated from the repository's, so anything added to the api's `environment:`
# block arrives with the deploy. It used not to: the file on the server stayed frozen at whatever the
# first bundle wrote, and every variable added afterwards was silently absent from the container
# while .env and the documentation both insisted it was there.
#
# .env is never touched - it holds the secrets and is the one file the server owns.
rm -rf .deploy-sync
python tools/make_bundle.py --no-image --skip-build --proxy "$PROXY" --out .deploy-sync >/dev/null
COMPOSE="$(find .deploy-sync -name docker-compose.yml | head -1)"
test -n "$COMPOSE" || { echo "could not generate a compose file" >&2; exit 1; }
ssh "$HOST" "cd '$DIR' && cp docker-compose.yml docker-compose.yml.previous"
scp -q "$COMPOSE" "$HOST:$DIR/docker-compose.yml"
rm -rf .deploy-sync

say "switching to $IMAGE and restarting"
if ! ssh "$HOST" "cd '$DIR' && \
	cp .env .env.previous && \
	grep -q '^API_IMAGE=' .env && sed -i 's|^API_IMAGE=.*|API_IMAGE=$IMAGE|' .env || echo 'API_IMAGE=$IMAGE' >> .env; \
	docker compose up -d"; then
	say "compose start failed - rolling back"
	ssh "$HOST" "cd '$DIR' && cp .env.previous .env && \
		cp docker-compose.yml.previous docker-compose.yml && docker compose up -d"
	exit 1
fi

say "health"
# The api migrates and seeds on boot, so give it a moment before deciding it is broken.
if ssh "$HOST" "for i in \$(seq 1 30); do \
		curl -sf http://127.0.0.1:\${API_HOST_PORT:-8787}/v1/health >/dev/null 2>&1 && exit 0; \
		sleep 2; \
	done; exit 1"; then
	ssh "$HOST" "cd '$DIR' && curl -s http://127.0.0.1:8787/v1/health; echo"
	echo
	echo "Deployed $IMAGE."
	echo "Previous .env kept as .env.previous - tools/deploy.sh $HOST --rollback puts it back."
else
	say "health check failed - rolling back"
	ssh "$HOST" "cd '$DIR' && cp .env.previous .env && \
		cp docker-compose.yml.previous docker-compose.yml && docker compose up -d"
	echo "Rolled back to the previous image. The new one is loaded but not in use."
	echo "Look at: ssh $HOST 'cd $DIR && docker compose logs --tail=50 api'"
	exit 1
fi
