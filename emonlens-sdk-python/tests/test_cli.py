import json

from emonlens import cli


def _result(status="COMPLETED", decision="PASS", unknown=0, fail=0, error=0):
    return {
        "run": {
            "id": "run-1",
            "status": status,
            "passCount": 1,
            "failCount": fail,
            "unknownCount": unknown,
            "errorCount": error,
            "incompleteCount": 0,
            "coverage": 100.0,
        },
        "gate": {"decision": decision} if decision else None,
    }


def test_cli_eval_run_pass(monkeypatch):
    monkeypatch.setattr(cli, "evaluate", lambda **kw: _result())
    assert cli.main(["eval", "run", "--dataset", "ds", "--suite", "suite", "--wait"]) == 0


def test_cli_eval_run_fail_and_blocked(monkeypatch):
    monkeypatch.setattr(cli, "evaluate", lambda **kw: _result(decision="FAIL", fail=1))
    assert cli.main(["eval", "run", "--dataset", "ds", "--suite", "suite", "--wait"]) == 2
    monkeypatch.setattr(cli, "evaluate", lambda **kw: _result(decision="BLOCKED", unknown=1))
    assert cli.main(["eval", "run", "--dataset", "ds", "--suite", "suite", "--wait"]) == 3


def test_cli_eval_json_and_assert(monkeypatch, capsys):
    monkeypatch.setattr(cli, "evaluate", lambda **kw: _result())
    code = cli.main(["eval", "run", "--dataset", "ds", "--suite", "suite",
                     "--wait", "--json", "--assert"])
    assert code == 0
    payload = json.loads(capsys.readouterr().out)
    assert payload["run"]["status"] == "COMPLETED"


def test_cli_eval_run_without_gate_passes_when_counts_clean(monkeypatch):
    result = _result()
    result["gate"] = None
    monkeypatch.setattr(cli, "evaluate", lambda **kw: result)
    assert cli.main(["eval", "run", "--dataset", "ds", "--suite", "suite", "--wait"]) == 0


def test_cli_gate_check_exit_codes(monkeypatch):
    monkeypatch.setattr(cli, "gate_check", lambda *a, **kw: {
        "usable": True, "decision": "PASS", "reasons": []})
    assert cli.main(["gate", "check", "--gate-id", "g1"]) == 0

    monkeypatch.setattr(cli, "gate_check", lambda *a, **kw: {
        "usable": True, "decision": "FAIL", "reasons": []})
    assert cli.main(["gate", "check", "--gate-id", "g1"]) == 2

    monkeypatch.setattr(cli, "gate_check", lambda *a, **kw: {
        "usable": False, "decision": "PASS", "reasons": ["EXPIRED"]})
    assert cli.main(["gate", "check", "--gate-id", "g1"]) == 3


def test_cli_missing_arguments_exits_nonzero():
    try:
        cli.main(["eval", "run"])
    except SystemExit as exc:
        assert exc.code != 0
