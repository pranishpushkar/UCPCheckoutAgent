#!/usr/bin/env bash
# H5 Stage 3, I8: no secret of the live run leaked. Run by the OWNER after scripts/shopify-live.sh, never by CI.
#   scripts/h5-leak-scan.sh [--evidence DIR]... [--base develop] [--with-images]   scan the run's evidence and the branch
#   scripts/h5-leak-scan.sh --dry-run                                               list what would be scanned
#   scripts/h5-leak-scan.sh --self-test                                             plant canaries and confirm each is found
#
# The values come from the environment, as for the live run: SHOPIFY_CLIENT_ID and SHOPIFY_CLIENT_SECRET (read from the
# Keychain, under the spike's names, when unset), BUYER_IP, SHOPIFY_STORE_DOMAIN and, if you give one, TOKEN_PREFIX (the
# access token's first characters). A value reaches grep only through a pattern file on a pipe, never as an argument
# (ps shows those). It prints pattern names and counts only, never a matched value or the file that holds it, and exits
# non-zero on any hit. Over the branch only the values fail it: its tests and docs hold fakes of every shape. --with-images also runs scripts/image-secret-scan.sh (Docker), unchanged.
set -euo pipefail
cd "$(dirname "$0")/.."

DRY=0; SELF=0; IMAGES=0; BASE=develop; DIRS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --self-test) SELF=1 ;;
    --with-images) IMAGES=1 ;;
    --base) BASE=$2; shift ;;
    --evidence) DIRS+=("$2"); shift ;;
    *) echo "usage: scripts/h5-leak-scan.sh [--evidence DIR]... [--base BRANCH] [--with-images] | --dry-run | --self-test" >&2; exit 2 ;;
  esac
  shift
done
[ ${#DIRS[@]} -gt 0 ] || DIRS=(notes/h5/stage3/evidence notes/h5/stage3/recordings)

# The fixed-string patterns, one "name<TAB>value" per line, for the values that are set.
fixed_patterns() {
  [ -n "${SHOPIFY_CLIENT_SECRET:-}" ] && printf 'the client secret\t%s\n' "$SHOPIFY_CLIENT_SECRET"
  [ -n "${SHOPIFY_CLIENT_ID:-}" ] && printf 'the client id\t%s\n' "$SHOPIFY_CLIENT_ID"
  [ -n "${TOKEN_PREFIX:-}" ] && printf 'the access token prefix\t%s\n' "$TOKEN_PREFIX"
  [ -n "${BUYER_IP:-}" ] && printf 'the buyer IP\t%s\n' "$BUYER_IP"
  [ -n "${SHOPIFY_STORE_DOMAIN:-}" ] && printf 'the store domain with /cart/c/\t%s/cart/c/\n' "$SHOPIFY_STORE_DOMAIN"
  return 0
}

# The shape patterns (extended regular expressions, case-insensitive); no value of ours is in them.
REGEX_NAMES=("a ?key= that is not ?key=<redacted>" "Shopify-Buyer-IP with a value" "Authorization: Bearer with a value")
REGEXES=('\?key=([^<]|$)' 'shopify-buyer-ip"?[[:space:]]*[:=][[:space:]]*"?[0-9a-f:.]{3,}' 'bearer[[:space:]]+[a-z0-9_.-]{8,}')

hits=0
report() {   # $1 name  $2 count
  if [ "$2" -gt 0 ]; then echo "HIT  $1: $2"; hits=$((hits + $2)); else echo "ok   $1: 0"; fi
}

# A shape over the branch's own code: its tests and docs hold fakes of every shape (a fake ?key=, a fake bearer token),
# so the count is shown and never fails the scan. The real values are the fixed patterns, which do.
inform() {   # $1 name  $2 count
  echo "info $1: $2 (fakes in the branch's tests and docs; not a failure)"
}

# Counts the files under the directories that hold one fixed value; the value goes in on a pipe.
count_fixed_dirs() {   # $1 value, then the directories
  local value=$1; shift
  { grep -rlaF -f <(printf '%s\n' "$value") -- "$@" 2>/dev/null || true; } | wc -l | tr -d ' '
}

count_regex_dirs() {   # $1 regex, then the directories
  local regex=$1; shift
  { grep -rlaiE -f <(printf '%s\n' "$regex") -- "$@" 2>/dev/null || true; } | wc -l | tr -d ' '
}

# Counts the commits of BASE..HEAD whose tree holds the value or shape, and the lines of git log -p that do.
count_branch() {   # $1 F or E, $2 the pattern
  local mode=$1 pattern=$2 trees=0 commit
  for commit in $(git rev-list "$BASE"..HEAD); do
    if git grep -qaI"$( [ "$mode" = E ] && echo iE || echo F)" -f <(printf '%s\n' "$pattern") "$commit" -- . 2>/dev/null; then
      trees=$((trees + 1))
    fi
  done
  local lines
  lines=$({ git log -p "$BASE"..HEAD | grep -a"$( [ "$mode" = E ] && echo iE || echo F)" -f <(printf '%s\n' "$pattern") || true; } | wc -l | tr -d ' ')
  echo $((trees + lines))
}

scan() {   # $1 1 to scan the branch too, then the directories
  local branch=$1; shift
  local name value i
  while IFS=$'\t' read -r name value; do
    report "$name (evidence)" "$(count_fixed_dirs "$value" "$@")"
    [ "$branch" = 1 ] && report "$name (branch $BASE..HEAD)" "$(count_branch F "$value")"
  done < <(fixed_patterns)
  for i in "${!REGEXES[@]}"; do
    report "${REGEX_NAMES[$i]} (evidence)" "$(count_regex_dirs "${REGEXES[$i]}" "$@")"
    [ "$branch" = 1 ] && inform "${REGEX_NAMES[$i]} (branch $BASE..HEAD)" "$(count_branch E "${REGEXES[$i]}")"
  done
  return 0
}

if [ "$DRY" = 1 ]; then
  echo "DRY-RUN evidence directories: ${DIRS[*]}"
  echo "DRY-RUN branch: every commit of $BASE..HEAD (git grep) and git log -p $BASE..HEAD"
  echo "DRY-RUN values: the client secret, the client id, the access token prefix (if TOKEN_PREFIX is set), the buyer IP," \
       "the store domain with /cart/c/; the client id and secret from the Keychain when unset (not read in a dry run)"
  for name in "${REGEX_NAMES[@]}"; do echo "DRY-RUN shape: $name"; done
  [ "$IMAGES" = 1 ] && echo "DRY-RUN images: scripts/image-secret-scan.sh"
  exit 0
fi

if [ "$SELF" = 1 ]; then
  WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
  SHOPIFY_CLIENT_SECRET="canary-secret-$(openssl rand -hex 8)"; SHOPIFY_CLIENT_ID="canary-id-$(openssl rand -hex 8)"
  TOKEN_PREFIX="canarytok_$(openssl rand -hex 4)"; BUYER_IP="198.51.100.$((RANDOM % 200 + 20))"
  SHOPIFY_STORE_DOMAIN="canary-$(openssl rand -hex 4).myshopify.com"
  printf '%s\n' "secret $SHOPIFY_CLIENT_SECRET" > "$WORK/a.log"
  printf '%s\n' "id $SHOPIFY_CLIENT_ID" "token ${TOKEN_PREFIX}rest" > "$WORK/b.log"
  printf '%s\n' "{\"Shopify-Buyer-IP\": \"$BUYER_IP\"}" > "$WORK/c.json"
  printf '%s\n' "https://$SHOPIFY_STORE_DOMAIN/cart/c/abc" "gid://shopify/Checkout/x?key=realkey123" \
    "Authorization: Bearer canarytokenvalue123" > "$WORK/d.txt"
  # The clean shapes must not count.
  printf '%s\n' "?key=<redacted>" "Bearer <redacted>" "Shopify-Buyer-IP: <redacted>" > "$WORK/clean.txt"
  out=$(scan 0 "$WORK")
  echo "$out"
  missed=$(echo "$out" | grep -c '^ok' || true)
  [ "$missed" -eq 0 ] || { echo "SELF-TEST FAILED: $missed canary pattern(s) not found: the scan is blind" >&2; exit 1; }
  [ "$(count_regex_dirs '\?key=([^<]|$)' "$WORK/clean.txt")" = 0 ] || { echo "SELF-TEST FAILED: ?key=<redacted> counted" >&2; exit 1; }
  echo "SELF-TEST PASSED: every planted canary was found by its pattern name"
  exit 0
fi

if [ -z "${SHOPIFY_CLIENT_SECRET:-}" ]; then
  SHOPIFY_CLIENT_SECRET="$(security find-generic-password -a "$USER" -s ucplab-shopify-catalog-secret -w)"
fi
if [ -z "${SHOPIFY_CLIENT_ID:-}" ]; then
  SHOPIFY_CLIENT_ID="$(security find-generic-password -a "$USER" -s ucplab-shopify-catalog-id -w)"
fi
for v in BUYER_IP SHOPIFY_STORE_DOMAIN; do
  [ -n "${!v:-}" ] || { echo "missing environment: $v" >&2; exit 2; }
done
for d in "${DIRS[@]}"; do [ -d "$d" ] || { echo "no such evidence directory: $d" >&2; exit 2; }; done
scan 1 "${DIRS[@]}"
if [ "$IMAGES" = 1 ]; then scripts/image-secret-scan.sh || hits=$((hits + 1)); fi
[ "$hits" -eq 0 ] || { echo "LEAK SCAN FAILED: $hits hit(s)" >&2; exit 1; }
echo "LEAK SCAN PASSED"
