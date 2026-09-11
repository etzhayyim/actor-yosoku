(ns yosoku.governor-test
  "Direct unit tests for ScenarioGovernor/check, isolating one policy rule at
  a time. The single invariant under test: every HARD violation forces
  `:hard? true` (unconditional hold) and every SOFT violation forces
  `:escalate? true` (human decides) while never *also* claiming `:ok? true`."
  (:require [clojure.test :refer [deftest is testing]]
            [yosoku.governor :as governor]
            [yosoku.models :as models]))

(def base (models/community-growth))
(def ctx {:actor-id "operator-1"})

(defn- register [model & {:keys [confidence] :or {confidence 0.9}}]
  {:effect :register-model :model-id "new" :model model :confidence confidence})

(defn- scenario [var eqn & {:keys [confidence] :or {confidence 0.8}}]
  {:effect :apply-scenario :model-id "community-growth"
   :patch {:variables {var {:xmile/eqn eqn}}} :confidence confidence})

(deftest benign-new-model-is-ok
  (let [v (governor/check {:op :model/propose} ctx (register (models/community-growth)) nil)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))
    (is (= [] (:violations v)))))

(deftest benign-scenario-patch-is-ok
  (let [v (governor/check {:op :scenario/propose} ctx (scenario "GrowthRate" "0.04") base)]
    (is (:ok? v))
    (is (= [] (:violations v)))))

(deftest structurally-invalid-model-is-hard-hold
  (let [v (governor/check {:op :model/propose} ctx
                          (register (models/broken-dangling-ref)) nil)]
    (is (:hard? v))
    (is (not (:ok? v)))
    (is (some #{:structural-invalid} (map :rule (:violations v))))))

(deftest unknown-variable-patch-is-hard-hold
  (let [v (governor/check {:op :scenario/propose} ctx (scenario "NoSuchVar" "1") base)]
    (is (:hard? v))
    (is (= [:unknown-variable] (map :rule (:violations v))))))

(deftest protected-variable-patch-is-hard-hold
  (testing "a bound-safe value change to a protected variable still holds"
    (let [v (governor/check {:op :scenario/propose} ctx (scenario "ComplianceFloor" "45") base)]
      (is (:hard? v))
      (is (= [:protected-variable] (map :rule (:violations v)))
          "isolated: -10% is within the parameter bound, so only protected-variable fires"))))

(deftest parameter-bound-swing-escalates-not-holds
  (let [v (governor/check {:op :scenario/propose} ctx (scenario "GrowthRate" "0.07") base)]
    (is (not (:hard? v)))
    (is (:escalate? v))
    (is (= [:parameter-bound] (map :rule (:violations v))))))

(deftest zero-old-value-parameter-bound-does-not-crash
  (testing "old-val exactly 0 -> nonzero is an infinite relative change (##Inf);
            regression for a crash where (int ##Inf) threw
            IllegalArgumentException instead of escalating"
    (let [zero-base (assoc-in base [:xmile/variables "GrowthRate" :xmile/eqn] "0")
          v (governor/check {:op :scenario/propose} ctx (scenario "GrowthRate" "0.07") zero-base)]
      (is (not (:hard? v)))
      (is (:escalate? v))
      (is (= [:parameter-bound] (map :rule (:violations v))))
      (is (re-find #"∞" (:detail (first (:violations v))))))))

(deftest implausible-output-escalates-not-holds
  (testing "a brand-new model (no patch, so parameter-bound cannot apply) whose
            own initial value is already absurd — implausible-output is
            independently caught by actually running xmile.execute"
    (let [v (governor/check {:op :model/propose} ctx
                            (register (models/implausible-seed)) nil)]
      (is (not (:hard? v)))
      (is (:escalate? v))
      (is (= [:implausible-output] (map :rule (:violations v)))))))

(deftest low-confidence-escalates-not-holds
  (let [v (governor/check {:op :scenario/propose} ctx
                          (scenario "GrowthRate" "0.035" :confidence 0.3) base)]
    (is (not (:hard? v)))
    (is (:escalate? v))
    (is (= [:low-confidence] (map :rule (:violations v))))))

(deftest replacing-existing-model-id-always-escalates
  (let [v (governor/check {:op :model/propose} ctx
                          (register (models/community-growth)) base)]
    (is (not (:hard? v)) "a benign replacement model is not structurally/protectedly bad")
    (is (:escalate? v))
    (is (= [:model-replace] (map :rule (:violations v))))))

(deftest hard-violation-wins-even-with-soft-violations-present
  (testing "a protected-variable edit that ALSO swings past bound (300%
            change: 50 -> 200) is still just a HOLD (hard wins; a human
            cannot approve past it, and the soft rule fired too)"
    (let [v (governor/check {:op :scenario/propose} ctx (scenario "ComplianceFloor" "200") base)]
      (is (:hard? v))
      (is (not (:ok? v)))
      (is (some #{:protected-variable} (map :rule (:violations v))))
      (is (some #{:parameter-bound} (map :rule (:violations v))))
      (is (= :hold (governor/verdict->disposition v))))))

(deftest verdict->disposition-maps-correctly
  (is (= :hold (governor/verdict->disposition {:hard? true :escalate? false})))
  (is (= :escalate (governor/verdict->disposition {:hard? false :escalate? true})))
  (is (= :commit (governor/verdict->disposition {:hard? false :escalate? false}))))

(deftest hold-fact-carries-violation-basis
  (let [v (governor/check {:op :scenario/propose} ctx (scenario "NoSuchVar" "1") base)
        f (governor/hold-fact {:op :scenario/propose :model-id "community-growth"} ctx v)]
    (is (= :governor-hold (:t f)))
    (is (= [:unknown-variable] (:basis f)))
    (is (= :hold (:disposition f)))))
