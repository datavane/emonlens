"""emonlens CLI：把评测/Gate 接进 CI。

退出码：
  0 PASS / verification usable
  2 FAIL
  3 BLOCKED / UNKNOWN / 证据不足
  4 配置、网络或服务端错误
  5 超时
"""
from __future__ import annotations

import argparse
import json
import sys
from typing import List, Optional

from .evaluate import (
    EmonLensError,
    EmonLensTimeoutError,
    assert_test,
    evaluate,
    gate_check,
)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="emonlens", description="EmonLens evaluation CLI")
    parser.add_argument("--host", default=None, help="EmonLens base URL (or EMONLENS_HOST)")
    parser.add_argument("--api-token", default=None,
                        help="open API token (or EMONLENS_API_TOKEN)")
    sub = parser.add_subparsers(dest="command", required=True)

    eval_parser = sub.add_parser("eval", help="dataset evaluation")
    eval_sub = eval_parser.add_subparsers(dest="eval_command", required=True)

    run = eval_sub.add_parser("run", help="submit a dataset run and optionally wait")
    run.add_argument("--dataset", required=True, help="dataset id")
    group = run.add_mutually_exclusive_group(required=True)
    group.add_argument("--suite", help="immutable eval suite id")
    group.add_argument("--evaluator", action="append", help="evaluator id (repeatable)")
    run.add_argument("--mode", default="TRACE", choices=["TRACE", "REPLAY"])
    run.add_argument("--revision", default=None, help="dataset revision id")
    run.add_argument("--candidate-label", default=None)
    run.add_argument("--wait", action="store_true", default=False,
                     help="wait until run reaches a terminal state")
    run.add_argument("--timeout", type=float, default=900.0)
    run.add_argument("--poll-interval", type=float, default=3.0)
    run.add_argument("--json", action="store_true", help="machine-readable output")
    run.add_argument("--assert", action="store_true", dest="assert_result",
                     help="exit non-zero on FAIL/BLOCKED/UNKNOWN")

    gate_parser = sub.add_parser("gate", help="quality gate")
    gate_sub = gate_parser.add_subparsers(dest="gate_command", required=True)
    check = gate_sub.add_parser("check", help="verify a historical gate")
    check.add_argument("--gate-id", required=True)
    check.add_argument("--json", action="store_true")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command == "eval":
            return _eval_run(args)
        if args.command == "gate":
            return _gate_check(args)
        return 4
    except EmonLensTimeoutError as exc:
        print("timeout: %s" % exc, file=sys.stderr)
        return 5
    except EmonLensError as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 4
    except AssertionError as exc:
        print(str(exc), file=sys.stderr)
        return 3


def _eval_run(args) -> int:
    result = evaluate(
        dataset_id=args.dataset,
        suite_id=args.suite,
        evaluator_ids=args.evaluator,
        mode=args.mode,
        revision_id=args.revision,
        candidate_label=args.candidate_label,
        wait=args.wait,
        timeout=args.timeout,
        poll_interval=args.poll_interval,
        host=args.host,
        api_token=args.api_token,
    )
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        run = result.get("run") or {}
        gate = result.get("gate") or {}
        print("runId=%s status=%s pass=%s fail=%s unknown=%s error=%s coverage=%s gate=%s"
              % (result.get("runId") or run.get("id"), run.get("status"),
                 run.get("passCount"), run.get("failCount"), run.get("unknownCount"),
                 run.get("errorCount"), run.get("coverage"), gate.get("decision")))
    if not args.wait:
        return 0
    if args.assert_result:
        assert_test(result)
        return 0
    return _exit_code(result)


def _gate_check(args) -> int:
    verification = gate_check(args.gate_id, host=args.host, api_token=args.api_token)
    if args.json:
        print(json.dumps(verification, ensure_ascii=False, indent=2))
    else:
        print("gateId=%s usable=%s decision=%s reasons=%s"
              % (args.gate_id, verification.get("usable"), verification.get("decision"),
                 ",".join(verification.get("reasons") or [])))
    usable = bool(verification.get("usable"))
    decision = str(verification.get("decision") or "").upper()
    if usable and decision == "PASS":
        return 0
    if decision == "FAIL":
        return 2
    return 3


def _exit_code(result) -> int:
    run = result.get("run") or {}
    gate = result.get("gate") or {}
    status = str(run.get("status") or "")
    decision = str(gate.get("decision") or "").upper() if gate else ""
    if status in ("FAILED", "PARTIAL_FAILED", "INTERRUPTED", "CANCELLED") or decision == "FAIL":
        return 2
    if int(run.get("errorCount") or 0) > 0 or int(run.get("incompleteCount") or 0) > 0:
        return 2
    if int(run.get("unknownCount") or 0) > 0:
        return 3
    if decision == "BLOCKED":
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
