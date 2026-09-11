(ns yosoku.models
  "Canned XMILE (kotoba-lang/org-oasis-open-xmile) models used to seed the
  demo store and to exercise the SD-LLM advisor / ScenarioGovernor end to end
  offline. These are demo/test fixtures, not a model catalog — a real
  deployment seeds models from an operator's own XMILE authoring tool (or a
  hand-built `.cljc` model) via `yosoku.store/with-model`."
  (:require [xmile.model :as m]))

(defn community-growth
  "A small forecast model: Members grow via Invitations (rate = GrowthRate)
  and shrink via Attrition (2%/tick). `ComplianceFloor` is a reference
  constant that stands in for a compliance/policy floor — a value the
  ScenarioGovernor's protected-variable check never lets a scenario patch
  touch, no matter who (or what) proposes the change."
  []
  (-> (m/model "community-growth"
               {:xmile/sim-specs (m/sim-specs 0.0 24.0 {:xmile/dt 1.0})})
      (m/add-variable (m/stock "Members" "500"
                                {:xmile/inflows #{"Invitations"}
                                 :xmile/outflows #{"Attrition"}}))
      (m/add-variable (m/flow "Invitations" "Members * GrowthRate"))
      (m/add-variable (m/flow "Attrition" "Members * 0.02"))
      (m/add-variable (m/aux "GrowthRate" "0.03"))
      (m/add-variable (m/aux "ComplianceFloor" "50"))))

(defn broken-dangling-ref
  "A structurally INVALID model (a flow's equation references a variable
  that does not exist) — used to exercise the ScenarioGovernor's hard
  structural-reject path. The yosoku analog of talent.hrllm's `:bias?`
  deliberate-failure injection."
  []
  (-> (m/model "broken"
               {:xmile/sim-specs (m/sim-specs 0.0 10.0 {:xmile/dt 1.0})})
      (m/add-variable (m/stock "Widget" "10" {:xmile/inflows #{"Production"}}))
      (m/add-variable (m/flow "Production" "NoSuchVariable * 2"))))

(defn implausible-seed
  "A structurally VALID but already-absurd model (a stock seeded at 2
  million) — used to exercise the ScenarioGovernor's implausible-output
  check on a brand-new `:model/propose` (no patch involved, so the
  parameter-bound check — which only applies to `:scenario/propose` patches
  — cannot mask what implausible-output is independently catching)."
  []
  (-> (m/model "implausible"
               {:xmile/sim-specs (m/sim-specs 0.0 10.0 {:xmile/dt 1.0})})
      (m/add-variable (m/stock "Hoard" "2000000" {:xmile/inflows #{"Growth"}}))
      (m/add-variable (m/flow "Growth" "Hoard * 0.5"))))

(defn etzhayyim-substrate-rollout
  "A real deployment scenario (not a governor-contract fixture like the three
  above): 12 stocks tracking rollout maturity of the etzhayyim mission
  charter's parallel-substrate layers (90-docs/adr/2605192100-etzhayyim-
  mission-charter.md §1.12) — CouncilSeats (governance legitimacy, 0-5),
  TreasuryCapacity (junbi reserve pipeline, 0-100), IdentityCoverage
  (did:web/did:plc/SBT issuance, 0-100), AgentCapacity (real, non-mock
  LLM-agent administrative throughput, 0-100), SocialCoverage (real, non-
  synthetic mimamori/hagukumi/Basic-High-Income delivery, 0-100),
  RoboticsUnits (deployed kuni-umi/watatsumi fleet, 0-100), PublicTrust (a
  reinforcing-loop stock: delivered SocialCoverage/RoboticsUnits/
  DisputeResolutionCoverage/AttestationCoverage feed back into faster
  CouncilOnboarding), ContractLawCoverage (Solidity smart contracts +
  Constitution.sol ratification, 0-100), LandTrustCoverage (Religious-Corp
  Land Trust real-estate holdings, 0-100), EducationCoverage (open-* apps +
  agent-fleet education reach, 0-100), DisputeResolutionCoverage
  (Council-based adjudication + transparency/audit-trail compliance —
  deliberately NOT wired to RoboticsUnits/physical force anywhere in this
  model; the charter's own \"Transparent Religious Force\" framing is
  transparency-based, not a coercive-capability stock, and this model does
  not represent one, 0-100), AttestationCoverage (kuni-umi's own documented
  Ed25519 N>=2 witness-attestation infrastructure — cryptographic multi-
  witness verification of a robotics operation, explicitly designed to
  replace a \"国家 inspector\" (nation-state inspector) — 0-100).

  Each downstream flow's equation multiplies in `MIN(1, upstream/threshold)`
  terms — the dependency DAG (u -> v = v's growth requires u's stock level
  as an operating input) made computational, so `xmile.execute/run` and
  ScenarioGovernor-mediated `:scenario/propose` patches double as a
  structural/reverse-topological-sort analysis tool, not just a forecast.
  LandTrustCoverage also gates FleetDeployment/CoverageExpansion (physical
  depots/care facilities), AttestationCoverage ALSO gates FleetDeployment
  (kuni-umi's own design requires witness-attestation infrastructure proven
  out before scaling robot dispatch — the substitute for a national
  inspector must itself be operational first), and EducationCoverage softly
  boosts AgentOnboarding's rate (human-capital -> agent-capacity pipeline)
  rather than hard-gating it.

  Nine aux constants are the model's real point — each is a *self-imposed*
  policy gate, not a missing-capability gate: `LegalActivationGate` (junbi
  CBDC/fiat activation — documented as \"an ops/legal act, not code\"),
  `RealLLMWiringGate` (mock-advisor -> real Murakumo inference),
  `G7ConsentGate` (mimamori's own documented \"live legs G7-gated\" self
  -limit), `DispatchGate` (kuni-umi's own documented S0 \"no robots
  dispatched\"), `ConstitutionRatificationGate` (Council ratifying
  Constitution.sol as binding), `LandAcquisitionGate` (real-estate capital
  deployment), `CurriculumGate` (education-content authorization),
  `AdjudicationAuthorityGate` (Council accepting binding-adjudication
  liability), `AttestationProtocolGate` (Council ratifying the N>=2
  witness-attestation protocol as binding). All nine start near 0 — the
  model's baseline trajectory reproduces the 2026-07-13 audit finding that
  growth is capped by self-imposed policy gates, not by missing capability
  (TreasuryCapacity and CouncilSeats — the least-gated stocks — still climb
  steadily). `ComplianceFloor`/`RegulatoryCap` are the two governor-
  protected constants (governor.cljc's default `protected-variables`),
  standing in for the charter's own explicit self-limit (\"国家転覆では
  ない\" — not a nation-overthrow) — no `:scenario/propose` patch may ever
  touch them, structurally, regardless of who or what proposes it; every
  stock in this model (including DisputeResolutionCoverage and
  AttestationCoverage) saturates at the same 100 ceiling as everything
  else, with no separate escalation path.

  See 90-docs/adr/2607132200-etzhayyim-post-nation-state-substrate-system-
  dynamics-reverse-toposort.md and its amendment ADRs (com-junkawasaki
  superproject) for the full structural analysis, per-gate leverage
  scoring, and reverse-topological build-order this model backs."
  []
  (-> (m/model "etzhayyim-substrate-rollout"
               {:xmile/sim-specs (m/sim-specs 0.0 60.0 {:xmile/dt 1.0})})
      (m/add-variable (m/stock "CouncilSeats" "1"
                                {:xmile/inflows #{"CouncilOnboarding"}}))
      (m/add-variable (m/stock "TreasuryCapacity" "70"
                                {:xmile/inflows #{"TreasuryGrowth"}}))
      (m/add-variable (m/stock "IdentityCoverage" "2"
                                {:xmile/inflows #{"IdentityIssuance"}}))
      (m/add-variable (m/stock "AgentCapacity" "5"
                                {:xmile/inflows #{"AgentOnboarding"}}))
      (m/add-variable (m/stock "SocialCoverage" "1"
                                {:xmile/inflows #{"CoverageExpansion"}}))
      (m/add-variable (m/stock "RoboticsUnits" "0"
                                {:xmile/inflows #{"FleetDeployment"}}))
      (m/add-variable (m/stock "PublicTrust" "5"
                                {:xmile/inflows #{"TrustGain"} :xmile/outflows #{"TrustDecay"}}))
      (m/add-variable (m/stock "ContractLawCoverage" "15"
                                {:xmile/inflows #{"ContractLawRatification"}}))
      (m/add-variable (m/stock "LandTrustCoverage" "0"
                                {:xmile/inflows #{"LandAcquisition"}}))
      (m/add-variable (m/stock "EducationCoverage" "3"
                                {:xmile/inflows #{"EducationExpansion"}}))
      (m/add-variable (m/stock "DisputeResolutionCoverage" "2"
                                {:xmile/inflows #{"AdjudicationExpansion"}}))
      (m/add-variable (m/stock "AttestationCoverage" "8"
                                {:xmile/inflows #{"AttestationExpansion"}}))

      (m/add-variable (m/flow "CouncilOnboarding"
                               "(5 - CouncilSeats) * CouncilOnboardRate * (1 + PublicTrust / 100)"))
      (m/add-variable (m/flow "TreasuryGrowth"
                               "(100 - TreasuryCapacity) * TreasuryGrowthRate"))
      (m/add-variable (m/flow "IdentityIssuance"
                               (str "(100 - IdentityCoverage) * IdentityIssuanceRate"
                                    " * MIN(1, CouncilSeats / 5)"
                                    " * MIN(1, TreasuryCapacity * LegalActivationGate / 50)")))
      (m/add-variable (m/flow "AgentOnboarding"
                               (str "(100 - AgentCapacity) * AgentOnboardingRate"
                                    " * MIN(1, IdentityCoverage / 40)"
                                    " * (1 + EducationCoverage / 200) * RealLLMWiringGate")))
      (m/add-variable (m/flow "CoverageExpansion"
                               (str "(100 - SocialCoverage) * CareMatchRate"
                                    " * MIN(1, AgentCapacity / 40)"
                                    " * MIN(1, IdentityCoverage / 40)"
                                    " * MIN(1, LandTrustCoverage / 30) * G7ConsentGate")))
      (m/add-variable (m/flow "FleetDeployment"
                               (str "(100 - RoboticsUnits) * ManufacturingRate"
                                    " * MIN(1, SocialCoverage / 30)"
                                    " * MIN(1, TreasuryCapacity / 60)"
                                    " * MIN(1, LandTrustCoverage / 40)"
                                    " * MIN(1, AttestationCoverage / 30) * DispatchGate")))
      (m/add-variable (m/flow "TrustGain"
                               (str "(SocialCoverage * 0.3 + RoboticsUnits * 0.2"
                                    " + DisputeResolutionCoverage * 0.2"
                                    " + AttestationCoverage * 0.3) * TrustGainRate")))
      (m/add-variable (m/flow "TrustDecay" "PublicTrust * TrustDecayRate"))
      (m/add-variable (m/flow "ContractLawRatification"
                               (str "(100 - ContractLawCoverage) * ContractLawRate"
                                    " * MIN(1, CouncilSeats / 5)"
                                    " * MIN(1, TreasuryCapacity / 60) * ConstitutionRatificationGate")))
      (m/add-variable (m/flow "LandAcquisition"
                               (str "(100 - LandTrustCoverage) * LandAcquisitionRate"
                                    " * MIN(1, TreasuryCapacity / 60)"
                                    " * MIN(1, ContractLawCoverage / 40) * LandAcquisitionGate")))
      (m/add-variable (m/flow "EducationExpansion"
                               (str "(100 - EducationCoverage) * EducationRate"
                                    " * MIN(1, AgentCapacity / 40)"
                                    " * MIN(1, IdentityCoverage / 40) * CurriculumGate")))
      (m/add-variable (m/flow "AdjudicationExpansion"
                               (str "(100 - DisputeResolutionCoverage) * DisputeResolutionRate"
                                    " * MIN(1, ContractLawCoverage / 40)"
                                    " * MIN(1, PublicTrust / 40) * AdjudicationAuthorityGate")))
      (m/add-variable (m/flow "AttestationExpansion"
                               (str "(100 - AttestationCoverage) * AttestationRate"
                                    " * MIN(1, IdentityCoverage / 40)"
                                    " * MIN(1, ContractLawCoverage / 40) * AttestationProtocolGate")))

      (m/add-variable (m/aux "CouncilOnboardRate" "0.03"))
      (m/add-variable (m/aux "TreasuryGrowthRate" "0.02"))
      (m/add-variable (m/aux "IdentityIssuanceRate" "0.05"))
      (m/add-variable (m/aux "AgentOnboardingRate" "0.05"))
      (m/add-variable (m/aux "CareMatchRate" "0.05"))
      (m/add-variable (m/aux "ManufacturingRate" "0.04"))
      (m/add-variable (m/aux "TrustGainRate" "0.06"))
      (m/add-variable (m/aux "TrustDecayRate" "0.02"))
      (m/add-variable (m/aux "ContractLawRate" "0.04"))
      (m/add-variable (m/aux "LandAcquisitionRate" "0.02"))
      (m/add-variable (m/aux "EducationRate" "0.05"))
      (m/add-variable (m/aux "DisputeResolutionRate" "0.03"))
      (m/add-variable (m/aux "AttestationRate" "0.04"))

      (m/add-variable (m/aux "LegalActivationGate" "0.05"))
      (m/add-variable (m/aux "RealLLMWiringGate" "0.1"))
      (m/add-variable (m/aux "G7ConsentGate" "0.05"))
      (m/add-variable (m/aux "DispatchGate" "0.0"))
      (m/add-variable (m/aux "ConstitutionRatificationGate" "0.1"))
      (m/add-variable (m/aux "LandAcquisitionGate" "0.05"))
      (m/add-variable (m/aux "CurriculumGate" "0.15"))
      (m/add-variable (m/aux "AdjudicationAuthorityGate" "0.05"))
      (m/add-variable (m/aux "AttestationProtocolGate" "0.05"))

      (m/add-variable (m/aux "ComplianceFloor" "1"))
      (m/add-variable (m/aux "RegulatoryCap" "100"))))
