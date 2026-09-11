(ns yosoku.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic' a configuration change, not a rewrite —
  the same guarantee gftd-talent-actor's store_contract_test proves for
  talent.store."
  (:require [clojure.test :refer [deftest is testing]]
            [yosoku.models :as models]
            [yosoku.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= ["community-growth"] (store/all-model-ids s)))
      (let [m (store/model-of s "community-growth")]
        (is (= "500" (get-in m [:xmile/variables "Members" :xmile/eqn])))
        (is (= #{"Invitations"} (get-in m [:xmile/variables "Members" :xmile/inflows]))
            "set-valued attrs (inflows) round-trip through the EDN encoding")
        (is (= 24.0 (get-in m [:xmile/sim-specs :xmile/stop]))))
      (is (= [] (store/ledger s))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "register-model writes a brand-new model"
        (store/commit-record! s {:effect :register-model :model-id "extra"
                                 :model (models/broken-dangling-ref)})
        (is (= "broken" (:xmile/name (store/model-of s "extra"))))
        (is (= #{"community-growth" "extra"} (set (store/all-model-ids s)))))
      (testing "apply-scenario patches the existing model in place"
        (store/commit-record! s {:effect :apply-scenario :model-id "community-growth"
                                 :patch {:variables {"GrowthRate" {:xmile/eqn "0.09"}}}})
        (is (= "0.09" (get-in (store/model-of s "community-growth")
                              [:xmile/variables "GrowthRate" :xmile/eqn])))
        (is (= "500" (get-in (store/model-of s "community-growth")
                             [:xmile/variables "Members" :xmile/eqn]))
            "untouched variables preserved"))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/model-of s "nope")))
    (is (= [] (store/all-model-ids s)))
    (is (= [] (store/ledger s)))
    (store/with-model s "x" (models/community-growth))
    (is (= "community-growth" (:xmile/name (store/model-of s "x"))))))

(deftest with-model-nil-args-are-a-no-op
  (doseq [[label s] (backends)]
    (testing label
      (let [before (store/model-of s "community-growth")]
        (store/with-model s nil nil)
        (store/with-model s "community-growth" nil)
        (is (= before (store/model-of s "community-growth")))))))
