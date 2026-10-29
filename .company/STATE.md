# Stato del progetto — AndroidAutoWaFixer

> Aggiornato: 2026-10-29. **Questo è il primo file da leggere in una
> sessione nuova.** Descrive dove siamo, cosa è aperto e cosa serve per
> ripartire. Va aggiornato dal Tech Lead alla fine di ogni ciclo significativo.

## Dove siamo

v1.2 verificata su Pixel 8 (Android 17) con WhatsApp 2.26.41.77 e Desktop Head
Unit via ADB: il file vocale viene individuato **per percorso esatto** tramite
FileObserver, il player nativo viene messo in pausa e il file viene riprodotto
su `USAGE_MEDIA`. Verificati anche switch immediato tra vocali e auto-advance.
Manca solo la prova acustica su un'auto reale.

## Board

Nessun ticket formale: il CEO ha fornito la specifica e poi il feedback sul
campo; il Tech Lead ha implementato e calibrato direttamente.

## Cosa è stato consegnato

- `WaFixAccessibilityService` — intercettazione solo con Android Auto
  connesso, parsing del fumetto, pausa nativa, toggle al secondo click.
- `VoiceNoteOpenWatcher` — `FileObserver` creato **solo durante la sessione
  Android Auto**; osserva radice + settimane ISO di oggi/ieri + 2 cartelle più
  recenti. Cattura l'`OPEN` esatto del `.opus` aperto da WhatsApp.
- Auto-advance: un `OPEN` senza click (WhatsApp passa da solo alla nota
  successiva) viene riprodotto dopo 300 ms; prima si clicca il controllo
  nativo in stato `Pausa`.
- Cache di sessione `firma messaggio → percorso`; i riascolti (WhatsApp riusa
  il player e non genera `OPEN`) usano `match cache`.
- Click durante il playback: stesso vocale → stop; vocale diverso → switch
  immediato con un solo click.
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
| Click su un altro vocale durante il playback | `switch vocale`, nuovo file riprodotto al primo click |
| Secondo click sullo stesso vocale | `stesso vocale: player fermato` + pausa nativa |
| Auto-advance con player nativo attivo | `auto: pausa del player nativo inviata` + playback del file aperto |
| Auto-advance mentre il nostro player suona | ferma il precedente e riproduce il nuovo |
| Player nativo durante il nostro playback | tutte le `control_btn` su `Riproduci` |
| Audio udibile in auto | **non provato** (DHU non ha casse) |

## Difetti noti / rischi aperti

Nessun bug noto nel flusso verificato. Rischi aperti, in ordine di priorità:

1. **Audio in auto non provato** (DHU non ha casse). È l'unica verifica
   mancante sul funzionamento reale del prodotto.
2. **Auto-advance euristico**: un `OPEN` senza click viene trattato come
   playback. Se WhatsApp aprisse un file per preload, partirebbe in anticipo.
   Nei test osservati l'`OPEN` coincide sempre con il playback.
3. **Pausa nativa quando WhatsApp non è in foreground**: `rootInActiveWindow`
   può non contenere il fumetto; l'auto-advance riprodurrebbe il file senza
   fermare il player nativo (possibile doppio audio sul telefono).
4. **Scope del watcher**: solo cartelle di oggi/ieri e le 2 più recenti. Le
   note più vecchie usano il fallback (1-2 s) e possono essere ambigue.
5. **Collisione firma cache**: stesso mittente + stessa durata + stesso minuto
   → un click su un altro vocale verrebbe trattato come "stesso vocale".
   Caso raro; la firma non include bounds o percorso.
6. **Cache di fallback**: un match sbagliato per durata resta in cache fino
   alla disconnessione AA; non c'è un comando per invalidarla.
7. **Player fuori chat escluso**: il tasto `out_of_chat_playback_btn` non
   espone durata/orario, quindi non viene intercettato (resta il bug nativo).
8. **WhatsApp Business** (`com.whatsapp.w4b`) non supportato.
9. **Localizzazione**: durata in parole riconosciuta in italiano/inglese;
   altre lingue (`Sekunden`, `segundos`, …) non sono coperte.
10. **Layout WhatsApp**: fixture e label sono validi per 2.26.41.77; un
    aggiornamento può richiedere nuova calibrazione.
11. **SAF**: percorso senza watcher, scansione DocumentFile più lenta; mai
    provato su device reale.
12. **Storage secondario / multi-utente** non supportati: solo primary
    external storage dell'utente corrente.
13. **Android 10–12** non provati (minSdk 29); test solo su Android 17.
14. **Pausa nativa a 350 ms**: su device lenti può arrivare tardi; il log
    dice se il click fallisce.
15. **Nessun test automatico del servizio**: parser/matcher/watcher/policy
    sono unit-testati; il flusso click→pause→play è verificato solo a mano.
16. **Release**: nessuna firma release né R8; decisione CEO ancora aperta.
17. **Force-stop**: disabilita il servizio di accessibilità (documentato);
    dopo un force-stop va rilanciato `scripts/adb-setup.sh`.
18. **Privacy log**: risolto in questa revisione; il log dei click non
    include più il `contentDescription` dei nodi WhatsApp.

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
