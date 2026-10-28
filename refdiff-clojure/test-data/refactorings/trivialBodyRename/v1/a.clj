(ns demo.trivial)

(defn added-fn [x]
  x)

(defn keep-a [x]
  (* x 2))

(defn keep-b [x]
  (- x 2))

(defn keep-c [x]
  (str x))

(defn keep-d [x]
  (vector x))
