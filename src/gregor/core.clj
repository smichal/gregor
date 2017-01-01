(ns gregor.core
  (:refer-clojure :exclude [flush send])
  (:import [org.apache.kafka.common TopicPartition]
           [org.apache.kafka.clients.consumer Consumer KafkaConsumer ConsumerRecords
            ConsumerRecord OffsetAndMetadata OffsetCommitCallback
            ConsumerRebalanceListener]
           [org.apache.kafka.clients.producer Producer KafkaProducer Callback
            ProducerRecord RecordMetadata]
           [kafka.admin AdminUtils]
           [kafka.utils ZkUtils]
           [java.util.concurrent TimeUnit]
           [scala.collection JavaConversions])
  (:require [clojure.set :as set]
            [clojure.string :as str]))


(def ^:no-doc str-deserializer "org.apache.kafka.common.serialization.StringDeserializer")
(def ^:no-doc str-serializer "org.apache.kafka.common.serialization.StringSerializer")
(def ^:no-doc byte-array-deserializer "org.apache.kafka.common.serialization.ByteArrayDeserializer")
(def ^:no-doc byte-array-serializer "org.apache.kafka.common.serialization.ByteArraySerializer")

(defn- as-properties
  [m]
  (let [ps (java.util.Properties.)]
    (doseq [[k v] m] (.setProperty ps k v))
    ps))

(defn topic-partition
  "A topic name and partition number."
  ^TopicPartition
  [^String topic ^Integer partition]
  (TopicPartition. topic partition))

(defn- arg-pairs
  [fn-name p1 p2 pairs]
  (let [pairs (remove nil? pairs)]
    (if (even? (count pairs))
      (->> pairs
           (concat [p1 p2])
           (partition 2))
      (throw (IllegalArgumentException.
              (str fn-name
                   " expects even number of optional args, found odd number."))))))

(defn- ->tps
  [fn-name topic partition tps]
  (let [pairs (arg-pairs fn-name topic partition tps)]
    (->> pairs (map #(apply topic-partition %)))))

(defn- reify-occ
  [cb]
  (reify OffsetCommitCallback
    (onComplete [this offsets-map ex]
      (cb offsets-map ex))))

(defn- reify-crl
  [assigned-cb revoked-cb]
  (reify ConsumerRebalanceListener
    (onPartitionsAssigned [this partitions]
      (when assigned-cb
        (assigned-cb partitions)))
    (onPartitionsRevoked [this partitions]
      (when revoked-cb
        (revoked-cb partitions)))))

(defn offset-and-metadata
  "Metadata for when an offset is committed."
  ([^Long offset] (OffsetAndMetadata. offset))
  ([^Long offset ^String metadata] (OffsetAndMetadata. offset metadata)))

(defn consumer-record->map
  [^ConsumerRecord record]
  {:value     (.value record)
   :key       (.key record)
   :partition (.partition record)
   :topic     (.topic record)
   :offset    (.offset record)
   :timestamp (.timestamp record)})

(defprotocol Closeable 
  "Provides two ways to close things: a default one with 'close [thing]'
   and the one with the specified timeout."
  (close [this]
         [this timeout]))

(extend-protocol Closeable
  KafkaProducer
    (close ([p] (.close p))
           ([p timeout]
            ;; Tries to close the producer cleanly within the specified timeout.
            ;; If the close does not complete within the timeout, fail any pending send
            ;; requests and force close the producer
            (.close p timeout TimeUnit/SECONDS)))
  KafkaConsumer
    (close ([c] (.close c))))

;;;;;;;;;;;;;;;;;;;;
;; Kafka Consumer ;;
;;;;;;;;;;;;;;;;;;;;

(defn assign!
  "Manually assign topics and partitions to this consumer."
  [^Consumer consumer ^String topic ^Integer partition & tps]
  (->> tps
       (->tps "assign!" topic partition)
       (vec)
       (.assign consumer)))

(defn assignment
  "Get the set of partitions currently assigned to this consumer."
  [^Consumer consumer]
  (set (.assignment consumer)))


(defn commit-offsets-async!
 "Commit offsets returned by the last poll for all subscribed topics and partitions,
  or manually specify offsets to commit.

  This is an asynchronous call and will not block. Any errors encountered are either
  passed to the callback (if provided) or discarded.
  
  offsets (optional) - commit the specified offsets for the specified list of topics
  and partitions to Kafka. A seq of offset maps, as below:

  e.g. {:topic \"foo\"
        :partition 1
        :offset 42}

  optionally provide metadata:

  e.g. {:topic \"bar\"
        :partition 0
        :offset 17
        :metadata \"Dude, that's so meta.\"}
  
  The committed offset should be the next message your application will consume,
  i.e. lastProcessedMessageOffset + 1.
  "
  ([^Consumer consumer]
   (.commitAsync consumer))
  ([^Consumer consumer callback]
   (.commitAsync consumer (reify-occ callback)))
  ([^Consumer consumer offsets callback]
   (let [m (into {} (for [{:keys [topic partition offset metadata]} offsets]
                      [(topic-partition topic partition)
                       (if metadata
                         (offset-and-metadata offset metadata)
                         (offset-and-metadata offset))]))]
     (.commitAsync consumer m (reify-occ callback)))))

(defn commit-offsets!
  "Commit offsets returned by the last poll for all subscribed topics and partitions, or
  manually specify offsets to commit.

  offsets (optional) - commit the specified offsets for the specified list of topics
  and partitions to Kafka. A seq of offset maps, as below:

  e.g. {:topic \"foo\"
        :partition 1
        :offset 42}

  optionally provide metadata:

  e.g. {:topic \"bar\"
        :partition 0
        :offset 17
        :metadata \"Dude, that's so meta.\"}
  "
  ([^Consumer consumer]
   (.commitSync consumer))
  ([^Consumer consumer offsets]
   (let [m (into {} (for [{:keys [topic partition offset metadata]} offsets]
                      [(topic-partition topic partition)
                       (if metadata
                         (offset-and-metadata offset metadata)
                         (offset-and-metadata offset))]))]
     (.commitSync consumer m))))

(defn committed
  "Return OffsetAndMetadata of the last committed offset for the given partition. This
  offset will be used as the position for the consumer in the event of a failure.

  If no offsets have been committed, return nil.
  "
  [^Consumer consumer ^String topic ^Integer partition]
  (when-let [offset (.committed consumer (topic-partition topic partition))]
    (let [m {:offset (.offset offset)
             :metadata (.metadata offset)}]
      (if (clojure.string/blank? (:metadata m))
        (assoc m :metadata nil)
        m))))

;; (defn list-topics
;;   "Get a map of String to List of PartitionInfo about partitions for all topics that the
;;   user is authorized to view."
;;   ^java.util.Map [^Consumer consumer]
;;   (.listTopics consumer))

(defn pause
  "Suspend fetching for a seq of topic name, partition number pairs."
  [^Consumer consumer topic partition & tps]
  (->> tps
       (->tps "pause" topic partition)
       (.pause consumer)))

(defn poll
  "Return a seq of consumer records currently available to the consumer (via a single poll).
  Fetches sequetially from the last consumed offset.

  A consumer record is represented as a clojure map with corresponding keys :value, :key,
  :partition, :topic, :offset

  timeout - the time, in milliseconds, spent waiting in poll if data is not
  available. If 0, returns immediately with any records that are available now.
  Must not be negative."
  ([consumer] (poll consumer 100))
  ([consumer timeout]
   (->> (.poll consumer timeout)
        (map consumer-record->map)
        (seq))))

(defn position
  "Return the offset of the next record that will be fetched (if a record with that
  offset exists)."
  ^Long
  [^Consumer consumer topic partition]
  (.position consumer (topic-partition topic partition)))

(defn records
  "Return a lazy sequence of sequences of consumer-records by polling the consumer.

  Each element in the returned sequence is the seq of consumer records returned from a
  poll by the consumer. The consumer fetches sequetially from the last consumed offset.

  A consumer record is represented as a clojure map with corresponding keys :value, :key,
  :partition, :topic, :offset

  timeout - the time, in milliseconds, spent waiting in poll if data is not
  available. If 0, returns immediately with any records that are available now.
  Must not be negative."
  ([^Consumer consumer] (records consumer 100))
  ([^Consumer consumer timeout] (repeatedly #(poll consumer timeout))))

(defn resume
  "Resume specified partitions which have been paused."
  [^Consumer consumer topic partition & tps]
  (->> tps
       (->tps "resume" topic partition)
       (.resume consumer)))

(defn seek!
  "Overrides the fetch offsets that the consumer will use on the next poll."
  [^Consumer consumer topic partition offset]
  (.seek consumer (topic-partition topic partition) offset))

(defn seek-to!
  "Seek to the :beginning or :end offset for each of the given partitions."
  [consumer offset topic partition & tps]
  (assert (contains? #{:beginning :end} offset) "offset must be :beginning or :end")
  (let [tps (->tps "seek-to!" topic partition tps)]
    (case offset
      :beginning (.seekToBeginning consumer tps)
      :end (.seekToEnd consumer tps))))

(defn subscribe
  "Subscribe to the given list of topics to get dynamically assigned partitions. Topic
  subscriptions are not incremental. This list will replace the current assignment (if
  there is one). It is not possible to combine topic subscription with group management
  with manual partition assignment through assign(List). If the given list of topics is
  empty, it is treated the same as unsubscribe.

  topics-or-regex can be a list of topic names or a java.util.regex.Pattern object to
  subscribe to all topics matching a specified pattern.

  the optional functions are a callback interface to trigger custom actions when the set
  of partitions assigned to the consumer changes.
  "
  [^Consumer consumer topics-or-regex & [partitions-assigned-fn partitions-revoked-fn]]
  (.subscribe consumer topics-or-regex
              (reify-crl partitions-assigned-fn partitions-revoked-fn)))

(defn subscription
  "Get the current subscription for this consumer."
  [^Consumer consumer]
  (set (.subscription consumer)))

(defn unsubscribe
  "Unsubscribe from topics currently subscribed with subscribe. This also clears any
  partitions directly assigned through assign."
  [^Consumer consumer]
  (.unsubscribe consumer))

(defn wakeup
  "Wakeup the consumer. This method is thread-safe and is useful in particular to abort a
  long poll. The thread which is blocking in an operation will throw WakeupException."
  [^Consumer consumer]
  (.wakeup consumer))

(defn offsets-for-time
  [^Consumer consumer timestamps-to-search]
  (->> (.offsetsForTimes consumer timestamps-to-search))
       (map (fn [[k v]]
              [k {:offset (.offset v)
                  :timestamp (.timestamp v)}]))
       (into {}))

(defn beginning-offsets
  [^Consumer consumer partitions]
  (.beginningOffsets consumer partitions))

(defn end-offsets
  [^Consumer consumer partitions]
  (.endOffsets consumer partitions))

(defn consumer
  "Return a KafkaConsumer.

  Args:
    servers: comma-separated host:port strs or list of strs as bootstrap servers
    group-id: str that identifies the consumer group this consumer belongs to
    topics: an optional list of topics to which the consumer will be dynamically
            subscribed.
    config: an optional map of str to str containing additional consumer
            configuration. More info on optional config is available here:
            http://kafka.apache.org/documentation.html#newconsumerconfigs

  The StringDeserializer class is the default for both key.deserializer and
  value.deserializer.
  "
  ^KafkaConsumer
  ([servers group-id] (consumer servers group-id [] {}))
  ([servers group-id topics] (consumer servers group-id topics {}))
  ([servers group-id topics config]
   (let [servers (if (sequential? servers) (str/join "," servers) servers)
         c (-> {"bootstrap.servers" servers
                "group.id" group-id
                "key.deserializer" str-deserializer
                "value.deserializer" str-deserializer}
               (merge config)
               (as-properties)
               (KafkaConsumer.))]
     (when (not-empty topics)
       (subscribe c topics))
     c)))


;;;;;;;;;;;;;;;;;;;;
;; Kafka Producer ;;
;;;;;;;;;;;;;;;;;;;;

(defn flush
  "Invoking this method makes all buffered records immediately available to send (even if
  linger.ms is greater than 0) and blocks on the completion of the requests associated
  with these records."
  [^Producer producer]
  (.flush producer))

(defn ->producer-record
  ^ProducerRecord
  ([^String topic value]
   (ProducerRecord. topic value))
  ([^String topic key value]
   (ProducerRecord. topic key value))
  ([^String topic ^Integer partition key value]
   (ProducerRecord. topic partition key value))
  ([^String topic ^Integer partition ^Long timestamp key value]
   (ProducerRecord. topic partition timestamp key value)))

(defn- send-record
  [^Producer producer ^ProducerRecord record & [callback]]
  (if callback
    (.send producer record
           (reify Callback
             (onCompletion [this metadata ex]
               (try
                 (callback {:offset (.offset metadata)
                            :partition (.partition metadata)
                            :topic (.topic metadata)}
                           ex)
                 (catch Exception _ nil)))))
    (.send producer record)))

(defn send
  "Asynchronously send a record to a topic, providing at least a topic and value."
  ^java.util.concurrent.Future
  ([^Producer producer ^String topic value]
   (send-record producer (->producer-record topic value)))
  ([^Producer producer ^String topic key value]
   (send-record producer (->producer-record topic key value)))
  ([^Producer producer ^String topic ^Integer partition key value]
   (send-record producer (->producer-record topic partition key value)))
  ([^Producer producer ^String topic ^Integer partition ^Long timestamp key value]
   (send-record producer (->producer-record topic partition timestamp key value))))

(defn send-then
  "Asynchronously send a record to a topic, providing at least a topic and value, and
  invoke the provided callback when the send has been acknowledged.

  The callback function should take 2 args:
    - a metadata map: the metadata for the record that was sent. Keys
      are :topic, :partition, :offset.
    - a java.lang.Exception object: the exception thrown during processing of this
      record."
  ^java.util.concurrent.Future
  ([^Producer producer ^String topic value callback]
   (send-record producer (->producer-record topic value) callback))
  ([^Producer producer ^String topic key value callback]
   (send-record producer (->producer-record topic key value) callback))
  ([^Producer producer ^String topic ^Integer partition ^Long timestamp key value callback]
   (send-record producer (->producer-record topic partition timestamp key value) callback)))

(defn producer
  "Return a KafkaProducer.

   The producer is thread safe and sharing a single producer instance across
   threads will generally be faster than having multiple instances.

   Args:
    servers: comma-separated host:port strs or list of strs as bootstrap servers
    config: an optional map of str to str containing additional producer
            configuration. More info on optional config is available here:
            http://kafka.apache.org/documentation.html#producerconfigs
            
   The StringSerializer class is the default for both key.serializer and value.serializer"
  ^KafkaProducer
  ([servers] (producer servers {}))
  ([servers config]
   (-> {"bootstrap.servers" servers
        "key.serializer" str-serializer
        "value.serializer" str-serializer}
       (merge config)
       (as-properties)
       (KafkaProducer.))))

;;;;;;;;;;;;;;;;;;;;;;
;; Topic Management ;;
;;;;;;;;;;;;;;;;;;;;;;

(defn- validate-zookeeper-config
  "A helper for validating a Zookeeper configuration map and applying default
  values. Any invalid item in the provided config will result in an assertion
  failure.

  Args:
    config-map: A map with Zookeeper connection details. Valid keys are as
                follows:

                :connection-string (mandatory) A valid connection string for
                                               the Zookeeper instance to
                                               connect to.
                :session-timeout   (optional)  The session timeout in millis.
                :connect-timeout   (optional)  The connect timeout in millis."
  [config-map]
  (let [defaults {:connection-string nil
                  :session-timeout 10000
                  :connect-timeout 10000}
        merged (merge defaults config-map)
        valid-keys (set (keys defaults))
        merged-keys (set (keys merged))]
    (assert (set/subset? merged-keys valid-keys)
            (format "Invalid Zookeeper config: %s"
                    (str/join ", " (set/difference merged-keys valid-keys))))
    (assert (string? (:connection-string merged))
            "Zookeeper config must contain a valid :connection-string")
    (assert (integer? (:session-timeout merged))
            "Zookeeper :session-timeout must be an integer")
    (assert (integer? (:connect-timeout merged))
            "Zookeeper :connect-timeout must be an integer")
    merged))

(defmacro with-zookeeper
  "A utility macro for interacting with Zookeeper.

  Args:
    zk-config: A map with Zookeeper connection details. This will be validated
               using validate-zookeeper-config before use.
    zookeeper: This will be bound to an instance of ZkUtils while the body is
               executed. The connection to Zookeeper will be cleaned up when
               the body exits."
  [zk-config zookeeper & body]
  `(let [zk-config# (validate-zookeeper-config ~zk-config)
         client-and-conn# (ZkUtils/createZkClientAndConnection
                           (:connection-string zk-config#)
                           (:session-timeout zk-config#)
                           (:connect-timeout zk-config#))]
     (with-open [client# (._1 client-and-conn#)
                 connection# (._2 client-and-conn#)]
       (let [~zookeeper (ZkUtils. client# connection# false)]
         ~@body))))

(def rack-aware-modes
  {:disabled (kafka.admin.RackAwareMode$Disabled$.)
   :enforced (kafka.admin.RackAwareMode$Enforced$.)
   :safe (kafka.admin.RackAwareMode$Safe$.)})

(defn- rack-aware-mode-constant
  "Convert a keyword name for a RackAwareMode into the appropriate constant
  from the underlying Kafka library.

  Args:
    mode: A keyword of the same name as one of the constants in
          kafka.admin.RackAwareMode."
  [mode]
  (when-not (contains? rack-aware-modes mode)
    (throw (IllegalArgumentException. (format "Bad RackAwareMode: %s" mode))))
  (get rack-aware-modes mode))

(defn create-topic
  "Create a topic.

  Args:
    zk-config: A map with Zookeeper connection details as expected by
               with-zookeeper.
    topic: The name of the topic to create.
    An unnamed configuration map. Valid keys are as follows:
  
      :partitions         (optional) The number of ways to partition the topic.
                                     Defaults to 1.
      :replication-factor (optional) The replication factor for the topic.
                                     Defaults to 1.
      :config             (optional) A map of configuration options for the
                                     topic.
      :rack-aware-mode    (optional) Control how rack aware replica assignment
                                     is done. Valid values are :disabled,
                                     :enforced, :safe. Default is :safe.
  "
  [zk-config topic {:keys [partitions replication-factor config rack-aware-mode]
                    :or {partitions 1
                         replication-factor 1
                         config nil
                         rack-aware-mode :safe}}]
  (with-zookeeper zk-config zookeeper
    (AdminUtils/createTopic zookeeper
                            topic
                            (int partitions)
                            (int replication-factor)
                            (as-properties config)
                            (rack-aware-mode-constant rack-aware-mode))))

(defn topics
  "Query existing topics.

  Args:
    zk-config: A map with Zookeeper connection details as expected by
               with-zookeeper."
  [zk-config]
  (with-zookeeper zk-config zookeeper
    (-> zookeeper .getAllTopics JavaConversions/seqAsJavaList seq)))

(defn topic-exists?
  "Query whether or not a topic exists.

  Args:
    zk-config: A map with Zookeeper connection details as expected by
               with-zookeeper.
    topic: The name of the topic to check for."
  [zk-config topic]
  (with-zookeeper zk-config zookeeper
    (AdminUtils/topicExists zookeeper topic)))

(defn delete-topic
  "Delete a topic.

  Args:
    zk-config: A map with Zookeeper connection details as expected by
               with-zookeeper.
    topic: The name of the topic to delete."
  [zk-config topic]
  (with-zookeeper zk-config zookeeper
    (AdminUtils/deleteTopic zookeeper topic)))
