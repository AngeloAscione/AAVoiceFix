#!/usr/bin/env python3
"""Test del protocollo AI Company.

Test deterministici, senza rete e senza modelli: verificano la macchina a
stati, il circuit breaker, la validazione degli handoff, il rispetto dello
scope e la generazione dei context pack.

Uso:
    python3 .company/scripts/test_protocol.py
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = REPO_ROOT / ".company" / "scripts"
PACKS_DIR = REPO_ROOT / ".company" / "board" / "packs"
sys.path.insert(0, str(SCRIPTS))
import guard  # noqa: E402

FAILURES: list[str] = []
PASSED = 0


def check(condition: bool, label: str, detail: str = "") -> None:
    global PASSED
    if condition:
        PASSED += 1
        print(f"  ok   {label}")
    else:
        FAILURES.append(f"{label}{': ' + detail if detail else ''}")
        print(f"  FAIL {label}" + (f" — {detail}" if detail else ""))


def run_board(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(SCRIPTS / "board.py"), *args],
        cwd=REPO_ROOT, capture_output=True, text=True, check=False,
    )


def run_guard(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(SCRIPTS / "guard.py"), *args],
        cwd=REPO_ROOT, capture_output=True, text=True, check=False,
    )


def run_pack(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(SCRIPTS / "pack.py"), *args],
        cwd=REPO_ROOT, capture_output=True, text=True, check=False,
    )


def make_ticket(ticket_id: str, **overrides) -> dict:
    ticket = {
        "id": ticket_id,
        "title": "Verificare il login scaduto",
        "type": "feature",
        "epic": "EPIC-01",
        "status": "BACKLOG",
        "priority": "P1",
        "spec": ".company/specs/PRD-x.md",
        "acceptance_criteria": [
            {"id": "AC-1", "text": "La sessione scaduta restituisce 401", "verified": False,
             "how": "tests/auth/session.test.ts::expiredSession"},
        ],
        "assignee": None,
        "branch": None,
        "attempts": {"qa_rejections": 0, "lead_cycles": 0, "lead_rejections": 0},
        "touches": ["src/auth/", "tests/auth/"],
        "history": [],
    }
    ticket.update(overrides)
    return ticket


def write_ticket(ticket: dict) -> Path:
    path = REPO_ROOT / ".company" / "board" / "tickets" / f"{ticket['id']}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(ticket, indent=2, ensure_ascii=False), encoding="utf-8")
    return path


def write_handoff(ticket_id: str, kind: str, data: dict) -> Path:
    suffix = kind.replace("->", "-")
    path = REPO_ROOT / ".company" / "board" / "handoffs" / f"{ticket_id}-{suffix}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    return path


def cleanup(paths: list[Path]) -> None:
    for path in paths:
        if path.exists():
            path.unlink()


def make_branch(branch: str) -> None:
    """Crea un branch di prova a partire da HEAD, se non esiste gia'.

    Se il repository non ha ancora commit, ne crea uno vuoto: senza un
    commit, `git branch` fallisce e i test che dipendono dal branch
    diventerebbero falsi negativi.
    """
    has_commit = subprocess.run(
        ["git", "rev-parse", "--verify", "HEAD"],
        cwd=REPO_ROOT, capture_output=True, text=True, check=False,
    ).returncode == 0
    if not has_commit:
        subprocess.run(
            [
                "git", "-c", "user.email=protocol@company.local",
                "-c", "user.name=AI Company Protocol",
                "commit", "--allow-empty", "-m", "test: empty base commit",
            ],
            cwd=REPO_ROOT, capture_output=True, text=True, check=False,
        )
    subprocess.run(
        ["git", "branch", branch], cwd=REPO_ROOT, capture_output=True, text=True, check=False
    )


def drop_branch(branch: str) -> None:
    subprocess.run(
        ["git", "branch", "-D", branch], cwd=REPO_ROOT, capture_output=True, text=True, check=False
    )


def test_state_machine() -> None:
    print("\n[1] macchina a stati")
    ticket = make_ticket("TKT-900")
    path = write_ticket(ticket)
    try:
        # BACKLOG -> TODO con l'attore giusto
        r = run_board("move", "TKT-900", "TODO", "--actor", "company-pm")
        check(r.returncode == 0, "BACKLOG -> TODO (company-pm)", r.stderr.strip())

        # BACKLOG -> TODO rifiutato per l'attore sbagliato
        r = run_board("move", "TKT-900", "BACKLOG", "--actor", "company-qa")
        check(r.returncode != 0, "transizione con attore non ammesso rifiutata")

        # transizione non ammessa
        r = run_board("move", "TKT-900", "DONE", "--actor", "company-lead")
        check(r.returncode != 0, "transizione non ammessa rifiutata")

        # TODO -> IN_PROGRESS senza branch
        r = run_board("move", "TKT-900", "IN_PROGRESS", "--actor", "company-lead")
        check(r.returncode != 0, "IN_PROGRESS senza branch rifiutata")

        current = json.loads(path.read_text(encoding="utf-8"))
        current["branch"] = "feature/TKT-900-session-expiry"
        current["status"] = "TODO"
        path.write_text(json.dumps(current, indent=2), encoding="utf-8")

        r = run_board("move", "TKT-900", "IN_PROGRESS", "--actor", "company-lead")
        check(r.returncode == 0, "TODO -> IN_PROGRESS con branch", r.stderr.strip())
    finally:
        cleanup([path])


def test_circuit_breaker() -> None:
    print("\n[2] circuit breaker")
    ticket = make_ticket("TKT-901", status="IN_QA", branch="feature/TKT-901-x")
    path = write_ticket(ticket)
    try:
        # Con max_qa_retries=3 il Developer ha 2 round: il terzo rifiuto
        # non torna al Developer.
        ticket["attempts"]["qa_rejections"] = 1
        path.write_text(json.dumps(ticket, indent=2), encoding="utf-8")
        r = run_board("move", "TKT-901", "IN_PROGRESS", "--actor", "company-qa")
        check(r.returncode == 0, "IN_QA -> IN_PROGRESS con 1 rifiuto ammesso", r.stderr.strip())

        current = json.loads(path.read_text(encoding="utf-8"))
        check(
            current["attempts"]["qa_rejections"] == 2,
            "il contatore qa_rejections sale a 2",
            str(current["attempts"]),
        )

        # con 2 rifiuti il terzo rientro al Dev e' bloccato
        current["status"] = "IN_QA"
        path.write_text(json.dumps(current, indent=2), encoding="utf-8")
        r = run_board("move", "TKT-901", "IN_PROGRESS", "--actor", "company-qa")
        check(r.returncode != 0, "IN_QA -> IN_PROGRESS al terzo rifiuto rifiutata")
        check("CIRCUIT BREAKER" in r.stderr, "il messaggio cita il circuit breaker", r.stderr.strip())

        # l'unica mossa ammessa e' BLOCKED
        r = run_board("move", "TKT-901", "BLOCKED", "--actor", "company-qa")
        check(r.returncode == 0, "IN_QA -> BLOCKED ammesso al terzo rifiuto", r.stderr.strip())

        current = json.loads(path.read_text(encoding="utf-8"))
        check(
            current["attempts"]["qa_rejections"] == 2,
            "il passaggio a BLOCKED conserva qa_rejections come evidenza",
            str(current["attempts"]),
        )
        check(
            current["attempts"]["lead_cycles"] == 0,
            "il ciclo guidato non e' ancora stato consumato all'ingresso in BLOCKED",
            str(current["attempts"]),
        )
    finally:
        cleanup([path])


def test_lead_cycle_limit() -> None:
    print("\n[3] limite del ciclo guidato del Tech Lead")
    ticket = make_ticket(
        "TKT-902",
        status="BLOCKED",
        branch="feature/TKT-902-x",
        attempts={"qa_rejections": 0, "lead_cycles": 1, "lead_rejections": 0},
    )
    path = write_ticket(ticket)
    try:
        r = run_board("move", "TKT-902", "IN_PROGRESS", "--actor", "company-lead")
        check(r.returncode != 0, "secondo ciclo guidato rifiutato")
        check("CIRCUIT BREAKER" in r.stderr, "il messaggio cita il circuit breaker")

        r = run_board("move", "TKT-902", "BLOCKED_WAITING_HUMAN", "--actor", "company-lead")
        check(
            r.returncode != 0,
            "BLOCKED -> BLOCKED_WAITING_HUMAN senza escalation report rifiutata",
        )
    finally:
        cleanup([path])


def test_escalation_sections() -> None:
    print("\n[4] escalation report")
    ticket = make_ticket(
        "TKT-903", status="BLOCKED", branch="feature/TKT-903-x",
        attempts={"qa_rejections": 0, "lead_cycles": 1, "lead_rejections": 0},
    )
    path = write_ticket(ticket)
    esc_dir = REPO_ROOT / ".company" / "escalations"
    esc_path = esc_dir / "ESC-TKT-903-20260101T000000Z.md"
    esc_dir.mkdir(parents=True, exist_ok=True)
    try:
        esc_path.write_text(
            "# Escalation\n\n## 1. Problema\n\nx\n\n## 2. Cosa è già stato tentato\n\ny\n",
            encoding="utf-8",
        )
        r = run_guard("escalate", "TKT-903")
        check(r.returncode != 0, "escalation report incompleto rifiutato")

        esc_path.write_text(
            "# Escalation\n\n## 1. Problema\n\nx\n\n## 2. Cosa è già stato tentato\n\ny\n\n"
            "## 3. Opzioni\n\nA\n\n## 4. Domanda al CEO\n\nScelta?\n",
            encoding="utf-8",
        )
        r = run_guard("escalate", "TKT-903")
        check(r.returncode == 0, "escalation report completo accettato", r.stderr.strip())

        r = run_board("move", "TKT-903", "BLOCKED_WAITING_HUMAN", "--actor", "company-lead")
        check(r.returncode == 0, "BLOCKED -> BLOCKED_WAITING_HUMAN ammessa", r.stderr.strip())
    finally:
        cleanup([path, esc_path])


def test_handoff_validation() -> None:
    print("\n[5] validazione degli handoff")
    ticket = make_ticket("TKT-904", status="IN_PROGRESS", branch="feature/TKT-904-x")
    path = write_ticket(ticket)
    handoff_path = REPO_ROOT / ".company" / "board" / "handoffs" / "TKT-904-dev-qa.json"
    try:
        # handoff con file fuori scope
        write_handoff("TKT-904", "dev->qa", {
            "ticket": "TKT-904", "from": "company-dev", "to": "company-qa",
            "kind": "dev->qa", "created_at": "2026-01-01T00:00:00Z",
            "summary": "implementato", "branch": "feature/TKT-904-x",
            "commits": [], "changed_files": ["src/unrelated/thing.ts"],
        })
        r = run_guard("check", "TKT-904", "dev->qa")
        check(r.returncode != 0, "handoff con file fuori scope rifiutato")

        # handoff con branch non conforme
        write_handoff("TKT-904", "dev->qa", {
            "ticket": "TKT-904", "from": "company-dev", "to": "company-qa",
            "kind": "dev->qa", "created_at": "2026-01-01T00:00:00Z",
            "summary": "implementato", "branch": "main",
            "commits": [], "changed_files": ["src/auth/session.ts"],
        })
        r = run_guard("check", "TKT-904", "dev->qa")
        check(r.returncode != 0, "handoff su branch protetto rifiutato")

        # handoff senza campi obbligatori
        write_handoff("TKT-904", "dev->qa", {
            "ticket": "TKT-904", "from": "company-dev", "to": "company-qa",
            "kind": "dev->qa", "created_at": "2026-01-01T00:00:00Z",
        })
        r = run_guard("check", "TKT-904", "dev->qa")
        check(r.returncode != 0, "handoff incompleto rifiutato")

        # handoff valido (branch inesistente -> deve fallire sul branch, non sullo scope)
        r = run_guard("check", "TKT-904", "dev->qa")
        check(r.returncode != 0, "handoff senza branch esistente rifiutato")
    finally:
        cleanup([path, handoff_path])


def test_qa_report_coverage() -> None:
    print("\n[6] copertura degli AC nel qa report")
    ticket = make_ticket("TKT-905", status="IN_QA", branch="feature/TKT-905-x")
    path = write_ticket(ticket)
    report_path = REPO_ROOT / ".company" / "board" / "handoffs" / "TKT-905-qa-report.json"
    qa_lead_path = REPO_ROOT / ".company" / "board" / "handoffs" / "TKT-905-qa-lead.json"
    make_branch("feature/TKT-905-x")
    try:
        # report APPROVED che non copre AC-2 del ticket
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps({
            "ticket": "TKT-905", "verdict": "APPROVED",
            "criteria": [{"id": "AC-1", "text": "x", "result": "PASS", "evidence": "test"}],
            "tests_run": "1 passed", "regressions": [],
        }), encoding="utf-8")

        ticket["acceptance_criteria"].append(
            {"id": "AC-2", "text": "y", "verified": False, "how": "tests/x.test.ts::y"}
        )
        path.write_text(json.dumps(ticket, indent=2), encoding="utf-8")

        write_handoff("TKT-905", "qa->lead", {
            "ticket": "TKT-905", "from": "company-qa", "to": "company-lead",
            "kind": "qa->lead", "created_at": "2026-01-01T00:00:00Z",
            "summary": "ok", "verdict": "APPROVED",
            "report_path": ".company/board/handoffs/TKT-905-qa-report.json",
        })
        r = run_guard("check", "TKT-905", "qa->lead")
        check(r.returncode != 0, "qa report che non copre tutti gli AC rifiutato")

        # verdict FAIL dentro il report
        report_path.write_text(json.dumps({
            "ticket": "TKT-905", "verdict": "APPROVED",
            "criteria": [
                {"id": "AC-1", "text": "x", "result": "PASS", "evidence": "test"},
                {"id": "AC-2", "text": "y", "result": "FAIL", "evidence": "test"},
            ],
            "tests_run": "1 failed", "regressions": [],
        }), encoding="utf-8")
        r = run_guard("check", "TKT-905", "qa->lead")
        check(r.returncode != 0, "qa report con AC FAIL rifiutato")

        # tutto PASS
        report_path.write_text(json.dumps({
            "ticket": "TKT-905", "verdict": "APPROVED",
            "criteria": [
                {"id": "AC-1", "text": "x", "result": "PASS", "evidence": "test"},
                {"id": "AC-2", "text": "y", "result": "PASS", "evidence": "test"},
            ],
            "tests_run": "2 passed", "regressions": [],
        }), encoding="utf-8")
        r = run_guard("check", "TKT-905", "qa->lead")
        check(r.returncode == 0, "qa report completo accettato", r.stderr.strip())
    finally:
        cleanup([path, report_path, qa_lead_path])
        drop_branch("feature/TKT-905-x")


def test_pack_generation() -> None:
    print("\n[7] context pack")
    ticket = make_ticket("TKT-906", status="IN_PROGRESS", branch="feature/TKT-906-x")
    ticket["design"] = "Usare un middleware di sessione."
    ticket["attempts"] = {"qa_rejections": 2, "lead_cycles": 0, "lead_rejections": 0}
    path = write_ticket(ticket)
    pack_path = REPO_ROOT / ".company" / "board" / "packs" / "TKT-906-company-dev.md"
    try:
        r = run_pack("TKT-906", "company-dev")
        check(r.returncode == 0, "pack generato", r.stderr.strip())
        content = pack_path.read_text(encoding="utf-8")
        check("AC-1" in content, "il pack contiene gli AC")
        check("Usare un middleware" in content, "il pack contiene il design")
        check("src/auth/" in content, "il pack contiene lo scope")
        check("2" in content.split("Rifiuti consecutivi")[1][:200], "il pack contiene i tentativi precedenti")
    finally:
        cleanup([path, pack_path])


def test_pack_artifact_paths() -> None:
    print("\n[11] path assoluti degli artefatti nel pack")
    ticket = make_ticket("TKT-907", status="IN_PROGRESS", branch="feature/TKT-907-x")
    path = write_ticket(ticket)
    try:
        for role in ["company-dev", "company-qa", "company-pm", "company-lead"]:
            pack_path = PACKS_DIR / f"TKT-907-{role}.md"
            r = run_pack("TKT-907", role)
            check(r.returncode == 0, f"pack generato per {role}", r.stderr.strip())
            content = pack_path.read_text(encoding="utf-8")
            check(
                str(REPO_ROOT) in content,
                f"il pack di {role} contiene il path assoluto del protocol root",
            )
            check(
                "board/handoffs" in content,
                f"il pack di {role} dice dove scrivere l'handoff",
            )
            pack_path.unlink()
    finally:
        cleanup([path])


def test_sync_agents() -> None:
    print("\n[8] sincronizzazione delle definizioni agenti")
    r = subprocess.run(
        [sys.executable, str(SCRIPTS / "sync_agents.py"), "--check"],
        cwd=REPO_ROOT, capture_output=True, text=True, check=False,
    )
    check(r.returncode == 0, "le definizioni agenti sono up to date", r.stdout.strip())

    for name in ["company-pm", "company-lead", "company-dev", "company-qa", "company-release"]:
        path = REPO_ROOT / ".omp" / "agents" / f"{name}.md"
        content = path.read_text(encoding="utf-8") if path.exists() else ""
        check(
            content.startswith("---\nname: ") and "description:" in content.split("---")[1],
            f"{name}.md ha frontmatter valido",
        )


def test_handoff_kind_alias() -> None:
    print("\n[10] alias dei kind di handoff")
    guard_mod = guard

    for kind in guard_mod.HANDOFF_SCHEMA:
        alias = kind.replace("->", "-")
        check(
            guard_mod.resolve_kind(alias) == kind,
            f"alias '{alias}' risolve a '{kind}'",
        )
    try:
        guard_mod.resolve_kind("non-esiste")
        check(False, "kind sconosciuto rifiutato")
    except guard_mod.GuardError:
        check(True, "kind sconosciuto rifiutato")


def test_commit_containment() -> None:
    print("\n[11] i commit dichiarati devono stare nel branch")
    ticket = make_ticket("TKT-908", status="IN_PROGRESS", branch="feature/TKT-908-x")
    path = write_ticket(ticket)
    handoff_path = REPO_ROOT / ".company" / "board" / "handoffs" / "TKT-908-dev-qa.json"
    make_branch("feature/TKT-908-x")
    head = subprocess.run(
        ["git", "rev-parse", "feature/TKT-908-x"],
        cwd=REPO_ROOT, capture_output=True, text=True, check=False,
    ).stdout.strip()
    try:
        # commit inesistente
        write_handoff("TKT-908", "dev->qa", {
            "ticket": "TKT-908", "from": "company-dev", "to": "company-qa",
            "kind": "dev->qa", "created_at": "2026-01-01T00:00:00Z",
            "summary": "x", "branch": "feature/TKT-908-x",
            "commits": ["deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"],
            "changed_files": ["src/auth/session.py"],
        })
        r = run_guard("check", "TKT-908", "dev->qa")
        check(r.returncode != 0, "commit inesistente rifiutato")

        # commit valido ma su un branch diverso: non e' contenuto in TKT-908-x
        side = subprocess.run(
            ["git", "commit-tree", head, "-m", "side commit", "-p", head],
            cwd=REPO_ROOT, capture_output=True, text=True, check=False,
        ).stdout.strip()
        write_handoff("TKT-908", "dev->qa", {
            "ticket": "TKT-908", "from": "company-dev", "to": "company-qa",
            "kind": "dev->qa", "created_at": "2026-01-01T00:00:00Z",
            "summary": "x", "branch": "feature/TKT-908-x",
            "commits": [side],
            "changed_files": ["src/auth/session.py"],
        })
        r = run_guard("check", "TKT-908", "dev->qa")
        check(r.returncode != 0, "commit fuori dal branch rifiutato", r.stderr.strip())

        # commit abbreviato contenuto nel branch: accettato
        write_handoff("TKT-908", "dev->qa", {
            "ticket": "TKT-908", "from": "company-dev", "to": "company-qa",
            "kind": "dev->qa", "created_at": "2026-01-01T00:00:00Z",
            "summary": "x", "branch": "feature/TKT-908-x",
            "commits": [head[:8]],
            "changed_files": ["src/auth/session.py"],
        })
        r = run_guard("check", "TKT-908", "dev->qa")
        check(r.returncode == 0, "commit abbreviato contenuto nel branch accettato", r.stderr.strip())
    finally:
        cleanup([path, handoff_path])
        drop_branch("feature/TKT-908-x")


def test_gate_scope_and_tests() -> None:
    print("\n[12] regole di uscita del ticket")
    ticket = make_ticket("TKT-909", status="IN_PROGRESS", branch="feature/TKT-909-x")
    ticket["acceptance_criteria"] = [
        {"id": "AC-1", "text": "x", "verified": False, "how": "ispezione manuale del codice"},
    ]
    path = write_ticket(ticket)
    make_branch("feature/TKT-909-x")
    try:
        result = guard.gate("TKT-909")
        check(not result["ok"], "gate fallisce senza test automatici")
        check(
            any("test automatico" in p for p in result["problems"]),
            "il gate segnala l'assenza di test automatici",
            str(result["problems"]),
        )

        ticket["acceptance_criteria"] = [
            {"id": "AC-1", "text": "x", "verified": False, "how": "test: tests/x.test.ts::y"},
        ]
        ticket["touches"] = []
        path.write_text(json.dumps(ticket, indent=2), encoding="utf-8")
        result = guard.gate("TKT-909")
        check(
            not any("fuori scope" in p for p in result["problems"]),
            "senza touches il gate non segnala scope creep",
            str(result["problems"]),
        )
    finally:
        cleanup([path])
        drop_branch("feature/TKT-909-x")


def test_breaker_end_to_end() -> None:
    print("\n[13] circuit breaker end-to-end")
    ticket = make_ticket("TKT-910", status="IN_QA", branch="feature/TKT-910-breaker")
    ticket["assignee"] = "company-qa"
    ticket["touches"] = ["src/x.py"]
    path = write_ticket(ticket)
    qa_dev = REPO_ROOT / ".company" / "board" / "handoffs" / "TKT-910-qa-dev.json"
    dev_qa = REPO_ROOT / ".company" / "board" / "handoffs" / "TKT-910-dev-qa.json"
    lead_dev = REPO_ROOT / ".company" / "board" / "handoffs" / "TKT-910-lead-dev.json"
    esc = REPO_ROOT / ".company" / "escalations" / "ESC-TKT-910-20260101T000000Z.md"

    # Branch reale con un commit: il guard verifica che i commit dichiarati
    # esistano e stiano nel branch.
    make_branch("feature/TKT-910-breaker")
    head = subprocess.run(
        ["git", "rev-parse", "feature/TKT-910-breaker"],
        cwd=REPO_ROOT, capture_output=True, text=True, check=False,
    ).stdout.strip()

    def write_qa_dev(attempt: int) -> None:
        qa_dev.write_text(json.dumps({
            "ticket": "TKT-910", "from": "company-qa", "to": "company-dev",
            "kind": "qa->dev", "created_at": "2026-01-01T00:00:00Z",
            "verdict": "REJECTED", "summary": f"rifiuto {attempt}",
            "failed_tests": [{
                "id": "AC-1", "expected": "200", "actual": "500",
                "command": "python3 -m unittest", "output": "AssertionError: expected 200, got 500",
                "test_bug": False,
            }],
            "logs": "AssertionError: expected 200, got 500",
            "next_action": "dev: correggere",
        }, indent=2), encoding="utf-8")

    def write_dev_qa() -> None:
        dev_qa.write_text(json.dumps({
            "ticket": "TKT-910", "from": "company-dev", "to": "company-qa",
            "kind": "dev->qa", "created_at": "2026-01-01T00:00:00Z",
            "summary": "correzione consegnata", "branch": "feature/TKT-910-breaker",
            "commits": [head], "changed_files": ["src/x.py"],
        }, indent=2), encoding="utf-8")

    try:
        for attempt in range(1, 4):
            write_qa_dev(attempt)
            r = run_board("move", "TKT-910", "IN_PROGRESS", "--actor", "company-qa")
            if attempt < 3:
                check(r.returncode == 0, f"rifiuto {attempt}: rientro al Dev ammesso", r.stderr.strip())
                write_dev_qa()
                r2 = run_board("move", "TKT-910", "IN_QA", "--actor", "company-dev")
                check(r2.returncode == 0, f"rifiuto {attempt}: riconsegna in IN_QA", r2.stderr.strip())
            else:
                check(r.returncode != 0, "rifiuto 3: rientro al Dev BLOCCATO", r.stderr.strip())
                check("CIRCUIT BREAKER" in r.stderr, "rifiuto 3: messaggio del breaker", r.stderr.strip())

        state = json.loads(path.read_text(encoding="utf-8"))
        check(
            state["attempts"]["qa_rejections"] == 2,
            "i rientri al Dev sono 2: il terzo rifiuto non riapre il ciclo",
            str(state["attempts"]),
        )

        r = run_board("move", "TKT-910", "BLOCKED", "--actor", "company-qa")
        check(r.returncode == 0, "IN_QA -> BLOCKED ammesso dopo il terzo rifiuto", r.stderr.strip())
        state = json.loads(path.read_text(encoding="utf-8"))
        check(state["status"] == "BLOCKED", "il ticket e' in BLOCKED", state["status"])

        # Il Tech Lead ha un solo ciclo guidato.
        lead_dev.write_text(json.dumps({
            "ticket": "TKT-910", "from": "company-lead", "to": "company-dev",
            "kind": "lead->dev", "created_at": "2026-01-01T00:00:00Z",
            "summary": "direttive riscritte",
            "branch": "feature/TKT-910-breaker", "commits": [],
        }, indent=2), encoding="utf-8")
        r = run_board("move", "TKT-910", "IN_PROGRESS", "--actor", "company-lead")
        check(r.returncode == 0, "primo ciclo guidato del Tech Lead ammesso", r.stderr.strip())
        state = json.loads(path.read_text(encoding="utf-8"))
        check(
            state["attempts"]["lead_cycles"] == 1,
            "lead_cycles incrementato a 1",
            str(state["attempts"]),
        )

        # Secondo blocco: il ciclo guidato e' esaurito.
        state["status"] = "BLOCKED"
        path.write_text(json.dumps(state, indent=2), encoding="utf-8")
        r = run_board("move", "TKT-910", "IN_PROGRESS", "--actor", "company-lead")
        check(r.returncode != 0, "secondo ciclo guidato BLOCCATO", r.stderr.strip())
        check("CIRCUIT BREAKER" in r.stderr, "messaggio del breaker sul ciclo guidato", r.stderr.strip())

        r = run_board("move", "TKT-910", "BLOCKED_WAITING_HUMAN", "--actor", "company-lead")
        check(r.returncode != 0, "escalation rifiutata senza escalation report", r.stderr.strip())

        esc.parent.mkdir(parents=True, exist_ok=True)
        esc.write_text(
            "# Escalation — TKT-910\n\n## 1. Problema\n\nx\n\n"
            "## 2. Cosa è già stato tentato\n\ny\n\n## 3. Opzioni\n\nA\n\n"
            "## 4. Domanda al CEO\n\nScegli A?\n",
            encoding="utf-8",
        )
        r = run_board("move", "TKT-910", "BLOCKED_WAITING_HUMAN", "--actor", "company-lead")
        check(r.returncode == 0, "escalation ammessa con escalation report valido", r.stderr.strip())
        state = json.loads(path.read_text(encoding="utf-8"))
        check(state["status"] == "BLOCKED_WAITING_HUMAN", "stato finale corretto", state["status"])
    finally:
        cleanup([path, qa_dev, dev_qa, lead_dev, esc])
        drop_branch("feature/TKT-910-breaker")


def test_base_branch_configurable() -> None:
    print("\n[14] branch base configurabile")
    import guard as guard_mod

    manifest = json.loads(
        (REPO_ROOT / ".company" / "manifest.json").read_text(encoding="utf-8")
    )
    configured = manifest["policy"]["base_branch"]
    check(
        guard_mod.base_branch() == configured,
        "guard.base_branch() restituisce il branch base configurato",
        f"{guard_mod.base_branch()} != {configured}",
    )
    check(
        isinstance(configured, str) and configured,
        "il branch base e' una stringa non vuota",
        repr(configured),
    )

    # Il branch base non deve essere hardcoded in giro.
    source = (SCRIPTS / "guard.py").read_text(encoding="utf-8")
    check(
        'base: str = "main"' not in source,
        "nessun branch base hardcoded nelle firme di guard.py",
    )
    worktree_source = (SCRIPTS / "worktree.py").read_text(encoding="utf-8")
    check(
        'str(worktree), "main"' not in worktree_source,
        "worktree.py non usa 'main' hardcoded",
    )


def test_pack_project_commands() -> None:
    print("\n[15] i comandi del progetto sono nel pack")
    import json as _json

    manifest_path = REPO_ROOT / ".company" / "manifest.json"
    manifest = _json.loads(manifest_path.read_text(encoding="utf-8"))
    project = manifest.get("project") or {}
    check(bool(project.get("test_command")), "il manifest dichiara il comando di test")
    check(bool(project.get("build_command")), "il manifest dichiara il comando di build")
    check(bool(project.get("lint_command")), "il manifest dichiara il comando di lint")

    ticket = make_ticket("TKT-911", status="IN_PROGRESS", branch="feature/TKT-911-x")
    path = write_ticket(ticket)
    pack_path = PACKS_DIR / "TKT-911-company-dev.md"
    try:
        r = run_pack("TKT-911", "company-dev")
        check(r.returncode == 0, "pack generato", r.stderr.strip())
        content = pack_path.read_text(encoding="utf-8")
        check(
            project.get("test_command", "") in content,
            "il pack contiene il comando di test del progetto",
        )
        check(
            project.get("build_command", "") in content,
            "il pack contiene il comando di build del progetto",
        )
    finally:
        cleanup([path, pack_path])


def test_manifest_keys_match_init() -> None:
    print("\n[16] le chiavi del manifest coincidono con quelle di init.py")
    init_source = (SCRIPTS / "init.py").read_text(encoding="utf-8")
    manifest = json.loads(
        (REPO_ROOT / ".company" / "manifest.json").read_text(encoding="utf-8")
    )
    for key in ("build_command", "lint_command", "test_command", "source_root", "test_root"):
        check(
            f'"{key}"' in init_source,
            f"init.py produce la chiave '{key}'",
        )
        check(
            key in (manifest.get("project") or {}),
            f"il manifest dichiara '{key}'",
        )
    # Le chiavi corte non devono sopravvivere: il pack le cerca con *_command.
    for short in ('"build"', '"lint"', '"test"'):
        check(
            f'{short}:' not in init_source,
            f"init.py non usa la chiave breve {short}",
        )


def test_protocol_is_project_agnostic() -> None:
    """Il protocollo non deve contenere path di un progetto specifico.

    Un path concreto in un prompt o in un documento diventa un'aspettativa
    sbagliata per chi installa la squadra su un altro progetto.
    """
    print("\n[17] il protocollo e' agnostico rispetto al progetto")
    banned = [
        "src/auth/session.py",
        "tests/auth/test_session.py",
        "src/auth/password_reset",
        "password-reset-request",
        "npm test -- tests/auth",
    ]
    files = [
        *sorted((REPO_ROOT / ".company" / "prompts").glob("*.md")),
        *sorted((REPO_ROOT / ".company" / "protocol").glob("*.md")),
        *sorted((REPO_ROOT / ".company" / "templates").glob("*")),
        REPO_ROOT / ".company" / "AGENTS.md",
        REPO_ROOT / ".company" / "manifest.json",
    ]
    for path in files:
        if not path.is_file():
            continue
        content = path.read_text(encoding="utf-8")
        hits = [token for token in banned if token in content]
        check(
            not hits,
            f"{path.relative_to(REPO_ROOT)} non contiene path di progetto specifico",
            str(hits),
        )

    # Gli script non devono hardcodare il progetto d'esempio.
    for script in sorted((REPO_ROOT / ".company" / "scripts").glob("*.py")):
        if script.name == "test_protocol.py":
            continue
        content = script.read_text(encoding="utf-8")
        hits = [token for token in banned if token in content]
        check(
            not hits,
            f"{script.name} non contiene path di progetto specifico",
            str(hits),
        )


def test_manifest_consistency() -> None:
    print("\n[9] coerenza del manifest")
    manifest = json.loads(
        (REPO_ROOT / ".company" / "manifest.json").read_text(encoding="utf-8")
    )
    pol = manifest["policy"]
    check(pol["max_qa_retries"] == 3, "max_qa_retries = 3")
    check(pol["max_lead_cycles"] == 1, "max_lead_cycles = 1")
    names = {a["name"] for a in manifest["agents"]}
    check(len(names) == len(manifest["agents"]), "nessun nome duplicato nel manifest")
    check("main" not in names and "sub" not in names, "nessun nome riservato nel manifest")
    for agent in manifest["agents"]:
        prompt = REPO_ROOT / ".company" / agent["prompt"]
        check(prompt.exists(), f"prompt presente: {agent['prompt']}")
        check(bool(agent.get("description")), f"descrizione presente: {agent['name']}")


def main() -> int:
    print("Test del protocollo AI Company")
    test_state_machine()
    test_circuit_breaker()
    test_lead_cycle_limit()
    test_escalation_sections()
    test_handoff_validation()
    test_qa_report_coverage()
    test_pack_generation()
    test_pack_artifact_paths()
    test_sync_agents()
    test_handoff_kind_alias()
    test_commit_containment()
    test_gate_scope_and_tests()
    test_breaker_end_to_end()
    test_base_branch_configurable()
    test_pack_project_commands()
    test_manifest_keys_match_init()
    test_protocol_is_project_agnostic()
    test_manifest_consistency()

    print(f"\n{'=' * 60}")
    print(f"passati: {PASSED}   falliti: {len(FAILURES)}")
    for failure in FAILURES:
        print(f"  FAIL {failure}")
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main())
