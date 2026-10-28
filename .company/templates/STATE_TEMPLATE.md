# Stato del progetto — <NOME>

> Aggiornato: <AAAA-MM-GG>. **Questo è il primo file da leggere in una
> sessione nuova.** Descrive dove siamo, cosa è aperto e cosa serve per
> ripartire. Va aggiornato dal Tech Lead alla fine di ogni ciclo significativo.

## Dove siamo

<2-4 righe: a che punto è il prodotto, quale epica è in corso, cosa manca per
chiuderla.>

## Board

| ID | Stato | Titolo |
|---|---|---|
| TKT-001 | | |

## Cosa è stato consegnato

- <componente> — <cosa fa>
- <componente> — <cosa fa>

**Verifiche**

| Prova | Esito |
|---|---|
| Test automatici | <N>, <falliti> |
| Build e lint | |
| Verifica manuale | |

## Difetti noti

<ID ticket> — <sintomo>, <causa>, <come è stato scoperto>.
Se non ce ne sono: "nessuno noto".

## Decisioni aperte (spettano al CEO)

| ID | Decisione | Stato |
|---|---|---|
| D1 | <decisione, opzioni, raccomandazione> | aperta |

## Ambiente: le trappole

Le cose che fanno perdere tempo se non le sai. Comandi che servono, flag
obbligatori, quoting, tool mancanti.

## Prossimo passo

<Il ticket concreto da cui ripartire, con i comandi.>

## Come riprendere in una sessione nuova

```bash
cd <path del progetto>
omp
```

La sessione che si apre lì è il Tech Lead di questo progetto: trova gli agenti
in `.omp/agents/`, la board in `.company/board/`, e legge questo file come
contesto. Non serve nessun passaggio di consegne: tutto ciò che conta è su
disco.
