# Contributing

Thanks for contributing to `kotlin-rate-limiter`.

## Before You Start

- Open an issue before starting large changes so the design can be discussed first.
- Keep pull requests focused. Small, isolated changes are easier to review and release.
- If your change affects behavior, update docs and tests in the same pull request.

## Development Setup

Requirements:

- JDK 17
- A working Gradle environment

Common commands:

```bash
./gradlew check
./gradlew test
./gradlew ktlintCheck
```

## Project Expectations

- Preserve the public API unless the change is explicitly intended as a breaking change.
- Prefer coroutine-native behavior over thread-blocking designs.
- Keep docs aligned with implementation details and test coverage.
- Add or update tests for bug fixes, edge cases, and public API changes.

## Pull Request Checklist

- Tests pass locally.
- Documentation is updated if behavior or APIs changed.
- New behavior has test coverage.
- Commit history is clear enough for review.

## Reporting Bugs

Please include:

- library version
- Kotlin version
- JDK version
- minimal reproduction
- expected behavior
- actual behavior

## Feature Requests

Feature requests are welcome, but this project is intentionally small. Proposals are more likely to be accepted when they:

- fit the client-side rate-limiting scope
- preserve a simple public API
- avoid framework coupling
- come with motivating real-world examples
