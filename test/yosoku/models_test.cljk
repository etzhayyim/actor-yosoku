(ns yosoku.models-test
  "Structural + governed-scenario smoke tests for
  `yosoku.models/etzhayyim-substrate-rollout` — the real deployment scenario
  model (distinct from the three governor-contract fixtures, which have no
  dedicated test namespace of their own since they're already exercised
  end-to-end by governor_test/advisor_test/scenario_test)."
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [xmile.validate :as validate]
            [xmile.execute :as execute]
            [yosoku.models :as models]
            [yosoku.governor :as governor]))

(def model (models/etzhayyim-substrate-rollout))

(deftest structurally-valid
  (let [problems (validate/validate model)]
    (is (= [] (validate/errors problems)))
    (is (= [] (validate/warnings problems)))))

(deftest baseline-run-is-finite-and-monotone-non-negative
  (testing "every stock stays finite and non-negative over the 60-tick horizon;
            RoboticsUnits stays at 0 since DispatchGate starts at 0.0 (S0 —
            no robots dispatched — matches the documented current state)"
    (let [{:keys [xmile/series]} (execute/run model)]
      (doseq [[nm vs] series]
        (doseq [v vs] (is (and (number? v) (>= v 0.0)) (str nm " went negative/non-finite"))))
      (is (= 0.0 (last (get series "RoboticsUnits")))))))

(deftest opening-a-gate-escalates-and-commits
  (testing "flipping a 0-valued gate (DispatchGate) to a materially higher
            value is an infinite relative change -> :parameter-bound
            escalation, not a hard hold (regression coverage for the
            zero-old-value formatting fix in yosoku.governor)"
    (let [v (governor/check {:op :scenario/propose} {:actor-id "t"}
                            {:effect :apply-scenario :model-id "etzhayyim-substrate-rollout"
                             :patch {:variables {"DispatchGate" {:xmile/eqn "0.9"}}}
                             :confidence 0.8}
                            model)]
      (is (not (:hard? v)))
      (is (:escalate? v))
      (is (= [:parameter-bound] (map :rule (:violations v)))))))

(deftest compliance-floor-is-protected-on-this-model-too
  (let [v (governor/check {:op :scenario/propose} {:actor-id "t"}
                          {:effect :apply-scenario :model-id "etzhayyim-substrate-rollout"
                           :patch {:variables {"ComplianceFloor" {:xmile/eqn "0"}}}
                           :confidence 0.9}
                          model)]
    (is (:hard? v))
    (is (some #{:protected-variable} (map :rule (:violations v))))))

(deftest dispute-resolution-coverage-is-never-wired-to-robotics-units
  (testing "DisputeResolutionCoverage's own equation, and every equation that
            references RoboticsUnits, must not mention the other — the model
            intentionally keeps 'transparency/adjudication capacity' and
            'physical/robotics capacity' structurally disjoint"
    (let [dispute-eqn (get-in model [:xmile/variables "AdjudicationExpansion" :xmile/eqn])
          fleet-eqn (get-in model [:xmile/variables "FleetDeployment" :xmile/eqn])]
      (is (not (str/includes? dispute-eqn "RoboticsUnits")))
      (is (not (str/includes? fleet-eqn "DisputeResolutionCoverage"))))))

(deftest land-trust-gates-physical-delivery-stocks
  (testing "LandTrustCoverage is a real dependency of both physical-delivery
            stocks (facilities are a prerequisite for care delivery and fleet
            depots alike)"
    (is (str/includes?
         (get-in model [:xmile/variables "CoverageExpansion" :xmile/eqn]) "LandTrustCoverage"))
    (is (str/includes?
         (get-in model [:xmile/variables "FleetDeployment" :xmile/eqn]) "LandTrustCoverage"))))

(deftest attestation-coverage-gates-fleet-deployment
  (testing "kuni-umi's own design requires witness-attestation infrastructure
            proven out before robot dispatch scales — AttestationCoverage is
            a real dependency of FleetDeployment, not just a parallel stock"
    (is (str/includes?
         (get-in model [:xmile/variables "FleetDeployment" :xmile/eqn]) "AttestationCoverage"))))

(deftest attestation-coverage-is-never-wired-to-dispute-resolution
  (testing "AttestationCoverage (cryptographic multi-witness verification)
            and DisputeResolutionCoverage (Council adjudication) stay
            structurally independent — neither equation mentions the other"
    (let [attest-eqn (get-in model [:xmile/variables "AttestationExpansion" :xmile/eqn])
          dispute-eqn (get-in model [:xmile/variables "AdjudicationExpansion" :xmile/eqn])]
      (is (not (str/includes? attest-eqn "DisputeResolutionCoverage")))
      (is (not (str/includes? dispute-eqn "AttestationCoverage"))))))
