# clj-taskpool

A Clojure library designed to create a simple to use task pool. The level of parallelism specified at the creation of the pool controls how many threads are used to process tasks.

## Usage

Include the namespace with something like:

```clojure
=> (use 'taskpool.taskpool)
```

### Create a pool

Creating a pool to submit tasks to is simple, just provide the name of the pool and the number of threads you want to consume tasks. The name is optional and can be omitted, but is helpful when debugging.

```clojure
    => (def pool (create-pool "my-pool" 10))
```

### Submitting tasks

To submit a task, simply call `add-task` with your task pool and the task to be added. Note that tasks are not guaranteed to run in the order you add them to the pool. For example the doseq below adds 20 simple tasks to the pool:

```clojure
=> (doseq [i (range 0 20)]
     (add-task pool
               (fn []
                 (Thread/sleep (+ (rand-int 1000) 1000))
                 (println (str (.getName (Thread/currentThread)) " finished " i)))))
my-pool-8 finished 14
my-pool-6 finished 1
my-pool-1 finished 6
my-pool-9 finished 13
my-pool-0 finished 5
my-pool-7 finished 0
my-pool-5 finished 3
my-pool-4 finished 7
my-pool-3 finished 2
my-pool-2 finished 4
my-pool-8 finished 19
my-pool-1 finished 17
my-pool-9 finished 16
my-pool-0 finished 15
my-pool-6 finished 18
my-pool-2 finished 8
my-pool-5 finished 11
my-pool-4 finished 10
my-pool-7 finished 12
my-pool-3 finished 9
```

Notice that tasks are run as soon as they are submitted, you don't need to prompt the pool to check for any new tasks.

### Remove pending tasks

Tasks that haven't been started can be removed from the pool. To remove a single task call `remove-pending-task` with your task pool and the task to be removed. It will return a boolean for if it succeeded in removing the task from the pool without running the task.

```
=> (remove-pending-task pool my-task)
true
```

Clearing all pending tasks is also supported:

```
=> (remove-all-pending-tasks pool)
```

### Terminating the pool

To shutdown the thread pool you simply call:

```
=> (terminate-pool pool)
```

This will automatically remove all pending tasks, and stop all the threads in the pool after they have completed their current task.

## License

Copyright © 2016 Michael du Breuil

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
