# session

*session* is a session management library for Clojure.

## Installation

The library is still under development.

## Usage

Sessions are stored in a mutable container. *session* comes with an in-memory implementation.

```
(require '[session.core :as sn])
(require '[session.memory :refer [make-memory-session-container]])

(def c (make-memory-session-container))
```

Each session has its own lifetime.

```
=> (def five-minutes (* 1000 60 5))
=> (sn/new-session! c five-minutes)
{:id :a8a01dc6-6f7f-4383-86e2-324e773264e2, :state #:session.core{:created 1676895453188, :touched 1676895453188, :lifetime 300000}}
```

Everytime you update a session the lifetime is extended.

```
clojure.core=> (sn/merge! c :a8a01dc6-6f7f-4383-86e2-324e773264e2 {:foo 23 :bar 42})
{:session.core/created 1676895453188, :session.core/touched 1676895494853, :session.core/lifetime 300000, :foo 23, :bar 42}

clojure.core=> (sn/remove-keys! c :a8a01dc6-6f7f-4383-86e2-324e773264e2 [:bar])
{:session.core/created 1676895453188, :session.core/touched 1676895523132, :session.core/lifetime 300000, :foo 23}

clojure.core=> (sn/keep-alive! c :a8a01dc6-6f7f-4383-86e2-324e773264e2)
{:session.core/created 1676895453188, :session.core/touched 1676895549262, :session.core/lifetime 300000, :foo 23}
```

To receive events (*:created*, *:updated*, *:removed*) subscribe a *clojure.core.async* channel.

```
=> (require '[clojure.core.async :as a])

=> (def receiver (a/chan))

=> (sn/subscribe! c :created receiver)

=> (sn/new-session! c sn/minimum-lifetime)

=> (a/<!! receiver)
{:event :created, :id :b5ce1f18-0e98-4ba5-861c-c784801b12b6, :state #:session.core{:created 1676895661847, :touched 1676895661847, :lifetime 30000}}
```

Session containers are an input source for transducers.

```
; find closest expiry date
(sn/transduce (comp
               (map :state)
               (map sn/time-to-live))
              min
              sn/maximum-lifetime
              c)
```

*session* comes with a routine for cleaning up expired sessions automatically.

```
(future (sn/start-session-cleaner c))

; disposing the session container will stop the cleaner thread
(sn/dispose! c)
```