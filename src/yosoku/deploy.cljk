(ns yosoku.deploy
  "Deploy entrypoint — wires a REAL Murakumo-fleet LLM (langchain.model
  OpenAI-compatible against the local Ollama, gemma-4-E4B) into the SD-Advisor
  and runs ONE `:scenario/advise` proposal against `etzhayyim-substrate-
  rollout` end to end (advise -> ScenarioGovernor -> commit/hold/escalate).

  Same shape as `tashikame.deploy`/`kouhou.deploy`: this only proves the
  real-LLM -> governor path against the live Murakumo model. yosoku has no
  publish rail (it is a simulation actor, not a publisher) — a committed
  proposal here writes the in-process MemStore + audit ledger, nothing
  external.

  Usage: clojure -M:dev -m yosoku.deploy \"<intent>\" [model-id]
  Env:   YOSOKU_OLLAMA_URL (default http://127.0.0.1:11434)
         YOSOKU_OLLAMA_MODEL (default gemma-4-E4B qat)"
  (:require [json.data-json :as json]
            [langchain.model :as model]
            [langgraph.graph :as g]
            [yosoku.advisor :as advisor]
            [yosoku.models :as models]
            [yosoku.store :as store]
            [yosoku.operation :as op]
            [kotoba.net.jvm-host :as net-host])
  (:gen-class))

(def ^:private default-ollama-url
  (or (System/getenv "YOSOKU_OLLAMA_URL") "http://127.0.0.1:11434"))

(def ^:private default-ollama-model
  (or (System/getenv "YOSOKU_OLLAMA_MODEL")
      "hf.co/unsloth/gemma-4-E4B-it-qat-GGUF:UD-Q4_K_XL"))

(def ^:private transport!
  "One shared kotoba.net JVM transport. `kotoba.net.jvm-host` is the single
  place allowed to touch java.net.http, so this namespace holds no interop;
  the delay keeps one pooled HttpClient instead of one per request."
  (delay (net-host/http-transport {:timeout-seconds 120})))

(defn jvm-http-fn
  "langchain.model :http-fn backed by kotoba.net's JVM host transport.
  Same contract as before ({:url :method :headers :body} -> {:status :body});
  the method still defaults to :post, since jvm-host fails closed on nil."
  [{:keys [url method headers body]}]
  (@transport! {:url url
                :method (or method :post)
                :headers headers
                :body body}))

(defn ollama-chat-model
  "Build a langchain.model/openai-model against a Murakumo-fleet Ollama.
  Refuses non-Murakumo hosts (Rider v3.3 §2(i))."
  ([]
   (ollama-chat-model default-ollama-url default-ollama-model))
  ([ollama-url ollama-model]
   (advisor/assert-murakumo! ollama-url)
   (model/openai-model
    {:url        (str ollama-url "/v1/chat/completions")
     :model      ollama-model
     :api-key    nil
     :http-fn    jvm-http-fn
     :json-write json/write-str
     :json-read  #(json/read-str % :key-fn keyword)})))

(defn -main
  [& args]
  (let [[intent model-id] (if (seq args) args
                               ["cautiously widen mimamori consent coverage"
                                "etzhayyim-substrate-rollout"])
        chat  (ollama-chat-model)
        ;; 256 is too tight for a "thinking" model (gemma4:e4b-it-qat emits a
        ;; separate :reasoning field before :content and can burn the whole
        ;; budget there, leaving :content empty -- verified live 2026-07-13
        ;; against the com-junkawasaki tailnet fleet).
        adv   (advisor/llm-advisor chat {:max-tokens 1024})
        s     (store/mem-store {model-id (models/etzhayyim-substrate-rollout)})
        actor (op/build s {:advisor adv})
        tid   "deploy-1"
        req   {:op :scenario/advise :model-id model-id :intent intent}
        r     (g/run* actor {:request req :context {:actor-id "yosoku"}} {:thread-id tid})]
    (println "=== yosoku deploy (real LLM @ Murakumo) ===")
    (println "model-id   :" model-id)
    (println "intent     :" intent)
    (println "disposition:" (get-in r [:state :disposition]))
    (println "ledger tail:" (pr-str (last (store/ledger s))))))
