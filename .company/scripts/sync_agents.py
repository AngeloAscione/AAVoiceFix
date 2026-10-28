#!/usr/bin/env python3
"""Genera le definizioni degli agenti OMP a partire dal manifest.

Il manifest (.company/manifest.json) e' la fonte unica del roster: nome,
descrizione, modello, tool consentiti, ruolo. I system prompt vivono in
.company/prompts/*.md. Questo script compone i due e produce i file che OMP
scopre come task agent in .omp/agents/.

I file generati sono derivati: si rigenerano con `company sync` e non vanno
editati a mano. Per cambiare un prompt si modifica .company/prompts/<ruolo>.md.

Uso:
    sync_agents.py [--check] [--output-dir .omp/agents]
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
COMPANY_DIR = REPO_ROOT / ".company"
MANIFEST = COMPANY_DIR / "manifest.json"
DEFAULT_OUTPUT = REPO_ROOT / ".omp" / "agents"

# Ruoli ammessi come agenti OMP: 'main' e 'sub' sono riservati.
RESERVED_NAMES = {"main", "sub"}


def yaml_scalar(value: str) -> str:
    """Serializza una stringa YAML in modo sicuro."""
    if value == "":
        return '""'
    if any(ch in value for ch in ':#{}[]&*!|>%@`"\'\n') or value.strip() != value:
        return json.dumps(value, ensure_ascii=False)
    return value


def yaml_list(values: list[str], indent: str = "  ") -> str:
    if not values:
        return "[]"
    return "\n".join(f"{indent}- {yaml_scalar(v)}" for v in values)


def render_agent(agent: dict, prompt_body: str) -> str:
    name = agent["name"]
    parts: list[str] = []
    parts.append("---")
    parts.append(f"name: {name}")
    parts.append(f"description: {yaml_scalar(agent['description'])}")
    parts.append("tools:")
    parts.append(yaml_list(agent.get("tools", [])))
    spawns = agent.get("spawns") or []
    if spawns:
        parts.append("spawns:")
        parts.append(yaml_list(spawns))
    models = agent.get("model") or []
    if models:
        parts.append("model:")
        parts.append(yaml_list(models))
    if agent.get("thinking_level"):
        parts.append(f"thinking-level: {agent['thinking_level']}")
    parts.append("---")
    parts.append("")
    parts.append(prompt_body.rstrip())
    parts.append("")
    return "\n".join(parts)


def build(output_dir: Path, check: bool) -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    agents = manifest["agents"]

    seen: set[str] = set()
    errors: list[str] = []
    for agent in agents:
        name = agent["name"].lower()
        if name in RESERVED_NAMES:
            errors.append(f"nome riservato: {agent['name']}")
        if name in seen:
            errors.append(f"nome duplicato: {agent['name']}")
        seen.add(name)
        prompt_path = COMPANY_DIR / agent["prompt"]
        if not prompt_path.exists():
            errors.append(f"prompt mancante: {agent['prompt']}")
        if not agent.get("description"):
            errors.append(f"descrizione mancante: {agent['name']}")

    if errors:
        for err in errors:
            print(f"ERRORE: {err}", file=sys.stderr)
        return 2

    output_dir.mkdir(parents=True, exist_ok=True)
    changed: list[str] = []
    for agent in agents:
        prompt_body = (COMPANY_DIR / agent["prompt"]).read_text(encoding="utf-8")
        content = render_agent(agent, prompt_body)
        target = output_dir / f"{agent['name']}.md"
        previous = target.read_text(encoding="utf-8") if target.exists() else None
        if previous == content:
            continue
        changed.append(str(target.relative_to(REPO_ROOT)))
        if not check:
            target.write_text(content, encoding="utf-8")

    if check:
        if changed:
            print("OUTDATED:")
            for path in changed:
                print(f"  {path}")
            print("Esegui `company sync` per rigenerare.")
            return 1
        print("agent definitions up to date")
        return 0

    for path in changed:
        print(f"generato {path}")
    if not changed:
        print("nessuna modifica: agent definitions up to date")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Sincronizza le definizioni degli agenti")
    parser.add_argument("--check", action="store_true", help="verifica senza scrivere")
    parser.add_argument("--output-dir", default=str(DEFAULT_OUTPUT))
    args = parser.parse_args(argv)
    out = Path(args.output_dir)
    if not out.is_absolute():
        out = REPO_ROOT / out
    return build(out, args.check)


if __name__ == "__main__":
    sys.exit(main())
