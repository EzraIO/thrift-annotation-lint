# Maven demo

This standalone Maven project contains an intentionally invalid model: two
logical fields use Thrift field ID `7`. It demonstrates that
ThriftAnnotationLint reports the issue during compilation, before a runtime
codec is created.

## Run everything

From the repository root:

```bash
sh examples/maven/run-demo.sh
```

The script performs two compilations:

1. Warning mode succeeds and prints `AW2002`.
2. Strict mode fails with `AW2002`; this failure is expected, so the script
   finishes successfully after confirming it happened.

Expected diagnostic text:

```text
[AW2002] Thrift model 'example.DuplicateIds' uses field ID 7 for different logical fields [first, second].
```

## Run manually

Install the processor snapshot from this checkout, then choose a mode:

```bash
mvn -Dmaven.test.skip=true clean install
mvn -f examples/maven/pom.xml -Pwarning clean compile
mvn -f examples/maven/pom.xml clean compile
```

The final command uses strict mode by default and is expected to return a
non-zero exit code.
