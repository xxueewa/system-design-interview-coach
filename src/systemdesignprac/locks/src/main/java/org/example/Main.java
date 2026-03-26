package org.example;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public class Main {

    private static final Logger logger = Logger.getLogger(Main.class.getName());
    private static final ConcurrentHashMap<String, Integer> fairCounter = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> unfairCounter = new ConcurrentHashMap<>();
    /***
     * Fairness of Lock:
     * whether the threads can acquire the lock with even likelihood
     *
     * constructor parameter: fairness,
     * true -> locks favor granting access to the longest-waiting thread.
     * o.w. -> this lock does not guarantee any particular access order
     *
     * Programs using fair locks accessed by many threads
     * may display lower overall throughput (i.e., are slower; often much slower)
     * than those using the default setting,
     * but have smaller variances in times to obtain locks and guarantee lack of starvation.
     *
     * Starvation in Java multithreading is a situation where a thread is repeatedly
     * denied access to shared resources or CPU time, preventing it from making progress,
     * even though the resources are available
     *
     * first-in, first-out
     */
    public static void fairLocks() {
        ReentrantLock fairLock = new ReentrantLock(true);
        logger.info("This is a fair lock: " + fairLock.isFair());

        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            for (int i = 0; i < 100; i++) {
                executor.submit(() -> {
                    fairLock.lock();
                    logger.info(Thread.currentThread().getName() + " acquired lock...");
                    try {
                        Thread.sleep(10);
                        fairCounter.put(Thread.currentThread().getName(), fairCounter.getOrDefault(Thread.currentThread().getName(), 0) + 1);
                        logger.info(Thread.currentThread().getName() + " completed task");
                    } catch (Exception e) {
                        logger.severe(e.getMessage());
                    } finally {
                        fairLock.unlock();
                    }
                });
            }
        } catch (Exception e) {
            logger.severe("Exception: " + e.getMessage());
        } finally {
            executor.close();
            logger.info("Shutdown thread pool: " + executor.isShutdown());
        }
    }

    public static void unfairLocks() {
        ReentrantLock unfairLock = new ReentrantLock(false);
        logger.info("This is an unfair lock: " + unfairLock.isFair());

        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            for (int i = 0; i < 100; i++) {
                executor.submit(() -> {
                    unfairLock.lock();
                    logger.info(Thread.currentThread().getName() + " acquired lock...");
                    try {
                        Thread.sleep(10);
                        unfairCounter.put(Thread.currentThread().getName(), unfairCounter.getOrDefault(Thread.currentThread().getName(), 0) + 1);
                        logger.info(Thread.currentThread().getName() + " completed task");
                    } catch (Exception e) {
                        logger.severe(e.getMessage());
                    } finally {
                        unfairLock.unlock();
                    }
                });
            }
        } catch (Exception e) {
            logger.severe("Exception: " + e.getMessage());
        } finally {
            executor.close();
            logger.info("Shutdown thread pool: " + executor.isShutdown());
        }
    }


    public static void main(String[] args) {
        fairLocks();
        unfairLocks();

        logger.info("=================== Counter Summary ===================");
        logger.info("Fair Lock -> " + fairCounter);
        logger.info("unFair Lock -> " + unfairCounter);
    }
}