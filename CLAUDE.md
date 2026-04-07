# System Design Interview Prep

This repo is a practice environment for system design interviews targeting mid-level (L4) roles.

## Context Files

- **Concepts** — `src/references/concepts/`
  - One file per topic (e.g., `relationaldb.md`, `nosql.md`, `concepts.md`)
  - To find relevant concepts: glob `src/references/concepts/**`, then read the matching file(s)

- **Example solutions** — `src/references/examples/`
  - One file per system design problem (e.g., `rate-limiter.md`, `url-shortener.md`)
  - Match the interview topic to the file name; read only that file

## How to Use

- Glob the concepts directory first, then read only the file(s) relevant to the current topic
- Read the matching example file before generating model answers, scoring designs, or teaching a system
- If no matching example exists, generate from first principles using the concept files