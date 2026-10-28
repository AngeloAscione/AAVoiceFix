# AI Company — contesto di progetto

Questo repository contiene una **squadra multi-agente** per lo sviluppo
software automatizzato. La sessione principale di OMP è il **Tech Lead**.

## Stato corrente (importato automaticamente)

Il contenuto di `.company/STATE.md` è incluso qui sotto tramite
`@import`: non serve leggerlo separatamente.

@../.company/STATE.md

## Regola operativa principale

Gli agenti non si parlano: si scambiano **artefatti su disco** in
`.company/board/handoffs/`, e ogni passaggio è validato da codice
(`.company/scripts/guard.py`, `.company/scripts/board.py`).

**Prima di ogni azione, leggi `.company/STATE.md`** (stato corrente, decisioni
aperte, prossimo passo) e **`.company/AGENTS.md`** — è il playbook operativo
completo: come assegnare un ticket, come invocare gli agenti, quando
escalare al CEO.

## Riferimenti

| Cosa | Dove |
|---|---|
| Stato corrente del progetto | `.company/STATE.md` |
| Playbook operativo | `.company/AGENTS.md` |
| Stati, transizioni, circuit breaker | `.company/protocol/STATE_MACHINE.md` |
| Schema degli handoff e dei pack | `.company/protocol/CONTRACTS.md` |
| Criteri e formato dell'escalation | `.company/protocol/ESCALATION.md` |
| Come si guida il ciclo | `.company/protocol/ORCHESTRATION.md` |
| System Prompt degli agenti | `.company/prompts/*.md` |
| Roster e policy | `.company/manifest.json` |
| CLI | `.company/bin/company` |

## Comandi frequenti

```bash
company status
company board --next
company pack TKT-001 company-dev
company spawn company-dev TKT-001
company verify TKT-001 dev->qa
company gate TKT-001
python3 .company/scripts/test_protocol.py
```

## Vincoli

- Gli agenti `company-*` scrivono solo dove il loro System Prompt e il
  protocollo li autorizzano. `.omp/agents/*.md` è **generato**: si edita
  `.company/prompts/*.md` e si esegue `company sync`.
- Il Tech Lead non scrive il codice applicativo dei ticket che supervisiona.
- Il Tech Lead scala al CEO solo nei quattro casi di
  `.company/protocol/ESCALATION.md`: tutto il resto è una sua decisione.
