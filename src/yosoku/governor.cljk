(ns yosoku.governor
  "ScenarioGovernor — the independent censor that earns the SD-LLM (SD-Advisor)
  the right to commit a System-Dynamics model or scenario intervention. The
  advisor (`yosoku.advisor`) has no notion of what is structurally valid
  XMILE, what is safe to auto-commit, or what a plausible simulated future
  looks like — so this MUST be a separate system, built directly on
  `kotoba-lang/org-oasis-open-xmile`'s own `xmile.validate`/`xmile.execute`
  (a REAL simulation engine, not LLM narrative) plus a small policy table.
  This is the yosoku analog of gftd-talent-actor's PolicyGovernor / the
  robotaxi-actor lineage's SafetyGovernor.

  Five checks, in priority order. The first three are HARD: a human approver
  CANNOT override them (you don't get to sign off past invalid XMILE, an
  unknown variable, or a protected-variable edit). The last two are SOFT:
  they only ask a human to look (a big parameter swing / an implausible
  simulated future / low advisor confidence), and the human may approve.

    1. Structural validity  — `xmile.validate/validate` has no `:error`
                               problem, AND no blocking `:warn` (conveyor/
                               queue transport, an unsupported integration
                               method) that would make `xmile.execute/run`
                               throw outright.
    2. Unknown variable     — a `:scenario/propose` patch names a variable
                               that does not exist on the base model
                               (introducing a variable is what
                               `:model/propose` is for, not a patch).
    3. Protected variable   — a patch never touches a configured
                               compliance-floor variable name.
    4. Parameter bound       — a bare-numeric equation change beyond a
                               configured relative bound requires sign-off.
                               (Also: `:model/propose` over an ALREADY
                               registered model-id is a full replace, and
                               always requires sign-off.)
    5. Implausible output    — actually SIMULATING the candidate model (the
                               real xmile.execute engine, not the advisor's
                               say-so) produces a value whose magnitude
                               exceeds a configured cap, or a non-finite
                               value.
    6. Confidence floor       — advisor confidence below threshold escalates.

  Every rule is derived from the PROPOSAL'S OWN CONTENT (the model/patch) and
  the base model — never from the advisor's self-reported `:confidence`/
  `:stake`, so an advisor that mis-reports its own risk can't buy its way
  past the governor."
  (:require [clojure.set :as set]
            [xmile.model :as m]
            [xmile.validate :as validate]
            [xmile.execute :as execute]
            [yosoku.scenario :as scenario]))

;; ───────────────────────── policy tables ─────────────────────────

(def protected-variables
  "Variable names a scenario patch may NEVER touch, no matter who proposes
  the change or how confident/urgent it looks — the SD analog of
  talent.policy's protected attributes. Configured per deployment; this
  default names the compliance-floor constant `yosoku.models/community-growth`
  ships with."
  #{"ComplianceFloor" "RegulatoryCap"})

(def confidence-floor 0.6)

(def parameter-bound
  "Max abs relative change (1.0 == 100%) a bare-numeric equation may swing
  before a scenario patch requires human sign-off."
  1.0)

(def implausible-magnitude
  "Abs value beyond which a simulated series entry is considered implausible
  (holds for human review rather than auto-committing)."
  1.0e6)

(def blocking-warn-codes
  "xmile.validate :warn codes that mean xmile.execute/run would throw if we
  tried to simulate the candidate — structurally valid XMILE, but this
  governor cannot certify a model it cannot actually run through the real
  engine, so these are treated as hard (like an :error)."
  #{:xmile/not-yet-executable :xmile/unsupported-method})

;; ───────────────────────── checks ─────────────────────────

(defn- structural-violations [candidate-model]
  (if-not candidate-model
    [{:rule :missing-model :detail "no candidate model to validate"}]
    (let [problems (validate/validate candidate-model)
          errs (validate/errors problems)
          blocking (filter #(contains? blocking-warn-codes (:xmile/code %))
                            (validate/warnings problems))]
      (into []
            (concat
             (map (fn [p] {:rule :structural-invalid :detail (:xmile/msg p)}) errs)
             (map (fn [p] {:rule :not-simulatable :detail (:xmile/msg p)}) blocking))))))

(defn- unknown-variable-violations [base-model patch]
  (let [known (m/variable-names base-model)
        unknown (remove known (scenario/patched-variable-names patch))]
    (when (seq unknown)
      [{:rule :unknown-variable
        :detail (str "patch references unknown variable(s): " (vec unknown))}])))

(defn- protected-violations [patch]
  (let [touched (scenario/patched-variable-names patch)
        hit (set/intersection touched protected-variables)]
    (when (seq hit)
      [{:rule :protected-variable
        :detail (str "patch touches protected variable(s): " (vec hit))}])))

(defn- abs* [x] #?(:clj (Math/abs (double x)) :cljs (js/Math.abs x)))

(defn- relative-change [old new]
  (if (zero? old)
    (if (zero? new) 0.0 ##Inf)
    (/ (abs* (- new old)) (abs* old))))

(defn- infinite?* [x]
  #?(:clj (Double/isInfinite (double x)) :cljs (not (js/isFinite x))))

(defn- pct-str
  "Human-readable percentage for a relative-change ratio. `(int ...)` throws
  on `##Inf` (old-val was exactly 0, e.g. a gate at rest going from 0 to any
  nonzero value is an infinite relative change) — this is a real, reachable
  case (any 0-initialized aux/parameter), not just a theoretical edge, so it
  must render rather than crash the governor."
  [rel]
  (if (infinite?* rel) "∞" (str (int (* 100 rel)))))

(defn- parameter-bound-violations [base-model patch]
  (keep
   (fn [[nm override]]
     (when-let [new-val (scenario/numeric-eqn (:xmile/eqn override))]
       (when-let [old-val (some-> (m/lookup base-model nm) :xmile/eqn scenario/numeric-eqn)]
         (let [rel (relative-change old-val new-val)]
           (when (> rel parameter-bound)
             {:rule :parameter-bound
              :detail (str nm ": " old-val " -> " new-val
                           " (" (pct-str rel) "% change, bound is "
                           (int (* 100 parameter-bound)) "%)")})))))
   (:variables patch)))

(defn- finite-and-bounded? [v]
  (and (number? v)
       #?(:clj (not (or (Double/isNaN (double v)) (Double/isInfinite (double v))))
          :cljs (js/isFinite v))
       (<= (abs* v) implausible-magnitude)))

(defn- implausible-output-violations
  "Actually RUN the candidate model (the real xmile.execute simulator) and
  scan every recorded series value. Only called once structural-violations
  is empty (execute/run assumes a validated model)."
  [candidate-model]
  (try
    (let [result (execute/run candidate-model)
          bad (distinct
               (for [[nm series] (:xmile/series result)
                     v series
                     :when (not (finite-and-bounded? v))]
                 nm))]
      (when (seq bad)
        [{:rule :implausible-output
          :detail (str "simulated output exceeds plausibility bound for: " (vec bad))}]))
    (catch #?(:clj Exception :cljs :default) ex
      ;; Defensive: structural-violations already screens out models
      ;; xmile.execute/run would throw on, so reaching here is unexpected.
      ;; Fail SAFE — an execution error is itself grounds for a hard hold,
      ;; there is nothing for a human to approve around.
      [{:rule :execution-error :detail (ex-message ex)}])))

(defn check
  "Censors an SD-LLM proposal. `base-model` is the currently-registered model
  for the proposal's `:model-id` (nil for a first-time `:model/propose`).
  Returns {:ok? :hard? :escalate? :confidence :violations [..]}.

    :hard?     — at least one HARD violation (structural/unknown-variable/
                 protected-variable/execution-error). Forces HOLD; a human
                 cannot override.
    :escalate? — soft only: a human decides (approve commits, reject holds).
    :ok?       — clean AND not escalating: safe to auto-commit."
  [_request _context proposal base-model]
  (let [effect (:effect proposal)
        patch (:patch proposal)
        candidate (case effect
                    :apply-scenario (scenario/apply-patch base-model patch)
                    :register-model (:model proposal)
                    nil)
        replace? (and (= effect :register-model) (some? base-model))
        struct-viol (structural-violations candidate)
        unknown-viol (when (= effect :apply-scenario)
                       (unknown-variable-violations base-model patch))
        protect-viol (when (= effect :apply-scenario)
                       (protected-violations patch))
        exec-viol (when (and candidate (empty? struct-viol))
                    (implausible-output-violations candidate))
        hard-exec (filterv #(= :execution-error (:rule %)) exec-viol)
        implausible (remove #(= :execution-error (:rule %)) exec-viol)
        hard (into [] (concat struct-viol unknown-viol protect-viol hard-exec))
        bound-viol (when (= effect :apply-scenario)
                     (parameter-bound-violations base-model patch))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        soft (into []
                   (concat bound-viol
                           implausible
                           (when replace?
                             [{:rule :model-replace
                               :detail "registering over an existing model-id requires sign-off"}])
                           (when low?
                             [{:rule :low-confidence
                               :detail (str "confidence " conf " below floor " confidence-floor)}])))
        hard? (boolean (seq hard))]
    {:ok? (and (not hard?) (empty? soft))
     :hard? hard?
     :escalate? (and (not hard?) (boolean (seq soft)))
     :violations (into hard soft)
     :confidence conf}))

(defn verdict->disposition
  "Map a ScenarioGovernor verdict to a base disposition."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD). The yosoku
  analog of talent.policy's hold-fact / robotaxi logging a safety-reject."
  [request _context verdict]
  {:t :governor-hold
   :op (:op request)
   :model-id (:model-id request)
   :disposition :hold
   :basis (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
