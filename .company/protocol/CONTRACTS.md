# Contratti di Handoff — AI Company

Ogni passaggio tra agenti è un **artefatto su disco**, non una conversazione.
Il file è la fonte di verità; la memoria di lavoro dell'agente non lo è.

## Percorsi

| Artefatto | Path | Scritto da | Letto da |
|---|---|---|---|
| PRD | `.company/specs/PRD-<slug>.md` | PM | Lead, Dev, QA |
| Epica | `.company/specs/EPIC-<NN>-<slug>.md` | PM | Lead, Dev, QA |
| Ticket | `.company/board/tickets/<TICKET>.json` | PM (crea), Lead (aggiorna) | tutti |
| Handoff | `.company/board/handoffs/<TICKET>-<from>-<to>.json` | agente mittente | agente ricevente, Lead |
| Context pack | `.company/board/packs/<TICKET>-<ruolo>.md` | Lead (via `pack.py`) | agente destinatario |
| QA report | `.company/board/handoffs/<TICKET>-qa-report.json` | QA | Lead, Dev |
| PR | `.company/board/handoffs/<TICKET>-pr.json` | Dev | Lead, QA |
| Escalation | `.company/escalations/ESC-<TICKET>-<ts>.md` | Lead | CEO |

## Schema dell'handoff (JSON)

```jsonc
{
  "ticket": "TKT-001",
  "from": "company-dev",
  "to": "company-qa",
  "kind": "dev->qa",
  "created_at": "2026-10-21T10:00:00Z",
  "branch": "feature/TKT-001-user-login",
  "commits": ["a1b2c3d"],
  "summary": "Implementato il login con sessione e redirect su 401",
  "changed_files": ["src/<modulo>/<file>.ts"],
  "artifacts": {
    "build": "ok",
    "lint": "ok",
    "tests_local": "12 passed, 0 failed",
    "pr_url": "https://github.com/org/repo/pull/42"
  },
  "questions": [],
  "next_action": "qa: eseguire la suite e verificare gli AC del ticket"
}
```

Campi obbligatori: `ticket`, `from`, `to`, `kind`, `created_at`, `summary`.
Campi obbligatori per `kind` specifici:

| kind | campi extra obbligatori |
|---|---|
| `dev->qa` | `branch`, `commits` (non vuoto), `changed_files` (non vuoto) |
| `qa->dev` | `failed_tests` (non vuoto), `logs` (non vuoto) |
| `qa->lead` | `verdict`, `report_path` |
| `dev->lead` | `branch`, `commits`, `pr_url` |
| `pr->lead` | `pr_url`, `diff_summary`, `review_findings` |

## Handoff `dev->qa`

Il Developer **non** scrive lo stato del ticket. Scrive l'handoff. Il runner
(`company run`) valida e muove il ticket in `IN_QA`.

Contenuto minimo:
- branch e commit esatti (verificabili con `git rev-parse`);
- elenco dei file modificati;
- esito di build/lint/test locali, con il comando usato;
- cosa il QA deve guardare in particolare (i punti che il Dev sa essere
  rischiosi, non i test banali).

## Handoff `qa->dev` (rifiuto)

Contiene:
- `verdict: "REJECTED"`;
- per ogni AC fallito: id dell'AC, esito atteso, esito reale;
- **output reale del test runner** (stack trace incluso), non una descrizione;
- il comando di riproduzione esatto;
- se il fallimento è di test e non di codice: `test_bug: true` — in quel caso
  l'AC va rivisto dal Lead, non corretto alla cieca dal Dev.

## QA report

```jsonc
{
  "ticket": "TKT-001",
  "verdict": "APPROVED" | "REJECTED",
  "criteria": [
    {"id": "AC-1", "text": "...", "result": "PASS" | "FAIL", "evidence": "test: <file di test>::<nome test>"}
  ],
  "tests_run": "<comando di test> — 34 passed, 0 failed",
  "regressions": [],
  "notes": ""
}
```

`verdict: APPROVED` richiede che **tutti** gli AC siano `PASS` e che la suite
non abbia regressioni. Un singolo `FAIL` ⇒ `REJECTED`.

## Context pack

Il Lead non passa mai "tutto il contesto". Per ogni assegnazione genera un
context pack con `pack.py`: il minimo necessario (ticket, AC, design, file
attesi, stato dei tentativi precedenti, errori noti). Questo mantiene la
memoria dell'agente pulita e rende l'input riproducibile e ispezionabile.

## Criteri di validazione (imposti da `guard.py`)

1. L'handoff esiste ed è JSON valido.
2. `ticket` e `kind` corrispondono alla transizione richiesta.
3. I campi obbligatori per quel `kind` sono presenti e non vuoti.
4. Il branch dichiarato esiste ed è diverso da `main`.
5. I commit dichiarati esistono nel repository.
6. Nel caso di `dev->qa`: i file modificati sono dentro `touches` del ticket
   (scope creep → rifiuto).
7. Nel caso di `qa->lead` con `verdict: APPROVED`: tutti gli AC sono `PASS`.
8. Nel caso di `dev->lead`: esiste una PR o un commit pronto per review.
