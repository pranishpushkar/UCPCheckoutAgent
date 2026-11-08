#!/usr/bin/env bash
# Build and start the merchant. Usage: scripts/run-merchant.sh [port]   (default 8080)
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -B -q -ntp -pl merchant -am package -DskipTests
exec java -cp merchant/target/classes dev.ucplab.merchant.MerchantServer "${1:-8080}"
