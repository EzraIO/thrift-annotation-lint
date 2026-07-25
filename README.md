# ThriftAnnotationLint

> A `javac` annotation processor that validates Java `@Thrift*` models before runtime.

ThriftAnnotationLint validates annotation-based Java models for Facebook Swift during
compilation. It moves model metadata failures from application startup or codec
initialization into `javac`, where developers receive an actionable source
diagnostic before packaging and deployment.

> **Project status:** `0.1.0-SNAPSHOT` is a preview. The processor is verified
> against the public Facebook Swift `0.19.2`, `0.20.0`, `0.21.1`, `0.22.1`, and
> `0.23.1` annotation and runtime metadata contracts, plus deliberate
> compile-time safety extensions described below. Its rule and diagnostic
> contracts may evolve before `1.0.0`.

## Why ThriftAnnotationLint?

Annotation-based models avoid generated IDL classes, but Facebook Swift normally
discovers many invalid definitions only when runtime metadata or codecs are
created. ThriftAnnotationLint keeps the annotation-based workflow and adds an earlier
feedback boundary:

```text
Java source -> ThriftAnnotationLint processor -> javac diagnostics -> package/deploy
```

Diagnostics are in English, carry a stable `AWxxxx` rule code, and point to the
model element or annotation value that caused the problem.

## Supported scope

ThriftAnnotationLint scans these model annotations:

- `@ThriftStruct`
- `@ThriftUnion`
- `@ThriftEnum`
- `@ThriftField`
- `@ThriftConstructor`
- `@ThriftUnionId`
- `@ThriftEnumValue`

The processor builds logical fields from Java fields, getters, setters,
constructor parameters, and builder methods before validating IDs, names,
requiredness, read/write paths, signatures, and recursively nested Java types.
Java records are supported when the application compiler supports them and the
record exposes annotated constructor and accessor paths.

Lombok-generated members are handled only when the generated method actually
carries `@ThriftField` and is visible to ThriftAnnotationLint during annotation processing.
Plain `@Data`, `@Getter`, or `@Setter` does not create Swift metadata, so ThriftAnnotationLint
does not guess an injection path from those annotations. If processors are
listed explicitly, run Lombok before ThriftAnnotationLint and keep a runtime codec test for
the generated model.

The preview does **not** validate:

- Thrift IDL files or the correctness of an IDL compiler's generated output
- RPC service annotations
- schema compatibility between releases
- Airlift Drift models
- values that require executing user code
- service-only types such as `ListenableFuture<T>` on model fields

See [Rule reference](docs/rules.md) for the diagnostic categories and known
runtime-only boundaries.

## Installation

The coordinates below describe the planned preview artifact. Until a release is
published, build and install the project locally with `mvn install`.

### Maven

Keep the Swift annotations on the application compile classpath and place
ThriftAnnotationLint on the annotation processor path:

```xml
<properties>
    <thrift.annotation.lint.version>0.1.0-SNAPSHOT</thrift.annotation.lint.version>
    <swift.version>0.23.1</swift.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.facebook.swift</groupId>
        <artifactId>swift-annotations</artifactId>
        <version>${swift.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.11.0</version>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>io.github.thriftannotationlint</groupId>
                        <artifactId>thrift-annotation-lint</artifactId>
                        <version>${thrift.annotation.lint.version}</version>
                    </path>
                </annotationProcessorPaths>
                <compilerArgs>
                    <arg>-Athrift.annotation.lint.mode=strict</arg>
                    <arg>-Athrift.annotation.lint.maxExactModels=512</arg>
                </compilerArgs>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### Gradle

```groovy
dependencies {
    compileOnly "com.facebook.swift:swift-annotations:0.23.1"
    annotationProcessor "io.github.thriftannotationlint:thrift-annotation-lint:0.1.0-SNAPSHOT"
}

tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += [
        "-Athrift.annotation.lint.mode=strict",
        "-Athrift.annotation.lint.maxExactModels=512"
    ]
}
```

The artifact declares the processor as Gradle-aggregating and registers it through
the standard `javax.annotation.processing.Processor` service provider file. Aggregating
classification is required because recursive-cycle validation relates multiple models in
the same compilation. The processor accepts all annotation rounds so it can inspect a
source enum whose only `@ThriftEnumValue` is inherited from a classpath interface; it
does not claim annotations and exits immediately for unrelated source roots.

## Safety extensions over official Swift

Swift metadata can represent some shapes that are legal to construct but unsafe
for a symmetric production codec. ThriftAnnotationLint intentionally rejects these shapes:

- read-only or write-only logical fields; every field must have both extraction
  and injection paths;
- different logical fields sharing one ID, even though Swift can merge members
  by ID and choose one name;
- final fields used as direct injection targets;
- abstract or non-static member construction types, and abstract builders;
- union ID members that are not writable primitive `short` values, because the
  default compiler codec does not box or unbox its discriminator path;
- unions that do not have a deterministic zero-argument or per-variant
  construction path;
- union payload field ID `0`, which collides with the default compiler codec's
  initial no-field discriminator;
- container extraction/injection paths whose concrete Java shape is not
  assignable to Swift's canonical `List`, `Set`, `Map`, or `ByteBuffer` codec
  shape, including unsafe nested concrete containers;
- multiple getter extraction paths, multiple field extraction paths when no
  getter deterministically replaces them, or multiple union method-injection
  paths for one logical field, because official metadata retains only one in
  the winning tier by unspecified reflection order;
- direct recursive model cycles with no edge marked `isRecursive=TRUE`;
- conflicting or duplicate metadata that Swift might otherwise resolve by
  iteration order.

These are product safety rules, not claims that the official runtime rejects
every shape. Use `-Athrift.annotation.lint.mode=warning` for the first rollout over an existing
codebase.

## Processor mode

`thrift.annotation.lint.mode` accepts two case-sensitive values:

| Value | Behavior |
| --- | --- |
| `strict` | Default. Model violations are compilation errors. |
| `warning` | Model violations are warnings so existing code can be audited before enforcement. |

Invalid options and internal processor failures always remain compilation
errors. The preview intentionally has no per-rule suppression mechanism.

`thrift.annotation.lint.maxExactModels` is a positive integer and defaults to `512`. It caps
additional exact generic model instances reached from source roots; the source
roots themselves do not consume this budget. An identity is charged only after
it is fully resolved and confirmed to be a model, so transient generated types
and declarations later classified as containers do not create false `AW9003`
diagnostics. Exceeding the budget reports `AW9003` instead of allowing a
branching or non-converging metadata graph to exhaust the compiler. Raise it
only for a reviewed finite graph.

Example diagnostic:

```text
[AW2002] Thrift field id 1 is used by both 'displayName' and 'userName'.
```

## Compatibility

| Component | Supported baseline |
| --- | --- |
| Processor bytecode | Java 8 |
| JDK used to compile applications | 8, 11, 17, 21 |
| Facebook Swift | See the verified release matrix below |
| Maven | 3.6.1 or newer |

The processor artifact has no runtime dependencies and uses only the standard
JSR 269 compiler APIs. Facebook Swift and its transitive dependencies are used
only by this project's test suite.

| Official Swift version | Annotation/compiler fixtures | Official metadata and codec fixtures |
| --- | --- | --- |
| `0.19.2` | Verified | Verified with the default compiler codec |
| `0.20.0` | Verified | Verified with the default compiler codec |
| `0.21.1` | Verified | Verified with the default compiler codec |
| `0.22.1` | Verified | Verified with the default compiler codec |
| `0.23.1` | Verified | Verified with the default compiler codec |

These five exact releases form the preview support matrix; compatibility is not
implied for every numerically intermediate release. Versions outside the
matrix, unverified forks, and source-incompatible variants are not claimed
compatible. A candidate version can be evaluated locally with
`mvn -Dswift.version=<version> verify`; passing is evidence for the covered API
and fixtures, not a guarantee for application-specific runtime configuration.
The codec guarantee covers Swift's default `CompilerThriftCodecFactory`.
`ReflectionThriftCodecFactory` is a custom runtime configuration and has
different union-builder invocation semantics; applications that select it must
retain factory-specific integration tests.

## Compile-time and runtime boundary

ThriftAnnotationLint never loads application classes or executes model methods. This is
important for safe, deterministic builds, but it means that some runtime facts
cannot be proven statically. For example, an `@ThriftEnumValue` method can have a
valid signature while returning duplicate or null values at runtime. Swift
runtime metadata validation remains the final authority for checks that depend
on executable values or runtime configuration.

Generic validation is demand-driven and use-site aware. Starting from annotated
source models, ThriftAnnotationLint follows reachable model references, substitutes the
exact concrete generic arguments into annotated members, and validates the
resulting instantiated shape even when the referenced model comes from the
classpath. Unrelated classpath models are not scanned. Unbound declaration type
variables and raw generic models are not rejected solely because no concrete
use-site binding exists. Custom `ThriftCatalog` coercions remain runtime-only.

Official Swift classifies Java enums before container interfaces. For other
declared types, it classifies container subtypes in `Map`, then `Set`, then
`Iterable` order before it inspects `@ThriftStruct` or `@ThriftUnion`. ThriftAnnotationLint
mirrors that precedence across generated-type rounds: an enum that implements
`Iterable` remains an enum, while model-looking members on an annotated
container class are ignored and its resolved element/key/value types and
reachable annotated models are still validated.
Concrete container roots may encode as their canonical interface but decode as
a canonical collection; direct root round trips remain an application
integration-test responsibility.

Official Swift releases in the support matrix use Paranamer's bytecode reader
for constructor and multi-parameter injection names. ThriftAnnotationLint therefore trusts
classpath names only when they are present in `LocalVariableTable`; a
`MethodParameters` attribute by itself is intentionally ignored. When LVT data
is absent, ThriftAnnotationLint models GeneralParanamer's deterministic `arg0`, `arg1`, ...
fallback in both Swift ID-inference passes. Parameters must still declare an
explicit `@ThriftField` ID or name as a codec-safety rule.

For Java 8 compatibility, classpath bytecode is read through the annotation
processor `CLASS_PATH`. If a model dependency is available only on a named
module path and parameter names cannot be supplied completely by annotations,
ThriftAnnotationLint fails closed with `AW3003` instead of guessing that LVT is absent. Put
that model dependency on the processor classpath or provide complete stable
annotation names.

For a declaration compiled in the current source round, JSR 269 exposes the
source name but cannot prove that the eventual class file will retain LVT data.
ThriftAnnotationLint consequently validates both the source/LVT name and the possible
no-LVT `argN` branch, and requires an explicit `@ThriftField` ID/name or a
stable annotation-provided name on source constructor and multi-parameter
injection parameters. It also mirrors
ThriftFieldParanamer's all-parameters annotation-name rule, including ordered
`@ThriftField(name=...)` and JSR-330 `@Named` annotations.

Annotation processors can also generate referenced types after an earlier
round has inspected their consumers. ThriftAnnotationLint defers findings for explicitly
unresolved shapes and rebuilds every historical source-root demand closure in
each later generated-source round. This also catches javac placeholders that
remain `DECLARED` instead of exposing an `ERROR` mirror. A temporary generated
symbol gap therefore cannot become an irreversible false diagnostic.

ThriftAnnotationLint is intended to prevent statically detectable metadata failures, not to
replace integration tests or runtime codec initialization tests.

## Preview upgrade policy

This is the first public preview and intentionally exposes no compatibility
aliases. Service discovery loads
`io.github.thriftannotationlint.ThriftAnnotationLintProcessor`; use that class
name only when an explicit `-processor` argument is required.

Start with `-Athrift.annotation.lint.mode=warning` when auditing an existing
model base, then switch to `strict` after resolving diagnostics. All user-facing
messages are English and use stable `AWxxxx` rule codes.

## Build

```bash
mvn verify
```

CI runs the default `0.23.1` suite on JDK 8, 11, 17, and 21, and runs the full
suite for each exact Swift release in the compatibility matrix on JDK 8. The
project compiles its own processor with annotation processing disabled
(`proc:none`) and validates the packaged service metadata through tests. The
suite also runs official Swift metadata fixtures and builder/union codec round
trips.

Maintainers should read [Architecture and behavioral invariants](docs/architecture.md)
before changing round handling, type resolution, extraction, validation, or
diagnostic routing.

## Independence and licensing

ThriftAnnotationLint is an independent project. It is not affiliated with, endorsed by, or
an official component of the Apache Software Foundation, Meta, Facebook, or
Airlift. Facebook Swift is a separate archived project and remains subject to
its own license.

ThriftAnnotationLint is licensed under the
[Apache License, Version 2.0](LICENSE).
