(ns demo.multi)

(defn old-fn
  ([x]
   (+ x 1))
  ([x y]
   (+ x y)))

(defn keep-a [x]
  (* x 2))

(defn keep-b [x]
  (- x 2))

(defn keep-c [x]
  (str x))

(defn keep-d [x]
  (vector x))
