#!/usr/bin/env bash
# Helper functions for recording CI check results without aborting subsequent steps.

CI_LOG_DIR="${CI_LOG_DIR:-ci-logs}"
CI_STATUS_DIR="${CI_STATUS_DIR:-ci-status}"

mkdir -p "$CI_LOG_DIR" "$CI_STATUS_DIR"

ci_record() {
  local id="$1"
  local status="$2"
  local desc="$3"
  local rc="${4:-0}"
  mkdir -p "$CI_STATUS_DIR"
  printf '%s\t%s\t%s\t%s\n' "$id" "$status" "$rc" "$desc" > "$CI_STATUS_DIR/${id}.tsv"
  echo "[ci_record] $id: $status (rc=$rc) - $desc"
}

ci_step() {
  local id="$1"
  local desc="$2"
  shift 2

  mkdir -p "$CI_LOG_DIR" "$CI_STATUS_DIR"
  local logfile="$CI_LOG_DIR/${id}.log"

  echo "=== [ci_step] $id: $desc ==="
  set +e
  "$@" 2>&1 | tee "$logfile"
  local rc="${PIPESTATUS[0]}"
  set -e

  local status="PASS"
  if [ "$rc" -ne 0 ]; then
    status="FAIL"
  elif [ -n "${CI_FAIL_IF_LOG_MATCHES:-}" ] && grep -Eq "$CI_FAIL_IF_LOG_MATCHES" "$logfile"; then
    status="FAIL"
    rc=1
  fi

  ci_record "$id" "$status" "$desc" "$rc"
  return 0
}
