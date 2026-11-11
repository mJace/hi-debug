#!/usr/bin/env bash
# 刪除整個練習 namespace
set -euo pipefail
oc delete project "${NS:-hi-debug-lab}"
