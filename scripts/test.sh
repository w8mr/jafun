#!/usr/bin/env bash
set -euo pipefail

PATTERN="${1:-*}"
shift 2>/dev/null || true

./gradlew ":core:jvmTest" --tests "*$PATTERN*" --rerun-tasks "$@" 