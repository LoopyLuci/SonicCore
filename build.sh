#!/usr/bin/env bash
# SonicCore build wrapper — pins the portable toolchain for every invocation.
set -euo pipefail
export JAVA_HOME="C:/Users/Server/soniccore-toolchain/jdk-21.0.12+8"
export ANDROID_HOME="C:/Users/Server/soniccore-toolchain/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$(dirname "$0")"
exec ./gradlew "$@"
