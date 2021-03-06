(ns examples.cycle.bmi-naive
  (:require [aid.core :as aid]
            [frp.core :as frp]))

(frp/defe weight-event height-event)

(def weight-behavior
  (frp/stepper 70 weight-event))

(def height-behavior
  (frp/stepper 170 height-event))

(def bmi
  (-> weight-behavior
      (/ (js/Math.pow (/ height-behavior 100) 2))
      int
      frp/transparent))

(aid/defcurried measurement-component
  [m value]
  [:div (str (:label m) value (:unit m))
   [:input (merge m {:on-change #(->> %
                                      .-target.value
                                      ((:event m)))
                     :type      "range"
                     :value     value})]])

(def weight-component
  (measurement-component {:event weight-event
                          :label "Weight "
                          :max   140
                          :min   40
                          :unit  "kg"}))

(def height-component
  (measurement-component {:event height-event
                          :label "Height "
                          :max   210
                          :min   140
                          :unit  "cm"}))

(def bmi-component
  (partial vector :h2 "BMI is "))

(def bmi-naive
  (frp/transparent (vector :div
                           (weight-component weight-behavior)
                           (height-component height-behavior)
                           (bmi-component bmi))))
