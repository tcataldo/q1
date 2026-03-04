#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

mvn package -DskipTests -q

exec java \
  --enable-preview \
  --enable-native-access=ALL-UNNAMED \
  ${JAVA_OPTS:-} \
  -jar q1-api/target/q1-api-*.jar
