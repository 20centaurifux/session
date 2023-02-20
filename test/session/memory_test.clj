(ns session.memory-test
  (:require [clojure.test :refer :all]
            [clojure.spec.test.alpha :refer [instrument]]
            [session.core-test :refer [check-session-container
                                       check-session-container-events
                                       check-session-cleaner]]
            [session.memory :refer [make-memory-session-container]]))

(instrument)

(deftest memory-session-container
  (check-session-container make-memory-session-container)
  (check-session-container-events make-memory-session-container)
  (check-session-cleaner make-memory-session-container))
