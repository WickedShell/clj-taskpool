(ns taskpool.test.core
  (:require [clojure.test :refer :all]
            [taskpool.taskpool :refer :all]))

(deftest pool-no-name
  (testing "Creating a pool without provising a pool name"
    (let [pool (create-pool 5)]
      (is pool))))

(deftest single-thread-name
  (testing "Creating a pool with a single thread and check the name of the created thread"
    (let [pool (create-pool "test-pool" 1)
          fetched-name (promise)]
      (add-task pool #(deliver fetched-name (.getName (Thread/currentThread))))
      (is (= @fetched-name "test-pool-0")))))

(deftest test-remove-pending-task
  (testing "Tests removing a single pending task from a pool with two threads."
    (let [pool (create-pool "test-pool-pending-task" 2)
          delay-promises (take 2 (repeatedly promise))
          test-promise (promise)
          test-task (fn [] (deliver test-promise :finished))]
      (doseq [i (range 0 2)]
             (add-task pool (fn [] (Thread/sleep 1000) (deliver (nth delay-promises i) true))))
      ; wait before adding the task that we want to test removal on, otherwise the loops might be spun to fast
      (Thread/sleep 500)
      (add-task pool test-task)
      (is (remove-pending-task pool test-task))
      ; wait of what was running to finish
      @(nth delay-promises 0)
      @(nth delay-promises 1)
      (is (not (realized? test-promise))))))


(deftest test-remove-all-pending-tasks
  (testing "Tests removing all pending tasks from a pool with two threads."
    (let [pool (create-pool "test-pool-pending-tasks" 2)
          test-promise (promise)
          test-task (fn [] (deliver test-promise :finished))
          started-counter (atom 0)]
      ; test remove-all-pending-tasks
      (doall
        (take 10
              (repeatedly
                #(add-task pool (fn []
                                    (swap! started-counter inc)
                                    (Thread/sleep 1000))))))
      ; allow some tasks to start
      (Thread/sleep 500)
      (remove-all-pending-tasks pool)
      (add-task pool test-task)
      @test-promise
      (is (= @started-counter 2)))))

(deftest test-termination
  (testing "Tests terminating a pool that had tasks running on it"
    (let [pool (create-pool "test-pool-termination" 2)
          test-promise (promise)
          test-task (fn [] (deliver test-promise :finished))
          run-tasks (atom 0)]
      ; load the pool with tasks
      (doseq [i (range 0 10)]
             (add-task pool (fn [] (Thread/sleep 1000) (swap! run-tasks inc))))
      ; allow tasks to start
      (Thread/sleep 500)
      (terminate-pool pool)

      ; allow the pool to end
      (Thread/sleep 1500)

      (is (= @run-tasks 2))

      ; general users should not peak at the state like this!
      (is (empty? @(:tasks-pending pool)))
      (is (not @(:running pool)))
      (try
        (add-task pool test-task)
        ; adding to a terminated pool should always throw an exception
        (is false)
        (catch Exception e (is true)))

      ; allow any running tasks to complete
      (Thread/sleep 1500)
      ; if the test-promise has been realized then we ran the task by accident somehow
      (is (not (realized? test-promise)))

      ; tweak the state to see if any of the backend threads are still alive
      (reset! (:running pool) true)
      (add-task pool test-task) ; and submit a task to be run
      (Thread/sleep 500) ; give the task a chance to be run
      (is (= (count @(:tasks-pending pool)) 1)) ; the test task should still be pending
      (is (not (realized? test-promise)))))) ; still shouldn't have realized the test

(deftest test-multiple-tasks
  (testing "Tests adding a set of tasks to the pool"
    (let [pool (create-pool "test-pool-task" 5)
          run-tasks (atom 0)]
      (add-task pool (into #{} (for [i (range 0 10)] (fn [] (swap! run-tasks inc)))))
      ; allow the tasks to all run
      (Thread/sleep 500)
      (is (= @run-tasks 10)))))

(deftest test-worker-pools
  (testing "Tests that passing data as an argument to a common function"
    (let [running-total (atom 0)
          pool (create-worker-pool 2 #(swap! running-total + %))]
      (add-task pool (into #{} (for [i (range 0 10)] i)))
      ; allow tasks to all run
      (Thread/sleep 500)
      (is (= @running-total 45)))))
