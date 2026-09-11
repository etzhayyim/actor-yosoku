(ns yosoku.advisor
  "SD-Advisor — the *contained intelligence node*. Drafts either a brand-new
  System-Dynamics model (`:model/propose`), a caller-specified scenario
  patch (`:scenario/propose`), or — real-LLM only — an open-ended scenario
  recommendation from a natural-language `:intent` (`:scenario/advise`),
  using `kotoba-lang/org-oasis-open-xmile`'s EDN model shape. CRITICAL: like
  gftd-talent-actor's HR-LLM, it is a smart-but-untrusted advisor — it
  returns a *proposal*, never a committed model. Every output is censored
  downstream by `yosoku.governor` before anything touches the SSoT.

  `mock-advisor` is a deterministic mock so the actor graph runs offline end
  to end and the governor contract is exercised without any network/API
  dependency — the default everywhere, including for `:scenario/advise`
  (which it cannot meaningfully reason about, so it safely no-ops).
  `llm-advisor` (bottom of this file) wires a real `langchain.model/
  ChatModel` against the Murakumo fleet (DEFAULT-PREFERRED per Rider v3.3
  §2(i), same allowlist as `tashikame.advisor`/`kouhou.advisor`) for
  `:scenario/advise`; `:model/propose`/`:scenario/propose` still route
  through the deterministic `infer` regardless of which advisor is
  injected — an LLM drafting a WHOLE new XMILE model from scratch is out of
  scope (per `yosoku.models`' own docstring, a real deployment seeds new
  models from an operator's authoring tool, not an LLM), and a caller-
  specified `:change` needs no open-ended reasoning to relay.

  Proposal shape:
    {:summary    str
     :rationale  str
     :cites      [str ..]                        ; variables/fields referenced
     :effect     :register-model | :apply-scenario | :noop
     :model-id   str
     :model      xmile-model-map                 ; only for :register-model
     :patch      {:variables {..} :sim-specs {..}} ; only for :apply-scenario
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [langchain.model :as model]
            [xmile.model :as m]
            [yosoku.models :as models]
            [yosoku.store :as store]))

(defn- propose-model
  "Draft a brand-new model. `:broken?` deliberately injects a structurally
  INVALID model (dangling reference); `:implausible?` deliberately injects a
  structurally valid but already-absurd model. Both are the yosoku analog of
  talent.hrllm's `:bias?` deliberate-failure injection — real failure modes
  the ScenarioGovernor must have something concrete to defend against."
  [{:keys [model-id broken? implausible?]}]
  (let [model (cond broken? (models/broken-dangling-ref)
                    implausible? (models/implausible-seed)
                    :else (models/community-growth))]
    {:summary (str model-id " forecast model draft")
     :rationale (cond
                  broken? "モデル雛形（意図的に壊れた参照を含む — 構造検証テスト用）"
                  implausible? "モデル雛形（意図的に非現実的な初期値を含む — 妥当性検証テスト用）"
                  :else "コミュニティ成長の標準テンプレート（招待/離脱/成長率）")
     :cites ["yosoku.models"]
     :effect :register-model
     :model-id model-id
     :model model
     :confidence (if (or broken? implausible?) 0.9 0.85)}))

(defn- propose-scenario
  "Draft a scenario intervention: a patch to one variable's equation on an
  existing registered model. `change` is `{:var name :eqn eqn-string}`.
  Confidence is a simple, ADVISORY heuristic only (bigger requested swings
  read as less certain) — it is never what makes the swing safe; the
  ScenarioGovernor independently recomputes the actual bound violation from
  the base model and the patch, never from this self-report."
  [st {:keys [model-id change]}]
  (let [{:keys [var eqn]} change
        base (store/model-of st model-id)
        old-eqn (some-> (m/lookup base var) :xmile/eqn)]
    {:summary (str model-id "." var " を " (or old-eqn "?") " → " eqn " に変更提案")
     :rationale "シナリオ介入の提案（既存モデルへの1変数パッチ）。妥当性はシミュレーション結果で判断すること。"
     :cites [var]
     :effect :apply-scenario
     :model-id model-id
     :patch {:variables {var {:xmile/eqn eqn}}}
     :confidence 0.8}))

(defn- cannot-advise
  "mock-advisor's answer to `:scenario/advise`: it has no free-reasoning
  capability, so it safely no-ops rather than guessing — governor.check's
  structural-violations on a nil candidate (`:effect :noop` never builds
  one) forces a HARD hold, so this can never silently auto-commit."
  [{:keys [model-id]}]
  {:summary "mock advisor cannot reason about open-ended intent"
   :rationale "inject a real llm-advisor for :scenario/advise, or use :scenario/propose with an explicit :change"
   :cites [] :effect :noop :model-id model-id :confidence 0.0})

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :model-id id ...op-specific...}"
  [st {:keys [op] :as request}]
  (case op
    :model/propose (propose-model request)
    :scenario/propose (propose-scenario st request)
    :scenario/advise (cannot-advise request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :model-id (:model-id request) :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────
;; The advisor is injected into the ScenarioActor, so the contained
;; intelligence node is a swap: a deterministic mock for dev/tests, or a real
;; LLM in production. Either way its output is a PROPOSAL the ScenarioGovernor
;; still censors — the single invariant never depends on which advisor ran.

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(defn trace
  "Decision-grounded audit record — the advisor's interpretable rationale is
  a key asset (scenario review, audits). Persisted to the `:audit` channel."
  [request proposal]
  {:t :advisor-proposal
   :op (:op request)
   :model-id (:model-id request)
   :summary (:summary proposal)
   :rationale (:rationale proposal)
   :cites (:cites proposal)
   :confidence (:confidence proposal)})

;; ───────────────────────── real-LLM advisor (Murakumo fleet) ─────────────────────────
;; Sealed just like the mock: :scenario/advise returns a PROPOSAL only — the
;; ScenarioGovernor still independently re-validates/re-simulates every
;; candidate regardless of what the LLM says (governor.cljc never trusts
;; this advisor's self-reported :confidence). The LLM may only ever tune ONE
;; already-existing numeric aux/parameter to a new numeric value — it can
;; neither invent a variable (that candidate would fail governor's
;; :unknown-variable check) nor author a formula (rejected below before the
;; proposal is even built) nor draft a whole new model (`:model/propose`
;; stays on the deterministic `infer` path, see namespace docstring).

(def allowed-infer-hosts
  "Murakumo-fleet inference hosts only (Rider v3.3 §2(i)). No opaque/lock-in
  commercial GPU by default. The com-junkawasaki tailnet entries (added
  2026-07-13) are the same physical fleet nodes as the localhost/LAN
  entries above, reachable via Tailscale instead — confirmed live and
  cross-checked against the fleet's own registry
  (https://api.murakumo.cloud/nodes)."
  #{"127.0.0.1:11434" "localhost:11434"
    "127.0.0.1:4000"  "localhost:4000"
    "192.168.1.70:4000"
    ;; com-junkawasaki tailnet Murakumo-fleet Ollama nodes (verified live
    ;; 2026-07-13 against https://api.murakumo.cloud/nodes)
    "100.98.142.59:11434"   ; dan
    "100.66.28.79:11434"    ; zebulun
    "100.102.78.81:11434"   ; levi
    "100.75.169.8:11434"    ; benjamin
    "100.89.204.30:11434"   ; issachar
    "100.82.123.35:11434"   ; joseph
    "100.101.27.85:11434"   ; naphtali
    "100.81.66.86:11434"})  ; simeon

(defn- host-port [url]
  (when (string? url) (second (re-find #"(?i)^[a-z]+://([^/]+)" url))))

(defn assert-murakumo!
  "Throw if `ollama-url` is not a Murakumo-fleet inference host."
  [ollama-url]
  (let [hp (host-port ollama-url)]
    (when-not (contains? allowed-infer-hosts hp)
      (throw (ex-info (str "inference host " hp " is not Murakumo-fleet (Rider v3.3 §2(i))")
                      {:host hp})))))

(def sd-advisor-system-prompt
  "You are the SD-Advisor for yosoku, a System-Dynamics scenario advisor.
Given a base XMILE model's current aux (parameter/gate) variables and a
natural-language intent, propose changing ONE existing numeric aux variable
to a new numeric value that serves the intent. You may NEVER propose a
variable name that is not in the list you were given, and NEVER propose a
formula — only a bare numeric literal. Respond with ONLY a single-line EDN
map, no prose, no code fences:
  {:var \"ExistingVarName\" :eqn \"0.42\" :rationale \"one short sentence\" :confidence <0.0-1.0>}
If no safe or relevant change exists, respond {:var nil}.")

(defn- aux-table
  "{name -> current eqn string} for every :aux variable in `base-model` — the
  only vocabulary the LLM is shown/allowed to reference."
  [base-model]
  (into {} (for [v (m/auxs base-model)] [(:xmile/name v) (:xmile/eqn v)])))

(defn- build-scenario-prompt [base-model intent]
  (str "Model: " (:xmile/name base-model) "\n"
       "Current aux/gate variables (name -> current value): " (pr-str (aux-table base-model)) "\n\n"
       "Intent: " intent "\n\n"
       "Return ONLY the EDN map now."))

(defn- numeric-literal? [s]
  (and (string? s) (re-matches #"[-+]?\d+(\.\d+)?" (str/trim s))))

(defn parse-scenario-advice-edn
  "Defensively parse the LLM's `{:var :eqn :rationale :confidence}` EDN map.
  Returns nil (never a partial/malformed map) unless `:var` is one of
  `known-vars` AND `:eqn` is a bare numeric literal — a hallucinated
  variable name or a formula is rejected HERE, before a proposal is even
  built, so an unparseable/out-of-vocabulary LLM turn can only ever route
  to `cannot-advise`'s safe :noop, never to a malformed `:apply-scenario`."
  [content known-vars]
  (let [cleaned (-> (str content)
                     (str/replace #"(?s)```[a-zA-Z]*" "")
                     (str/replace "```" ""))
        m (try (some-> (re-find #"(?s)\{.*\}" cleaned) edn/read-string)
               (catch #?(:clj Throwable :cljs :default) _ nil))
        var-name (:var m)
        eqn (:eqn m)]
    (when (and (string? var-name) (contains? known-vars var-name) (numeric-literal? eqn))
      {:var var-name
       :eqn (str/trim eqn)
       :rationale (str (or (:rationale m) ""))
       :confidence (let [c (:confidence m)]
                     (if (number? c) (max 0.0 (min 1.0 (double c))) 0.5))})))

(defn llm-advisor
  "Advisor backed by a `langchain.model/ChatModel` (OpenAI-compatible Ollama
  against the Murakumo fleet, or Anthropic). Handles `:scenario/advise`
  itself (the only op an LLM meaningfully adds reasoning to); every other op
  falls back to the deterministic `infer` — same fallback shape as
  `mock-advisor`, so swapping advisors never changes `:model/propose`/
  `:scenario/propose` behavior. gen-opts -> `model/-generate` opts (e.g.
  `{:max-tokens 512}`)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st {:keys [op model-id intent] :as request}]
       (if (not= op :scenario/advise)
         (infer st request)
         (let [base (store/model-of st model-id)]
           (if-not base
             {:summary "no such model" :rationale (str "model-id " model-id " not registered")
              :cites [] :effect :noop :model-id model-id :confidence 0.0}
             (let [content (:content
                            (model/-generate chat-model
                              [{:role :system :content sd-advisor-system-prompt}
                               {:role :user :content (build-scenario-prompt base intent)}]
                              gen-opts))
                   advice (parse-scenario-advice-edn content (m/variable-names base))]
               (if advice
                 {:summary (str model-id "." (:var advice) " -> " (:eqn advice))
                  :rationale (:rationale advice)
                  :cites [(:var advice)]
                  :effect :apply-scenario
                  :model-id model-id
                  :patch {:variables {(:var advice) {:xmile/eqn (:eqn advice)}}}
                  :confidence (:confidence advice)}
                 {:summary "LLM proposed no safe change"
                  :rationale "unparseable output, out-of-vocabulary variable, or non-numeric eqn"
                  :cites [] :effect :noop :model-id model-id :confidence 0.0})))))))))
