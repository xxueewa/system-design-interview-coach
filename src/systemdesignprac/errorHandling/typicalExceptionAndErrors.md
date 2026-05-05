## CheckedException vs UncheckedException
### CheckedException
recoverable, normally caused by external resource (file system: FileNotFoundException, network issue: TimeoutException), could be caught and handled.

| Exception                 | Type    | Extends                      | Handle                                                                                                       |
|---------------------------|---------|------------------------------|--------------------------------------------------------------------------------------------------------------|
| IOException               | Checked | Exception                    | Use try-with-resources for streams/files;<br/>propagate via throws to caller if context is better there      |
| FileNotFoundException     | Checked | IOException                  | Validate path existence before opening, catch and return a meaningful result or re-throw as domain exception |
| SQLException              | Checked | Exception                    | Wrap in a domain-specific unchecked exception(RepositoryException) log at the boundary, not everywhere       |
| ParseException            | Checked | Exception                    | Catch and return `Optional.empty()` or a validation error, never swallow silently                              |
| InterruptedException      | Checked | Exception                    | Set the interrupt flag immediately using `Thread.currentThread().interrupt()`, then handle or rethrow          |
| CloneNotSupported         | Checked | Exception                    | use copy constructor or factory method over clone() to avoid this entirely                                   |
| ClassNotFoundException    | Checked | ReflectiveOperationException | Catch and fail fast with a clear error message; indicates a classpath/deployment issue                       |
| InvocationTargetException | Checked | ReflectiveOperationException | Unwrap with `getCause()` and re-throw the underlying exception; don't expose raw reflection errors             |
| MalformedURLException     | Checked | IOException                  | Validate URLS at construction time; wrap in unchecked if the URL is hard-coded                               |
| TimeoutException          | Checked | Exception                    | Catch, log with elapsed time, and decide: retry with back-off, degrade gracefully, or propagate to caller    |


### UncheckedException
unrecoverable, normally reveals the programming logic error, need to be fixed

| Exception                       | Type      | Extends                  | Handle                                                                                                     |
|---------------------------------|-----------|--------------------------|------------------------------------------------------------------------------------------------------------|
| NullPointerException            | Unchecked | RuntimeException         | Use `objects.requireNonNull()` at method entry; prefer Optional for nullable returns                       |
| ArrayIndexOutOfBoundsException  | Unchecked | RuntimeException         | Guard with bounds checks; use List over arrays where possible; never catch to hide logic bugs              |
| ClassCastException              | Unchecked | RuntimeException         | Use `instanceof` check before casting, generics eliminate most cases at compile time                       |
| IllegalArgumentException        | Unchecked | RuntimeException         | Throw proactively for invalid inputs; use Precondition or explicit guards at method start                  |
| IllegalStateException           | Unchecked | RuntimeException         | Throw to signal broken invariants; enforce state via design to prevent occurrence                          |
| NumberFormatException           | Unchecked | IllegalArgumentException | Validate input format first or use `Optional` returning parse helpers; catch only at user-input boundary   |
| UnsupportedOperationException   | Unchecked | RuntimeException         | Avoid in new code, signal interface over design, split the interface or provide a no-op with documentation |
| ConcurrentModificationException | Unchecked | RuntimeException         | Use iterator's `remove()` `CopyOnWriteArrayList` or collect-then-mutate pattern                            | 
| StackOverflowError              | Unchecked | Error                    | Fix the recursive logic, never catch this error.                                                           |
| OutOfMemoryError                | Unchecked | Error                    | Profile and fix memory leaks; tune heap settings, never catch this error.                                  |