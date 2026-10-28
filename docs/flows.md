# Flows of the customer preferences service

Diagrams of every flow in the sample. GitHub renders the Mermaid blocks. The page `docs/flows.md` is the source for the diagrams.

## Modules and build-time dependencies

The proto jar is the contract for gRPC. Each module that needs stubs unpacks the jar at build time. No module depends on another module's code.

```mermaid
flowchart LR
  proto["preferences-proto<br/>.proto files only"]
  service["preferences-service<br/>common service"]
  advisor["advisor-service<br/>same project"]
  partner["partner-service<br/>other project"]
  proto -- "unpack at build" --> service
  proto -- "unpack at build" --> advisor
  proto -- "unpack at build" --> partner
```

## Runtime topology

Who calls whom. The three access patterns are in one picture. advisor-service calls by service name. partner-service calls the public address. An MCP client calls the public address too. Only the stream feeds a local view, and it stays inside the project.

```mermaid
flowchart LR
  adv(["Advisor user"])
  partnerUser(["Partner user"])
  assistant(["MCP client or LLM"])
  subgraph projectA["Akka project"]
    advisor["advisor-service"]
    view[("ContactPreferencesByCustomerView")]
    prefs["preferences-service<br/>gRPC, HTTP and MCP endpoints"]
    entity[("CustomerPreferencesEntity<br/>one per customer")]
    history[("PreferenceHistoryView")]
    publisher["PreferenceChangesPublisher"]
  end
  subgraph projectB["Other Akka project"]
    partner["partner-service"]
  end
  adv -- "HTTP" --> advisor
  advisor -- "gRPC write, token, by service name" --> prefs
  advisor -- "HTTP can-contact, token, by service name" --> prefs
  prefs --> entity
  entity --> publisher
  publisher -- "stream preference-changes" --> view
  advisor -- "reads" --> view
  entity --> history
  partnerUser -- "HTTP" --> partner
  partner -- "gRPC, token, public address" --> prefs
  assistant -- "MCP /mcp, token, public address" --> prefs
```

## Write from the same project

advisor-service writes over gRPC. The token subject becomes the `changedBy` value. The entity records one event with all three channels.

```mermaid
sequenceDiagram
  actor U as Advisor user
  participant A as advisor-service
  participant E as preferences-service gRPC endpoint
  participant K as CustomerPreferencesEntity
  U->>A: PUT /customers/c-1/contact-preferences
  A->>E: Update over gRPC with a bearer token
  E->>E: ACL allows advisor-service, JWT checks the issuer
  E->>E: subject of the token becomes changedBy
  E->>K: update command
  alt the rules pass
    K-->>E: PreferencesUpdated persisted
    E-->>A: Preferences
    A-->>U: 200
  else a rule is broken
    K-->>E: error
    E-->>A: INVALID_ARGUMENT
    A-->>U: 400
  else the service cannot be reached
    A-->>U: 503
  end
```

## Read paths in advisor-service

Two questions, two sources. The read of the preferences comes from the local view. The live question calls the service. Choose per question.

```mermaid
flowchart TD
  q1["GET contact-preferences"] --> v["ContactPreferencesByCustomerView<br/>local, may lag, answers when the service is down"]
  v --> r1["200, known true or false"]
  q2["GET can-contact/channel"] --> h["HTTP call by service name<br/>exact, fails when the service is down"]
  h --> r2["200 or 503 after the timeout"]
```

## The preference-changes stream

Each accepted write becomes a PreferenceChanged event. It holds the customer id, the three flags and the time. It does not hold who changed it or why. A reader ignores an event older than its row, because delivery is at least once and not ordered.

```mermaid
sequenceDiagram
  participant K as CustomerPreferencesEntity
  participant P as PreferenceChangesPublisher
  participant V as ContactPreferencesByCustomerView
  participant A as advisor-service endpoint
  actor U as Advisor user
  K->>P: PreferencesUpdated
  P->>P: build PreferenceChanged without changedBy and reason
  P-->>V: stream preference-changes, delivered at least once
  alt changedAt is newer than the row
    V->>V: replace the row
  else older or equal
    V->>V: ignore the event
  end
  U->>A: GET /customers/c-1/contact-preferences
  A->>V: query by customer
  V-->>A: row
  A-->>U: 200
```

## When preferences-service is unavailable

The read from the view keeps answering. The write and the live question fail with 503. After the service is back, the view lags until the stream reconnects. That took about 8 seconds in a local run.

```mermaid
flowchart TD
  down["preferences-service is unavailable"]
  down --> read["advisor: GET contact-preferences"]
  read --> readok["answers from the local view, 200"]
  down --> write["advisor: PUT contact-preferences"]
  write --> write503["gRPC UNAVAILABLE, 503, nothing applied"]
  down --> live["advisor: GET can-contact"]
  live --> live503["no answer within the timeout, 503"]
  down --> p["partner: GET or PUT"]
  p --> p503["503, partner has no local copy"]
  down --> back["service is back"]
  back --> lag["view lags until the stream reconnects"]
```

## Other project

partner-service builds from the proto jar only. It calls the public address with a bearer token. A service stream does not cross projects, so every read is a call.

```mermaid
sequenceDiagram
  actor U as Partner user
  participant PS as partner-service
  participant I as Akka ingress
  participant S as preferences-service
  U->>PS: PUT /customers/c-1/contact-preferences
  PS->>I: gRPC Update with a bearer token, public address
  I->>S: forward the request as the internet principal
  S->>S: ACL allows the internet, JWT checks issuer and signature
  S-->>PS: Preferences or a status code
  PS-->>U: 200, 400 or 503
  Note over PS,S: No stream across projects, so every read is a Get call
```

## Interactive and LLM clients

The MCP endpoint offers two read-only tools. The token rule is the same as for the other endpoints. Access control applies to the whole endpoint, not to single tools, so there is no write tool.

```mermaid
sequenceDiagram
  participant M as MCP client
  participant E as PreferencesMcpEndpoint at /mcp
  participant K as CustomerPreferencesEntity
  M->>E: tools/list with a bearer token
  E-->>M: get_contact_preferences, can_contact
  M->>E: tools/call can_contact customerId and channel
  E->>K: get
  K-->>E: preferences
  E-->>M: text answer
  M->>E: any call without a token
  E-->>M: rejected with an HTTP status
```

## Audit history

Every write is an event. PreferenceHistoryView keeps one row per customer. The history routes need the claim `role=auditor`.

```mermaid
flowchart LR
  w["any write<br/>gRPC or HTTP"] --> k["CustomerPreferencesEntity<br/>PreferencesUpdated event<br/>with changedBy, reason, changedAt"]
  k --> h[("PreferenceHistoryView<br/>one row per customer")]
  h --> q1["GET /preferences/id/history"]
  h --> q2["GET /preferences/changes-by/caller"]
  q1 --> a{"token has role auditor?"}
  q2 --> a
  a -- "yes" --> ok["200 with the changes"]
  a -- "no" --> no["403"]
```

## Who may call preferences-service

The ACL decides which callers may reach an endpoint. The token rule applies on top of it to every caller, including advisor-service.

```mermaid
flowchart TD
  req["Request to an endpoint"] --> acl{"ACL: caller is advisor-service<br/>or comes from the internet?"}
  acl -- "no" --> denied["rejected by the ACL"]
  acl -- "yes" --> jwt{"Bearer token present?"}
  jwt -- "no" --> t400["400"]
  jwt -- "yes" --> iss{"issuer preferences-callers<br/>and signature valid?"}
  iss -- "no" --> t403["403"]
  iss -- "yes" --> role{"history route?"}
  role -- "no" --> run["the call runs"]
  role -- "yes" --> aud{"role auditor?"}
  aud -- "no" --> t403b["403"]
  aud -- "yes" --> run
```

## Changing the contract

The checks run before the jar is published. A breaking change needs a new package version, and both versions run during the transition.

```mermaid
flowchart LR
  edit["Edit preferences.proto"] --> lint["buf lint"]
  lint --> brk["buf breaking<br/>against main"]
  brk --> q{"breaking change?"}
  q -- "yes" --> v2["add package preferences.v2<br/>and serve both versions"]
  q -- "no" --> pub["publish the preferences-proto jar"]
  v2 --> pub
  pub --> use["services unpack the jar at build<br/>and generate their stubs"]
```
