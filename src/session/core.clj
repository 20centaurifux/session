(ns session.core
  (:require [clojure.spec.alpha :as s]
            [clojure.core.async :as a]))

(def minimum-lifetime
  "Minimum session lifetime in milliseconds."
  30000)

(def maximum-lifetime
  "Maximum session lifetime in milliseconds."
  (* 60000 60 24))

(defn now
  "Returns Unix time in milliseconds (UTC)."
  []
  (.toEpochMilli (java.time.Instant/now)))

(defn- posint?
  [n]
  (and (int? n) (pos? n)))

;; session id
(s/def ::id (s/and keyword #(parse-uuid (name %))))

(s/fdef make-id
  :ret ::id)

(defn make-id
  "Generates a unique session id."
  []
  (keyword (.toString (java.util.UUID/randomUUID))))

;; session state
(s/def ::created posint?)
(s/def ::touched posint?)
(s/def ::lifetime (s/and int? #(>= % minimum-lifetime)))
(s/def ::state (s/keys :req [::created ::touched ::lifetime]))

(s/fdef make-state
  :args (s/cat :state ::lifetime)
  :ret ::state)

(defn make-state
  "Creates new session state expiring in `lifetime` milliseconds."
  [lifetime]
  (let [timestamp (now)]
    {::created timestamp
     ::touched timestamp
     ::lifetime lifetime}))

(s/fdef alive?
  :args (s/cat :state ::state)
  :ret boolean?)

(defn alive?
  "Tests if session state is not expired."
  [state]
  (> (+ (::touched state) (::lifetime state)) (now)))

(s/fdef expired?
  :args (s/cat :state ::state)
  :ret boolean?)

(defn expired?
  "Tests if session state is expired."
  [state]
  (not (alive? state)))

(s/def ::timestamp posint?)

(s/fdef time-to-live
  :args (s/cat :state ::state :date (s/? ::timestamp))
  :ret ::timestamp)

(defn time-to-live
  "Milliseconds until `state` expires.
  Compares end-of life-to `session.core/now` if `date` is not specified."
  ([state date]
   (let [eol (+ (::touched state) (::lifetime state))]
     (if (> eol date)
       (- eol date)
       0)))
  ([state]
   (time-to-live state (now))))

(s/fdef keep-alive
  :args (s/cat :state ::state)
  :ret ::state)

(defn keep-alive
  "Updates access time."
  [state]
  (assoc state ::touched (now)))

(defmacro with-state->
  "Tests if `state`is unexpired.
  If true, state is threaded trough the `forms`. Returns result or nil."
  [state & forms]
  (let [statesym (gensym)]
    `(let [~statesym ~state]
       (if (and ~statesym (alive? ~statesym))
         ~(loop [state' statesym
                 forms' forms]
            (if forms'
              (let [form (first forms')
                    threaded (if (seq? form)
                               `(~(first form) ~state' ~@(next form))
                               (list form state'))]
                (recur threaded (next forms')))
              state'))))))

;; session container
(defprotocol MutableSessionContainer
  "Mutable container for sessions."
  (-dispose! [this])

  (-new-session! [this lifetime])

  (-remove-session! [this id])

  (-remove-expired-sessions! [this])

  (-merge! [this id m])

  (-remove-keys!  [this id keys])

  (-keep-alive! [this id])

  (-subscribe! [this event ch])

  (-unsubscribe! [this event ch])

  (-transduce [this xform f init]))

(s/def ::container #(satisfies? MutableSessionContainer %))

(s/fdef dispose!
  :args (s/cat :container ::container)
  :ret nil?)

(defn dispose!
  "Releases resources and stops asynchronous tasks."
  [container]
  (-dispose! container))

(s/def ::id-and-state (s/keys :req-un [::id :session.core/state]))

(s/fdef new-session!
  :args (s/cat :container ::container :lifetime ::lifetime)
  :ret ::id-and-state)

(defn new-session!
  "Creates a session.
  Returns new map containing session id and state."
  [container lifetime]
  (-new-session! container lifetime))

(s/fdef remove-session!
  :args (s/cat :container ::container :id ::id)
  :ret (s/or ::state nil?))

(defn remove-session!
  "Tests if unexpired session with associated `id` exists.
  If true, removes session and returns its state."
  [container id]
  (-remove-session! container id))

(s/fdef remove-expired-sessions!
  :args (s/cat :container ::container)
  :ret (s/coll-of ::id-and-state))

(defn remove-expired-sessions!
  "Removes and returns expired sessions from `container`."
  [container]
  (-remove-expired-sessions! container))

(s/fdef merge!
  :args (s/cat :container ::container :id ::id :m (s/map-of keyword? any?))
  :ret (s/or ::state nil?))

(defn merge!
  "Tests if unexpired session with associated `id` exists.
  If true, session state is
  merged with m and last access time is updated. Returns new state or nil."
  [container id m]
  (-merge! container id m))

(s/fdef remove-keys!
  :args (s/cat :container ::container :id ::id :keys (s/coll-of keyword?))
  :ret (s/or ::state nil?))

(defn remove-keys!
  "Tests if unexpired session with associated `id` exists.
  If true, keys are removed from session state and last access time is updated.
  Returns new state or nil."
  [container id keys]
  (-remove-keys! container id keys))

(s/fdef keep-alive!
  :args (s/cat :container ::container :id ::id)
  :ret (s/or ::state nil?))

(defn keep-alive!
  "Tests is unexpired session with associated `id` exists.
  If true, last access time is updated. Returns new state or nil."
  [container id]
  (-keep-alive! container id))

(s/fdef transduce
  s/args (s/cat :xform fn? :f fn? :init any? :container ::container))

(defn transduce
  "Reduces unexpired session states with a transformation of (xform f)."
  [xform f init container]
  (-transduce container xform f init))

(s/def ::write-port #(satisfies? clojure.core.async.impl.protocols/WritePort %))

(s/def ::event #{:created :removed :updated})

(s/def ::container-event (s/keys :req-un [::event ::state]))

(s/fdef subscribe!
  :args (s/cat :container ::container :event ::event :ch ::write-port)
  :ret nil?)

(defn subscribe!
  "Subscribes `ch` to an `event`"
  [container event ch]
  (-subscribe! container event ch))

(s/fdef unsubscribe!
  :args (s/cat :container ::container :event ::event :ch ::write-port)
  :ret nil?)

(defn unsubscribe!
  "Unsubscribes `ch` from an `event`"
  [container event ch]
  (-unsubscribe! container event ch))

(defn start-session-cleaner
  "Starts a thread calling `session.core/remove-expired-sessions!`
  automatically. Exits when `container` disposes."
  [container]
  (let [ch (a/chan)]
    (subscribe! container :created ch)
    (loop [millis 0]
      (let [[ev ch'] (a/alts!! [ch (a/timeout millis)])]
        (when (or ev (not= ch' ch))
          (remove-expired-sessions! container)
          (recur (transduce (comp
                             (map :state)
                             (map time-to-live))
                            min
                            maximum-lifetime
                            container)))))))
