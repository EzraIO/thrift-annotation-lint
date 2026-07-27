#!/usr/bin/env sh

set -eu

script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
project_root=$(CDPATH= cd "$script_dir/../.." && pwd)
processor_version=$(mvn --quiet -f "$project_root/pom.xml" -DforceStdout help:evaluate -Dexpression=project.version)
manifest="$script_dir/cases.txt"
all_modes=false

if [ -z "$processor_version" ]; then
    printf '%s\n' 'Unable to determine the local ThriftAnnotationLint version.' >&2
    exit 1
fi

if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [ "$1" != "--all-modes" ]; }; then
    printf '%s\n' 'Usage: sh examples/maven/run-demo.sh [--all-modes]' >&2
    exit 2
fi

if [ "$#" -eq 1 ]; then
    all_modes=true
fi

if [ ! -f "$manifest" ]; then
    printf '%s\n' 'Unable to find the demo case manifest.' >&2
    exit 1
fi

fail_with_log() {
    message=$1
    log_file=$2

    printf '%s\n' "$message" >&2
    sed -n '1,$p' "$log_file" >&2
    rm -f "$log_file"
    exit 1
}

assert_only_code() {
    expected_code=$1
    log_file=$2

    if ! grep -F "[$expected_code]" "$log_file" >/dev/null; then
        fail_with_log "Expected $expected_code, but it was not reported." "$log_file"
    fi
    if grep -F '[AW' "$log_file" | grep -F -v "[$expected_code]" >/dev/null; then
        fail_with_log "Expected only $expected_code, but another ThriftAnnotationLint diagnostic was reported." "$log_file"
    fi
}

assert_no_codes() {
    log_file=$1

    if grep -F '[AW' "$log_file" >/dev/null; then
        fail_with_log 'Expected no ThriftAnnotationLint diagnostics.' "$log_file"
    fi
}

print_diagnostics() {
    log_file=$1

    grep -F '[AW' "$log_file" || true
}

compile_case() {
    case_id=$1
    mode=$2
    budget=$3
    log_file=$4

    mvn -f "$script_dir/pom.xml" --batch-mode --no-transfer-progress \
        -Dthrift.annotation.lint.version="$processor_version" \
        -Ddemo.case="$case_id" \
        -Dthrift.annotation.lint.mode="$mode" \
        -Dthrift.annotation.lint.maxExactModels="$budget" \
        clean compile >"$log_file" 2>&1
}

ensure_case_sources() {
    case_id=$1
    source_dir="$script_dir/cases/$case_id/src/main/java"

    if [ ! -d "$source_dir" ]; then
        printf '%s\n' "Demo case '$case_id' has no source directory." >&2
        exit 1
    fi
    if ! find "$source_dir" -type f -name '*.java' -print | grep -q .; then
        printf '%s\n' "Demo case '$case_id' has no Java source file." >&2
        exit 1
    fi
}

expect_success() {
    case_id=$1
    mode=$2
    budget=$3
    expected_code=$4

    ensure_case_sources "$case_id"
    log_file=$(mktemp "${TMPDIR:-/tmp}/thrift-annotation-lint-demo.XXXXXX")

    printf '%s\n' "Compiling $case_id in $mode mode (success expected)..."
    if ! compile_case "$case_id" "$mode" "$budget" "$log_file"; then
        fail_with_log "Expected $case_id to compile successfully in $mode mode." "$log_file"
    fi

    if [ -n "$expected_code" ]; then
        assert_only_code "$expected_code" "$log_file"
    else
        assert_no_codes "$log_file"
    fi
    print_diagnostics "$log_file"
    rm -f "$log_file"
}

expect_failure() {
    case_id=$1
    mode=$2
    budget=$3
    expected_code=$4

    ensure_case_sources "$case_id"
    log_file=$(mktemp "${TMPDIR:-/tmp}/thrift-annotation-lint-demo.XXXXXX")

    printf '%s\n' "Compiling $case_id in $mode mode ($expected_code failure expected)..."
    if compile_case "$case_id" "$mode" "$budget" "$log_file"; then
        fail_with_log "Expected $case_id to fail with $expected_code in $mode mode." "$log_file"
    fi

    assert_only_code "$expected_code" "$log_file"
    print_diagnostics "$log_file"
    rm -f "$log_file"
}

printf '%s\n' 'Building and installing the local ThriftAnnotationLint checkout...'
mvn -f "$project_root/pom.xml" --batch-mode --no-transfer-progress -Dmaven.test.skip=true clean install

while IFS='|' read -r case_id kind code budget; do
    case "$case_id" in
        ''|\#*)
            continue
            ;;
    esac

    case "$kind" in
        valid)
            expect_success "$case_id" strict "$budget" ''
            if [ "$all_modes" = true ]; then
                expect_success "$case_id" warning "$budget" ''
            fi
            ;;
        violation)
            expect_success "$case_id" warning "$budget" "$code"
            if [ "$all_modes" = true ] || [ "$code" = AW2002 ]; then
                expect_failure "$case_id" strict "$budget" "$code"
            fi
            ;;
        advisory)
            expect_success "$case_id" strict "$budget" "$code"
            if [ "$all_modes" = true ]; then
                expect_success "$case_id" warning "$budget" "$code"
            fi
            ;;
        always-error)
            if [ "$code" = AW9001 ]; then
                expect_failure "$case_id" verbose "$budget" "$code"
            else
                expect_failure "$case_id" warning "$budget" "$code"
                if [ "$all_modes" = true ]; then
                    expect_failure "$case_id" strict "$budget" "$code"
                fi
            fi
            ;;
        *)
            printf '%s\n' "Unknown demo case kind '$kind' for '$case_id'." >&2
            exit 1
            ;;
    esac
done <"$manifest"

if [ "$all_modes" = true ]; then
    printf '%s\n' 'All demo cases passed in every applicable processor mode.'
else
    printf '%s\n' 'Demo matrix passed. Run with --all-modes to verify every regular case in strict mode too.'
fi
