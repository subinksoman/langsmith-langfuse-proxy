# JOLT Transformation Specs Explanation

## Overview

JOLT (JSON to JSON Transformation Language) is used to transform LangSmith JSON format to Langfuse format.

**Important:** JOLT has limitations, so we use a hybrid approach:
- **JOLT**: Field mapping, restructuring, filtering
- **Java**: UUID generation, timestamp handling, orchestration, merging

---

## JOLT Operations Used

| Operation | Purpose |
|-----------|---------|
| `shift` | Move/copy fields from input to output |
| `default` | Add default values if missing |
| `remove` | Remove unwanted fields |
| `modify-overwrite-beta` | Transform field values |

---

## Spec 1: Filter LLM Runs (`spec-01-filter-llm-runs.json`)

**Purpose:** Extract only `run_type: "llm"` runs from the input.

**Input:**
```json
{
  "post": [
    { "id": "aaa", "run_type": "chain", ... },
    { "id": "bbb", "run_type": "llm", ... },
    { "id": "ccc", "run_type": "llm", ... }
  ],
  "patch": [...]
}
```

**JOLT Spec:**
```json
[
  {
    "operation": "shift",
    "spec": {
      "post": {
        "*": {                          // For each item in post array
          "run_type": {
            "llm": {                    // Only if run_type == "llm"
              "@2": "llm_runs[]"        // Move entire object to llm_runs array
            }
          }
        }
      },
      "patch": {
        "*": {
          "@": "patches[]"              // Copy all patches
        }
      }
    }
  }
]
```

**Output:**
```json
{
  "llm_runs": [
    { "id": "bbb", "run_type": "llm", ... },
    { "id": "ccc", "run_type": "llm", ... }
  ],
  "patches": [...]
}
```

**Explanation:**
- `"*"` - Wildcard, matches any array index
- `"@2"` - Reference to grandparent (2 levels up), which is the entire run object
- `"llm_runs[]"` - Output to array named llm_runs

---

## Spec 2: Extract LLM Fields (`spec-02-extract-llm-fields.json`)

**Purpose:** Flatten and extract relevant fields from an LLM run.

**Input (single LLM run):**
```json
{
  "id": "46d17fa7-...",
  "trace_id": "91c06284-...",
  "session_name": "default",
  "name": "ChatGroq",
  "extra": {
    "metadata": {
      "ls_provider": "groq",
      "ls_model_name": "llama-3.3-70b-versatile"
    }
  },
  "inputs": {
    "messages": [[{ "kwargs": { "content": "hello" }}]]
  },
  "outputs": {
    "generations": [[{ "text": "Hi there!" }]],
    "llmOutput": { "tokenUsage": { "promptTokens": 10 }}
  }
}
```

**JOLT Spec:**
```json
[
  {
    "operation": "shift",
    "spec": {
      "id": "run_id",
      "trace_id": "trace_id",
      "session_name": "session_id",
      "name": "name",
      "start_time": "start_time",
      "end_time": "end_time",
      "extra": {
        "metadata": {
          "ls_provider": "metadata.ls_provider",
          "ls_model_name": "metadata.ls_model_name",
          "ls_model_type": "metadata.ls_model_type"
        },
        "invocation_params": {
          "model": "model"
        }
      },
      "inputs": {
        "messages": {
          "*": {
            "*": {
              "kwargs": {
                "content": "input_messages[&3].content"
              }
            }
          }
        }
      },
      "outputs": {
        "generations": {
          "0": {
            "0": {
              "text": "output.content"
            }
          }
        },
        "llmOutput": {
          "tokenUsage": {
            "promptTokens": "usage.input",
            "completionTokens": "usage.output",
            "totalTokens": "usage.total"
          }
        }
      }
    }
  },
  {
    "operation": "default",
    "spec": {
      "output": {
        "role": "assistant"
      }
    }
  }
]
```

**Output:**
```json
{
  "run_id": "46d17fa7-...",
  "trace_id": "91c06284-...",
  "session_id": "default",
  "name": "ChatGroq",
  "model": "llama-3.3-70b-versatile",
  "metadata": {
    "ls_provider": "groq",
    "ls_model_name": "llama-3.3-70b-versatile"
  },
  "input_messages": [{ "content": "hello" }],
  "output": { "content": "Hi there!", "role": "assistant" },
  "usage": { "input": 10, "output": 5, "total": 15 }
}
```

---

## Spec 3: Trace Create (`spec-03-trace-create.json`)

**Purpose:** Build Langfuse trace-create event structure.

**JOLT Spec:**
```json
[
  {
    "operation": "shift",
    "spec": {
      "trace_id": "body.id",
      "session_id": "body.sessionId",
      "name": "body.name",
      "start_time": ["body.timestamp", "timestamp"],
      "metadata": "body.metadata",
      "input_messages": "body.input"
    }
  },
  {
    "operation": "default",
    "spec": {
      "type": "trace-create"
    }
  }
]
```

**Output:**
```json
{
  "type": "trace-create",
  "timestamp": "2025-12-11T...",
  "body": {
    "id": "91c06284-...",
    "sessionId": "default",
    "name": "ChatGroq",
    "timestamp": "2025-12-11T...",
    "metadata": {...},
    "input": [...]
  }
}
```

---

## Spec 4: Generation Create (`spec-04-generation-create.json`)

**Purpose:** Build Langfuse generation-create event structure.

**JOLT Spec:**
```json
[
  {
    "operation": "shift",
    "spec": {
      "run_id": "body.id",
      "trace_id": "body.traceId",
      "name": "body.name",
      "start_time": ["body.startTime", "timestamp"],
      "metadata": "body.metadata",
      "input_messages": "body.input",
      "model": "body.model"
    }
  },
  {
    "operation": "default",
    "spec": {
      "type": "generation-create",
      "body": {
        "modelParameters": {}
      }
    }
  }
]
```

---

## Spec 5: Generation Update (`spec-05-generation-update.json`)

**Purpose:** Build Langfuse generation-update event structure.

**JOLT Spec:**
```json
[
  {
    "operation": "shift",
    "spec": {
      "run_id": "body.id",
      "trace_id": "body.traceId",
      "model": "body.model",
      "end_time": ["body.endTime", "timestamp"],
      "output": "body.output",
      "usage": ["body.usage", "body.usageDetails"]
    }
  },
  {
    "operation": "default",
    "spec": {
      "type": "generation-update"
    }
  }
]
```

---

## Spec 6: Trace Create Final (`spec-06-trace-create-final.json`)

**Purpose:** Build final Langfuse trace-create event with output.

**JOLT Spec:**
```json
[
  {
    "operation": "shift",
    "spec": {
      "trace_id": "body.id",
      "end_time": ["body.timestamp", "timestamp"],
      "output": "body.output"
    }
  },
  {
    "operation": "default",
    "spec": {
      "type": "trace-create"
    }
  }
]
```

---

## JOLT Limitations (Why We Need Java)

| Feature | JOLT Can Do? | Solution |
|---------|--------------|----------|
| Field mapping | ✅ Yes | JOLT shift |
| Filtering by value | ⚠️ Limited | JOLT + Java |
| Generate UUIDs | ❌ No | Java UUID.randomUUID() |
| Get current timestamp | ❌ No | Java Instant.now() |
| Merge two objects by ID | ❌ No | Java HashMap |
| Conditional logic | ⚠️ Limited | Java if/else |
| Loop with counter | ❌ No | Java for loop |

---

## Complete Transformation Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                     Java Orchestration                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Parse incoming JSON                                         │
│                                                                 │
│  2. Apply JOLT Spec 1: Filter LLM runs                          │
│     └─► Get only run_type == "llm"                              │
│                                                                 │
│  3. Java: Merge patches into runs by ID                         │
│                                                                 │
│  4. For EACH LLM run:                                           │
│     │                                                           │
│     ├─► Apply JOLT Spec 2: Extract fields                       │
│     │                                                           │
│     ├─► Java: Generate UUID for event                           │
│     │                                                           │
│     ├─► If has input:                                           │
│     │   ├─► Apply JOLT Spec 3: trace-create                     │
│     │   └─► Apply JOLT Spec 4: generation-create                │
│     │                                                           │
│     └─► If has output:                                          │
│         ├─► Apply JOLT Spec 5: generation-update                │
│         └─► Apply JOLT Spec 6: trace-create-final               │
│                                                                 │
│  5. Java: Combine all events into batch array                   │
│                                                                 │
│  6. Java: Add metadata                                          │
│                                                                 │
│  7. Send to Langfuse                                            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Testing JOLT Specs

You can test JOLT specs online at: https://jolt-demo.appspot.com/

1. Paste the spec in "Spec" field
2. Paste sample input in "Input JSON" field
3. Click "Transform"
4. See output in "Output" field

---

## Files Summary

| File | Purpose |
|------|---------|
| `sample-input.json` | Example LangSmith input for testing |
| `expected-output.json` | Expected Langfuse output |
| `spec-01-filter-llm-runs.json` | Filter only LLM type runs |
| `spec-02-extract-llm-fields.json` | Extract and flatten fields |
| `spec-03-trace-create.json` | Build trace-create event |
| `spec-04-generation-create.json` | Build generation-create event |
| `spec-05-generation-update.json` | Build generation-update event |
| `spec-06-trace-create-final.json` | Build final trace-create with output |
