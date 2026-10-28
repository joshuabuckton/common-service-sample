#!/usr/bin/env bash
# The audit history and the MCP endpoint of preferences-service.
#
# Start preferences-service first, and write something with smoke-same-project.sh or
# smoke-other-project.sh. Pass the customer id that you used.
# Usage: scripts/smoke-audit-and-mcp.sh <customer-id>
set -euo pipefail
source "$(dirname "$0")/lib.sh"

CUSTOMER=${1:?Pass the customer id that you wrote with another smoke script}
JSON='Content-Type: application/json'
MCP_ACCEPT='Accept: application/json, text/event-stream'

show "history of the customer, as an auditor" "$PREFERENCES/preferences/$CUSTOMER/history" \
  -H "Authorization: Bearer $AUDITOR_TOKEN"
show "every change that advisor-service made, as an auditor" "$PREFERENCES/preferences/changes-by/advisor-service" \
  -H "Authorization: Bearer $AUDITOR_TOKEN"
expect_status "the history needs the auditor role" 403 "$PREFERENCES/preferences/$CUSTOMER/history" \
  -H "Authorization: Bearer $ASSISTANT_TOKEN"

echo "== MCP: the tools (names only)"
curl -s "$PREFERENCES/mcp" -H "Authorization: Bearer $ASSISTANT_TOKEN" -H "$JSON" -H "$MCP_ACCEPT" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | grep -o '"name":"[a-z_]*"'

show "MCP: can_contact sms" "$PREFERENCES/mcp" -H "Authorization: Bearer $ASSISTANT_TOKEN" -H "$JSON" -H "$MCP_ACCEPT" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"can_contact\",\"arguments\":{\"customerId\":\"$CUSTOMER\",\"channel\":\"sms\"}}}"
expect_status "MCP without a token is rejected" 400 "$PREFERENCES/mcp" -H "$JSON" -H "$MCP_ACCEPT" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}'
