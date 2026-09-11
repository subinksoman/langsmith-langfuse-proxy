/**
 * Drives n8n's own bundled LangChain + LangSmith SDK at the proxy.
 *
 * This is the path an n8n AI node actually takes: the LangChainTracer batches
 * runs and POSTs them to LANGCHAIN_ENDPOINT, splitting each run into a start
 * event and a later end event exactly as it would against real LangSmith. The
 * hand-written fixtures in e2e-test.sh imitate that shape; this exercises the
 * client that produces it.
 *
 *   docker exec -w /usr/local/lib/node_modules/n8n n8n-langfuse-test \
 *     node /scripts/n8n-sdk-test.js
 */
const { FakeListChatModel }   = require('@langchain/core/utils/testing');
const { LangChainTracer }     = require('@langchain/core/tracers/tracer_langchain');
const { ChatPromptTemplate }  = require('@langchain/core/prompts');
const { Client }              = require('langsmith');

const ENDPOINT   = process.env.LANGCHAIN_ENDPOINT || 'http://langsmith-proxy:3001';
const SESSION_ID = process.env.TEST_SESSION_ID || `sdk-session-${Date.now()}`;

async function main() {
  const client = new Client({ apiUrl: ENDPOINT, apiKey: 'proxy-does-not-check-this' });
  const tracer = new LangChainTracer({ client, projectName: 'n8n' });

  const model = new FakeListChatModel({
    responses: ['Kochi is warm and humid, around 31C.'],
    model: 'llama-3.3-70b-versatile',
  });

  const prompt = ChatPromptTemplate.fromMessages([
    ['system', 'You are a weather bot.'],
    ['human', '{question}'],
  ]);

  const chain = prompt.pipe(model).withConfig({ runName: 'WeatherAgent' });

  const result = await chain.invoke(
    { question: 'what is the weather in Kochi' },
    {
      callbacks: [tracer],
      tags: ['n8n-sdk-test'],
      // The metadata an n8n workflow attaches: it is what the proxy routes on
      // and what fills the trace's session, user and project.
      metadata: { session_id: SESSION_ID, user_id: 'subin', workflow_name: 'SDK Weather Agent' },
    },
  );

  console.log('model replied:', JSON.stringify(result.content));

  // Flush the tracer's queue so every start and end event has been POSTed
  // before the process exits.
  await client.awaitPendingTraceBatches();
  console.log('SESSION_ID=' + SESSION_ID);
}

main().catch((e) => { console.error('FAILED:', e); process.exit(1); });
