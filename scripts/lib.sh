#!/usr/bin/env bash
# Helpers for the smoke scripts. Source this file, do not run it.
#
# Local runs do not check the signature of a token, so the scripts build unsigned tokens. A deployed
# service needs a token signed with the key that `akka service jwks add` registered.

ADVISOR=${ADVISOR:-http://localhost:9001}
PARTNER=${PARTNER:-http://localhost:9002}
PREFERENCES=${PREFERENCES:-http://localhost:9000}

b64url() { printf '%s' "$1" | base64 | tr '+/' '-_' | tr -d '='; }

# token '<claims json>' prints an unsigned token with those claims.
token() { echo "$(b64url '{"alg":"none"}').$(b64url "$1")."; }

# The issuer that preferences-service accepts.
ISSUER=preferences-callers
ASSISTANT_TOKEN=$(token "{\"iss\":\"$ISSUER\",\"sub\":\"assistant-1\"}")
AUDITOR_TOKEN=$(token "{\"iss\":\"$ISSUER\",\"sub\":\"auditor-1\",\"role\":\"auditor\"}")

# show 'title' curl-args... prints the title, the body and the HTTP status.
show() {
  local title=$1
  shift
  echo "== $title"
  curl -s -w '\n[HTTP %{http_code}]\n' "$@"
}

# expect_status 'title' status curl-args... fails unless the call returns that status.
expect_status() {
  local title=$1 want=$2
  shift 2
  local got
  got=$(curl -s -o /dev/null -w '%{http_code}' "$@")
  if [ "$got" != "$want" ]; then
    echo "FAIL: $title: expected $want, got $got"
    exit 1
  fi
  echo "ok: $title ($got)"
}
