package org.example;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class DiningPhilosophers {

    private Lock forks[] = new Lock[5];
    private Semaphore semaphore = new Semaphore(4);

    public DiningPhilosophers() {
        for (int i = 0; i < 5; i++) {
            forks[i] = new ReentrantLock();
        }

    }

    void pickFork(int phId, int id, Runnable pick) {
        forks[id].lock();
        pick.run();
        System.out.println(phId + "Pick: " + id + ", " + Thread.currentThread().getName());
    }

    void putFork(int phId, int id, Runnable put) {
        put.run();
        System.out.println(phId + "Put: " + id + ", " + Thread.currentThread().getName());
        forks[id].unlock();
    }

    // call the run() method of any runnable to execute its code
    public void wantsToEat(int philosopher,
                           Runnable pickLeftFork,
                           Runnable pickRightFork,
                           Runnable eat,
                           Runnable putLeftFork,
                           Runnable putRightFork) throws InterruptedException {
        int leftFork = philosopher;
        int rightFork = (philosopher + 4) % 5;

        semaphore.acquire();

        System.out.println("Start: " + Thread.currentThread().getName());

        pickFork(philosopher, leftFork, pickLeftFork);
        pickFork(philosopher, rightFork, pickRightFork);
        eat.run();
        System.out.println("Eat: " + Thread.currentThread().getName());
        putFork(philosopher, rightFork, putRightFork);
        putFork(philosopher, leftFork, putLeftFork);

        System.out.println("Completed: " + Thread.currentThread().getName());

        semaphore.release();
    }

    public static void main(String[] args) {

        DiningPhilosophers diningPhilosophers = new DiningPhilosophers();

        ExecutorService threadPool = Executors.newFixedThreadPool(10);

        /*
        output[i] = [a, b, c] (three integers)
        - a is the id of a philosopher.
        - b specifies the fork: {1 : left, 2 : right}.
        - c specifies the operation: {1 : pick, 2 : put, 3 : eat}.
         */

        for (int i = 0; i < 10; i++) {
            int philosopher = i % 5;
            threadPool.submit(() -> {
                Runnable pickLeftFork = () -> System.out.println(Arrays.toString(new int[]{philosopher, 1, 1}));
                Runnable pickRightFork = () -> System.out.println(Arrays.toString(new int[]{philosopher, 2, 1}));
                Runnable eat = () -> System.out.println(Arrays.toString(new int[]{philosopher, 0, 3}));
                Runnable putLeftFork = () -> System.out.println(Arrays.toString(new int[]{philosopher, 1, 2}));
                Runnable putRightFork = () -> System.out.println(Arrays.toString(new int[]{philosopher, 2, 2}));
                try {
                    diningPhilosophers.wantsToEat(philosopher, pickLeftFork, pickRightFork, eat, putLeftFork, putRightFork);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        threadPool.shutdownNow();

    }
}
