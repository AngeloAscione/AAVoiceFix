#!/usr/bin/env python3
"""Generazione dei context pack: l'input minimo di un agente.

Un context pack e' un file Markdown che contiene tutto (e solo) cio' che
l'agente destinatario deve sapere per svolgere il suo compito: ticket,
Acceptance Criteria, design, scope, tentativi precedenti, errori noti.

Principio: l'agente riceve un pack, non una conversazione. Il pack e'
riproducibile, ispezionabile e indipendente dalla lunghezza del contesto.

Uso:
    pack.py <TICKET> <ruolo> [--output PATH]
    pack.py <TICKET> --all
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(
    os.environ.get("COMPANY_ROOT", Path(__file__).resolve().parents[2])
).resolve()
COMPANY_DIR = REPO_ROOT / ".company"
TICKETS_DIR = COMPANY_DIR / "board" / "tickets"
HANDOFFS_DIR = COMPANY_DIR / "board" / "handoffs"
PACKS_DIR = COMPANY_DIR / "board" / "packs"
MANIFEST = COMPANY_DIR / "manifest.json"

ROLE_FROM = {
    "company-pm": "pm->lead",
    "company-lead": "lead->dev",
    "company-dev": "dev->qa",
    "company-qa": "qa->lead",
    "company-release": "pr->lead",
}


class PackError(Exception):
    pass


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load_ticket(ticket_id: str) -> dict:
    path = TICKETS_DIR / f"{ticket_id}.json"
    if not path.exists():
        raise PackError(f"ticket inesistente: {ticket_id}")
    return json.loads(path.read_text(encoding="utf-8"))


def read_handoff(ticket_id: str, suffix: str) -> dict | None:
    path = HANDOFFS_DIR / f"{ticket_id}-{suffix}.json"
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None


def extract_design(ticket: dict) -> str:
    """Il design del Tech Lead vive nelle note tecniche del ticket JSON."""
    design = ticket.get("design") or ticket.get("technical_notes") or ""
    if isinstance(design, list):
        design = "\n".join(f"- {line}" for line in design)
    return design.strip()


def project_info() -> dict:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    return manifest.get("project") or {}


def build_pack(ticket: dict, role: str) -> str:
    attempts = ticket.get("attempts") or {}
    criteria = ticket.get("acceptance_criteria") or []
    project = project_info()
    lines: list[str] = []

    lines.append(f"# Context Pack — {ticket['id']} — {role}")
    lines.append("")
    lines.append(f"_Generato il {now_iso()}. Input completo per l'agente._")
    lines.append("")

    lines.append("## Progetto")
    lines.append("")
    lines.append(f"- **Nome**: {project.get('name') or REPO_ROOT.name}")
    lines.append(f"- **Stack**: {project.get('stack') or 'non dichiarato'}")
    lines.append(f"- **Comando build**: `{project.get('build_command') or 'non definito'}`")
    lines.append(f"- **Comando lint**: `{project.get('lint_command') or 'non definito'}`")
    lines.append(f"- **Comando test**: `{project.get('test_command') or 'non definito'}`")
    lines.append(f"- **Root sorgenti**: `{project.get('source_root') or 'src'}`")
    lines.append(f"- **Root test**: `{project.get('test_root') or 'tests'}`")
    if project.get("instrumented_test_command"):
        lines.append(
            f"- **Test strumentati**: `{project['instrumented_test_command']}` "
            f"(richiedono un emulatore o un device collegato)"
        )
    lines.append("")
    lines.append(
        "> Sono i comandi che **devi** usare per verificare il tuo lavoro. "
        "Esegui esattamente questi, riportandone l'output reale."
    )
    lines.append("")

    lines.append("## Ticket")
    lines.append("")
    lines.append(f"- **ID**: {ticket.get('id')}")
    lines.append(f"- **Titolo**: {ticket.get('title')}")
    lines.append(f"- **Tipo**: {ticket.get('type')}")
    lines.append(f"- **Epica**: {ticket.get('epic') or '-'}")
    lines.append(f"- **Stato**: {ticket.get('status')}")
    lines.append(f"- **Priorità**: {ticket.get('priority')}")
    lines.append(f"- **Spec**: {ticket.get('spec') or '-'}")
    lines.append(f"- **Branch**: {ticket.get('branch') or '(da assegnare)'}")
    lines.append("")

    if criteria:
        lines.append("## Acceptance Criteria")
        lines.append("")
        for ac in criteria:
            how = ac.get("how") or "(da specificare)"
            verified = " ✅" if ac.get("verified") else ""
            lines.append(f"- **{ac.get('id')}**: {ac.get('text')}{verified}")
            lines.append(f"  - Verificato da: {how}")
        lines.append("")

    design = extract_design(ticket)
    lines.append("## Design / direttive tecniche")
    lines.append("")
    lines.append(design if design else "(non ancora scritto dal Tech Lead)")
    lines.append("")

    touches = ticket.get("touches") or []
    lines.append("## Scope autorizzato (`touches`)")
    lines.append("")
    if touches:
        for path in touches:
            lines.append(f"- `{path}`")
    else:
        lines.append("(non definito)")
    lines.append("")
    lines.append("> Modificare un file fuori da questo elenco fa fallire la review.")
    lines.append("")

    out_of_scope = ticket.get("out_of_scope") or []
    if out_of_scope:
        lines.append("## Fuori scope")
        lines.append("")
        for item in out_of_scope:
            lines.append(f"- {item}")
        lines.append("")

    deps = ticket.get("dependencies") or {}
    if deps:
        lines.append("## Dipendenze")
        lines.append("")
        if deps.get("blocked_by"):
            lines.append(f"- **Bloccato da**: {deps['blocked_by']}")
        if deps.get("blocks"):
            lines.append(f"- **Blocca**: {deps['blocks']}")
        lines.append("")

    lines.append("## Tentativi precedenti")
    lines.append("")
    lines.append(
        f"- Rifiuti consecutivi di QA: **{attempts.get('qa_rejections', 0)}** "
        f"(max {json.loads(MANIFEST.read_text(encoding='utf-8'))['policy']['max_qa_retries']})"
    )
    lines.append(f"- Cicli guidati del Tech Lead: **{attempts.get('lead_cycles', 0)}**")
    lines.append(f"- Rilievi di review: **{attempts.get('lead_rejections', 0)}**")
    lines.append("")

    rejections = read_handoff(ticket["id"], "qa-dev")
    if rejections and rejections.get("failed_tests"):
        lines.append("### Errori noti (rifiuto QA)")
        lines.append("")
        for item in rejections["failed_tests"]:
            lines.append(
                f"- **{item.get('id', '?')}** — atteso: {item.get('expected')} · "
                f"reale: {item.get('actual')}"
            )
            if item.get("command"):
                lines.append(f"  - Riproduci: `{item['command']}`")
            if item.get("test_bug"):
                lines.append("  - ⚠ Il QA ha segnalato un possible bug del test, non del codice.")
        lines.append("")

    review = read_handoff(ticket["id"], "pr-lead")
    if review and review.get("review_findings"):
        lines.append("### Rilievi della review precedente")
        lines.append("")
        for finding in review["review_findings"]:
            lines.append(f"- {finding}")
        lines.append("")

    history = ticket.get("history") or []
    if history:
        lines.append("## Cronologia")
        lines.append("")
        for entry in history[-6:]:
            lines.append(
                f"- {entry.get('ts')} — {entry.get('from')} → {entry.get('to')} "
                f"({entry.get('actor')}) {entry.get('note', '')}".rstrip()
            )
        lines.append("")

    lines.append("## Dove scrivere i tuoi artefatti")
    lines.append("")
    lines.append(
        f"**Root del protocollo: `{REPO_ROOT}`** — questo percorso e' il checkout "
        f"principale. Se stai lavorando in un worktree, il tuo handoff va "
        f"**comunque** scritto sotto questo path, non dentro il worktree: la board "
        f"e' unica e il Tech Lead legge da qui."
    )
    lines.append("")
    if role == "company-dev":
        lines.append(
            f"- Handoff: `{REPO_ROOT}/.company/board/handoffs/{ticket['id']}-dev-qa.json`"
        )
    elif role == "company-qa":
        lines.append(
            f"- QA report: `{REPO_ROOT}/.company/board/handoffs/{ticket['id']}-qa-report.json`"
        )
        lines.append(
            f"- Handoff approvato: `{REPO_ROOT}/.company/board/handoffs/{ticket['id']}-qa-lead.json`"
        )
        lines.append(
            f"- Handoff di rifiuto: `{REPO_ROOT}/.company/board/handoffs/{ticket['id']}-qa-dev.json`"
        )
    elif role == "company-pm":
        lines.append(f"- PRD: `{REPO_ROOT}/.company/specs/PRD-<slug>.md`")
        lines.append(
            f"- Handoff: `{REPO_ROOT}/.company/board/handoffs/{ticket['id']}-pm-lead.json`"
        )
    elif role == "company-lead":
        lines.append(
            f"- Handoff: `{REPO_ROOT}/.company/board/handoffs/{ticket['id']}-lead-dev.json`"
        )
    elif role == "company-release":
        lines.append(
            f"- Handoff: `{REPO_ROOT}/.company/board/handoffs/{ticket['id']}-release-lead.json`"
        )
    lines.append("")

    lines.append("## Istruzioni per questo ruolo")
    lines.append("")
    if role == "company-dev":
        lines.append(
            "Implementa **solo** questo ticket, sul branch indicato, dentro lo scope "
            "autorizzato. Verifica build, lint e test prima di consegnare. Scrivi "
            "l'handoff `dev->qa` con l'output reale dei comandi."
        )
    elif role == "company-qa":
        lines.append(
            "Verifica **ogni** Acceptance Criterion, scrivi ed esegui i test, cerca "
            "regressioni sulla suite completa. Produci il `qa_report` con evidenza "
            "per ogni AC e l'handoff `qa->lead` (approvato) o `qa->dev` (rifiutato, "
            "con output reale del test)."
        )
    elif role == "company-lead":
        lines.append(
            "Definisci il design, assegna il ticket e supervisiona il ciclo. "
            "Non scrivere il codice applicativo del ticket. Applica il circuit "
            "breaker e scala al CEO solo nei casi previsti dal protocollo."
        )
    elif role == "company-pm":
        lines.append(
            "Formalizza requisiti, epiche e ticket. Scrivi il PRD solo se l'input "
            "è una nuova idea. Non specificare come implementare."
        )
    elif role == "company-release":
        lines.append(
            "Esegui il rilascio del branch indicato: merge, verifica sulla main, "
            "changelog, tag. Non modificare il codice e non forzare i conflitti."
        )
    lines.append("")

    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Genera i context pack della AI Company")
    parser.add_argument("ticket")
    parser.add_argument("role", nargs="?", choices=sorted(ROLE_FROM) + ["all"])
    parser.add_argument("--output", help="percorso di output (default: .company/board/packs/)")
    args = parser.parse_args(argv)

    ticket = load_ticket(args.ticket)
    roles = sorted(ROLE_FROM) if args.role in (None, "all") else [args.role]

    PACKS_DIR.mkdir(parents=True, exist_ok=True)
    written = []
    for role in roles:
        content = build_pack(ticket, role)
        if args.output and len(roles) == 1:
            out = Path(args.output)
            if not out.is_absolute():
                out = REPO_ROOT / out
        else:
            out = PACKS_DIR / f"{args.ticket}-{role}.md"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(content, encoding="utf-8")
        written.append(str(out.relative_to(REPO_ROOT)))

    for path in written:
        print(path)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except PackError as exc:
        print(f"ERRORE: {exc}", file=sys.stderr)
        sys.exit(2)
