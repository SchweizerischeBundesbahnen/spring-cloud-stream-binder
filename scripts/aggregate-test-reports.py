#!/usr/bin/env python3
"""
Aggregate all failsafe test reports from target/failsafe-reports-*/TEST-*.xml
into a single consolidated report showing each test with its execution time,
status, and error logs (if any).

Output:
  - target/failsafe-reports/consolidated-report.txt  (human-readable)
  - target/failsafe-reports/consolidated-report.xml  (single merged JUnit XML)

Usage:
  python3 scripts/aggregate-test-reports.py [--base-dir <path>]
"""

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class TestCase:
    classname: str
    name: str
    time: float
    status: str  # PASSED, FAILED, ERROR, SKIPPED
    fork: str
    message: str = ""
    error_type: str = ""
    error_text: str = ""
    system_out: str = ""
    system_err: str = ""


@dataclass
class TestSuite:
    name: str
    time: float
    tests: int
    errors: int
    failures: int
    skipped: int
    fork: str
    test_cases: list = field(default_factory=list)


def parse_test_xml(xml_path: str, fork: str) -> TestSuite | None:
    try:
        tree = ET.parse(xml_path)
    except ET.ParseError as e:
        print(f"  WARNING: Failed to parse {xml_path}: {e}", file=sys.stderr)
        return None

    root = tree.getroot()
    suite = TestSuite(
        name=root.get("name", "unknown"),
        time=float(root.get("time", "0")),
        tests=int(root.get("tests", "0")),
        errors=int(root.get("errors", "0")),
        failures=int(root.get("failures", "0")),
        skipped=int(root.get("skipped", "0")),
        fork=fork,
    )

    for tc_elem in root.iter("testcase"):
        tc = TestCase(
            classname=tc_elem.get("classname", ""),
            name=tc_elem.get("name", ""),
            time=float(tc_elem.get("time", "0")),
            status="PASSED",
            fork=fork,
        )

        failure = tc_elem.find("failure")
        error = tc_elem.find("error")
        skipped_elem = tc_elem.find("skipped")

        if failure is not None:
            tc.status = "FAILED"
            tc.message = failure.get("message", "")
            tc.error_type = failure.get("type", "")
            tc.error_text = (failure.text or "").strip()
        elif error is not None:
            tc.status = "ERROR"
            tc.message = error.get("message", "")
            tc.error_type = error.get("type", "")
            tc.error_text = (error.text or "").strip()
        elif skipped_elem is not None:
            tc.status = "SKIPPED"
            tc.message = skipped_elem.get("message", "")

        sysout = tc_elem.find("system-out")
        if sysout is not None and sysout.text:
            tc.system_out = sysout.text.strip()

        syserr = tc_elem.find("system-err")
        if syserr is not None and syserr.text:
            tc.system_err = syserr.text.strip()

        suite.test_cases.append(tc)

    return suite


def find_fork_txt_output(reports_dir: str, classname: str) -> str:
    """Find the .txt output file for a test class in the fork's reports directory."""
    txt_path = os.path.join(reports_dir, f"{classname}.txt")
    if os.path.isfile(txt_path):
        try:
            with open(txt_path, "r", errors="replace") as f:
                return f.read()
        except OSError:
            pass
    return ""


def write_text_report(suites: list[TestSuite], output_path: str, base_dir: str):
    total_tests = 0
    total_passed = 0
    total_failed = 0
    total_errors = 0
    total_skipped = 0
    total_time = 0.0
    all_cases: list[TestCase] = []

    for s in suites:
        total_time += s.time
        for tc in s.test_cases:
            total_tests += 1
            all_cases.append(tc)
            if tc.status == "PASSED":
                total_passed += 1
            elif tc.status == "FAILED":
                total_failed += 1
            elif tc.status == "ERROR":
                total_errors += 1
            elif tc.status == "SKIPPED":
                total_skipped += 1

    with open(output_path, "w") as f:
        f.write("=" * 120 + "\n")
        f.write("CONSOLIDATED FAILSAFE TEST REPORT\n")
        f.write("=" * 120 + "\n\n")
        f.write(f"Total Tests:   {total_tests}\n")
        f.write(f"Passed:        {total_passed}\n")
        f.write(f"Failed:        {total_failed}\n")
        f.write(f"Errors:        {total_errors}\n")
        f.write(f"Skipped:       {total_skipped}\n")
        f.write(f"Total Time:    {total_time:.3f}s\n")
        f.write(f"Test Suites:   {len(suites)}\n\n")

        # Group by status for quick reference
        failed_cases = [tc for tc in all_cases if tc.status in ("FAILED", "ERROR")]
        if failed_cases:
            f.write("!" * 120 + "\n")
            f.write(f"FAILURES AND ERRORS ({len(failed_cases)})\n")
            f.write("!" * 120 + "\n\n")
            for tc in failed_cases:
                f.write(f"  [{tc.status}] {tc.classname}#{tc.name} (fork {tc.fork}, {tc.time:.3f}s)\n")
                if tc.error_type:
                    f.write(f"    Type: {tc.error_type}\n")
                if tc.message:
                    f.write(f"    Message: {tc.message[:500]}\n")
                if tc.error_text:
                    # Limit stack trace to first 50 lines
                    lines = tc.error_text.split("\n")[:50]
                    f.write("    Stack Trace:\n")
                    for line in lines:
                        f.write(f"      {line}\n")
                    if len(tc.error_text.split("\n")) > 50:
                        f.write(f"      ... ({len(tc.error_text.split(chr(10))) - 50} more lines)\n")
                if tc.system_err:
                    err_lines = tc.system_err.split("\n")[:20]
                    f.write("    System.err:\n")
                    for line in err_lines:
                        f.write(f"      {line}\n")
                f.write("\n")

        # Per-suite detail
        f.write("-" * 120 + "\n")
        f.write("DETAILED RESULTS BY TEST SUITE\n")
        f.write("-" * 120 + "\n\n")

        for s in sorted(suites, key=lambda x: x.name):
            status_icon = "PASS" if s.errors == 0 and s.failures == 0 else "FAIL"
            f.write(f"[{status_icon}] {s.name} (fork {s.fork}, {s.time:.3f}s, "
                    f"{s.tests} tests, {s.failures} failures, {s.errors} errors, {s.skipped} skipped)\n")
            for tc in s.test_cases:
                icon = {"PASSED": "+", "FAILED": "X", "ERROR": "!", "SKIPPED": "-"}.get(tc.status, "?")
                f.write(f"  [{icon}] {tc.name} ({tc.time:.3f}s)\n")
                if tc.status in ("FAILED", "ERROR") and tc.message:
                    f.write(f"      >>> {tc.message[:200]}\n")
            f.write("\n")

        # Log file references
        log_dir = os.path.join(base_dir, "target", "logs")
        if os.path.isdir(log_dir):
            f.write("-" * 120 + "\n")
            f.write("PER-FORK LOG FILES\n")
            f.write("-" * 120 + "\n")
            for log_file in sorted(glob.glob(os.path.join(log_dir, "it-fork-*.log"))):
                size = os.path.getsize(log_file)
                f.write(f"  {os.path.relpath(log_file, base_dir)} ({size:,} bytes)\n")
            f.write("\n")


def write_xml_report(suites: list[TestSuite], output_path: str):
    """Write a single merged JUnit XML report."""
    total_tests = 0
    total_errors = 0
    total_failures = 0
    total_skipped = 0
    total_time = 0.0

    for s in suites:
        total_tests += s.tests
        total_errors += s.errors
        total_failures += s.failures
        total_skipped += s.skipped
        total_time += s.time

    root = ET.Element("testsuites", {
        "tests": str(total_tests),
        "errors": str(total_errors),
        "failures": str(total_failures),
        "skipped": str(total_skipped),
        "time": f"{total_time:.3f}",
    })

    for s in sorted(suites, key=lambda x: x.name):
        suite_elem = ET.SubElement(root, "testsuite", {
            "name": s.name,
            "tests": str(s.tests),
            "errors": str(s.errors),
            "failures": str(s.failures),
            "skipped": str(s.skipped),
            "time": f"{s.time:.3f}",
            "fork": s.fork,
        })

        for tc in s.test_cases:
            tc_elem = ET.SubElement(suite_elem, "testcase", {
                "classname": tc.classname,
                "name": tc.name,
                "time": f"{tc.time:.3f}",
            })

            if tc.status == "FAILED":
                fail_elem = ET.SubElement(tc_elem, "failure", {
                    "message": tc.message,
                    "type": tc.error_type,
                })
                fail_elem.text = tc.error_text
            elif tc.status == "ERROR":
                err_elem = ET.SubElement(tc_elem, "error", {
                    "message": tc.message,
                    "type": tc.error_type,
                })
                err_elem.text = tc.error_text
            elif tc.status == "SKIPPED":
                skip_elem = ET.SubElement(tc_elem, "skipped")
                if tc.message:
                    skip_elem.set("message", tc.message)

            if tc.system_out:
                sysout_elem = ET.SubElement(tc_elem, "system-out")
                sysout_elem.text = tc.system_out
            if tc.system_err:
                syserr_elem = ET.SubElement(tc_elem, "system-err")
                syserr_elem.text = tc.system_err

    tree = ET.ElementTree(root)
    ET.indent(tree, space="  ")
    tree.write(output_path, encoding="unicode", xml_declaration=True)


def write_enhanced_summary(suites: list[TestSuite], output_path: str):
    """Rewrite failsafe-summary.xml with individual test entries including time and status."""
    total_tests = 0
    total_errors = 0
    total_failures = 0
    total_skipped = 0
    total_time = 0.0

    for s in suites:
        total_tests += s.tests
        total_errors += s.errors
        total_failures += s.failures
        total_skipped += s.skipped
        total_time += s.time

    root = ET.Element("failsafe-summary", {
        "result": "255" if (total_errors + total_failures) > 0 else "0",
        "timeout": "false",
    })

    ET.SubElement(root, "completed").text = str(total_tests)
    ET.SubElement(root, "errors").text = str(total_errors)
    ET.SubElement(root, "failures").text = str(total_failures)
    ET.SubElement(root, "skipped").text = str(total_skipped)
    ET.SubElement(root, "totalTime").text = f"{total_time:.3f}"

    tests_elem = ET.SubElement(root, "tests")
    for s in sorted(suites, key=lambda x: x.name):
        for tc in s.test_cases:
            tc_elem = ET.SubElement(tests_elem, "test", {
                "classname": tc.classname,
                "name": tc.name,
                "time": f"{tc.time:.3f}",
                "status": tc.status,
                "fork": tc.fork,
            })
            if tc.status in ("FAILED", "ERROR") and (tc.message or tc.error_text):
                if tc.error_type:
                    tc_elem.set("type", tc.error_type)
                if tc.message:
                    msg_elem = ET.SubElement(tc_elem, "message")
                    msg_elem.text = tc.message[:1000]
                if tc.error_text:
                    trace_elem = ET.SubElement(tc_elem, "stackTrace")
                    trace_elem.text = tc.error_text

    tree = ET.ElementTree(root)
    ET.indent(tree, space="  ")
    tree.write(output_path, encoding="unicode", xml_declaration=True)


def main():
    parser = argparse.ArgumentParser(description="Aggregate failsafe test reports")
    parser.add_argument("--base-dir", default=os.getcwd(),
                        help="Project base directory (default: cwd)")
    args = parser.parse_args()

    base_dir = os.path.abspath(args.base_dir)
    target_dir = os.path.join(base_dir, "target")

    if not os.path.isdir(target_dir):
        print(f"ERROR: target directory not found: {target_dir}", file=sys.stderr)
        sys.exit(1)

    # Find all failsafe report directories
    report_dirs = sorted(glob.glob(os.path.join(target_dir, "failsafe-reports-*")))
    if not report_dirs:
        print("No failsafe-reports-* directories found in target/", file=sys.stderr)
        sys.exit(1)

    print(f"Found {len(report_dirs)} failsafe report directories")

    suites: list[TestSuite] = []
    for report_dir in report_dirs:
        fork = os.path.basename(report_dir).replace("failsafe-reports-", "")
        xml_files = glob.glob(os.path.join(report_dir, "TEST-*.xml"))
        for xml_file in xml_files:
            suite = parse_test_xml(xml_file, fork)
            if suite:
                suites.append(suite)

    if not suites:
        print("No test suite data found in report XMLs", file=sys.stderr)
        sys.exit(1)

    total_cases = sum(len(s.test_cases) for s in suites)
    print(f"Parsed {len(suites)} test suites with {total_cases} individual test cases")

    # Ensure output directory exists
    output_dir = os.path.join(target_dir, "failsafe-reports")
    os.makedirs(output_dir, exist_ok=True)

    # Write reports
    txt_path = os.path.join(output_dir, "consolidated-report.txt")
    write_text_report(suites, txt_path, base_dir)
    print(f"Text report:  {os.path.relpath(txt_path, base_dir)}")

    xml_path = os.path.join(output_dir, "consolidated-report.xml")
    write_xml_report(suites, xml_path)
    print(f"XML report:   {os.path.relpath(xml_path, base_dir)}")

    # Rewrite failsafe-summary.xml with individual test entries
    summary_path = os.path.join(output_dir, "failsafe-summary.xml")
    write_enhanced_summary(suites, summary_path)
    print(f"Summary:      {os.path.relpath(summary_path, base_dir)}")

    # Print summary
    failed = sum(1 for s in suites for tc in s.test_cases if tc.status in ("FAILED", "ERROR"))
    if failed:
        print(f"\n*** {failed} test(s) FAILED or ERRORED — see {os.path.relpath(txt_path, base_dir)} for details ***")
        sys.exit(1)
    else:
        print(f"\nAll {total_cases} tests passed.")


if __name__ == "__main__":
    main()
