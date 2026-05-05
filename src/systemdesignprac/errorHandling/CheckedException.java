class TrafficLight {

    private ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private Lock readLock = readWriteLock.readLock();
    private Lock writeLock = readWriteLock.writeLock();
    int greenLightRoad = 1;

    public TrafficLight() {

    }

    public void carArrived(
            int carId,           // ID of the car
            int roadId,          // ID of the road the car travels on. Can be 1 (road A) or 2 (road B)
            int direction,       // Direction of the car
            Runnable turnGreen,  // Use turnGreen.run() to turn light to green on current road
            Runnable crossCar    // Use crossCar.run() to make car cross the intersection
    ) throws InterruptedException {
        /**
         * Checked Exception, need to maintain the interrupted flag, otherwise the thread or higher-level code won't know
         * the states, could cause indefinite loop
         * Throw the exception to caller to handle
         */
        readLock.lock();
        if (roadId != greenLightRoad) {
            readLock.unlock();
            writeLock.lock();
            try {
                if (roadId != greenLightRoad) {
                    greenLightRoad = roadId;
                    turnGreen.run();
                }
                readLock.lock();
            } finally {
                writeLock.unlock();
            }
        }


        try {
            crossCar.run();
        } finally {
            readLock.unlock();
        }
    }
}