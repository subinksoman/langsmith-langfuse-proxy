Update the n8n workflow message-tracking and correlation design so that a single user message is represented by one common msg_id/traceid across the entire workflow, even when the message passes through multiple agents, sub-agents, tools, or workflow nodes.

Requirements
Session ID
sessionId represents the complete conversation/session.
The same sessionId must be maintained across all messages and all workflow executions belonging to that session.
Multiple user messages can belong to the same sessionId.
Message ID/traceid
Generate msg_id only once when a new user message enters the workflow.
This msg_id represents the logical user message/request.
The same msg_id must be propagated to every downstream agent, sub-agent, tool, and n8n node involved in processing that request.
Do not generate a new msg_id for individual agents.
Agent Execution ID
Introduce a separate execution_id / node_execution_id for identifying an individual agent or node execution.
Each agent execution can have a different execution_id.
This ID is only for internal tracing, debugging, retries, and execution-level logging.
Correlation hierarchy
sessionId
    |
    +-- msg_id/trace id
          |
          +-- Agent 1 --> execution_id
          |
          +-- Agent 2 --> execution_id
          |
          +-- Agent 3 --> execution_id
          |
          +-- Agent 4 --> execution_id
          |
          +-- Final Response