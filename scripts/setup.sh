#!/usr/bin/env bash
# 建立（或重置）hi-debug-lab 練習環境。
# 會先刪除再重建所有 lab 資源：你用 oc set env / oc patch 做的修正都會被還原，
# ephemeral container 也會跟著舊 Pod 一起消失。
set -euo pipefail
NS=${NS:-hi-debug-lab}
ROOT=$(cd "$(dirname "$0")/.." && pwd)

oc get project "$NS" >/dev/null 2>&1 || oc new-project "$NS" >/dev/null
oc project "$NS" >/dev/null
oc delete -f "$ROOT/manifests/labs.yaml" --ignore-not-found --wait=true >/dev/null
oc delete pod client --ignore-not-found >/dev/null
oc create configmap lab-app-src --from-file="$ROOT/app/App.java" --dry-run=client -o yaml | oc apply -f - >/dev/null
oc apply -f "$ROOT/manifests/labs.yaml" >/dev/null
echo "已部署到 namespace ${NS}。用 'oc get pods -n ${NS} -w' 觀察。"
