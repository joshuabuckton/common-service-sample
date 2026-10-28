#!/usr/bin/env bash
# Pattern 2, a service in another project: partner-service calls the public address of
# preferences-service with a bearer token. It has no stream and no local view, so every read is a call.
#
# Start preferences-service and partner-service first (see the README).
# Usage: scripts/smoke-other-project.sh [customer-id]
set -euo pipefail
source "$(dirname "$0")/lib.sh"

CUSTOMER=${1:-p-$RANDOM}
JSON='Content-Type: application/json'

show "write through the public address" -X PUT "$PARTNER/customers/$CUSTOMER/contact-preferences" \
  -H "$JSON" -d '{"email":false,"sms":true,"phone":true,"reason":"partner update"}'
show "read through the public address" "$PARTNER/customers/$CUSTOMER/contact-preferences"

# The token rule applies to every caller. Calling preferences-service directly shows it.
expect_status "no token is rejected" 400 "$PREFERENCES/preferences/$CUSTOMER"
expect_status "a token from another issuer is rejected" 403 "$PREFERENCES/preferences/$CUSTOMER" \
  -H "Authorization: Bearer $(token '{"iss":"someone-else","sub":"x"}')"
expect_status "a valid token is accepted" 200 "$PREFERENCES/preferences/$CUSTOMER" \
  -H "Authorization: Bearer $ASSISTANT_TOKEN"
