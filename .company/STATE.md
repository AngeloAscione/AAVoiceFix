# Stato del progetto — AndroidAutoWaFixer

> Aggiornato: 2026-10-28. **Questo è il primo file da leggere in una
> sessione nuova.** Descrive dove siamo, cosa è aperto e cosa serve per
> ripartire. Va aggiornato dal Tech Lead alla fine di ogni ciclo significativo.

## Dove siamo

Squadra AI Company installata sul repository. Il progetto applicativo Android
**non esiste ancora**: la directory era vuota, ora contiene solo il protocollo
(`.company/`, `.omp/`) e il `.gitignore`. Nessuna epica in corso, board vuota.

## Board

Nessun ticket.

## Cosa è stato consegnato

- Protocollo AI Company — roster `company-pm`, `company-lead`, `company-dev`,
  `company-qa`, `company-release`, prompt, board, script, CLI `company`.
- Definizioni agenti generate in `.omp/agents/` (`company sync --check` → ok).
- Configurazione OMP di progetto — `modelRoles.company_*`, concorrenza 3,
  `approvalMode: yolo`.

**Verifiche**

| Prova | Esito |
|---|---|
| `python3 .company/scripts/test_protocol.py` | 142 passati, 0 falliti |
| `company status` / `company board` | ok, board vuota |
| `company sync --check` | agent definitions up to date |
| `./gradlew ...` (build/lint/test) | non eseguibile: wrapper assente |

## Difetti noti

Nessuno noto nel protocollo. Vincolo di setup: `project.*_command` puntano al
wrapper Gradle, che non è ancora presente.

## Decisioni aperte (spettano al CEO)

| ID | Decisione | Stato |
|---|---|---|
| D1 | Requisiti e scope di AndroidAutoWaFixer (prima idea per il PM) | aperta |
| D2 | Scaffold Android: package name, minSdk/targetSdk, modulo unico o multi-modulo | aperta |

## Ambiente: le trappole

- Il repository è stato inizializzato con `git init -b main`: il branch base è
  `main` (vedi `policy.base_branch` in `.company/manifest.json`).
- `company init --detect .` fallisce finché non esiste un file di stack
  (`gradlew` + `app/build.gradle.kts`): è il motivo per cui lo stack `android`
  è stato impostato a mano. Rilancialo dopo lo scaffold e confronta i comandi.
- I comandi `./gradlew :app:*` falliscono senza wrapper: non sono verificati.
- `.company/board/*` e `.company/worktrees/*` sono runtime e ignored (i
  `.gitkeep` restano tracciati).
- Gli agenti sono headless: `tools.approvalMode: yolo` in `.omp/config.yml`
  è obbligatorio, altrimenti `bash` resta bloccato e il ciclo Dev↔QA non gira.

## Prossimo passo

Il CEO fornisce la prima idea; il Tech Lead la passa a `company-pm` per PRD,
epiche e ticket in `.company/board/tickets/`; poi il primo ticket crea lo
scheletro Android (wrapper Gradle incluso) così i comandi del manifest
diventano eseguibili e verificabili.

## Come riprendere in una sessione nuova

```bash
cd /mnt/ssd2tb/Coding/AndroidAutoWaFixer
omp
```

La sessione che si apre lì è il Tech Lead di questo progetto: trova gli agenti
in `.omp/agents/`, la board in `.company/board/`, e legge questo file come
contesto. Non serve nessun passaggio di consegne: tutto ciò che conta è su
disco.
