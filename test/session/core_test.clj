(ns session.core-test
  (:require [clojure.test :refer :all]
            [clojure.spec.test.alpha :refer [instrument]]
            [clojure.spec.alpha :as s]
            [clojure.core.async :as a]
            [session.core :as c]))

(instrument)

;; session test
(deftest session-state
  (with-redefs [c/minimum-lifetime 1]
    (testing "create id"
      (is (s/valid? :session.core/id (c/make-id))))

    (testing "create state"
      (let [s (c/make-state 5)]
        (is (s/valid? :session.core/state s))
        (is (= (:session.core/lifetime s) 5))))

    (testing "alive?"
      (let [s (c/make-state 5)]
        (is (c/alive? s))
        (testing "not alive?"
          (Thread/sleep 5)
          (is (false? (c/alive? s))))))

    (testing "not expired?"
      (let [s (c/make-state 5)]
        (is (false? (c/expired? s)))
        (testing "expired?"
          (Thread/sleep 5)
          (is (c/expired? s)))))

    (testing "few milliseconds left"
      (let [s (c/make-state 5)]
        (is (and (>= (c/time-to-live s) 1) (<= (c/time-to-live s) 5)))
        (testing "no milliseconds left"
          (Thread/sleep 5)
          (is (zero? (c/time-to-live s))))))

    (testing "keep alive"
      (let [s (c/make-state 50)]
        (Thread/sleep 25)
        (let [s' (c/keep-alive s)]
          (Thread/sleep 25)
          (is (c/expired? s))
          (is (c/alive? s')))))

    (testing "update session"
      (let [s (c/make-state 5)]
        (let [s' (c/with-state-> s (assoc :foo :bar))]
          (is (#{:bar} (:foo s')))
          (testing "update expired session"
            (Thread/sleep 5)
            (let [s'' (c/with-state-> s (assoc :baz 23))]
              (is (nil? s'')))))))))

;; session container
(defmacro ^:private with-container
  [bindings & body]
  `(let ~bindings
     (do ~@body)
     (c/dispose! ~(first bindings))))

(defn ^:private test-expired-session
  [c lifetime f]
  (let [r (c/new-session! c lifetime)]
    (Thread/sleep (+ lifetime 5))
    (f (:id r))))

(s/def ::created (s/and :session.core/container-event
                        #(#{:created} (:event %))))

(s/def ::updated (s/and :session.core/container-event
                        #(#{:updated} (:event %))))

(s/def ::removed (s/and :session.core/container-event
                        #(#{:removed} (:event %))))

(defn check-session-container
  [f]
  (testing "create & dispose container"
    (let [c (f)]
      (is (s/valid? :session.core/container c))
      (is (nil? (c/dispose! c)))))

  (testing "create session"
    (with-container [c (f)]
      (let [r (c/new-session! c c/minimum-lifetime)]
        (is (s/valid? :session.core/id-and-state r))
        (is (= (:session.core/lifetime (:state r)) c/minimum-lifetime))
        (is (and (<= (c/time-to-live (:state r)) c/minimum-lifetime)
                 (>= (c/time-to-live (:state r)) (- c/minimum-lifetime 10)))))))

  (testing "remove session"
    (with-container [c (f)
                     r (c/new-session! c c/minimum-lifetime)
                     s (c/remove-session! c (:id r))]
      (is (s/valid? :session.core/state s))
      (is (= (:session.core/lifetime s) c/minimum-lifetime))
      (is (and (<= (c/time-to-live (:state r)) c/minimum-lifetime)
               (>= (c/time-to-live (:state r)) (- c/minimum-lifetime 10))))))

  (testing "remove non-existing session"
    (with-container [c (f)]
      (is (nil? (c/remove-session! c (c/make-id))))))

  (testing "remove expired session"
    (with-redefs [c/minimum-lifetime 0]
      (with-container [c (f)]
        (test-expired-session c 0 #(is (nil? (c/remove-session! c %)))))))

  (testing "remove expired sessions"
    (with-redefs [c/minimum-lifetime 0]
      (with-container [c (f)
                       r (c/new-session! c 0)
                       rx (c/remove-expired-sessions! c)]
        (is (= (count rx) 1))
        (is (= (first rx) r)))))

  (testing "merge session state"
    (with-container [c (f)
                     r (c/new-session! c c/minimum-lifetime)]
      (Thread/sleep 5)
      (let [s (c/merge! c (:id r) {:foo "bar" :hello "world"})
            s' ((:id r) c)]
        (is (s/valid? :session.core/state s))
        (is (= (:session.core/created s)
               (get-in r [:state :session.core/created])))
        (is (= (:session.core/lifetime s)
               (get-in r [:state :session.core/lifetime])))
        (is (> (:session.core/touched s)
               (get-in r [:state :session.core/touched])))
        (is (= s s'))
        (is (= (:foo s) "bar"))
        (is (= (:hello s) "world")))))

  (testing "merge with non-existing session"
    (with-container [c (f)]
      (is (nil? (c/merge! c (c/make-id) {})))))

  (testing "merge expired session"
    (with-redefs [c/minimum-lifetime 0]
      (with-container [c (f)]
        (test-expired-session c 0 #(is (nil? (c/merge! c % {})))))))

  (testing "remove keys"
    (with-container [c (f)
                     r (c/new-session! c c/minimum-lifetime)
                     s (c/merge! c (:id r) {:foo 1 :bar 2 :baz 3})]
      (Thread/sleep 5)
      (let [s' (c/remove-keys! c (:id r) [:foo :bar])
            s'' ((:id r) c)]
        (is (s/valid? :session.core/state s'))
        (is (= (:session.core/created s')
               (get-in r [:state :session.core/created])))
        (is (= (:session.core/lifetime s')
               (get-in r [:state :session.core/lifetime])))
        (is (> (:session.core/touched s')
               (get-in r [:state :session.core/touched])))
        (is (= s' s''))
        (is (nil? (:foo s')))
        (is (nil? (:bar s')))
        (is (= (:baz s') 3)))))

  (testing "remove keys from non-existing session"
    (with-container [c (f)]
      (is (nil? (c/remove-keys! c (c/make-id) [])))))

  (testing "remove keys from expired session"
    (with-redefs [c/minimum-lifetime 0]
      (with-container [c (f)]
        (test-expired-session c 0 #(is (nil? (c/remove-keys! c % [])))))))

  (testing "keep alive"
    (with-container [c (f)
                     r (c/new-session! c c/minimum-lifetime)]
      (Thread/sleep 50)
      (let [s (c/keep-alive! c (:id r))]
        (is (s/valid? :session.core/state s))
        (is (= (:session.core/created s)
               (get-in r [:state :session.core/created])))
        (is (= (:session.core/lifetime s)
               (get-in r [:state :session.core/lifetime])))
        (is (> (:session.core/touched s)
               (get-in r [:state :session.core/touched]))))))

  (testing "keep non-existing session alive"
    (with-container [c (f)]
      (is (nil? (c/keep-alive! c (c/make-id))))))

  (testing "keep expired session alive"
    (with-redefs [c/minimum-lifetime 0]
      (with-container [c (f)]
        (test-expired-session c 0 #(is (nil? (c/keep-alive! c %)))))))

  (testing "transduce"
    (with-container [c (f)
                     r (c/new-session! c c/minimum-lifetime)
                     sx (c/transduce (map identity) conj [] c)]
      (is (= (count sx) 1))
      (let [m (first sx)]
        (s/valid? :session.core/id-and-state m)
        (is (= m r)))))

  (testing "transduce ignores expired sessions"
    (with-redefs [c/minimum-lifetime 0]
      (with-container [c (f)
                       r (c/new-session! c 30000)
                       r' (c/new-session! c 0)
                       sx (c/transduce (map identity) conj [] c)]
        (is (= (count sx) 1))
        (let [m (first sx)]
          (s/valid? :session.core/id-and-state m)
          (is (= m r)))))))

(defn check-session-container-events
  [f]
  (testing "subscribe! and unsubscribe! return nil"
    (with-container [c (f)
                     ch (a/chan)]
      (is (nil? (c/subscribe! c :created ch)))
      (is (nil? (c/unsubscribe! c :created ch)))))

  (testing "new-session! produces :created event"
    (with-container [c (f)
                     ch (a/chan)
                     _ (c/subscribe! c :created ch)
                     r (c/new-session! c c/minimum-lifetime)
                     [ev _] (a/alts!! [ch (a/timeout 500)])]
      (is (s/valid? ::created ev))
      (is (= (:id r) (:id ev)))
      (is (= ((:id r) c) (:state ev)))))

  (testing "remove-session! produces :removed event"
    (with-container [c (f)
                     ch (a/chan)
                     _ (c/subscribe! c :removed ch)
                     r (c/new-session! c c/minimum-lifetime)
                     s (c/remove-session! c (:id r))
                     [ev _] (a/alts!! [ch (a/timeout 500)])]
      (is (s/valid? ::removed ev))
      (is (= (:id r) (:id ev)))
      (is (= (s (:state ev))))))

  (testing "remove expired sessions produces :removed event"
    (with-redefs [c/minimum-lifetime 0]
      (with-container [c (f)
                       ch (a/chan)
                       _ (c/subscribe! c :removed ch)
                       r (c/new-session! c 0)
                       rx (c/remove-expired-sessions! c)
                       [ev _] (a/alts!! [ch (a/timeout 500)])]
        (is (s/valid? ::removed ev))
        (is (= (:id r) (:id ev)))
        (is (= ((:state r) (:state ev)))))))

  (testing "merge! produces :updated event"
    (with-container [c (f)
                     ch (a/chan)
                     _ (c/subscribe! c :updated ch)
                     r (c/new-session! c c/minimum-lifetime)
                     s (c/merge! c (:id r) {:hello "world"})
                     [ev _] (a/alts!! [ch (a/timeout 500)])]
      (is (s/valid? ::updated ev))
      (is (= (:id r) (:id ev)))
      (is (= (s (:state ev))))))

  (testing "remove-keys! produces :updated event"
    (with-container [c (f)
                     ch (a/chan)
                     _ (c/subscribe! c :updated ch)
                     r (c/new-session! c c/minimum-lifetime)
                     s (c/merge! c (:id r) {:hello "world"})
                     s' (c/remove-keys! c (:id r) [:hello])]
      (let [[ev _] (a/alts!! [ch (a/timeout 500)])]
        (is (s/valid? ::updated ev))
        (is (= (:id r) (:id ev)))
        (is (= (s (:state ev)))))

      (let [[ev _] (a/alts!! [ch (a/timeout 500)])]
        (is (#{:updated} (:event ev)))
        (is (= (:id r) (:id ev)))
        (is (= (s' (:state ev)))))))

  (testing "keep-alive produces :updated event"
    (with-container [c (f)
                     ch (a/chan)
                     _ (c/subscribe! c :updated ch)
                     r (c/new-session! c c/minimum-lifetime)
                     s (c/keep-alive! c (:id r))
                     [ev _] (a/alts!! [ch (a/timeout 500)])]
      (is (s/valid? ::updated ev))
      (is (= (:id r) (:id ev)))
      (is (= (s (:state ev))))))

  (testing "no event received after unsubscribe!"
    (with-container [c (f)
                     ch (a/chan)
                     _ (c/subscribe! c :created ch)
                     _ (c/subscribe! c :updated ch)
                     _ (c/unsubscribe! c :created ch)
                     r (c/new-session! c c/minimum-lifetime)
                     s (c/keep-alive! c (:id r))]
      (let [[ev _] (a/alts!! [ch (a/timeout 500)])]
        (is (s/valid? ::updated ev))
        (is (= (:id r) (:id ev)))
        (is (= (s (:state ev))))))))

(defn check-session-cleaner
  [f]
  (with-redefs [c/minimum-lifetime 0]
    (with-container [c (f)
                     ch (a/chan)
                     _ (c/subscribe! c :removed ch)
                     _ (a/thread (c/start-session-cleaner c))
                     r (c/new-session! c 1000)]
      (testing "no session removed yet"
        (let [[_ ch'] (a/alts!! [ch (a/timeout 500)])]
          (is (not= ch' ch)))
        (Thread/sleep 500)
        (let [[ev ch'] (a/alts!! [ch (a/timeout 500)])]
          (is (= ch' ch))
          (is (s/valid? ::removed ev))
          (is (= (:id r) (:id ev)))
          (is (= ((:state r) (:state ev)))))))))
