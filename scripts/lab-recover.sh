#!/usr/bin/env bash
# Recovers the lab stack after `docker kill` of lab-net (m6a decision 3; ADR 0006 "Known gaps").
#   scripts/lab-recover.sh
# Every other container joins lab-net's network namespace (compose.yaml), so when lab-net is killed and restarted they stay
# on the dead namespace and keep reporting healthy: no healthcheck inside them can tell. This starts lab-net, then restarts
# the others in dependency order, waiting for each group to be healthy before the next: the merchants, then the fault proxies,
# then agent-service, whose startup check suspends a merchant it cannot reach. Safe to run on a healthy stack (it restarts
# everything but keeps the data volume). Run from anywhere; it needs Docker and the stack's .env, and never reads a secret.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== lab-net"
docker compose up -d --wait lab-net
for group in "merchant-a merchant-b merchant-c" "fault-proxy-a fault-proxy-b fault-proxy-c" "agent-service"; do
  echo "== restart $group"
  # shellcheck disable=SC2086  # a word list on purpose
  docker compose restart $group
  # shellcheck disable=SC2086
  docker compose up -d --wait $group
done
echo "== the stack"
docker compose ps --format 'table {{.Service}}\t{{.State}}\t{{.Health}}'
curl -fsS -o /dev/null http://127.0.0.1:8080/readyz && echo "RECOVERED: agent-service answers on 127.0.0.1:8080"
