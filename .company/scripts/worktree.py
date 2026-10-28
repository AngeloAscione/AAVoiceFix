#!/usr/bin/env python3
"""Worktree isolati per i Developer.

Ogni ticket lavora su un branch dedicato `feature/<TICKET>-<slug>` con un
worktree in .company/worktrees/<TICKET>-<slug>. Il Tech Lead crea il
worktree; il Developer ci lavora dentro; il merge avviene solo dopo QA
APPROVED e review.

Uso:
    worktree.py create <TICKET>
    worktree.py list
    worktree.py remove <TICKET>
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKTREES_DIR = REPO_ROOT / ".company" / "worktrees"
TICKETS_DIR = REPO_ROOT / ".company" / "board" / "tickets"
MANIFEST = REPO_ROOT / ".company" / "manifest.json"
BRANCH_RE = re.compile(r"^(feature|fix|chore|hotfix)/[A-Z]+-\d+-[a-z0-9][a-z0-9-]*$")


class WorktreeError(Exception):
    pass


def git(*args: str, cwd: Path = REPO_ROOT) -> tuple[int, str]:
    proc = subprocess.run(
        ["git", *args], cwd=cwd, capture_output=True, text=True, check=False
    )
    return proc.returncode, (proc.stdout + proc.stderr).strip()


def load_ticket(ticket_id: str) -> dict:
    path = TICKETS_DIR / f"{ticket_id}.json"
    if not path.exists():
        raise WorktreeError(f"ticket inesistente: {ticket_id}")
    return json.loads(path.read_text(encoding="utf-8"))


def slugify(title: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    return slug[:40] or "ticket"


def create(ticket_id: str) -> int:
    ticket = load_ticket(ticket_id)
    branch = ticket.get("branch")
    if not branch:
        raise WorktreeError(
            f"{ticket_id} non ha un branch: il Tech Lead deve impostarlo "
            f"(board.py set-field {ticket_id} branch feature/{ticket_id}-{slugify(ticket.get('title',''))})"
        )
    if not BRANCH_RE.match(branch):
        raise WorktreeError(f"branch non conforme al naming: {branch}")

    protected = json.loads(MANIFEST.read_text(encoding="utf-8"))["policy"]["protected_branches"]
    if branch in protected:
        raise WorktreeError(f"branch protetto: {branch}")

    code, out = git("rev-parse", "--verify", branch)
    if code == 0:
        # Il branch esiste gia': riuso il worktree se presente.
        worktree = WORKTREES_DIR / f"{ticket_id}-{slugify(ticket.get('title', ''))}"
        if worktree.exists():
            print(f"worktree gia' presente: {worktree.relative_to(REPO_ROOT)}")
            return 0
        raise WorktreeError(
            f"il branch {branch} esiste gia' ma non ha un worktree. "
            f"Usalo direttamente o rimuovilo prima."
        )

    WORKTREES_DIR.mkdir(parents=True, exist_ok=True)
    worktree = WORKTREES_DIR / f"{ticket_id}-{slugify(ticket.get('title', ''))}"
    base = json.loads(MANIFEST.read_text(encoding="utf-8"))["policy"].get("base_branch", "main")
    code, out = git("worktree", "add", "-b", branch, str(worktree), base)
    if code != 0:
        raise WorktreeError(f"git worktree add fallito: {out}")
    print(f"creato worktree {worktree.relative_to(REPO_ROOT)} sul branch {branch}")
    return 0


def list_all() -> int:
    code, out = git("worktree", "list", "--porcelain")
    if code != 0:
        print(out, file=sys.stderr)
        return 1
    entries = []
    current: dict[str, str] = {}
    for line in out.splitlines():
        if not line.strip():
            if current:
                entries.append(current)
                current = {}
            continue
        key, _, value = line.partition(" ")
        if key == "worktree":
            current = {"path": value}
        elif key == "branch":
            current["branch"] = value.replace("refs/heads/", "")
    if current:
        entries.append(current)
    if not entries:
        print("nessun worktree")
        return 0
    for entry in entries:
        marker = "*" if ".company/worktrees" in entry.get("path", "") else " "
        print(f"{marker} {entry.get('branch', '(detached)'):<40} {entry.get('path')}")
    return 0


def remove(ticket_id: str) -> int:
    ticket = load_ticket(ticket_id)
    slug = slugify(ticket.get("title", ""))
    worktree = WORKTREES_DIR / f"{ticket_id}-{slug}"
    if not worktree.exists():
        candidates = [p for p in WORKTREES_DIR.glob(f"{ticket_id}-*")] if WORKTREES_DIR.exists() else []
        if not candidates:
            print(f"nessun worktree per {ticket_id}")
            return 0
        worktree = candidates[0]
    code, out = git("worktree", "remove", "--force", str(worktree))
    if code != 0:
        raise WorktreeError(f"git worktree remove fallito: {out}")
    print(f"rimosso {worktree.relative_to(REPO_ROOT)}")
    return 0


def main(argv: list[str] | None = None) -> int:
    if argv is None:
        argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        return 2
    action, *rest = argv
    try:
        if action == "create":
            return create(rest[0])
        if action == "list":
            return list_all()
        if action == "remove":
            return remove(rest[0])
    except WorktreeError as exc:
        print(f"ERRORE: {exc}", file=sys.stderr)
        return 2
    print(f"azione sconosciuta: {action}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
