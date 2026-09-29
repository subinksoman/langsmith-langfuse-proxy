# langsmith-langfuse-proxy

A Spring Boot service that impersonates LangSmith so n8n's AI nodes can be
observed in Langfuse.

n8n's LangChain nodes emit LangSmith traces whenever `LANGSMITH_TRACING` is set.
Point `LANGSMITH_ENDPOINT` at this proxy and it rewrites each batch into Langfuse
ingestion events, routing them to the right project. **n8n needs no workflow
changes for this** — the env var is the whole integration.

```
n8n ──LangSmith batch──► proxy ──Langfuse ingestion──► Langfuse
```

---

## Quick start

```bash
# 1. Langfuse (owns the shared docker network)
docker compose -f langfuse.yaml up -d

# 2. Credentials for the project the proxy writes to
export LANGFUSE_PUBLIC_KEY=pk-lf-...
export LANGFUSE_SECRET_KEY=sk-lf-...
export DOCKER_UID=$(id -u) DOCKER_GID=$(id -g)   # so the container can write ./logs

# 3. Proxy + n8n + a mock LLM
docker compose -f docker-compose.n8n.yml up -d
```

| | URL |
|---|---|
| Langfuse | http://localhost:3100 |
| proxy | http://localhost:3001 |
| n8n | http://localhost:5678 |

Check it is alive: `curl localhost:3001/health`

---

## Configuring n8n

### The minimum

```yaml
LANGSMITH_TRACING:  "true"
LANGSMITH_ENDPOINT: http://langsmith-proxy:3001
LANGSMITH_API_KEY:  anything-the-proxy-does-not-check
LANGSMITH_PROJECT:  n8n
```

That is enough for every AI node — agents, chains, chat models, prompts,
parsers, tools — to appear in Langfuse with inputs, outputs, model, token usage
and latency.

### The other variables, and why

| variable | purpose |
|---|---|
| `LANGSMITH_TRACING_BACKGROUND` | `true` flushes traces off the workflow's critical path. Only this spelling is read; `"false"` is what *disables* background mode. Set it `false` for `n8n execute` CLI runs, which otherwise exit before the flush. |
| `LANGCHAIN_ENDPOINT` | Legacy alias, kept deliberately. The SDK resolves `LANGSMITH_<NAME>` first and falls back to `LANGCHAIN_<NAME>` — but `run_trees.js` reads `LANGCHAIN_ENDPOINT` *directly*, with no fallback, defaulting to `http://localhost:1984`. Leaving it unset would discard traces rather than fail. Keep it equal to `LANGSMITH_ENDPOINT`. |

Point n8n at real LangSmith instead, to compare:

```bash
export LANGSMITH_ENDPOINT=https://api.smith.langchain.com
export LANGSMITH_API_KEY=lsv2_pt_...
docker compose -f docker-compose.n8n.yml up -d --force-recreate --no-deps n8n
```

Both are read from the environment, so no key is ever written into the compose
file. Unset them and recreate to go back to the proxy.

### What n8n traces by itself, and what it cannot

**Automatic** — every LangChain node. No workflow code.

**Never** — Set, Code, HTTP Request, IF, Switch, Postgres, Webhook and the rest.
`n8n-nodes-base` contains no reference to the tracer, so those nodes emit
nothing. To trace one, POST a run to the proxy yourself (see *Tracing non-AI
nodes*).

**Not carried, even for AI nodes:** n8n sends `execution_id`, `workflow` and
`node`, but nothing identifying a *conversation* or a *user message*. Sessions,
per-message traces and per-project routing therefore need a little workflow
code — one LangChain Code node per execution:

```js
const chain = prompt.pipe(llm).withConfig({
  tags: ['node:crm', `msg:${msgId}`],
  metadata: {
    node: 'crm',                      // routes to the crm Langfuse project
    session_id: sessionId,            // groups the conversation
    msg_id: msgId,                    // one user message = one trace
    user_id: userId,
    execution_id: String($execution.id),   // withConfig REPLACES n8n's metadata,
                                           // so put the execution id back
  },
});
```

A **native AI Agent node cannot do this** — it has no field for run metadata and
wraps its model in a proxy that discards metadata set on the instance. It
inherits from a Code node in the same execution instead. See `CLAUDE.md` for the
full set of n8n constraints.

---

## Configuring the proxy

Everything is standard Spring configuration: a property in
`config/application.properties`, an environment variable
(`LANGFUSE_PUBLIC_KEY` → `langfuse.public-key`), or `SPRING_APPLICATION_JSON`.

### Credentials

`src/main/resources/application.properties` is **packaged into the jar**, so it
deliberately carries no values — anything written there ships inside the
published image and is readable by anyone who pulls it. Supply them at runtime:

```bash
# default project
LANGFUSE_PUBLIC_KEY=pk-lf-...
LANGFUSE_SECRET_KEY=sk-lf-...

# per-node projects
SPRING_APPLICATION_JSON='{"langfuse":{"projects":{
  "crm":    {"public-key":"pk-lf-...","secret-key":"sk-lf-..."},
  "weather":{"public-key":"pk-lf-...","secret-key":"sk-lf-..."}}}}'
```

or mount a `config/application.properties` (git-ignored; see
`config/application.properties.example`).

A project listed without credentials is treated as unconfigured and falls back
to the default project, so a half-finished config loses routing rather than the
whole batch.

### Settings

| property | default | what it does |
|---|---|---|
| `langfuse.base-url` | — | Langfuse base URL, e.g. `http://langfuse-web:3000` |
| `langfuse.ingestion-endpoint` | `/api/public/ingestion` | ingestion path |
| `langfuse.public-key` / `.secret-key` | — | default ("catch-all") project |
| `langfuse.projects.<node>.public-key` / `.secret-key` | — | per-node project routing |
| `server.port` | `3001` | listen port |
| `proxy.async.enabled` | `true` | answer n8n `202` as soon as a batch is queued. `false` forwards inline, which is easier to debug because failures surface in the response |
| `proxy.async.workers` | `4` | delivery threads. Batches are partitioned by project, each worker single-threaded, so a project's batches keep their order |
| `proxy.async.queue-capacity` | `5000` | per worker |
| `proxy.async.offer-timeout-millis` | `1000` | how long a full queue applies backpressure before returning 502 |
| `proxy.async.max-retries` | `3` | delivery retries, linear backoff |
| `proxy.async.retry-backoff-millis` | `500` | backoff step |
| `proxy.async.shutdown-drain-seconds` | `20` | time to finish queued batches on shutdown |
| `proxy.trace-cache.ttl-minutes` | `180` | how long a run id stays resolvable to its trace. A LangSmith PATCH carries no `trace_id`, so this window is what lets outputs, token usage and end time reach the trace the POST created |
| `proxy.trace-cache.max-entries` | `20000` | cache bound |
| `proxy.orphan-adoption-window-seconds` | `120` | how recent a tool request must be to claim an incoming tool run |
| `proxy.log-requests` / `.log-responses` | `true` | log payloads at DEBUG |
| `proxy.timeout-seconds` | `30` | HTTP timeout to Langfuse |
| `spring.servlet.multipart.max-request-size` | `25MB` | LangSmith allows 20MB batches; the Spring default of 10MB would reject large traces |
| `logging.file.max-size` | `10MB` | log rollover |
| `logging.file.max-history` / `.total-size-cap` | `30` / `500MB` | archive bounds |

### Endpoints

| endpoint | purpose |
|---|---|
| `POST /runs`, `/runs/batch` | LangSmith JSON ingestion |
| `POST /runs/multipart` | multipart ingestion — **what current n8n actually uses** |
| `GET /info`, `/runs/info` | the LangSmith handshake |
| `GET /health` | health, plus `delivery`, `queueDepth`, `accepted`, `delivered`, `failed`, `dropped` |

`/health` is how you spot trouble: a `queueDepth` that keeps climbing means
Langfuse is not keeping up; `dropped` is the count that actually lost data.

---

## Project routing

The proxy picks a Langfuse project per trace, highest priority first:

1. a `node:<name>` tag
2. `metadata.node_name`
3. `metadata.node` — **only if it matches a configured project**, because n8n
   auto-injects the node's *display name* there and most display names are not
   projects
4. a plain tag matching a configured project
5. a run-name prefix (`crm_`, `crm-`)
6. inheritance from the trace cache, or a sibling in the same n8n execution
7. otherwise the default project

Two ways to drive it: name your n8n node exactly as the project key (zero code),
or declare `metadata.node` / a `node:` tag in a Code node (also gives you
session and user).

---

## The correlation model

```
sessionId                 the conversation — many messages
  └── msg_id  →  one Langfuse trace, keyed on hash(sessionId, msg_id)
        ├── Agent 1 ──► execution_id / node_execution_id
        ├── Agent 2 ──► execution_id
        ├── Tool     ──► matched to the agent that requested it
        └── Final response
```

- `sessionId` is the conversation and the chat-memory key.
- `msg_id` is one user message. **Minted once at the entry node** when the
  caller omits it, and only there — a second mint downstream would split one
  message across two traces. Both are echoed in the response so the caller can
  reuse the session next turn.
- `execution_id` / `node_execution_id` identify a single agent execution and
  live on the observations, for debugging and retries.

Missing `user_id` defaults to `sixdee`.

Because the trace id is derived rather than random, re-sending the same
`msg_id` lands on the **same** trace — a retry does not split.

### Sub-workflows

A sub-workflow runs under its own n8n execution id with no link back, so it
needs its own seeding Code node declaring the same `msg_id`. It then joins the
caller's trace. Pass the ids in with `inputSource: passthrough`.

### Tool calls

n8n runs a tool as its own root run, in its own request, with no execution id,
no parent and no message id. The proxy indexes the tool an agent asked for and
matches the run that executes it. When two messages have a live request for the
same tool the match is ambiguous, and the run is left standalone rather than
filed against a guess — a misattributed tool call is silently wrong, an
unattributed one is visibly missing.

---

## Tracing non-AI nodes

Regular n8n nodes emit nothing. To put one in the message's trace, POST a run
from an HTTP Request node:

```json
{"post":[{
  "id":"<uuid>", "name":"HTTP Request: fetch customer", "run_type":"tool",
  "trace_id":"<any id>",
  "start_time":"...Z", "end_time":"...Z",
  "inputs":{"method":"GET","url":"..."}, "outputs":{"status":200},
  "extra":{"metadata":{"session_id":"...","msg_id":"...","user_id":"...","node":"crm"}}
}]}
```

`session_id` + `msg_id` place it in the right trace; `trace_id` is ignored for
grouping. `run_type` controls rendering: `tool`/`chain` → SPAN, `llm` →
GENERATION.

---

## Token usage

Read from `llmOutput.tokenUsage`, `.usage`, `.usage_metadata`,
`kwargs.usage_metadata`, `response_metadata.usage` / `.token_usage`, and
`tokenUsageEstimate` as a last resort. Each block is read **whole** — sources
are never combined, because a measured block and an estimate can disagree
sharply and mixing them yields a total matching neither. Estimated usage is
labelled `token_usage_source: "estimate"` on the observation.

Two caveats worth knowing:

- n8n's own `tokenUsageEstimate` is written to its **execution data**, not the
  LangSmith stream, so the proxy never receives it. It is visible in the n8n UI
  and via `GET /executions/{id}?includeData=true`.
- When no usage reaches Langfuse at all, **Langfuse tokenises the text itself**
  for models it recognises and prices that. So an absent usage block does not
  mean zero tokens on the trace.

---

## Testing

Suites live in `scripts/` and assert against the live Langfuse API, not mocks.
See `TESTING.md`. `scripts/mock-openai/` is an OpenAI-compatible endpoint so the
example workflows run with no API key; set `MOCK_OMIT_USAGE=true` to make it
return no usage block.

---

## Further reading

- `CLAUDE.md` — why the code is shaped this way, and the n8n constraints behind it
- `TESTING.md` — the test suites and how to run them
- `RELEASE_NOTES.md` — what changed in each version
