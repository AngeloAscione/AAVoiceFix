# Stato del progetto — AndroidAutoWaFixer

> Aggiornato: 2026-10-29. **Questo è il primo file da leggere in una
> sessione nuova.** Descrive dove siamo, cosa è aperto e cosa serve per
> ripartire. Va aggiornato dal Tech Lead alla fine di ogni ciclo significativo.

## Dove siamo

v1.2.2 verificata su Pixel 8 (Android 17) con WhatsApp 2.26.41.77 e Desktop Head
Unit via ADB: il file vocale viene individuato **per percorso esatto** tramite
FileObserver, il player nativo viene messo in pausa e il file viene riprodotto
su `USAGE_MEDIA`. Verificati anche switch immediato tra vocali e auto-advance.
Il CEO ha confermato che l'audio funziona anche tramite DHU e che il flusso
completo (click, stop, switch, auto-advance, nuove note in arrivo) funziona
in modo perfetto sul campo.

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
- Nessuna interruzione da `OPEN`: se stiamo già riproducendo, un nuovo file
  aperto da WhatsApp (download o nota in arrivo) viene ignorato e l'eventuale
  player nativo messo in pausa. Il nuovo vocale parte solo con un click.
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
| `OPEN` di un vocale scaricato, senza playback nativo | **ignorato** (nessun audio) |
| `OPEN` di un nuovo vocale durante il nostro playback | **ignorato**: la traccia corrente continua; pausa nativa |
| Player nativo durante il nostro playback | tutte le `control_btn` su `Riproduci` |
| Audio udibile via DHU | **ok, confermato dal CEO** |
| `./gradlew :app:assembleRelease` + `apksigner verify` | ok: APK release firmato, `minSdk 35`, versione 1.2 |

## Difetti noti / rischi aperti

Nessun bug noto nel flusso verificato. Rischi aperti, in ordine di priorità:

1. **Auto-advance**: ora richiede una nota nativa in stato `Pausa` **e** la
   corrispondenza di durata tra file aperto e nota in riproduzione. Un
   download viene ignorato. Residuo: un download con la stessa durata della
   nota in riproduzione potrebbe ancora passare; caso raro.
2. **Pausa nativa quando WhatsApp non è in foreground**: `rootInActiveWindow`
   può non contenere il fumetto; l'auto-advance riprodurrebbe il file senza
   fermare il player nativo (possibile doppio audio sul telefono).
3. **Scope del watcher**: solo cartelle di oggi/ieri e le 2 più recenti. Le
   note più vecchie usano il fallback (1-2 s) e possono essere ambigue.
4. **Collisione firma cache**: stesso mittente + stessa durata + stesso minuto
   → un click su un altro vocale verrebbe trattato come "stesso vocale".
   Caso raro; la firma non include bounds o percorso.
5. **Cache di fallback**: un match sbagliato per durata resta in cache fino
   alla disconnessione AA (scelta CEO: nessun comando di invalidazione).
6. **Layout WhatsApp**: fixture e label sono validi per 2.26.41.77; un
   aggiornamento può richiedere nuova calibrazione.
7. **SAF**: percorso senza watcher, scansione DocumentFile più lenta; non è
   il percorso ufficiale (si usa `MANAGE_EXTERNAL_STORAGE`).
8. **Pausa nativa a 350 ms**: su device lenti può arrivare tardi; il log
   dice se il click fallisce.
9. **Nessun test automatico del servizio**: parser/matcher/watcher/policy
   sono unit-testati; il flusso click→pause→play è verificato solo a mano.
10. **Perdita del keystore**: senza `keystore/aawafixer-release.jks` e
    `keystore.properties` non si possono firmare aggiornamenti compatibili.
    Devono essere conservati fuori dal repository.
11. **Force-stop**: disabilita il servizio di accessibilità (documentato);
    dopo un force-stop va rilanciato `scripts/adb-setup.sh`.

## Decisioni CEO

**Chiuse**

| ID | Decisione | Esito |
|---|---|---|
| D1 | Prova acustica | **ok via DHU**, audio funzionante (conferma CEO) |
| D2 | Distribuzione | niente Play Store; canali open (GitHub/F-Droid/amici); R8 off; keystore release creato fuori dal repo |
| D3 | WhatsApp Business | non supportato (solo `com.whatsapp`) |
| D4 | Permessi storage | `MANAGE_EXTERNAL_STORAGE`; nessun hardening |
| D5 | Android minimo | **Android 15+ (API 35)** |
| D6 | Storage | solo primary storage dell'utente corrente |
| D7 | Lingue | solo IT/EN |
| D8 | Player fuori chat | non intercettato |
| D9 | Cache | nessun comando di invalidazione |
| D10 | Licenza open-source | **Apache-2.0**, `LICENSE` + `NOTICE` |
| D11 | Canale di distribuzione | **solo GitHub**: `git@github.com:AngeloAscione/AAVoiceFix.git` |

**Ancora aperte**

Nessuna.

## Ambiente: le trappole

- AGP 9 include Kotlin built-in: non applicare `org.jetbrains.kotlin.android`.
- Comandi verificati: `company init . --detect` (stack android) coincide con
  il manifest.
- Gradle 9.8.2 / JDK 21; `minSdk 35` (Android 15+), `targetSdk 37`.
- Remoto GitHub: `origin` → `git@github.com:AngeloAscione/AAVoiceFix.git`
  (canale unico di distribuzione; niente F-Droid).
- Identità commit: Angelo Ascione <iosonoangeloascione@gmail.com>.
- Firma release: `keystore/aawafixer-release.jks` + `keystore.properties`
  (entrambi gitignored). Alias `aawafixer`, DN
  `CN=AA Voice Fix, OU=Community, O=AA Voice Fix, C=IT`.
  SHA-256 certificato:
  `7e966fbb0154ce4b3467c295e84d10a7bdfb391c9e1531fe7313c5598e812f7d`.
  Backup obbligatorio.
- `adb shell settings put secure enabled_accessibility_services` va eseguito
  preservando i servizi esistenti: usare `scripts/adb-setup.sh`.
- **Non** usare `am force-stop` sull'app: Android disabilita il servizio di
  accessibilità; rilanciare lo script.
- DHU: il Pixel è collegato via ADB e `CarConnection` riporta `type=2`
  (projection). Utile per test funzionali, non acustici.
- Il telefono ha ~286 cartelle settimanali in `WhatsApp Voice Notes`.

## Prossimo passo

Il repository è su GitHub (`origin/main`). Pubblicare
la release **v1.2.2** con l'APK firmato:

```bash
./gradlew :app:assembleRelease
git tag -a v1.2.2 -m "AA Voice Fix 1.2.2"
git push origin v1.2.2
# poi allegare app/build/outputs/apk/release/app-release.apk alla release
```

Note release: Apache-2.0, Android 15+, niente Play Store. Il keystore e
`keystore.properties` restano fuori dal repository e vanno conservati.

## Come riprendere in una sessione nuova

```bash
cd /mnt/ssd2tb/Coding/AndroidAutoWaFixer
omp
```

La sessione principale è il Tech Lead: agenti in `.omp/agents/`, board in
`.company/board/`, contesto in questo file.
