#!/usr/bin/env python3
"""Aggregates CI check statuses, unit test XMLs, lint results, and logs into qa-report.md and failure-report.md."""

import json
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def load_statuses(artifacts_dir: Path) -> list[tuple[str, str, str, str]]:
    rows = []
    for tsv in sorted(artifacts_dir.rglob("ci-status/*.tsv")):
        for line in tsv.read_text(errors="replace").splitlines():
            parts = line.split("\t")
            if len(parts) >= 4:
                rows.append((parts[0], parts[1], parts[2], parts[3]))
    return rows


def summarize_junit(artifacts_dir: Path) -> tuple[int, int, int]:
    tests = failures = errors = 0
    for xml_path in artifacts_dir.rglob("TEST-*.xml"):
        try:
            root = ET.parse(xml_path).getroot()
            tests += int(root.attrib.get("tests", 0))
            failures += int(root.attrib.get("failures", 0))
            errors += int(root.attrib.get("errors", 0))
        except Exception:
            continue
    return tests, failures, errors


def summarize_lint(artifacts_dir: Path) -> tuple[int, int]:
    errors = warnings = 0
    for xml_path in artifacts_dir.rglob("lint-results*.xml"):
        try:
            root = ET.parse(xml_path).getroot()
            for issue in root.findall("issue"):
                sev = issue.attrib.get("severity", "").lower()
                if sev in ("error", "fatal"):
                    errors += 1
                elif sev == "warning":
                    warnings += 1
        except Exception:
            continue
    return errors, warnings


def main() -> None:
    if len(sys.argv) < 3:
        print("Usage: qa_report.py <artifacts-dir> <out-dir>", file=sys.stderr)
        sys.exit(1)

    artifacts_dir = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)

    version = os.environ.get("VERSION_NAME", "unknown")
    strict_lint = os.environ.get("STRICT_LINT", "false")
    job_results = json.loads(os.environ.get("JOB_RESULTS", "{}"))

    rows = load_statuses(artifacts_dir)
    unit_tests, unit_failures, unit_errors = summarize_junit(artifacts_dir)
    lint_errors, lint_warnings = summarize_lint(artifacts_dir)

    qa_lines = [
        f"# Mihon QA Report (v{version})",
        "",
        "## Job Summary",
        "",
        "| Job | Result |",
        "| :--- | :--- |",
    ]
    for job_name, info in job_results.items():
        qa_lines.append(f"| `{job_name}` | **{info.get('result', 'unknown')}** |")

    qa_lines += [
        "",
        "## Release-readiness gates",
        "",
        "| Check | Status | Details |",
        "| :--- | :--- | :--- |",
    ]
    for check_id, status, rc, desc in rows:
        qa_lines.append(f"| `{check_id}` | **{status}** | {desc} |")

    qa_lines += [
        "| `physical-nfc-tags` | **NOT RUN** | Requires physical NFC tags and hardware reader |",
        "| `colorizer-server-e2e` | **NOT RUN** | Requires reachable self-hosted colorizer GPU server |",
        "",
        "Release rule: Automated gates (build, unit tests, manifest, APK signing, emulator checks) must pass; physical NFC tag writes and colorizer server tests require on-device verification.",
        "",
        "## Metrics",
        "",
        f"- **Unit tests:** {unit_tests} total, {unit_failures} failed, {unit_errors} errors",
        f"- **Android Lint:** {lint_errors} errors, {lint_warnings} warnings (`strict_lint={strict_lint}`)",
    ]

    (out_dir / "qa-report.md").write_text("\n".join(qa_lines) + "\n")

    failures = [r for r in rows if r[1] == "FAIL"]
    fail_lines = [f"# Failure Report (v{version})", ""]
    if not failures:
        fail_lines.append("No failed automated checks recorded.")
    else:
        for check_id, status, rc, desc in failures:
            fail_lines.append(f"## `{check_id}` (rc={rc})")
            fail_lines.append(desc)
            fail_lines.append("")
            log_matches = list(artifacts_dir.rglob(f"ci-logs/{check_id}.log"))
            if log_matches:
                tail = log_matches[0].read_text(errors="replace").splitlines()[-40:]
                fail_lines.append("```text")
                fail_lines.extend(tail)
                fail_lines.append("```")
                fail_lines.append("")

    (out_dir / "failure-report.md").write_text("\n".join(fail_lines) + "\n")


if __name__ == "__main__":
    main()
