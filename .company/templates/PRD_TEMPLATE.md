# PRD — <Titolo del prodotto / della feature>

| Campo | Valore |
|---|---|
| ID | PRD-<slug> |
| Autore | company-pm |
| Stato | draft \| approved \| superseded |
| Data | <YYYY-MM-DD> |
| Episodi coperti | EPIC-01, EPIC-02 |

## 1. Problema

<3-6 righe. Quale problema dell'utente o del business risolviamo. Numeri se
esistono ("oggi 3 utenti su 10 perdono i dati"). Se non ci sono numeri, non
inventarli: scrivi "dato non disponibile" e indicane la raccolta.>

## 2. Utenti target

| Utente | Contesto d'uso | Bisogno |
|---|---|---|
| <ruolo> | <quando/dove> | <cosa deve poter fare> |

## 3. Obiettivo

<Una frase. Se serve "e anche", quasi certamente sono due obiettivi → due
epiche.>

## 4. Non obiettivo (out of scope)

- <cosa NON facciamo in questa release>
- <cosa NON facciamo perché non è il problema>

## 5. Requisiti funzionali

| ID | Requisito | Priorità | Fonte |
|---|---|---|---|
| FR-1 | <descrizione osservabile, dal punto di vista dell'utente> | Must \| Should \| Could | <epoca/prodotto> |

## 6. Requisiti non funzionali

| ID | Tipo | Criterio misurabile |
|---|---|---|
| NFR-1 | performance | <es. p95 < 200 ms su 1000 record> |
| NFR-2 | sicurezza | <es. nessun dato sensibile nei log> |
| NFR-3 | accessibilità | <es. WCAG AA> |
| NFR-4 | compatibilità | <es. browser supportati> |

## 7. Flussi principali

### 7.1 <Nome del flusso>
1. <attore> <azione>
2. <sistema> <reazione>
3. <esito osservabile>

### 7.2 <Gestione errori>
1. <azione che fallisce>
2. <messaggio all'utente>
3. <stato finale del dato>

## 8. Modello dati (se introdotto)

| Entità | Campi | Relazioni | Note |
|---|---|---|---|
| <Nome> | <campo: tipo> | <rel> | <regola> |

## 9. Metriche di successo

| Metrica | Baseline | Target | Come si misura |
|---|---|---|---|
| <nome> | <oggi> | <target> | <strumento> |

## 10. Rischi e assunzioni

| # | Rischio / assunzione | Impatto | Mitigazione |
|---|---|---|---|
| 1 | <testo> | alto/medio/basso | <azione> |

## 11. Epiche

| ID | Epica | Ticket | Priorità |
|---|---|---|---|
| EPIC-01 | <titolo> | TKT-001, TKT-002 | P0 |

## 12. Dipendenze e vincoli

- **Dipendenze tecniche:** <servizi, librerie, altri team>
- **Vincoli di legge/compliance:** <GDPR, licenze>
- **Vincoli di budget:** <limite>
