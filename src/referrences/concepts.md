

## Failure Modes in Highly Available System 

## Solutions
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

