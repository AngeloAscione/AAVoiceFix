# QA Report — <TKT-NNN> <Titolo>

| Campo | Valore |
|---|---|
| Ticket | TKT-NNN |
| Branch testata | feature/TKT-NNN-<slug> |
| Commit | <sha> |
| Verdetto | APPROVED \| REJECTED |
| Data | <YYYY-MM-DD> |

## 1. Acceptance Criteria

Tutti gli AC del ticket devono comparire. Un AC non elencato è un AC non
verificato ⇒ verdetto `REJECTED`.

| ID | Criterio | Esito | Evidenza |
|---|---|---|---|
| AC-1 | <criterio> | PASS \| FAIL | `tests/unit/foo.test.ts::testName` |
| AC-2 | <criterio> | PASS \| FAIL | <comando + output> |

## 2. Test eseguiti

| Suite | Comando | Esito | Totale |
|---|---|---|---|
| Unit | `<cmd>` | ok | 12 passed, 0 failed |
| Integration | `<cmd>` | ok | 3 passed, 0 failed |
| E2E | `<cmd>` | — | non esiste in questo progetto |

Output relevante (incollare qui se ci sono failure):

```
<output reale del test runner, stack trace incluso>
```

## 3. Regressioni

| Area | Esito | Note |
|---|---|---|
| <area toccata dal ticket> | ok \| FAIL | |

## 4. Copertura

- **AC coperti da test automatici:** <N>/<M>
- **AC verificati solo per ispezione:** <elenco e perché>

## 5. Qualità del codice (note, non bloccanti)

- <osservazioni su leggibilità, naming, duplicazioni — stile `nit`, non blocker>

## 6. Verdetto e motivazione

- `APPROVED`: tutti gli AC `PASS`, nessuna regressione, suite verde.
- `REJECTED`: almeno un AC `FAIL` o una regressione. Elencare **tutti** i
  fallimenti con il comando di riproduzione.

> Il QA non modifica il codice applicativo. Il rifiuto torna al Developer con
> l'output reale del test, non con una descrizione del problema.
