# actor-yosoku

Canonical repository: `etzhayyim/actor-yosoku`. This governed scenario
simulation organ belongs to the Tamaki-centered Etzhayyim artificial
organism. It may propose and simulate bounded scenarios, while the independent
ScenarioGovernor and human escalation boundary retain commit authority. The
former `com-etzhayyim-yosoku` name remains a compatibility redirect.

予測 (yosoku — "forecast/prediction") — a **governed System-Dynamics scenario
simulation actor**. Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj) StateGraph
runtime (portable `.cljc`, supervised superstep loop, interrupts, Datomic/
in-mem checkpoints) — the same actor pattern as `gftd-talent-actor`
(HR-LLM ⊣ PolicyGovernor) and `cloud-itonami` (ops-LLM ⊣ CertGovernor).

> **Why an actor layer at all?** An LLM is fluent at drafting a System
> Dynamics model or proposing "what if we doubled the growth rate" — but it
> has **no notion of what is structurally valid XMILE, what a plausible
> simulated future looks like, or which variables are policy-fixed and must
> never move**. Letting it commit a model or a scenario patch directly
> invites silently-invalid models, runaway forecasts presented as fact, and
> quiet edits to compliance-floor constants. This project seals the SD-LLM
> into a single node and wraps it with an independent **ScenarioGovernor**
> built on a REAL simulation engine — [`kotoba-lang/org-oasis-open-xmile`](https://github.com/kotoba-lang/org-oasis-open-xmile)
> (OASIS XMILE 1.0 stock-and-flow modeling + a pure fixed-step Euler/RK4
> simulator) — plus a human sign-off workflow and an immutable audit ledger.
> A forecast actor's whole value proposition collapses if its "simulation"
> is just LLM narrative; the governor's job is to make sure it never is.

## The single invariant

> **The SD-Advisor never commits a model or scenario intervention the
> ScenarioGovernor would reject.**

Hard violations (invalid XMILE / unknown variable / a protected variable
touched / an execution error) fall back to **hold** and *cannot* be
overridden by a human. Soft violations (a parameter swing beyond a
configured bound, a simulated future that crosses an implausibility
threshold, low advisor confidence, or replacing an already-registered model)
go to a human sign-off workflow — the human may approve or reject, but can
never approve past a hard violation.

## The core contract

```
request
   │
   ▼
┌───────────┐    proposal     ┌───────────────────┐
│ SD-Advisor│ ──────────────▶ │ ScenarioGovernor  │  (independent system,
│ (sealed)  │ model/patch +   │ xmile.validate +  │   built on the REAL
└───────────┘ rationale       │ xmile.execute +   │   xmile.execute engine)
                              │ policy table      │
                              └─────────┬─────────┘
                        commit ◀────────┼────────▶ hold (構造/保護変数違反;上書き不可)
                            │                │
                       SSoT + 台帳     escalate ─▶ 人間承認 (interrupt-before)
```

## StateGraph

```
intake → propose(SD-Advisor) → govern(ScenarioGovernor) → decide ─┬ commit ──▶ END
                                                                   ├ escalate ─▶ request-approval [interrupt-before]
                                                                   │              resume ─▶ commit | hold
                                                                   └ hold ─────▶ END
```

One graph run = one proposal (`:model/propose` a brand-new model, or
`:scenario/propose` a patch to an existing one). No unbounded inner loop —
every proposal is auditable and checkpointed.

## ScenarioGovernor policy rules

| # | Rule | Severity | What it catches |
|---|---|---|---|
| 1 | `:structural-invalid` / `:not-simulatable` | **HARD** | `xmile.validate/validate` reports an `:error` (dangling reference, illegal algebraic loop, malformed `sim_specs`/`gf`), or a `:warn` that would make `xmile.execute/run` throw (conveyor/queue transport, an unsupported integration method) |
| 2 | `:unknown-variable` | **HARD** | a `:scenario/propose` patch names a variable that doesn't exist on the base model (introducing a variable is what `:model/propose` is for) |
| 3 | `:protected-variable` | **HARD** | a patch touches a configured compliance-floor variable name (`ComplianceFloor` / `RegulatoryCap` by default) — never overridable, no matter who asks |
| 4 | `:parameter-bound` | soft (escalate) | a bare-numeric equation change swings more than 100% relative to its current value |
| 5 | `:model-replace` | soft (escalate) | `:model/propose` targets an ALREADY-registered model-id (treated as a full replace, always requires sign-off) |
| 6 | `:implausible-output` | soft (escalate) | **actually running** the candidate model through `xmile.execute/run` produces a value whose magnitude exceeds `1.0e6`, or a non-finite value |
| 7 | `:low-confidence` | soft (escalate) | advisor confidence below `0.6` |
| — | `:execution-error` | **HARD** (fail-safe) | the real simulator threw despite passing structural validation (should not happen; defensive) |

Every rule is derived from the **proposal's own content** (the model/patch,
re-validated and re-simulated) and the base model — never from the
advisor's self-reported `:confidence`/`:stake`. An advisor that
mis-represents its own risk cannot buy its way past the governor.

## Run

```bash
clojure -M:dev:run     # drive 5 representative proposals through one ScenarioActor
clojure -M:dev:test    # scenario contract · store parity · advisor · governor · ledger
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

> CI is provided by the murakumo fleet. The test suite expects the west
> workspace layout so the local `langgraph` dependency resolves.

Demo output walks five proposals: a brand-new model (**commit**) → a
model with a dangling reference (**structural reject → hold**) → an
in-bound growth-rate tweak (**commit**) → an edit to the protected
`ComplianceFloor` variable (**protected-variable reject → hold**) → a huge
growth-rate swing that is both out-of-bound AND simulates to an implausible
population (**escalate → human approves → commit**), then prints the
immutable audit ledger and proves the same contract holds against
`DatomicStore`.

## Layout

| File | Actor / role |
|---|---|
| `src/yosoku/models.cljc` | canned XMILE models (`community-growth` demo model + deliberately-broken/implausible fixtures) built on `xmile.model` |
| `src/yosoku/scenario.cljc` | pure patch-application helpers (`apply-patch`, `numeric-eqn`) shared by the governor (candidate construction) and the store (committing an approved patch) |
| `src/yosoku/advisor.cljc` | **SD-Advisor** — `mock-advisor` (the only advisor shipped in v1; see Follow-ups) |
| `src/yosoku/governor.cljc` | **ScenarioGovernor** — structural validity (`xmile.validate`) · unknown/protected variables · parameter bound · implausible output (`xmile.execute`) · confidence floor |
| `src/yosoku/operation.cljc` | **ScenarioActor** — langgraph-clj StateGraph (1 run = 1 proposal); Store/Advisor injected |
| `src/yosoku/store.cljc` | **Store** protocol — `MemStore` (default) ‖ `DatomicStore` (`langchain.db`, swappable to Datomic Local / kotoba-server) + append-only ledger |
| `src/yosoku/sim.cljc` | demo driver |
| `test/yosoku/*_test.clj` | scenario patch helpers · governor policy rules (isolated) · advisor proposal shape · ScenarioActor contract (reject/hold/escalate/commit + ledger append-only) · store parity (Mem≡Datomic) — **39 tests / 129 assertions** |

## Backend / advisor swap (all injection)

The two things the actor depends on are both a *swap*, not a rewrite — the
core (ScenarioActor / ScenarioGovernor / audit ledger) never changes:

```clojure
;; SSoT: in-mem → Datomic (langchain.db; further swappable to a real
;;       Datomic Local / kotoba-server pod via :db-api)
(def store (yosoku.store/datomic-seed-db))

;; Advisor: mock → real LLM is a Follow-up (not wired in v1 — see below)
(def actor (yosoku.operation/build store {:advisor (yosoku.advisor/mock-advisor)}))
```

A proposal from a broken/hallucinating advisor can never auto-commit: the
governor re-derives every violation from the model/patch itself, so a
confident-but-wrong advisor doesn't bypass it.

## Status

Design + implementation complete for v1 scope: `MemStore` ‖ `DatomicStore`
contract-tested for parity, mock `Advisor`, full ScenarioGovernor policy
table, StateGraph with `interrupt-before` human sign-off. **0 lint errors /
39 tests / 129 assertions / 0 failures.**

## Follow-ups

- **Real LLM Advisor wiring** — only `mock-advisor` is implemented. A real
  advisor implements the `yosoku.advisor/Advisor` protocol backed by a real
  `langchain.model` `ChatModel` (the same seam `gftd-talent-actor`'s
  `talent.hrllm/llm-advisor` uses), matching `kotoba-lang/kessai`'s
  mock-adapter-only precedent: "only the mock adapter is [implemented]; a
  real adapter implements the same protocol."
- **RBAC / multi-operator permission table** — every request in v1 runs
  under a single trusted operator `:context` (used for ledger attribution
  only, not gating). `gftd-talent-actor`'s `talent.policy` RBAC table (role ×
  operation × subject) is a natural template if this actor grows multiple
  callers with different authority.
- **Versioned scenario history** — a committed `:scenario/propose` patch
  mutates the SSoT's "current" model in place (the append-only ledger is
  the audit trail of what changed and why, not a versioned entity graph).
  Keeping every historical candidate model as its own addressable version
  (not just the ledger's patch/rationale) is a natural extension once a real
  operator wants to diff/replay scenarios, not just audit them.
- **XMILE v2 surface** — `org-oasis-open-xmile` itself does not yet simulate
  conveyor/queue stocks, arrays, stochastic/delay/smooth/trend built-ins, or
  `rk2`/`rk45`/`gear` integration (see its own README Follow-ups). The
  ScenarioGovernor's `:not-simulatable` rule already treats any model
  exercising these as HARD (cannot commit), so this actor inherits the
  underlying library's v2 roadmap rather than working around it.
- **Rollout phase gate** — `gftd-talent-actor` layers a Phase 0→3 staged
  rollout (`talent.phase`) on top of its PolicyGovernor. v1 of this actor
  has no equivalent; the task's explicit scope was the governor's
  structural/policy checks, not a staged-autonomy gate. Adding one is a
  reasonable next step if this actor moves toward less-supervised operation.
- **RAD identity / manifest registration** — this repo does not itself carry
  a `manifest.jsonld`/DID or a registration into `etzhayyim/root`'s RAD
  identity ledger (`80-data/kotoba-rad/*.identity.journal.edn`); several
  sibling `etzhayyim/com-etzhayyim-*` actors carry that (they publish to
  app-aozora and need a self-`did:key`). This actor has no publication
  surface — it commits to its own Store, it does not post — so that step is
  deferred to, and owned by, the orchestrating session once this actor's
  code is reviewed, per this build's scope.
