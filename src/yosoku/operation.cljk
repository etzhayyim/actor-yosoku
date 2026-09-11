(ns yosoku.operation
  "ScenarioActor — one System-Dynamics model/scenario proposal = one
  supervised actor run, expressed as a langgraph-clj StateGraph. The advisor
  (SD-Advisor) is sealed into a single node (`:propose`); its proposal is
  ALWAYS routed through the ScenarioGovernor (`:govern`) before anything
  commits to the SSoT.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store   (MemStore | DatomicStore | kotoba-server) — `store` arg
    - the Advisor (mock | real LLM)                          — :advisor opt

  One graph run = one proposal (intake → propose → govern → decide → commit
  | hold | approval). No unbounded inner loop — each proposal is auditable
  and checkpointed.

  Human-in-the-loop = real sign-off workflow: `interrupt-before
  #{:request-approval}` pauses the actor and hands the decision to a human
  reviewer. The approver resumes with `{:approval {:status :approved}}`
  (or `:rejected`).

    intake → propose(SD-Advisor) → govern(ScenarioGovernor) → decide ─┬ commit ──▶ END
                                                                       ├ escalate ─▶ request-approval [interrupt-before]
                                                                       │              resume ─▶ commit | hold
                                                                       └ hold ─────▶ END

  HARD governor violations (invalid XMILE / unknown variable / protected
  variable / execution error) go straight to :hold — no override. SOFT
  violations (parameter swing beyond bound / implausible simulated output /
  low confidence / replacing an existing model) escalate to
  :request-approval, where a human may approve (commit) or reject (hold)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [yosoku.advisor :as advisor]
            [yosoku.governor :as governor]
            [yosoku.store :as store]))

(defn- commit-fact [request context proposal]
  {:t :committed
   :op (:op request)
   :actor (:actor-id context)
   :model-id (:model-id request)
   :disposition :commit
   :basis (:cites proposal)
   :summary (:summary proposal)})

(defn- commit-record [request proposal]
  {:effect (:effect proposal)
   :model-id (:model-id request)
   :model (:model proposal)
   :patch (:patch proposal)})

(defn build
  "Compiles a ScenarioActor graph bound to `store` (any `yosoku.store/Store`).
  opts:
    :advisor      — a `yosoku.advisor/Advisor` (default: mock-advisor)
    :checkpointer — langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor checkpointer]
             :or {advisor (advisor/mock-advisor)
                  checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request {:default nil}
         :context {:default nil}   ; injected actor-id / caller identity
         :proposal {:default nil}
         :verdict {:default nil}
         :disposition {:default nil}   ; :commit | :hold | :escalate
         :record {:default nil}
         :approval {:default nil}
         :audit {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; SD-Advisor inference (the contained intelligence node) — proposal only.
      (g/add-node :propose
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; ScenarioGovernor — independent censor (separate system than the advisor).
      (g/add-node :govern
        (fn [{:keys [request proposal]}]
          (let [base (store/model-of store (:model-id request))]
            {:verdict (governor/check request nil proposal base)})))

      ;; Decide: governor disposition only (no rollout-phase gate for v1 —
      ;; see README Follow-ups).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (case (governor/verdict->disposition verdict)
            :hold
            {:disposition :hold
             :audit [(governor/hold-fact request context verdict)]}

            :escalate
            {:disposition :escalate
             :audit [{:t :approval-requested
                      :op (:op request) :model-id (:model-id request)
                      :reason (mapv :rule (:violations verdict))
                      :confidence (:confidence verdict)}]}

            :commit
            {:disposition :commit
             :record (commit-record request proposal)})))

      ;; Approval handoff — paused by interrupt-before; a human reviewer
      ;; resumes with :approval. Then route commit/hold.
      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (commit-record request proposal)
             :audit [{:t :approval-granted :op (:op request)
                      :model-id (:model-id request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(assoc (governor/hold-fact
                             request context
                             (update verdict :violations conj {:rule :approver-rejected}))
                           :t :approval-rejected)]})))

      ;; Commit — the ONLY node that writes the SSoT + audit ledger.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            {:audit [f]})))

      ;; Hold — write the rejection to the ledger; no SSoT mutation.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :propose)
      (g/add-edge :propose :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer checkpointer
        :interrupt-before #{:request-approval}})))
