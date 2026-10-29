# Common service sample: customer contact preferences

This sample shows how to build a common service that many other services use, and what the owner of
that service decides. The domain is small on purpose. A customer accepts or refuses contact by email,
SMS and phone. Every service that contacts a customer asks first, so the answer lives in one place.

The sample uses the Akka SDK only. Every link points to the SDK documentation.

## Three ways to reach a common service

| Access pattern | Caller | What the sample shows |
|---|---|---|
| Same project | `advisor-service` | A gRPC call and an HTTP call by service name, `@Acl` by calling service, an agent that uses the MCP tools and an optional local view fed by a stream. |
| Other project | `partner-service` | The public address, a bearer token and the proto jar as the only shared artifact. |
| Interactive and LLM clients | Any MCP client | An MCP endpoint with read-only tools, behind the same public address and token rules. |

Read [docs/flows.md](docs/flows.md) for the diagrams of each flow.

## Questions the owner of a common service answers

| Question | Answer in the sample | Where to look |
|---|---|---|
| How do callers reach it? | By name in the same project, by public address from another project, through MCP for tools. | `PreferencesGrpcEndpointImpl`, `PreferencesEndpoint`, `PreferencesMcpEndpoint` |
| Who may call it? | `@Acl` names the services. A bearer token from `preferences-callers` is required of every caller. | The `@Acl` and `@JWT` annotations on the three endpoints |
| What is the contract? | The proto jar for gRPC. The tool names and parameters for MCP. | `preferences-proto`, `PreferencesMcpEndpoint` |
| How do callers learn about changes? | A service stream inside the project, for a caller that keeps a local view. | `PreferenceChangesPublisher` |
| What happens when it is down? | Writes and live questions return 503. The one read that `advisor-service` keeps in a local view still answers. | `ContactPreferencesByCustomerView`, `AdvisorEndpoint` |
| Who changed what, and why? | An event-sourced entity keeps every write. The token subject is the `changedBy` value. | `CustomerPreferencesEntity`, `PreferenceHistoryView` |
| What if a write arrives twice or late? | Each write sets all three channels, so a second delivery changes nothing. A reader ignores an event older than its row. | `PreferenceChanged`, `ContactPreferencesByCustomerView` |

## Modules

| Module | Type | Role |
|---|---|---|
| `preferences-proto` | Jar of `.proto` files | The gRPC contract of the common service. |
| `preferences-service` | Akka service | The common service. gRPC, HTTP and MCP endpoints, the audit history and the `preference-changes` stream. |
| `advisor-service` | Akka service | A caller in the same project. |
| `partner-service` | Akka service | A caller in another project. It uses the proto jar only. |

The gRPC contract lives in one place, the `preferences-proto` jar, so that it is easy to share. The owner and every
caller generate their stubs from the same versioned artifact, so they cannot drift apart. A caller depends on the
contract and never on the code of the common service, which is what lets a service in another project or team use
it. The jar can also be reviewed and checked for compatibility without building a service. See
[Check the contract](#check-the-contract).

To use it, each of the three services unpacks the jar into `target/proto` during the build, and the SDK generates its
gRPC code from there. Do not copy the `.proto` file into a service, because that brings back the drift that the jar
prevents.

One detail of the SDK: it generates code only when a `src/main/proto` directory exists. So each service keeps an
empty one, marked with a `.gitkeep` file because git does not track empty directories.

## Build and test

You need Java 21 or later and the Akka repository token in your Maven settings. Run the build from the
repository root.

```bash
mvn verify
```

Use `verify`, not `compile`. The modules that generate code unpack the proto jar, and Maven resolves that
jar only after it is packaged. The build needs no Docker and no cloud account. The calls between services
are mocked in the tests with the mocking support of the SDK TestKit.

## Run the services locally

Install the proto jar once, because each service unpacks it from the local Maven repository.

```bash
mvn -pl preferences-proto install
```

Start each service in its own terminal.

```bash
mvn -pl preferences-service compile exec:java
```

```bash
mvn -pl advisor-service compile exec:java
```

```bash
mvn -pl partner-service compile exec:java
```

The ports are 9000 for `preferences-service`, 9001 for `advisor-service` and 9002 for `partner-service`.
The scripts in `scripts/` call the services with `curl`. They build unsigned tokens, which a local run accepts.

| Script | Steps |
|---|---|
| `scripts/smoke-same-project.sh [customer]` | Write over gRPC, read from the local view, ask the live question over HTTP, and two rejected requests. With `WITH_AGENT=1` it also asks the assistant, which needs a model (see [Agents as callers](#agents-as-callers)). |
| `scripts/smoke-other-project.sh [customer]` | Write and read through the public address, and the three token cases. |
| `scripts/smoke-audit-and-mcp.sh <customer>` | The audit history as an auditor, the role check, the MCP tools and a rejected MCP call. |

To see the outage behaviour, stop `preferences-service` after the first script. The read of
`advisor-service` still answers with 200. A write and the live question return 503.

## Same project

[Calls by service name](https://doc.akka.io/sdk/component-and-service-calls.html) need no address.
`advisor-service` asks for a client by the name `preferences-service`.

- A write goes over gRPC with the generated client from the proto jar.
- The question "may I contact this customer on this channel" goes over HTTP. It shows the second client
  style. Both calls are in `PreferencesServiceCaller`.
- Start with the direct call. The one read of `advisor-service` that must keep working when the service is down
  comes from `ContactPreferencesByCustomerView`, which a [service stream](https://doc.akka.io/sdk/consuming-producing.html)
  feeds. It never calls `preferences-service`, so it can lag behind a write. Add a view only for a caller with
  that need.
- `@Acl` on the endpoints and on the stream names `advisor-service`. See [access control](https://doc.akka.io/sdk/access-control.html).

The stream carries the customer id, the three flags and the time of the change. It does not carry who
changed the preferences or why, because every reader in the project sees it. `advisor-service` declares
its own `PreferenceChanged` record with the same type name and fields. The stream sends JSON, and the
SDK also supports protobuf messages for consumers, which would move the stream contract into the proto
jar. The sample does not use that.

### Agents as callers

`ContactAdvisorAgent` in `advisor-service` is an [agent](https://doc.akka.io/sdk/agents.html) that uses the MCP
endpoint of `preferences-service` as its tools. `POST /customers/assistant` with `{"question": "..."}` returns
`{"answer": "..."}`. The agent is a caller like the others in this section.

- It reaches the endpoint by service name, with `RemoteMcpTools.fromService`, so it needs no address. See
  [remote MCP tools](https://doc.akka.io/sdk/agents/extending.html#mcp_tools).
- `withAllowedToolNames` limits it to `get_contact_preferences` and `can_contact`. The MCP endpoint has no write
  tool anyway, so the agent can read preferences and cannot change them.
- It sends the same bearer token as the other calls of the service, `advisor.preferences-token`, as an
  `Authorization` header. The `@Acl` of `PreferencesMcpEndpoint` allows `advisor-service`, and the token rule
  applies on top of it, as described under [Token](#token).
- Every request is its own session, so the agent remembers no earlier question.
- The model is OpenAI by default, with the key in `OPENAI_API_KEY`. The key is needed only to run the agent
  locally. The build and the tests need none. Set `AGENT_MODEL_PROVIDER`, and the key of that provider, to use
  another provider. A local model works too if it supports tool calls. The sample was tried by hand with one.
- The tests replace the model and switch the MCP tools off with `advisor.assistant.mcp-tools=false`, because the
  TestKit cannot reach another service to list the tools. They cover the route and the agent with a model that
  answers directly, and not the calls to `preferences-service`.

The agent suits a free-text question from a person. The live call suits a program that needs a yes or no. The view
is for a program that must still answer when the service is down.

## Token

`preferences-service` requires a bearer token from every caller, including `advisor-service`. The ACL
decides which services and callers may reach an endpoint. The token rule applies on top of it, and the
token subject becomes the `changedBy` value in the audit history. A caller that the ACL allows but that
sends no token is rejected. See [JWT authentication](https://doc.akka.io/sdk/auth-with-jwts.html).

- The issuer is `preferences-callers`.
- `advisor-service` reads its token from `advisor.preferences-token`, and `partner-service` reads its
  token from `partner.preferences-token`. Each has an environment variable that overrides the setting:
  `ADVISOR_PREFERENCES_TOKEN` and `PARTNER_PREFERENCES_TOKEN`.
- The history routes also need the claim `role=auditor`.
- A local run and the integration tests accept an unsigned token, because the SDK does not check the signature there.
- A rejected call gives 400 when there is no token, 403 for a token from another issuer or without the role.
  The numbers come from a local run.

For a deployed service, sign the tokens with an RS256 or ES256 key. A JWKS key set accepts only
asymmetric algorithms, and an `HS256` key set makes the service stop at startup. Put the public key in a
secret and register it with `akka service jwks add preferences-service --secret <secret> --issuer
preferences-callers --allowed-algorithms RS256`.

## Other project

`partner-service` runs in a different Akka project. A service stream does not cross projects, and a call
by service name resolves only inside one project. It needs three things:

1. The proto jar. Publish `preferences-proto` to a Maven repository that the other project can reach. The
   local `mvn install` is enough for a demo. Version the jar with the contract, and follow the schema
   evolution rules in the [gRPC endpoint documentation](https://doc.akka.io/sdk/grpc-endpoints.html).
2. A build that unpacks the `.proto` files into `target/proto` before code generation. See
   `partner-service/pom.xml`. Keep an empty `src/main/proto` directory in the module, as described under
   [Modules](#modules).
3. The public address and a token. `partner.preferences-host` holds the address, and
   `PARTNER_PREFERENCES_HOST` overrides it. The `akka.javasdk.grpc.client` setting maps the address to
   `localhost` for local runs.

Every read of `partner-service` is a call, so it fails with 503 when the service is not reachable. For a
local read model across projects, use a message broker topic. The sample does not build one.

## Interactive and LLM clients

`PreferencesMcpEndpoint` is an [MCP endpoint](https://doc.akka.io/sdk/mcp-endpoints.html) at `/mcp` with two
tools, `get_contact_preferences` and `can_contact`. Both are read-only.

- `@Acl` and `@JWT` apply to the whole class, not to single tools. So the endpoint has no write tool, and
  the audit history stays on the HTTP routes that need the auditor role.
- The descriptions of the tools and of their parameters are the contract for the model. A renamed tool
  breaks its callers, like a renamed field in a proto.
- An agent in the same project is one such client. See [Agents as callers](#agents-as-callers).
- The SDK does not support the OAuth flows of the MCP specification. A client sends a ready-made bearer token.
- The sample tests the endpoint with hand-written JSON-RPC, because the TestKit has no MCP client. It has not
  been tried with a real MCP client. A rejected call answers with plain text and an HTTP status, not with a
  JSON-RPC error.

## Audit history

`CustomerPreferencesEntity` is an [event-sourced entity](https://doc.akka.io/sdk/event-sourced-entities.html).
Each write is one `PreferencesUpdated` event with the three channels, `changedBy`, `reason` and the time. The
last write wins. `PreferenceHistoryView` answers two questions: the changes of one customer, and the changes
that one caller made across customers. A write with the same values as before is still recorded.

## Check the contract

Run the contract checks before you publish `preferences-proto`:

```bash
scripts/check-proto.sh
```

The script runs `buf lint` and `buf breaking` on `preferences-proto`. It uses a local `buf` when there is one,
and otherwise the `bufbuild/buf` Docker image. `buf breaking` compares the working tree with the git ref that
you pass, `main` by default, and skips the comparison when that ref has no `preferences-proto/buf.yaml` yet.

- The lint uses the `STANDARD` rules. It leaves out two rules about response message names, because the calls
  return the `Preferences` message directly.
- The breaking check uses the `FILE` rules. They report a field type change, a removed or renumbered field,
  and a rename.
- The package `preferences.v1` carries the major version. For an incompatible change, add a `preferences.v2`
  package and serve both versions during the transition.
- Adding a field passes the check. Mark the number and name of a removed field `reserved`.

## Limits

- The HTTP routes of `advisor-service` and `partner-service` allow calls from the internet and check no caller,
  so that you can try the sample with `curl`. Restrict the ACL and add token checks before you reuse them.
  This includes `POST /customers/assistant`, where every call is also a request to a model.
- After `preferences-service` restarts, `advisor-service` takes several seconds to reconnect to the stream,
  and its view lags until then. A list can also lag behind a write by a short time.
- The live question of `advisor-service` waits up to 5 seconds. A write that times out can still have been
  applied, so read before you write again. `partner-service` sets no timeout of its own.
- The history row of a customer grows with every write. That fits an audit trail on a sample. A customer with
  a very large number of changes needs another shape.
- The sample stores opaque customer ids and no personal data. A real consent store has to meet rules on erasure
  and retention, which an append-only history makes harder. Decide that before you reuse the design.
- The common service has no delete operation.
- A customer with no record has every channel off. Nobody may contact them until a preference is recorded.
