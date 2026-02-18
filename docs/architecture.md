# Pi Mobile Architecture (High-Level)

This document gives a high-level view of how Pi Mobile works across Android, bridge, and pi runtime.

## 1) System Context

```mermaid
flowchart LR
    User[Mobile user]

    subgraph Android[Android app]
      Hosts[Hosts + Tokens]
      Sessions[Sessions screen\ncache + filter]
      Chat[Chat screen + ViewModel]
      Net[PiRpcConnection\nWebSocketTransport]
    end

    subgraph Bridge[Node bridge]
      WS[WebSocket server\n/auth + envelope routing/]
      Locks[Control lock manager\n(cwd + session)]
      PM[Process manager\n(one pi process per cwd)]
      Indexer[Session indexer\nreads JSONL sessions]
      Ext[Internal extensions\npi-mobile-tree\npi-mobile-open-stats]
    end

    subgraph Laptop[Local pi runtime]
      Pi[pi --mode rpc]
      Files[(~/.pi/agent/sessions/*.jsonl)]
    end

    User --> Android
    Hosts --> Sessions
    Sessions --> Net
    Chat --> Net

    Net <-->|ws://.../ws\n{channel,payload}| WS
    WS --> Locks
    WS --> PM
    WS --> Indexer

    PM --> Pi
    Pi --> Ext
    Indexer --> Files
    Pi --> Files
```

## 2) Main Runtime Flow (Resume + Prompt)

```mermaid
sequenceDiagram
    participant A as Android app
    participant B as Bridge
    participant P as pi (RPC)

    A->>B: WebSocket connect + Bearer token
    B-->>A: bridge_hello { clientId, resumed, cwd }
    A->>B: bridge_set_cwd
    B-->>A: bridge_cwd_set
    A->>B: bridge_acquire_control
    B-->>A: bridge_control_acquired

    A->>B: rpc:get_state + rpc:get_messages
    B->>P: forward RPC
    P-->>B: response events
    B-->>A: rpc envelopes

    A->>B: rpc:prompt
    B->>P: prompt
    P-->>B: message_update/tool events/agent_end
    B-->>A: streamed rpc events
```

## 3) Reconnect + Resync Strategy

```mermaid
flowchart TD
    D[Socket disconnect detected] --> R[WebSocketTransport enters RECONNECTING]
    R --> C{Reconnect succeeds?}

    C -- No --> B[Backoff + retry]
    B --> R

    C -- Yes --> H[Wait for new bridge_hello]
    H --> S[Re-run bridge_set_cwd]
    S --> L[Re-acquire control lock]
    L --> G[get_state + get_messages]
    G --> U[Emit RpcResyncSnapshot]
    U --> V[ChatViewModel refreshes timeline/streaming state]
```

## 4) Tree Navigation Bridge Flow

```mermaid
flowchart LR
    A[User selects tree entry] --> B[Android sends bridge_navigate_tree]
    B --> C[Bridge validates cwd + control lock]
    C --> D[Bridge checks get_commands for pi-mobile-tree]
    D --> E[Bridge sends rpc prompt: /pi-mobile-tree <entryId> <statusKey>]
    E --> F[Extension navigates tree + setEditorText + setStatus]
    F --> G[Bridge captures setStatus payload]
    G --> H[Bridge returns bridge_tree_navigation_result]
    H --> I[Android updates tree + editor draft]
```

## 5) Control-Lock Model

```mermaid
stateDiagram-v2
    [*] --> Unlocked
    Unlocked --> LockedByClientA: bridge_acquire_control(cwd[, sessionPath])
    LockedByClientA --> LockedByClientA: same client re-acquires
    LockedByClientA --> DeniedForOthers: other client acquire attempt
    DeniedForOthers --> LockedByClientA
    LockedByClientA --> Unlocked: bridge_release_control / disconnect timeout
```

## Architectural Notes

- **Bridge is mandatory**: pi RPC is stdio-based; the bridge provides network transport + policy.
- **Per-cwd subprocesses**: isolates project state and keeps tool cwd semantics correct.
- **Control lock before RPC**: prevents concurrent writers to the same cwd/session.
- **Resync after reconnect**: avoids stale UI after transient network failures.
- **Freshness polling in chat**: detects cross-device/session-file drift and prompts user to sync.
- Decision rationale is captured in [ADRs](adr/README.md).
