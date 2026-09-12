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
ROLLBACK=0
SKIP_BUILD=0

while [ $# -gt 0 ]; do
	case "$1" in
		--dir) DIR="$2"; shift 2 ;;
		--rollback) ROLLBACK=1; shift ;;
		--skip-build) SKIP_BUILD=1; shift ;;
		-*) echo "unknown option: $1" >&2; exit 2 ;;
		*) HOST="$1"; shift ;;
	esac
done

if [ -z "$HOST" ]; then
	echo "usage: tools/deploy.sh user@host [--dir /path] [--rollback] [--skip-build]" >&2
	exit 2
fi

cd "$(dirname "$0")/.."

say() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

# ------------------------------------------------------------------------------------------------
if [ "$ROLLBACK" = 1 ]; then
	say "rolling back on $HOST"
	ssh "$HOST" "cd '$DIR' && \
		test -f .env.previous || { echo 'no .env.previous - nothing to roll back to'; exit 1; }; \
		cp .env .env.failed && cp .env.previous .env && docker compose up -d"
	echo "Rolled back. The image that failed is still loaded, so a re-deploy is cheap."
	exit 0
fi

TAG="$(git rev-parse --short HEAD 2>/dev/null || echo local)"
if [ -n "$(git status --porcelain 2>/dev/null || true)" ]; then
	TAG="$TAG-dirty"
fi
IMAGE="kotatsuredo-server:$TAG"

if [ "$SKIP_BUILD" = 0 ]; then
	say "building $IMAGE for linux/amd64"
	docker build --platform linux/amd64 --provenance=false --sbom=false -t "$IMAGE" .
fi

say "backing up the database first"
# Migrations run at startup, so the dump has to predate the new image rather than follow it.
ssh "$HOST" "cd '$DIR' && mkdir -p backups && \
	docker compose exec -T postgres pg_dump -U kotatsuredo kotatsuredo | gzip > backups/pre-deploy-\$(date +%F-%H%M).sql.gz && \
	ls -lh backups | tail -1"

say "streaming the image over ($(docker image inspect "$IMAGE" --format '{{.Size}}' | awk '{printf "%.0f MB", $1/1000000}') uncompressed)"
docker save "$IMAGE" | gzip -1 | ssh "$HOST" "gunzip | docker load"

say "switching to $IMAGE and restarting"
ssh "$HOST" "cd '$DIR' && \
	cp .env .env.previous && \
	grep -q '^API_IMAGE=' .env && sed -i 's|^API_IMAGE=.*|API_IMAGE=$IMAGE|' .env || echo 'API_IMAGE=$IMAGE' >> .env; \
	docker compose up -d"

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
	ssh "$HOST" "cd '$DIR' && cp .env.previous .env && docker compose up -d"
	echo "Rolled back to the previous image. The new one is loaded but not in use."
	echo "Look at: ssh $HOST 'cd $DIR && docker compose logs --tail=50 api'"
	exit 1
fi
