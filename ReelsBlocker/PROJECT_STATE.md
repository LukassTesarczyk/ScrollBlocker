# ReelsBlocker -- stav projektu (pro novou Claude Code session)

Tenhle soubor se aktualizuje po každé dokončené verzi appky. Účel: dát
nové konverzaci (bez historie té staré) rychlý a úplný obrázek o tom, o
čem appka je, jak funguje a v jakém je stavu -- ať nemusí Lukáš znovu
vysvětlovat celý projekt od začátku.

Pravidla pro vývoj (konvence, design systém, co se nesmí měnit bez
důvodu) jsou v `CLAUDE.md` v rootu repa -- ten soubor se needituje bez
schválení. Tohle je doplňkový, čistě popisný soubor.

## Co appka je

Android appka (accessibility service), která pomáhá s digitálním
detoxem -- konkrétně proti nekonečnému scrollování krátkých videí
(Instagram Reels, TikTok For You feed). Autor a jediný uživatel je
Lukáš, appka je jeho osobní nástroj, ne appka pro širokou distribuci
(žádný Play Store, instaluje se ručně přes staženej `.apk` z GitHub
Actions).

Balíček: `com.example.reelsblocker`. Repo na GitHubu se jmenuje
`scrollblocker`, ale kód je v podsložce `ReelsBlocker/`.

## Jak appka funguje (princip)

Appka běží jako Android AccessibilityService -- systémová služba, která
smí číst obsah obrazovky ostatních appek (jen strukturu UI, ne
screenshoty ani citlivá data) a simulovat kliknutí/gesta. Díky tomu umí:

1. **Detekovat**, jaká appka/obrazovka je právě otevřená (Instagram
   Reels, TikTok For You, Instagram feed, DM, Stories...) podle
   resource-id prvků v UI stromu.
2. **Nechat projít jeden reel/video**, ale při dalším swipu uživatele
   přesměrovat pryč -- u Instagramu kliknutím na Home tab (zpět do
   feedu), u TikToku kliknutím na Inbox tab.
3. **Zakrýt ikonku Reels** v Instagramu barevným overlayem (barva se
   vzorkuje ze skutečného screenshotu obrazovky, aby splynula s
   pozadím) -- vizuálně "schová" lákadlo.
4. **Sledovat čas strávený** v jednotlivých kategoriích obrazovek
   (Reels/Feed/DM/Stories/Other u Instagramu, Feed/Other u TikToku) a
   kolikrát blokování zafungovalo -- pro statistiky v appce.

Detekce Instagramu je založená na resource-id změřených ze skutečných
logů (ne z dokumentace -- ta neexistuje). TikTok detekce je založená na
`viewpager` + `long_press_layout` (For You feed), ostatní id jsou
per-build obfuskovaná.

## Podporované appky

- **Instagram** (`com.instagram.android`) -- plně implementováno:
  detekce Reels/Feed/DM/Stories, blokování, overlay na ikonce, time
  tracking.
- **TikTok** (`com.zhiliaoapp.musically`) -- implementováno od v1.27:
  detekce For You feedu, "1 video pak ven" blokování (exit do Inboxu),
  overlay na Home ikonce, time tracking (jen Feed/Other kategorie,
  žádné DM/Stories jako u Instagramu).
- **Snapchat** -- zatím jen UI (přepínač v hubu), žádná detekční logika.
  Appka na to sama upozorňuje textem u vybrané appky.

## UI appky

- **Nahoře vlevo šipka** otevírá boční vysouvací panel (drawer) se 4
  záložkami:
  - **Home** -- status (Running/Stopped), Run/Stop tlačítka, statistiky
    (Total blocked / Today, graf posledních 7 dní jako sloupce, graf
    "Time spent today" jako donut + legenda -- **resetuje se denně**,
    **každá appka má svoje vlastní statistiky** nezávisle na ostatních).
  - **Settings** -- návod k nastavení (accessibility, battery, permissions),
    PIN lock sekce, Shutdown App tlačítko.
  - **Log** -- lokální debug log appky (žádný adb potřeba), jde
    zkopírovat nebo stáhnout jako `.txt`. Přepínač "Debug overlay" pro
    živý badge na obrazovce s aktuálně detekovanou kategorií.
  - **Languages** -- přepínač jazyka appky (~30 jazyků), nezávislý na
    systémovém jazyce telefonu.
- **Dole "hub"** appek (Instagram/TikTok/Snapchat) -- klepnutí přepne
  vybranou appku (a přepne i Home statistiky na tu appku), podržení
  otevře action sheet (Stats / Move / Run-Stop). "Move" = reorder
  klepnutím, ne fyzickým tažením.
- **PIN lock** (volitelný, 4-6 číslic) -- chrání před impulzivním
  vypnutím blokování (Stop, Run/Stop v hubu, Shutdown). Vlastní tmavý
  dialog (ne systémový), sedí do designu appky.
- **Dva home-screen widgety** (od v1.28, oba jen pro Instagram):
  1. Donut widget -- kolečko (time-spent graf) + kompaktní legenda
     vpravo, stejné barvy jako v appce.
  2. Bars widget -- sloupcový graf posledních 7 dní, stejný vzhled jako
     v appce.

## Design systém

Tmavý, minimalistický, plochý. Pozadí `#121212`, kartičky `#1A1A1A`,
akcentová barva teal `#26A69A`, destruktivní akce červená `#C62828`.
Sériový font všude (`serif` -- skutečný Times New Roman není součástí
Androidu). Detaily viz `CLAUDE.md` sekce "Design systém".

Barvy grafu kategorií (od v1.28, výraznější než dřív): Reels fialová
`#A855F7`, DM zelená `#22C55E`, Feed modrá `#3B82F6`, Stories růžová
`#EC4899`, Other šedá `#70706C`.

## Historie verzí

Detailní changelog s odůvodněním každé verze je v `README.md` (nejnovější
nahoře) -- to je jediný trvalý záznam historie projektu, nikdy se
neškrtá. Aktuální verze appky je vidět v appce vpravo nahoře.

## Otevřené/rozdělané věci

- **Inbox (seznam DM konverzací) se v grafu/badge chybně hlásí jako
  FEED**, ne DMS. Otevřená DM konverzace (thread) se detekuje správně
  (`thread_fragment_container`/`message_list`), ale samotný seznam
  konverzací (Inbox) žádné změřené id nemá -- Home tab pod ním zůstává
  "selected", takže to propadne do FEED větve. Přidané diagnostické
  logování (`classifyScreen`, v1.28) zachytí view-id na obrazovce,
  až se pošle čerstvý log z Debug tabu pořízený přímo na Inbox
  obrazovce -- pak půjde dodat přesnou podmínku. Bez logu se nehádá
  (CLAUDE.md pravidlo 5).
- **Debug badge zatím neumí rozlišit Profile / Videos / Camera** uvnitř
  Instagramu -- žádná id pro tyhle obrazovky nebyla nikdy změřena.
  Potřeba čerstvý log z každé z nich (s zapnutým Debug overlay), než se
  přidá klasifikace.
- TikTok a Snapchat: Snapchat nemá žádnou detekční logiku, jen UI.
- Reorder hubu je "Move v action sheetu -> klepni kam přesunout", ne
  fyzické tažení prstem.
- Overlay přes Reels/TikTok Home ikonku je heuristika nad cizím UI --
  může se kdykoli rozbít po update appky. Postup na dohledání nových
  resource id je v `README.md`.
- **Známá mezera:** swipe do Reels přímo z feedu/DM (ne přes tab) může
  appka v některých případech nezachytit -- čeká se na čerstvý log
  právě tohoto scénáře.

## Jak appku testuje Lukáš

Nemá přístup k počítači ani Android Studiu -- vše přes GitHub Actions.
Instaluje `.apk` na Xiaomi Redmi Note 14 Pro+ (HyperOS/MIUI). Debug
hlavně přes vestavěný Log tab v appce (ne adb logcat).
