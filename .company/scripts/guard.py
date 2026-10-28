#!/usr/bin/env python3
"""Validazione degli handoff e delle regole di uscita della AI Company.

Il guard e' il livello che rende il protocollo applicato e non dichiarato:
verifica che un handoff sia completo, che il branch sia lecito, che i file
toccati restino dentro lo scope del ticket, che il verdetto QA sia
coerente con gli Acceptance Criteria.

Uso:
    guard.py check <TICKET> <kind>          # valida un handoff
    guard.py handoff <TICKET> <from> <to>   # crea lo scheletro di un handoff
    guard.py gate <TICKET>                  # regole di uscita del ticket
    guard.py escalate <TICKET>              # valida un escalation report
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(
    os.environ.get("COMPANY_ROOT", Path(__file__).resolve().parents[2])
).resolve()
COMPANY_DIR = REPO_ROOT / ".company"
TICKETS_DIR = COMPANY_DIR / "board" / "tickets"
HANDOFFS_DIR = COMPANY_DIR / "board" / "handoffs"
ESCALATIONS_DIR = COMPANY_DIR / "escalations"
MANIFEST = COMPANY_DIR / "manifest.json"

# kind -> (da, a, campi obbligatori non vuoti)
HANDOFF_SCHEMA: dict[str, tuple[str, str, list[str]]] = {
    "pm->lead": ("company-pm", "company-lead", ["summary"]),
    "lead->dev": ("company-lead", "company-dev", ["summary"]),
    "lead->qa": ("company-lead", "company-qa", ["summary"]),
    "dev->qa": ("company-dev", "company-qa", ["summary", "branch", "commits", "changed_files"]),
    "qa->dev": ("company-qa", "company-dev", ["summary", "verdict", "failed_tests", "logs"]),
    "qa->lead": ("company-qa", "company-lead", ["summary", "verdict", "report_path"]),
    "pr->lead": ("company-dev", "company-lead", ["summary", "pr_url", "diff_summary"]),
    "release->lead": ("company-release", "company-lead", ["summary", "result"]),
}

KIND_TO_FILENAME = {
    "pm->lead": "pm-lead",
    "lead->dev": "lead-dev",
    "lead->qa": "lead-qa",
    "dev->qa": "dev-qa",
    "qa->dev": "qa-dev",
    "qa->lead": "qa-lead",
    "pr->lead": "pr-lead",
    "release->lead": "release-lead",
}

# Alias senza '->': i kind sono usati anche come argomenti di shell, dove
# '>' verrebbe interpretato come redirect.
KIND_ALIASES = {kind.replace("->", "-"): kind for kind in HANDOFF_SCHEMA}


def resolve_kind(kind: str) -> str:
    """Normalizza un kind di handoff, accettando anche la forma con trattino."""
    if kind in HANDOFF_SCHEMA:
        return kind
    if kind in KIND_ALIASES:
        return KIND_ALIASES[kind]
    raise GuardError(f"kind di handoff sconosciuto: {kind}")

BRANCH_RE = re.compile(r"^(feature|fix|chore|hotfix)/[A-Z]+-\d+-[a-z0-9][a-z0-9-]*$")
ESCALATION_SECTIONS = ("## 1. Problema", "## 2. Cosa è già stato tentato", "## 3. Opzioni", "## 4. Domanda al CEO")


class GuardError(Exception):
    """Violazione di una regola del protocollo."""


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def policy() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))["policy"]


def load_ticket(ticket_id: str) -> dict:
    path = TICKETS_DIR / f"{ticket_id}.json"
    if not path.exists():
        raise GuardError(f"ticket inesistente: {ticket_id}")
    return json.loads(path.read_text(encoding="utf-8"))


def handoff_path(ticket_id: str, kind: str) -> Path:
    suffix = KIND_TO_FILENAME.get(kind, kind.replace("->", "-"))
    return HANDOFFS_DIR / f"{ticket_id}-{suffix}.json"


def find_handoff(ticket_id: str, kind: str) -> dict | None:
    """Trova l'handoff del kind richiesto, verificandone i campi obbligatori."""
    kind = resolve_kind(kind)
    path = handoff_path(ticket_id, kind)
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None
    if not isinstance(data, dict):
        return None
    for field in HANDOFF_SCHEMA.get(kind, ("", "", []))[2]:
        if not data.get(field):
            return None
    return data


def find_escalation(ticket_id: str) -> str | None:
    if not ESCALATIONS_DIR.exists():
        return None
    matches = sorted(ESCALATIONS_DIR.glob(f"ESC-{ticket_id}-*.md"))
    if not matches:
        return None
    return matches[-1].read_text(encoding="utf-8")


def escalation_missing_sections(text: str) -> list[str]:
    return [s for s in ESCALATION_SECTIONS if s not in text]


def git(*args: str) -> tuple[int, str]:
    proc = subprocess.run(
        ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=False
    )
    return proc.returncode, (proc.stdout + proc.stderr).strip()


def branch_exists(branch: str) -> bool:
    code, out = git("rev-parse", "--verify", branch)
    return code == 0 and bool(out)


def commit_exists(sha: str) -> bool:
    code, _ = git("cat-file", "-e", f"{sha}^{{commit}}")
    return code == 0


def branch_contains(branch: str, sha: str) -> bool:
    """True se il commit e' effettivamente contenuto nel branch."""
    code, _ = git("merge-base", "--is-ancestor", sha, branch)
    return code == 0


def commits_in_branch(branch: str, base: str | None = None) -> list[str]:
    """Commit presenti nel branch e non nella base, dal piu' recente."""
    code, out = git("log", "--format=%H", f"{base or base_branch()}..{branch}")
    if code != 0:
        return []
    return [line.strip() for line in out.splitlines() if line.strip()]


def base_branch() -> str:
    """Il branch base del progetto: la base dei diff e dei commit del branch."""
    return policy().get("base_branch") or "main"


def changed_files(branch: str, base: str | None = None) -> list[str]:
    code, out = git("diff", "--name-only", f"{base or base_branch()}...{branch}")
    if code != 0:
        return []
    return [line.strip() for line in out.splitlines() if line.strip()]


def path_in_scope(path: str, touches: list[str]) -> bool:
    """True se il path ricade dentro almeno uno dei path autorizzati."""
    clean = path.strip().lstrip("./")
    for allowed in touches:
        allowed = allowed.strip().lstrip("./")
        if not allowed:
            continue
        if allowed.endswith("/"):
            if clean.startswith(allowed):
                return True
            continue
        if clean == allowed:
            return True
        # un path autorizzato che sia directory padre
        if "/" not in allowed and "/" in clean and clean.split("/")[0] == allowed:
            return True
    return False


def is_test_path(path: str, globs: list[str]) -> bool:
    from fnmatch import fnmatch

    return any(fnmatch(path, pattern) for pattern in globs)


def check_handoff(ticket_id: str, kind: str) -> dict:
    """Valida un handoff. Ritorna l'handoff se valido, solleva GuardError altrimenti."""
    kind = resolve_kind(kind)
    if kind not in HANDOFF_SCHEMA:
        raise GuardError(f"kind di handoff sconosciuto: {kind}")

    path = handoff_path(ticket_id, kind)
    if not path.exists():
        raise GuardError(f"handoff mancante: {path.relative_to(REPO_ROOT)}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise GuardError(f"handoff non e' JSON valido: {exc}") from exc

    src, dst, required = HANDOFF_SCHEMA[kind]
    if data.get("from") != src or data.get("to") != dst:
        raise GuardError(
            f"handoff {kind} deve avere from='{src}' e to='{dst}', "
            f"trovati from='{data.get('from')}' to='{data.get('to')}'"
        )
    if data.get("ticket") != ticket_id:
        raise GuardError(
            f"handoff {kind} dichiara il ticket '{data.get('ticket')}', atteso '{ticket_id}'"
        )
    if not data.get("created_at"):
        raise GuardError(f"handoff {kind} senza created_at")
    for field in required:
        if not data.get(field):
            raise GuardError(f"handoff {kind} senza il campo obbligatorio '{field}'")

    ticket = load_ticket(ticket_id)
    pol = policy()

    branch = data.get("branch") or ticket.get("branch")
    if branch:
        if branch in pol["protected_branches"]:
            raise GuardError(f"branch '{branch}' e' protetto: i branch protetti non si lavorano")
        if not BRANCH_RE.match(branch):
            raise GuardError(
                f"branch '{branch}' non conforme al naming: atteso "
                f"<tipo>/<TICKET>-<slug>, es. feature/TKT-001-user-login"
            )
        if not branch_exists(branch):
            raise GuardError(f"branch '{branch}' non esiste nel repository")

    for sha in data.get("commits") or []:
        if not commit_exists(sha):
            raise GuardError(f"commit inesistente: {sha}")
        if branch and not branch_contains(branch, sha):
            raise GuardError(
                f"commit {sha} non e' contenuto nel branch {branch}: "
                f"l'handoff dichiara un commit che il branch non contiene"
            )

    if kind == "dev->qa":
        touches = ticket.get("touches") or []
        if touches:
            out_of_scope = [
                f for f in data.get("changed_files", []) if not path_in_scope(f, touches)
            ]
            if out_of_scope:
                raise GuardError(
                    f"scope creep: i file {out_of_scope} sono fuori da 'touches' "
                    f"({touches}). Chiedi al Tech Lead di estendere lo scope."
                )

    if kind == "qa->lead":
        if data.get("verdict") != "APPROVED":
            raise GuardError(
                f"handoff qa->lead con verdetto '{data.get('verdict')}': "
                f"serve APPROVED per la review"
            )
        report_path = REPO_ROOT / str(data["report_path"])
        if not report_path.exists():
            raise GuardError(f"qa report non trovato: {data['report_path']}")
        report = json.loads(report_path.read_text(encoding="utf-8"))
        criteria = report.get("criteria") or []
        if not criteria:
            raise GuardError("qa report senza criteria")
        failed = [c for c in criteria if c.get("result") != "PASS"]
        if failed:
            raise GuardError(
                f"qa report APPROVED ma {len(failed)} AC non superati: "
                f"{', '.join(c.get('id', '?') for c in failed)}"
            )
        ac_ids = {c.get("id") for c in ticket.get("acceptance_criteria", [])}
        if ac_ids:
            missing = ac_ids - {c.get("id") for c in criteria}
            if missing:
                raise GuardError(
                    f"qa report non copre gli AC {sorted(missing)} del ticket: "
                    f"un AC non verificato non puo' passare la review"
                )

    if kind == "qa->dev":
        if data.get("verdict") != "REJECTED":
            raise GuardError("handoff qa->dev richiede verdict 'REJECTED'")
        for item in data.get("failed_tests", []):
            if not item.get("output"):
                raise GuardError(
                    "rifiuto QA senza output reale del test: il Developer deve "
                    "poter riprodurre il fallimento"
                )

    if kind == "pr->lead":
        if not data.get("review_findings") and not data.get("merged"):
            raise GuardError(
                "handoff pr->lead senza review_findings e senza 'merged': "
                "manca l'esito della review"
            )

    return data


def gate(ticket_id: str) -> dict:
    """Regole di uscita del ticket: scope, branch, test."""
    ticket = load_ticket(ticket_id)
    pol = policy()
    problems: list[str] = []
    branch = ticket.get("branch")

    if not branch:
        problems.append("nessun branch assegnato")
    else:
        if branch in pol["protected_branches"]:
            problems.append(f"branch protetto: {branch}")
        elif not BRANCH_RE.match(branch):
            problems.append(f"branch non conforme al naming: {branch}")
        elif not branch_exists(branch):
            problems.append(f"branch inesistente: {branch}")

    touches = ticket.get("touches") or []
    if branch and branch_exists(branch):
        files = changed_files(branch)
        if touches:
            out = [f for f in files if not path_in_scope(f, touches)]
            if out:
                problems.append(f"file fuori scope: {out}")
        if not files:
            problems.append(f"il branch {branch} non contiene modifiche")
        # Ogni commit del branch deve essere nel pack/handoff: un commit
        # "fantasma" non dichiarato non e' tracciato dal protocollo.
        declared = set()
        for kind in ("dev->qa", "pr->lead"):
            handoff = find_handoff(ticket_id, kind)
            if handoff:
                declared.update(handoff.get("commits") or [])
        if declared:
            # Gli handoff possono citare SHA abbreviati: confronta per prefisso.
            prefixes = tuple(declared)
            undeclared = [
                sha
                for sha in commits_in_branch(branch)
                if not any(sha.startswith(prefix) for prefix in prefixes)
            ]
            if undeclared:
                problems.append(
                    f"commit del branch non dichiarati nell'handoff: {undeclared}"
                )

    acs = ticket.get("acceptance_criteria") or []
    if not acs:
        problems.append("il ticket non ha Acceptance Criteria")
    for ac in acs:
        if not ac.get("how"):
            problems.append(f"AC {ac.get('id')} non ha il campo 'how' (come si verifica)")

    has_tests = False
    for ac in acs:
        how = str(ac.get("how") or "")
        if re.search(r"test|spec|suite", how, re.IGNORECASE):
            has_tests = True
            break
    if not has_tests:
        problems.append("nessun AC e' coperto da un test automatico")

    return {"ticket": ticket_id, "ok": not problems, "problems": problems}


def cmd_check(args: argparse.Namespace) -> int:
    data = check_handoff(args.ticket, args.kind)
    print(f"OK  {args.ticket}  {args.kind}")
    print(json.dumps({k: v for k, v in data.items() if k != "logs"}, indent=2, ensure_ascii=False))
    return 0


def cmd_handoff(args: argparse.Namespace) -> int:
    """Crea lo scheletro di un handoff con i campi obbligatori gia' valorizzati."""
    args.kind = resolve_kind(args.kind)
    if args.kind not in HANDOFF_SCHEMA:
        raise GuardError(f"kind sconosciuto: {args.kind}")
    src, dst, required = HANDOFF_SCHEMA[args.kind]
    ticket = load_ticket(args.ticket)
    data: dict = {
        "ticket": args.ticket,
        "from": src,
        "to": dst,
        "kind": args.kind,
        "created_at": now_iso(),
        "summary": "",
    }
    if src == "company-dev" or dst == "company-dev":
        data["branch"] = ticket.get("branch") or ""
        data["commits"] = []
    if args.kind == "dev->qa":
        data["changed_files"] = []
        data["artifacts"] = {"build": "", "lint": "", "tests_local": "", "pr_url": None}
        data["questions"] = []
        data["next_action"] = "qa: eseguire la suite e verificare gli AC"
    elif args.kind == "qa->dev":
        data["verdict"] = "REJECTED"
        data["failed_tests"] = []
        data["logs"] = ""
        data["next_action"] = "dev: correggere e riconsegnare"
    elif args.kind == "qa->lead":
        data["verdict"] = "APPROVED"
        data["report_path"] = f".company/board/handoffs/{args.ticket}-qa-report.json"
        data["next_action"] = "lead: code review e merge"
    elif args.kind == "pr->lead":
        data["pr_url"] = ""
        data["diff_summary"] = ""
        data["review_findings"] = []
        data["merged"] = False
        data["next_action"] = "lead: code review"
    elif args.kind == "release->lead":
        data["result"] = "released"
        data["merged_commit"] = ""
        data["changelog_entry"] = ""
        data["tag"] = None
        data["verification"] = ""
        data["issues"] = []
        data["next_action"] = "lead: chiudere il ticket in DONE"
    elif args.kind == "pm->lead":
        data["artifacts"] = {"prd": "", "epic": "", "tickets": []}
        data["questions"] = []
        data["next_action"] = "lead: definire il design e assegnare il ticket"
    else:
        data["next_action"] = ""

    path = handoff_path(args.ticket, args.kind)
    HANDOFFS_DIR.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"creato {path.relative_to(REPO_ROOT)} (campi obbligatori: {required})")
    return 0


def cmd_gate(args: argparse.Namespace) -> int:
    result = gate(args.ticket)
    if result["ok"]:
        print(f"GATE OK  {args.ticket}")
        return 0
    print(f"GATE FAIL  {args.ticket}")
    for problem in result["problems"]:
        print(f"  - {problem}")
    return 1


def cmd_escalate(args: argparse.Namespace) -> int:
    text = find_escalation(args.ticket)
    if text is None:
        print(f"ERRORE: nessun escalation report per {args.ticket}", file=sys.stderr)
        return 2
    missing = escalation_missing_sections(text)
    if missing:
        print(f"ERRORE: escalation report incompleto, mancano: {missing}", file=sys.stderr)
        return 1
    print(f"OK  escalation report per {args.ticket}: sezioni complete")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Guard del protocollo AI Company")
    sub = parser.add_subparsers(dest="command", required=True)

    p_check = sub.add_parser("check")
    p_check.add_argument("ticket")
    p_check.add_argument("kind")
    p_check.set_defaults(func=cmd_check)

    p_handoff = sub.add_parser("handoff")
    p_handoff.add_argument("ticket")
    p_handoff.add_argument("kind")
    p_handoff.set_defaults(func=cmd_handoff)

    p_gate = sub.add_parser("gate")
    p_gate.add_argument("ticket")
    p_gate.set_defaults(func=cmd_gate)

    p_esc = sub.add_parser("escalate")
    p_esc.add_argument("ticket")
    p_esc.set_defaults(func=cmd_escalate)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except GuardError as exc:
        print(f"ERRORE: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
