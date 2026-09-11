/**
 * Minimal OpenAI-compatible chat server, so an n8n AI workflow can run
 * end-to-end without a paid API key or network egress.
 *
 * It returns a real chat-completion envelope — choices, finish_reason and a
 * usage block — because those are exactly the fields the proxy has to carry
 * through to Langfuse as output, finish_reason and token counts. A stub that
 * omitted usage would make the test pass while proving nothing about them.
 */
const http = require('http');

const PORT = process.env.PORT || 8080;

const REPLY =
  'Kochi is warm and humid today, around 31 degrees Celsius with a chance of afternoon showers.';

// An agent is only worth tracing if it actually loops, so the mock emits a
// tool call the first time it is offered tools and a plain answer once it sees
// the tool's result. That produces the real two-LLM-call agent trace rather
// than a single completion.
function decide(body) {
  const tools = body.tools || [];
  const messages = body.messages || [];
  const alreadyCalledTool = messages.some((m) => m.role === 'tool' || m.tool_call_id);

  if (tools.length > 0 && !alreadyCalledTool) {
    const tool = tools.find((t) => (t.function || {}).name) || tools[0];
    const name = (tool.function || {}).name;
    // The calculator is the only tool whose argument shape we can guess.
    const args = name && name.toLowerCase().includes('calc')
      ? { input: '31 * 9 / 5 + 32' }
      : { query: 'Kochi weather today' };
    return {
      tool_calls: [{
        id: 'call_mock_1',
        type: 'function',
        function: { name, arguments: JSON.stringify(args) },
      }],
    };
  }
  // A classifier prompt wants one word back, not the weather report. Echo the
  // intent the user's text implies so a routing Switch downstream really branches.
  const system = messages.find((m) => m.role === 'system');
  if (system && /classify/i.test(String(system.content || ''))) {
    const text = messages.filter((m) => m.role === 'user').map((m) => m.content).join(' ').toLowerCase();
    const intent = /bill|invoice|payment|refund/.test(text) ? 'billing'
                 : /weather|rain|temperature|celsius|fahrenheit|umbrella/.test(text) ? 'weather'
                 : 'general';
    return { content: intent };
  }

  return { content: REPLY };
}

function countTokens(text) {
  // Deliberately crude: the point is a stable, non-zero number, not accuracy.
  return Math.max(1, Math.ceil(String(text).length / 4));
}

const server = http.createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    const raw = Buffer.concat(chunks).toString('utf8');
    const json = (code, body) => {
      res.writeHead(code, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(body));
    };

    console.log(`${req.method} ${req.url}`);

    if (req.url.startsWith('/v1/models')) {
      return json(200, {
        object: 'list',
        data: [{ id: 'gpt-4o-mini', object: 'model', owned_by: 'mock' }],
      });
    }

    if (req.url.startsWith('/v1/chat/completions')) {
      let body = {};
      try { body = JSON.parse(raw || '{}'); } catch { /* fall through to defaults */ }

      const prompt = (body.messages || []).map((m) => m.content).join('\n');
      const promptTokens = countTokens(prompt);
      const completionTokens = countTokens(REPLY);

      console.log(`  model=${body.model} messages=${(body.messages || []).length} tools=${(body.tools || []).length} stream=${!!body.stream}`);

      const decided = decide(body);
      const isToolCall = !!decided.tool_calls;

      const completion = {
        id: 'chatcmpl-mock-' + Date.now(),
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: body.model || 'gpt-4o-mini',
        choices: [{
          index: 0,
          message: {
            role: 'assistant',
            content: isToolCall ? '' : decided.content,
            tool_calls: decided.tool_calls,
            refusal: null,
          },
          logprobs: null,
          finish_reason: isToolCall ? 'tool_calls' : 'stop',
        }],
        usage: {
          prompt_tokens: promptTokens,
          completion_tokens: completionTokens,
          total_tokens: promptTokens + completionTokens,
        },
        system_fingerprint: 'fp_mock',
      };

      if (!body.stream) return json(200, completion);

      // Streamed form, for when the node asks for it.
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      const base = { id: completion.id, object: 'chat.completion.chunk', created: completion.created, model: completion.model };
      res.write(`data: ${JSON.stringify({ ...base, choices: [{ index: 0, delta: { role: 'assistant', content: REPLY }, finish_reason: null }] })}\n\n`);
      res.write(`data: ${JSON.stringify({ ...base, choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: completion.usage })}\n\n`);
      res.write('data: [DONE]\n\n');
      return res.end();
    }

    json(404, { error: { message: `mock-openai has no route for ${req.url}`, type: 'invalid_request_error' } });
  });
});

server.listen(PORT, '0.0.0.0', () => console.log(`mock-openai listening on ${PORT}`));
