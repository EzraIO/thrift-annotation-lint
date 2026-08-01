# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
the project intends to use [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.2] - 2026-08-01

### Changed

- Consolidated internal wire-type tokens and shared validation message prefixes;
  behavior, diagnostic text, and public APIs remain unchanged.

## [0.2.1] - 2026-08-01

### Changed

- Split validation, recursive-cycle analysis, type inspection, round planning,
  metadata extraction, and class-file parsing into focused package-private
  components without changing public APIs or observable diagnostics.
- Preserved deterministic rule execution, collection order, round-local cache
  lifetimes, generic traversal, exact-model budgeting, and iterative deep-graph
  handling.

### Added

- Added architecture ownership checks and focused JVM method-descriptor tests;
  the complete suite now contains 216 passing tests.

## [0.2.0] - 2026-07-30

### Changed

- Established the ThriftAnnotationLint Java package, processor entry point,
  processor options, and
  `io.github.ezraio:thrift-annotation-lint` coordinates.
- Replaced compiler-internal APIs with a standard JSR 269 processor design.
- Standardized all diagnostics on English `AWxxxx` rule codes.
- Moved Facebook Swift dependencies to the test classpath.
- Stopped treating unannotated Lombok-generated accessors as Swift metadata.
- Expanded the verified official Swift matrix to `0.19.2`, `0.20.0`, `0.21.1`,
  `0.22.1`, and `0.23.1`.
- Defined the codec compatibility claim around Swift's default compiler codec;
  custom reflection-factory union semantics remain runtime-tested by consumers.
- Decomposed processor round state, exact-demand closure, type inspection,
  metadata extraction, class-file parsing, logical-field validation, union
  validation, and cycle validation into package-private services while
  preserving the public processor and diagnostic contracts.
- Hardened generated-round behavior for parameterized owners, source
  model-to-container migration, semantic cycle deduplication, and Paranamer's
  last-`LocalVariableTable` selection without changing diagnostic text or
  ordering.

### Added

- Apache-2.0 licensing and public GitHub repository metadata.
- A runnable Maven demo that shows `AW2002` in both warning and strict modes.
- Strict and warning validation modes.
- Compile-time validation for Swift struct, union, enum, field, constructor, and
  builder metadata.
- JDK 8, 11, 17, and 21 CI coverage.
- Cross-round recursive-cycle detection with source-located diagnostics.
- Demand-driven validation of exact generic use-site shapes in reachable source
  and classpath models.
- Deferred validation and historical source-root closure rebuilding in every
  later generated-source round, including stale javac placeholders that do not
  expose an `ERROR` use-site mirror.
- Classpath parameter-name recovery from `LocalVariableTable`, matching the
  supported Swift/Paranamer runtime contract while intentionally rejecting
  `MethodParameters`-only inference.
- Exact source and classpath simulation of Paranamer annotation names and its
  no-LVT `argN` fallback across both Swift ID-inference passes.
- Stable source-parameter identity checks that require an explicit field ID or
  name when runtime name retention cannot be proven.
- A configurable exact-model closure budget with the always-error `AW9003`
  diagnostic for branching or non-converging metadata graphs.
- Codec-safe checks for final injection fields, abstract construction types,
  builder sentinels, primitive-`short` union IDs, discriminator inference
  collisions, reserved payload ID `0`, and deterministic per-variant union
  construction.
- Directional and recursively nested canonical container-shape checks for
  `List`, `Set`, `Map`, and `ByteBuffer` extraction and injection paths.
- Swift-compatible `Map` > `Set` > `Iterable` container-before-model
  classification for annotated roots, including generic substitution, resolved
  element validation, and exact-model demand traversal across generated rounds.
- Reflection-order ambiguity checks for duplicate extractors in Swift's final
  winning tier and union method injections that official metadata silently
  overwrites.
- Compilation-wide resolved-model budgeting, iterative large-cycle analysis,
  and structural executable type-variable cache identities; unresolved or
  later-reclassified container identities never reserve model budget slots.
- Bounded, fail-closed class-file parsing with a weighted LRU cache and explicit
  module-path bytecode-access diagnostics.
- Java record handling on compilers that support records.
- Wildcard round participation with a fast no-op path, allowing source enums to
  validate `@ThriftEnumValue` inherited only from classpath interfaces.
- Enum-before-container type precedence, including source and classpath enums
  that legally implement `Iterable`.
- Official Swift metadata contract tests and builder/union codec round trips.
- Architecture invariants and focused unit tests for processor options,
  exact-model budgeting, source-root migration, type-system probes, bounded
  class-file parsing, logical fields, unions, and deterministic cycles.
