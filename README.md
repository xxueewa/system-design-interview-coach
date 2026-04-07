# System Design Interview Prep

A hands-on interview preparation toolkit combining a built-in system design knowledge base, AI-powered mock interviews, and working Java code implementations.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## Features

https://github.com/user-attachments/assets/70c4516f-0972-407b-9006-5f40845775a0



- AI-powered mock system design interviews via Claude Code skill
- Structured concept reference library (CAP theorem, isolation levels, locking, and more)
- Full reference answer walkthroughs for common interview questions
- Runnable Java implementations for each design problem
- Structured evaluation rubric for self-assessment

## Getting Started

### Prerequisites

- [Claude Code CLI](https://claude.ai/code)
- Java 11+

### Setup

1. Clone the repo:
   ```bash
   git clone https://github.com/your-username/System-Design-Interview-Prep.git
   cd System-Design-Interview-Prep
   ```

2. Run the setup script to install the Claude Code skill:
   ```bash
   bash src/setup.sh
   ```

3. Open Claude Code in the project directory and invoke the skill:
   ```
   /sysdesigninterviewprep
   ```

4. Choose a mode — mock interview, review an answer, or learn a concept.

## Running the Java Code

Compile and run from the project root:

```bash
javac -d out src/systemdesignprac/RateLimiter.java src/Main.java
java -cp out Main
```

## Project Structure

```
System-Design-Interview-Prep/
├── CLAUDE.md                              # Auto-loaded context for Claude Code
├── LICENSE
├── README.md
└── src/
    ├── setup.sh                           # Installs the Claude Code skill
    ├── Main.java
    ├── references/
    │   ├── concepts/                      # Per-topic concept explanations
    │   │   ├── concepts.md                # CAP theorem, circuit breaker
    │   │   ├── relationaldb.md            # ACID, isolation levels, locking
    │   │   └── nosql.md
    │   └── examples/                      # Full reference answers per system
    │       ├── rate-limiter.md
    │       └── url-shortener.md
    ├── skills/
    │   └── sysdesigninterviewprep/
    │       └── SKILL.md                   # Claude Code interview coach skill
    └── systemdesignprac/                  # Java implementations
        ├── RateLimiter.java
        ├── CircuitBreaker.java
        └── ...
```

## Topics Covered 

| #  | Topic                  | Summary                                                                                                      |
|----|------------------------|--------------------------------------------------------------------------------------------------------------|
| 1  | Design A Rate Limiter  | Token Bucket, Sliding Window Log, Fixed Window Counter; client- vs server-side; Redis-based distributed impl |
| 2  | Design A URL Shortener | Register short url and supports redirection                                                                  |
(In Progress)
## Contributing

Contributions are welcome! You can help by:

- Adding new concept explanations to `src/references/concepts/`
- Adding reference answer walkthroughs to `src/references/examples/`
- Implementing Java code for topics not yet covered
- Fixing errors or improving existing explanations

Please open an issue before submitting a large change so we can discuss the approach.

## License

This project is licensed under the [MIT License](LICENSE).
