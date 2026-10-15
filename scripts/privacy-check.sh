#!/usr/bin/env bash
# Refuse to commit keys, tokens, private hosts or home paths.
set -euo pipefail
cd "$(dirname "$0")/.."
patterns=(
  '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'
  '(^|[^0-9])(10|192\.168|100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7]))\.[0-9]{1,3}\.[0-9]{1,3}([^0-9]|$)'
  '/Users/[A-Za-z0-9._-]+'
  '/home/[A-Za-z0-9._-]+'
  '\.ts\.net'
)
failed=0
for p in "${patterns[@]}"; do
  m=$(git ls-files | xargs grep -lE "$p" 2>/dev/null || true)
  if [[ -n "$m" ]]; then echo "privacy check: '$p' matched in:"; echo "$m"; failed=1; fi
done
for f in '*.p8' 'watch/.team' '*.jks' '*.apk'; do
  if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then echo "forbidden file tracked: $f"; failed=1; fi
done
[[ $failed -eq 0 ]] && echo "Privacy check passed."
exit $failed
