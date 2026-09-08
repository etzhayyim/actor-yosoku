(ns yosoku.scenario
  "Pure helpers for applying a proposed scenario PATCH to a base XMILE model
  (kotoba-lang/org-oasis-open-xmile EDN shape) to build the CANDIDATE model
  the ScenarioGovernor validates/simulates before anything commits. No I/O,
  no store, no advisor — just data, so it is trivially unit-testable and
  reusable by both `yosoku.governor` (to build the candidate to censor) and
  `yosoku.store` (to actually apply an approved patch to the SSoT).

  A patch is:
    {:variables {var-name {:xmile/eqn \"...\"} ...}   ; per-variable overrides
     :sim-specs {:xmile/stop 36 ...}}                  ; sim_specs overrides
  Both keys are optional; a nil/empty patch is a no-op (candidate == base).

  `apply-patch` is intentionally DEFENSIVE: a patch key naming a variable
  that does not exist in the base model is silently ignored here (never
  fabricates a new variable via a scenario patch — introducing a variable is
  what `:model/propose` is for). `yosoku.governor` independently flags any
  such key as an `:unknown-variable` violation, so the two concerns don't
  silently overlap: the candidate never drifts from the base on an unknown
  key, and the governor still tells you it happened."
  (:require [kotoba.lang.text :as str]))

(defn apply-patch
  "Merge `patch` onto `base-model`, returning the candidate model. `base-model`
  nil is a no-op (returns nil) — callers with no base (e.g. a `:model/propose`
  with no prior model at that id) never reach this fn."
  [base-model patch]
  (when base-model
    (let [{:keys [variables sim-specs]} patch]
      (cond-> base-model
        (seq sim-specs) (update :xmile/sim-specs merge sim-specs)
        (seq variables)
        (update :xmile/variables
                (fn [vs]
                  (reduce-kv
                   (fn [acc nm override]
                     (if (contains? acc nm)
                       (update acc nm merge override)
                       acc))
                   vs variables)))))))

(defn patched-variable-names
  "The set of variable names a patch's `:variables` key mentions (whether or
  not they actually exist in the base model — that's for the caller to
  check against the base)."
  [patch]
  (set (keys (:variables patch))))

(defn numeric-eqn
  "Parse `eqn-str` as a bare decimal-literal equation (e.g. \"150\" or
  \"0.08\"), or nil if it is a formula (references other variables /
  operators / scientific notation). Used by the parameter-bound policy
  check, which only applies to simple constant tuning — a formula change
  isn't a \"parameter\" in the sense that bound is checking."
  [eqn-str]
  (when (string? eqn-str)
    (let [s (str/trim eqn-str)]
      (when (re-matches #"[-+]?\d+(\.\d+)?" s)
        #?(:clj (Double/parseDouble s) :cljs (js/parseFloat s))))))
