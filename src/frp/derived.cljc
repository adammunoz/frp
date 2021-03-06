(ns frp.derived
  (:require [clojure.walk :as walk]
            [aid.core :as aid]
            [aid.unit :as unit]
            [cats.core :as m]
            [com.rpl.specter :as s]
            #?(:clj [riddley.walk :as riddley])
            [frp.clojure.core :as core]
            [frp.io :as io]
            [frp.primitives.behavior :as behavior]
            [frp.primitives.event :as event]
            [frp.primitives.net :as net]
            [frp.time :as time])
  #?(:cljs (:require-macros frp.derived)))

(defn event
  ([& as]
   (aid/casep as
     empty? (event/mempty)
     (->> as
          (map event/pure)
          (apply m/<>)))))

(defmacro defe
  [& names]
  `(do ~@(map (fn [x#]
                `(def ~x#
                   (event)))
              names)))

(def behavior?
  (partial instance? frp.primitives.behavior.Behavior))

(def behavior
  behavior/pure)

(def behaviorize
  (aid/if-else behavior?
               behavior))

(def eventize
  (aid/if-else event/event?
               event))

(def multiton?
  (comp (partial < 1)
        count))

(def has-argument?
  (aid/build and
             seq?
             multiton?))

(defn make-only?
  [x y]
  (aid/build and
             (partial some x)
             (complement (partial some y))))

(def event-only?
  (make-only? event/event? behavior?))

(def behavior-only?
  (make-only? behavior? event/event?))

(defn transparent*
  [f & more]
  (->> more
       (map (aid/casep more
              event-only? eventize
              behavior-only? behaviorize
              identity))
       (apply ((aid/casep more
                 (aid/build or
                            event-only?
                            behavior-only?)
                 aid/lift-a
                 identity)
                f))))

;The reader conditional avoids the following warning.
;WARNING: Use of undeclared Var clojure.walk/macroexpand-all
#?(:clj
   (defmacro transparent
     [expr]
     (->> expr
          ;TODO macroexpand expr when ClojureScript starts supporting runtime macro expansion
          ;macroexpand is only intended as a REPL utility
          ;https://cljs.github.io/api/cljs.core/macroexpand
          walk/macroexpand-all
          (walk/postwalk #(aid/casep %
                            has-argument? `(apply transparent* ~(vec %))
                            %)))))

(def accum
  (partial core/reduce (aid/flip aid/funcall)))

(def switcher
  (comp m/join
        behavior/stepper))

(def SECOND
  (s/nthpath 1))

(def set-non-action
  (partial s/setval* s/FIRST true))

(def sfirst
  (comp first
        second))

(defn get-undo-redo
  [size undo redo net]
  (->> net
       (m/<$> #(aid/if-else (comp (partial (aid/flip aid/funcall) (:occs %))
                                  set
                                  (partial map :occs)
                                  flatten
                                  rest)
                            (comp (partial s/setval* s/FIRST false)
                                  (partial s/setval* s/LAST [])
                                  (partial s/transform*
                                           SECOND
                                           (comp (partial take (inc size))
                                                 (partial s/setval*
                                                          s/BEFORE-ELEM
                                                          %))))))
       (m/<> (aid/<$ (aid/if-then (comp multiton?
                                        second)
                                  (comp set-non-action
                                        (partial s/transform*
                                                 SECOND
                                                 rest)
                                        (aid/transfer* [s/LAST s/BEFORE-ELEM]
                                                       sfirst)))
                     undo)
             (aid/<$ (aid/if-else (comp empty?
                                        last)
                                  (comp set-non-action
                                        (partial s/transform*
                                                 s/LAST
                                                 rest)
                                        (aid/transfer* [SECOND s/BEFORE-ELEM]
                                                       (comp first
                                                             last))))
                     redo))
       (accum [false [] []])
       (core/filter first)
       core/dedupe
       (m/<$> sfirst)))

(def prefix
  (gensym))

(def get-alias
  (comp symbol
        (partial str prefix)))

(def get-event-alias
  (comp (partial apply array-map)
        (partial mapcat (juxt identity
                              get-alias))))

(defn run-aciton
  [action]
  `(io/run ~(get-alias action) ~action))

(def run-actions
  (partial map run-aciton))

#?(:clj (defn alias-expression
          [actions expr]
          (->> actions
               get-event-alias
               (repeat 2)
               (s/setval s/AFTER-ELEM expr)
               (apply riddley/walk-exprs))))

(defn get-result
  [history size undo redo initial-result inner-result]
  (let [net (event)
        outer-result (event)]
    (io/run (fn [x]
              (outer-result x)
              (net @history))
            inner-result)
    (->> net
         (get-undo-redo size undo redo)
         (io/run history))
    (aid/casep inner-result
      event/event? outer-result
      ((aid/lift-a (fn [t initial-result* outer-result*]
                     (aid/case-eval t
                       time/epoch initial-result*
                       outer-result*)))
        behavior/time
        initial-result
        (behavior/stepper unit/unit outer-result)))))
;This definition may leak memory because of fmapping behavior.
;(defn get-result
;  [history size undo redo actions initial-result inner-result]
;  (let [net (event)
;        outer-result (event)]
;    (->> actions
;         (apply m/<>)
;         (aid/<$ true)
;         (m/<> (->> redo
;                    (m/<> undo)
;                    (aid/<$ false)))
;         (behavior/stepper true)
;         ((aid/casep inner-result
;            event/event? event/snapshot
;            (aid/lift-a vector))
;           inner-result)
;         (io/on (fn [[inner-result* action]]
;                  (outer-result inner-result*)
;                  (if action
;                    (net @history)))))
;    (io/on #(if (not= (:occs @history) (:occs %))
;              (history %))
;           (get-state size undo redo net))
;    (aid/casep inner-result
;      event/event? outer-result
;      (->> outer-result
;           (m/<$> behavior)
;           (switcher initial-result)))))

(aid/defcurried get-binding
  [event* action]
  [(get-alias action) event*])

(defn get-bindings
  [event* actions]
  (mapcat (get-binding event*) actions))

#?(:clj
   (defmacro undoable
     ;TODO add reset as a parameter
     ;TODO delete actions for Clojure
     ;TODO delete actions for ClojureScript when ClojureScript supports dynamic macro expansion with advanced optimizations
     ;TODO deal with the arity in a function
     ;When expr is an event, with-undo doesn't go back to the state where there is no occurrence.
     ([undo actions expr]
      `(undoable event/positive-infinity ~undo (event) ~actions ~expr))
     ([x y actions expr]
      (aid/casep x
        number? `(undoable ~x ~y (event) ~actions ~expr)
        `(undoable event/positive-infinity ~x ~y ~actions ~expr)))
     ([size undo redo actions expr]
      (potemkin/unify-gensyms
        `(let [history## (net/net)
               ~@(get-bindings `(net/with-net history##
                                              (event))
                               actions)]
           ~@(run-actions actions)
           (get-result
             history##
             ~size
             ~undo
             ~redo
             ~expr
             (net/with-net history##
                           ~(alias-expression actions expr))))))))
