(ns yosoku.operation-test
  "The ScenarioActor contract as executable tests — the yosoku analog of
  gftd-talent-actor's policy_contract_test. The single invariant under test:

    the SD-Advisor never writes a model the ScenarioGovernor would reject,
    and every decision (commit OR hold) leaves exactly one ledger fact,
    append-only, never mutating a prior entry.

  MemStore + mock-advisor only — no real LLM calls anywhere in this file."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [yosoku.operation :as op]
            [yosoku.store :as store]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def ctx {:actor-id "operator-1"})

(defn- exec-op [actor tid request]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(deftest benign-new-model-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1" {:op :model/propose :model-id "cg-1"})]
    (is (= :commit (get-in res [:state :disposition])))
    (is (some? (store/model-of db "cg-1")) "SSoT actually gained the new model")
    (is (= 1 (count (store/ledger db))))
    (is (= :commit (-> (store/ledger db) first :disposition)))))

(deftest structurally-broken-model-is-held-not-written
  (let [[db actor] (fresh)
        res (exec-op actor "t2" {:op :model/propose :model-id "cg-2" :broken? true})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (nil? (store/model-of db "cg-2")) "SSoT never gained the broken model")
    (is (= [:structural-invalid] (-> (store/ledger db) first :basis)))))

(deftest protected-variable-patch-is-held-not-written
  (let [[db actor] (fresh)
        before (store/model-of db "community-growth")
        res (exec-op actor "t3" {:op :scenario/propose :model-id "community-growth"
                                 :change {:var "ComplianceFloor" :eqn "0"}})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= before (store/model-of db "community-growth")) "SSoT unchanged")
    (is (some #{:protected-variable} (-> (store/ledger db) first :basis)))))

(deftest benign-scenario-patch-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t4" {:op :scenario/propose :model-id "community-growth"
                                 :change {:var "GrowthRate" :eqn "0.04"}})]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "0.04" (get-in (store/model-of db "community-growth")
                          [:xmile/variables "GrowthRate" :xmile/eqn])))
    (is (= 1 (count (store/ledger db))))))

(deftest parameter-swing-escalates-then-human-approve-commits
  (let [[db actor] (fresh)
        r1 (exec-op actor "t5" {:op :scenario/propose :model-id "community-growth"
                                :change {:var "GrowthRate" :eqn "0.5"}})]
    (is (= :interrupted (:status r1)) "pauses for human sign-off, never auto-commits")
    (is (= [] (store/ledger db)) "no ledger fact yet — commit hasn't happened")
    (testing "approve → commit"
      (let [r2 (g/run* actor {:approval {:status :approved :by "reviewer-1"}}
                       {:thread-id "t5" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= "0.5" (get-in (store/model-of db "community-growth")
                             [:xmile/variables "GrowthRate" :xmile/eqn])))
        (is (= :commit (-> (store/ledger db) last :disposition)))))))

(deftest parameter-swing-escalates-then-human-reject-holds
  (let [[db actor] (fresh)
        before (store/model-of db "community-growth")
        _ (exec-op actor "t6" {:op :scenario/propose :model-id "community-growth"
                               :change {:var "GrowthRate" :eqn "0.5"}})
        r2 (g/run* actor {:approval {:status :rejected :by "reviewer-1"}}
                   {:thread-id "t6" :resume? true})]
    (is (= :hold (get-in r2 [:state :disposition])))
    (is (= before (store/model-of db "community-growth")) "nothing committed on reject")
    (is (= :hold (-> (store/ledger db) last :disposition)))
    (is (some #{:approver-rejected} (-> (store/ledger db) last :basis)))))

(deftest tiny-benign-swing-commits-without-any-escalation
  (testing "a small, in-bound swing through the mock advisor's own (fixed
            0.8) confidence heuristic clears the confidence floor too — it
            just commits, no interrupt at all"
    (let [[db actor] (fresh)
          res (exec-op actor "t7" {:op :scenario/propose :model-id "community-growth"
                                   :change {:var "GrowthRate" :eqn "0.031"}})]
      (is (= :commit (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (= 1 (count (store/ledger db)))))))

(deftest every-decision-leaves-exactly-one-ledger-fact
  (testing "N operations → N ledger facts, one commit + one hold"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :model/propose :model-id "cg-1"})
      (exec-op actor "b" {:op :model/propose :model-id "cg-2" :broken? true})
      (is (= 2 (count (store/ledger db))))
      (is (= [:commit :hold] (mapv :disposition (store/ledger db)))))))

(deftest ledger-is-append-only-and-order-preserving
  (testing "each successive op only ever APPENDS — a prior entry is never
            mutated once written, and order is preserved"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :model/propose :model-id "cg-1"})
      (let [after-1 (store/ledger db)]
        (is (= 1 (count after-1)))
        (exec-op actor "b" {:op :model/propose :model-id "cg-2" :broken? true})
        (let [after-2 (store/ledger db)]
          (is (= 2 (count after-2)))
          (is (= after-1 (vec (take 1 after-2))) "first entry byte-identical after a second op")
          (exec-op actor "c" {:op :scenario/propose :model-id "community-growth"
                              :change {:var "GrowthRate" :eqn "0.04"}})
          (let [after-3 (store/ledger db)]
            (is (= 3 (count after-3)))
            (is (= after-2 (vec (take 2 after-3))) "first two entries byte-identical after a third op")
            (is (= [:commit :hold :commit] (mapv :disposition after-3)))))))))
