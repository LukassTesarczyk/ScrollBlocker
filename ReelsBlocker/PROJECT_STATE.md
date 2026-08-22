# ScrollGuard (dříve "Reels Blocker") -- stav projektu (pro novou Claude Code session)

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

Appka se na telefonu (ikonka, název appky, Nastavení přístupnosti,
oznámení) jmenuje **ScrollGuard** -- do v1.28 se jmenovala "Reels
Blocker", přejmenováno ve v1.29. Balíček (`com.example.reelsblocker`),
GitHub repo (`scrollblocker`), podsložka s kódem (`ReelsBlocker/`) a
názvy tříd v Kotlin kódu (`ReelsAccessibilityService` atd.) se NEMĚNILY
-- jde čistě o zobrazovaný název, ne o technický rename.

## Jak appka funguje (princip)

Appka běží jako Android AccessibilityService -- systémová služba, která
smí číst obsah obrazovky ostatních appek (jen strukturu UI, ne
screenshoty ani citlivá data) a simulovat kliknutí/gesta. Díky tomu umí:

1. **Detekovat**, jaká appka/obrazovka je právě otevřená (Instagram
   Reels, TikTok For You, Instagram feed, DM, Stories...) podle
   resource-id prvků v UI stromu.
2. **Nechat projít jeden reel/video**, ale při dalším swipu uživatele
   přesměrovat pryč -- u Instagramu kliknutím na Home tab (zpět do
   feedu), u TikToku kliknutím na Inbox tab. Za swipe se počítá jen
   scroll samotného přehrávače/pageru (kontrola zdroje události, od
   v1.30) -- scrollování komentářů apod. blokování nespustí.
3. **Volitelně blokovat i scrollování feedu** (přepínač na Home záložce,
   jen Instagram, od v1.30, přepracováno v1.33, v1.34, v1.36, v1.37, v1.39 a v1.40): jakmile je
   uživatel na Home tabu s feedem a přepínač je zapnutý, appka zakrývá
   plochu neprůhledným overlayem (samostatné okno, ne to samé jako
   overlay na ikonce Reels) -- ale **okno je "click-through"
   (`FLAG_NOT_TOUCHABLE`), takže dotyky propadají do Instagramu pod ním a
   feed se pod blokem doopravdy scrolluje** (nic se necítí zaseklé).
   Velikost bloku se **plynule animuje (~160 ms, zrychleno v v1.37)** mezi
   dvěma stavy podle toho, jestli appka aktuálně vidí historky v UI stromu. Historky nemají
   žádné vlastní id (zjištěno z logu v v1.35/v1.36) -- appka je pozná
   strukturálně: **2+ prvků `outer_container` vedle sebe se stejnou
   horní hranicí v horní čtvrtině obrazovky** (`STORIES_ITEM_RESOURCE_ID`
   + `findStoriesTrayBounds`) -- jeden `outer_container` samotný se
   nepočítá (id je obecné, používá se i jinde v appce, např. na
   Reels-dismiss obrazovce).
   **Od v1.40 se hýbe jen HORNÍ hrana; dolní je vždycky horní okraj Home
   tab ikonky, takže blok nikdy nezakryje spodní lištu** (uživatel si ji
   výslovně přál nechat viditelnou -- a je to jediná cesta z feedu pryč,
   takže její zakrytí by uživatele uvěznilo; do v1.39 ji "celoobrazovkový"
   režim přejížděl):
   - Vidí historky (2+ `outer_container` v řadě) -> horní hrana = spodní
     okraj historek.
   - Nevidí historky (uživatel odscrolloval pryč, nebo strukturální shoda
     selhala) -> horní hrana = horní okraj displeje (0).
   Technicky je to jedno pevné okno přes **celou fyzickou obrazovku**
   (od v1.37 s `FLAG_LAYOUT_IN_SCREEN` + `FLAG_LAYOUT_NO_LIMITS` +
   `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`; bez nich okno začínalo až pod
   stavovou lištou, zatímco souřadnice z Instagramu jsou absolutní, takže
   byl celý blok posunutý o výšku stavové lišty dolů -- odtud nezakrytý
   proužek nahoře a vykukující spodní lišta). Uvnitř okna je jednoduchá
   `View` s plnou barvou přes celou plochu, která se animuje jen přes
   `translationY` + `scaleY` (`pivotY = 0`) -- žádný layout pass za
   snímek, takže je to plynulé; popisek je jejím sourozencem (ne
   potomkem), aby ho škálování nedeformovalo. Od v1.37 navíc **watchdog**
   (`feedOverlayRecheck`, každých 250 ms po dobu, co je blok vidět) sám
   ověřuje přes `rootInActiveWindow`, co je doopravdy na obrazovce, a
   schová blok hned, jak to přestane být feed -- dřív se schovával jen
   při další události z Instagramu, což ho někdy nechalo viset dlouho
   nebo napořád. **Od v1.39 hlídač potřebuje
   `FEED_OVERLAY_MISS_TOLERANCE` (3) neprůkazných čtení po sobě**, než
   něco schová: ve v1.37/v1.38 stačilo jedno čtení, kde root nepatřil
   Instagramu, a protože se systemui s Instagramem střídá pořád dokola
   (stačí letmá stavová lišta), hlídač blok zabíjel do 250 ms po každém
   zobrazení -- to byla nahlášená chyba "overlay se vůbec neukázal".
   Čtení, které pozitivně ukáže Instagram mimo feed, schovává hned jako
   dřív, takže rychlost mizení se nezhoršila. Každé schování se loguje
   i s důvodem ("Feed overlay hidden (...)") a zobrazení taky ("Feed
   overlay shown: top=... bottom=..."). Počítá se do statistik jedním započítáním při každém
   zobrazení bloku (ne opakovaně). **Od v1.40 se blok řídí přesně tou
   samou klasifikací jako štítek/statistiky** (`applyFeedOverlaySizing`
   volá `classifyScreen(root)`): do v1.39 měl vlastní zvláštní kontrolu
   "je Home tab `isSelected`?", což se s klasifikací mohlo rozejít --
   v logu z 2026-08-22 je úsek, kde Instagram nehlásil jako zvýrazněnou
   žádnou záložku, klasifikace tedy spadla na obsahový fallback a
   ukazovala FEED, ale blok se odmítal zobrazit ("ze začátku detekovalo,
   pak vůbec"). Starý mechanismus "počkej 5s po zmizení historek, pak tě příští
   scroll pošle nahoru" (v1.30) byl v1.33 nahrazen, ne doplněn.
4. **Zakrýt ikonku Reels** v Instagramu barevným overlayem (barva se
   vzorkuje ze skutečného screenshotu obrazovky, aby splynula s
   pozadím) -- vizuálně "schová" lákadlo.
5. **Sledovat čas strávený** v jednotlivých kategoriích obrazovek
   (Reels/Feed/DM/Stories/Other u Instagramu, Reels [zobrazeno jako
   "Videa"]/Other u TikToku) a kolikrát blokování zafungovalo -- pro
   statistiky v appce.

Stabilita služby (od v1.30): celá obsluha accessibility událostí je
zabalená v try/catch (jedna chyba dřív shodila celou službu -> stav
"Nefunguje" v nastavení přístupnosti) a záplavy content-change událostí
se vzorkují na max. 1 za 200 ms (přetížené hlavní vlákno byl druhý
kandidát na "Nefunguje"). Window-state a scroll události se zpracovávají
vždy.

**Klasifikace obrazovky Instagramu jede od v1.38 primárně podle
zvýrazněné (`isSelected`) záložky ve spodní liště** (`classifyScreen` ->
`findSelectedBottomTab` + `categoryForBottomTab`): svítící domeček =
FEED, vlaštovka = DM, Reels záložka = REELS atd. Důvod: Instagram drží
v UI stromu i podstromy *sousedních* záložek, jen s bounds odsunutými
mimo obrazovku, takže "existuje někde prvek typický pro feed" bylo
skoro vždycky pravda bez ohledu na to, co uživatel doopravdy vidí --
odtud nespolehlivá detekce feedu i starý bug "Inbox se hlásí jako FEED".
Zvýrazněná je vždy právě jedna záložka, takže je to jednoznačný signál.
**Celá lišta je změřená z logu 2026-08-22** (v1.40): `feed_tab`/Home ->
FEED, `clips_tab`/Reels -> REELS, `direct_tab`/Message -> DM,
`search_tab`/"Search and explore" -> OTHER, `profile_tab`/Profile ->
OTHER. Matching zůstává substringový (ne tabulka přesných id) schválně,
ať se přejmenovaná nebo přidaná záložka degraduje na log místo špatné
kategorie -- **nerozpoznaná záložka se nehádá, ale zaloguje**
("Selected bottom tab not mapped: id=... desc=...", pravidlo 5).

**Jak se appka k liště dostane (v1.39):** NE procházením stromu s
limitem hloubky -- to byla chyba v1.38, lišta Instagramu je hlouběji než
25 úrovní, takže se nenašla vůbec nic a klasifikace tiše propadala na
starý (nespolehlivý) fallback; poznávacím znamením je, že se v logu
nikdy neobjevilo "Selected bottom tab not mapped". Místo toho se
`findAccessibilityNodeInfosByViewId` (bez limitu hloubky) najde
**kotva** -- záložka se změřeným id (`BOTTOM_TAB_ANCHOR_IDS`:
`clips_tab`/`feed_tab`/...) -- z ní se jde `parent` na samotnou lištu a
přečtou se její děti = sousední záložky. `isSelectedWithin` bere v úvahu
i to, že highlight může sedět na ikonce uvnitř záložky, ne na kontejneru.
`logBottomTabsIfChanged` zapíše celou lištu (id/desc/selected) vždycky,
když se složení nebo výběr změní -- odtud půjde doplnit vlaštovku, lupu
a profil.

Celoobrazovkové případy (Reels přehrávač, historky, otevřená DM
konverzace) se řeší před záložkami, protože lištu překrývají -- a od
v1.38 se u nich (i u feed fallbacku) kontroluje, že prvek je **opravdu
vidět na obrazovce** (`hasVisibleNodeById`), ne že jen existuje v
odsunuté sousední záložce.

Zbytek detekce Instagramu je založený na resource-id změřených ze
skutečných logů (ne z dokumentace -- ta neexistuje). TikTok detekce je
založená na `viewpager` + `long_press_layout` (For You feed), ostatní id
jsou per-build obfuskovaná.

**TikTok detekce má dvě úrovně (od v1.29):** vstup do feedu vyžaduje
přísnou shodu (`viewpager` + `long_press_layout`), ale jakmile je session
jednou potvrzená, k setrvání ve feedu stačí samotné `viewpager` --
`long_press_layout` totiž podle logu z 2026-07-08 chybí u některých
videí (reklamy, videa s nálepkami), takže přísná podmínka na každém
eventu session resetovala dřív, než se stihlo zachytit skutečné swipnutí
pryč, a blokování tak fakticky nikdy nezafungovalo. Vstup zůstává
přísný schválně, ať se drafty/vlastní profil (mají `viewpager` bez
`long_press_layout`) nikdy nezačnou počítat jako feed.

## Podporované appky

- **Instagram** (`com.instagram.android`) -- plně implementováno:
  detekce Reels/Feed/DM/Stories, blokování, overlay na ikonce, time
  tracking.
- **TikTok** (`com.zhiliaoapp.musically`) -- implementováno od v1.27,
  detekce opravená v v1.29 (viz výše): detekce For You feedu, "1 video
  pak ven" blokování (exit do Inboxu), overlay na Home ikonce, time
  tracking (jen Reels/Other kategorie -- Reels se v UI přejmenovává na
  "Videa" pro TikTok kontext, Feed se u TikToku vůbec nezobrazuje,
  protože je vždycky nulový).
- **Snapchat** -- zatím jen UI (přepínač v hubu), žádná detekční logika.
  Appka na to sama upozorňuje textem u vybrané appky.

## UI appky

- **Nahoře vlevo šipka** otevírá boční vysouvací panel (drawer) se 4
  záložkami:
  - **Home** -- status (Running/Stopped), Run/Stop tlačítka, statistiky
    (Total blocked / Today, graf posledních 7 dní jako sloupce, graf
    "Time spent today" jako donut + legenda -- **resetuje se denně**,
    **každá appka má svoje vlastní statistiky** nezávisle na ostatních,
    **nulové kategorie se v legendě nezobrazují**).
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
  vypnutím blokování (Stop, Run/Stop v hubu, Shutdown, vypnutí blokování
  feedu). Vlastní tmavý dialog (ne systémový), sedí do designu appky,
  340dp široký (rozšířeno v v1.29, ať se text tlačítek vejde na jeden
  řádek).
- **Přepínač "Blokovat scrollování feedu"** (od v1.30) -- na Home
  záložce, viditelný jen pro Instagram, aktivní stav teal. Vypnutí chce
  PIN, zapnutí ne.
- **Dva home-screen widgety** (oba jen pro Instagram):
  1. Donut widget (přepracován v v1.31) -- celá plocha widgetu je graf:
     kolečko + legenda s rozpadem podle kategorií (nulové kategorie se
     neukazují). Široký tvar = kolečko vlevo, legenda vpravo; čtverec =
     legenda uvnitř kolečka. Každá instance se vykresluje ve své
     skutečné velikosti (onAppWidgetOptionsChanged -> okamžité
     překreslení po resize), bitmapa 2px/dp. Default 3x3, resize
     libovolně oběma směry (min 110dp).
  2. Bars widget -- sloupcový graf posledních 7 dní, stejný vzhled jako
     v appce. Default 4x3, jde zmenšit na 3x2.

## Design systém

Tmavý, minimalistický, plochý. Pozadí `#121212`, kartičky `#1A1A1A`,
akcentová barva teal `#26A69A`, destruktivní akce červená `#C62828`.
Sériový font všude (`serif` -- skutečný Times New Roman není součástí
Androidu). Detaily viz `CLAUDE.md` sekce "Design systém".

Barvy grafu kategorií (výraznější od v1.28): Reels/Videa fialová
`#A855F7`, DM zelená `#22C55E`, Feed modrá `#3B82F6`, Stories růžová
`#EC4899`, Other šedá `#70706C`.

## Historie verzí

Detailní changelog s odůvodněním každé verze je v `README.md` (nejnovější
nahoře) -- to je jediný trvalý záznam historie projektu, nikdy se
neškrtá. Aktuální verze appky je vidět v appce vpravo nahoře.

## Otevřené/rozdělané věci

- **Zdroj scrollu u komentářů pod reelem není potvrzený z logu (od
  v1.32).** Detekce swipu mezi reely bere jako platný swipe zdroj
  `clips_viewer_view_pager` NEBO `IgLazyColumn` -- to druhé je nový
  Compose přehrávač, přes který novější Instagram swipuje (změřeno z logu
  z 2026-07-11, kde se čtyři skutečné swipy zahazovaly jako "non-swipe
  view: IgLazyColumn" a blokování tiše nedělalo nic). Zbývající neznámá:
  jestli scrollování komentářů hlásí taky `IgLazyColumn` -- pokud ano,
  mohlo by se vrátit vyhazování z reelu při čtení komentářů. `isPagerScroll`
  proto pořád loguje každý zahozený zdroj ("Ignoring scroll from non-swipe
  view: ..."), takže z prvního logu, kde by k tomu došlo, půjde komentáře
  odfiltrovat cíleně. Bez logu se nehádá (pravidlo 5).
- ~~Id řádku s historkami (`stories_tray`) potvrzené jako špatné~~ --
  **vyřešeno v1.36.** Log z 2026-08-21 (`Top-of-screen dump` z v1.35)
  ukázal, že historky žádné vlastní id nemají -- appka teď detekuje
  strukturálně (2+ `outer_container` v řadě, viz bod 3 výše a
  `STORIES_ITEM_RESOURCE_ID`/`findStoriesTrayBounds`). Zatím
  nepotvrzené uživatelem naostro po v1.36 -- pokud by malý/velký blok
  pořád nefungoval podle očekávání, další log s `Top-of-screen dump`
  ukáže přesně proč (diagnostika z v1.35 zůstává jako fallback).
- ~~Inbox se hlásí jako FEED~~ -- **řešeno v1.38** přechodem na
  klasifikaci podle zvýrazněné spodní záložky (viz výše). Zatím ale
  neověřeno uživatelem naostro.
- ~~Záložky mimo domeček a Reels nejsou změřené~~ -- **změřeno v1.40.**
  Log z 2026-08-22 dal celou lištu: `feed_tab`/Home, `clips_tab`/Reels,
  `direct_tab`/Message, `search_tab`/"Search and explore",
  `profile_tab`/Profile. Všech pět padá do správné kategorie. Matching
  zůstává substringový (ne tabulka přesných id) schválně, ať se
  přejmenovaná/přidaná záložka degraduje na log místo špatné kategorie --
  "Selected bottom tab not mapped: ..." pořád existuje.
- **Debug badge nerozlišuje Profile / Search / Camera zvlášť** -- všechny
  spadají do OTHER (TimeCategory nemá pro ně vlastní kategorii a přidání
  by zasáhlo statistiky, widgety i překlady).
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
