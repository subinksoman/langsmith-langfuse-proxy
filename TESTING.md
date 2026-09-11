# Testing the LangSmith → Langfuse proxy

Three suites, smallest blast radius first. All of them need the Langfuse stack
and the n8n stack running, and the two keys of the Langfuse project the proxy
writes to.

```bash
docker compose -f langfuse.yaml up -d                       # Langfuse (owns the network)

export LANGFUSE_PUBLIC_KEY=pk-lf-...                        # target project
export LANGFUSE_SECRET_KEY=sk-lf-...
export DOCKER_UID=$(id -u) DOCKER_GID=$(id -g)              # so the container can write ./logs

docker compose -f docker-compose.n8n.yml up -d --build      # proxy + n8n + mock LLM
```

| | command | what it proves |
|---|---|---|
| 1 | `./scripts/e2e-test.sh` | A POST/PATCH pair sent as separate HTTP requests lands on **one** trace with input, output, tokens, model parameters and real latency. This is the split that used to lose everything. |
| 2 | `./scripts/run-all-tests.sh` | The above, plus n8n's own bundled LangChain + LangSmith SDK round-tripping through `/runs/multipart`. |
| 3 | `./scripts/n8n-workflow-test.sh` | Three real n8n workflows executed by the n8n CLI, asserted through the Langfuse API. |
| 4 | `./scripts/chat-webhook-test.sh` | The chat webhook: two interleaved sessions, asserted to segregate by `sessionId` while each `msg_id` gets its own trace. |
| 5 | `./scripts/multi-agent-test.sh` | Three agents in sequence behind one webhook, on OpenRouter, all landing in one Langfuse session. |
| 6 | `./scripts/subflow-test.sh` | A parent workflow calling a sub-workflow, with the sub's agents still landing in the caller's session. |

## Per-project routing

The proxy picks a Langfuse project per trace. In priority order it looks at a
`node:<name>` tag, `metadata.node_name`, then `metadata.node` — and for
`metadata.node` only accepts values that match a configured project, because
n8n auto-injects the node's *display name* there and most display names are not
projects.

Map projects without editing `application.properties`:

```bash
export SPRING_APPLICATION_JSON='{"langfuse":{"projects":{
  "crm":{"public-key":"pk-lf-...","secret-key":"sk-lf-..."}}}}'
docker compose -f docker-compose.n8n.yml up -d --force-recreate langsmith-proxy
```

Two ways to drive it from a workflow, both covered by suite 3:

- **Name the n8n node after the project.** `n8n-workflows/weather-routed.json`
  names its Basic LLM Chain node `weather`; n8n injects that as
  `metadata.node` and the proxy matches it. No code.
- **Inject metadata explicitly.** `n8n-workflows/crm-session-routing.json`
  uses a LangChain Code node with
  `.withConfig({ tags: ['node:crm'], metadata: { node, session_id, user_id, workflow_name } })`.
  This is also the only way to get a real **session** and **user**.

## Sessions

n8n sets `LANGCHAIN_PROJECT`, which LangSmith reports as `session_name`. That is
a *project* name, not a conversation, so the proxy no longer uses it as the
Langfuse `sessionId` — doing so put every trace from an instance into one
session. A session must come from `metadata.session_id`, as in the crm workflow.

## Cost

Langfuse computes cost from its own model price list. `gpt-4o-mini` is there, so
suite 3 asserts a non-zero cost; a self-hosted model such as
`llama-3.3-70b-versatile` is not, and shows zero until you define it under
**Settings → Models**.

## The mock LLM

`scripts/mock-openai/server.js` is an OpenAI-compatible endpoint so the
workflows run with no API key and no egress. It returns a full chat-completion
envelope — `choices`, `finish_reason` and a `usage` block — because those are
exactly the fields the proxy has to carry through.

## The chat webhook: sessionId vs msg_id

`n8n-workflows/chat-webhook.json` is a `POST /webhook/chat` endpoint that takes
the chat payload and keeps the two identifiers in their distinct roles:

```json
{ "action": "sendMessage", "sessionId": "SESSION-1001", "route": "support",
  "chatInput": "What is the weather in Kochi today?",
  "message": { "payload": "..." }, "msg_id": "MSG-001" }
```

| payload field | role | where it lands in Langfuse |
|---|---|---|
| `sessionId` | the conversation — same for every call of a chat | the **Session**, via `metadata.session_id`; also the chat-memory key, so history carries across turns |
| `msg_id` | one webhook call — new for every message | its **own trace**, tagged `msg:<id>` and recorded as `metadata.msg_id` |
| `chatInput` / `message.payload` | the user's text | the trace input |
| `route`, `action` | request context | trace metadata |
| `user_id` | who is chatting | the trace's `userId` |

Conflating the two is the failure this design avoids: keying memory on `msg_id`
would restart the conversation every turn, and putting `msg_id` in `session_id`
would give every message its own single-trace Session.

The mapping is done in the workflow's LangChain Code node, on the run config:

```js
.withConfig({
  runName: `chat ${msgId}`,
  tags: ['node:crm', `msg:${msgId}`],
  metadata: { node: 'crm', session_id: sessionId, msg_id: msgId, user_id, route, action },
})
```

### Running it

The webhook needs an **active** workflow, and `n8n import:workflow` cannot
activate one in regular (non-queue) deployment mode:

```bash
docker cp n8n-workflows/chat-webhook.json n8n-langfuse-test:/home/node/wf/
docker exec n8n-langfuse-test n8n import:workflow  --input=/home/node/wf/chat-webhook.json
docker exec n8n-langfuse-test n8n publish:workflow --id=chatWebhookTest
docker restart n8n-langfuse-test          # the webhook registers on boot

curl -X POST http://localhost:5678/webhook/chat -H 'Content-Type: application/json' \
  -d '{"action":"sendMessage","sessionId":"SESSION-1001","route":"support",
       "chatInput":"hello","message":{"payload":"hello"},"msg_id":"MSG-001"}'
```

Then open Langfuse → **Sessions** → `SESSION-1001`: one session, one trace per
`msg_id`, with the prompt growing 2 → 4 → 6 messages across the turns.

## Multi-agent flow (OpenRouter)

`n8n-workflows/multi-agent-support.json` — `POST /webhook/support`. Three agent
calls in a straight line, each waiting for the one before it, with plain n8n
nodes between them:

```
Webhook → Intent Classifier → Prepare Specialist Brief
        → Specialist Agent → Extract Specialist Answer → Summariser Agent
        → Build Response
```

There is no normalisation step: agent 1 reads the webhook body exactly as it
arrives and passes it on untouched apart from the `intent` it adds. The only
fallback is a missing `user_id`, which becomes **`sixdee`**.

| node | kind | role |
|---|---|---|
| Prepare Brief, Extract Answer, Build Response | plain Code | hand off, carry state, assemble the reply |
| **Intent Classifier** | LangChain Code node | agent 1 — weather / billing / general |
| **Specialist Agent** | native n8n AI Agent (+ Calculator tool, session memory) | agent 2 — answers, briefed by agent 1 |
| **Summariser Agent** | native n8n AI Agent | agent 3 — rewrites agent 2's answer |

All three share one `lmChatOpenRouter` sub-node (`openai/gpt-4o-mini`).

### Why agent 1 is a Code node

It is the only kind of AI node that can set run metadata. n8n's native **AI
Agent node has no field for it**, and it wraps the model sub-node in a logging
proxy that discards metadata assigned to the instance — so an Agent's trace
reaches Langfuse with an n8n `execution_id` and nothing else: no session, no
project.

The proxy closes that gap: it remembers what any trace of an `execution_id`
declared and applies it to the others. Agent 1 declares `session_id`, `msg_id`,
`user_id` and `node` (plus `execution_id`, which `withConfig` metadata would
otherwise replace), and agents 2 and 3 inherit all of it.

### Credentials

```bash
# Real OpenRouter: set the key and drop the url override in n8n-workflows/credentials.json
export OPENROUTER_API_KEY=sk-or-v1-...
export OPENROUTER_BASE_URL=https://openrouter.ai/api/v1
```

Unset, the credential points at `scripts/mock-openai`, which also answers
classification prompts with one word and emits a tool call the first time it is
offered tools — so the agent loop is real.

### One trace per LLM call

A native n8n AI Agent emits **each LLM call as its own root run**, so a turn
where the specialist calls a tool produces two traces, not one nested tree. They
still group correctly by session and `msg_id`; only the nesting is lost, and
that is n8n's tracing shape rather than something the proxy can rebuild.

## Sub-workflows

They work, but not for free. **n8n runs a sub-workflow under its own execution
id** — the proxy's execution correlation is scoped to one execution by design,
so nothing crosses the boundary on its own. Measured:

```
EXEC  MSG_ID   AGENT          WORKFLOW             SESSION
55    MSG-S1   classifier     Subflow Parent       6d_sub01
56    MSG-S1   native-agent   Support Sub-agent    None      ← orphaned
```

A sub-workflow built only from native AI Agent nodes cannot attribute itself at
all: those nodes have no field for run metadata, and nothing upstream reaches
them.

**The rule is one LangChain Code node per execution** — parent, and each
sub-workflow. It is the only kind of AI node that can declare metadata, and it
is an agent call in its own right, so seeding costs no extra LLM round trip.
Native agents beside it inherit through that execution's own id.

`n8n-workflows/support-subagent.json` does exactly this: a `Sub Specialist`
Code node declares `session_id` / `msg_id` / `node` from its input, and a native
`Sub Agent` after it inherits them. The parent passes its own execution id
along as `parent_execution_id` in the workflow data, since n8n gives the
sub-workflow no link back:

```
EXEC  MSG_ID   AGENT          WORKFLOW             PARENT_EXEC   SESSION
57    MSG-S2   classifier     Subflow Parent       None          6d_sub02
58    MSG-S2   subflow        Support Sub-agent    57            6d_sub02
58    MSG-S2   native-agent   Support Sub-agent    None          6d_sub02
```

The sub-workflow's trigger uses `inputSource: passthrough`, so `sessionId`,
`msg_id` and `user_id` arrive with the items and need no explicit mapping.
