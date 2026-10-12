#!/usr/bin/env bash
# JPrompter launcher for Linux and macOS.
# Builds the jar with Maven on the first run, then starts the app.
#
# Usage: ./jprompter.sh [--build] [script.txt]
#   --build   rebuild the jar before starting (after pulling new code, for example)

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIN_JAVA=21

die() { echo "jprompter: $*" >&2; exit 1; }

build=false
args=()
for arg in "$@"; do
    case "$arg" in
        --build) build=true ;;
        -h|--help) sed -n '2,6s/^# \{0,1\}//p' "$0"; exit 0 ;;
        *) args+=("$arg") ;;
    esac
done

# Prefer JAVA_HOME, fall back to java on the PATH.
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA="java"
else
    die "Java not found. Install Java $MIN_JAVA or newer (e.g. https://adoptium.net) or set JAVA_HOME."
fi

# "25.0.4" -> 25, "1.8.0_402" -> 8
version="$("$JAVA" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
major="${version%%.*}"
[[ "$major" == "1" ]] && major="$(echo "$version" | cut -d. -f2)"
[[ "$major" =~ ^[0-9]+$ ]] || die "could not read the Java version from: $("$JAVA" -version 2>&1 | head -1)"
(( major >= MIN_JAVA )) || die "Java $MIN_JAVA or newer is required, but found Java $version ($JAVA)."

find_jar() {
    local jars=("$DIR"/target/jprompter-*.jar)
    [[ -e "${jars[0]}" ]] && echo "${jars[0]}"
}

JAR="$(find_jar || true)"
if $build || [[ -z "$JAR" ]]; then
    command -v mvn >/dev/null 2>&1 || die "Maven (mvn) is needed to build JPrompter. Install it from https://maven.apache.org"
    echo "Building JPrompter..."
    mvn -q -B -f "$DIR/pom.xml" package || die "build failed."
    JAR="$(find_jar)" || die "build finished but no jar was found in $DIR/target."
fi

exec "$JAVA" -jar "$JAR" "${args[@]+"${args[@]}"}"
