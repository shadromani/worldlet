(ns worldlet.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [worldlet.core :as w]
            [worldlet.demo :as demo]))

(deftest a-small-impossible-world
  (let [initial (demo/staircase)
        history (w/advance initial 7)]
    (is (= [0 0 0 1 2 3 3] (mapv #(get-in % [:world :facts :stairs]) history)))
    ;; Rule order in the trace follows the source, not causal order.
    (is (= [:clock :stairs-dream :moon-wakes] (:fired (nth history 5))))
    (is (= history (w/advance initial 7)))
    (is (= {:hour 0 :moon :awake :stairs 0} (:facts initial)))))

(deftest rules-read-a-shared-snapshot
  (let [initial (w/world {:door :closed :visits 0}
                         [{:id :open :when [] :set {:door :open}}
                          {:id :enter :when [[:= :door :open]] :add {:visits 1}}])
        once (:world (w/step initial))]
    (is (= 0 (get-in once [:facts :visits])))
    (is (= 1 (get-in (w/step once) [:world :facts :visits])))))

(deftest conflicting-writes-are-not-order-dependent
  (let [rules [{:id :one :when [] :set {:x 1}}
               {:id :two :when [] :add {:x 1}}]]
    (doseq [rs [rules (vec (reverse rules))]]
      (try
        (w/step (w/world {:x 0} rs))
        (is false "Expected a conflicting write")
        (catch clojure.lang.ExceptionInfo e
          (is (= :x (:fact (ex-data e))))
          (is (= #{:one :two} (set (:rules (ex-data e))))))))))

(deftest inactive-writers-do-not-conflict
  (is (= 1 (get-in (w/step (w/world {:x 0}
                                     [{:id :yes :when [[:= :x 0]] :set {:x 1}}
                                      {:id :no :when [[:= :x 1]] :set {:x 2}}]))
                   [:world :facts :x]))))

(deftest independent-rules-commute
  (let [rules [{:id :left :when [] :add {:x 1}}
               {:id :right :when [[:< :x 3]] :add {:y 2}}]
        run (fn [rs] (mapv #(get-in % [:world :facts])
                           (w/advance (w/world {:x 0 :y 0} rs) 5)))]
    (is (= (run rules) (run (vec (reverse rules)))))
    (is (= {:x 5 :y 6} (last (run rules))))))

(deftest predicate-language
  (doseq [[op expected] [[:= false] [:not= true] [:< true] [:<= true] [:> false] [:>= false]]]
    (is (= (if expected [:match] [])
           (:fired (w/step (w/world {:x 2 :matched false}
                                    [{:id :match :when [[op :x 3]]
                                      :set {:matched true}}]))))))
  (is (= [] (:fired (w/step (w/world {:x 2}
                                     [{:id :both :when [[:= :x 2] [:> :x 3]]
                                       :set {:x 9}}]))))))

(deftest invalid-data-is-rejected
  (doseq [rule [{:id :bad :when [[:eval :x 0]] :set {:x 1}}
                {:id :bad :when [[:= :missing 0]] :set {:x 1}}
                {:id :bad :when [] :set {:missing 1}}
                {:id :bad :when [] :add {:x "one"}}
                {:id :bad :when [] :set {:x 1} :add {:x 2}}
                {:id :bad :when [] :set {:x 1} :typo true}
                {:id :bad :when []}]]
    (is (thrown? clojure.lang.ExceptionInfo (w/world {:x 0} [rule]))))
  (is (thrown? clojure.lang.ExceptionInfo
               (w/world {:x 0} [{:id :same :when [] :set {:x 1}}
                                {:id :same :when [] :set {:x 2}}])))
  (is (thrown? clojure.lang.ExceptionInfo (w/world {:x (fn [] 1)} [])))
  (is (thrown? clojure.lang.ExceptionInfo
               (w/step (w/world {:x 0} [{:id :change :when [[:< :x 1]] :set {:x :oops}}])))))

(deftest validation-errors-identify-the-offending-rules
  (testing "duplicate IDs are reported once, in first appearance order"
    (let [rule (fn [id] {:id id :when [] :set {:x 1}})]
      (try
        (w/world {:x 0} (mapv rule [:second :first :second :first :second]))
        (is false "Expected duplicate rule IDs to be rejected")
        (catch clojure.lang.ExceptionInfo e
          (is (= [:second :first] (:rule-ids (ex-data e))))))))
  (testing "invalid rules include their zero-based position, even when inactive"
    (let [valid {:id :valid :when [] :set {:x 1}}
          invalid {:id :invalid :when [[:= :x 99]] :add {:x "one"}}
          input {:tick 0 :facts {:x 0} :rules [valid invalid]}]
      (doseq [run [#(w/world (:facts input) (:rules input))
                   #(w/step input)
                   #(w/advance input 0)]]
        (try
          (run)
          (is false "Expected the inactive invalid rule to be rejected")
          (catch clojure.lang.ExceptionInfo e
            (is (= 1 (:rule-index (ex-data e))))
            (is (= invalid (:rule (ex-data e))))))))))

(deftest boundaries
  (let [empty-world (w/world {} [])]
    (is (= {:tick 1 :facts {} :rules []} (:world (w/step empty-world))))
    (is (= [] (w/advance empty-world 0)))
    (doseq [n [-1 1.5 nil]]
      (is (thrown? clojure.lang.ExceptionInfo (w/advance empty-world n)))))
  (is (= 9223372036854775808N
         (get-in (w/step (w/world {:x Long/MAX_VALUE}
                                  [{:id :grow :when [] :add {:x 1}}]))
                 [:world :facts :x]))))

(deftest saved-worlds-support-independent-continuations
  (let [initial (demo/staircase)
        history (w/advance initial 3)
        saved (:world (last history))
        waking-early (assoc-in saved [:facts :moon] :awake)
        asleep-history (w/advance saved 2)
        awake-history (w/advance waking-early 2)
        stairs (fn [results] (mapv #(get-in % [:world :facts :stairs]) results))]
    (is (= [1 2] (stairs asleep-history)))
    (is (= [0 0] (stairs awake-history)))
    (is (= [4 5] (mapv #(get-in % [:world :tick]) awake-history)))
    (is (= 3 (:tick waking-early)))
    (is (= (:rules saved) (:rules waking-early)))
    (is (= {:hour 3 :moon :asleep :stairs 0} (:facts saved)))
    (is (= history (w/advance initial 3)))
    (is (= asleep-history (w/advance saved 2)))
    (testing "edited branches are still validated, even for zero steps"
      (is (thrown? clojure.lang.ExceptionInfo
                   (w/advance (assoc-in saved [:facts :stairs] :invalid) 0))))))
