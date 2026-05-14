package org.example;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.atomic.AtomicStampedReference;

/**
 * Compare-And-Swap (CAS) in Optimistic Locking
 *
 * Core idea:
 *   CAS(address, expected, newValue):
 *     if *address == expected  →  *address = newValue, return true   (success)
 *     else                     →  do nothing,          return false  (retry)
 *
 * This is a single atomic CPU instruction (CMPXCHG on x86), so no lock is
 * needed. The caller optimistically assumes no contention, and retries if
 * another thread raced and changed the value first.
 *
 * Demos in this file:
 *   1. Manual CAS simulation        — shows the logic without java.util.concurrent
 *   2. AtomicInteger (real CAS)     — lock-free counter with retry loop
 *   3. ABA problem                  — CAS succeeds even though the value changed twice
 *   4. ABA fix: AtomicStampedReference  — version stamp detects the invisible change
 *   5. ABA fix: AtomicMarkableReference — boolean mark (simpler, coarser)
 *
 *
 *   ⏺ The classic real-world victim is a lock-free stack (Treiber stack):
 *
 *   Stack: top → [A] → [B] → [C]
 *
 *   Thread 1 wants to pop A:
 *     snapshot_top = A
 *     snapshot_next = B        ← "if top is still A, set top = B"
 *     ... gets preempted ...
 *
 *   Thread 2 pops A, pops B (B is now freed/reused elsewhere), pushes A back:
 *     Stack is now: top → [A] → [C]   (B is gone or recycled)
 *
 *   Thread 1 resumes:
 *     CAS(top, A, B) succeeds!   ← top WAS A, still looks like A
 *     top is now set to B        ← but B was already freed/recycled
 *
 *   Result: top points to freed memory → use-after-free / dangling pointer.
 *
 *   ┌───────────────────────┬───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
 *   │       Scenario        │                                                        What goes wrong                                                        │
 *   ├───────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
 *   │ Lock-free linked list │ A node is popped, freed, and a new node happens to get the same memory address. CAS sees the same pointer value and           │
 *   │  / stack              │ incorrectly links to the recycled node.                                                                                       │
 *   ├───────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
 *   │ Lock-free memory      │ A freed block is handed out to another thread. The original thread's CAS still matches and "reclaims" it — now two owners     │
 *   │ allocator             │ exist for the same block.                                                                                                     │
 *   ├───────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
 *   │ Optimistic DB /       │ Row version is read as v1, another transaction updates v1→v2→v1. The original transaction's check passes and it commits on    │
 *   │ version check         │ top of logically different data (e.g., a bank balance that briefly went negative).                                            │
 *   └───────────────────────┴───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
 */
public class CompareAndSwap {

    // =========================================================================
    // 1. Manual CAS simulation (single-threaded illustration)
    //    Shows the raw logic: read → compare → conditionally write, all as one
    //    indivisible operation (simulated here with synchronized).
    // =========================================================================
    static class ManualCAS {
        private int value;

        ManualCAS(int initial) { this.value = initial; }

        // Returns true if the swap happened.
        synchronized boolean compareAndSwap(int expected, int update) {
            if (value != expected) return false;
            value = update;
            return true;
        }

        synchronized int get() { return value; }
    }

    // =========================================================================
    // 2. Lock-free counter using AtomicInteger (real hardware CAS)
    //    Multiple threads increment concurrently; no synchronized keyword needed.
    //    Pattern: read → compute new value → CAS; retry if CAS fails.
    // =========================================================================
    static class LockFreeCounter {
        private final AtomicInteger count = new AtomicInteger(0);

        void increment() {
            int current, next;
            do {
                current = count.get();          // optimistic read
                next    = current + 1;          // local computation
            } while (!count.compareAndSet(current, next)); // retry if raced
        }

        int get() { return count.get(); }
    }

    // =========================================================================
    // 3. ABA Problem demo
    //
    //    Thread 1 reads value A.
    //    Thread 2 changes A → B → A.
    //    Thread 1's CAS sees A and succeeds — but the value was NOT stable.
    //
    //    Real-world impact: in a lock-free linked-list pop, a node could be
    //    recycled and reinserted, making the head pointer look unchanged while
    //    the list structure is actually different.
    // =========================================================================
    static void demoABAProblem() throws InterruptedException {
        AtomicInteger shared = new AtomicInteger(100);

        // Thread 1: slow thread — reads A, is preempted, then CAS-es
        Thread thread1 = new Thread(() -> {
            int snapshot = shared.get();          // reads 100 (A)
            System.out.println("[T1] snapshot = " + snapshot);

            // Simulates preemption: sleep while T2 runs
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // CAS succeeds even though the value went A→B→A in between
            boolean swapped = shared.compareAndSet(snapshot, 999);
            System.out.println("[T1] CAS(" + snapshot + " → 999) succeeded: " + swapped
                    + "  (actual value now: " + shared.get() + ")");
            System.out.println("[T1] *** ABA problem: T1 is unaware the value changed twice ***");
        });

        // Thread 2: fast thread — changes A→B→A before T1 wakes up
        Thread thread2 = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            shared.set(200);                      // A → B
            System.out.println("[T2] changed 100 → 200 (A→B)");
            shared.set(100);                      // B → A
            System.out.println("[T2] changed 200 → 100 (B→A)  ← value looks the same as before");
        });

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
    }

    // =========================================================================
    // 4. ABA Fix: AtomicStampedReference
    //    Pairs the value with a monotonically-increasing version stamp.
    //    CAS must match BOTH the value AND the stamp, so A/stamp=1 ≠ A/stamp=3.
    // =========================================================================
    static void demoStampedFix() throws InterruptedException {
        // Initial value=100, stamp=0
        AtomicStampedReference<Integer> ref = new AtomicStampedReference<>(100, 0);

        Thread thread1 = new Thread(() -> {
            int[]     stampHolder = new int[1];
            int       snapshot    = ref.get(stampHolder);   // reads value + stamp
            int       snapStamp   = stampHolder[0];
            System.out.println("[T1] snapshot=" + snapshot + " stamp=" + snapStamp);

            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // CAS requires both value AND stamp to match
            boolean swapped = ref.compareAndSet(snapshot, 999, snapStamp, snapStamp + 1);
            System.out.println("[T1] CAS succeeded: " + swapped
                    + "  (stamp mismatch detected the A→B→A cycle)");
        });

        Thread thread2 = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            int[] s = new int[1];
            int v = ref.get(s);
            ref.compareAndSet(v, 200, s[0], s[0] + 1);   // A→B, stamp 0→1
            System.out.println("[T2] changed 100→200 stamp→1");
            v = ref.get(s);
            ref.compareAndSet(v, 100, s[0], s[0] + 1);   // B→A, stamp 1→2
            System.out.println("[T2] changed 200→100 stamp→2  ← stamp is now 2, not 0");
        });

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
    }

    // =========================================================================
    // 5. ABA Fix: AtomicMarkableReference
    //    Uses a single boolean "mark" bit instead of a counter.
    //    Coarser than a stamp — only distinguishes marked/unmarked, not how many
    //    times the value changed. Enough when you only care "was this touched?"
    // =========================================================================
    static void demoMarkableFix() throws InterruptedException {
        // Initial value=100, mark=false (untouched)
        AtomicMarkableReference<Integer> ref = new AtomicMarkableReference<>(100, false);

        Thread thread1 = new Thread(() -> {
            boolean[] markHolder = new boolean[1];
            int snapshot = ref.get(markHolder);
            System.out.println("[T1] snapshot=" + snapshot + " marked=" + markHolder[0]);

            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // CAS fails because T2 flipped the mark to true
            boolean swapped = ref.compareAndSet(snapshot, 999, markHolder[0], false);
            System.out.println("[T1] CAS succeeded: " + swapped
                    + "  (mark change detected the modification)");
        });

        Thread thread2 = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            ref.compareAndSet(100, 200, false, true);  // A→B, mark true
            System.out.println("[T2] changed 100→200 mark→true");
            ref.compareAndSet(200, 100, true, true);   // B→A, mark stays true
            System.out.println("[T2] changed 200→100 mark still true");
        });

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
    }

    public static void main(String[] args) throws InterruptedException {
        // --- 1. Manual CAS ---
        System.out.println("=== 1. Manual CAS simulation ===");
        ManualCAS cas = new ManualCAS(42);
        System.out.println("CAS(42→100): " + cas.compareAndSwap(42, 100));  // true
        System.out.println("CAS(42→200): " + cas.compareAndSwap(42, 200));  // false, value is now 100
        System.out.println("Value: " + cas.get());                           // 100

        // --- 2. Lock-free counter ---
        System.out.println("\n=== 2. Lock-free counter (10 threads × 1000 increments) ===");
        LockFreeCounter counter = new LockFreeCounter();
        Thread[] workers = new Thread[10];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(() -> { for (int j = 0; j < 1000; j++) counter.increment(); });
            workers[i].start();
        }
        for (Thread w : workers) w.join();
        System.out.println("Expected: 10000  Got: " + counter.get());

        // --- 3. ABA problem ---
        System.out.println("\n=== 3. ABA Problem (CAS unaware of A→B→A cycle) ===");
        demoABAProblem();

        // --- 4. Fix with AtomicStampedReference ---
        System.out.println("\n=== 4. ABA Fix: AtomicStampedReference ===");
        demoStampedFix();

        // --- 5. Fix with AtomicMarkableReference ---
        System.out.println("\n=== 5. ABA Fix: AtomicMarkableReference ===");
        demoMarkableFix();
    }
}