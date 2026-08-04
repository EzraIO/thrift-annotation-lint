# Maven demo case matrix

This standalone Maven project is a runnable catalog of the stable
ThriftAnnotationLint diagnostics. Every source directory under cases contains
one focused case. The demo intentionally compiles invalid models so the
processor can show the same feedback an application build receives.

The default Maven invocation compiles the valid examples. It does not fail just
because the repository also contains negative fixtures.

## Run the verified matrix

From the repository root:

~~~
sh examples/maven/run-demo.sh
~~~

The script builds and installs the processor from the current checkout, then
verifies all cases declared in cases.txt:

- valid-models, valid-airlift-drift, and valid-prestodb-drift succeed in strict
  mode without a ThriftAnnotationLint diagnostic;
- every regular model-rule case succeeds in warning mode and reports only its
  expected diagnostic code;
- duplicate-field-id also demonstrates that strict mode fails;
- builder-constructor-advisory demonstrates the one intentional advisory:
  Swift ignores a public model constructor when a builder is configured, so
  AW3005 is a warning even in strict mode;
- invalid-processor-option and exact-model-budget demonstrate their
  always-error behavior.

To verify every regular rule in both warning and strict mode, run:

~~~
sh examples/maven/run-demo.sh --all-modes
~~~

The runner checks the process result and stable AW code, and rejects unrelated
ThriftAnnotationLint diagnostics in the same case. It intentionally does not
lock full English wording, line numbers, or Maven's rendering format.

## Case catalog

| Case directory | Diagnostic | Expected behavior |
| --- | --- | --- |
| valid-models | none | Valid field, constructor, builder, union, enum, recursive, and nested-container models compile cleanly. |
| valid-airlift-drift | none | Airlift Drift fields, `Optional`, and nested models compile cleanly. |
| valid-prestodb-drift | none | The PrestoDB Drift annotation namespace compiles with the same Drift rules. |
| mixed-drift-namespace | AW1001 | One model mixes Airlift and PrestoDB Drift annotation namespaces. |
| invalid-model-declaration | AW1001 | A public type declares two model annotations. |
| missing-field-id | AW2001 | A field has no resolvable Thrift ID. |
| duplicate-field-id | AW2002 | Two logical fields reuse one ID. |
| conflicting-field-id | AW2003 | Getter and setter use different IDs. |
| conflicting-field-name | AW2004 | Getter and setter use different names. |
| conflicting-requiredness | AW2005 | Getter and setter use different requiredness. |
| invalid-legacy-id | AW2006 | A negative ID omits its legacy declaration. |
| conflicting-idl-annotations | AW2007 | Getter and setter use different IDL annotations. |
| read-only-field | AW3001 | A field has no injection path. |
| multiple-constructors | AW3002 | A model has two Thrift constructors. |
| invalid-setter | AW3003 | A setter has an invalid signature. |
| invalid-member-modifier | AW3004 | An annotated field is private. |
| invalid-builder | AW3005 | A configured builder has no valid build method. |
| builder-constructor-advisory | AW3005 | A valid builder causes a public model constructor to be ignored; this remains a warning. |
| unsupported-java-type | AW4001 | Object has no supported Swift representation. |
| incompatible-accessor-types | AW4002 | Getter and setter types disagree. |
| undeclared-recursion | AW4003 | A direct recursive edge is not declared optional and recursive. |
| missing-union-id | AW5001 | A union has no discriminator. |
| incomplete-union-construction | AW5002 | A union constructor does not provide deterministic per-variant construction. |
| required-union-field | AW5003 | A union payload is required. |
| union-field-id-zero | AW5004 | A union payload uses the unsafe ID zero. |
| invalid-enum-value-method | AW6001 | An enum value method has an invalid signature. |
| invalid-processor-option | AW9001 | An unsupported processor mode is always an error. |
| exact-model-budget | AW9003 | The exact-model budget is deliberately exceeded and is always an error. |

AW9002 is not a runnable source case. It is the processor's unexpected-failure
safety net, not a valid model shape. Reproducing it would require deliberately
breaking the processor, so it is deliberately omitted from an application
example.

## Run one case manually

Install the processor from this checkout, then select a case directory:

~~~
mvn -Dmaven.test.skip=true clean install
mvn -f examples/maven/pom.xml clean compile
mvn -f examples/maven/pom.xml -Ddemo.case=duplicate-field-id \
    -Dthrift.annotation.lint.mode=warning clean compile
mvn -f examples/maven/pom.xml -Ddemo.case=duplicate-field-id clean compile
~~~

The first two commands succeed. The warning-mode duplicate-ID command succeeds
and prints AW2002. The final strict command deliberately returns a non-zero
exit code with AW2002.

For the budget case, lower the option as the manifest does:

~~~
mvn -f examples/maven/pom.xml -Ddemo.case=exact-model-budget \
    -Dthrift.annotation.lint.mode=warning \
    -Dthrift.annotation.lint.maxExactModels=1 clean compile
~~~

That command deliberately fails with AW9003 even in warning mode. See the
[rule reference](../../docs/rules.md) for definitions and rollout guidance.
