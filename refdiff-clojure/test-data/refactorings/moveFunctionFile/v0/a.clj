(ns demo.move)

(defn helper [x]
  (+ x 1))

(defn caller [x]
  (helper x))
