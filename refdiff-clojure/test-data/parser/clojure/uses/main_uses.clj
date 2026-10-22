(ns demo.uses
  (:require [demo.lib :as lib]))

(defn target [x] x)

(defn caller [x]
  (target x))

(defn nested [x]
  (str (target (target x))))

(defn recursive [n]
  (if (zero? n)
    0
    (recursive (dec n))))

(defn multi
  ([] (multi 1))
  ([x] x)
  ([x y] (+ x y)))

(defn call-multi-2 []
  (multi 1 2))

(defn variadic-target [x & xs] x)

(defn call-variadic [x y z]
  (variadic-target x y z))

(defn call-variadic-too-few []
  (variadic-target))

(defn qualified []
  (demo.lib/other 1))

(defn aliased []
  (lib/other 1))

(defn higher-order [xs]
  (map target xs))

(defn higher-order-list [xs]
  (map (fn [x] (target x)) xs))

(defn missing []
  (not-declared 1))

(defn duplicate []
  (target 1)
  (target 2))

(defn global-only []
  (only-global 1))

(defn shadowed [target]
  (target 1))

(defn let-shadowed []
  (let [target (fn [x] x)]
    (target 1)))
