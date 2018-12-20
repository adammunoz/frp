(ns frp.history
  (:require [frp.browser :as browser]))

(browser/defevent pushstate)

(defn push-state
  [state title url-string]
  (js/history.pushState state title url-string)
  (pushstate (browser/convert js/location)))
