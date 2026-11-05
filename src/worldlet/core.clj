(ns worldlet.core
  "A small, deterministic interpreter for worlds described as data.")

(defn- fail [message data]
  (throw (ex-info message data)))

(defn- scalar? [x]
  (or (nil? x) (boolean? x) (string? x) (keyword? x) (integer? x)))

(defn- fact-map? [x]
  (and (map? x) (every? keyword? (keys x)) (every? scalar? (vals x))))

(defn- predicate-valid? [facts p]
  (and (vector? p) (= 3 (count p))
       (let [[op fact value] p]
         (and (contains? #{:= :not= :< :<= :> :>=} op)
              (keyword? fact) (contains? facts fact) (scalar? value)
              (or (#{:= :not=} op)
                  (and (integer? (get facts fact)) (integer? value)))))))

(defn- validate [world]
  (let [{:keys [tick facts rules]} world]
    (when-not (and (map? world) (integer? tick) (<= 0 tick)
                   (fact-map? facts) (vector? rules))
      (fail "Expected a world with nonnegative :tick, scalar :facts and vector :rules"
            {:world world}))
    (let [ids (mapv :id rules)
          counts (frequencies ids)
          duplicates (filterv #(> (get counts %) 1) (distinct ids))]
      (when (seq duplicates)
        (fail "Rule ids must be unique" {:rule-ids duplicates})))
    (doseq [[index rule] (map-indexed vector rules)]
      (let [{:keys [id when set add] :or {set {} add {}}} rule]
        (when-not (and (map? rule) (keyword? id)
                       (every? #{:id :when :set :add} (keys rule))
                       (vector? when) (every? #(predicate-valid? facts %) when)
                       (fact-map? set) (map? add)
                       (seq (concat (keys set) (keys add)))
                       (every? #(contains? facts %) (concat (keys set) (keys add)))
                       (every? (fn [[k v]] (and (integer? v) (integer? (get facts k)))) add)
                       (not-any? #(contains? set %) (keys add)))
          (fail "Invalid rule: check predicates, existing fact keys and effects"
                {:rule rule :rule-index index})))))
  world)

(defn world
  "Create a world at tick zero. Facts are a flat keyword map of EDN scalars.
  Rules are an ordered vector of {:id :when :set :add} maps."
  [facts rules]
  (validate {:tick 0 :facts facts :rules rules}))

(defn- holds? [facts [op fact value]]
  (let [actual (get facts fact)]
    (case op
      := (= actual value)
      :not= (not= actual value)
      :< (< actual value)
      :<= (<= actual value)
      :> (> actual value)
      :>= (>= actual value))))

(defn step
  "Evaluate all rules against the same snapshot, then apply their effects.
  Two active rules writing the same fact are an error, even if values agree.
  Return {:world next-world :fired [rule-ids...]}. No input is mutated."
  [input]
  (validate input)
  (let [{:keys [facts rules]} input
        active (filterv #(every? (partial holds? facts) (:when %)) rules)
        writes (mapcat (fn [rule]
                         (concat (map (fn [[k v]] [k v (:id rule)]) (:set rule))
                                 (map (fn [[k n]] [k (+' (get facts k) n) (:id rule)])
                                      (:add rule)))) active)
        next-facts
        (:facts (reduce (fn [{:keys [facts owners]} [k value id]]
                          (when (contains? owners k)
                            (fail "Conflicting writes in one tick"
                                  {:fact k :rules [(get owners k) id]}))
                          {:facts (assoc facts k value) :owners (assoc owners k id)})
                        {:facts facts :owners {}} writes))
        next-world (assoc input :tick (inc' (:tick input)) :facts next-facts)]
    ;; Reject effects that would invalidate a numeric rule on the next tick.
    (validate next-world)
    {:world next-world :fired (mapv :id active)}))

(defn advance
  "Return n step results as a vector, excluding the initial world."
  [input n]
  (when-not (and (integer? n) (<= 0 n))
    (fail "Step count must be a nonnegative integer" {:steps n}))
  (validate input)
  (loop [current input left n history []]
    (if (zero? left)
      history
      (let [result (step current)]
        (recur (:world result) (dec left) (conj history result))))))
