# Contributing

Thank you for helping improve ThriftAnnotationLint. The project is still a preview, so every
behavior change should be small, explicit, and backed by a compiler test.

## Development requirements

- JDK 8, 11, 17, or 21
- Maven 3.6.1 or newer

Run the complete local check before opening a pull request:

```bash
mvn verify
```

## Change expectations

- Use only standard JSR 269 and Java 8 APIs in production code.
- Do not add a production dependency without first documenting why the standard
  JDK APIs are insufficient.
- Add a successful compilation case and a failing compilation case for each new
  validation rule.
- Keep diagnostic codes stable. New diagnostics must use the `[AWxxxx]` prefix,
  be written in English, and point to a relevant source element.
- Do not execute or load application classes during annotation processing.
- Update `docs/rules.md` and `CHANGELOG.md` when public behavior changes.

## Compatibility changes

A change that accepts a previously rejected model is normally a bug fix. A
change that rejects a previously accepted model needs a migration note and a
warning-mode rollout path. If behavior is meant to match Facebook Swift, include
the equivalent runtime metadata result in the issue or pull-request description.

## Licensing status

Contributions intentionally submitted for inclusion are licensed under the
[Apache License, Version 2.0](LICENSE), unless agreed otherwise in writing.
