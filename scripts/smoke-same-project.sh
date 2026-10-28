#!/usr/bin/env bash
# Pattern 1, a service in the same project: advisor-service calls preferences-service by name.
#
# Start preferences-service and advisor-service first (see the README).
# Usage: scripts/smoke-same-project.sh [customer-id]
#
# To see the outage behaviour, stop preferences-service and run the last section by hand:
#   curl localhost:9001/customers/<id>/contact-preferences                answers 200 from the local view
#   curl -X PUT localhost:9001/customers/<id>/contact-preferences ...      returns 503
#   curl localhost:9001/customers/<id>/can-contact/email                   returns 503
set -euo pipefail
source "$(dirname "$0")/lib.sh"

CUSTOMER=${1:-c-$RANDOM}
JSON='Content-Type: application/json'

show "write over gRPC by service name" -X PUT "$ADVISOR/customers/$CUSTOMER/contact-preferences" \
  -H "$JSON" -d '{"email":true,"sms":false,"phone":true,"reason":"signup"}'

# The change reaches the local view through the stream. That takes about a second, and longer
# right after preferences-service restarted, because advisor-service then reconnects to the stream.
echo "(waiting for the change to reach the local view through the stream)"
for _ in $(seq 1 20); do
  if curl -s "$ADVISOR/customers/$CUSTOMER/contact-preferences" | grep -q '"known":true'; then break; fi
  sleep 1
done

show "read from the local view" "$ADVISOR/customers/$CUSTOMER/contact-preferences"
show "live question over HTTP by service name: email" "$ADVISOR/customers/$CUSTOMER/can-contact/email"
show "live question over HTTP by service name: sms" "$ADVISOR/customers/$CUSTOMER/can-contact/sms"

expect_status "an unknown channel is a 400" 400 "$ADVISOR/customers/$CUSTOMER/can-contact/fax"
expect_status "a customer id with a colon is a 400" 400 -X PUT "$ADVISOR/customers/a:b/contact-preferences" \
  -H "$JSON" -d '{"email":true,"sms":true,"phone":true,"reason":""}'
