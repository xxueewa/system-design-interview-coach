package org.example;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.*;
import java.util.logging.Logger;

/**
 * Inter-thread communication mechanisms in Java:
 *
 *  1. Semaphore        – limits how many threads access a resource concurrently (permits)
 *  2. Mutex            – mutual exclusion: only one thread at a time (binary semaphore / ReentrantLock)
 *  3. Monitor          – synchronized + wait/notify: threads wait for a condition inside a locked block
 *  4. Condition        – explicit condition variables on a ReentrantLock (finer-grained than monitor)
 *  5. CountDownLatch   – one-shot gate: threads wait until a count reaches zero
 *  6. CyclicBarrier    – reusable barrier: all threads must arrive before any proceeds
 *  7. BlockingQueue    – producer/consumer channel: thread-safe hand-off with built-in blocking
 */
public class Main {

    private static final Logger logger = Logger.getLogger(Main.class.getName());

    // -------------------------------------------------------------------------
    // 1. Semaphore
    //    A pool of N permits. acquire() blocks when none are available.
    //    Use case: limit concurrent DB connections, rate limiting.
    // -------------------------------------------------------------------------
    static void demoSemaphore() throws InterruptedException {
        logger.info("\n===== 1. SEMAPHORE (3 permits, 6 threads) =====");
        Semaphore semaphore = new Semaphore(3); // only 3 threads in the "resource pool" at once

        Runnable task = () -> {
            try {
                semaphore.acquire();
                logger.info(Thread.currentThread().getName() + " acquired permit. In use: " + (3 - semaphore.availablePermits()));
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                logger.info(Thread.currentThread().getName() + " releasing permit");
                semaphore.release();
            }
        };

        try (ExecutorService ex = Executors.newFixedThreadPool(6)) {
            for (int i = 0; i < 6; i++) ex.submit(task);
        }
    }

    // -------------------------------------------------------------------------
    // 2. Mutex (ReentrantLock as binary semaphore)
    //    Only one thread holds the lock at a time.
    //    Use case: protect a critical section (e.g. shared counter update).
    // -------------------------------------------------------------------------
    static void demoMutex() throws InterruptedException {
        logger.info("\n===== 2. MUTEX (ReentrantLock) =====");
        ReentrantLock mutex = new ReentrantLock();
        AtomicInteger counter = new AtomicInteger(0);

        Runnable task = () -> {
            mutex.lock();
            try {
                int val = counter.get();
                Thread.sleep(10); // simulate work; without mutex this causes lost updates
                counter.set(val + 1);
                logger.info(Thread.currentThread().getName() + " → counter = " + counter.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                mutex.unlock();
            }
        };

        try (ExecutorService ex = Executors.newFixedThreadPool(5)) {
            for (int i = 0; i < 5; i++) ex.submit(task);
        }
        logger.info("Final counter (expected 5): " + counter.get());
    }

    // -------------------------------------------------------------------------
    // 3. Monitor (synchronized + wait / notifyAll)
    //    The classic Java mechanism. A thread waits inside the monitor until
    //    another thread changes state and calls notify.
    //    Use case: producer signals consumer that data is ready.
    // -------------------------------------------------------------------------
    static void demoMonitor() throws InterruptedException {
        logger.info("\n===== 3. MONITOR (synchronized + wait/notifyAll) =====");

        final Object monitor = new Object();
        final boolean[] ready = {false};

        Thread consumer = new Thread(() -> {
            synchronized (monitor) {
                while (!ready[0]) {         // always loop — guard against spurious wakeups
                    try {
                        logger.info("Consumer waiting...");
                        monitor.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                logger.info("Consumer woke up — data is ready!");
            }
        }, "consumer");

        Thread producer = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            synchronized (monitor) {
                ready[0] = true;
                logger.info("Producer set ready=true, calling notifyAll");
                monitor.notifyAll();
            }
        }, "producer");

        consumer.start();
        producer.start();
        consumer.join();
        producer.join();
    }

    // -------------------------------------------------------------------------
    // 4. Condition Variable (ReentrantLock + Condition)
    //    Like a monitor but you can have multiple named conditions on one lock.
    //    Use case: bounded buffer with separate "not full" and "not empty" conditions.
    // -------------------------------------------------------------------------
    static void demoCondition() throws InterruptedException {
        logger.info("\n===== 4. CONDITION VARIABLE =====");

        ReentrantLock lock = new ReentrantLock();
        Condition notEmpty = lock.newCondition();
        Condition notFull  = lock.newCondition();
        int[] buffer = new int[3];
        int[] count  = {0}, head = {0}, tail = {0};

        Runnable producer = () -> {
            for (int i = 1; i <= 6; i++) {
                lock.lock();
                try {
                    while (count[0] == buffer.length) notFull.await();  // wait if full
                    buffer[tail[0]] = i;
                    tail[0] = (tail[0] + 1) % buffer.length;
                    count[0]++;
                    logger.info("Produced " + i + "  buffer size=" + count[0]);
                    notEmpty.signalAll();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            }
        };

        Runnable consumer = () -> {
            for (int i = 0; i < 6; i++) {
                lock.lock();
                try {
                    while (count[0] == 0) notEmpty.await();             // wait if empty
                    int val = buffer[head[0]];
                    head[0] = (head[0] + 1) % buffer.length;
                    count[0]--;
                    logger.info("Consumed " + val + " buffer size=" + count[0]);
                    notFull.signalAll();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            }
        };

        Thread p = new Thread(producer, "producer");
        Thread c = new Thread(consumer, "consumer");
        p.start(); c.start();
        p.join();  c.join();
    }

    // -------------------------------------------------------------------------
    // 5. CountDownLatch
    //    One-shot: a set of threads waits until N events have occurred.
    //    Cannot be reset. Use case: wait for all workers to finish before aggregating.
    // -------------------------------------------------------------------------
    static void demoCountDownLatch() throws InterruptedException {
        logger.info("\n===== 5. COUNT DOWN LATCH =====");
        int workers = 4;
        CountDownLatch latch = new CountDownLatch(workers);

        try (ExecutorService ex = Executors.newFixedThreadPool(workers)) {
            for (int i = 0; i < workers; i++) {
                ex.submit(() -> {
                    try {
                        Thread.sleep(200);
                        logger.info(Thread.currentThread().getName() + " done, counting down");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await(); // main thread blocks here
        }
        logger.info("All workers finished — aggregating results");
    }

    // -------------------------------------------------------------------------
    // 6. CyclicBarrier
    //    Reusable: all threads must reach the barrier before any continues.
    //    Use case: parallel phases (e.g. map → barrier → reduce → barrier → ...).
    // -------------------------------------------------------------------------
    static void demoCyclicBarrier() throws InterruptedException {
        logger.info("\n===== 6. CYCLIC BARRIER =====");
        int parties = 3;
        CyclicBarrier barrier = new CyclicBarrier(parties,
                () -> logger.info("--- All threads reached barrier, starting next phase ---"));

        try (ExecutorService ex = Executors.newFixedThreadPool(parties)) {
            for (int phase = 1; phase <= 2; phase++) {
                int p = phase;
                for (int i = 0; i < parties; i++) {
                    ex.submit(() -> {
                        try {
                            Thread.sleep((long) (Math.random() * 300));
                            logger.info(Thread.currentThread().getName() + " finished phase " + p);
                            barrier.await();
                        } catch (InterruptedException | BrokenBarrierException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
                Thread.sleep(600); // let phase complete before submitting next
            }
        }
    }

    // -------------------------------------------------------------------------
    // 7. BlockingQueue (producer–consumer channel)
    //    Thread-safe hand-off. put() blocks when full; take() blocks when empty.
    //    Use case: task queues, pipeline stages, event buses.
    // -------------------------------------------------------------------------
    static void demoBlockingQueue() throws InterruptedException {
        logger.info("\n===== 7. BLOCKING QUEUE =====");
        BlockingQueue<Integer> queue = new LinkedBlockingQueue<>(3);
        int POISON = -1;

        Thread producer = new Thread(() -> {
            try {
                for (int i = 1; i <= 5; i++) {
                    queue.put(i);
                    logger.info("Enqueued " + i + "  queue size=" + queue.size());
                    Thread.sleep(100);
                }
                queue.put(POISON); // signal done
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "producer");

        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    int val = queue.take();
                    if (val == POISON) break;
                    logger.info("Dequeued " + val);
                    Thread.sleep(200);
                }
                logger.info("Consumer received poison pill, shutting down");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "consumer");

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
    }

    public static void main(String[] args) throws InterruptedException {
        demoSemaphore();
        demoMutex();
        demoMonitor();
        demoCondition();
        demoCountDownLatch();
        demoCyclicBarrier();
        demoBlockingQueue();
    }
}