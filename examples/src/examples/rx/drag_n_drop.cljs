(ns examples.rx.drag-n-drop
  (:require [aid.core :as aid]
            [cats.core :as m]
            [clojure.set :as set]
            [com.rpl.specter :as s :include-macros true]
            [frp.clojure.core :as core]
            [frp.core :as frp]
            [frp.window :as window]))

(def black "hsl(0, 0%, 0%)")

(def white "hsl(0, 0%, 100%)")

(def initialize
  (partial frp/stepper (s/setval (s/multi-path :page-x :page-y) 0 {})))

(def origin
  (->> window/dragstart
       initialize
       (frp/snapshot window/drop)
       (m/<$> (partial apply merge-with -))
       (core/reduce (partial merge-with +))
       initialize
       (m/<$> (partial (aid/flip set/rename-keys) {:page-x :left
                                                   :page-y :top}))))

(defn drag-n-drop-component
  [origin* height]
  [:div {:on-drag-over #(.preventDefault %)
         :style        {:position "absolute"
                        :top      0
                        :height   height
                        :width    "100%"}}
   [:div {:draggable true
          :style     (merge origin*
                            {:background-image    "url(/img/logo.png)"
                             :background-repeat   "no-repeat"
                             :background-position "center"
                             :background-color    black
                             :color               white
                             :height              200
                             :position            "absolute"
                             :width               200})}
    "Drag Me!"]
   [:h1 "Drag and Drop Example"]
   [:p "Example to show coordinating events to perform drag and drop"]])

(def drag-n-drop
  ((aid/lift-a drag-n-drop-component) origin window/inner-height))
