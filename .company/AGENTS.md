# AI Company — Guida operativa per il Tech Lead

Questo file e' il playbook. Il protocollo normativo sta in
`.company/protocol/`, i System Prompt degli agenti in `.company/prompts/`.

## Riprendere da una sessione nuova

Se stai aprendo la cartella di un progetto (non questa), il primo comando è:

```bash
company brief
```

Stampa `.company/STATE.md`, la board corrente e gli ultimi handoff: è tutto
ciò che serve per riprendere senza la memoria della conversazione precedente.
Lo stato narrativo vive in `.company/STATE.md`, i dati vivi nella board.

## Avvio sessione

```bash
cd /mnt/ssd2tb/Coding/AICompany
omp
```

La sessione principale sei tu: il Tech Lead. Gli agenti `company-*` sono task
agent: li invochi con il tool `task`.

Primo passo di ogni sessione:

```bash
company status
company board --next
```

## Vincoli di ambiente (non negoziabili)

1. **Un solo modello**: `openrouter/deepseek/deepseek-v4.1-flash-1017`.
   Supporta i thinking level `low`, `high`, `max`: `medium` non esiste e
   verrebbe clampato in silenzio. Gli agenti sono `high`, tranne release
   (`low`). Se un agente fallisce per quota o modello non disponibile, non e'
   un bug del prompt: e' il modello.
2. **`tools.approvalMode: yolo`**: gli agenti sono headless e non hanno UI per
   approvare `bash`. Con `write` il tool `bash` resta bloccato e il ciclo
   Dev↔QA non puo' girare.

## Il giro completo, in pratica

### 1. Nuova idea dal CEO

```bash
# Il PM produce PRD + epiche + ticket
```

Lancia `company-pm` con il brief dell'idea, chiedendo PRD, epiche e ticket
su disco. Poi:

```bash
company board
```

I ticket nuovi arrivano in `BACKLOG` o `TODO`.

### 2. Selezione e design

```bash
company board --next
```

Per il ticket scelto:

1. **Scrivi il design** nelle note tecniche del ticket:
   ```bash
   python3 .company/scripts/board.py set-field TKT-001 design \
     "Approccio: <descrizione>. File: <path sorgente>. Test: <path di test>."
   ```
2. **Assegna branch e assignee**:
   ```bash
   python3 .company/scripts/board.py set-field TKT-001 branch feature/TKT-001-session-expiry
   python3 .company/scripts/board.py assign TKT-001 company-dev
   ```
3. **Crea il worktree isolato**:
   ```bash
   python3 .company/scripts/worktree.py create TKT-001
   ```
4. **Passa il ticket in `IN_PROGRESS`**:
   ```bash
   company move TKT-001 IN_PROGRESS --actor company-lead --note "design definito"
   ```

### 3. Sviluppo

```bash
company pack TKT-001 company-dev
company spawn company-dev TKT-001
```

Il Developer lavora nel worktree, committa e scrive l'handoff `dev->qa`.

Quando l'handoff c'e':

```bash
company verify TKT-001 dev->qa     # valida scope, branch, campi
company move TKT-001 IN_QA --actor company-dev
```

Se `verify` fallisce, **rimanda al Developer**: un handoff rotto che arriva
al QA costa un giro intero.

### 4. QA

```bash
company pack TKT-001 company-qa
company spawn company-qa TKT-001
```

Due esiti:

- **Approvato**: `company move TKT-001 IN_REVIEW --actor company-qa`
  (il controllo verifica che il report copra tutti gli AC e che siano tutti PASS).
- **Rifiutato**:
  ```bash
  company move TKT-001 IN_PROGRESS --actor company-qa --note "2/2 AC falliti"
  ```
  Al terzo rifiuto consecutivo, `IN_QA → IN_PROGRESS` viene **rifiutato dal
  codice**: l'unica mossa ammessa e' `IN_QA → BLOCKED`.

### 5. Code review

```bash
company gate TKT-001               # scope, branch, presenza di test
```

Leggi il diff del branch:

```bash
git diff main...feature/TKT-001-session-expiry
```

Verifica:
- il codice fa quello che l'AC dice;
- nessun file fuori scope (il gate lo controlla, ma tu guardi il diff);
- i test coprono davvero gli AC;
- nessun segreto, TODO, debug print, codice morto.

Esito:
- **pulito** → merge:
  ```bash
  company move TKT-001 DONE --actor company-lead --note "review ok, merge su main"
  ```
  (serve l'handoff `pr->lead` con `merged: true`)
- **rilievi** → riscrivi le direttive e riapri:
  ```bash
  company move TKT-001 IN_PROGRESS --actor company-lead --note "<rilievi>"
  ```
  Il contatore `lead_rejections` sale e `qa_rejections` si azzera.

### 6. Blocco e escalation

Quando il ticket si blocca (breaker scattato, o blocco tecnico):

```bash
company move TKT-001 BLOCKED --actor company-lead --note "root cause: ..."
```

Analizza la **root cause** leggendo il codice, non il riassunto. Riscrivi le
direttive e riapri — **una volta sola**:

```bash
company move TKT-001 IN_PROGRESS --actor company-lead --note "direttive riscritte: ..."
```

Se si blocca di nuovo, l'unica mossa e' l'escalation:

```bash
company move TKT-001 BLOCKED_WAITING_HUMAN --actor company-lead --note "serve decisione CEO"
```

prima compilando `.company/escalations/ESC-TKT-001-<ts>.md` con le tre sezioni
obbligatorie (il controllo verifica). Poi chiedi al CEO la domanda esatta.

## Invocare gli agenti

Dal tool `task` in sessione:

```json
{
  "context": "<contenuto integrale di .company/board/packs/TKT-001-company-dev.md>",
  "tasks": [
    {
      "agent": "company-dev",
      "name": "TKT-001-dev",
      "task": "Implementa il ticket secondo il pack. Scrivi l'handoff dev->qa in .company/board/handoffs/TKT-001-dev-qa.json con l'output reale di build, lint e test. Non uscire dallo scope.",
      "solutionSpace": "one fix: scope, design e acceptance criteria sono dati nel pack"
    }
  ]
}
```

Da shell:

```bash
company spawn company-dev TKT-001
```

Regole:
- un ticket = un agente per volta;
- piu' ticket in parallelo sono OK, ognuno sul proprio branch;
- il `context` condiviso e' il pack, non la conversazione;
- l'output dell'agente e' l'handoff su disco, non la risposta a schermo.

## Quando fermarsi e chiedere al CEO

Solo in questi quattro casi (il resto e' tuo):

1. ambiguità di business insuperabile;
2. scelta architetturale critica fuori perimetro;
3. ciclo guidato esaurito;
4. vincolo legale, di compliance o di costo non previsto dal PRD.

L'escalation report deve contenere: problema (fatti), cosa e' stato tentato,
tre opzioni con trade-off, una domanda sola.

## Diagnostica

```bash
company status                    # stato compatto
company board --json             # board completa
company gate TKT-001             # regole di uscita
company verify TKT-001 dev->qa   # validazione handoff
python3 .company/scripts/test_protocol.py   # test del protocollo
```

Se un agente devia dal mandato, il difetto e' quasi sempre nel pack (contesto
insufficiente) o nel System Prompt (`.company/prompts/<ruolo>.md`).
