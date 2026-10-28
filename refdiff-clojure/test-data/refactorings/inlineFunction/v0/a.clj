(ns demo.inline)

(defn inc-one [x]
  (+ x 1))

(defn caller [x]
  (let [y (inc-one x)]
    (println y)))
