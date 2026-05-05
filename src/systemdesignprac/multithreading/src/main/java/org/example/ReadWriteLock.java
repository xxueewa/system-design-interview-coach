package org.example;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class ReadWriteLock {

    Logger logger = Logger.getLogger(ReadWriteLock.class.getName());
    ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    Lock writeLock = readWriteLock.writeLock();
    Lock readLock = readWriteLock.readLock();
    AtomicInteger record = new AtomicInteger(0);

    /*
    user1 write: 1
    user 2 3 4 read
    user5 write: 2
    user 2 3 4 read
     */


    public void write() {
        writeLock.lock();
        try {
            record.getAndIncrement();
            logger.log(Level.INFO, Thread.currentThread().getName() + " wrote: " + record.get());
        } catch (Exception e) {
            logger.severe("Exception in write: " + e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public void read() {
        readLock.lock();
        try {
            System.out.println("Read from the record: " + record.get());
            logger.log(Level.INFO, Thread.currentThread().getName() + " read: " + record.get());
        } catch (Exception e) {
            logger.severe("Exception in read: " + e.getMessage());
        } finally {
            readLock.unlock();
        }
    }

    public static void main(String[] args) {
        ReadWriteLock implementation = new ReadWriteLock();
        ExecutorService threadPool = Executors.newFixedThreadPool(10);
        Random random = new Random();
        Set<Integer> randomRead = new HashSet<>();

        for (int i = 0; i < 10; i++) {
            randomRead.add(random.nextInt(50));
        }
        System.out.println(randomRead);

        for (int i = 0; i < 50; i++) {
            threadPool.submit(() -> implementation.write());
            if (randomRead.contains(i)) {
                threadPool.submit(() -> implementation.read());
            }
        }

        threadPool.shutdown();
    }

}
