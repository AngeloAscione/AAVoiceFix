# TKT-NNN — <Titolo, verbo all'infinito>

| Campo | Valore |
|---|---|
| ID | TKT-NNN |
| Tipo | feature \| bug \| chore \| spike |
| Epica | EPIC-NN |
| Stato | BACKLOG |
| Priorità | P0 \| P1 \| P2 \| P3 |
| Assegnatario | — |
| Branch | — |
| Spec | `.company/specs/PRD-<slug>.md` §<sezione> |

## Contesto

<2-4 righe: perché questo ticket esiste, da quale requisito deriva.
Riferimento: FR-3, NFR-1.>

## Requisito specifico

<Descrizione del comportamento atteso, in termini osservabili.>

## Acceptance Criteria

Ogni AC deve essere **verificabile da un test o da un'ispezione inequivocabile**.
Un AC che inizia con "Il sistema deve..." e non dice come si verifica non è un AC.

| ID | Criterio | Come si verifica |
|---|---|---|
| AC-1 | <comportamento osservabile> | `test/unit/foo.test.ts::<nome test>` |
| AC-2 | <comportamento osservabile> | <ispezione o comando> |
| AC-3 | <comportamento osservabile> | <test> |

## Scope

### File attesi (`touches`)

- `src/<path>`
- `tests/<path>`

> Il guard rifiuta l'handoff se i file modificati escono da questo elenco.

### Fuori scope

- <cosa NON deve essere toccato in questo ticket>

## Dipendenze

- **Bloccato da:** <TKT-NNN | EPIC-NN | —>
- **Blocca:** <TKT-NNN | EPIC-NN | —>

## Note tecniche (compilate dal Tech Lead)

<Design: approccio, file da creare/modificare, edge case, strategia di test.
Scritto dal Tech Lead, non dal Developer.>

## Cronologia

| Data | Da | A | Attore | Nota |
|---|---|---|---|---|
| — | — | BACKLOG | company-pm | creato |
