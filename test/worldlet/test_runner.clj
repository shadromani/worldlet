(ns worldlet.test-runner
  (:require [clojure.test :as t]
            [worldlet.core-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'worldlet.core-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
