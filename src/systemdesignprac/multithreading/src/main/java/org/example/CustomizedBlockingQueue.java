package org.example;

public class CustomizedBlockingQueue {

    // ========== 自定义 BlockingQueue ==========
    static class MyBlockingQueue {
        private final int[] buffer;
        private final int capacity;
        private int head = 0;   // 取数据的指针
        private int tail = 0;   // 放数据的指针
        private int size = 0;

        public MyBlockingQueue(int capacity) {
            this.capacity = capacity;
            this.buffer = new int[capacity];
        }

        /**
         * 放入数据；队列满时阻塞，直到有空位
         */
        public synchronized void put(int value) throws InterruptedException {
            while (size == capacity) {
                System.out.println(Thread.currentThread().getName()
                        + " >>> 队列已满(" + size + "/" + capacity + ")，put(" + value + ") 被阻塞...");
                wait();  // 释放锁并挂起，等待 take() 唤醒
            }
            buffer[tail] = value;
            tail = (tail + 1) % capacity;
            size++;
            System.out.println(Thread.currentThread().getName()
                    + " put(" + value + ")  队列: " + toString());
            notifyAll();  // 唤醒可能阻塞的 take()
        }

        /**
         * 取出数据；队列空时阻塞，直到有数据
         */
        public synchronized int take() throws InterruptedException {
            while (size == 0) {
                System.out.println(Thread.currentThread().getName()
                        + " >>> 队列为空，take() 被阻塞...");
                wait();  // 释放锁并挂起，等待 put() 唤醒
            }
            int value = buffer[head];
            head = (head + 1) % capacity;
            size--;
            System.out.println(Thread.currentThread().getName()
                    + " take() = " + value + "  队列: " + toString());
            notifyAll();  // 唤醒可能阻塞的 put()
            return value;
        }

        @Override
        public synchronized String toString() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < size; i++) {
                sb.append(buffer[(head + i) % capacity]);
                if (i < size - 1) sb.append(", ");
            }
            sb.append("] (").append(size).append("/").append(capacity).append(")");
            return sb.toString();
        }
    }

    // ========== 主程序 ==========
    public static void main(String[] args) throws InterruptedException {
        MyBlockingQueue queue = new MyBlockingQueue(5);

        // Thread1: 生产者，put 1-10
        Thread producer = new Thread(() -> {
            for (int i = 1; i <= 10; i++) {
                try {
                    queue.put(i);
                    Thread.sleep(100);  // 模拟生产耗时
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println(Thread.currentThread().getName() + " 生产完毕。");
        }, "Thread-1(Producer)");

        // Thread2: 消费者，持续 take
        Thread consumer = new Thread(() -> {
            for (int i = 1; i <= 10; i++) {
                try {
                    int val = queue.take();
                    Thread.sleep(300);  // 消费比生产慢，触发阻塞
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println(Thread.currentThread().getName() + " 消费完毕。");
        }, "Thread-2(Consumer)");

        producer.start();
        consumer.start();

        producer.join();
        consumer.join();
        System.out.println("所有任务完成。");
    }
}