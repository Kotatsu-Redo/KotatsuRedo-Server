#!/bin/sh
# Restarts a container that has gone unhealthy.
#
# Docker's restart policy only fires when a process *exits*. A JVM that is wedged - pool exhausted,
# deadlocked, garbage collecting forever - stays "up" and answers nothing, and `unless-stopped` will
# happily leave it there. The image already declares a HEALTHCHECK; nothing was acting on it.
#
# Deliberately a cron script rather than another daemon: one fewer thing that can itself be the thing
# that died. Install with:
#
#   */2 * * * * /root/kotatsuserver/deploy/watchdog.sh >> /var/log/kotatsu-watchdog.log 2>&1
#
# Two strikes before restarting, so one slow health probe during a garbage collection or a deploy is
# not enough to bounce a working server.
set -eu

DIR="${WATCHDOG_DIR:-/root/kotatsuserver}"
STATE="${WATCHDOG_STATE:-/var/tmp/kotatsu-watchdog}"
SERVICES="${WATCHDOG_SERVICES:-api postgres}"
STRIKES="${WATCHDOG_STRIKES:-2}"

mkdir -p "$STATE"
cd "$DIR"

stamp() { date -u +%Y-%m-%dT%H:%M:%SZ; }

for service in $SERVICES; do
	container=$(docker compose ps -q "$service" 2>/dev/null || true)
	counter="$STATE/$service.strikes"

	if [ -z "$container" ]; then
		echo "$(stamp) $service has no container; starting it"
		docker compose up -d "$service" || true
		: > "$counter"
		continue
	fi

	# A container with no healthcheck reports nothing; treat that as healthy rather than restarting
	# something we cannot actually assess.
	health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container" 2>/dev/null || echo unknown)
	running=$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null || echo false)

	if [ "$running" = "true" ] && { [ "$health" = "healthy" ] || [ "$health" = "none" ] || [ "$health" = "starting" ]; }; then
		[ -s "$counter" ] && echo "$(stamp) $service recovered ($health)"
		: > "$counter"
		continue
	fi

	strikes=$(cat "$counter" 2>/dev/null || echo 0)
	strikes=$((strikes + 1))
	echo "$strikes" > "$counter"
	echo "$(stamp) $service is $health (running=$running), strike $strikes/$STRIKES"

	if [ "$strikes" -ge "$STRIKES" ]; then
		echo "$(stamp) restarting $service"
		docker compose restart "$service" || docker compose up -d "$service" || true
		: > "$counter"
	fi
done
