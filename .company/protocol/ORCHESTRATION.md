# Orchestrazione — AI Company

## Due modalità operative

| Modalità | Chi guida | Quando | Come |
|---|---|---|---|
| **Supervised** (default) | Tech Lead in sessione interattiva | sviluppo normale | `omp` → il Lead usa `task` per invocare i sub-agenti |
| **Headless** | runner `company run` | batch, CI, produzione | `bin/company run <TICKET>` |

La modalità supervised è quella con cui operi a regime: il Tech Lead **è** la
sessione principale di OMP, e `company-lead` esiste come definizione di ruolo
riutilizzabile per le sessioni headless e per il sub-agent di review.

## Flusso supervised (il giro completo)

```
CEO ──idea──→ PM ──PRD+epiche+ticket──→ [board]
                                             │
                        Lead: pack.py → context pack + branch
                                             │
                        ┌────────────────────┴────────────────────┐
                        │                                         │
                    DEV (isolated)                            (altri ticket paralleli)
                        │
                   build/lint/test ok
                        │
                        │ handoff dev->qa
                        ▼
                    QA (isolated, stesso branch)
                        │
              ┌─────────┴─────────┐
        tests falliti          tests ok
              │                   │
   handoff qa->dev      handoff qa->lead (APPROVED)
   (qa_rejections++)              │
        (se == 3 → BLOCKED)      ▼
                        Lead: code review
                        ┌───────┴────────┐
                    rilievi           ok
                        │               │
              riscrive direttive    handoff dev->lead + merge
              → IN_PROGRESS              │
                                        ▼
                                     DONE ──→ Release (tag/changelog)
```

## Flusso headless (`bin/company`)

```bash
company board                      # stato della board
company pack TKT-001 company-dev   # genera il context pack
company spawn company-dev TKT-001  # lancia l'agente con il pack come input
company verify TKT-001             # valida gli handoff e avanza il board
company gate TKT-001               # regole di uscita (scope, test, branch)
```

Il runner non decide: valida. Le decisioni restano al Tech Lead e agli agenti,
ma ogni scelta lascia un artefatto validabile.

## Isolamento del Developer

Ogni Developer lavora su un branch dedicato con worktree:

```
.company/worktrees/TKT-001-user-login/   ← checkout isolato
branch: feature/TKT-001-user-login
base:  main
```

`guard.py` verifica, prima di accettare un handoff:
- il branch è `feature/<TICKET>-<slug>` e non è `main`/`master`/`release`;
- i file modificati sono dentro `touches` del ticket;
- i test esistono e vengono eseguiti.

`company gate` fallisce con codice di uscita non zero se una regola è violata:
il runner non porta avanti un ticket non conforme.

## Parallelismo

Più ticket possono essere in lavorazione contemporaneamente, ognuno su un
proprio worktree. Limite configurabile: `task.maxConcurrency` in
`.omp/config.yml` (3, allineato al limite del provider). **Un ticket = un
agente per volta**: mai due Developer sullo stesso ticket.

## Memoria pulita

Il principio operativo: **l'agente riceve un context pack, non la
conversazione**.

- Il Lead genera il pack con `pack.py` → file Markdown con ticket, AC, design,
  scope, errori noti, tentativi precedenti.
- Il pack è l'unico input dell'agente.
- L'output dell'agente è un handoff JSON su disco.
- Il Lead legge l'handoff, non la trascrizione.

Questo rende ogni esecuzione riproducibile, ispezionabile e indipendente dalla
dimensione della conversazione.

## Ruolo del Tech Lead a regime

Il Lead è sempre l'attore che:

1. legge il board e sceglie il ticket successivo;
2. scrive il design e genera il pack;
3. invoca Dev e QA;
4. fa la code review;
5. decide il merge e lo stato `DONE`;
6. applica il breaker e riscrive le direttive quando il ciclo si blocca;
7. scala al CEO solo nei quattro casi definiti in `ESCALATION.md`.

## Quando fermarsi e chiedere

Il Lead si ferma e chiede al CEO **prima** di:
- merge su `main` (default: sì, lo fa lui; il CEO può vietarlo con
  `--no-merge`),
- adottare una dipendenza nuova non prevista dal PRD,
- modificare i requisiti di un ticket già in lavorazione,
- cambiare il contratto di un'API pubblica.

In tutti gli altri casi decide da solo. Un Lead che chiede troppo è un Lead
che non scala: è un costo, non una garanzia.
