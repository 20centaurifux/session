(ns session.memory
  (:require [session.core :refer [MutableSessionContainer
                                  make-id
                                  make-state
                                  with-state->
                                  keep-alive
                                  now
                                  time-to-live]]
            [clojure.core.async :as a]))

(defn- put-event!
  [ch event id state]
  (a/put! ch {:event event :id id :state state})
  ch)

(deftype MemorySessionContainer [sessions chan-agent pub]
  MutableSessionContainer
  (-dispose! [_]
    (await chan-agent)
    (a/close! @chan-agent))

  (-new-session! [_ lifetime]
    (let [id (make-id)]
      (dosync
       (let [state (make-state lifetime)]
         (alter sessions
                assoc
                id
                state)

         (send chan-agent
               put-event!
               :created
               id
               state)

         {:id id :state state}))))

  (-remove-session! [_ id]
    (dosync
     (when-let [state (with-state-> (get @sessions id))]
       (alter sessions
              dissoc
              id)

       (send chan-agent
             put-event!
             :removed
             id
             state)

       state)))

  (-remove-expired-sessions!
    [_]
    (dosync
     (let [timestamp (now)
           states (filter (fn [[_ v]]
                            (zero? (time-to-live v timestamp)))
                          @sessions)]
       (doseq [[id state] states]
         (alter sessions
                dissoc
                id)

         (send chan-agent
               put-event!
               :removed
               id
               state))

       (map (fn [[k v]] {:id k :state v})
            states))))

  (-merge! [_ id m]
    (dosync
     (when-let [state (with-state->
                        (get @sessions id)
                        (merge m)
                        keep-alive)]
       (alter sessions
              assoc
              id
              state)

       (send chan-agent
             put-event!
             :updated
             id
             state)

       state)))

  (-remove-keys! [_ id keys]
    (dosync
     (when-let [state (with-state->
                        (get @sessions id)
                        (#(apply dissoc % keys))
                        keep-alive)]
       (alter sessions
              assoc
              id
              state)

       (send chan-agent
             put-event!
             :updated
             id
             state)

       state)))

  (-keep-alive! [_ id]
    (dosync
     (when-let [state (with-state->
                        (get @sessions id)
                        keep-alive)]
       (alter sessions
              assoc
              id
              state)

       (send chan-agent
             put-event!
             :updated
             id
             state)

       state)))

  (-transduce [_ xform f init]
    (let [timestamp (now)]
      (transduce (comp
                  (filter (fn [[_ v]]
                            (> (time-to-live v timestamp) 0)))
                  (map (fn [[k v]]
                         {:id k :state v}))
                  xform)
                 f
                 init
                 @sessions)))

  (-subscribe! [_ event ch]
    (a/sub pub event ch)
    nil)

  (-unsubscribe! [_ event ch]
    (a/unsub pub event ch)
    nil)

  clojure.lang.ILookup
  (valAt [_ k]
    (with-state-> (get @sessions k)))

  (valAt [_ k default-val]
    (or (with-state-> (get @sessions k))
        default-val)))

(defn make-memory-session-container
  "Creates a new `session.memory.MemorySessionContainer`."
  []
  (let [ev-chan (a/chan 1)]
    (MemorySessionContainer. (ref {})
                             (agent ev-chan)
                             (a/pub ev-chan :event))))
