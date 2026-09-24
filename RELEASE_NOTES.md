# Release notes

## 3.0.1

Token usage: support `tokenUsageEstimate`, and stop combining sources.

A provider can report a measured `tokenUsage` alongside LangChain's
`tokenUsageEstimate`, and the two disagree sharply — 72 measured prompt tokens
against 1840 estimated is typical for a tool-calling turn. Usage was previously
assembled by taking the larger of each field across every source, which with
both blocks present produced `promptTokens` from the estimate and
`completionTokens` from the measurement: a triple matching neither, and a
roughly 25x inflated prompt cost.

- `tokenUsageEstimate` is now read, from `llmOutput`, `response_metadata` or
  the message kwargs.
- Each usage block is read **whole**. Sources are tried in order and the first
  that reports tokens wins, so the three numbers always come from one place.
- Measured usage always beats an estimate. The estimate is a fallback for
  providers that report nothing.
- An estimate is labelled `token_usage_source: "estimate"` on the observation,
  because Langfuse prices it exactly as it prices measured tokens.

Every usage shape previously recognised still is — `llmOutput.tokenUsage`,
`.usage`, `.usage_metadata`, `kwargs.usage_metadata`,
`response_metadata.usage` and `.token_usage` — now sharing one alias table
covering the OpenAI, Anthropic, Google and LangChain spellings.

## 3.0.0

One user message is now one Langfuse trace, and n8n no longer waits for Langfuse.

Both are breaking changes to what downstream sees, hence the major version.

### Breaking

**A trace is now a message, not a LangSmith trace.** Every n8n AI node opens its
own LangSmith trace, so a message that passed through three agents and a
sub-workflow used to arrive in Langfuse as four unrelated traces. The Langfuse
trace id is now derived from `(sessionId, msg_id)`, which collapses them into
the single request the user actually made, with each agent, sub-agent and tool
as observations inside it.

Trace ids therefore change shape: they are a hash of the pair rather than
LangSmith's id. Anything holding old trace ids will not resolve. Traces already
in Langfuse are untouched.

**Ingestion answers `202 Accepted`, not `200 OK`.** Batches are queued and
delivered off the request thread, so a 2xx now means "accepted for delivery",
not "Langfuse has it". Callers that assert on `200` specifically must be
relaxed to any 2xx. Set `proxy.async.enabled=false` to restore inline
forwarding.

### Added

- **Async delivery.** n8n is answered as soon as a batch is queued — measured
  2–28 ms against 17–73 ms before, since the Langfuse round trip is no longer
  on the critical path. Batches are partitioned across workers by project and
  each worker is single-threaded, so a project's batches keep the order they
  were transformed; Langfuse merges last-non-null-wins, and reordering would
  let an earlier value overwrite a later one. Queue depth, accepted, delivered,
  failed and dropped are exposed on `/health`. A full queue applies
  backpressure for up to a second and then returns 502 rather than discarding
  traces silently, and the queue is drained on shutdown.
- **Tool calls are correlated to their message.** n8n runs a tool as its own
  root run, in its own request, with no execution id, no parent and no message
  id. The proxy indexes the tool an agent asked for and matches the run that
  executes it. When two messages have a live request for the same tool the
  match is ambiguous, and the run is left standalone rather than filed against
  a guess — a misattributed tool call is silently wrong, an unattributed one is
  visibly missing.
- **Sub-workflows join the caller's trace.** They run under their own n8n
  execution id with no link back, so the sub-workflow declares the same
  `msg_id` and lands on the same trace.
- **Ids are minted when absent.** A request with no `sessionId` or `msg_id`
  gets one, minted once at the entry node and echoed in the response so the
  caller can reuse the session on its next turn. With nothing declared at all,
  the proxy still groups by n8n execution rather than by AI node.
- `node_execution_id` on every observation, so a single agent execution can be
  followed for debugging and retries while `msg_id` ties the agents together.

### Fixed

- A trace's input could end up permanently empty. A tool call adopted into a
  message marked the trace input as already written; if it arrived before the
  message's own batch, the user's question could never be written afterwards.
- The trace input was whichever agent reached the trace first — on a
  classifier-led flow, its system prompt rather than the user's question. The
  workflow now declares the input outright.
- LangSmith's `session_name` is `LANGCHAIN_PROJECT`, a project name rather than
  a conversation. Using it as the Langfuse `sessionId` put every trace from an
  n8n instance into one session.
- A pom change re-downloaded every Maven dependency, turning a 15s rebuild into
  minutes. A BuildKit cache mount takes it to 10s.
- Per-request access logging moved to DEBUG; the container healthcheck hits
  `/health` every 15s and was burying the ingestion logs.

### Notes

- `n8n`'s env uses the current `LANGSMITH_*` names. `LANGCHAIN_ENDPOINT` is
  kept deliberately: `run_trees.js` reads it directly with no `LANGSMITH_`
  fallback and defaults to `http://localhost:1984`, so leaving it unset would
  discard traces rather than fail.
- Verified end to end against a live Langfuse: 22 correlation cases covering
  supplied/minted ids, multi-turn, retries, interleaved sessions, sub-workflows
  and sequential and concurrent tool calls.

## 2.0.1

Version bump only; no behaviour change.

## 2.0.0

LangSmith reports a run twice — a POST when it starts and a PATCH when it ends —
and the PATCH, which carries the outputs, token usage and latency, was landing
on an invented trace in the wrong project. Current n8n fared worse still: its
LangSmith SDK posts multipart, and that endpoint returned 500.

- POST and PATCH halves reunited via a trace-context cache and `dotted_order`
- `/runs/multipart` implemented (langsmith-js 0.6+, bundled in n8n)
- One batch per project; per-trace name, input, metadata and output
- Langfuse 207 partial failures surfaced instead of reported as success
- `modelParameters`, full metadata, `finish_reason` and usage carried through
- n8n `execution_id` correlation, so native AI Agent nodes inherit session,
  user and project
- Credentials removed from the published image
- 10MB log rollover
