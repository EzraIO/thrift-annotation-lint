# Architecture and behavioral invariants

ThriftAnnotationLint is a JSR 269 adapter around a deterministic validation pipeline. Its
internal classes are package-private implementation details; the only public
production class is `ThriftAnnotationLintProcessor`. This document records the constraints
that must remain true when the implementation is changed.

## Processing pipeline

For every relevant, non-terminal annotation round, processing follows this
order:

1. `RoundPlanner` snapshots pending work before clearing or aggregating any
   state for the new round.
2. Every current source type is registered in `CompilationState` before model
   validation starts.
3. Current source roots are ordered by qualified name. Historical source roots
   are rebuilt after current roots in later generated-source rounds; current
   containers likewise remain ahead of reclassified and historical containers.
4. `DemandClosure` schedules exact generic model identities in insertion order.
5. Each demand runs the fixed sequence: extract metadata, perform a late
   model-to-container migration, defer unresolved symbols, reserve exact-model
   budget, store the resolved model, validate it, then enqueue references.
6. Compilation-wide recursive cycles are validated after all resolved demands
   in the round.
7. `FindingRouter` deduplicates and emits the buffered findings in their stable
   semantic order.

The order above is part of the diagnostic contract. In particular, a finding
must never be emitted from a symbol graph that may still be completed by
another processor.

## Round and state ownership

`ValidationSession` is a thin lifecycle facade created once for one processor compilation.
`RoundValidationEngine` owns one `CompilationState`, one `ThriftTypeInspector`, and the
subsystem facades. `ReferenceDemandScheduler` owns container validation and referenced-model
queue expansion. No `TypeMirror`, `Element`, or resolved hierarchy view is cached by the type,
extraction, or validation subsystems across annotation rounds. Elements kept as
diagnostic anchors are held only by the compilation state and are discarded
when their exact model identity is released.

`CompilationState` exclusively owns:

- processed exact identities;
- round-local resolved models and immutable logical-field validation results
  used by cycle validation;
- current-compilation type names;
- pending raw model names, kinds, and dialects;
- dependency diagnostic anchors;
- source-root classification; and
- the exact-model budget.

Pending models are copied at round start and then aggregated for the whole
round. A resolved sibling must not erase an unresolved sibling with the same raw
model name.

## Source-root classification and migration

`SourceRootRegistry` represents three mutually exclusive states for a source
name: unknown, model, or container. It is mutable only through
`CompilationState`; callers receive immutable snapshots. Registering a model
removes its container state. Registering a container is allowed only for an
unknown or already-container source root; a model-to-container transition must
use the atomic migration operation.

A late model-to-container transition is coordinated by `CompilationState` as a
single operation. It changes the registry state and releases the former
resolved model, processed identity, exact-model reservation, and dependency
diagnostic anchor before the container is validated. This prevents transient
generated hierarchies from consuming budget or suppressing later work.

## Exact generic demand closure

`DemandClosure` owns candidate scheduling, generic ancestry, and expanding-cycle detection.
`ModelReferenceCollector` owns wire-type reference traversal, while
`TypeComplexityCalculator` owns the structural metric used by growth detection.
`RoundPlanner` delegates current and historical candidate discovery to dedicated collectors and
stable ordering to `RoundDemandOrdering`. `LinkedHashMap` and `LinkedHashSet` are
used deliberately: replacing them with unordered collections can change which
diagnostic is reported first or which recursive edge represents a cycle.
`DemandPath` is a persistent parent-linked path: appending a demand shares all ancestors instead
of copying the complete path, while same-raw-type lookup retains insertion order.

Source roots do not consume `ExactModelBudget`. A referenced exact identity is
charged only after extraction proves that the symbol graph is complete and the
type is still a model. Duplicate reservations are idempotent, and released
capacity is reusable. The first overflow produces `AW9003`; later overflows in
the same compilation do not create duplicate diagnostics.

## Type system

`ThriftTypeInspector` is the shared type facade:

- `TypeHierarchyResolver` resolves mirror-first hierarchy views, with an
  element fallback for incomplete javac symbols and generic substitution;
- `WireTypeClassifier` owns catalog lookup and shared classification precedence;
- `WireTypeSupport` and `DialectTypePolicy` apply recursive support and codec capabilities;
- `NormalizedWireTypeFormatter` and `CarrierShapeClassifier` own wire identity and Java wrapper
  compatibility rather than forwarding those decisions back to the catalog;
- `JavaTypeIdentityFormatter` creates the exact identity strings used by state
  and diagnostics; and
- `NormalizedTypeCompatibility` checks supported and canonical codec shapes.

Classification precedence is binary, enum, `Map`, `Set`, `Iterable`, then
the Drift-only Optional policy, and finally struct/union. Optional generic
elements are classified recursively; primitive Optional variants map to their
I32, I64, and DOUBLE wire shapes. The exact identity format is intentionally a string contract and
must remain character-for-character compatible. Incomplete symbols fail closed
and are deferred; they are not guessed from source text.

## Metadata extraction and class files

`SwiftModelExtractor` assembles a model from unresolved-symbol inspection,
member resolution, parameter-name resolution, and construction, field, union,
and enum extractors. It receives the current round's compilation type names as
an explicit argument and has no mutable cross-round extraction context.
Model declaration checks, builder type binding, executable generic restoration, and parameter
field identity each have a dedicated package-private owner; their public extraction order remains
coordinated by the existing facades.
The planner and extractor share one member resolver, which caches hierarchy and `getAllMembers`
results only for the active round.
Type classification and supertype views have the same round-local lifetime and are cleared before
historical roots are rebuilt.

`ThriftAnnotationDialect` is the boundary between shared metadata validation and
codec-specific annotation names. A model selects exactly one annotation dialect:
Facebook Swift, Airlift Drift, or PrestoDB Drift. The two Drift dialects share
runtime rules through an explicit Drift capability but retain exact annotation
packages, cache identities, and graph boundaries. Extraction never searches a
different dialect after selection, and mixed annotations fail before logical-field
validation. This keeps the existing `SwiftModel` representation internal while
preventing annotation families from being merged accidentally.
Demand, pending-round state, recursion vertices, and validation caches carry the
selected dialect explicitly. User-visible identities remain exact Java type
names, while internal cache keys combine dialect and exact identity so a plain
enum reached from multiple dialects cannot be incorrectly deduplicated. Explicit
model annotations take precedence over inherited reference dialects, and a
cross-dialect reference terminates that branch with `AW1001`.

Classpath parameter names are obtained through `ClasspathParameterNames`,
which bounds resource reads and cache weight. `JvmDescriptorEncoder` produces
declaration descriptors. `ClassFileConstantPoolReader`, `MethodDescriptorParser`, and the bounded
data reader isolate the binary-format mechanics used by `ClassFileParameterNameParser`, which
reads only the class-file structures needed to reproduce Paranamer 2.8. Local variable
slot widths, non-zero start positions, partial tables, annotation-name
all-or-nothing behavior, and the deterministic `argN` fallback are Swift compatibility
requirements. `MethodParameters` alone is not treated as name evidence. Drift parameter
resolution shares the bounded LVT reader but has its own annotation-name and failure
fallback rules in `ThriftParameterNameResolver`.

## Validation and diagnostics

`SwiftModelValidator` coordinates immutable logical-field resolution, ordered
field rules, union rules, and deterministic Tarjan cycle validation. Logical-field annotation,
type, access-path, and ID rules have separate package-private owners while the coordinator retains
their observable execution order. Model graph construction, iterative strongly connected
components, and representative cycle selection are likewise separate components. Primary
and no-LVT variants each resolve logical fields once. The primary result is
retained only for the active round and reused by cycle validation. Two-pass ID
inference, union discriminator checks, no-LVT relocation, same-location
deduplication, and cycle representative-edge selection are observable behavior.
Cycle findings use an SCC-wide semantic key only for cross-round deduplication;
their first representative edge, source anchor, message, and ordinary sort key
remain unchanged.

Diagnostic code, severity, English message, order, source line, element or
annotation-value anchor, and relocation prefix are all compatibility
properties. A refactor must preserve them even when the underlying rule result
is unchanged. Invalid options and internal failures remain errors in warning
mode. Characterization tests freeze every diagnostic field before temporary
compiler sources are removed, so line and annotation anchors cannot degrade
into post-compilation file-system artifacts.

## Dependency and compatibility boundaries

Production code uses Java 8 syntax, targets class-file major version 52, and has
no production dependency. Swift and Drift are referenced by qualified annotation names
and are test-scoped. Service registration, Gradle aggregating metadata, Maven
coordinates, processor options, and the absence of a rule SPI or `ServiceLoader`
extension mechanism are deliberate constraints.

The original 149 behavioral tests are a non-reducible characterization
baseline. Their exact method identities and enabled state are guarded, so a
replacement test cannot disguise removal of an original scenario. New
architecture and edge-case tests add to that baseline; they do not replace it.
Compatibility changes require explicit product review rather than being folded
into an internal refactor.
