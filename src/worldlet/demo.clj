(ns worldlet.demo
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [worldlet.core :as w]))

(defn staircase []
  (let [{:keys [facts rules]} (edn/read-string
                             (slurp (io/resource "worldlet/staircase.edn")))]
    (w/world facts rules)))

(defn- show [world]
  (let [{:keys [hour moon stairs]} (:facts world)]
    (format "%d     %-6s %d" hour (name moon) stairs)))

(defn -main [& _]
  (println "The stairs grow only while the moon sleeps.")
  (println "hour  moon   stairs")
  (let [initial (staircase)]
    (println (show initial))
    (doseq [{:keys [world]} (w/advance initial 7)]
      (println (show world)))))
