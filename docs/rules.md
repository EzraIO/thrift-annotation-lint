# Rule reference

ThriftAnnotationLint diagnostics use the format `[AWxxxx] message`. Codes are stable within
the `0.1.x` preview line; message wording may be clarified without changing the
meaning of a code.

## Model and field rules

| Code | Meaning |
| --- | --- |
| `AW1001` | A Thrift model has an invalid declaration, annotation combination, or visibility. |
| `AW2001` | A logical field has no Thrift field ID. |
| `AW2002` | Different logical fields reuse one Thrift field ID. |
| `AW2003` | Members of one logical field declare conflicting IDs. |
| `AW2004` | Members of one logical field declare conflicting names. |
| `AW2005` | Members of one logical field declare conflicting requiredness. |
| `AW2006` | A legacy field ID configuration is invalid. |
| `AW2007` | Members of one logical field declare conflicting IDL annotations. |

## Access, construction, and type rules

| Code | Meaning |
| --- | --- |
| `AW3001` | A field has no valid read or write/injection path. |
| `AW3002` | A model declares multiple Thrift constructors. |
| `AW3003` | A getter, setter, or constructor signature is invalid or relies on ambiguous reflection-order selection. |
| `AW3004` | An annotated member has invalid modifiers, including an unsafe final injection field. |
| `AW3005` | A builder definition does not match its model. |
| `AW4001` | A Java type cannot be represented by the supported Swift model. |
| `AW4002` | A logical field has incompatible Java types or an unsafe canonical codec read/write shape. |
| `AW4003` | A recursive field is not optional or a direct model cycle lacks a recursive edge. |

## Union, enum, and processor rules

| Code | Meaning |
| --- | --- |
| `AW5001` | A union ID member is missing, duplicated, malformed, or not primitive `short`. |
| `AW5002` | A union constructor is invalid or does not deterministically cover every variant. |
| `AW5003` | A union field has invalid requiredness. |
| `AW5004` | A union payload uses unsafe field ID `0`, which is the default codec's no-field discriminator. |
| `AW6001` | A Thrift enum value method is missing where Drift requires it, is duplicated, or has an invalid signature. |
| `AW6002` | A Drift enum declares multiple `@ThriftEnumUnknownValue` fallback constants. |
| `AW9001` | A processor option is invalid. |
| `AW9002` | An unexpected processor failure prevented reliable validation. |
| `AW9003` | Reachable exact-model validation exceeded its configured safety budget. |

## Runtime-only checks

Static annotation processing cannot safely evaluate behavior that depends on
executing application code. ThriftAnnotationLint therefore does not attempt to validate:

- duplicate or null values returned by an `@ThriftEnumValue` method;
- reflection behavior changed by agents or runtime bytecode transformation;
- dynamic codec or catalog configuration;
- compatibility with a model from a previous release.

In particular, the processor cannot know whether a current field ID belonged to a removed
field, whether a `required` field was optional in a deployed version, or whether one ID's
wire type changed. These are schema-history questions and require an explicit baseline.

The verified codec contract uses Swift's default `CompilerThriftCodecFactory`.
In particular, `ReflectionThriftCodecFactory` invokes union builder factories
differently for known, empty, and unknown payloads. Treat that factory as a
runtime-only configuration and keep direct round-trip tests for every union
builder shape that uses it.

Applications should retain a runtime metadata/codec initialization test for
these cases.

## Reachable types and generic use sites

Validation starts at annotated source models and follows only the model types
they actually reference. For every reachable source or classpath model,
ThriftAnnotationLint preserves the exact instantiated Java type and substitutes concrete
generic use-site arguments into annotated members. This allows a use such as
`Box<CustomType>` to be checked according to how `Box<T>` uses `T`, without
reporting unrelated invalid models elsewhere on the classpath. Raw models and
unbound declaration type variables are deferred when there is no concrete
binding to prove invalid.

## Processing rounds and classpath parameter names

If a referenced type, hierarchy member, builder, or union ID is not resolved in
the current annotation-processing round, findings for that model are held back.
ThriftAnnotationLint rebuilds every historical source root's reachable-type closure in each
later generated-source round so generated symbols are validated in their
completed form, including javac placeholders whose use-site kind never changed
to `ERROR`.

Classpath constructor and multi-parameter injection names are trusted only when
present in `LocalVariableTable`, matching the Paranamer bytecode lookup used by
the supported official Swift releases. `MethodParameters` alone is not enough.
When LVT data is unavailable, ThriftAnnotationLint uses the same deterministic `argN`
fallback as GeneralParanamer and includes those names in both ID-inference
passes. Source injection parameters are checked with both source/LVT and
no-LVT names because JSR 269 cannot guarantee which debug attributes the
eventual class file will retain. Source and no-LVT classpath parameters must
still provide an explicit `@ThriftField` ID/name or a stable annotation name.
Annotation-provided names follow ThriftFieldParanamer's all-parameters behavior
and annotation order, including JSR-330 `@Named`.

Class bytes for dependency methods must be visible through the annotation
processor `CLASS_PATH`. Module-path-only model dependencies fail closed with
`AW3003` when complete annotation names do not make bytecode lookup unnecessary;
ThriftAnnotationLint does not silently treat inaccessible bytes as a missing LVT.

`-Athrift.annotation.lint.maxExactModels=<positive integer>` defaults to `512` and limits only
additional exact generic instances reached from source roots. Independent
source roots are exempt. Only fully resolved declarations that remain models
after Swift's container classification consume the budget. `AW9003` is always
an error because validation did not finish; increase the limit only after
confirming that the graph is finite.

## Deliberately stricter rules

`AW2002`, `AW3001`, reflection-order ambiguity checks in `AW3003`, final-field
checks in `AW3004`, abstract/non-static construction checks, canonical container
direction checks, deterministic union construction, and the primitive-`short`
union ID rule intentionally reject some metadata shapes that supported official
Swift releases can build. They prevent ambiguous schemas, one-way fields, or
failures deferred until default codec use. Adopt them in warning mode before
enabling strict mode on an existing codebase.

Swift classifies Java enums before container interfaces. For other declared
types, it classifies subtypes in `Map`, then `Set`, then `Iterable` order before
struct/union annotations. ThriftAnnotationLint preserves both priorities even when a
generated supertype appears in a later round. It ignores struct-looking members
on an annotated container root, but validates resolved element/key/value types
and uses them as roots for reachable exact-model validation.

ThriftAnnotationLint also inspects source enums without direct Swift annotations because an
enum can inherit `@ThriftEnumValue` from a classpath interface. The processor
does not claim unrelated annotations and takes a fast no-op path for ordinary
non-enum source compilations.

Unannotated enums inherit the referencing model's dialect. Drift requires exactly
one valid `@ThriftEnumValue` method, while Swift permits zero or one. The same
plain enum may therefore be validated independently for both dialects. Explicit
cross-dialect struct, union, or enum references are rejected with `AW1001`.

Drift supports `Optional<T>`, `OptionalInt`, `OptionalLong`, and
`OptionalDouble`; generic elements are validated recursively and normalized to
their element wire type. Raw Optional and unsupported elements use `AW4001`.
Swift Optional fields remain unsupported.
