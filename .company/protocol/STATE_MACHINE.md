# Protocollo di Stato dei Ticket — AI Company

Fonte normativa: questo file. `scripts/board.py` ne implementa la macchina a stati;
in caso di disaccordo, vince il codice (che è testato) e va corretto questo documento.

## 1. Stati

| Stato | Significato | Chi può farci entrare |
|---|---|---|
| `BACKLOG` | Ticket formalizzato dal PM, non ancora pronto per il team tecnico | PM |
| `TODO` | Ticket pronto: requisiti, AC e priorità definiti | PM, Tech Lead |
| `IN_PROGRESS` | Uno sviluppatore sta lavorando sul ticket | Tech Lead (assegnazione) |
| `IN_QA` | Il codice è committato e in attesa di verifica | Developer |
| `IN_REVIEW` | QA ha approvato: il Tech Lead deve fare la code review | QA |
| `DONE` | Review superata, merge su `main` effettuato | Tech Lead |
| `BLOCKED` | Blocco tecnico: il Tech Lead deve intervenire | Tech Lead, QA (breaker), Developer |
| `BLOCKED_WAITING_HUMAN` | Blocco non risolvibile dal Tech Lead: attende il CEO | Tech Lead |

## 2. Transizioni ammesse

| Da | A | Attore | Condizione |
|---|---|---|---|
| `BACKLOG` | `TODO` | PM, Lead | PRD linkato, AC scritti, priorità assegnata |
| `TODO` | `IN_PROGRESS` | Lead | Design del Lead scritto, branch creato, Developer assegnato |
| `TODO` | `BACKLOG` | Lead | Ticket incompleto o fuori priorità |
| `IN_PROGRESS` | `IN_QA` | Dev | Handoff `dev->qa` presente e validato, commit pushato |
| `IN_QA` | `IN_REVIEW` | QA | Tutti gli AC verificati, suite verde, `qa_report` approvato |
| `IN_QA` | `IN_PROGRESS` | QA | Test falliti **e** i rientri al Dev sono meno di `max_qa_retries - 1` (2) |
| `IN_QA` | `BLOCKED` | QA, Lead | Il rifiuto n. 3 (breaker) oppure blocco tecnico non risolvibile dal Dev |
| `IN_REVIEW` | `DONE` | Lead | Code review superata, merge su `main` eseguito, PR chiusa |
| `IN_REVIEW` | `IN_PROGRESS` | Lead | Review con rilievi: il Lead riscrive le direttive e riapre il ciclo |
| `IN_REVIEW` | `BLOCKED` | Lead | Review con difetto architetturale che richiede decisione |
| `BLOCKED` | `IN_PROGRESS` | Lead | Root cause analizzata e direttive riscritte; **solo** se `lead_cycles <= max_lead_cycles` (1) |
| `BLOCKED` | `BLOCKED_WAITING_HUMAN` | Lead | Ciclo guidato fallito, oppure decisione fuori mandato |
| `BLOCKED_WAITING_HUMAN` | `IN_PROGRESS` | Lead | Il CEO ha deciso; il Lead applica la decisione e riparte |
| `BLOCKED_WAITING_HUMAN` | `BACKLOG` | Lead | Il CEO ha deciso di accantonare il ticket |
| `IN_QA` | `BLOCKED_WAITING_HUMAN` | Lead | Requisito ambiguo o scelta architetturale fuori perimetro: scala diretta |

## 3. Circuit Breaker

La regola è ferrea e applicata da `board.py`, non a parole.

- `qa_rejections` conta i **rientri al Developer** dopo un rifiuto di QA sul
  ciclo di correzione Dev↔QA.
- `max_qa_retries = 3` è il numero massimo di **rifiuti consecutivi** di QA.
  Con `max_qa_retries = 3` il Developer ha **2 round di correzione**: il
  terzo rifiuto non torna al Developer, finisce dal Tech Lead.
- La transizione `IN_QA → IN_PROGRESS` è **rifiutata dal codice** quando
  `qa_rejections >= max_qa_retries - 1`: l'unica mossa ammessa è
  `IN_QA → BLOCKED`.
- Il passaggio a `BLOCKED` **conserva** `qa_rejections`: è l'evidenza di quanti
  cicli sono falliti. Il contatore si azzera solo quando le direttive cambiano
  (`BLOCKED → IN_PROGRESS`) o quando QA approva (`→ IN_REVIEW`).
- Il Tech Lead può riaprire `BLOCKED → IN_PROGRESS` **al massimo
  `max_lead_cycles = 1` volta** (un ciclo guidato). Se il ticket si blocca di
  nuovo, l'unica mossa ammessa è `BLOCKED → BLOCKED_WAITING_HUMAN`.
- `IN_REVIEW → IN_PROGRESS` (rilievi di review) azzera `qa_rejections` e
  incrementa `lead_rejections`: la direttiva è cambiata, il ciclo ricomincia
  legittimamente.
- `BLOCKED_WAITING_HUMAN → IN_PROGRESS` azzera tutti i contatori: la decisione
  del CEO è il punto di reset.
- Un ticket in `DONE` non può più essere riapto: si crea un ticket nuovo.

```
BACKLOG → TODO → IN_PROGRESS → IN_QA ──(QA ok)──→ IN_REVIEW → DONE
                        ↑            │                              │
                        │            └──(QA ko, x3)──→ BLOCKED ←───┘
                        │                                  │
                        └────(Lead, 1 ciclo)───────────────┤
                                                           ↓
                                            BLOCKED_WAITING_HUMAN → (CEO)
```

## 4. Chi può scrivere il board

**Solo il Tech Lead o il runner `company run` (dopo validazione di `guard.py`).**
Gli agenti non scrivono mai lo stato del ticket: producono artefatti in
`.company/board/handoffs/` e il runner valida e avanza il board. Questo rende
la macchina a stati autoritativa e rende ogni passaggio tracciabile a un
artefatto.

## 5. Campi obbligatori del ticket

| Campo | Tipo | Note |
|---|---|---|
| `id` | string | `TKT-NNN`, mai riutilizzato |
| `title` | string | una riga, verbo all'infinito |
| `type` | enum | `feature` \| `bug` \| `chore` \| `spike` |
| `epic` | string | `EPIC-NN` o `-` |
| `status` | enum | uno degli stati sopra |
| `priority` | enum | `P0` \| `P1` \| `P2` \| `P3` |
| `spec` | string | path del PRD o dell'epica di riferimento |
| `acceptance_criteria` | array | oggetti `{id, text, verified}`; `verified` è impostato solo da QA |
| `assignee` | string | `company-dev` \| `company-qa` \| `company-lead` |
| `branch` | string | `feature/<TICKET>-<slug>`; obbligatorio da `IN_PROGRESS` |
| `attempts` | object | `{qa_rejections, lead_cycles, lead_rejections}` |
| `touches` | array | path attesi dal Developer (usato dal guard di scope) |
| `history` | array | `{ts, from, to, actor, note}` — log append-only |

## 6. Invarianti verificate dal codice

1. Nessuna transizione fuori dalla tabella §2.
2. Nessuna transizione che violi il breaker §3.
3. `IN_QA` richiede `branch` valorizzato e un handoff `dev->qa` validato.
4. `IN_REVIEW` richiede un `qa_report` con verdetto `APPROVED`.
5. `DONE` richiede merge su `main` confermato (flag `merged` sull'handoff PR).
6. `BLOCKED_WAITING_HUMAN` richiede un `escalation_report` compilato con le
   tre sezioni obbligatorie (problema, opzioni, domanda).
7. Il conteggio di `history` non può diminuire.
