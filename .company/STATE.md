# Stato del progetto — AndroidAutoWaFixer

> Aggiornato: 2026-10-29. **Questo è il primo file da leggere in una
> sessione nuova.** Descrive dove siamo, cosa è aperto e cosa serve per
> ripartire. Va aggiornato dal Tech Lead alla fine di ogni ciclo significativo.

## Dove siamo

v1.1 verificata su Pixel 8 (Android 17) con WhatsApp 2.26.41.77 e Desktop Head
Unit via ADB: il file vocale viene individuato **per percorso esatto** tramite
FileObserver, il player nativo viene messo in pausa e il file viene riprodotto
su `USAGE_MEDIA`. Manca solo la prova acustica su un'auto reale.

## Board

Nessun ticket formale: il CEO ha fornito la specifica e poi il feedback sul
campo; il Tech Lead ha implementato e calibrato direttamente.

## Cosa è stato consegnato

- `WaFixAccessibilityService` — intercettazione solo con Android Auto
  connesso, parsing del fumetto, pausa nativa, toggle al secondo click.
- `VoiceNoteOpenWatcher` — `FileObserver` creato **solo durante la sessione
  Android Auto**; osserva radice + settimane ISO di oggi/ieri + 2 cartelle più
  recenti. Cattura l'`OPEN` esatto del `.opus` aperto da WhatsApp.
- Cache di sessione `firma messaggio → percorso`; i riascolti (WhatsApp riusa
  il player e non genera `OPEN`) usano `match cache`.
- `WhatsAppUiParser` — tarato sul layout reale: durata nel
  `contentDescription` di `main_layout` (`17 secondi`), orario 12h con U+202F,
  esclusione di `description` (`0:00`), `audio_seekbar`, video e player fuori
  chat. Accetta lo stato `Riproduci` e `Metti in pausa`.
- `OpusDuration` — durata dal granule Ogg.
- `VoiceNoteMatcher` — fallback durata ±1,5 s + orario/mtime; **nessun**
  fallback all'ultimo file.
- `VoiceNoteFinder` — scansione diretta (ultime 40 settimane, 200 file) o SAF.
- `VoiceNotePlayer` — Media3 ExoPlayer, `USAGE_MEDIA`/`SPEECH`, AudioFocus
  `TRANSIENT_MAY_DUCK`, rilascio a fine traccia.
- `MainActivity` — stato permessi/AA/WhatsApp/player, test ultimo vocale,
  telemetria.
- `scripts/adb-setup.sh` + README; fixture reale in
  `app/src/test/resources/whatsapp_voice_note_row.xml`.

**Verifiche**

| Prova | Esito |
|---|---|
| `./gradlew :app:testDebugUnitTest` | 27 test, 0 falliti |
| `./gradlew :app:assembleDebug :app:lintDebug` | build + lint ok |
| Pixel 8 + DHU (CarConnection type=2) | watcher attivo su 3 cartelle |
| Tap su vocale 17 s | `OPEN` esatto → `WA0027`, pausa nativa, playback |
| Riascolto 4 s | `match cache` immediato |
| Secondo tap durante playback | `secondo click: player fermato` + pausa nativa |
| Player nativo durante il nostro playback | tutte le `control_btn` su `Riproduci` |
| Audio udibile in auto | **non provato** (DHU non ha casse) |

## Difetti noti

Nessuno noto nel flusso verificato. Rischi residui:

1. WhatsApp può cambiare di nuovo label/layout: il fixture e i log
   (`fumetto vocale non riconosciuto`, `vocale durata=…`) guidano la
   calibrazione.
2. Il watcher copre solo le cartelle di oggi/ieri e le 2 più recenti: note
   molto vecchie usano il fallback (1-2 s di scansione).
3. Su device lenti la pausa iniettata a 350 ms può arrivare in ritardo.
4. Il fallback può scegliere un file sbagliato solo se più file hanno la
   stessa durata e lo stesso orario; il match viene comunque loggato.

## Decisioni aperte (spettano al CEO)

| ID | Decisione | Stato |
|---|---|---|
| D1 | Prova acustica su auto reale con WhatsApp + Android Auto | aperta |
| D2 | Distribuzione (firma release, R8) | aperta |

## Ambiente: le trappole

- AGP 9 include Kotlin built-in: non applicare `org.jetbrains.kotlin.android`.
- Comandi verificati: `company init . --detect` (stack android) coincide con
  il manifest.
- Gradle 9.8.2 / JDK 21.
- `adb shell settings put secure enabled_accessibility_services` va eseguito
  preservando i servizi esistenti: usare `scripts/adb-setup.sh`.
- **Non** usare `am force-stop` sull'app: Android disabilita il servizio di
  accessibilità; rilanciare lo script.
- DHU: il Pixel è collegato via ADB e `CarConnection` riporta `type=2`
  (projection). Utile per test funzionali, non acustici.
- Il telefono ha ~286 cartelle settimanali in `WhatsApp Voice Notes`.

## Prossimo passo

Collegare un'auto reale (o un device con uscita audio) e verificare che il
vocale si senta dalle casse. Log:

```bash
adb logcat -s AAWaFixer
```

Attesi: `OPEN catturato` o `match cache`, `pausa del player nativo inviata`,
`riproduco …`, `vocale terminato`.

## Come riprendere in una sessione nuova

```bash
cd /mnt/ssd2tb/Coding/AndroidAutoWaFixer
omp
```

La sessione principale è il Tech Lead: agenti in `.omp/agents/`, board in
`.company/board/`, contesto in questo file.
