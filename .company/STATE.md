# Stato del progetto — AndroidAutoWaFixer

> Aggiornato: 2026-10-28. **Questo è il primo file da leggere in una
> sessione nuova.** Descrive dove siamo, cosa è aperto e cosa serve per
> ripartire. Va aggiornato dal Tech Lead alla fine di ogni ciclo significativo.

## Dove siamo

v1.0 funzionante e verificata su emulatore: intercettazione del Play WhatsApp,
pausa del player nativo, matching del file `.opus` e riproduzione su
`USAGE_MEDIA`. Manca la prova sul campo con WhatsApp + Android Auto reali.

## Board

Nessun ticket formale: il CEO ha fornito la specifica completa e il Tech Lead
ha implementato direttamente (eccezione esplicita al flusso PM → Dev → QA).

## Cosa è stato consegnato

- Progetto Android Kotlin completo (Gradle wrapper 9.8.2, AGP 9.4.1,
  compileSdk/targetSdk 37, minSdk 29).
- `WaFixAccessibilityService` — intercettazione solo con Android Auto
  connesso (`CarConnection`), parsing del fumetto, soppressione dei click
  iniettati, toggle al secondo click.
- `WhatsAppUiParser` — Play, durata e orario dai nodi di accessibilità;
  esclusione dei video; riconoscimento per viewId WhatsApp
  (`voice_note_playback_control`, `voice_note_seekbar`,
  `voice_note_audio_duration`, `date`).
- `OpusDuration` — durata dal granule Ogg, senza decodifica.
- `VoiceNoteMatcher` — match per durata (tolleranza 1,5 s), orario del
  messaggio e mtime; fallback all'ultimo `.opus`.
- `VoiceNoteFinder` — scansione diretta (`MANAGE_EXTERNAL_STORAGE`) o SAF.
- `VoiceNotePlayer` — Media3 ExoPlayer, `USAGE_MEDIA` +
  `CONTENT_TYPE_SPEECH`, AudioFocus `TRANSIENT_MAY_DUCK`, rilascio a fine
  traccia.
- `MainActivity` — stato accessibilità/file/SAF/Android Auto/WhatsApp/player,
  pulsanti di concessione, test dell'ultimo vocale, telemetria ultimo evento.
- `scripts/adb-setup.sh` e README con i comandi ADB esatti.

**Verifiche**

| Prova | Esito |
|---|---|
| `./gradlew :app:testDebugUnitTest` | 17 test, 0 falliti |
| `./gradlew :app:assembleDebug :app:lintDebug` | build + lint ok |
| Install su emulatore API 35 via ADB | ok, UI renderizzata |
| `appops MANAGE_EXTERNAL_STORAGE` + service abilitato via ADB | ok |
| Riproduzione reale di un `.opus` 24,5 s | ok: "vocale terminato", focus/player rilasciati |
| Parser su struttura viewId WhatsApp reale | coperto da unit test |
| Pixel 8 (Android 17) via ADB: install, appops, service | ok, servizio ATTIVO |
| Scansione reale + playback su Pixel 8 | ok: `202644/PTT-20261028-WA0028.opus`, <1 s |
| WhatsApp + Android Auto reali | **non ancora provato** (nessuna auto connessa) |

## Difetti noti

Nessuno noto nel codice. Due rischi di campo:

1. WhatsApp può cambiare layout/label: il parser è euristico e va calibrato
   con `adb logcat -s AAWaFixer` + `uiautomator dump`.
2. La pausa nativa è un click iniettato a 350 ms: su device lenti può arrivare
   in ritardo. Il log dice se il click è riuscito.

## Decisioni aperte (spettano al CEO)

| ID | Decisione | Stato |
|---|---|---|
| D1 | Prova su device reale con WhatsApp + Android Auto e raccolta log | aperta |
| D2 | Eventuale distribuzione (firma release, R8) | aperta |

## Ambiente: le trappole

- AGP 9 include Kotlin built-in: **non** applicare `org.jetbrains.kotlin.android`
  (il plugin viene rifiutato).
- Comandi verificati: manifest `project.*_command` allineati a
  `company init . --detect` (stack android).
- Gradle 9.8.2 richiede JDK 17+; il default della macchina è JDK 21.
- `adb shell settings put secure enabled_accessibility_services` va eseguito
  preservando i servizi esistenti: usare `scripts/adb-setup.sh`.
- L'emulatore API 35 non ha Android Auto: `CarConnection` resta
  `CONNECTION_TYPE_NOT_CONNECTED`, quindi il percorso di intercettazione non è
  testabile lì.

## Prossimo passo

Installare su un telefono reale con `scripts/adb-setup.sh`, collegare Android
Auto, aprire una chat con un vocale e verificare:

```bash
adb logcat -s AAWaFixer
```

Attesi: `vocale durata=… ora=…`, `pausa del player nativo inviata`,
`match PTT-…`, `riproduco …`, `vocale terminato`.

## Come riprendere in una sessione nuova

```bash
cd /mnt/ssd2tb/Coding/AndroidAutoWaFixer
omp
```

La sessione che si apre lì è il Tech Lead di questo progetto: trova gli agenti
in `.omp/agents/`, la board in `.company/board/`, e legge questo file come
contesto. Non serve nessun passaggio di consegne: tutto ciò che conta è su
disco.
