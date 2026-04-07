## CAP theorem
CAP stands for Consistency, Availability and Partition tolerance.
Any distributed system can provide at most two of the following three guarantees. 
### 1. Consistency
Every read receives the most recent write or an error. Consistency means that all clients 
see the same data at the same time, no matter which node they connect to.For this to happen, 
whenever data is written to one node, it must be instantly forwarded or replicated to all 
the other nodes in the system before the write is deemed ‘successful’.

### 2. Availability
Every request received by a non-failing node in the system must result in a response, 
without the guarantee that it contains the most recent version of the data

### 3. Partition Tolerance
The system continues to operate despite an arbitrary number of messages 
being dropped (or delayed) by the network between nodes.
When a network partition failure happens, it must be decided whether to do one of the following:
1. cancel the operation and thus decrease the availability but ensure consistency.
2. proceed with the operation and thus provide availability but result in inconsistency.

Therefore, the guarantee combination normally are AP or CP.

## Failure Modes in Highly Available System
### 1. Circuit Breaker
The Circuit Breaker pattern helps handle faults that might take varying amounts of time to 
recover from when an application connects to a remote service or resource. A circuit breaker 
temporarily blocks access to a faulty service after it detects failures. This action prevents 
repeated unsuccessful attempts so that the system can recover effectively. This pattern can 
improve the stability and resiliency of an application.

In a distributed environment, calls to remote resources and services can fail 
because of transient faults. Transient faults include overcommitted or temporarily 
unavailable resources, slow network connections, or time-outs. These faults typically 
correct themselves after a short period of time. To help manage these faults, you should 
design a cloud application to use a strategy, such as 'Circuit Breaker pattern serves + Retry pattern'.
An application can combine these two patterns by using the Retry pattern to invoke 
an operation through a circuit breaker. However, the retry logic should be sensitive 
to any exceptions that the circuit breaker returns and stop retry attempts if the circuit 
breaker indicates that a fault isn't transient.

#### Implementation 
A circuit breaker acts as a proxy. The proxy could be implemented as a state machine that 
includes the following states: Closed, Open, Half-Open.

**Closed**: The request from the application is routed to the operation. 
The proxy maintains a count of the number of recent failures. If the call to 
the operation is unsuccessful, the proxy increments this count. If the number of recent 
failures exceeds a specified threshold within a given time period, the proxy is placed 
into the Open state and starts a time-out timer. When the timer expires, the proxy is 
placed into the Half-Open state.

**Open**: The request from the application fails immediately and an exception is returned to the application.

**Half-Open**: A limited number of requests from the application are allowed to pass through
and invoke the operation. If these requests are successful, the circuit breaker assumes 
that the fault that caused the failure is fixed, and the circuit breaker switches to the 
Closed state. The failure counter is reset. If any request fails, the circuit breaker 
assumes that the fault is still present, so it reverts to the Open state. It restarts 
the time-out timer so that the system can recover from the failure.

