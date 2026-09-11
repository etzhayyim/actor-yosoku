(ns yosoku.sim
  "Demo runner: push five representative proposals through one ScenarioActor
  and watch the ScenarioGovernor + sign-off workflow earn the SD-Advisor the
  right to commit a System-Dynamics forecast model.

    op1  新規モデル登録（健全）                        → commit
    op2  新規モデル登録（構造壊れ: ダングリング参照）  → 構造検証 REJECT → hold
    op3  シナリオ介入（bound 内の成長率調整）          → commit
    op4  シナリオ介入（保護変数 ComplianceFloor 編集） → 保護変数 REJECT → hold
    op5  シナリオ介入（成長率を 0.5 へ急変更、bound 超）→ 人間承認へ escalate
                                                       → 承認 → commit

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [yosoku.store :as store]
            [yosoku.operation :as op]))

(defn- line [& xs] (println (apply str xs)))

(defn- run-op!
  "Run one proposal on its own thread-id. If it interrupts for human sign-off,
  a reviewer 'approves' (or rejects) and we resume."
  [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  人間承認 — レビュー中 (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "reviewer-1"}}
                             {:thread-id thread-id :resume? true})]
            (line "   ▶  承認" (if approve? "可決" "却下") " → disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   → disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)
        ctx {:actor-id "operator-1"}]

    (line "── ScenarioActor (SD-Advisor sealed; ScenarioGovernor active) ──")

    (line "\nop1  新規モデル登録（健全）")
    (run-op! actor "op1" {:op :model/propose :model-id "cg-1"} ctx true)

    (line "\nop2  新規モデル登録（構造壊れ: ダングリング参照）")
    (run-op! actor "op2" {:op :model/propose :model-id "cg-2" :broken? true} ctx true)

    (line "\nop3  シナリオ介入（成長率 0.03→0.04、bound 内）")
    (run-op! actor "op3" {:op :scenario/propose :model-id "community-growth"
                          :change {:var "GrowthRate" :eqn "0.04"}} ctx true)

    (line "\nop4  シナリオ介入（保護変数 ComplianceFloor を編集）")
    (run-op! actor "op4" {:op :scenario/propose :model-id "community-growth"
                          :change {:var "ComplianceFloor" :eqn "0"}} ctx true)

    (line "\nop5  シナリオ介入（成長率を 0.5 へ急変更、bound 超 + 実行結果も非現実的）")
    (run-op! actor "op5" {:op :scenario/propose :model-id "community-growth"
                          :change {:var "GrowthRate" :eqn "0.5"}} ctx true)

    (line "\n── 監査台帳 (append-only; 不変の証跡) ──")
    (doseq [f (store/ledger db)]
      (line "  " (store/ledger-line f)))

    (line "\n── バックエンド差し替え: DatomicStore でも同一契約 ──")
    (let [ds (store/datomic-seed-db)
          dactor (op/build ds)]
      (g/run* dactor {:request {:op :scenario/propose :model-id "community-growth"
                                :change {:var "GrowthRate" :eqn "0.035"}}
                      :context ctx}
              {:thread-id "datomic-op1"})
      (line "  DatomicStore: community-growth.GrowthRate = "
            (get-in (store/model-of ds "community-growth")
                    [:xmile/variables "GrowthRate" :xmile/eqn])
            " / ledger = " (mapv :disposition (store/ledger ds))))
    (line "\ndone.")))
