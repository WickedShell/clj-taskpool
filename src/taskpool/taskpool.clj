(ns taskpool.taskpool
    (:require [clojure.core.async :as async]
              [clojure.set :as set])
    (:import [java.util.concurrent.locks Condition ReentrantLock]))

(defn- take-work
  "Finds a task from the current pool of tasks, blocks until it finds a task to worn on"
  [^ReentrantLock lock ^Condition condition tasks-pending in-flight-tasks]
  (with-local-vars [work nil]
    (loop []
    (try
      (.lock lock)
      (if-not (empty? @tasks-pending)
        (do
          (var-set work (first @tasks-pending))
          (swap! tasks-pending disj @work)
          (swap! in-flight-tasks conj @work))
        (.await condition))
      (finally
        (.unlock lock)))
    (or @work
        (recur)))))

(defn- complete-work
  "Manages removal of the task from the list of in flight tasks"
  [^ReentrantLock lock work in-flight-tasks]
  (try
    (.lock lock)
    (swap! in-flight-tasks disj work)
    (finally
      (.unlock lock))))

(defn create-worker-pool
  "Creates a pool of workers that run the provided function on any pending tasks"
  ([parallelism f] (create-worker-pool (gensym "taskpool") parallelism f))
  ([pool-name parallelism f]
   (let [lock (new ReentrantLock)
         condition (.newCondition lock)
         pool (atom #{})
         in-flight-tasks (atom #{})]
     (doseq [i (range 0 parallelism)]
            (async/thread
              (.setName (Thread/currentThread) (format "%s-%d" pool-name i))
              (try
                (loop []
                      (let [work (take-work lock condition pool in-flight-tasks)]
                        (f work)
                        (complete-work lock work in-flight-tasks)
                        (recur)))
                (catch InterruptedException e))))
     {:condition condition
      :lock lock
      :parallelism parallelism
      :name pool-name
      :running (atom true)
      :in-flight-tasks in-flight-tasks
      :tasks-pending pool})))

(defn create-pool
  "Creates a new task pool, with the specified level of parallelism"
  ([parallelism] (create-pool (gensym "taskpool") parallelism))
  ([pool-name parallelism]
   (create-worker-pool pool-name parallelism (fn [task] (task)))))

(defn add-task
  "Adds a task or a set of tasks to be run in the task pool.
  Task run order is not guaranteed, duplicates of a task already pending will not be added."
  [task-pool task]
  (let [{:keys [^ReentrantLock lock ^Condition condition running in-flight-tasks tasks-pending]} task-pool]
    (if @running
      (do
        (.lock lock)
        (let [new-tasks (if (set? task)
                          (set/union @tasks-pending task)
                          (conj @tasks-pending task))]
          (reset! tasks-pending (set/difference new-tasks @in-flight-tasks)))
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
