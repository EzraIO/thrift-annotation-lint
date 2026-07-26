#!/usr/bin/env sh

set -eu

script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
project_root=$(CDPATH= cd "$script_dir/../.." && pwd)
processor_version=$(mvn --quiet -f "$project_root/pom.xml" -DforceStdout help:evaluate -Dexpression=project.version)
warning_log=$(mktemp "${TMPDIR:-/tmp}/thrift-annotation-lint-warning.XXXXXX")
strict_log=$(mktemp "${TMPDIR:-/tmp}/thrift-annotation-lint-strict.XXXXXX")

cleanup() {
    rm -f "$warning_log" "$strict_log"
}

trap cleanup EXIT HUP INT TERM

if [ -z "$processor_version" ]; then
    printf '%s\n' 'Unable to determine the local ThriftAnnotationLint version.' >&2
    exit 1
fi

printf '%s\n' 'Building and installing the local ThriftAnnotationLint snapshot...'
mvn -f "$project_root/pom.xml" --batch-mode --no-transfer-progress -Dmaven.test.skip=true clean install

printf '%s\n' 'Compiling the demo in warning mode (AW2002 is expected)...'
if ! mvn -f "$script_dir/pom.xml" --batch-mode --no-transfer-progress -Dthrift.annotation.lint.version="$processor_version" -Pwarning clean compile >"$warning_log" 2>&1; then
    sed -n '1,$p' "$warning_log"
    exit 1
fi
sed -n '1,$p' "$warning_log"
if ! grep -F '[AW2002]' "$warning_log" >/dev/null; then
    printf '%s\n' 'Warning mode completed without the expected AW2002 diagnostic.' >&2
    exit 1
fi

printf '%s\n' 'Compiling the demo in strict mode (failure with AW2002 is expected)...'
if mvn -f "$script_dir/pom.xml" --batch-mode --no-transfer-progress -Dthrift.annotation.lint.version="$processor_version" clean compile >"$strict_log" 2>&1; then
    sed -n '1,$p' "$strict_log"
    printf '%s\n' 'Expected strict mode to reject the duplicate Thrift field ID.' >&2
    exit 1
fi
sed -n '1,$p' "$strict_log"
if ! grep -F '[AW2002]' "$strict_log" >/dev/null; then
    printf '%s\n' 'Strict mode failed without the expected AW2002 diagnostic.' >&2
    exit 1
fi

printf '%s\n' 'Demo complete: warning mode reported the issue and strict mode rejected it.'
