# ADR-0001: actor-yosoku — SD-Advisor を封じ込めた System-Dynamics シナリオ・シミュレーション actor 設計

- Status: Accepted (2026-07-07)
- 関連: gftd-talent-actor ADR-0001 (HR-LLM を封じ込めたタレントマネジメント actor 設計 — 同型の
  写像元), cloud-itonami (ops-LLM ⊣ CertGovernor), langgraph-clj ADR-0001 (Pregel superstep +
  interrupt + Datomic checkpoint), kotoba-lang/org-oasis-open-xmile (OASIS XMILE 1.0 の
  stock-and-flow モデル + 方程式パーサ + 構造検証 + Euler/RK4 シミュレータ、本 actor が
  唯一のシミュレーション基盤として直接依拠する)
- 文脈: `org-oasis-open-xmile` が同一セッションで実装された直後、etzhayyim 配下に
  「System Dynamics を使う actor を1つ新設せよ」という指示があった（既存 actor の
  改修ではなく新設。ユーザー確認済み）。

## 課題

System Dynamics（Jay Forrester のストック・フロー・モデリング手法）でシナリオ予測・意思決定
支援を行いたいが、これを素朴に「LLM にモデルとシミュレーション結果を語らせる」形で作ると、
致命的な弱点を抱える:

1. **構造的に壊れたモデルを事実として提示しうる** — ダングリング参照・不正な代数ループ・
   壊れた `sim_specs` を持つモデルは、そもそも XMILE として無効であり、シミュレーション不能
   （実行すれば例外になるか、無意味な数値を返す）。LLM 自身にはこれを検知する手段がない。
2. **非現実的な将来予測を「シミュレーション結果」と称して提示しうる** — LLM は「もっともらしい
   数字」を生成できても、実際に微分方程式を解いた結果ではない。パラメータをわずかに動かした
   だけで暴走する非線形フィードバック系（今回のモデルで言えば指数成長）を、LLM の自己申告する
   confidence だけで信頼するのは危険。
3. **コンプライアンス上固定すべき変数を LLM が無自覚に動かしうる** — 規制上限や準備金下限の
   ような「触ってはいけない定数」を、シナリオ介入の一環として静かに書き換えてしまう。

したがって設計課題は「LLM でシステムダイナミクスを回す」ことではなく、**「LLM を信頼境界の
内側に封じ込め、構造検証・実シミュレーション・保護変数・人間承認をどう被せるか」**である。
これは gftd-talent-actor が HR-LLM を PolicyGovernor で封じ込めた構図、さらに遡れば
robotaxi 系譜が研究モデルを SafetyGovernor で封じ込めた構図の、そのままの写像である。

## 決定

### 1. SD-Advisor は最下層の1ノードに封じ込め、直接コミットさせない

ScenarioActor 内で SD-Advisor は *proposal*（新規モデル案、またはシナリオ介入パッチ ＋
根拠トレース）のみを返す**助言者**として扱う。出力は必ず独立した `ScenarioGovernor` を
通してから台帳に commit する。**単一の不変条件**:

> **SD-Advisor は、ScenarioGovernor が拒否するモデル/シナリオ介入のコミットを決して
> 行わない。**

### 2. ScenarioGovernor は「実際にシミュレーションを回す」ことで LLM のナラティブに依存しない

`talent.policy` が保護属性表という**規程データ**で武装したのに対し、この governor は
**`kotoba-lang/org-oasis-open-xmile` という実行可能なシミュレーション・エンジン**で
武装する。これが本 actor の設計上の核心:

- **構造検証（HARD）**: `xmile.validate/validate` が `:error` を1件でも返せば拒否
  （ダングリング参照・不正な代数ループ・壊れた `sim_specs`/`gf`）。加えて、
  `xmile.execute/run` が例外を投げる種類の `:warn`（conveyor/queue 輸送・未対応の
  積分法）も HARD 扱いにする — 「実行できないものを govern する」ことはできないため。
- **妥当性検証（SOFT）**: 構造的に有効な候補モデルを **実際に `xmile.execute/run` で
  シミュレートし**、出力系列のいずれかが妥当性上限（既定 `1.0e6`）を超える、または
  非有限値になる場合は人間承認へ escalate する。これは LLM の自己申告 confidence を
  一切信頼しない — 実行結果という一次情報だけで判定する。
- **保護変数（HARD）**: 設定された変数名集合（既定 `ComplianceFloor`/`RegulatoryCap`）への
  パッチは、確信度や緊急性に関わらず常に拒否。人間が承認で上書きすることもできない。
- **未知変数（HARD）**: シナリオパッチが既存モデルに存在しない変数名を参照した場合は拒否
  （新規変数の導入は `:model/propose` の役割であり、パッチの役割ではない）。
- **パラメータ境界（SOFT）**: 裸の数値方程式が現在値から相対 100% を超えて変化する場合は
  人間承認へ escalate。
- **モデル置換（SOFT）**: 既に登録済みの model-id への `:model/propose`（新規モデル登録）は
  実質的な全置換とみなし、常に人間承認を要求する。
- **確信度フロア（SOFT）**: advisor の confidence が閾値（既定 0.6）未満なら escalate。

すべての判定は **proposal 自体の内容（モデル/パッチ）とベースモデルから governor が
独自に再計算**する — advisor の自己申告する `:confidence`/`:stake` を信頼しない。これは
`talent.policy` が `:cites`/`:columns` を直接スキャンして公正性/開示違反を検知するのと
同型のパターンで、"advisor が自分のリスクを過小申告しても governor を通過できない" ことを
構造的に保証する。

### 3. ScenarioActor = langgraph-clj StateGraph、1 run = 1 proposal

```
intake → propose(SD-Advisor) → govern(ScenarioGovernor) → decide ─┬ commit ──▶ END
                                                                   ├ escalate ─▶ request-approval [interrupt-before]
                                                                   │              人間が承認/却下
                                                                   │              resume ─▶ commit | hold
                                                                   └ hold ─────▶ END
```

- 連続処理ループを持たず「1 proposal = 1 run」とし、各提案を監査可能・checkpoint 可能にする
  （gftd-talent-actor の「1操作=1 run」と同型）。
- `interrupt-before #{:request-approval}` を実際の人間承認ワークフローに転用する
  （langgraph-clj の human-in-the-loop）。
- `:audit` チャネルに提案根拠・判定・承認・差し戻しを蓄積 → 監査台帳の証跡が同一ファクトログ
  から落ちてくる。

### 4. SSoT と台帳はすべて `:db-api` 駆動（Store は swap）

`yosoku.store/Store` protocol を `MemStore`（既定、`.cljc` in-mem）と `DatomicStore`
（`langchain.db` 経由、実 Datomic Local / kotoba-server pod へさらに差し替え可能）の両方が
実装し、同一の contract test（`store_contract_test.clj`）で parity を保証する。登録済み
モデルは EDN 全体を単一の値として保持する（変数ごとにエンティティを分解しない）— シナリオ
パッチ/置換は SSoT の観点では「現在モデルの丸ごと入れ替え」であり、何がどう変わったかの
監査証跡は append-only 台帳（バージョン管理されたエンティティグラフではなく）が担う。

### 5. Advisor は `mock-advisor` のみを v1 で出荷する（判断）

`gftd-talent-actor` は `mock-advisor` と `llm-advisor`（`langchain.model` 経由、
`model/mock-model` でテスト）の両方を実装したが、本 actor は `kotoba-lang/kessai` の
「mock adapter のみ実装、real adapter は follow-up」という先例に倣い、v1 では
`mock-advisor` のみを出荷する。`Advisor` protocol という注入境界（swap point）自体は
実装するので、実 LLM 化はプロトコルの実装差し替えであり書き直しではない。

## 帰結

- **得るもの**: 実行可能な XMILE シミュレーション・エンジンに直接依拠した、LLM ナラティブに
  頼らない意思決定支援。構造検証・妥当性検証・保護変数・人間承認が実行可能なゲートとして
  強制される。不変の監査台帳。
- **負うもの**: `org-oasis-open-xmile` v1 のスコープ外機能（conveyor/queue・配列・
  確率的/delay/smooth/trend 組み込み関数・rk2/rk45/gear 積分法）を持つモデルは
  `:not-simulatable` として一律 HARD reject になる — これは同ライブラリの v2 ロードマップに
  追従する形の制約であり、本 actor 側で回避策を作らない。
- **段階導入**: v1 は Phase 0→3 のような段階的自律化ゲートを持たない（README Follow-ups）。
  タスクの明示スコープは governor の構造/ポリシー検査であり、段階的自律化は将来の拡張。

## 代替案と不採用理由

- **LLM にシミュレーション結果も語らせる（実行エンジン無し）**: 実装は速いが、「非現実的な
  予測を事実として提示しうる」という本 ADR の動機そのものに反する。実 XMILE エンジンを
  持たない「予測」actor は、そもそもこの actor を作る理由がない。
- **`talent.policy` 型の RBAC を v1 から搭載**: 本タスクの明示スコープ（構造検証・
  パラメータ境界・妥当性・保護変数）を超える。単一の信頼された operator context を前提とし、
  複数 caller の権限分離は follow-up とした（README 参照）。
- **バージョン管理されたシナリオ履歴グラフ**: 台帳とは別にモデルの全バージョンを
  エンティティとして保持する設計も検討したが、v1 の要求（"何が・いつ・なぜ変わったか"の
  監査可能性）は append-only 台帳で十分満たせる。diff/replay が必要になった時点の follow-up。
