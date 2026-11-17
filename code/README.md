# Rising Sea v6.5

Minecraft Java 26.3 / Fabric; JDK 25 fuer den Build.

## Funktionen

- LAYERS, REALISTIC, TSUNAMI (EXPERIMENTAL) mit globalem Zielwasserstand, manueller Zielhoehe und Chunk-Verarbeitung.
- Feste Anstiegsintervalle in Sekunden oder optional ein **zufaelliges Intervall nach Minecraft-Tagen** (`randomSchedule`, `randomMinDays`, `randomMaxDays`). Nur LAYERS/REALISTIC; TSUNAMI verwendet weiterhin `tsunamiIntervalDays`. Ein Minecraft-Tag entspricht 24.000 Server-Ticks. Der ausgeloste Zeitpunkt wird in der Weltdaten-Datei gespeichert.
- Optional `waitForChunkPass`: automatischen Anstieg erst freigeben, wenn die laufende Chunk-Warteschlange abgearbeitet ist. Bei grossen Radien kann das die Flut stark verzoegern; standardmaessig **aus**. REALISTIC bleibt eine vereinfachte Wasser-Ausbreitung, kein physikalischer Wellensimulator.
- Chunk-Raender werden beim Fuellen vor dem Chunk-Innenbereich bearbeitet. Das kann sichtbare Kanten verringern, verhindert Minecraft-Fluessigkeitsspruenge nicht sicher.
- `/risingsea status` zeigt Zielpegel, offene Chunks und seit Serverstart platzierte Wasserbloecke. `debug` kann als Diagnose-Einstellung geschaltet werden.
- Lokale Standardkonfiguration ist auch im Mod-Menu-Hauptmenue anzeigbar und dort speicherbar. Im Spiel sind die **Serverwerte verbindlich**; auf dedizierten Servern wirken lokale Hauptmenue-Defaults nicht.
- Bei steigendem Meeresspiegel werden ausschliesslich geeignete bestehende Boote angehoben; beim Absenken uebernimmt Vanilla die Bootsphysik. Andere Entities werden nicht extra versetzt.

## Konsolenbeispiele

```text
risingsea set randomschedule true
risingsea set randommindays 1
risingsea set randommaxdays 5
risingsea set waitscan true
risingsea set debug true
risingsea status
risingsea setlevel 75
risingsea mode tsunami
risingsea set tsunamidays 3
```

## Build

Unter Windows CMD im Ordner mit `build.gradle`:

```bat
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot" && set "PATH=C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\bin;%PATH%" && gradle --stop && gradle clean build
```

Erwartete JAR bei erfolgreichem Build: `build/libs/risingsea-6.4.jar`.

## Test und Grenzen

Dies ist **Quellcode** und keine getestete JAR. Vor dem Einsatz eine Welt sichern. Globaler Zielwert heisst nicht, dass alle ungeladenen Chunks sofort veraendert werden. Das Rueckbau-Ledger erfasst nur vom Mod gesetzte Wasserbloecke. TSUNAMI und Vanilla-Fluessigkeitsphysik koennen Wasser ueber die gespeicherten Positionen hinaus verteilen. Die experimentelle Wellenbereinigung ist weiterhin nicht zuverlaessig vollstaendig. Falling Sea und Rising Sea nicht gleichzeitig in derselben Welt zum Bearbeiten von Wasserstaenden einsetzen.

Die Basis fuer die Chunk-Rand-Reihenfolge, Scan-Barriere und persistente Random-Intervalle wurde anhand der vom Nutzer bereitgestellten Falling Sea v1.22-Quellen angepasst. Die Funktionen werden nicht 1:1 uebertragen, weil Entfernen von Wasser und Hinzufuegen von Wasser unterschiedliche Nebenwirkungen erzeugen.


## Languages / Sprachen
GUI, HUD effect, and key server/chat messages are translated to English and German
through `assets/risingsea/lang/en_us.json` and `de_de.json`. Language follows Minecraft client settings.

## Rising Sea status-effect-style HUD
A compact effect-like countdown appears in the upper right during the final warning seconds
when a rise is pending, flooding is active and chunk processing is ready. This is a HUD element,
not a potion effect; it does not alter player attributes. It is updated via server snapshots.

## Icon
`assets/risingsea/icon.png`: upward arrows above seawater, opposite direction from Falling Sea.

## Countdown (v6.5)

- Only the upper-right effect tile is shown; the redundant actionbar countdown was removed.
- `countdownLastSeconds` sets the *start of the warning window* (0-60 seconds), not the interval between events.
- `intervalSeconds` governs regular rise in LAYERS/REALISTIC. TSUNAMI uses `tsunamiIntervalDays` (Minecraft days); 0 means manual only.
- Manual-only tsunami reports `manual` in `/risingsea status` and never displays an absurd countdown.
- While the next rising step is waiting for a chunk pass, the effect tile is suppressed to avoid a frozen countdown.

For 60 seconds of advance notice, configure `countdownLastSeconds=60`. To trigger a tsunami automatically use `tsunamiIntervalDays>=1`; normal `intervalSeconds=60` does not override the tsunami day timer.

## Tsunami-Wellenfront (v6.5)

Die voruebergehende Wellenhaube ist jetzt eine mit der Front wandernde **Partikelvisualisierung** und erzeugt **keine zusaetzlichen Wasserquellen** oberhalb des Zielpegels. Dadurch kann nach einer Welle keine neue, dauerhaft stehende Wasserwand aus den Haubenbloecken entstehen. Der dauerhafte Wasseranstieg erfolgt weiter ueber echte Wasserbloecke, aber waehrend der aktiven Welle erst in bereits erreichten geladenen Chunks. Nach Ende der Welle werden andere geladene Chunks aufgeholt. Die Darstellung ist KEINE vollwertige physikalische Tsunami-Fluessigkeit und hat keine kollisionserzeugende Wasserhaube; die Boots-Auswurfmechanik bleibt positionsbasiert.

**Alte Welten:** Wasser, das aeltere Versionen als Wellenhaube erzeugt haben und das sich durch Vanilla-Wasserphysik weiterverbreitet hat, wird nicht automatisch bereinigt. Nutze eine frische Testwelt oder ein Backup. Sehr hohe Partikel-Dichten koennen Performance kosten; die Ausgabe ist serverseitig begrenzt.
