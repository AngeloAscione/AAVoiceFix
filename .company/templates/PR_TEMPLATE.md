# PR — <TKT-NNN> <Titolo>

| Campo | Valore |
|---|---|
| Ticket | TKT-NNN |
| Branch | feature/TKT-NNN-<slug> |
| Base | main |
| Autore | company-dev |
| Stato | open \| approved \| merged \| rejected |

## Cosa cambia

<2-5 righe. La modifica dal punto di vista del comportamento osservabile,
non del diff. Se un file è stato spostato, dirlo qui.>

## Ticket collegati

- TKT-NNN

## Acceptance Criteria coperti

| ID | Criterio | Implementato in |
|---|---|---|
| AC-1 | <criterio> | `src/foo.ts:42` |

## Verifiche eseguite

| Comando | Esito |
|---|---|
| `<build>` | ok / failed |
| `<lint>` | ok / failed |
| `<test>` | <N passed, M failed> |

> Incollare l'output dei test qui se ci sono failure. Un PR con test rotti non
> viene mandato in review.

## Note per il reviewer

<Punti su cui il reviewer deve guardare con attenzione: scelte non ovvie,
trade-off, zone in cui il Developer sa che il codice è fragile.>

## Checklist

- [ ] Il branch parte da `main` aggiornato
- [ ] Nessun file fuori dallo scope del ticket
- [ ] Nessun segreto, credenziale o dato di test reale nel diff
- [ ] Nessun `TODO` / `FIXME` lasciato
- [ ] Build e lint verdi
- [ ] Test nuovi o aggiornati per ogni AC coperto
