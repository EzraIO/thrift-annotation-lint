# ThriftAnnotationLint

[![CI](https://github.com/EzraIO/thrift-annotation-lint/actions/workflows/ci.yml/badge.svg)](https://github.com/EzraIO/thrift-annotation-lint/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/EzraIO/thrift-annotation-lint?include_prereleases&sort=semver)](https://github.com/EzraIO/thrift-annotation-lint/releases)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.ezraio/thrift-annotation-lint.svg)](https://central.sonatype.com/artifact/io.github.ezraio/thrift-annotation-lint)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-007396)](#compatibility)
[![License](https://img.shields.io/github/license/EzraIO/thrift-annotation-lint)](LICENSE)

> Catch broken Facebook Swift and Airlift Drift `@Thrift*` models during
> compilation—not during application startup or codec initialization.

ThriftAnnotationLint is a zero-runtime-dependency Java annotation processor. It
finds duplicate field IDs, invalid constructors and unions, incompatible Java
types, and undeclared recursive models while `javac` can still point directly
to the offending source element.

- **Find failures earlier:** turn metadata and codec initialization failures
  into compiler diagnostics.
- **Adopt safely:** audit an existing model base in `warning` mode before
  enabling build-breaking `strict` mode.
- **Keep builds lean:** the processor uses standard JSR 269 APIs and adds no
  application runtime dependencies.
- **Use either dialect:** verified against Facebook Swift and Airlift Drift.

> **Project status:** `0.2.2` is a preview release. The processor is verified
> against the public Facebook Swift `0.19.2`, `0.20.0`, `0.21.1`, `0.22.1`, and
> `0.23.1` contracts and the Airlift Drift `1.18` annotation contract, plus deliberate
> compile-time safety extensions described below. Its rule and diagnostic
> contracts may evolve before `1.0.0`.

## See a production-shaped failure at the source

The dangerous mistakes are rarely isolated in a two-field demo. They are more
often introduced when a mature request model gains one more DTO or when a
developer copies an existing annotation and forgets to change its field ID.
Both changes below are valid Java and are easy to miss in a large review
(imports are omitted and the classes would normally live in separate files):

```java
@ThriftStruct
public class CreateOrderRequest {
    @ThriftField(1)
    public String requestId;

    @ThriftField(2)
    public long customerId;

    @ThriftField(3)
    public List<OrderLine> lines;

    @ThriftField(4)
    public Map<String, String> attributes;

    @ThriftField(7)
    public String promotionCode;

    // Added during a checkout refactor. Address is an ordinary Java DTO,
    // but it was never declared as a Thrift struct.
    @ThriftField(8)
    public Address shippingAddress;

    // Copied from promotionCode. The name changed, but field ID 7 did not.
    @ThriftField(7)
    public FraudContext fraudContext;
}

// Missing @ThriftStruct and @ThriftField metadata.
public class Address {
    public String city;
    public String street;
    public String postalCode;
}

@ThriftStruct
public class OrderLine {
    @ThriftField(1)
    public String sku;

    @ThriftField(2)
    public int quantity;
}

@ThriftStruct
public class FraudContext {
    @ThriftField(1)
    public String deviceId;
}
```

The compiler sees valid field types and valid annotation syntax; it does not
know that `Address` has no Swift/Drift codec shape or that IDs form one schema
across the entire class. Without an earlier check, either problem can surface
only when Swift or Drift builds runtime metadata or initializes a codec.

ThriftAnnotationLint points back to both declarations during the same build:

```text
error: [AW4001] Thrift model 'example.CreateOrderRequest' field
'shippingAddress' uses unsupported Java type 'example.Address'.

error: [AW2002] Thrift model 'example.CreateOrderRequest' uses field ID 7
for different logical fields [promotionCode, fraudContext].
```

The fixes are local and explicit: make `Address` a real Thrift model (and
annotate its serialized members), and assign `fraudContext` a new, never-reused
field ID. The value of the check is that neither mistake reaches packaging,
codec initialization, or production traffic first.

It also catches missing read/write paths, invalid constructors and builders,
unsafe union definitions, incompatible Java types, undeclared recursive edges,
and invalid exact generic models reached through source or classpath references.

## Why check during compilation?

Java's type system can verify that an annotation is syntactically valid, but it
does not understand the schema formed by several `@ThriftField` declarations.
Swift and Drift normally assemble that schema later, while building runtime
metadata or a codec. By then the code has already compiled, and the failure may
appear only in a test environment or after an application starts.

ThriftAnnotationLint runs as a standard JSR 269 annotation processor so it can:

- report the error on the exact field, method, or constructor parameter that
  introduced it;
- fail the same `javac`, Maven, Gradle, or CI build that introduced the invalid
  model;
- validate source models together with the exact generic models they reach on
  the classpath, without loading application classes or executing user code;
- keep the checker out of the application runtime—the published JAR is needed
  only on the annotation-processor path.

### Why this matters in a staged release pipeline

Enterprise services often publish model or client JARs before releasing the
server that consumes them. If an annotation mistake is discovered only during
codec initialization, integration tests, application startup, or deployment,
the feedback arrives after packaging and potentially after a long release
pipeline has already run.

When ThriftAnnotationLint is enabled in the application build, reachable models
from the current source set and classpath are checked during `javac`. The
consumer service therefore fails at compilation—with an exact source
location—before slow tests, packaging, deployment, or rollout begin.

This shortens the feedback loop, avoids publishing a server artifact that
cannot initialize its Thrift codec, and reduces the risk of discovering
metadata errors in production. Runtime codec and integration tests are still
required for behavior that depends on executable values or runtime
configuration.

Static analysis cannot prove behavior that requires running application code,
so codec round-trip tests still complement these checks. See
[Compile-time and runtime boundary](#compile-time-and-runtime-boundary).

## Quick start

Version `0.2.2` is published on
[Maven Central](https://central.sonatype.com/artifact/io.github.ezraio/thrift-annotation-lint/0.2.2).
For an established codebase, begin with `warning` mode and switch to `strict`
after reviewing the findings.

### Maven

```xml
<dependencies>
    <!-- Choose the annotation library used by your models. -->
    <dependency>
        <groupId>com.facebook.swift</groupId>
        <artifactId>swift-annotations</artifactId>
        <version>0.23.1</version>
    </dependency>
    <!-- Airlift Drift alternative:
    <dependency>
        <groupId>io.airlift.drift</groupId>
        <artifactId>drift-api</artifactId>
        <version>1.18</version>
    </dependency>
    -->
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
                        <groupId>io.github.ezraio</groupId>
                        <artifactId>thrift-annotation-lint</artifactId>
                        <version>0.2.2</version>
                    </path>
                </annotationProcessorPaths>
                <compilerArgs>
                    <arg>-Athrift.annotation.lint.mode=strict</arg>
                </compilerArgs>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### Gradle

```groovy
repositories {
    mavenCentral()
}

dependencies {
    // Use this for Facebook Swift models:
    compileOnly "com.facebook.swift:swift-annotations:0.23.1"
    // Or this for Airlift Drift models:
    // compileOnly "io.airlift.drift:drift-api:1.18"
    annotationProcessor "io.github.ezraio:thrift-annotation-lint:0.2.2"
}

tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += ["-Athrift.annotation.lint.mode=strict"]
}
```

The JAR registers itself through the standard annotation-processor service file
and declares Gradle aggregating behavior, so no explicit `-processor` class name
is required.

The runnable example catalog is available under
[`examples/maven`](examples/maven/README.md).

## What it catches

| Area | Examples | Diagnostic families |
| --- | --- | --- |
| Model declarations | Invalid visibility, annotation combinations, or mixed Swift/Drift dialects | `AW1001` |
| Field metadata | Missing, duplicate, or conflicting IDs; conflicting names, requiredness, or IDL annotations | `AW2001`–`AW2007` |
| Access and construction | Missing read/write paths, ambiguous getters, invalid constructors, setters, builders, or modifiers | `AW3001`–`AW3005` |
| Java types | Unsupported field types, incompatible member types, unsafe nested containers, or undeclared recursive edges | `AW4001`–`AW4003` |
| Unions | Missing or invalid discriminators, non-deterministic construction, invalid requiredness, or payload ID `0` | `AW5001`–`AW5004` |
| Enums | Invalid enum value methods or multiple Drift unknown-value fallbacks | `AW6001`–`AW6002` |
| Processor safety | Invalid options, internal failures, or an unbounded exact generic model graph | `AW9001`–`AW9003` |

See the [complete rule reference](docs/rules.md) for each stable diagnostic code,
deliberately stricter safety rule, and runtime-only limitation.

## Supported scope

ThriftAnnotationLint scans these model annotations:

- `@ThriftStruct`
- `@ThriftUnion`
- `@ThriftEnum`
- `@ThriftField`
- `@ThriftConstructor`
- `@ThriftUnionId`
- `@ThriftEnumValue`
- Drift `@ThriftEnumUnknownValue`

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
- values that require executing user code
- service-only types such as `ListenableFuture<T>` on model fields

The release-to-release boundary is deliberate. Questions from real Thrift users often
involve retiring and reusing field IDs, changing field types, or weakening a published
`required` field. Those checks require an old schema/model baseline, not just the current
JSR 269 compilation. This processor catches unsafe metadata inside the current build; a
future compatibility mode may compare a committed baseline separately.

See [Rule reference](docs/rules.md) for the diagnostic categories and known
runtime-only boundaries.

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

## Compatibility

| Component | Supported baseline |
| --- | --- |
| Processor bytecode | Java 8 |
| JDK used to compile applications | 8, 11, 17, 21 |
| Facebook Swift | See the verified release matrix below |
| Airlift Drift | `1.18` annotations verified; see notes below |
| Maven | 3.6.1 or newer |

The processor artifact has no runtime dependencies and uses only the standard
JSR 269 compiler APIs. Facebook Swift, Airlift Drift, and their transitive dependencies are used
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

Airlift Drift `1.18` is the newest release whose published artifacts retain Java 8
bytecode and is therefore the preview's verified Drift baseline. Newer Drift releases
use the same core model annotations but require newer Java runtimes; they are not yet
claimed as verified. Drift model discovery, fields, constructors, builders, unions,
enums, IDL annotations, and shared Java type rules are supported. One model must use
one annotation dialect consistently; mixing `com.facebook.swift.codec.*` and
`io.airlift.drift.annotations.*` annotations is rejected.
Drift enums must expose exactly one valid `@ThriftEnumValue` method; Swift enums
continue to allow zero or one. An unannotated Java enum inherits the dialect of
the model that references it and is validated independently when reached from
both dialects. An explicitly annotated Swift model cannot be referenced by a
Drift model (or vice versa); this is reported as `AW1001` at the reference site.

Drift fields support `Optional<T>`, `OptionalInt`, `OptionalLong`, and
`OptionalDouble`. Generic Optional elements are checked recursively, including
nested containers and models, and normalize to the element wire type. Raw
`Optional` and unsupported element types remain `AW4001`. Optional types are not
accepted for Facebook Swift models.

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

Airlift Drift `1.18` instead checks `@ThriftField(name=...)`, then its parameter-name
reader (method metadata, bytecode debug names, and finally reflection fallback). For
deterministic build/runtime parity, Drift injection parameters should declare an explicit
field ID or name when no stable bytecode name is available; the processor rejects an
identity that would otherwise depend on compiler debug or `-parameters` settings.

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

## Runnable Maven demo

From a source checkout, run:

```bash
sh examples/maven/run-demo.sh
```

The script installs the current checkout into the local Maven repository, then
compiles a catalog of valid and intentionally invalid Swift models. It verifies
every runnable stable user-facing rule category in warning mode, demonstrates
strict rejection for duplicate IDs, and checks the always-error option and
exact-model-budget safeguards. AW9002 is the non-runnable internal safety net
and is documented in the case catalog. Run
`sh examples/maven/run-demo.sh --all-modes` to check every regular rule in
both warning and strict mode. See [the example README](examples/maven/README.md)
for the case catalog and equivalent manual commands.

CI runs the default `0.23.1` suite on JDK 8, 11, 17, and 21, and runs the full
suite for each exact Swift release in the compatibility matrix on JDK 8. The
project compiles its own processor with annotation processing disabled
(`proc:none`) and validates the packaged service metadata through tests. The
suite also runs official Swift metadata fixtures and builder/union codec round
trips, plus Drift annotation/compiler compatibility fixtures.

Maintainers should read [Architecture and behavioral invariants](docs/architecture.md)
before changing round handling, type resolution, extraction, validation, or
diagnostic routing.

## Independence and licensing

ThriftAnnotationLint is an independent project. It is not affiliated with, endorsed by, or
an official component of the Apache Software Foundation, Meta, Facebook, or
Airlift. Facebook Swift and Airlift Drift are separate projects and remain subject to
their own licenses.

ThriftAnnotationLint is licensed under the
[Apache License, Version 2.0](LICENSE).
