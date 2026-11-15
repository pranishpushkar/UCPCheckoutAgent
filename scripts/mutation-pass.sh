#!/usr/bin/env bash
# One mutation pass (ADR 0009), run by hand or as one job of .github/workflows/mutation.yml.
#   scripts/mutation-pass.sh <pass> [out-dir]      pass: core-policy core-approval core-merchant core-json core-intent
#                                                  core-recovery core-orchestrator ucp persistence
# Writes <out-dir>/<pass>/mutations.xml, index.html and run.properties (default out-dir: target/mutation-runs). PIT runs only
# under -Pmutation, one glob per run (a comma list matched nothing). No secret is read or needed.
set -euo pipefail
pass="${1:?usage: mutation-pass.sh <pass> [out-dir]}"
out="${2:-target/mutation-runs}"
cd "$(dirname "$0")/.."
tries=500
seed=1
excluded=()
extra=()
case "$pass" in
  core-policy)       module=agent-core;        classes='dev.ucplab.core.policy.*';       tests='dev.ucplab.*' ;;
  core-approval)     module=agent-core;        classes='dev.ucplab.core.approval.*';     tests='dev.ucplab.*' ;;
  core-merchant)     module=agent-core;        classes='dev.ucplab.core.merchant.*';     tests='dev.ucplab.*'; excluded=(-DexcludedClasses='dev.ucplab.core.merchant.fake.*') ;;
  core-json)         module=agent-core;        classes='dev.ucplab.core.json.*';         tests='dev.ucplab.*' ;;
  core-intent)       module=agent-core;        classes='dev.ucplab.core.intent.*';       tests='dev.ucplab.*' ;;
  core-recovery)     module=agent-core;        classes='dev.ucplab.core.recovery.*';     tests='dev.ucplab.*' ;;
  core-orchestrator) module=agent-core;        classes='dev.ucplab.core.orchestrator.*'; tests='dev.ucplab.*'; tries=10000 ;;
  ucp)               module=agent-ucp;         classes='dev.ucplab.ucp.*';               tests='dev.ucplab.ucp.*' ;;
  persistence)       module=agent-persistence; classes='dev.ucplab.persistence.*';       tests='dev.ucplab.persistence.*'
                     extra=(-Dmutation.targetTests2='dev.ucplab.core.orchestrator.SqlCrashMatrixTest') ;;
  *) echo "unknown pass: $pass" >&2; exit 2 ;;
esac
mvn=(./mvnw -B -ntp)
if [ "$module" != agent-core ]; then
  # ucp and persistence run against core's test-jar: install the current one first, with everything else the module
  # resolves from the local repository (the root POM, fault-proxy). -am, not -pl agent-core alone: on an empty repository,
  # as on a fresh runner, the root POM is missing and the pass fails before PIT starts.
  "${mvn[@]}" -q -pl "$module" -am install -DskipTests
fi
start=$(date +%s)
"${mvn[@]}" -Pmutation -pl "$module" test-compile org.pitest:pitest-maven:mutationCoverage \
  -Dmutation.targetClasses="$classes" -Dmutation.targetTests="$tests" -Dmutation.mutators=STRONGER \
  -Dmutation.property.tries="$tries" -Dmutation.property.seed="$seed" ${excluded[@]+"${excluded[@]}"} ${extra[@]+"${extra[@]}"}
wall=$(( $(date +%s) - start ))
dest="$out/$pass"
rm -rf "$dest"
mkdir -p "$dest"
cp -R "$module"/target/pit-reports/. "$dest"/
cat > "$dest/run.properties" <<PROPS
module=$module
classes=$classes
mutators=STRONGER
tries=$tries
seed=$seed
wall.seconds=$wall
PROPS
echo "pass $pass: $wall s, $dest"
