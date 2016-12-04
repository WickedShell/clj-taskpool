(ns taskpool.taskpool
    (:require [clojure.core.async :as async]
              [clojure.set :as set])
    (:import [java.util.concurrent.locks Condition ReentrantLock]))

(defn- take-work
  "Finds a task from the current pool of tasks, blocks until it finds a task to worn on"
  [^ReentrantLock lock ^Condition condition tasks-pending]
  (with-local-vars [work nil]
    (loop []
    (try
      (.lock lock)
      (if-not (empty? @tasks-pending)
        (do
          (var-set work (first @tasks-pending))
          (swap! tasks-pending disj @work))
        (.await condition))
      (finally
        (.unlock lock)))
    (or @work
        (recur)))))

(defn create-pool
  "Creates a new task pool, with the specified level of parallelism"
  ([parallelism] (create-pool (gensym "taskpool") parallelism))
  ([pool-name parallelism]
   (let [lock (new ReentrantLock)
         condition (.newCondition lock)
         pool (atom #{})]
     (doseq [i (range 0 parallelism)]
            (async/thread
              (.setName (Thread/currentThread) (format "%s-%d" pool-name i))
              (try
                (loop []
                      ((take-work lock condition pool))
                      (recur))
                (catch InterruptedException e))))
     {:condition condition
      :lock lock
      :parallelism parallelism
      :name pool-name
      :running (atom true)
      :tasks-pending pool})))

(defn add-task
  "Adds a task or a set of tasks to be run in the task pool.
  Task run order is not guaranteed, duplicates of a task already pending will not be added."
  [task-pool task]
  (let [{:keys [^ReentrantLock lock ^Condition condition running tasks-pending]} task-pool]
    (if @running
      (do
        (.lock lock)
        (if (set? task)
          (swap! tasks-pending set/union task)
          (swap! tasks-pending conj task))
        (.signalAll condition)
        (.unlock lock))
      (throw (ex-info
               "Attempted to add a task to a pool which has been terminated" 
               {:name (:name task-pool)})))))

(defn remove-pending-task
  "Removes a pending task from the task pool"
  [task-pool task]
  (with-local-vars [removed false]
  (let [{:keys [^ReentrantLock lock tasks-pending]} task-pool]
    (.lock lock)
    (when (contains? @tasks-pending task)
      (swap! tasks-pending disj task)
      (var-set removed true))
    (.unlock lock))
  @removed))

(defn remove-all-pending-tasks
  "Removes all pending tasks from the task pool"
  [task-pool]
  (let [{:keys [^ReentrantLock lock tasks-pending]} task-pool]
    (.lock lock)
    (swap! tasks-pending empty)
    (.unlock lock)))

(defn- terminate-thread
  "Terminates the thread this task is run on."
  []
  (.interrupt (Thread/currentThread))
  ; the interrupt actually kills the thread, but it's possible to actually run
  ; this multiple times before the thread has caught the interrupt, so mark the
  ; thread to sleep so that it will yeild and give the interrupt time to fire
  (Thread/sleep 1000))

(defn terminate-pool
  "Terminate a pool. All threads will exit once their task has completed"
  [task-pool]
  (let [{:keys [^ReentrantLock lock parallelism running]} task-pool]
    (.lock lock)
    (remove-all-pending-tasks task-pool)
    (doseq [i (range 0 parallelism)]
           (add-task task-pool #(terminate-thread)))
    (reset! running false)
    (.unlock lock)))
