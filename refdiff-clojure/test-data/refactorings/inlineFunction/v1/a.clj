(ns demo.inline)

(defn caller [x]
  (let [y (+ x 1)]
    (println y)))
