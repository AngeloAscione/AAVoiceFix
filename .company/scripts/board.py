#!/usr/bin/env python3
"""Board dei ticket della AI Company.

Implementa la macchina a stati e il circuit breaker definiti in
.company/protocol/STATE_MACHINE.md. E' l'unica fonte di verita sullo stato
dei ticket: nessun agente scrive direttamente i campi di stato.

Uso:
    board.py list [--status STATUS] [--json]
    board.py show TKT-001
    board.py create --file <json>
    board.py move TKT-001 <STATO> --actor <ruolo> [--note "..."]
    board.py attempts TKT-001 --qa-rejections N --lead-cycles N
    board.py assign TKT-001 <ruolo>
    board.py set-field TKT-001 <campo> <valore>
    board.py next [--limit N]
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
TICKETS_DIR = REPO_ROOT / ".company" / "board" / "tickets"
MANIFEST = REPO_ROOT / ".company" / "manifest.json"

BACKLOG = "BACKLOG"
TODO = "TODO"
IN_PROGRESS = "IN_PROGRESS"
IN_QA = "IN_QA"
IN_REVIEW = "IN_REVIEW"
DONE = "DONE"
BLOCKED = "BLOCKED"
BLOCKED_WAITING_HUMAN = "BLOCKED_WAITING_HUMAN"

STATES = (
    BACKLOG,
    TODO,
    IN_PROGRESS,
    IN_QA,
    IN_REVIEW,
    DONE,
    BLOCKED,
    BLOCKED_WAITING_HUMAN,
)

# (from, to) -> actori ammessi
TRANSITIONS: dict[tuple[str, str], set[str]] = {
    (BACKLOG, TODO): {"company-pm", "company-lead"},
    (TODO, BACKLOG): {"company-lead"},
    (TODO, IN_PROGRESS): {"company-lead"},
    (IN_PROGRESS, IN_QA): {"company-dev", "company-lead"},
    (IN_QA, IN_REVIEW): {"company-qa", "company-lead"},
    (IN_QA, IN_PROGRESS): {"company-qa", "company-lead"},
    (IN_QA, BLOCKED): {"company-qa", "company-lead"},
    (IN_QA, BLOCKED_WAITING_HUMAN): {"company-lead"},
    (IN_REVIEW, DONE): {"company-lead"},
    (IN_REVIEW, IN_PROGRESS): {"company-lead"},
    (IN_REVIEW, BLOCKED): {"company-lead"},
    (BLOCKED, IN_PROGRESS): {"company-lead"},
    (BLOCKED, BLOCKED_WAITING_HUMAN): {"company-lead"},
    (BLOCKED_WAITING_HUMAN, IN_PROGRESS): {"company-lead"},
    (BLOCKED_WAITING_HUMAN, BACKLOG): {"company-lead"},
}

# Stati da cui un ticket puo' essere lavorato in parallelo da un agente.
ACTIVE_STATES = {IN_PROGRESS, IN_QA, IN_REVIEW}

PRIORITY_ORDER = {"P0": 0, "P1": 1, "P2": 2, "P3": 3}


class BoardError(Exception):
    """Errore di regola: operazione rifiutata dalla macchina a stati."""


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load_manifest() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def policy() -> dict:
    return load_manifest()["policy"]


def ticket_path(ticket_id: str) -> Path:
    return TICKETS_DIR / f"{ticket_id}.json"


def load_ticket(ticket_id: str) -> dict:
    path = ticket_path(ticket_id)
    if not path.exists():
        raise BoardError(f"ticket inesistente: {ticket_id}")
    return json.loads(path.read_text(encoding="utf-8"))


def save_ticket(ticket: dict) -> None:
    TICKETS_DIR.mkdir(parents=True, exist_ok=True)
    path = ticket_path(ticket["id"])
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(ticket, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    tmp.replace(path)


def all_tickets() -> list[dict]:
    if not TICKETS_DIR.exists():
        return []
    tickets = []
    for path in sorted(TICKETS_DIR.glob("*.json")):
        try:
            tickets.append(json.loads(path.read_text(encoding="utf-8")))
        except json.JSONDecodeError as exc:
            raise BoardError(f"ticket corrotto {path.name}: {exc}") from exc
    return tickets


def next_ticket_id() -> str:
    highest = 0
    for ticket in all_tickets():
        ident = ticket.get("id", "")
        if ident.startswith("TKT-"):
            try:
                highest = max(highest, int(ident[4:]))
            except ValueError:
                continue
    return f"TKT-{highest + 1:03d}"


def require_handoff(ticket: dict, kind: str) -> dict:
    """Carica un handoff validato per il ticket. Delega a guard.py."""
    guard = import_guard()
    handoff = guard.find_handoff(ticket["id"], kind)
    if handoff is None:
        raise BoardError(
            f"manca l'handoff '{kind}' per {ticket['id']}: "
            f"non e' possibile la transizione senza di esso"
        )
    return handoff


def import_guard():
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import guard  # noqa: PLC0415

    return guard


def check_transition(ticket: dict, to_state: str, actor: str) -> None:
    """Applica tutte le regole della macchina a stati e del circuit breaker."""
    from_state = ticket["status"]
    pol = policy()
    max_qa = int(pol["max_qa_retries"])
    max_lead = int(pol["max_lead_cycles"])

    if to_state not in STATES:
        raise BoardError(f"stato sconosciuto: {to_state}")
    if from_state == to_state:
        raise BoardError(f"{ticket['id']} e' gia' in {to_state}")

    allowed_actors = TRANSITIONS.get((from_state, to_state))
    if allowed_actors is None:
        raise BoardError(
            f"transizione non ammessa: {from_state} -> {to_state} "
            f"(transizioni valide da {from_state}: "
            f"{[t for (f, t) in TRANSITIONS if f == from_state]})"
        )
    if actor not in allowed_actors:
        raise BoardError(
            f"transizione {from_state} -> {to_state} non consentita a '{actor}' "
            f"(consentiti: {sorted(allowed_actors)})"
        )

    attempts = ticket.setdefault(
        "attempts", {"qa_rejections": 0, "lead_cycles": 0, "lead_rejections": 0}
    )

    # Circuit breaker: il ciclo Dev<->QA puo' fallire al massimo max_qa volte
    # consecutive. L'ultimo fallimento (il max_qa-esimo) NON torna al Developer:
    # finisce dal Tech Lead. Con max_qa=3 il Developer ha 2 round di correzione
    # e il terzo rifiuto porta il ticket in BLOCKED.
    if from_state == IN_QA and to_state == IN_PROGRESS:
        if attempts.get("qa_rejections", 0) >= max_qa - 1:
            raise BoardError(
                f"CIRCUIT BREAKER: {ticket['id']} sta per registrare il rifiuto "
                f"n. {max_qa} di QA (rifiuti consecutivi: "
                f"{attempts.get('qa_rejections', 0)}, max {max_qa}). "
                f"L'unica mossa ammessa e' IN_QA -> BLOCKED: il Tech Lead "
                f"deve risolvere la root cause."
            )

    # Un solo ciclo guidato del Tech Lead.
    if from_state == BLOCKED and to_state == IN_PROGRESS:
        if attempts.get("lead_cycles", 0) >= max_lead:
            raise BoardError(
                f"CIRCUIT BREAKER: {ticket['id']} ha esaurito i cicli guidati del "
                f"Tech Lead (max {max_lead}). L'unica mossa ammessa e' "
                f"BLOCKED -> BLOCKED_WAITING_HUMAN."
            )

    # Requisiti di ingresso per stato.
    if to_state == IN_PROGRESS:
        if from_state == TODO:
            if not ticket.get("branch"):
                raise BoardError(
                    f"{ticket['id']} non ha un branch: il Tech Lead deve assegnare "
                    f"branch=feature/<TICKET>-<slug> prima di avviare il lavoro"
                )
        if from_state == BLOCKED:
            require_handoff(ticket, "lead->dev")

    if to_state == IN_QA:
        if not ticket.get("branch"):
            raise BoardError(f"{ticket['id']} non ha un branch: impossibile passare in IN_QA")
        handoff = require_handoff(ticket, "dev->qa")
        if not handoff.get("commits"):
            raise BoardError(f"handoff dev->qa di {ticket['id']} senza commit")

    if to_state == IN_REVIEW:
        report = require_handoff(ticket, "qa->lead")
        if report.get("verdict") != "APPROVED":
            raise BoardError(
                f"{ticket['id']} non puo' entrare in IN_REVIEW: il verdetto QA e' "
                f"'{report.get('verdict')}', serve APPROVED"
            )
        criteria = report.get("criteria") or []
        failed = [c for c in criteria if c.get("result") != "PASS"]
        if failed:
            raise BoardError(
                f"{ticket['id']} non puo' entrare in IN_REVIEW: {len(failed)} AC "
                f"non superati ({', '.join(c.get('id', '?') for c in failed)})"
            )

    if to_state == DONE:
        pr = require_handoff(ticket, "pr->lead")
        if not pr.get("merged"):
            raise BoardError(
                f"{ticket['id']} non puo' passare a DONE: il merge su main non e' "
                f"confermato (handoff pr->lead senza 'merged': true)"
            )

    if to_state == BLOCKED_WAITING_HUMAN:
        esc = import_guard().find_escalation(ticket["id"])
        if esc is None:
            raise BoardError(
                f"{ticket['id']} non puo' passare a BLOCKED_WAITING_HUMAN: "
                f"manca l'escalation report in .company/escalations/"
            )
        missing = import_guard().escalation_missing_sections(esc)
        if missing:
            raise BoardError(
                f"escalation report di {ticket['id']} incompleto: mancano le "
                f"sezioni {missing}"
            )


def apply_transition(ticket: dict, to_state: str, actor: str, note: str) -> dict:
    from_state = ticket["status"]
    attempts = ticket.setdefault(
        "attempts", {"qa_rejections": 0, "lead_cycles": 0, "lead_rejections": 0}
    )
    pol = policy()
    max_qa = int(pol["max_qa_retries"])

    # Aggiornamento contatori secondo la macchina a stati.
    if to_state == IN_PROGRESS:
        if from_state == IN_QA:
            # Il rifiuto e' gia' avvenuto: questo rientro e' il suo effetto.
            attempts["qa_rejections"] = attempts.get("qa_rejections", 0) + 1
        elif from_state == IN_REVIEW:
            attempts["lead_rejections"] = attempts.get("lead_rejections", 0) + 1
            attempts["qa_rejections"] = 0
        elif from_state == BLOCKED:
            # Riapertura guidata dal Tech Lead: consuma un ciclo guidato e
            # azzera i rifiuti, perche' le direttive sono cambiate.
            attempts["lead_cycles"] = attempts.get("lead_cycles", 0) + 1
            attempts["qa_rejections"] = 0
        elif from_state == BLOCKED_WAITING_HUMAN:
            attempts["qa_rejections"] = 0
            attempts["lead_cycles"] = 0
            attempts["lead_rejections"] = 0
    elif to_state == IN_REVIEW:
        # QA ha superato la verifica: il ciclo Dev<->QA riparte pulito.
        attempts["qa_rejections"] = 0
    elif to_state == BLOCKED:
        # Il contatore dei rifiuti NON si azzera: e' l'evidenza di quanti
        # cicli di correzione sono falliti. Si azzera solo quando il ticket
        # torna a una revisione superata o quando il CEO decide.
        pass
    elif to_state == BACKLOG:
        attempts["qa_rejections"] = 0
        attempts["lead_cycles"] = 0
        attempts["lead_rejections"] = 0

    ticket["status"] = to_state
    ticket.setdefault("history", []).append(
        {
            "ts": now_iso(),
            "from": from_state,
            "to": to_state,
            "actor": actor,
            "note": note,
        }
    )
    return ticket


def cmd_list(args: argparse.Namespace) -> int:
    tickets = all_tickets()
    if args.status:
        tickets = [t for t in tickets if t.get("status") == args.status]
    tickets.sort(key=lambda t: (PRIORITY_ORDER.get(t.get("priority", "P3"), 9), t.get("id", "")))
    if args.json:
        print(json.dumps(tickets, indent=2, ensure_ascii=False))
        return 0
    if not tickets:
        print("board vuota")
        return 0
    print(f"{'ID':<10} {'STATO':<22} {'PRIO':<5} {'ASSEGNATARIO':<14} TITOLO")
    for t in tickets:
        print(
            f"{t.get('id',''):<10} {t.get('status',''):<22} {t.get('priority',''):<5} "
            f"{str(t.get('assignee') or '-'):<14} {t.get('title','')}"
        )
    return 0


def cmd_show(args: argparse.Namespace) -> int:
    ticket = load_ticket(args.ticket)
    print(json.dumps(ticket, indent=2, ensure_ascii=False))
    return 0


def cmd_create(args: argparse.Namespace) -> int:
    ticket = json.loads(Path(args.file).read_text(encoding="utf-8"))
    if not ticket.get("id"):
        ticket["id"] = next_ticket_id()
    if ticket["id"] in {t.get("id") for t in all_tickets()}:
        raise BoardError(f"ticket duplicato: {ticket['id']}")
    ticket.setdefault("status", BACKLOG)
    ticket.setdefault("attempts", {"qa_rejections": 0, "lead_cycles": 0, "lead_rejections": 0})
    ticket.setdefault("history", []).append(
        {
            "ts": now_iso(),
            "from": None,
            "to": ticket["status"],
            "actor": args.actor,
            "note": args.note or "creato",
        }
    )
    save_ticket(ticket)
    print(f"creato {ticket['id']} in {ticket['status']}")
    return 0


def cmd_move(args: argparse.Namespace) -> int:
    ticket = load_ticket(args.ticket)
    check_transition(ticket, args.to, args.actor)
    apply_transition(ticket, args.to, args.actor, args.note or "")
    save_ticket(ticket)
    print(f"{ticket['id']}: {args.to}")
    return 0


def cmd_attempts(args: argparse.Namespace) -> int:
    ticket = load_ticket(args.ticket)
    attempts = ticket.setdefault(
        "attempts", {"qa_rejections": 0, "lead_cycles": 0, "lead_rejections": 0}
    )
    if args.qa_rejections is not None:
        attempts["qa_rejections"] = args.qa_rejections
    if args.lead_cycles is not None:
        attempts["lead_cycles"] = args.lead_cycles
    if args.lead_rejections is not None:
        attempts["lead_rejections"] = args.lead_rejections
    save_ticket(ticket)
    print(json.dumps(attempts))
    return 0


def cmd_assign(args: argparse.Namespace) -> int:
    ticket = load_ticket(args.ticket)
    ticket["assignee"] = args.assignee
    save_ticket(ticket)
    print(f"{ticket['id']} assegnato a {args.assignee}")
    return 0


def cmd_set_field(args: argparse.Namespace) -> int:
    ticket = load_ticket(args.ticket)
    value: object = args.value
    if args.json_value:
        value = json.loads(args.value)
    ticket[args.field] = value
    save_ticket(ticket)
    print(f"{ticket['id']}.{args.field} = {value}")
    return 0


def cmd_next(args: argparse.Namespace) -> int:
    candidates = [
        t
        for t in all_tickets()
        if t.get("status") in (TODO, BACKLOG)
        or (t.get("status") == BLOCKED and t.get("assignee") == "company-lead")
    ]
    candidates.sort(
        key=lambda t: (PRIORITY_ORDER.get(t.get("priority", "P3"), 9), t.get("id", ""))
    )
    selected = candidates[: args.limit]
    if args.json:
        print(json.dumps(selected, indent=2, ensure_ascii=False))
        return 0
    for t in selected:
        print(f"{t.get('id')} [{t.get('priority')}] {t.get('title')} ({t.get('status')})")
    if not selected:
        print("nessun ticket disponibile")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Board dei ticket AI Company")
    sub = parser.add_subparsers(dest="command", required=True)

    p_list = sub.add_parser("list")
    p_list.add_argument("--status")
    p_list.add_argument("--json", action="store_true")
    p_list.set_defaults(func=cmd_list)

    p_show = sub.add_parser("show")
    p_show.add_argument("ticket")
    p_show.set_defaults(func=cmd_show)

    p_create = sub.add_parser("create")
    p_create.add_argument("--file", required=True)
    p_create.add_argument("--actor", default="company-pm")
    p_create.add_argument("--note", default="")
    p_create.set_defaults(func=cmd_create)

    p_move = sub.add_parser("move")
    p_move.add_argument("ticket")
    p_move.add_argument("to")
    p_move.add_argument("--actor", required=True)
    p_move.add_argument("--note", default="")
    p_move.set_defaults(func=cmd_move)

    p_attempts = sub.add_parser("attempts")
    p_attempts.add_argument("ticket")
    p_attempts.add_argument("--qa-rejections", type=int)
    p_attempts.add_argument("--lead-cycles", type=int)
    p_attempts.add_argument("--lead-rejections", type=int)
    p_attempts.set_defaults(func=cmd_attempts)

    p_assign = sub.add_parser("assign")
    p_assign.add_argument("ticket")
    p_assign.add_argument("assignee")
    p_assign.set_defaults(func=cmd_assign)

    p_field = sub.add_parser("set-field")
    p_field.add_argument("ticket")
    p_field.add_argument("field")
    p_field.add_argument("value")
    p_field.add_argument("--json-value", action="store_true")
    p_field.set_defaults(func=cmd_set_field)

    p_next = sub.add_parser("next")
    p_next.add_argument("--limit", type=int, default=5)
    p_next.add_argument("--json", action="store_true")
    p_next.set_defaults(func=cmd_next)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except BoardError as exc:
        print(f"ERRORE: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
