# Reels Blocker (Android)

A small accessibility-service app that detects when Instagram shows the
Reels tab and automatically presses "back" to exit it. Everything else
in Instagram (DMs, feed, profile) works normally.

## What you need before starting

- **Android Studio** (free, from developer.android.com) — this is a real
  Android project, not something you can run from a browser.
- Basic comfort following step-by-step instructions in Android Studio.
  You don't need to know Kotlin to get this running, just to tweak it.
- A USB cable (or wireless debugging) to install the app on your Redmi
  Note 14 Pro+, since this isn't going on the Play Store.
- Your phone with **Developer Options** and **USB debugging** enabled
  (Settings → About phone → tap "MIUI/HyperOS version" 7 times, then
  Settings → Additional settings → Developer options → USB debugging).

## How to build and install

1. Open Android Studio → "Open" → select this `ReelsBlocker` folder.
2. Let Gradle sync (first time will download dependencies — needs internet).
3. Connect your phone via USB, accept the debugging prompt on the phone.
4. Click the green Run ▶ button, select your device.
5. The app installs and opens automatically.

## First-run setup on the phone

1. In the app, tap **"Open Accessibility Settings"** → find "Reels
   Blocker Service" → enable it → confirm the warning dialog.
2. Tap **"Open App Battery Settings"** → set battery usage to **"No
   restrictions"** and turn on **Autostart**. On HyperOS/MIUI, background
   services get killed aggressively otherwise, and the whole thing stops
   working silently after a while.
3. Open Instagram, tap the Reels tab — it should immediately bounce back.

## Keeping detection working (important)

Instagram changes its internal view IDs across app updates, so the
detection in `ReelsAccessibilityService.kt` may stop working after an
Instagram update. When that happens:

1. Enable Developer Options → "Layout Inspector" isn't needed on-device;
   instead use `adb shell uiautomator dump` while Reels is open:
   ```
   adb shell uiautomator dump /sdcard/window_dump.xml
   adb pull /sdcard/window_dump.xml
   ```
2. Open `window_dump.xml` and search for elements with
   `resource-id` or `content-desc` containing "reel" or "clips".
3. Add whatever you find to `RESOURCE_ID_CANDIDATES` or
   `TEXT_CANDIDATES` at the top of `ReelsAccessibilityService.kt`.
4. Re-run the app from Android Studio (Run ▶) to reinstall the update.

## Build bez počítače (jen z telefonu)

Kompilace neběží na telefonu — poběží zdarma na GitHubu. Telefon jen
nahraje kód nahoru a stáhne hotové .apk zpátky. Potřebuješ:

- **Termux** — nainstaluj z F-Droid (ne z Play Store, ta verze je
  zastaralá). F-Droid appku si stáhneš z f-droid.org v prohlížeči.
- Účet na **github.com** (zdarma).

### Postup (jednorázově)

1. Na github.com si v prohlížeči na telefonu vytvoř nový **prázdný**
   repozitář (např. `reels-blocker`), veřejný nebo soukromý — bez
   README, bez .gitignore.
2. V Termuxu:
   ```
   pkg update && pkg install git unzip -y
   ```
3. Stáhni si tenhle zip (odkaz z chatu) do složky Stažené soubory,
   pak v Termuxu:
   ```
   termux-setup-storage
   cd ~
   unzip /sdcard/Download/ReelsBlocker.zip
   cd ReelsBlocker
   git init
   git add .
   git commit -m "initial"
   git branch -M main
   git remote add origin https://github.com/TVOJE_JMENO/reels-blocker.git
   git push -u origin main
   ```
   Při push tě Git požádá o přihlášení — nepoužívej heslo účtu, ale
   **Personal Access Token** (GitHub → Settings → Developer settings →
   Personal access tokens → generovat s právem `repo`, zkopírovat a
   vložit místo hesla).
4. Jakmile push proběhne, GitHub automaticky spustí build (soubor
   `.github/workflows/build.yml` v repu se o to postará). Sleduj to na
   `github.com/TVOJE_JMENO/reels-blocker/actions`.
5. Po pár minutách klikni na proběhlý běh → dole v sekci "Artifacts" →
   stáhni `reels-blocker-apk` (je to .zip s .apk uvnitř).
6. Rozbal a nainstaluj .apk — telefon tě požádá o povolení "instalace z
   neznámých zdrojů" pro appku, kterou soubor otevíráš (prohlížeč nebo
   Soubory) — povol to jen pro tuhle jednu instalaci.

### Aktualizace kódu (např. oprava resource ID po update Instagramu)

Po prvním nahrání už Termux nepotřebuješ. Stačí přímo na github.com v
mobilním prohlížeči otevřít soubor `ReelsAccessibilityService.kt`,
kliknout na tužku (edit), upravit, commitnout — GitHub Actions build se
spustí automaticky znova a nové .apk si zase stáhneš z Actions.



The app now allows you to watch **one reel per "opening"** (whether you
got there from a friend's DM, the Reels tab, or a profile), and kicks
you back out the moment you swipe/scroll to a second reel in the same
viewing session. Close it and open another reel later, and you get a
fresh "one allowed reel" again.

Note: Instagram doesn't expose whether a reel was opened from a DM vs.
the main feed, so the app can't literally check "was this shared by a
friend". It uses the next best thing — session + scroll counting —
which in practice behaves exactly the way you'd want: friend sends a
reel, you watch it, and the instant you try to swipe to whatever comes
next, you're back out.

## v1.6 -- oprava problikávání/"not working", nové rozhraní se šipkou

Tohle kolo bylo o spolehlivosti appky, ne o detekci Reels (tu jsem
schválně nesahal bez čerstvého logu -- pravidlo, které tu platí, ať se
znovu neháže tip do tmy na resource ID Instagramu).

- **Problikávání čtverečku + appka "nefunguje":** appka si zapisovala
  log na disk synchronně přímo na hlavním vlákně při každé jednotlivé
  accessibility události -- a těch je při scrollování Reels hodně za
  sekundu. To sekalo hlavní vlákno, což se navenek projevovalo jako
  problikávání overlaye, a v horším případě jako ANR ("aplikace
  neodpovídá"), který Android hlásí jako službu, co "nefunguje" v
  Nastavení -- Přístupnost. Zápis logu teď běží na vlastním vlákně na
  pozadí.
- **Stejný důvod, druhá příčina:** vzorkování barvy čtverečku dělalo
  kopii celé obrazovky (screenshot) přímo na hlavním vlákně každé 4
  sekundy. Přesunuto taky na pozadí.
- **Čtvereček se schovával moc rychle:** stačilo, aby Instagram na
  jeden snímek "nenašel" ikonku (typicky při překreslení jinde na
  obrazovce), a appka ho hned schovala a zase ukázala. Teď se schová
  až po 500ms nepřetržité nepřítomnosti, ne po pár chybějících
  snímcích.
- **Ochrana proti duplicitnímu překryvu:** pokud systém službu znovu
  napojí (typicky po tom, co ji HyperOS na chvíli zabil), appka teď
  nejdřív uklidí starý overlay, než založí nový -- dřív mohlo dojít k
  duplicitě/pádu.
- Do konfigurace přístupnostní služby přidán doporučený atribut
  `android:isAccessibilityTool="true"`.
- **Nové rozhraní:** záložky Overview/Setup/Debug nahoře zmizely,
  místo nich je vlevo nahoře malá šipka -- klepnutím vyjede boční
  panel s Overview / Settings / Log. Karty se zaoblenými rohy, plošší
  tlačítka bez CAPS LOCK textu -- celkově minimalističtější vzhled.
- V Setup instrukcích přidán krok: uzamkni appku v posledních
  aplikacích (ikonka zámku), ať ji HyperOS neukončuje na pozadí -- to
  je nejčastější důvod, proč se služba časem ukáže jako "not working".

Pokud po týhle verzi pořád problikává nebo se detekce Reels chová
divně, pošli prosím čerstvý log z menu -> Log -- bez něj bych jen
znovu hádal resource ID a to se historicky nevyplácelo.

## v1.5 -- opravy z reálného logu + reorder hubu + výchozí Stop

Díky poslanému logu se konečně potvrdily dvě konkrétní příčiny:

- **Barva nikdy nefungovala:** log ukázal `Services don't have the
  capability of taking the screenshot`. Chyběl jeden atribut v
  konfiguraci accessibility služby (`android:canTakeScreenshot="true"`)
  -- bez něj vzorkování barvy vždy tiše selhalo a overlay běžel jen na
  záložní barvě. Přidáno.
- **Problikávání:** log ukázal, že se souřadnice ikonky měnily o desítky
  pixelů během desítek milisekund (mikro-animace lišty při scrollu).
  Zvýšen práh pro překreslení a přidán časový limit mezi aktualizacemi
  pozice.
- **"Po čase přestalo blokovat scroll":** appka brala jediný snímek, kdy
  detekce "jsem v Reels" na moment selhala (typicky během přechodové
  animace mezi reely), jako opuštění session -- a tím ti dávala nový
  "zdarma" reel při každém takovém zaškobrtnutí. Teď to vyžaduje 2 po
  sobě jdoucí neshody, než appka session skutečně ukončí.
- **Všechny appky v hubu teď defaultně startují na Stop** -- musíš
  aktivně zmáčknout Run.
- **Reorder hubu:** podržet ikonku appky → zvětší se a objeví se u ní
  ikonka šipek → klepni, kam ji chceš přesunout → appky se plynule
  prohodí. (Poznámka: implementováno jako "podrž → klepni kam", ne jako
  fyzické tažení prstem přes obrazovku -- spolehlivější bez možnosti to
  rovnou vyzkoušet na tvém zařízení. Pokud bys chtěl doopravdy drag
  prstem, dá se to udělat, ale bude to chtít víc kol ladění naživo.)

## v1.4 -- hub appek, Debug log v appce, oprava nekonzistentního vyhazování, nová animace

- **Oprava "vyhazuje mě někdy hned, někdy funguje":** appka teď 700ms po
  vstupu do Reels ignoruje scroll události -- Instagram totiž občas sám
  vygeneruje takovou událost, jen když se přehrávač usadí na obrazovce,
  což appka dřív mylně brala za tvůj swipe.
- **Debug log přímo v appce (Debug tab):** žádný adb/Termux potřeba.
  Appka si loguje do souboru, tab má tlačítka Refresh / Copy / Clear.
  Zkopíruj a pošli, když se něco pokazí.
- **Hub appek dole** (jako u TikToku/Instagramu): Instagram, TikTok,
  Snapchat. Instagram je plně funkční, ostatní dva zatím jen ukládají
  přepínač Run/Stop pro budoucí detekci (upřímně řečeno: bez
  implementace pro danou appku zatím nic neblokují ani nesledují --
  appka na to jasně upozorní žlutou poznámkou).
- **Nová animace při vyhození:** tmavý flash + bublina "↩ Zpět do
  feedu" uprostřed obrazovky, žádná namodralá barva.

## v1.3 -- overlay flicker/position/color fixes, 1-reel bug, exit animation

- **Overlay problikával:** teď se schová až po 3 po sobě jdoucích
  "nenalezeno" místo okamžitě při první chybějící snímku, a
  přepočítává pozici jen když se ikonka reálně posunula o víc než pár
  pixelů -- žádné zbytečné blikání.
- **Overlay byl níž, než měl:** accessibility overlay okna se u tohoto
  typu (`TYPE_ACCESSIBILITY_OVERLAY`) vykreslují posunutá o výšku
  status baru navíc oproti tomu, co vrací `getBoundsInScreen()` --
  teď se to o tuhle výšku koriguje.
- **Barva pořád špatně:** vzorkování pixelu přesunuto vedle ikonky
  (ne nad ni), kde je mnohem větší šance narazit na čisté pozadí lišty
  místo náhodného prvku.
- **Bug: appka nechávala projít 2 reely místo 1** -- "spotřebování"
  prvního reelu se počítalo až při prvním scrollu, ne hned při vstupu
  do přehrávače. Opraveno -- teď vyhodí hned na první swipe po prvním
  reelu.
- **Run/Stop:** aktivní stav se teď zvýrazňuje barvou tlačítka
  (zelená = běží, červená = zastaveno), text stavu bez závorek.
- **Animace při vyhození z Reels:** krátký barevný flash přes celou
  obrazovku (~350ms) v momentě, kdy appka přesměruje zpět do feedu.

## v1.2 -- nové rozhraní + statistiky

- Overlay čtvereček: rozvolněná kontrola velikosti/pozice (byla moc
  přísná a odmítala i správné nálezy) + logování odmítnutých kandidátů
  do logcatu pro snadnější ladění příště.
- Appka teď má dvě záložky: **Overview** (celkový počet zablokovaných
  reels, dnešní počet, graf posledních 7 dní) a **Setup** (schované
  instrukce a tlačítka na oprávnění).
- Run/Stop zůstává vždy nahoře, mimo záložky.
- Minimalistické tmavé rozhraní (#121212 pozadí, teal akcent).

## v1.1 -- opravy scrollování a overlaye

- **Zásadní příčina "nejde scrollovat feed/DMs":** Instagram zřejmě
  používá stejné resource ID (`clips_viewer_view_pager`) pro skutečný
  celoobrazovkový přehrávač i pro vloženou "Suggested reels" karuselovou
  sekci přímo ve feedu. Detekce teď navíc ověřuje, že nalezený prvek
  skutečně zabírá (skoro) celou obrazovku -- jinak to nepovažuje za
  "jsem v Reels" a nechá scroll/DM na pokoji.
- **Čtvereček přes ikonku:** přidána kontrola rozumné velikosti/pozice
  (musí být malý a v dolním pruhu obrazovky) -- pokud detekce najde něco
  podezřele velkého nebo jinde, overlay se radši vůbec nezobrazí, než
  aby omylem zakryl/zablokoval něco jiného.
- **Verze appky** je teď vidět v pravém horním rohu (`vX.Y`), čte se
  přímo z `versionName` v `app/build.gradle` -- při každé další úpravě
  kódu se bude zvyšovat, ať jde vždy poznat, jestli běží aktuální build.

## v1.0 -- předchozí opravy

- **Overlay zůstával viditelný i mimo Instagram:** konfigurace omezovala
  příjem událostí jen na Instagram (`android:packageNames`), takže
  appka se nikdy nedozvěděla, že jsi odešel jinam. Teď se sleduje
  packageName aktivní appky přímo v kódu, takže se overlay schová hned
  po přepnutí na cokoli jiného.
- **Barva čtverečku neseděla:** místo odhadu podle systémového
  světlý/tmavý režimu appka teď (na Androidu 11+) vezme skutečný
  screenshot obrazovky a vzorkuje barvu pixelu těsně vedle ikonky, takže
  se overlay barevně přizpůsobí i vlastnímu tématu Instagramu, ne jen
  systémovému.
- **Appka vyhazovala i ze scrollování feedu/DM:** rozpoznávání "jsem v
  Reels" bylo příliš benevolentní (padalo i na text "Suggested reels"
  vložený přímo do feedu). Teď se drží jen striktních resource ID
  skutečného celoobrazovkového přehrávače.
- **Místo "back" teď appka klikne na Home tab**, takže tě to vrátí do
  feedu místo rizika úplného opuštění Instagramu (což hrozilo, pokud byl
  přehrávač otevřený jako samostatná obrazovka bez back-stacku, např. z
  DM odkazu).

## How it works (short version)

Android's Accessibility API lets an app (with the user's explicit
permission) read the on-screen UI structure of other apps and simulate
button presses — that's the same mechanism screen readers and apps like
AppBlock/Opal use. This app only looks at Instagram's screens
(`android:packageNames="com.instagram.android"` in the service config)
and only ever performs one action: a "back" press when it thinks Reels
is on screen. It doesn't read messages, contacts, or anything outside
Instagram, and it doesn't send data anywhere.

## Limitations

- Detection is heuristic, not perfect — it can occasionally misfire or
  miss (e.g. if Instagram A/B-tests a new layout for some accounts).
- Xiaomi/HyperOS battery management can still kill the background
  service even with the settings above; if it stops working, check
  Settings → Apps → Reels Blocker → Battery again after any HyperOS
  update, since those settings sometimes reset.
- This only works on Android. iOS does not allow this kind of
  cross-app UI access without a jailbreak.
