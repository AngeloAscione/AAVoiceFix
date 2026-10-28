#!/usr/bin/env python3
"""company init — installa la squadra in un repository.

Copia lo scheletro del protocollo (.company/, .omp/) in un altro progetto e
rileva stack e comandi di build/lint/test. Il Tech Lead DEVE rivedere i
comandi rilevati: ogni agente li usa per verificare il proprio lavoro, quindi
un comando sbagliato fa fallire l'intero ciclo.

Uso:
    company init <path> [--dry-run] [--force] [--name NOME] [--stack STACK]
    company init --detect <path>      # mostra i comandi rilevati, non scrive
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

SOURCE_ROOT = Path(__file__).resolve().parents[2]
SOURCE_COMPANY = SOURCE_ROOT / ".company"
SOURCE_OMP = SOURCE_ROOT / ".omp"

# Sorgente -> destinazione (relativi alla root del progetto target)
COPY_TREE = [
    (".company/prompts", ".company/prompts"),
    (".company/protocol", ".company/protocol"),
    (".company/templates", ".company/templates"),
    (".company/scripts", ".company/scripts"),
    (".company/bin", ".company/bin"),
    (".company/AGENTS.md", ".company/AGENTS.md"),
    (".company/manifest.json", ".company/manifest.json"),
    (".omp/agents", ".omp/agents"),
    (".omp/AGENTS.md", ".omp/AGENTS.md"),
    (".omp/config.yml", ".omp/config.yml"),
    # Il .gitignore del protocollo non sovrascrive quello del progetto:
    # viene installato come file separato da includere.
    (
        ".company/templates/GITIGNORE_PROTOCOL.md",
        ".company/templates/GITIGNORE_PROTOCOL.md",
    ),
]

EMPTY_DIRS = [
    ".company/specs",
    ".company/board/tickets",
    ".company/board/handoffs",
    ".company/board/packs",
    ".company/board/decisions",
    ".company/escalations",
    ".company/logs",
    ".company/worktrees",
]

# pattern -> (stack, build, lint, test, test_root, source_root)
STACKS: list[tuple[str, str, str, str, str, str]] = [
    (
        "python",
        "python3 -m compileall -q src",
        "python3 -m compileall -q src tests",
        "python3 -m unittest discover -s tests -v",
        "tests",
        "src",
    ),
    (
        "node",
        "npm run build",
        "npm run lint",
        "npm test",
        "tests",
        "src",
    ),
    (
        "node",
        "npm run build",
        "npx tsc --noEmit",
        "npm test",
        "tests",
        "src",
    ),
    (
        "go",
        "go build ./...",
        "go vet ./...",
        "go test ./...",
        ".",
        ".",
    ),
    (
        "rust",
        "cargo build",
        "cargo clippy --all-targets",
        "cargo test",
        "tests",
        "src",
    ),
    (
        "java",
        "mvn -q -DskipTests package",
        "mvn -q checkstyle:check",
        "mvn -q test",
        "src/test",
        "src/main",
    ),
    (
        "php",
        "composer validate",
        "php -l src",
        "vendor/bin/phpunit",
        "tests",
        "src",
    ),
]


# Stack Android: i comandi gradle sono relativi alla root del progetto.
# `test_command` e' il test unit JVM (Robolectric), che gira senza device.
# `instrumented_test_command` richiede un emulatore o un device collegato:
# non e' il comando di verifica predefinito perche' la maggior parte delle
# macchine non ha un emulatore pronto, ma e' il comando da usare quando
# l'emulatore c'e' e serve la prova end-to-end reale.
ANDROID = (
    "android",
    "./gradlew :app:assembleDebug",
    "./gradlew :app:lintDebug",
    "./gradlew :app:testDebugUnitTest",
    "app/src/test",
    "app/src/main",
)


class InitError(Exception):
    pass


def detect_base_branch(root: Path) -> str:
    """Il branch base reale del progetto: `main`, `master`, `develop`...

    Non indovinare: se il repo ha un branch gia' spuntato, e' quello.
    Altrimenti si preferisce `main` se esiste, poi `master`.
    """
    proc = subprocess.run(
        ["git", "rev-parse", "--abbrev-ref", "HEAD"],
        cwd=root, capture_output=True, text=True, check=False,
    )
    current = proc.stdout.strip()
    # In un repo senza commit, `rev-parse` restituisce "HEAD": non e' un branch.
    if proc.returncode == 0 and current and current != "HEAD":
        return current
    # Repo appena inizializzato: il branch corrente e' quello di init.
    unborn = subprocess.run(
        ["git", "symbolic-ref", "--short", "HEAD"],
        cwd=root, capture_output=True, text=True, check=False,
    ).stdout.strip()
    if unborn:
        return unborn
    listing = subprocess.run(
        ["git", "branch", "--format=%(refname:short)"],
        cwd=root, capture_output=True, text=True, check=False,
    )
    branches = listing.stdout.split()
    for candidate in ("main", "master", "develop", "trunk"):
        if candidate in branches:
            return candidate
    return "main"


def detect(root: Path) -> dict:
    """Rileva stack e comandi dai file del progetto target.

    Le chiavi sono quelle del manifest: `*_command` per i comandi. Devono
    coincidere con `.company/manifest.json` o il pack non troverebbe i
    comandi da eseguire.
    """
    found: dict[str, str] = {}

    if (root / "pyproject.toml").exists() or (root / "setup.py").exists():
        found = dict(zip(("stack", "build_command", "lint_command", "test_command", "test_root", "source_root"), STACKS[0]))
        found["stack"] = "python"
    elif (root / "gradlew").exists() and (root / "app" / "build.gradle.kts").exists():
        found = dict(zip(("stack", "build_command", "lint_command", "test_command", "test_root", "source_root"), ANDROID))
        found["stack"] = "android"
    elif (root / "package.json").exists():
        pkg = json.loads((root / "package.json").read_text(encoding="utf-8"))
        scripts = pkg.get("scripts") or {}
        build = "npm run build" if "build" in scripts else "npx tsc --noEmit"
        lint = "npm run lint" if "lint" in scripts else "npx tsc --noEmit"
        test = "npm test" if "test" in scripts else "npx vitest run"
        found = {
            "stack": "node", "build_command": build, "lint_command": lint,
            "test_command": test, "test_root": "tests", "source_root": "src",
        }
    elif (root / "go.mod").exists():
        found = dict(zip(("stack", "build_command", "lint_command", "test_command", "test_root", "source_root"), STACKS[3]))
    elif (root / "Cargo.toml").exists():
        found = dict(zip(("stack", "build_command", "lint_command", "test_command", "test_root", "source_root"), STACKS[4]))
    elif (root / "pom.xml").exists():
        found = dict(zip(("stack", "build_command", "lint_command", "test_command", "test_root", "source_root"), STACKS[5]))
    elif (root / "composer.json").exists():
        found = dict(zip(("stack", "build_command", "lint_command", "test_command", "test_root", "source_root"), STACKS[6]))
    else:
        raise InitError(
            "stack non riconosciuto: nessun pyproject/package.json/go.mod/Cargo.toml/pom.xml/composer.json"
        )

    # Radici alternative: se la radice dichiarata non esiste, prova delle
    # alternative plausibili. I candidati sono valutati nell'ordine e vanno
    # scelti solo se esistono davvero: `app` esiste in un progetto Android ma
    # i sorgenti stanno in `app/src/main`.
    for key, candidates in (
        ("source_root", ("src", "app/src/main", "lib", "app", ".")),
        ("test_root", ("tests", "app/src/test", "test", "spec", ".")),
    ):
        if (root / found[key]).exists():
            continue
        for candidate in candidates:
            if (root / candidate).is_dir():
                found[key] = candidate
                break

    return found


def copy_skeleton(target: Path, dry_run: bool, force: bool) -> list[str]:
    written: list[str] = []
    for src_rel, dst_rel in COPY_TREE:
        src = SOURCE_ROOT / src_rel
        dst = target / dst_rel
        if not src.exists():
            continue
        if dst.exists() and not force:
            written.append(f"gia' presente, non toccato: {dst_rel}")
            continue
        if dry_run:
            written.append(f"[dry-run] copierei {src_rel} -> {dst_rel}")
            continue
        if src.is_dir():
            if dst.exists():
                shutil.rmtree(dst)
            shutil.copytree(src, dst, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
        else:
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
        written.append(f"copiato: {dst_rel}")
    return written


def update_manifest(target: Path, project: dict, base_branch: str, dry_run: bool) -> None:
    path = target / ".company" / "manifest.json"
    if dry_run or not path.exists():
        return
    manifest = json.loads(path.read_text(encoding="utf-8"))
    manifest["project"] = project
    manifest.setdefault("policy", {})["base_branch"] = base_branch
    path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def create_state_file(target: Path, project: dict, dry_run: bool) -> None:
    """Crea `.company/STATE.md` se manca.

    È il file che una sessione nuova legge per primo: senza, ogni sessione
    deve ricostruire lo stato leggendo board, handoff e git log.
    """
    path = target / ".company" / "STATE.md"
    if path.exists():
        print("  STATE.md gia' presente, non toccato")
        return
    template = SOURCE_COMPANY / "templates" / "STATE_TEMPLATE.md"
    if not template.exists():
        return
    text = template.read_text(encoding="utf-8").replace("<NOME>", str(project.get("name", target.name)))
    if dry_run:
        print("  [dry-run] creerei .company/STATE.md dal template")
        return
    path.write_text(text, encoding="utf-8")
    print("  creato .company/STATE.md (da compilare con lo stato iniziale)")


def create_empty_dirs(target: Path, dry_run: bool) -> None:
    for rel in EMPTY_DIRS:
        directory = target / rel
        if dry_run:
            continue
        directory.mkdir(parents=True, exist_ok=True)
        keep = directory / ".gitkeep"
        if not keep.exists():
            keep.touch()


def is_git_repo(root: Path) -> bool:
    proc = subprocess.run(
        ["git", "rev-parse", "--is-inside-work-tree"],
        cwd=root, capture_output=True, text=True, check=False,
    )
    return proc.returncode == 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="company init",
        description="Installa la squadra AI Company in un repository",
    )
    parser.add_argument("path", help="path del progetto target")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--force", action="store_true", help="sovrascrive i file esistenti")
    parser.add_argument("--detect", action="store_true", help="mostra i comandi rilevati e basta")
    parser.add_argument("--name", help="nome del progetto (default: nome della directory)")
    parser.add_argument("--stack", help="forza lo stack rilevato")
    parser.add_argument("--test-command", help="forza il comando di test")
    parser.add_argument("--build-command", help="forza il comando di build")
    parser.add_argument("--lint-command", help="forza il comando di lint")
    args = parser.parse_args(argv)

    target = Path(args.path).expanduser().resolve()
    if not target.is_dir():
        print(f"ERRORE: {target} non e' una directory", file=sys.stderr)
        return 2

    if args.detect:
        try:
            detected = detect(target)
        except InitError as exc:
            print(f"ERRORE: {exc}", file=sys.stderr)
            return 2
        detected["base_branch"] = detect_base_branch(target)
        print(json.dumps(detected, indent=2, ensure_ascii=False))
        return 0

    if not is_git_repo(target):
        print(
            f"ATTENZIONE: {target} non e' un repository git. "
            f"Il protocollo usa branch e worktree: inizializzalo con `git init`.",
            file=sys.stderr,
        )

    try:
        project = detect(target)
    except InitError as exc:
        print(f"ERRORE: {exc}", file=sys.stderr)
        return 2

    project["name"] = args.name or target.name
    if args.stack:
        project["stack"] = args.stack
    if args.test_command:
        project["test_command"] = args.test_command
    if args.build_command:
        project["build_command"] = args.build_command
    if args.lint_command:
        project["lint_command"] = args.lint_command
    project["notes"] = (
        "Comandi rilevati da `company init`. VERIFICATI DAL TECH LEAD: ogni agente li usa "
        "per build, lint e test, quindi un comando sbagliato fa fallire l'intero ciclo."
    )
    if project.get("stack") == "android":
        project["instrumented_test_command"] = "./gradlew :app:connectedDebugAndroidTest"
        project["instrumented_test_root"] = "app/src/androidTest"
        project["instrumented_notes"] = (
            "Test strumentati: richiedono un emulatore o un device collegato. "
            "Non sono il comando di verifica predefinito, perche' su molte macchine "
            "l'emulatore non e' pronto: usali quando serve la prova end-to-end reale."
        )

    base = detect_base_branch(target)
    written = copy_skeleton(target, args.dry_run, args.force)
    if not args.dry_run:
        update_manifest(target, project, base, args.dry_run)
        create_empty_dirs(target, args.dry_run)
        create_state_file(target, project, args.dry_run)
        # Rigenera le definizioni agenti nel target.
        subprocess.run(
            [sys.executable, str(target / ".company" / "scripts" / "sync_agents.py")],
            cwd=target, check=False,
        )

    print(f"Progetto: {project['name']}  (stack: {project['stack']})")
    print(f"  build: {project['build_command']}")
    print(f"  lint:  {project['lint_command']}")
    print(f"  test:  {project['test_command']}")
    print(f"  sorgenti: {project['source_root']}/   test: {project['test_root']}/")
    print(f"  branch base: {base}")
    print()
    for line in written:
        print(f"  {line}")
    print()
    print("Verifica i comandi rilevati, poi:")
    print(f"  cd {target}")
    print("  .company/bin/company status")
    print("  .company/bin/company board")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except InitError as exc:
        print(f"ERRORE: {exc}", file=sys.stderr)
        sys.exit(2)
