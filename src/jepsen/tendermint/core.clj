(ns jepsen.tendermint.core
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.logging :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [knossos.model :as model]
            [slingshot.slingshot :refer [try+]]
            [jepsen [checker :as checker]
             [cli :as cli]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [independent :as independent]
             [nemesis :as nemesis]
             [tests :as tests]
             [util :as util :refer [timeout map-vals]]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.nemesis.time :as nt]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [cheshire.core :as json]
            [jepsen.tendermint.client :as tc]
            ))

(def base-dir "/opt/tendermint")

(defn install-component!
  "Download and install a tendermint component"
  [app opts]
  (let [base    "https://s3-us-west-2.amazonaws.com/tendermint/binaries/"
        ext     "linux_amd64.zip"
        version (get-in opts [:versions (keyword app)])
        path    (str base app "/v" version "/" app "_" version "_" ext)]
    (cu/install-archive! path (str base-dir "/" app))))

(defn gen-validator
  "Generate a new validator structure, and return the validator's data as a
  map."
  []
  (c/cd base-dir
        (-> (c/exec "./tendermint" :--home base-dir :gen_validator)
            (json/parse-string true))))

(defn gen-validator!
  "Generates a validator for the current node (or, if this validator is a dup,
  waits for the appropriate validator to be ready and clones its keys), then
  writes out a validator.json, and registers the keys in the test map."
  [test node]
  (let [validator (if-let [orig (get (:dup-validators test) node)]
                    ; Copy from orig's keys
                    (do (info node "copying" orig "validator key")
                        @(get-in test [:validators orig]))
                    ; Generate a fresh one
                    (gen-validator))]
    (deliver (get-in test [:validators node]) validator)
    (c/su
      (c/cd base-dir
            (c/exec :echo (json/generate-string validator)
                    :> "priv_validator.json")
            (info "Wrote priv_validator.json")))))

(defn gen-genesis
  "Generate a new genesis structure for a test. Blocks until all pubkeys are
  available."
  [test]
  (let [weights (:validator-weights test)]
    {:app_hash      ""
     :chain_id      "jepsen"
     :genesis_time  "0001-01-01T00:00:00.000Z"
     :validators    (->> (:validators test)
                         (reduce (fn [validators [node validator]]
                                   (let [pub-key (:pub_key @validator)]
                                     (if (some #(= pub-key (:pub_key %))
                                               validators)
                                       validators
                                       (conj validators
                                             {:amount  (get weights node)
                                              :name    node
                                              :pub_key pub-key}))))
                                 []))}))

(defn gen-genesis!
  "Generates a new genesis file and writes it to disk."
  [test]
  (c/su
    (c/cd base-dir
          (c/exec :echo (json/generate-string (gen-genesis test))
                  :> "genesis.json")
          (info "Wrote genesis.json"))))

(defn write-config!
  "Writes out a config.toml file to the current node."
  []
  (c/su
    (c/cd base-dir
          (c/exec :echo (slurp (io/resource "config.toml"))
                  :> "config.toml"))))

(defn seeds
  "Constructs a --seeds command line for a test, so a tendermint node knows
  what other nodes to talk to."
  [test node]
  (->> (:nodes test)
       (remove #{node})
       (map (fn [node] (str node ":46656")))
       (str/join ",")))

(def socket
  "The socket we use to communicate with merkleeyes"
  "unix://merkleeyes.sock")

(def merkleeyes-logfile (str base-dir "/merkleeyes.log"))
(def tendermint-logfile (str base-dir "/tendermint.log"))
(def merkleeyes-pidfile (str base-dir "/merkleeyes.pid"))
(def tendermint-pidfile (str base-dir "/tendermint.pid"))

(defn start-tendermint!
  "Starts tendermint as a daemon."
  [test node]
  (c/su
    (c/cd base-dir
          (cu/start-daemon!
            {:logfile tendermint-logfile
             :pidfile tendermint-pidfile
             :chdir   base-dir}
            "./tendermint"
            :--home base-dir
            :node
            :--proxy_app socket
            :--p2p.seeds (seeds test node)))))

(defn start-merkleeyes!
  "Starts merkleeyes as a daemon."
  []
  (c/su
    (c/cd base-dir
          (cu/start-daemon!
            {:logfile merkleeyes-logfile
             :pidfile merkleeyes-pidfile
             :chdir   base-dir}
            "./merkleeyes"
            :start
            :--dbName   "jepsen"
            :--address  socket))))

(defn stop-tendermint! []
  (c/su (cu/stop-daemon! tendermint-pidfile)))

(defn stop-merkleeyes! []
  (c/su (cu/stop-daemon! merkleeyes-pidfile)))

(defn db
  "A complete Tendermint system. Options:

  :versions         A version map, with keys...
    :tendermint     The version of tendermint to install (e.g. \"0.10.0\")
    :abci           The version ot ABCI
    :merkleeyes     The version of Merkle Eyes"
  [opts]
  (reify db/DB
    (setup! [_ test node]
      (c/su
        ; (install-component! "tendermint"  opts)
        (install-component! "abci"        opts)
        ; (install-component! "merkleeyes"  opts)
        (c/cd base-dir
              (c/exec :wget "https://s3-us-west-2.amazonaws.com/tendermint/jepsen/tendermint")
              (c/exec :chmod "+x" "tendermint")
              (c/exec :wget "https://s3-us-west-2.amazonaws.com/tendermint/jepsen/merkleeyes")
              (c/exec :chmod "+x" "merkleeyes"))

        (gen-validator! test node)
        (gen-genesis!   test)
        (write-config!)

        (start-merkleeyes!)
        (start-tendermint! test node)

        (nt/install!)

        (Thread/sleep 1000)))

    (teardown! [_ test node]
      (stop-merkleeyes!)
      (stop-tendermint!)
      (c/su
        (c/exec :rm :-rf base-dir)))

    db/LogFiles
    (log-files [_ test node]
      [tendermint-logfile
       merkleeyes-logfile
       (str base-dir "/priv_validator.json")
       (str base-dir "/genesis.json")
       ])))

(defn r   [_ _] {:type :invoke, :f :read,  :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 10)})
(defn cas [_ _] {:type :invoke, :f :cas,   :value [(rand-int 10) (rand-int 10)]})

(defn cas-register-client
  ([]
   (cas-register-client nil))
  ([node]
   (reify client/Client
     (setup! [_ test node]
       (cas-register-client node))

     (invoke! [_ test op]
       (let [[k v] (:value op)
             crash (if (= (:f op) :read)
                     :fail
                     :info)]
         (try+
           (case (:f op)
             :read  (assoc op
                           :type :ok
                           :value (independent/tuple k (tc/read node k)))
             :write (do (tc/write! node k v)
                        (assoc op :type :ok))
             :cas   (let [[v v'] v]
                      (tc/cas! node k v v')
                      (assoc op :type :ok)))

           (catch [:type :unauthorized] e
             (assoc op :type :fail, :error :precondition-failed))

           (catch [:type :base-unknown-address] e
             (assoc op :type :fail, :error :not-found))

           (catch java.net.ConnectException e
             (assoc op :type :fail, :error :connection-refused))

           (catch java.net.SocketTimeoutException e
             (assoc op :type crash, :error :timeout)))))

     (teardown! [_ test]))))


(defn set-client
  ([]
   (set-client nil))
  ([node]
   (reify client/Client
     (setup! [_ test node]
       (set-client node))

     (invoke! [_ test op]
       (let [[k v] (:value op)]
         (try+
           (case (:f op)
             ; WIP
             )))))))

(defn dup-groups
  "Takes a test with a :dup-validators map of nodes to the nodes they imitate,
  and turns that into a collection of collections of nodes, each of which is
  several nodes pretending to be the same node."
  [test]
  (let [dv (:dup-validators test)]
    (->> (:nodes test)
         (reduce (fn [index node]
                   (let [orig (get dv node node)
                         coll (get index orig #{})]
                     (assoc index orig (conj coll node))))
                 {})
         vals)))

(defn dup-validators-grudge
  "Takes a test. Returns a function which takes a collection of nodes from that
  test, and constructs a network partition (a grudge) which isolates some dups
  completely, and leaves one connected to the majority component."
  [test]
  (let [groups  (dup-groups test)
        singles (filter #(= 1 (count %)) groups)
        dups    (filter #(< 1 (count %)) groups)]
    (fn [nodes]
      ; Pick one random node from every group of dups to participate in the
      ; main component, and compute the remaining complement for each dup
      ; group.
      (let [chosen-ones (map (comp hash-set rand-nth vec) dups)
            exiles      (map remove chosen-ones dups)]
        (nemesis/complete-grudge
          (cons ; Main group
                (set (concat (apply concat singles)
                             (apply concat chosen-ones)))
                ; Exiles
                exiles))))))

(defn nemesis
  "The generator and nemesis for each nemesis profile"
  [test]
  (case (:nemesis test)
    :twofaced-validators {:nemesis (nemesis/partitioner
                                     (dup-validators-grudge test))
                          :generator (gen/start-stop 0 5)}
    :half-partitions {:nemesis   (nemesis/partition-random-halves)
                      :generator (gen/start-stop 5 30)}
    :ring-partitions {:nemesis (nemesis/partition-majorities-ring)
                      :generator (gen/start-stop 5 30)}
    :single-partitions {:nemesis (nemesis/partition-random-node)
                        :generator (gen/start-stop 5 30)}
    :clocks     {:nemesis   (nt/clock-nemesis)
                 :generator (gen/stagger 5 (nt/clock-gen))}
    :none       {:nemesis   client/noop
                 :generator gen/void}))

(defn dup-validators
  "Takes a test. Constructs a map of nodes to the nodes whose validator keys
  they use instead of their own. If a node has no entry in the map, it
  generates its own validator key."
  [test]
  (if (:dup-validators test)
    ; We need fewer than 1/3.
    (let [[orig & clones] (take (Math/floor (/ (count (:nodes test)) 3.01))
                                (:nodes test))]
      (zipmap clones (repeat orig)))))

(defn validator-weights
  "Takes a test. Computes a map of node names to voting amounts. When
  dup-validators are involved, allocates just shy of 2/3 votes to the
  duplicated key, assuming there's exactly one dup key."
  [test]
  (let [dup-vals (:dup-validators test)]
    (if (seq dup-vals)
      (let [groups  (dup-groups test)
            singles (filter #(= 1 (count %)) groups)
            dups    (filter #(< 1 (count %)) groups)
            n       (count groups)]
        (assert (= 1 (count dups))
                "Don't know how to handle more than one dup validator key")
        ; The sum of the normal nodes weights should be just over 1/3, so
        ; that the remaining node can make up just under 2/3rds of the votes
        ; by itself. Let a normal node's weight be 2. Then 2(n-1) is the
        ; combined voting power of the normal bloc. We can then choose
        ; 4(n-1) - 1 as the weight for the dup validator. The total votes
        ; are
        ;
        ;    2(n-1) + 4(n-1) - 1
        ;  = 6(n-1) - 1
        ;
        ; which implies a single dup node has fraction...
        ;
        ;    (4(n-1) - 1) / (6(n-1) - 1)
        ;
        ; which approaches 2/3 from 0 for n = 1 -> infinity, and if a single
        ; regular node is added to a duplicate node, a 2/3+ majority is
        ; available for all n >= 1.
        (merge (zipmap (apply concat singles) (repeat 2))
               (zipmap (first dups) (repeat (dec (* 4 (dec n)))))))
      (zipmap (:nodes test) (repeat 1)))))

(defn test
  [opts]
  (let [n       (count (:nodes opts))
        checker (checker/compose
                  {:linear   (independent/checker (checker/linearizable))
                   :timeline (independent/checker (timeline/html))
                   :perf     (checker/perf)})
        test (merge
               tests/noop-test
               opts
               {:name (str "tendermint " (name (:nemesis opts)))
                :os   debian/os
                :nonserializable-keys [:validators]
                ; A map of validator nodes to the nodes whose keys they use
                ; instead of their own.
                :dup-validators (dup-validators opts)
                ; Map of node names to validator data structures, including keys
                :validators (->> (:nodes opts)
                                 (map (fn [node] [node (promise)]))
                                 (into {}))
                :db       (db {:versions {:tendermint "0.10.0"
                                          :abci       "0.5.0"
                                          :merkleeyes "0.2.2"}})
                :client   (cas-register-client)
                :model    (model/cas-register)
                :checker  checker})
        nemesis (nemesis test)
        test    (merge test
                       {:validator-weights (validator-weights test)
                        :generator (->> (independent/concurrent-generator
                                          (* 2 n)
                                          (range)
                                          (fn [k]
                                            (->> (gen/mix [w cas])
                                                 (gen/reserve n r)
                                                 (gen/stagger 1/2)
                                                 (gen/limit 100))))
                                        (gen/nemesis (:generator nemesis))
                                        (gen/time-limit (:time-limit opts)))
                        :nemesis (:nemesis nemesis)})]
    test))
