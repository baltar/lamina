;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.time
  (:use
    [lamina.core.utils]
    [potemkin])
  (:require
    [clojure.tools.logging :as log]
    [clojure.string :as str])
  (:import
    [java.util
     Calendar
     TimeZone]
    [java.util.concurrent
     ThreadFactory
     TimeUnit
     ThreadPoolExecutor
     ScheduledThreadPoolExecutor
     LinkedBlockingQueue
     ]))

(defn now []
  (System/currentTimeMillis))

(defn nanoseconds
  "Converts nanoseconds -> milliseconds"
  [n]
  (/ n 1e6))

(defn microseconds
  "Converts microseconds -> milliseconds"
  [n]
  (/ n 1e3))

(defn milliseconds
  "Converts milliseconds -> milliseconds"
  [n]
  n)

(defn seconds
  "Converts seconds -> milliseconds"
  [n]
  (* n 1e3))

(defn minutes
  "Converts minutes -> milliseconds"
  [n]
  (* n 6e4))

(defn hours
  "Converts hours -> milliseconds"
  [n]
  (* n 36e5))

(defn days
  "Converts days -> milliseconds"
  [n]
  (* n 864e5))

(defn hz
  "Converts frequency -> period in milliseconds"
  [n]
  (/ 1e3 n))

(let [intervals (partition 2
                  ["d" (days 1)
                   "h" (hours 1)
                   "m" (minutes 1)
                   "s" (seconds 1)])]

  (defn format-duration
    "Returns a formatted string describing an interval, i.e. '5d 3h 1m'"
    [n]
    (loop [s "", n n, intervals intervals]
      (if (empty? intervals)
        (if (empty? s)
          "0s"
          (str/trim s))
        (let [[desc val] (first intervals)]
          (if (>= n val)
            (recur
              (str s (int (/ n val)) desc " ")
              (rem n val)
              (rest intervals))
            (recur s n (rest intervals))))))))

(let [sorted-units [:millisecond Calendar/MILLISECOND
                    :second Calendar/SECOND
                    :minute Calendar/MINUTE
                    :hour Calendar/HOUR
                    :day Calendar/DAY_OF_YEAR
                    :week Calendar/WEEK_OF_MONTH
                    :month Calendar/MONTH]
      unit->calendar-unit (apply hash-map sorted-units)
      units (->> sorted-units (partition 2) (map first))
      unit->cleared-fields (zipmap
                             units
                             (map
                               #(->> (take % units) (map unit->calendar-unit))
                               (range (count units))))]
  
  (defn floor [timestamp unit]
    (assert (contains? unit->calendar-unit unit))
    (let [^Calendar cal (doto (Calendar/getInstance (TimeZone/getTimeZone "UTC"))
                          (.setTimeInMillis timestamp))]
      (doseq [field (unit->cleared-fields unit)]
        (.set cal field 0))
      (.getTimeInMillis cal)))

  (defn add [timestamp value unit]
    (assert (contains? unit->calendar-unit unit))
    (let [^Calendar cal (doto (Calendar/getInstance (TimeZone/getTimeZone "UTC"))
                          (.setTimeInMillis timestamp))]
      (.add cal (unit->calendar-unit unit) value)
      (.getTimeInMillis cal))))

;;;

(defprotocol+ ITaskQueue
  (invoke-once- [_ delay f])
  (invoke-repeatedly- [_ period f]))

(let [queue-factory (thread-factory (constantly "lamina-scheduler-queue"))
      task-queue    (ScheduledThreadPoolExecutor. 1 ^ThreadFactory queue-factory)

      cnt (atom 0)
      task-factory (thread-factory #(str "lamina-scheduler-" (swap! cnt inc)))
      task-executor (ThreadPoolExecutor.
                      (int (num-cores))
                      Integer/MAX_VALUE
                      (long 60)
                      TimeUnit/SECONDS
                      (LinkedBlockingQueue.)
                      ^ThreadFactory task-factory)]

  (def default-task-queue
    (reify ITaskQueue
      (invoke-once- [_ delay f]
        (let [enqueue-fn (fn []
                           (.execute task-executor
                             #(try
                                (f)
                                (catch Throwable e
                                  (log/error e "Error in delayed invocation")))))]
          (if (<= delay 0)
            (enqueue-fn)
            (.schedule task-queue
              ^Runnable enqueue-fn
              (long (* 1e6 delay))
              TimeUnit/NANOSECONDS)))
        true)
      (invoke-repeatedly- [this period f]
        (let [target-time (atom (+ (now) period))
              latch (atom false)
              cancel-callback #(reset! latch true)

              schedule-next (fn schedule-next []
                              (invoke-once- this (max 0.1 (- @target-time (now)))
                                (fn []
                                  (try
                                    (f cancel-callback)
                                    (finally
                                      (when-not @latch
                                        (swap! target-time + period)
                                        (schedule-next)))))))]
          (schedule-next)
          true)))))

(defn invoke-once
  "Delays invocation of a function by 'delay' milliseconds."
  ([delay f]
     (invoke-once default-task-queue delay f))
  ([task-queue delay f]
     (invoke-once- task-queue delay f)))

(defn invoke-repeatedly
  "Repeatedly invokes a function every 'period' milliseconds, but ensures that the function cannot
   overlap its own invocation if it takes more than the period to complete.

   The function will be given a single parameter, which is a callback that can be invoked to cancel
   future invocations."
  ([period f]
     (invoke-repeatedly default-task-queue period f))
  ([task-queue period f]
     (invoke-repeatedly- task-queue period f)))

