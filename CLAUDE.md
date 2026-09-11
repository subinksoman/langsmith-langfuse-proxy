# langsmith-langfuse-proxy

A Spring Boot service that impersonates LangSmith so n8n's AI nodes can be
observed in Langfuse. n8n is pointed at it with `LANGCHAIN_ENDPOINT`; it
rewrites each batch into Langfuse ingestion events and forwards them to the
right project.

## Build, run, test

```bash
mvn -o compile                       # offline once deps are cached
mvn -o package -DskipTests

docker compose -f langfuse.yaml up -d                    # Langfuse, owns the network
export LANGFUSE_PUBLIC_KEY=pk-lf-... LANGFUSE_SECRET_KEY=sk-lf-...
export DOCKER_UID=$(id -u) DOCKER_GID=$(id -g)           # so the container can write ./logs
docker compose -f docker-compose.n8n.yml up -d --build   # proxy + n8n + mock LLM
```

Test suites are in `scripts/` and documented in `TESTING.md`. They assert
against the live Langfuse API, not mocks, so they need both stacks up.

## Why it is shaped the way it is

Most of the design exists to work around how LangSmith and n8n actually behave.
Changing any of the following will silently lose data in the Langfuse UI.

**A run arrives twice, in separate HTTP requests.** The POST carries name,
model, inputs and n8n metadata; the PATCH carries outputs, token usage and
end_time — and frequently no `trace_id`. `TraceContextCache` remembers, per run
id, which trace and project the POST belonged to, plus the descriptive fields
only the POST had. `dotted_order` resolves the trace even on a cold cache.

**Never write a placeholder.** Langfuse merges events last-non-null-wins, so a
second event carrying `name: "LLMGeneration"`, `model: "unknown"` or
`startTime: now` overwrites the real values. A patch-only run therefore emits
only an *update*, never a create — that is what `PATCH_ONLY_MARKER` is for. The
same rule governs the trace: a name or session id that was merely guessed must
not be written.

**A batch can hold several traces, for several projects.** Runs are grouped by
trace so each gets its own name, input, metadata and output, then traces are
grouped by n8n node because each node routes to a different Langfuse project
with its own credentials. `transformAll` returns one payload per project.

**Langfuse answers 207.** A 2xx does not mean the events were accepted;
per-event rejections appear only in the body. `LangfuseService.logPartialFailures`
reports them, and a partial failure is treated as a failure.

**Current LangSmith SDKs post multipart.** langsmith-js 0.6+ (bundled in n8n)
uses `/runs/multipart` whatever `/info` advertises. `MultipartRunAssembler`
rebuilds the batch from the `post.<id>` / `post.<id>.inputs` / `.outputs` parts.

**Project routing** resolves a node name per trace, highest first: a
`node:<name>` tag, `metadata.node_name`, `metadata.node` *only if it matches a
configured project* (n8n auto-injects the node's display name there), a plain
tag matching a project, a name prefix, then inheritance from the trace cache or
an execution sibling. Unresolved traces go to the default project.

## n8n constraints worth remembering

- **A native AI Agent node cannot carry run metadata.** It has no field for it,
  and n8n wraps its model sub-node in a logging proxy that discards metadata
  assigned to the instance. Verified three ways; none work.
- The workaround is **one LangChain Code node per n8n execution**. It can set
  metadata via `.withConfig({metadata})`, and the proxy shares what any trace of
  an `execution_id` declared with the others. It is an agent call itself, so
  seeding costs no extra LLM round trip.
- `withConfig` metadata **replaces** what n8n injected, so `execution_id` has to
  be re-declared by hand or the correlation key is lost.
- **A sub-workflow gets its own execution id** and no link back to the caller,
  so it needs its own seeding node; pass `sessionId`/`msg_id` in through
  `inputSource: passthrough`.
- LangSmith's `session_name` is `LANGCHAIN_PROJECT` — a *project* name, not a
  conversation. It must never become the Langfuse `sessionId`.
- A native agent emits **one trace per LLM call**, so a tool-calling turn
  produces two traces rather than one nested tree.
- `n8n import:workflow` deactivates the workflow; a webhook needs
  `n8n publish:workflow --id=<id>` followed by a container restart.
- The n8n CLI starts its own task-runner broker, so `n8n execute` needs
  `N8N_RUNNERS_ENABLED=false`.
- **A sub-node expression resolves against its parent node's input item.** Use
  `{{ $json.<field> }}`; a `$('Other Node')` reference from a sub-node can fail
  with an opaque "Error in sub-node X" that appears nowhere in the logs. This
  matters for a memory node's `sessionKey`: read the value from the item the
  agent was given, not from the raw webhook body, or a request that arrives
  without a sessionId has no key and the whole execution fails.

## Conventions

- The chat contract is `sessionId` (the conversation, and the memory key) and
  `msg_id` (one user message). Never conflate them. Both are minted at the
  entry node when the caller omits them, and only there — a second mint
  downstream would split one message across two traces — and both are echoed
  in the response so the caller can reuse the session on its next turn.
- One user message is **one Langfuse trace**, keyed on a hash of
  (sessionId, msg_id), with every agent, sub-agent and tool of that message as
  observations inside it. `execution_id` / `node_execution_id` identify a single
  agent execution and live on the observations, for debugging and retries.
- Missing `user_id` defaults to `sixdee`.
- Logs roll at 10MB, 30 archives, 500MB total; tune with `logging.file.*`.
  Per-request access lines are DEBUG — the healthcheck hits `/health` every 15s.

## Credentials

`src/main/resources/application.properties` is **packaged into the jar**, so
anything written there ships inside the published image and is readable by
anyone who pulls it (`unzip -p app.jar BOOT-INF/classes/application.properties`).
It therefore carries no values: the default project reads
`${LANGFUSE_PUBLIC_KEY:}` / `${LANGFUSE_SECRET_KEY:}`, and the per-node project
map is commented out. Supply credentials at runtime instead:

- `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` — the default project
- `SPRING_APPLICATION_JSON` — the per-node map, e.g.
  `{"langfuse":{"projects":{"crm":{"public-key":"pk-lf-...","secret-key":"sk-lf-..."}}}}`
- or a mounted `config/application.properties` (see `docker-compose.n8n.yml`)

A project present in the map but without credentials is treated as unconfigured
and falls back to the default project, so a half-finished config loses routing
rather than the whole batch.

`config/application.properties` is a bind mount, never baked into the image —
but it does hold real keys and is committed, so it should move to a secret
store before this repo is shared.
