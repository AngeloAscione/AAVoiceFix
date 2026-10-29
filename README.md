# AA Voice Fix

Intercettore trasparente per le note vocali di WhatsApp quando il telefono è
proiettato su Android Auto.

**Problema:** con Android Auto attivo, il tasto "Riproduci" di un vocale in chat
prende correttamente l'AudioFocus (la musica si abbassa) ma WhatsApp instrada
l'audio su un canale che la proiezione ignora: il vocale è muto. Le app che
usano il canale multimediale (Telegram) si sentono.

**Soluzione:** il servizio di accessibilità riconosce il click sul Play, mette
in pausa il player nativo e riproduce lo **stesso file `.opus`** con
`USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`. Android Auto lo tratta come musica e lo
manda alle casse dell'auto.

Nessun root, nessun Shizuku, nessun ADB a runtime: solo l'installazione
one-shot.

## Flusso

```mermaid
sequenceDiagram
    participant U as Utente
    participant WA as WhatsApp
    participant W as FileObserver
    participant S as AccessibilityService
    participant F as VoiceNoteFinder
    participant P as ExoPlayer

    U->>WA: tap "Riproduci"
    WA->>W: OPEN del .opus (solo sessione AA)
    WA->>S: TYPE_VIEW_CLICKED (com.whatsapp)
    S->>S: CarConnection == PROJECTION?
    S->>S: parse fumetto: durata + orario
    S->>WA: tap iniettato dopo 350 ms (pausa)
    alt OPEN catturato o cache di sessione
        W-->>S: percorso esatto
    else nessun OPEN (player riusato)
        S->>F: fallback durata ±1,5 s + mtime
        F-->>S: VoiceNoteFile
    end
    S->>P: play(uri), AudioFocus TRANSIENT_MAY_DUCK
    P-->>U: audio su USAGE_MEDIA → casse auto
```

Il servizio resta dormiente finché `CarConnection` non segnala proiezione o
unità nativa: nessun polling, nessun servizio in background aggiuntivo.

## Intercettazione

1. **Android Auto**: `androidx.car.app.connection.CarConnection`, libreria
   AndroidX ufficiale. Nessun evento viene processato se il tipo è
   `CONNECTION_TYPE_NOT_CONNECTED`.
2. **Click**: solo eventi `TYPE_VIEW_CLICKED` del package `com.whatsapp`, con
   `packageNames` già filtrato nel config XML.
3. **Fumetto**: dal nodo cliccato si risale fino a 9 livelli; il sottoalbero
   viene appiattito in `UiNode` e il parser puro cerca la durata e l'orario.
   WhatsApp 2.26.41.77 (Pixel 8, Android 17) espone questa struttura:

   | viewId | Ruolo |
   |---|---|
   | `control_btn` | desc `Riproduci messaggio vocale` / `Metti in pausa messaggio vocale` |
   | `main_layout` | desc `Greg Garofalo, messaggio vocale, 17 secondi, 6:30 PM, riprodotto` |
   | `description` | testo `0:00` = tempo trascorso, **non** durata |
   | `audio_seekbar` | desc `Messaggio vocale, tempo trascorso: 0 secondi` |
   | `date` | `6:30 PM` (12h) |

   La durata vera è quindi nel `contentDescription` del fumetto, in parole
   (`17 secondi`), e l'orario è in 12h con spazio U+202F prima di `PM`.
   Le SeekBar e il nodo `description` sono esclusi. I video e il player
   fuori chat (`out_of_chat_playback_btn`) sono esclusi.
4. **Pausa del player nativo**: il nodo Play viene ricliccato dopo 350 ms. Il
   nodo cambia subito label in `Metti in pausa...`: il parser accetta sia
   `Riproduci` sia `Pausa`. I click generati dall'app sono ignorati per 1,5 s:
   senza questa finestra la pausa iniettata riattiverebbe il ciclo.
5. **File esatto**: vedi sotto.
6. **Playback**: Media3 ExoPlayer con `USAGE_MEDIA` e
   `CONTENT_TYPE_SPEECH`; AudioFocus `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`
   (la musica si abbassa). Focus e player vengono rilasciati a fine traccia.
7. **Toggle**: un click su un vocale mentre l'app sta riproducendo ferma il
   player (e mette comunque in pausa quello nativo).

## Individuazione del file `.opus`

### 1. Percorso esatto con FileObserver (primario)

Quando WhatsApp avvia il player nativo apre il file: un `FileObserver`
(inotify) sulle cartelle delle note vocali cattura quell'`OPEN` e restituisce
il **percorso esatto**, senza euristiche.

Policy richiesta: **il watcher esiste solo durante la sessione Android Auto**
(viene creato all connessione e distrutto alla disconnessione). Osserva:

- la radice `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/`;
- le cartelle settimanali ISO `YYYYWW` di **oggi e ieri** (dove arrivano i
  nuovi download, anche se ancora vuote);
- come rete di sicurezza, le due cartelle numeriche più recenti.

Gli `OPEN` generati dall'app (scansione durate, ExoPlayer) sono ignorati. Il
percorso esatto viene memorizzato in una cache `firma messaggio → file`,
valida per la sessione AA: WhatsApp, sui riascolti, riusa il player e **non**
genera un nuovo `OPEN`.

### 2. Fallback per durata (solo se l'OPEN manca)

Directory scansionata: le ultime 40 cartelle settimanali, fino a 200 file per
mtime (le ~286 cartelle complete sarebbero troppo lente).

1. Durata reale letta dal **granule position** dell'ultima pagina Ogg
   (`granule / 48000`), senza decodifica.
2. Si tengono i file con durata entro **1,5 s** da quella mostrata in chat.
3. Tra questi vince il mtime con orario più vicino all'orario del messaggio
   (distanza circolare sulla giornata); a parità, il più recente.
4. **Nessun fallback all'ultimo file**: se nessun file ha la durata giusta,
   l'app non riproduce nulla e lo scrive nel log. Dopo i test sul campo
   riprodurre un vocale sbagliato è peggio che restare muti.

Il primo match di fallback viene memorizzato nella cache di sessione: i
riascolti sono immediati e coerenti.

## Build

Requisiti: JDK 17+, Android SDK 37. Gradle wrapper incluso.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Test unitari (JVM, nessun device): parser UI WhatsApp, lettura durata Ogg/Opus,
algoritmo di matching.

## Setup via ADB USB (una volta sola)

```bash
./gradlew :app:assembleDebug
./scripts/adb-setup.sh
```

Lo script esegue esattamente:

```bash
# 1. install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. accesso completo ai file (necessario per leggere Android/media/com.whatsapp)
adb shell appops set dev.local.aawafixer MANAGE_EXTERNAL_STORAGE allow

# 3. solo Android <= 12: permesso storage legacy
adb shell pm grant dev.local.aawafixer android.permission.READ_EXTERNAL_STORAGE

# 4. abilita il servizio di accessibilità preservando quelli esistenti
CURRENT="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
adb shell settings put secure enabled_accessibility_services \
  "${CURRENT:-dev.local.aawafixer/dev.local.aawafixer.WaFixAccessibilityService}"
adb shell settings put secure accessibility_enabled 1

# 5. verifica
adb shell appops get dev.local.aawafixer MANAGE_EXTERNAL_STORAGE
adb shell settings get secure enabled_accessibility_services
```

Se `enabled_accessibility_services` era valorizzato, **non sovrascriverlo**:
accoda `:dev.local.aawafixer/dev.local.aawafixer.WaFixAccessibilityService`
(lo script lo fa già).

### Alternativa SAF (senza MANAGE_EXTERNAL_STORAGE)

Nell'app: **Scegli cartella note vocali (SAF)** → seleziona
`WhatsApp Voice Notes`. La tree URI viene resa persistente e usata per
scansionare e riprodurre i file. È più lenta (una query per file) ma non
richiede permessi speciali.

### Disinstallazione / ripristino

```bash
adb shell settings put secure enabled_accessibility_services ""   # oppure rimuovi solo il nostro componente
adb shell appops set dev.local.aawafixer MANAGE_EXTERNAL_STORAGE deny
adb uninstall dev.local.aawafixer
```

Riavvia l'app (o il telefono) dopo aver cambiato `enabled_accessibility_services`.

**Non usare `adb shell am force-stop dev.local.aawafixer`**: su Android 13+
il force-stop disabilita il servizio di accessibilità del pacchetto e azzera
`enabled_accessibility_services`. Per fermare l'app usa il pannello delle app
recenti o rilancia `scripts/adb-setup.sh` dopo il force-stop.

## Uso

1. Collega il telefono ad Android Auto.
2. Apri l'app **AA Voice Fix**: i tre stati devono essere verdi/attivi
   (Accessibilità ATTIVA, File accesso completo o SAF, Android Auto CONNESSO).
3. In auto apri la chat WhatsApp sul telefono e tocca **Riproduci**.
4. Il vocale parte dalle casse dell'auto. Un secondo tap lo ferma.

## Diagnostica

```bash
adb logcat -s AAWaFixer
```

L'app mostra anche l'ultimo evento e il file scelto nella schermata principale.
Se un vocale resta muto, il log indica quale fase è fallita:

| Messaggio | Significato |
|---|---|
| `watcher attivo su N cartelle` | sessione AA iniziata, watcher creato |
| `OPEN catturato: …` | WhatsApp ha aperto un `.opus`: percorso esatto |
| `match esatto …` | riproduzione del file catturato dal watcher |
| `match cache …` | riascolto: percorso esatto preso dalla cache di sessione |
| `match … durata=… mtime=…` | fallback per durata (OPEN assente) |
| `vocale durata=… ora=…` | metadati estratti dal fumetto |
| `nessun .opus per durata=… ora=…` | nessun file compatibile: nessun audio |
| `click iniettato ignorato` | finestra di soppressione, normale |
| `impossibile mettere in pausa il player nativo` | nodo WhatsApp cambiato |
| `fumetto vocale non riconosciuto` | parser da calibrare su un nuovo layout |

Per calibrare un aggiornamento di WhatsApp, servono nell'ordine:
`adb shell uiautomator dump` mentre un vocale è visibile, poi confrontare
`viewId` e `contentDescription` con i pattern in
`app/src/main/java/dev/local/aawafixer/WhatsAppUi.kt`.

## Limiti noti

- WhatsApp può cambiare layout/label in qualsiasi aggiornamento: il parser è
  tarato su 2.26.41.77 e resta euristico per i layout precedenti. Un fixture
  reale è in `app/src/test/resources/whatsapp_voice_note_row.xml`.
- Solo note vocali **ricevute/inviate** presenti in `Android/media/...`; i file
  non ancora scaricati non esistono su disco.
- Il watcher copre solo le cartelle di oggi/ieri e le due più recenti: una
  nota molto vecchia usa il fallback per durata.
- La pausa nativa è un click iniettato: su device molto lenti può arrivare
  dopo l'inizio della riproduzione (350 ms è un compromesso).
- Se il file non viene trovato, il player nativo resta in pausa: meglio muto
  che sul canale sbagliato. Il log dice perché.
- L'app non modifica WhatsApp: nessuna patch, nessun hook, nessun root.
- Su Android 12+ il sistema mostra la notifica delle app di accessibilità.
