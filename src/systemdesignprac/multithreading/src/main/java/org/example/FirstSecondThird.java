package org.example;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class FirstSecondThird {
    /*
    Question:
    The same instance of Foo will be passed to three different threads.
    Thread A calls first(), thread B calls second(), thread C calls third().
    Ensure second() runs after first(), and third() runs after second().
    Output: "firstsecondthird"
     */

    interface Foo {
        void first(Runnable printFirst) throws InterruptedException;
        void second(Runnable printSecond) throws InterruptedException;
        void third(Runnable printThird) throws InterruptedException;
    }

    // -------------------------------------------------------------------------
    // Approach 1: Semaphore
    // signaling mechanism for thread syncrhonization
    // second() and third() each block on a semaphore that the predecessor
    // releases after it finishes, enforcing the ordering.
    // -------------------------------------------------------------------------
    static class FooSemaphore implements Foo {
        private final Semaphore s12 = new Semaphore(0); // gates second()
        private final Semaphore s23 = new Semaphore(0); // gates third()

        @Override
        public void first(Runnable printFirst) {
            printFirst.run();
            s12.release();
        }

        @Override
        public void second(Runnable printSecond) throws InterruptedException {
            s12.acquire();
            printSecond.run();
            s23.release();
        }

        @Override
        public void third(Runnable printThird) throws InterruptedException {
            s23.acquire();
            printThird.run();
        }
    }

    // -------------------------------------------------------------------------
    // Approach 2: wait / notifyAll
    // pessimistic lock implemented using synchronized
    // A shared state counter tracks progress; waiting threads loop on wait()
    // to guard against spurious wakeups.
    // -------------------------------------------------------------------------
    static class FooWaitNotify implements Foo {
        private int state = 1; // 1 → first's turn, 2 → second's, 3 → third's

        @Override
        public synchronized void first(Runnable printFirst) {
            printFirst.run();
            state = 2;
            notifyAll();
        }

        @Override
        public synchronized void second(Runnable printSecond) throws InterruptedException {
            while (state != 2) wait();
            /*
             wait() can return even when no one called notify() — these are called spurious wakeups.
             The JVM/OS specification explicitly allows a thread to wake up
             without being notified. With if, the thread would proceed past the check and run
             printSecond.run() even though state might still be 1.
             */
            printSecond.run();
            state = 3;
            notifyAll();
        }

        @Override
        public synchronized void third(Runnable printThird) throws InterruptedException {
            while (state != 3) wait();
            printThird.run();
        }
    }

    // -------------------------------------------------------------------------
    // Approach 3: CountDownLatch
    // Each latch starts at 1; the predecessor counts it down to unblock the
    // successor. Clear and expressive for one-shot ordering.
    // -------------------------------------------------------------------------
    static class FooCountDownLatch implements Foo {
        private final CountDownLatch l12 = new CountDownLatch(1);
        private final CountDownLatch l23 = new CountDownLatch(1);

        @Override
        public void first(Runnable printFirst) {
            printFirst.run();
            l12.countDown();
        }

        @Override
        public void second(Runnable printSecond) throws InterruptedException {
            l12.await();
            printSecond.run();
            l23.countDown();
        }

        @Override
        public void third(Runnable printThird) throws InterruptedException {
            l23.await();
            printThird.run();
        }
    }

    // -------------------------------------------------------------------------
    // Approach 4: AtomicInteger + spin-wait (busy-waiting)
    // optimistic lock: version mechanism
    // No blocking primitives — threads spin until the counter reaches the
    // expected value. Simple but wastes CPU; fine only for very short waits.
    // -------------------------------------------------------------------------
    static class FooSpinWait implements Foo {
        private final AtomicInteger state = new AtomicInteger(1);

        @Override
        public void first(Runnable printFirst) {
            printFirst.run();
            state.set(2);
        }

        @Override
        public void second(Runnable printSecond) {
            while (state.get() != 2) Thread.onSpinWait();
            printSecond.run();
            state.set(3);
        }

        @Override
        public void third(Runnable printThird) {
            while (state.get() != 3) Thread.onSpinWait();
            printThird.run();
        }
    }

    // -------------------------------------------------------------------------
    // Demo: run all four approaches
    // -------------------------------------------------------------------------
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Semaphore ===");
        runDemo(new FooSemaphore());

        System.out.println("=== wait/notifyAll ===");
        runDemo(new FooWaitNotify());

        System.out.println("=== CountDownLatch ===");
        runDemo(new FooCountDownLatch());

        System.out.println("=== Spin-wait (AtomicInteger) ===");
        runDemo(new FooSpinWait());
    }

    // Launches threads in reverse order (C, B, A) to prove ordering is enforced.
    private static void runDemo(Foo foo) throws InterruptedException {
        Thread a = new Thread(() -> {
            try { foo.first(() -> System.out.print("first")); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Thread b = new Thread(() -> {
            try { foo.second(() -> System.out.print("second")); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Thread c = new Thread(() -> {
            try { foo.third(() -> System.out.print("third")); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        c.start(); b.start(); a.start(); // reverse start order stresses sync
        a.join(); b.join(); c.join();
        System.out.println();
    }
}