# Running the agentic patterns durably on Diagrid Catalyst

The five patterns in this directory are the upstream Spring AI implementations of
[Anthropic's workflow patterns](https://www.anthropic.com/research/building-effective-agents), adapted
so that every LLM call runs as a **Dapr Workflow** on [Diagrid Catalyst](https://docs.diagrid.io).
The pattern logic itself is unchanged Spring AI — no orchestration framework, no rewrite.

## Why these patterns want durability

Each pattern is a *multi-call* workflow, and that is exactly where an in-process agent loses work.
A chain that dies on step 3 of 4 has already paid for steps 1 and 2; a fan-out that dies with two of
four inputs done has paid for two; a refinement loop that dies in round 3 has paid for two full
generate-and-critique rounds. Plain Spring AI holds all of that in local variables on one JVM's heap,
so a crash, a redeploy, or an OOM discards it and the next attempt starts from zero.

On Catalyst each call's model turns and tool calls become checkpointed workflow activities, and each
step is scheduled under an instance id derived from a **run id**. Re-running with the same run id
attaches to the recorded executions instead of starting new ones, so completed steps return their
recorded answers and only the unfinished work reaches the model.

## What changed from upstream

| Change | Why |
|---|---|
| `spring-ai-starter-model-anthropic` → `spring-ai-starter-model-openai` | these samples run on OpenAI; the commented-out Anthropic/Ollama alternatives are gone |
| added `io.diagrid:diagrid-spring-ai-starter` | **the only thing that makes calls durable** — no application-code change is needed for durability itself |
| Spring Boot `4.0.0` → `4.0.5`, Java 17 → 21 | matches the verified Catalyst stack; Java 21 so a blocking durable call parks a *virtual* thread |
| `netty-bom` pinned first in `dependencyManagement` | aligns Netty between Dapr's grpc-netty and Spring Boot 4; the mixed state fails at runtime |
| each role exposed as a `ChatClient` **`@Bean`** | a bean name becomes the agent's name in Catalyst's agent registry and its workflow name; a `ChatClient` built inline is durable but anonymous |
| per-step **instance ids** from a run id | what turns "durable calls" into "a resumable pattern" — see below |
| `spring.application.name` set to the module name | it is the Dapr app-id; the registry records it, and tooling correlates agent → app → workflows by it |
| `evaluator-optimizer`: added a `maxIterations` cap (default 5) | the upstream refine loop is unbounded; on the durable path every round schedules two workflows |

Everything else — prompts, control flow, structured-output records — is untouched.

### One caveat worth knowing

Durability is applied by an advisor that the starter attaches to the **auto-configured
`ChatClient.Builder` bean**. Building from that injected builder (what these samples do) keeps it;
`ChatClient.builder(chatModel)` creates a fresh unmanaged builder and is **silently not durable**.
When a component needs several clients, `clone()` the injected builder per role, as
`routing-workflow`, `orchestrator-workers`, and `evaluator-optimizer` do.

## Setup

Requirements: JDK 21, Maven, the [Diagrid CLI](https://docs.diagrid.io/catalyst/references/cli-reference/),
and an `OPENAI_API_KEY`.

```bash
# 1. A Catalyst project with managed workflows. Nothing runs next to your app.
diagrid project create agentic-patterns --enable-managed-workflow --use

# 2. Your model key.
export OPENAI_API_KEY=sk-...
```

`diagrid dev run` (below) provisions the app id named in the run file on first use, so there is no
separate create step. To create it up front instead — these are command-line apps with no HTTP
server, so they need no endpoint; the workflow worker dials *out* to Catalyst:

```bash
diagrid agent create chain-workflow --ignore-if-exists --wait
```

Check what a run file would touch, without changing anything:

```bash
diagrid dev run --file dev-chain-workflow.yaml --dry-run
```

## Run a pattern

```bash
cd chain-workflow
diagrid dev run --file dev-chain-workflow.yaml --approve
```

Each app prints its run id on startup, along with the flag to resume it.

## Watch it resume

This is the point of the exercise. Start a pattern, kill it part-way, then restart it with the run
id it printed:

```bash
cd chain-workflow
diagrid dev run --file dev-chain-workflow.yaml --approve
# ... note the "Run id:" line, let step 1 and 2 finish, then Ctrl-C

diagrid dev run --file dev-chain-workflow.yaml --approve -- \
  mvn spring-boot:run -Dspring-boot.run.arguments=--chain.run-id=<the run id>
```

The completed steps come back immediately from their recorded results — no model call, no token
spend — and the chain picks up where it stopped. Without the run id you get a fresh execution and
the whole chain runs again.

You can also watch the instances in the Catalyst dashboard, or with
`diagrid workflow get <instance-id>`.

## Per-pattern reference

| Module | Agents (`ChatClient` beans) | Resume flag | Instance ids |
|---|---|---|---|
| `chain-workflow` | `chainAgent` | `--chain.run-id` | `<runId>-step-<1..4>` |
| `parallelization-workflow` | `stakeholderAnalyst` | `--parallel.run-id` | `<runId>-input-<i>` |
| `routing-workflow` | `ticketClassifier`, `supportSpecialist` | `--routing.run-id` | `<runId>-ticket-<n>-classify`, `…-handle` |
| `orchestrator-workers` | `taskOrchestrator`, `contentWorker` | `--orchestrator.run-id` | `<runId>-orchestrate`, `<runId>-worker-<i>` |
| `evaluator-optimizer` | `solutionGenerator`, `solutionEvaluator` | `--evaluator.run-id` | `<runId>-generate-<n>`, `<runId>-evaluate-<n>` |

Each agent runs under its own workflow name, `spring-ai.<beanName>.workflow`, which is how the
dashboard groups an agent's runs.

### Why the ids stay stable across a resume

Every id is derived from the run id plus a position, and every position is decided by *replayed*
output. In `orchestrator-workers`, the worker ids depend on the task list the orchestrator produced —
on a resume that decomposition replays from its record, so the same list comes back and the worker
ids line up. Same for `routing-workflow` (the replayed classification picks the same route) and
`evaluator-optimizer` (replayed solutions and feedback rebuild an identical context for the next
round). Nothing depends on wall-clock time or randomness.

## Limits

- **Only `.call()` is durable**, not `.stream()`.
- **A repeated call with a spent id does not re-run.** A completed id returns its recorded answer; a
  *failed* one surfaces the recorded failure. To genuinely retry a failed step, purge the id first
  (`diagrid workflow purge <instance-id>`) or use a new run id.
- **A caller-supplied instance id is a bearer handle** — anyone presenting it attaches to that run
  and reads its result. Fine for these local samples; guard it like a primary key in a real app.
- **`parallelization-workflow` still uses a fixed platform-thread pool** to fan out, as upstream
  does. Each of those threads blocks on a durable call, which virtual threads make cheap but do not
  make unbounded.

## Further reading

- [diagrid-spring-ai](https://github.com/diagridio/java-ai) — the durability library, its full
  configuration surface, and the tool crash-safety rules
- [Catalyst Spring AI docs](https://docs.diagrid.io/develop/agents/spring-ai/)
- [Spring AI quickstart on Catalyst](https://docs.diagrid.io/getting-started/quickstarts/ai-agents/?agentframework=spring-ai)
