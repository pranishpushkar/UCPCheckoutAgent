#!/usr/bin/env bash
# m6-plan C3, risk 2: the shared network namespace works for the loopback-bound merchant and proxy.
# Run by hand from the repo root with Docker running: scripts/compose-spike.sh
# It shows that (1) discovery through the proxy works from inside the namespace, (2) the merchant's /_lab/orders and
# the proxy admin API cannot be reached from the host or from the Compose network, and (3) `docker compose down -v`
# leaves nothing running. It exits non-zero on the first check that fails.
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "FAIL: $*"; exit 1; }
# Prints "reachable" or "unreachable" for a URL fetched from the host; never fails the script itself.
from_host() { if curl -s -o /dev/null --max-time 3 "$1"; then echo reachable; else echo unreachable; fi; }

echo "== build the jars"
./mvnw -B -q -ntp -DskipTests package -pl merchant,fault-proxy -am

echo "== docker compose up"
docker compose up -d --build --wait
docker compose ps --format 'table {{.Service}}\t{{.State}}\t{{.Health}}\t{{.Ports}}'

echo "== (1) discovery through the proxy, from inside the namespace"
discovery=$(docker compose exec -T fault-proxy curl -fsS http://127.0.0.1:9101/.well-known/ucp)
echo "$discovery" | head -c 400; echo
echo "$discovery" | grep -q '127.0.0.1:9101' || fail "the proxy did not rewrite discovery to its own authority"
log=$(docker compose exec -T fault-proxy curl -fsS 'http://127.0.0.1:9101/_proxy/log?after=0')
echo "proxy log: $log" | head -c 400; echo
echo "$log" | grep -q '/.well-known/ucp' || fail "the proxy log does not show the discovery request"
echo "ok: discovery went through the proxy to the merchant"

echo "== (2) nothing of the lab is reachable from outside the namespace"
for url in http://127.0.0.1:9001/_lab/orders http://127.0.0.1:9101/_proxy/log http://127.0.0.1:9101/.well-known/ucp; do
  result=$(from_host "$url")
  echo "host -> $url: $result"
  [ "$result" = unreachable ] || fail "$url is reachable from the host"
done
# The namespace's own address on the Compose network: the merchant and the proxy listen on loopback only, so even a
# container on that network (here, the namespace itself through its eth0 address) is refused.
ip=$(docker compose exec -T lab-net hostname -I | awk '{print $1}')
for port in 9001 9101; do
  if docker compose exec -T lab-net curl -s -o /dev/null --max-time 3 "http://$ip:$port/_lab/orders"; then
    fail "port $port answers on the Compose network address $ip"
  fi
  echo "compose network $ip:$port: unreachable"
done
echo "inside the namespace, /_lab/orders is served (ground truth for the agent):"
docker compose exec -T merchant-a curl -fsS http://127.0.0.1:9001/_lab/orders; echo
echo "and the proxy refuses /_lab (section 12):"
docker compose exec -T fault-proxy curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9101/_lab/orders

echo "== (3) docker compose down -v"
docker compose down -v
left=$(docker ps -a --filter label=com.docker.compose.project=ucplab --format '{{.Names}}')
[ -z "$left" ] || fail "containers left: $left"
volumes=$(docker volume ls --filter label=com.docker.compose.project=ucplab --format '{{.Name}}')
[ -z "$volumes" ] || fail "volumes left: $volumes"
echo "ok: no container or volume of project ucplab is left"
echo "SPIKE PASSED"
