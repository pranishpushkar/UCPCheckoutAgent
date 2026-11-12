#!/usr/bin/env bash
# Run the scripted evaluation (13.1) and write report.json and report.md.
# Usage: scripts/eval.sh [--mode scripted] [--runs N] [--time-scale K] [--lanes L] [--seed S]
#                        [--scenario ID [--run R]] [--out DIR] [--evidence failures|all] [--require-full]
# Defaults: 20 runs per scenario at time scale 4 on 4 lanes (a REDUCED report) into eval-harness/target/eval.
# The acceptance report: scripts/eval.sh --mode scripted --runs 200 --time-scale 1 --lanes 16 --require-full
# Exit codes: 0 clean, 1 violation or aborted run, 2 unexpected outcome, 3 harness error or --require-full not met.
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -B -q -ntp -pl eval-harness -am package -DskipTests \
  dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.includeScope=runtime
exec java -cp "eval-harness/target/classes:$(cat eval-harness/target/classpath.txt)" dev.ucplab.eval.EvalMain "$@"
