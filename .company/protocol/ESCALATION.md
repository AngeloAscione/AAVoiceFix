# Protocollo di Escalation — AI Company

## Principio

Il Tech Lead è un **filtro**, non un collo di bottiglia. L'obiettivo è che il CEO
(umano) veda solo ciò che richiede una decisione di business o una scelta
architetturale fuori mandato. Tutto il resto si risolve a livello tecnico.

## Livelli di escalation

| Livello | Da | A | Trigger | Output |
|---|---|---|---|---|
| L0 | Dev | Dev | errore di compilazione/lint | fix locale, nessuna notifica |
| L1 | Dev | QA | codice pronto, build e lint verdi | handoff `dev->qa` |
| L2 | QA | Dev | test falliti, rientri al Dev disponibili | handoff `qa->dev` con log |
| L2b | QA | Lead | test falliti per la terza volta consecutiva | ticket in `BLOCKED` |
| L3 | Lead | Dev/QA | root cause identificata | direttive tecniche riscritte, ticket riapre |
| L4 | Lead | CEO | ciclo guidato esaurito **oppure** decisione fuori mandato | `BLOCKED_WAITING_HUMAN` + escalation report |
| L5 | CEO | Lead | decisione presa | il Lead applica e riparte |

## Quando il Tech Lead DEVE escalare (L4)

Scalare **solo** in questi casi:

1. **Ambiguità di business insuperabile.** Il requisito richiede una decisione
   che cambia il comportamento per l'utente finale e le opzioni tecniche portano
   a esiti di prodotto diversi. Esempio: "quando l'utente clicca due volte deve
   annullare o applicare?".
2. **Scelta architetturale critica fuori perimetro.** Esempio: adottare un
   database distribuito quando il progetto oggi usa SQLite; cambiare il
   linguaggio; introdurre una dipendenza che vincola la licenza.
3. **Il ciclo guidato non ha risolto.** `lead_cycles` ha raggiunto
   `max_lead_cycles` (1) e il ticket si è bloccato di nuovo.
4. **Vincolo legale, di compliance o di costo non previsto dal PRD.** Budget
   API, licenze, GDPR, retention dati.

Il Tech Lead **NON** escala per:

- un test che fallisce: è lavoro del Developer;
- un refactor che non piace: decide lui, in review;
- un bug chiaro con fix ovvio: decide lui;
- un ritardo: continua a lavorare e scala solo se lo stoppage è totale;
- un dettaglio di design: decide lui.

## Formato dell'Escalation Report

File: `.company/escalations/ESC-<TICKET>-<timestamp>.md`.
Il Tech Lead deve compilarlo **integralmente**: se una delle tre sezioni manca,
il ticket non può entrare in `BLOCKED_WAITING_HUMAN` (validato da `guard.py`).

```markdown
# Escalation — <TICKET-ID> — <titolo breve>

## 1. Problema
<2-6 righe: cosa non funziona o cosa deve essere deciso. Fatti, non opinioni.>
<Se tecnico: stack trace o output reale, non parafrasi.>

## 2. Cosa è già stato tentato
1. <tentativo> — <esito>
2. <tentativo> — <esito>
3. <tentativo> — <esito>
<Citare i tentativi del ciclo Dev↔QA e il ciclo guidato del Lead.>

## 3. Opzioni
### Opzione A — <nome> (consigliata)
- **Cosa cambia:** <descrizione>
- **Impatto:** <tempo, rischio, manutenzione>
- **Costo:** <stima>
- **Rischio principale:** <cosa può andare storto>

### Opzione B — <nome>
- **Cosa cambia:** <descrizione>
- **Impatto:** <tempo, rischio, manutenzione>
- **Costo:** <stima>
- **Rischio principale:** <cosa può andare storto>

### Opzione C — <nome>
- **Cosa cambia:** <descrizione>
- **Impatto:** <tempo, rischio, manutenzione>
- **Costo:** <stima>
- **Rischio principale:** <cosa può andare storto>

## 4. Domanda al CEO
<Una domanda, formulata come scelta binaria o trivariata, senza gergo.
Esempio: "Approvi l'Opzione A (Opzione B raddoppia il tempo di rilascio,
Opzione C blocca la funzionalità in-app)?">

## 5. Contesto
- Ticket: <TICKET-ID>
- PRD: <path>
- Branch: <branch>
- Commit: <sha>
- Impatto se non si decide: <cosa si ferma>
```

## Regole di stile dell'escalation

- **Fatto, non interpretazione.** "Il test `test_login_expired` fallisce con
  `AssertionError: expected 200, got 401`", non "sembra che il login non
  funzioni".
- **Opzioni concrete e distinte.** Tre opzioni massimo. Se due opzioni sono la
  stessa, eliminarne una.
- **Una domanda sola.** Il CEO deve poter rispondere in una riga.
- **Nessun ultimatum.** Se il ticket può aspettare, scrivere "impatto se non si
  decide" e non "bloccato" come se fosse urgente.
- **Nessuna colpa.** Nomi di file e versioni, non "il team ha sbagliato".

## Ritorno dal CEO

Quando il CEO risponde, il Tech Lead:

1. registra la decisione in `.company/board/decisions/DEC-<TICKET>-<ts>.md`;
2. esegue `board.py move <TICKET> IN_PROGRESS --actor company-lead
   --note "decisione CEO: <sintesi>"` (azzera i contatori);
3. riapre il ciclo con la direttiva esplicita della decisione, non con
   l'interpretazione del Lead.
