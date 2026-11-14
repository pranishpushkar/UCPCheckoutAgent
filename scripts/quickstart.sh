#!/usr/bin/env bash
# The quick start of the README (steps 2 to 4 and 9), as one script, so the README and the CI job cannot drift: a
# test (ReadmeTest) requires the README's code block to equal the commands between the markers below.
# Step 1 is the clone; run this from the checkout. Step 5 to 7 is the console, or scripts/quickstart-smoke.sh.
# Step 8 (the live model) is in the README only: it reads your key from the shell and is not run here.
#
#   scripts/quickstart.sh          steps 2 to 4: start the stack and wait until all four services are healthy
#   scripts/quickstart.sh --down   the same, then step 9
set -euo pipefail
cd "$(dirname "$0")/.."

# >>> quickstart
cp .env.example .env
./mvnw -B -ntp -DskipTests package
docker compose up -d --build --wait
# <<<

[ "${1:-}" = "--down" ] || exit 0

# >>> teardown
docker compose down -v
# <<<
