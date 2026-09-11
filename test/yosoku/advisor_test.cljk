(ns yosoku.advisor-test
  "Most of this suite drives the mock SD-Advisor — these tests only prove it
  produces well-shaped, structurally-sound proposals; the actual censoring
  is `yosoku.governor`'s job, exercised in governor_test.clj/
  operation_test.clj. `llm-advisor`'s tests at the bottom use
  `langchain.model/mock-model` (fully offline/deterministic, no network) to
  prove the real-LLM wiring, prompt-building, and defensive EDN parsing are
  correct — they do NOT prove anything about a live Murakumo endpoint."
  (:require [clojure.test :refer [deftest is testing]]
            [langchain.model :as model]
            [xmile.validate :as validate]
            [yosoku.advisor :as advisor]
            [yosoku.models :as models]
            [yosoku.store :as store]))

(deftest model-propose-produces-a-structurally-valid-proposal
  (let [p (advisor/infer (store/seed-db) {:op :model/propose :model-id "cg-1"})]
    (is (= :register-model (:effect p)))
    (is (= "cg-1" (:model-id p)))
    (is (map? (:model p)))
    (is (validate/valid? (validate/validate (:model p)))
        "the mock's default template is real, executable XMILE")
    (is (>= (:confidence p) 0.6))))

(deftest model-propose-broken-flag-produces-a-structurally-invalid-model
  (testing "the :broken? deliberate-failure fixture is actually broken (sanity
            check on the test fixture itself, not just the governor's reaction)"
    (let [p (advisor/infer (store/seed-db) {:op :model/propose :model-id "cg-2" :broken? true})]
      (is (not (validate/valid? (validate/validate (:model p))))))))

(deftest model-propose-implausible-flag-is-structurally-valid-but-absurd
  (let [p (advisor/infer (store/seed-db) {:op :model/propose :model-id "cg-3" :implausible? true})]
    (is (validate/valid? (validate/validate (:model p)))
        "implausible != invalid — it's a plausibility problem, not a structural one")))

(deftest scenario-propose-produces-a-patch-against-the-current-model
  (let [st (store/seed-db)
        p (advisor/infer st {:op :scenario/propose :model-id "community-growth"
                             :change {:var "GrowthRate" :eqn "0.05"}})]
    (is (= :apply-scenario (:effect p)))
    (is (= {:variables {"GrowthRate" {:xmile/eqn "0.05"}}} (:patch p)))
    (is (= ["GrowthRate"] (:cites p)))
    (is (>= (:confidence p) 0.6))))

(deftest unroutable-op-is-a-safe-noop
  (let [p (advisor/infer (store/seed-db) {:op :not-a-real-op})]
    (is (= :noop (:effect p)))
    (is (= 0.0 (:confidence p)))))

(deftest trace-is-a-decision-grounded-audit-record
  (let [req {:op :model/propose :model-id "cg-1"}
        p (advisor/infer (store/seed-db) req)
        tr (advisor/trace req p)]
    (is (= :advisor-proposal (:t tr)))
    (is (= :model/propose (:op tr)))
    (is (= "cg-1" (:model-id tr)))
    (is (= (:confidence p) (:confidence tr)))))

(deftest mock-advisor-cannot-advise-safely-noops
  (let [p (advisor/infer (store/seed-db) {:op :scenario/advise :model-id "community-growth"
                                          :intent "grow faster"})]
    (is (= :noop (:effect p)))
    (is (= 0.0 (:confidence p)))))

;; ───────────────────────── assert-murakumo! ─────────────────────────

(deftest assert-murakumo-accepts-allowlisted-hosts
  (advisor/assert-murakumo! "http://127.0.0.1:11434")
  (advisor/assert-murakumo! "http://192.168.1.70:4000"))

(deftest assert-murakumo-accepts-tailnet-fleet-nodes
  (testing "com-junkawasaki tailnet Murakumo-fleet Ollama nodes (verified live
            2026-07-13) are the same physical fleet as the LAN entries, just
            reachable via Tailscale"
    (doseq [ip ["100.98.142.59" "100.66.28.79" "100.102.78.81" "100.75.169.8"
                "100.89.204.30" "100.82.123.35" "100.101.27.85" "100.81.66.86"]]
      (advisor/assert-murakumo! (str "http://" ip ":11434")))))

(deftest assert-murakumo-rejects-other-hosts
  (is (thrown? Exception (advisor/assert-murakumo! "https://api.openai.com"))))

;; ───────────────────────── parse-scenario-advice-edn ─────────────────────────

(def ^:private known #{"GrowthRate" "ComplianceFloor"})

(deftest parse-scenario-advice-accepts-well-formed-numeric-advice
  (is (= {:var "GrowthRate" :eqn "0.05" :rationale "safe nudge" :confidence 0.7}
         (advisor/parse-scenario-advice-edn
          "{:var \"GrowthRate\" :eqn \"0.05\" :rationale \"safe nudge\" :confidence 0.7}" known))))

(deftest parse-scenario-advice-strips-code-fences
  (is (= "GrowthRate"
         (:var (advisor/parse-scenario-advice-edn
                "```edn\n{:var \"GrowthRate\" :eqn \"0.04\"}\n```" known)))))

(deftest parse-scenario-advice-rejects-out-of-vocabulary-variable
  (is (nil? (advisor/parse-scenario-advice-edn
             "{:var \"NoSuchVar\" :eqn \"0.5\"}" known))))

(deftest parse-scenario-advice-rejects-formula-not-literal
  (testing "a formula (references another var) is not a bare numeric literal —
            the LLM may tune a value, never author logic"
    (is (nil? (advisor/parse-scenario-advice-edn
               "{:var \"GrowthRate\" :eqn \"GrowthRate * 2\"}" known)))))

(deftest parse-scenario-advice-rejects-unparseable-content
  (is (nil? (advisor/parse-scenario-advice-edn "not edn at all {{{" known))))

;; ───────────────────────── llm-advisor (offline, mock-model) ─────────────────────────

(deftest llm-advisor-scenario-advise-produces-a-governed-patch-proposal
  (let [st (store/mem-store {"etzhayyim-substrate-rollout" (models/etzhayyim-substrate-rollout)})
        chat (model/mock-model
              [{:role :assistant
                :content "{:var \"G7ConsentGate\" :eqn \"0.2\" :rationale \"modest safe expansion\" :confidence 0.7}"}])
        adv (advisor/llm-advisor chat)
        p (advisor/-advise adv st {:op :scenario/advise :model-id "etzhayyim-substrate-rollout"
                                    :intent "cautiously widen mimamori consent coverage"})]
    (is (= :apply-scenario (:effect p)))
    (is (= {:variables {"G7ConsentGate" {:xmile/eqn "0.2"}}} (:patch p)))
    (is (= ["G7ConsentGate"] (:cites p)))
    (is (= 0.7 (:confidence p)))))

(deftest llm-advisor-falls-back-to-infer-for-non-advise-ops
  (testing "an llm-advisor never calls the chat model for :model/propose or
            :scenario/propose — those stay on the deterministic path"
    (let [chat (model/mock-model
                (fn [_ _] (throw (ex-info "chat model should never be called for this op" {}))))
          adv (advisor/llm-advisor chat)
          st (store/seed-db)
          p (advisor/-advise adv st {:op :scenario/propose :model-id "community-growth"
                                     :change {:var "GrowthRate" :eqn "0.04"}})]
      (is (= :apply-scenario (:effect p)))
      (is (= {:variables {"GrowthRate" {:xmile/eqn "0.04"}}} (:patch p))))))

(deftest llm-advisor-unparseable-output-is-a-safe-noop
  (let [st (store/mem-store {"etzhayyim-substrate-rollout" (models/etzhayyim-substrate-rollout)})
        chat (model/mock-model [{:role :assistant :content "I cannot decide, sorry."}])
        adv (advisor/llm-advisor chat)
        p (advisor/-advise adv st {:op :scenario/advise :model-id "etzhayyim-substrate-rollout"
                                    :intent "anything"})]
    (is (= :noop (:effect p)))
    (is (= 0.0 (:confidence p)))))

(deftest llm-advisor-unknown-model-id-is-a-safe-noop
  (let [chat (model/mock-model
              (fn [_ _] (throw (ex-info "chat model should never be called for an unregistered model-id" {}))))
        adv (advisor/llm-advisor chat)
        p (advisor/-advise adv (store/mem-store {}) {:op :scenario/advise :model-id "no-such-model"
                                                       :intent "anything"})]
    (is (= :noop (:effect p)))
    (is (= 0.0 (:confidence p)))))
