# Contributing to OCC Card Pricer

Thank you for your interest in contributing. This document explains how to report issues and submit changes.

## Reporting Issues

Open a GitHub issue with:

- A clear title describing the problem or request
- Steps to reproduce (for bugs)
- Expected vs. actual behavior
- Application version (`Help → About` or `app-version.properties`)
- Operating system and Java version

## Submitting Changes

1. Fork the repository and create a branch from `master`.
2. Make your changes. Follow the existing code style and project structure.
3. Add or update tests for any changed behavior. Tests must pass headless with no external service dependencies.
4. Run `./mvnw clean verify` (or `.\mvnw.cmd clean verify` on Windows) and confirm all tests pass.
5. Open a pull request against `master` with a clear description of the change and its motivation.

## Development Setup

- Install JDK 25 and set `JAVA_HOME`.
- Maven Wrapper is included — no separate Maven installation needed.
- IDE support: IntelliJ IDEA, VS Code, and Eclipse configurations are present in the repository.
- Source is in `src/`, tests in `test/`. The build uses non-standard source directories (configured in `pom.xml`).

## Code Guidelines

- Keep changes focused. One pull request per logical change.
- Match the existing naming conventions and package structure.
- Tests should use in-memory preferences and temporary directories (see `testsupport/`).
- Do not add external service dependencies to the test suite.
- Trade and pricing logic changes should include corresponding test coverage.

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
