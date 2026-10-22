(ns ^:no-doc demo.core
  "Namespace doc"
  {:author "test"}
  (:require [clojure.string :as str]))

(defn greet
  "Greets a person."
  [name]
  (str "Hello, " name)
  (str "Bye, " name))

(defn- hidden [x] x)

(defn multi
  "Multiple arities."
  {:added "1.0"}
  ([x] (str x))
  ([x y] (+ x y))
  ([x y & more] (apply + x y more)))

(defn destructured [{:keys [a b]} [c d] & rest]
  [a b c d rest])

(defn no-body [])

(defn ^String typed [] "value")

(defn ^{:added "2"} annotated [] nil)

^:private (defn wrapped [z] z)
