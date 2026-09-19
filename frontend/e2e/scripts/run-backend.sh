#!/usr/bin/env bash
# Starts one EasyCRM backend for E2E from the boot jar (plan decision P10).
# Usage: run-backend.sh <port> [extra --spring.args...]
#
# The rate-limit policy list is restated IN FULL: Spring Boot replaces a whole list when a
# higher-precedence source sets any index, so overriding only policies[1].capacity would drop the
# other entries' names and paths and fail validation at startup. Capacities are raised because every
# E2E request arrives from 127.0.0.1 through the vite preview proxy (one bucket for the whole suite).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
libs="$here/../../../backend/build/libs"
jar="$(find "$libs" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1)"
if [ -z "$jar" ]; then
  echo "no boot jar in $libs; run ./gradlew bootJar in backend/ first" >&2
  exit 1
fi

# The jar is built by the Gradle toolchain pinned to Java 25 (backend/build.gradle.kts), but the
# `java` on this shell's PATH is whatever the caller's default happens to be (Gradle picks its
# toolchain independently of PATH, so a green `./gradlew bootJar` proves nothing about `java -jar`
# here). Prefer a real Java 25 over PATH's `java`, which fails fast and unhelpfully
# (UnsupportedClassVersionError) against a jar built for a newer class file version.
java_bin="java"
if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  resolved="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
  [ -n "$resolved" ] && java_bin="$resolved/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  java_bin="$JAVA_HOME/bin/java"
fi

port="$1"
shift
exec "$java_bin" -jar "$jar" \
  --server.port="$port" \
  --easycrm.jobs.quotation-expiry.cron=- \
  '--easycrm.rate-limit.policies[0].name=public-share' \
  '--easycrm.rate-limit.policies[0].path=/public/q/*' \
  '--easycrm.rate-limit.policies[0].capacity=60' \
  '--easycrm.rate-limit.policies[0].refill-period=1h' \
  '--easycrm.rate-limit.policies[1].name=session' \
  '--easycrm.rate-limit.policies[1].path=/api/v1/auth/{endpoint:refresh|logout|me}' \
  '--easycrm.rate-limit.policies[1].capacity=100000' \
  '--easycrm.rate-limit.policies[1].refill-period=1m' \
  '--easycrm.rate-limit.policies[2].name=auth' \
  '--easycrm.rate-limit.policies[2].path=/api/v1/auth/**' \
  '--easycrm.rate-limit.policies[2].capacity=100000' \
  '--easycrm.rate-limit.policies[2].refill-period=1m' \
  "$@"
