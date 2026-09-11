(ns yosoku.scenario-test
  (:require [clojure.test :refer [deftest is testing]]
            [yosoku.models :as models]
            [yosoku.scenario :as scenario]))

(deftest apply-patch-merges-variable-eqn
  (let [base (models/community-growth)
        patched (scenario/apply-patch base {:variables {"GrowthRate" {:xmile/eqn "0.09"}}})]
    (is (= "0.09" (get-in patched [:xmile/variables "GrowthRate" :xmile/eqn])))
    (testing "other variables untouched"
      (is (= "500" (get-in patched [:xmile/variables "Members" :xmile/eqn]))))))

(deftest apply-patch-merges-sim-specs
  (let [base (models/community-growth)
        patched (scenario/apply-patch base {:sim-specs {:xmile/stop 48.0}})]
    (is (= 48.0 (get-in patched [:xmile/sim-specs :xmile/stop])))
    (is (= 0.0 (get-in patched [:xmile/sim-specs :xmile/start])) "untouched fields preserved")))

(deftest apply-patch-ignores-unknown-variable-names
  (testing "a patch key naming a nonexistent variable is a silent no-op here
            (yosoku.governor independently flags it as :unknown-variable)"
    (let [base (models/community-growth)
          patched (scenario/apply-patch base {:variables {"NoSuchVar" {:xmile/eqn "1"}}})]
      (is (= base patched)))))

(deftest apply-patch-nil-base-is-nil
  (is (nil? (scenario/apply-patch nil {:variables {"X" {:xmile/eqn "1"}}}))))

(deftest apply-patch-nil-or-empty-patch-is-noop
  (let [base (models/community-growth)]
    (is (= base (scenario/apply-patch base nil)))
    (is (= base (scenario/apply-patch base {})))))

(deftest patched-variable-names-reflects-patch-keys
  (is (= #{"A" "B"} (scenario/patched-variable-names
                     {:variables {"A" {:xmile/eqn "1"} "B" {:xmile/eqn "2"}}})))
  (is (= #{} (scenario/patched-variable-names nil))))

(deftest numeric-eqn-parses-bare-numbers
  (is (= 150.0 (scenario/numeric-eqn "150")))
  (is (= 0.08 (scenario/numeric-eqn "0.08")))
  (is (= -3.5 (scenario/numeric-eqn "-3.5"))))

(deftest numeric-eqn-rejects-formulas
  (is (nil? (scenario/numeric-eqn "Members / 4")))
  (is (nil? (scenario/numeric-eqn "1e10")) "scientific notation intentionally not treated as a bare constant")
  (is (nil? (scenario/numeric-eqn nil)))
  (is (nil? (scenario/numeric-eqn ""))))
