#!/usr/bin/env bash
# Lints the preferences contract and checks that it does not break the version in another git ref.
#
# Usage: scripts/check-proto.sh [ref]
#   ref   The git ref to compare against. The default is main.
#
# The script uses a local buf when there is one. Otherwise it runs the buf Docker image.
set -euo pipefail
REF=${1:-main}
BUF_IMAGE=${BUF_IMAGE:-bufbuild/buf:1.50.0}
ROOT=$(cd "$(dirname "$0")/.." && pwd)

if command -v buf >/dev/null 2>&1; then
  buf() { (cd "$ROOT/preferences-proto" && command buf "$@"); }
  AGAINST="$ROOT/.git#ref=$REF,subdir=preferences-proto"
else
  # The mount is read-only. The container needs the git directory for the comparison.
  buf() { docker run --rm -v "$ROOT:/workspace:ro" -w /workspace/preferences-proto "$BUF_IMAGE" "$@"; }
  AGAINST="/workspace/.git#ref=$REF,subdir=preferences-proto"
fi

echo "== buf lint"
buf lint
echo "ok"

echo "== buf breaking against $REF"
if git -C "$ROOT" cat-file -e "$REF:preferences-proto/buf.yaml" 2>/dev/null; then
  buf breaking --against "$AGAINST"
  echo "ok"
else
  echo "skipped: $REF has no preferences-proto/buf.yaml yet, so there is nothing to compare with"
fi
