# ScrollGuard (Android)

(Formerly "Reels Blocker" -- renamed in v1.29. Repo folder, package id and
class names are unchanged, only the name shown on the phone changed.)

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

## v1.30 -- blokování feedu, konec vyhazování z komentářů, stabilnější služba

Velká (a možná poslední velká) verze -- tři věci:

- **Nové: volitelné blokování feedu.** Na Home záložce (jen u Instagramu)
  přibyl přepínač "Blokovat scrollování feedu". Když je zapnutý, funguje
  to přesně podle zadání: appka pozná, že jsi ve feedu (domeček) a že
  zmizel řádek s historkami nahoře (= odscrolloval jsi dolů). Po 5
  vteřinách od zmizení historek tě další scroll vrátí na začátek feedu
  (klepnutím na domeček -- Instagram to bere jako "skoč nahoru", takže
  nikam nevyletíš z appky, jen se feed vrátí na vršek). Ukáže se pilulka
  "↑ Zpět na začátek" a počítá se to do statistik. Vypnutí přepínače
  chce PIN (když je PIN zapnutý), zapnutí ne -- stejná logika jako Stop.
  Pozn.: id řádku s historkami (`stories_tray`) je jediná věc v téhle
  verzi, která zatím není potvrzená z tvého logu -- appka loguje, kdy
  historky vidí/nevidí, takže kdyby to id nesedělo, pozná se to z logu
  (blokování by se "natahovalo" hned po vstupu do feedu) a půjde doladit.
- **Oprava: komentáře už tě nevyhodí z reelu.** Scrollování v komentářích
  (nebo v jakémkoli seznamu vykresleném přes přehrávač) posílalo úplně
  stejnou "scroll" událost jako swipe na další reel -- appka to brala
  jako "swipnul sis další reel" a vyhodila tě. Teď se u každého scrollu
  kontroluje, CO se vlastně scrollovalo: jen scroll samotného přehrávače
  (pageru) se počítá jako swipe. Platí pro Instagram i TikTok. Ignorované
  scrolly se logují i s id prvku, takže kdyby něco, z logu se pozná co.
- **Stabilita: proti stavu "Nefunguje" v nastavení přístupnosti.** Dvě
  příčiny, obě opravené: (1) jakákoli neošetřená chyba v obsluze událostí
  shodila celou službu -- teď je celá obsluha v ochranné síti a chyba se
  jen zapíše do logu; (2) Instagram při přehrávání sype desítky "změna
  obsahu" událostí za vteřinu a appka na každou dělala několik průchodů
  celým stromem obrazovky na hlavním vlákně -- přetížená služba je přesně
  to, co systém po chvíli označí "Nefunguje". Tyhle záplavy se teď
  vzorkují (max. 1 za 200 ms); události důležité pro blokování (přepnutí
  okna, scrolly) se zpracovávají vždycky hned. Kdyby se stav "Nefunguje"
  ještě někdy objevil, je to skoro jistě HyperOS killer -- pomáhá zámek
  v posledních aplikacích (bod 4 v návodu).

## v1.29 -- oprava TikToku podle logu, přejmenování na ScrollGuard, doladění widgetů

Reakce na tvůj test v1.28 + čerstvý TikTok log:

- **TikTok konečně opravdu blokuje.** Z logu bylo vidět, proč "vůbec
  nefungoval": appka požadovala, aby na obrazovce bylo současně
  `viewpager` I `long_press_layout`, jenže `long_press_layout` chybí u
  některých videí (reklamy, videa s nálepkami) -- appka to vyhodnotila
  jako "opustil jsi feed" během pár vteřin, session se pořád resetovala
  a k opravdovému blokování se to nikdy nedostalo. Teď appka rozlišuje
  vstup (přísná podmínka, aby drafty/vlastní profil zůstaly nedotčené) a
  setrvání ve feedu (stačí `viewpager`, který byl ve všech úsecích logu
  přítomný pořád) -- takže krátký výpadek `long_press_layout` už session
  nezruší.
- **Graf u TikToku má teď smysluplné popisky.** Čas u TikToku se teď
  počítá do stejné kategorie jako Instagram Reels (přejmenované u
  TikToku na "Videa"), a řádek "Feed" (u TikToku vždycky nulový a matoucí)
  se v grafu i legendě prostě nezobrazuje -- obecně teď mizí jakákoli
  nulová kategorie, ne jen u TikToku.
- **PIN dialog rozšířený**, ať se "Potvrdit" vejde na jeden řádek.
- **Widget s kolečkem byl zjednodušený** (kolečko + jedna hodnota času
  místo nabité tabulky, která se na menší velikost nevešla pořádně) a
  oba widgety teď jdou zmenšit: kolečkový widget je defaultně 3x3 a jde
  zmenšit až na 2x2, sloupcový graf jde zmenšit až na 3x2.
- **Ikonka appky mírně zmenšená** (prstenec zabírá méně místa v rámci
  bezpečné zóny).
- **Appka se teď jmenuje ScrollGuard** -- ve všech ~30 jazycích, v
  Nastavení přístupnosti (dřív "Reels Blocker Service", teď "ScrollGuard
  Service" -- po aktualizaci ho tak najdeš i tam) i v oznámení. Složka v
  repu (`ReelsBlocker/`), balíček appky a názvy tříd v kódu se neměnily,
  jde jen o jméno, které appka ukazuje na telefonu.

## v1.28 -- vlastní statistiky pro každou appku, dva widgety, hezčí PIN, TikTok do Inboxu

Reakce na tvůj test v1.27 -- postupně po bodech:

- **Graf "Kde trávíš čas" se teď resetuje každý den** (o půlnoci) a
  **Instagram a TikTok mají úplně oddělené statistiky** -- vlastní graf
  času i vlastní počet zablokování pro každou appku zvlášť, nemíchá se
  to dohromady. Přepneš appku v hubu dole a Home záložka ukáže její
  vlastní čísla.
- **Barvy grafu jsou teď výraznější** -- Reels sytě fialová, Feed sytě
  modrá, DMs sytě zelená, Historky sytě růžová (dřív to byly bledé
  pastelové odstíny).
- **TikTok se teď počítá do statistik** -- předtím se blokovalo, ale
  nikde se to nezapočítávalo. Teď má TikTok vlastní graf i počítadlo
  zablokování jako Instagram.
- **Widget je teď dva widgety, ne jeden.** První je kolečko (donut graf)
  s časy vedle něj, druhý je sloupcový graf posledních 7 dní -- oba
  vypadají stejně jako v appce. Přidáš je zvlášť (podrž prst na ploše ->
  Widgety -> Reels Blocker -> vyber, který chceš).
- **PIN dialog má teď tmavý design appky**, ne systémové bílé okno se
  starým Androidem -- tmavá kartička, zaoblený vstup na číslice, tlačítka
  ve stylu appky (Zrušit / Potvrdit).
- **TikTok tě po vyhození pošle do Inboxu**, ne na náhodnou obrazovku
  "zpět" jako dřív (to mohlo skončit kdekoliv, klidně zase ve videu).
  Zkouší najít Inbox záložku stejně, jako appka hledá Home ikonku --
  když by to Inbox nenašlo, spadne to zpátky na obyčejné "zpět".
- **Žlutá poznámka "detekce zatím neexistuje" u TikToku zmizela** --
  appka teď TikTok bere jako plně implementovanou appku (stejně jako
  Instagram), takže se ta poznámka logicky ukazuje už jen u Snapchatu,
  který opravdu ještě nic nedělá.
- **Ikonka appky je nový obrázek** (`image-1.jpg` z rootu repa) --
  zelený prstenec na tmavě modrém pozadí, zpracovaný stejným postupem
  jako předchozí ikonka (ne ruční překreslení).
- **Inbox (seznam zpráv) se pořád někdy hlásí jako Feed, ne DMs** --
  tohle se bez tvého čerstvého logu nedalo bezpečně opravit (pravidlo
  č. 5 v CLAUDE.md -- neházet se do detekce Instagramu naslepo). Appka
  teď potichu loguje, co přesně na té obrazovce vidí, takže stačí
  poslat log pořízený přímo na obrazovce se seznamem zpráv (se
  zapnutým Debug overlay v Log tabu) a příště to půjde doladit přesně.
  Stejná prosba platí i pro rozlišení Profilu / Videí / Fotoaparátu v
  debug štítku -- zatím pro ně nemám žádná data.

## v1.27 -- graf z naměřených dat, widget, PIN, TikTok blokování

Velká verze -- všechno v ní vychází z logů, cos poslal (žádné hádání):

- **Graf "Kde trávíš čas" konečně měří doopravdy.** Z reconu jsem přečetl
  skutečná ID: DMs (`thread_fragment_container`, `message_list`),
  Historky (`reel_viewer_root`), feed (`row_feed_*`). Nově má graf i
  kategorii **Reels** (fialová) -- čas strávený v Reels přehrávači se
  počítá zvlášť. DMs už nebudou na nule.
- **Debug štítek teď ukazuje, co appka právě detekuje:** REELS / FEED /
  DMs / STORY / IG · other / TIKTOK feed -- každá kategorie svojí barvou.
  Na nahrávkách obrazovky bude okamžitě vidět, jestli appka vidí totéž
  co ty.
- **Widget na plochu** -- dnešní počet zablokování, celkový počet a
  prstencový graf času (stejné barvy jako v appce). Klepnutí na widget
  otevře appku. Najdeš ho v launcheru mezi widgety (podrž prst na ploše
  -> Widgety -> Reels Blocker). Aktualizuje se sám.
- **PIN zámek** -- v Nastavení, které je teď rozdělené do sekcí (**Návod /
  Oprávnění / PIN zámek**). Po zapnutí PINu (4-6 číslic) vyžaduje
  vypnutí blokování (Stop, Run/Stop v hubu i Vypnout aplikaci) nejdřív
  zadat PIN. Pojistka pro chvíle slabé vůle -- není to trezor, jen
  zpomalovací práh.
- **TikTok se teď blokuje jako Instagram.** Z TikTok logu: skoro všechna
  jeho ID jsou zamlžená (mění se s každou verzí -- `be1`, `hpk`...), ale
  dvě jsou stabilní: `viewpager` + `long_press_layout` = For You
  přehrávač. Takže: jedno video se dokouká, další swipe tě vyhodí
  (tlačítkem zpět -- TikTok nemá změřené bezpečné místo, kam kliknout).
  Ikonku domečku dole zakrývá čtvereček (hledá se podle popisku "Home",
  protože ID jsou zamlžená). **Drafty a vlastní profil zůstávají
  přístupné** -- ty obrazovky v logu `long_press_layout` nemají, takže je
  appka nebere jako feed. Zapíná se v hubu (TikTok -> Run).
- Pozn.: zablokovaná TikTok videa se zatím nepočítají do statistik/grafu
  (ty jsou zatím čistě instagramové) -- můžu doplnit příště, kdyžtak řekni.

## v1.26 -- Shutdown místo restartu, větší ikonka oznámení, příprava na TikTok

- **Tlačítko Shutdown App v bočním menu.** Restart tlačítko z Nastavení
  je pryč -- místo něj je úplně dole v bočním menu (pod Home / Settings /
  Log / Languages) červené "Vypnout aplikaci". Po klepnutí appka vypne
  accessibility službu (jediný způsob, jak systému zabránit, aby ji na
  pozadí okamžitě znovu nastartoval), zruší oznámení a kompletně ukončí
  všechny svoje procesy. **Důležité: po vypnutí je potřeba službu znovu
  zapnout ručně v Nastavení -> Přístupnost, než appka zase začne
  blokovat** -- to je nevyhnutelná daň za skutečně kompletní vypnutí,
  ne opomenutí.
- **Větší ikonka v oznámení.** Mozek v stavové liště teď vyplňuje skoro
  celou povolenou plochu (velikost samotné ikonky ve stavové liště
  omezuje Android, víc než ~24dp nedovolí nikomu) a v rozbaleném
  oznámení navíc přibyla velká barevná ikonka mozku vpravo.
- **Graf "Kde trávíš čas" a DMs na nule:** appka zatím neumí DM obrazovku
  poznat -- nemá ověřené ID (proto čas z DMs padá do "Ostatní", ne že by
  se ztrácel). Přidal jsem záznam, který si při pobytu na nerozpoznané
  obrazovce (DMs, historky, hledání...) zapíše, jaká ID tam skutečně
  jsou (max 1× za 5 vteřin, jen při změně obrazovky) -- z příštího logu
  po chvíli brouzdání v DMs už detekci postavím najisto.
- **Příprava na TikTok:** když v hubu zapneš TikTok na Run a budeš ho
  chvíli používat, appka si stejným způsobem zapíše, z čeho se jeho
  obrazovky skládají -- z toho pak postavím skutečnou detekci (For You
  feed atd.), stejně jako kdysi u Instagramu, ale rovnou z dat.

## v1.25 -- diagnostika pro poslední zbylý zdroj problémů

Nejdřív dobrá zpráva: oprava úniku paměti z v1.24 drží -- v novém logu
už není ten "vyhazuje skoro pořád hned" vzorec, první reel se ve většině
případů odkoukal celý (jednou dokonce 15 vteřin), přesně jak má být.

Co v logu pořád je: **appka opakovaně nenajde tlačítko Home dole v
Instagramu** ("Home tab not found -- falling back to back button", 3×
v jednom logu) a musí sáhnout po záložním tlačítku zpět, které tě
občas vrátí ne na feed, ale rovnou do dalšího reelu -- a to pak vypadá
jako "vyhodilo mě to, i když nemělo".

**Proč to rovnou natvrdo neopravím:** appka teď hledá tlačítko Home
podle dvou konkrétních ID (`feed_tab`, `home_tab`), co jsou z dřívějška
odhadnutá z chování v logu -- ne z oficiální dokumentace Instagramu
(žádná neexistuje). Pokud tahle ID přestala sedět (Instagram si mezi
verzemi mění vnitřní názvy), jediný poctivý způsob, jak najít ty
správné, je podívat se, co tam ve skutečnosti je -- ne hádat další ID
naslepo a riskovat, že pokazím i tu část, co teď funguje.

Přidal jsem tedy log, který přesně tohle udělá: pokaždé, když appka
tlačítko Home nenajde, si zapíše, jaká klikatelná tlačítka skutečně
jsou dole na obrazovce (jejich ID, popisek, souřadnice). Až mi pošleš
další log z chvíle, kdy se tohle stane, uvidím černé na bílém, jak se
tlačítko Home doopravdy jmenuje, a opravím to najisto -- ne naslepo.

Chápu, že chceš appku dotaženou k dokonalosti -- shoduju se s tebou, že
tohle je poslední velký kus skládačky. Nejde to ale uhodnout, jde to
jen změřit.

## v1.24 -- oprava mojí vlastní chyby z v1.23 (únik paměti)

Měl jsi pravdu, v1.23 bylo skutečně horší -- a tentokrát to byla moje
chyba, ne další skrytá vlastnost HyperOS.

- **Co se stalo:** nová kontrola z v1.23 ("zeptej se systému naživo,
  jestli jsi fakt pořád v Instagramu") si při každém spuštění vyžádala
  od systému kousek paměti (referenci na aktuální obrazovku), ale já
  jsem zapomněl appce říct, ať tu referenci zase uvolní. Při normálním
  používání appka tohle dělá při každém přepnutí pryč z Instagramu --
  takže se to opakovalo pořád dokola, únik se hromadil, až appce došla
  interní kapacita a začala se chovat nevyzpytatelně i v úplně jiných
  částech kódu (třeba při hledání, jestli jsi v Reels) -- což se
  projevilo přesně jako "vyhazuje skoro pořád hned", protože appka si
  najednou nebyla jistá vůbec ničím.
- **Oprava:** appka si teď tu referenci po použití pořádně uklidí, jak
  má. Samotná myšlenka z v1.23 (obecná kontrola místo honění
  jednotlivých viníků) zůstává -- jen bez chyby, co ji doprovázela.

Díky za rychlou zpětnou vazbu, tohle přesně ukazuje, proč je dobré
testovat po každé verzi znovu, ne jen předpokládat, že novější je vždycky
lepší.

## v1.23 -- jedna obecná pojistka místo honění jednotlivých viníků

Psal jsi, že už to funguje líp, ale pár× se pořád objevilo "not IG" i
když jsi byl v Instagramu -- v novém logu se ukázalo, že oprava klávesnice
z v1.22 jednou neproběhla (kontrola přes seznam oken je momentka, co se
může minout s událostí, která ji vyvolala) -- tentokrát to trvalo jen 2,8
vteřiny místo 23, ale pořád se to stalo.

**Místo honění dalšího konkrétního viníka (co bude příště? jiná appka?
jiný typ okna?) jsem to teď vyřešil obecně:** kdykoliv appka usoudí, že
opouštíš Instagram, se předtím ještě jednou zeptá systému naživo "jsi
fakt pořád v Instagramu?" (stejný trik, co appka od v1.19 používá jinde).
Pokud systém řekne, že Instagram je pořád aktivní, appka to přechodné
hlášení ignoruje a zůstává u toho, že jsi v Instagramu. Tohle by mělo
zachytit úplně cokoliv -- klávesnici, systémové komponenty HyperOS, i
věci, co jsem ještě neviděl -- bez toho, abych musel čekat na další log
a další konkrétní opravu.

Řešení je záměrně **stejný, už jednou ověřený princip** jako oprava z
v1.19, jen použitý na dalším místě -- ne nový mechanismus, který by mohl
něco jinýho pokazit.

Na to druhé, vyhození z Reels bez scrollnutí, jsem v tomhle logu nenašel
jednoznačný důkaz nové chyby (spíš to vypadá na stejné krátké výpadky
detekce, co řeší oprava výše) -- pokud se to bude dít dál i po týhle
verzi, pošli prosím log s debug štítkem zapnutým, ať vidím přesně, co se
děje v tu chvíli.

## v1.22 -- stejný bug, tentokrát u klávesnice (opraveno obecně)

Nejnovější log ukázal úplně stejnou třídu chyby jako v1.21, ale tentokrát
kolem klávesnice: `com.google.android.inputmethod.latin` (Gboard) jednou
vyvolala skutečné přepnutí okna (typicky když píšeš komentář/popisek/
hledáš), appka to vzala jako "teď je aktivní klávesnice", a zůstala v
tom přesvědčení **23 vteřin** i po zavření klávesnice -- zavření
klávesnice zjevně nevyvolá spolehlivě odpovídající přepnutí zpátky na
appku pod ní.

- **Tentokrát jsem to neopravil natvrdo jménem balíčku** (jako u
  `miui.systemui.plugin`), protože klávesnice se liší telefon od
  telefonu (Gboard, Mi klávesnice, SwiftKey...) -- to by opravilo jen
  tenhle jeden případ. Appka se teď místo toho ptá systému přímo, jestli
  je okno, co si o sobě myslí, že je "aktivní", doopravdy klávesnice
  (`AccessibilityWindowInfo.TYPE_INPUT_METHOD`) -- a pokud ano, appka to
  ignoruje úplně, ať už to hlásí jakákoliv appka. Mělo by to fungovat
  bez ohledu na to, jakou klávesnici máš nastavenou.
- Mrkl jsem taky na ten jeden reel, kde tě to vyhodilo až po (podle tebe)
  třech scrollech -- v logu je vidět 7 vteřin ticha mezi posledním
  ignorovaným "usazovacím" pohybem a skutečným swipnutím, což vypadá, že
  jsi ten reel prostě sledoval o něco déle, než jsi swipnul dál -- appka
  na to správně počkala. Pokud se ti to bude dít dál a bude to vypadat
  jinak, klidně pošli log s debug štítkem zapnutým.

## v1.21 -- konečná oprava "not IG" i přes to, že jsi byl v Reels

Log z v1.20 (díky novému štítku i bez něj) ukázal přesně to, co jsi
popsal -- appka na 33 vteřin v kuse hlásila "not IG", i když jsi
prokazatelně pořád seděl v Reels.

- **Příčina:** `miui.systemui.plugin` (systémová komponenta HyperOS) v
  logu jednou vyvolala *doopravdy skutečné* přepnutí okna (ne jen
  "šum" z jiných typů událostí, jak jsem si myslel u opravy v v1.15) --
  appka to tedy oprávněně vzala jako "uživatel odešel z Instagramu" a
  zůstala v tomhle přesvědčení 33 vteřin, dokud nepřišla další skutečná
  změna. Po celou tu dobu appka Reels vůbec nesledovala.
- **Tohle vysvětluje obě věci, co sis myslel, že si odporují:** "vyhodilo
  mě to hned" (detekce se zrovna vzpamatovala a doběhla normální
  toleranci) i "nevyhodilo mě to vůbec" (appka celou dobu myslela, že
  není v Instagramu, takže limit "1 reel" neměl šanci se vůbec spustit)
  -- je to jeden a tentýž bug.
- **Oprava:** appka teď `miui.systemui.plugin` ignoruje úplně, ve všech
  případech -- nikdy ho nebere jako důkaz, že jsi odešel z Instagramu,
  bez ohledu na to, jaký typ události to nahlásí. Je to čistě systémová
  komponenta HyperOS, nikdy ne appka, kterou bys skutečně používal.

## v1.20 -- živý debug štítek na obrazovce (nový přístup k ladění)

Poslední log měl navíc appku `com.miui.screenrecorder` prolínající se s
Instagramem stovky×, protože jsi nahrával obrazovku -- to appce dost
znepřehlednilo signál a je docela možné, že samotné nahrávání
(výkonová zátěž navíc) přispělo k tomu, že appka na chvíli minula
ikonku Reels vícekrát za sebou. Zvedl jsem toleranci na tyhle výpadky
(2 → 4 mise po sobě, než appka usoudí, že jsi opravdu odešel).

Ale hlavně -- **nová věc, ne jen další záplata na to samé.** Doteď jsem
se musel dohadovat, co se dělo, ze suchého textového logu s
milisekundovými časy, což je pomalé a nejednoznačné. Přidal jsem
**živý debugovací štítek přímo na obrazovku** (Debug tab -> tlačítko
"Debug overlay: OFF/ON"), malý černý štítek v levém horním rohu, co v
reálném čase ukazuje, co appka právě vidí: jestli jsi v Instagramu,
jestli má nalezenou ikonku Reels, jestli jsi v Reels přehrávači, kdy tě
appka vyhodila atd. -- barevně (zeleně = OK, oranžově = nenalezeno,
červeně = vyhozeno).

**Až budeš příště nahrávat obrazovku kvůli bugu, zapni si tenhle štítek
předtím** -- uvidím přesně, co appka dělala v každém okamžiku videa,
místo abych to zpětně dolovat z logu. Mělo by to hodně zrychlit, jak
rychle se příští bug najde a opraví.

## v1.19 -- oprava "appka na chvíli vypadla" (čtvereček i detekce)

Log ukázal přesně to, co jsi popsal ("uprostřed používání to vypadlo
třeba na minutu") -- asi 80 vteřin appka viděla desítky přepínání mezi
Instagramem a systémovou lištou, ale žádné z nich nebylo skutečné
přepnutí okna (appka si pořád myslela, že je pořád v Instagramu), a
přesto se po celou tu dobu nenašla ikonka záložky ani se nic
nedetekovalo -- čtvereček zůstal schovaný, dokud nepřišlo první
opravdové přepnutí appky, které to zase srovnalo.

- **Příčina:** appka se ptá systému "co je teď skutečně na obrazovce"
  (`rootInActiveWindow`) při každé události, ale při krátkém probliknutí
  stavové lišty (typické při sledování něčeho na celou obrazovku) systém
  na okamžik vrátí obsah stavové lišty místo Instagramu -- aniž by to
  appka zaregistrovala jako "opustil jsi Instagram" (protože to není
  skutečné přepnutí okna). Appka pak hledala ikonku Reels ve špatném
  obsahu, nic nenašla, a čekala, až přijde další skutečné přepnutí, aby
  se to opravilo -- což mohlo trvat klidně minutu.
- **Oprava:** appka si teď při každé události ověří, že to, co skutečně
  vidí na obrazovce, je opravdu Instagram, než se do toho pustí. Pokud
  ne (jen to krátké probliknutí), tu jednu událost přeskočí a nic
  nemění -- ani neschovává čtvereček, ani neresetuje sezení -- protože
  Instagram je pořád reálně v popředí, jen se to na okamžik odchýlilo.
  Příště by se to mělo srovnat prakticky okamžitě, ne až za minutu.

## v1.18 -- storky se přestanou plést s Reels, delší tolerance na první video

Nový log (díky opravě z v1.17 konečně obsahoval to důležité) ukázal dvě
konkrétní věci:

- **`reel_viewer_root` (jedno ze dvou ID, podle kterých appka poznávala
  "jsem v Reels") se v logu pokaždé objevilo těsně po tom, co zmizela
  ikonka dolní lišty -- což platí pro JAKÝKOLI celoobrazovkový obsah,
  včetně Historek. Instagram měl Historky interně pojmenované "Reel" ještě
  předtím, než vznikla dnešní (TikToku podobná) funkce Reels, která
  používá jiné interní názvy ("clips_*") -- proto appka dosud omylem
  reagovala na Historky stejně jako na Reels. Odebral jsem `reel_viewer_root`
  z detekce úplně, zůstává jen `clips_viewer_view_pager` (skutečná
  Reels záložka). Vedlejší dopad: reel vložený přímo do feedu nebo sdílený
  v DM (což taky používalo tohle ID) appka teď nezastaví -- to je zatím
  přijatelná cena za to, že tě to přestane vyhazovat z historek.
- **Appka někdy vyhodnotila jako "druhé swipnutí" ještě doznívající
  posun z toho, jak ses do Reels teprve dostal** -- log ukázal, že tohle
  doznívání jednou trvalo 683 ms, těsně pod tehdejším limitem 700 ms, a
  hned další pohyb appka vzala jako tvoje druhé swipnutí a vyhodila tě,
  i když jsi první video ještě nedokoukal. Zvedl jsem limit na 1200 ms.
- **Tlačítko "Otevřít nastavení baterie" teď žádá o výjimku z omezování
  na pozadí rovnou jedním potvrzením** (systémový dialog), místo aby tě
  posílalo do App Info hledat "Bez omezení" ručně o pár menu dál. Log
  ukázal, že appka se v půlce session sama znovu připojovala ("Service
  connected" podruhé) -- to je HyperOS, co appku na pozadí zabil; tohle by
  to mělo omezit, i když stoprocentní záruka to není (je to chování
  HyperOS, ne appky) -- pomůže i zamknutí appky v Recent apps (viz návod
  v appce).

## v1.17 -- opravený skutečný důvod, proč log "nefungoval"

Napsal jsi, že jsi obě věci (návrat do Instagramu po minimalizaci appky i
swipe z DMs do Reels) udělal, ale log to nezachytil -- to byla stopa k
dalšímu skutečnému bugu, ne že bys něco udělal špatně.

- **Log si zapisoval úplně KAŽDÝ přechod mezi appkama, celý den, ne jen
  během testování.** Appka nemá (záměrně) filtr na balíček, takže
  zaznamenává i to, když si zkontroluješ Discord, otevřeš klávesnici,
  přepneš na appku Claude atd. V logu, cos poslal, tvořily tyhle
  irelevantní přechody (klávesnice, launcher, appka Claude, výběr
  souborů) přes 90 % všech řádků. Log má strop 200 KB, po jeho
  překročení appka nejstarší polovinu zahodí -- takže i pár minut
  běžného používání telefonu dokázalo z logu vytlačit přesně tu část, na
  kterou jsem se ptal, dřív než jsi stihl log vyexportovat. Tohle
  vysvětluje, proč to "nefungovalo", i když jsi obě věci reálně udělal.
- **Oprava:** appka teď zapisuje do logu jen přechody, který se týkaj
  Instagramu (vstup/výstup) -- ne každý skok mezi appkama, co s
  Instagramem nemaj nic společnýho. Zvedl jsem taky strop logu z 200 KB
  na 500 KB pro rezervu.
- Nemusíš teď dělat nic extra navíc -- stačí normálně vyzkoušet: Instagram
  → Reels → minimalizovat appku ReelsBlocker → zpátky do Instagramu →
  Reels znovu → DM vlákno → swipe do Reels → historka → export logu z
  appky. Log teď bude mnohem menší a mnohem víc relevantní.

## v1.16 -- restart tlačítko natvrdo viditelné, přesný čas na vteřiny

- **Restart App tlačítko jde teď vidět vždycky, bez scrollování.** Prohlédl
  jsem si fotku, cos poslal (zesvětlil a zkontroloval pixely v místě, kam
  ukazuje šipka) -- tam skutečně nic nebylo, appka v1.15 měla tlačítko
  správně v kódu, ale sedělo úplně dole ve scrollovatelném obsahu Nastavení
  tabu (pod dlouhým textem návodu + 3 tlačítky), takže bylo potřeba
  doscrollovat. Radši než to nechávat na scrollování, přesunul jsem
  tlačítko úplně mimo scrollovací oblast -- teď je natvrdo připíchnuté
  hned nad spodní lištou appek, viditelné okamžitě, jakmile otevřeš
  Nastavení, bez ohledu na to, jak dlouhý je návod.
- **Log potvrdil, že oprava blbnutí detekce z v1.15 fakt funguje.** V logu
  je vidět přesně ten scénář, co jsi popsal -- minimalizace appky (swipe
  domů) vygenerovala desítky přepínání mezi Instagramem a launcherem
  během jedné animace, a appka to teď správně vyhodnotila jako JEDNU
  změnu popředí, ne desítky. To přesně dokazuje, že v1.15 oprava dělá, co
  má.
- **Co ale log nepotvrdil ani nevyvrátil:** log ukazuje, že jsi po
  minimalizaci appky (ReelsBlocker) přešel na jiné appky (Claude, klávesnice,
  výběr souboru) a do Instagramu ses už nevrátil -- takže v logu není nic,
  z čeho bych poznal, jestli detekce po opětovném otevření Instagramu
  fungovala nebo ne. Stejně tak log neobsahuje moment, kdy bys swipnul z
  DMs do Reels. Potřebuju k tomu ještě jeden, cíleně zaměřený log -- viz
  níž.
- **Graf "Kde trávíš čas" teď počítá na vteřiny**, ne jen na celé minuty --
  krátké testovací session dřív ukazovaly "0m" u všeho, což vypadalo jako
  že graf vůbec nefunguje. Teď se zobrazí třeba "42s" nebo "3m 12s".
- DMs/Historky v grafu pořád čekají na resource ID z logu (viz v1.15).

**Prosím o jeden konkrétní, krátký log přesně v tomhle pořadí, ať
konečně mám z čeho:** otevři Instagram → jdi do Reels, ověř že blokuje →
**minimalizuj appku ReelsBlocker (ne Instagram) → počkej pár vteřin → vrať
se zpátky do Instagramu a zkus Reels znovu** → pak otevři DM vlákno →
swipni stranou do Reels → podívej se na něčí historku → pak mi rovnou z
appky exportuj Log. Nemusí to být dlouhé, hlavně ať to obsahuje přesně
tyhle kroky za sebou.

## v1.15 -- skutečná příčina rozbité detekce (podruhé), spolehlivé updaty

Log, cos poslal, ukázal přesně to, co bylo za tím vším: `miui.systemui.plugin`
(systémová komponenta HyperOS) se v běhu proplétala s `com.instagram.android`
i desítkykrát za vteřinu, a appka to brala jako "uživatel opustil Instagram" --
což vynulovalo stav sezení v Reels. Tohle je stejná třída bugu, co v1.10
opravovala pro vlastní balíček appky a v1.11 pro prázdný balíček -- tentokrát
to ale bylo přes systémovou komponentu HyperOS, kterou appka neměla důvod
podezřívat.

- **Skutečná oprava:** appka teď nebere "jaký balíček to poslal" z každé
  jednotlivé události (scroll, změna obsahu, cokoliv), ale sleduje jen
  události typu "skutečně se přepnulo okno" (`TYPE_WINDOW_STATE_CHANGED`).
  Krátké systémové probliknutí (hlasitost, gesta, cokoliv jiného od HyperOS)
  se tak už nebere jako "opustil jsi Instagram".
- **Tohle by mělo vysvětlit úplně všechno, co jsi popsal:** nekonzistentní
  počet povolených reels (stav se resetoval a znova povolil "1 reel" pořád
  dokola), blikání/mizení čtverečku, to, že v1.14 (kratší doba mizení)
  najednou vypadalo hůř než v1.13 -- kratší tolerance na blbnutí, co už
  předtím bylo skryté, ho jen zviditelnila. **A nejspíš i swipe z DMs do
  Reels, co pořád obcházel blokování** -- pokud se stav resetoval pořád
  dokola, appka se nikdy nedostala k bodu "swipnul jsi přes první reel,
  konec". Uvidíme z dalšího logu, jestli tohle bylo opravdu to celé.
- **Restart App tlačítko a obecně "proč zase nevidím novou verzi":** appka
  se dosud na GitHub Actions podepisovala pokaždé jiným náhodným debug
  klíčem (CI runner nemá trvalé úložiště pro `~/.android/debug.keystore`,
  takže si ho Gradle při každém běhu vygeneroval znova). Android ale
  odmítne nainstalovat aktualizaci podepsanou jiným klíčem, než měla
  předchozí verze, dokud appku nejdřív neodinstaluješ -- takže tvoje
  předchozí instalace klidně mohly tiše selhávat a ty jsi pořád koukal na
  starou verzi bez restart tlačítka. Přidal jsem do repa pevný debug klíč,
  který se bude používat na každém buildu od teď, takže by měly jít
  instalovat načisto přes sebe.
  **Jednorázově ale teď appku z telefonu odinstaluj ručně, než nainstaluješ
  tenhle build** -- staré instalace mají ještě starý (náhodný) klíč, takže
  první přechod na pevný klíč se sám neobejde bez odinstalace. Od příští
  verze už by to mělo jít bez toho.
- **Přidal jsem diagnostický log** (jaké resource ID přesně odpovídalo při
  vstupu do Reels), aby příští log konečně obsahoval dost informací na to,
  jestli si appka plete Historky s Reels kvůli sdílenému ID -- bez
  hádání, jen čtu, co appka už teď najde.
- **Graf "Kde trávíš čas" pořád ukazuje DMs/Historky jako 0** -- na to
  pořád nemám ověřená resource ID, viz předchozí verze. Napiš prosím
  ještě jeden log v tomhle pořadí: otevři DM vlákno, swipni stranou do
  Reels, podívej se na historku -- ať mám z čeho vytáhnout ID pro obojí
  najednou.

## v1.14 -- skutečná ikonka z fotky, graf "kde trávíš čas", spolehlivější restart

- **Ikonka appky je teď opravdu z fotky, cos poslal.** Minule jsem si ji
  musel ručně překreslit (obrázek vložený přímo do chatu se nedal stáhnout
  jako soubor), což z toho udělalo trochu dětskou kopii. Tentokrát jsi
  fotku (`Icon.jpg`) nahrál rovnou do GitHub repa, takže jsem z ní
  automaticky vyřezal fialový mozek (odstranění pozadí, vystředění,
  zmenšení na bezpečnou zónu adaptivní ikonky) a použil přímo tu -- žádné
  ruční kreslení, je to 1:1 stejný motiv jako na fotce.
- **Tlačítko "Restart App" teď skutečně funguje.** Předtím appka
  naplánovala restart přes systémový alarm a hned se ukončila -- na
  HyperOS/MIUI ale agresivní správa baterie takový alarm pro už mrtvý
  proces často zahodí, takže appka zmizela a už se sama neotevřela.
  Restart teď spustí nové okno appky rovnou, ještě než se starý proces
  ukončí, takže na žádný naplánovaný alarm nezávisí.
- **Notifikace o aktivním blokování má teď ikonku appky** (fialový mozek)
  místo obecné systémové ikonky.
- **Čtvereček přes ikonku Reels teď mizí prakticky okamžitě** místo aby
  ještě chvíli doznívalo, když třeba přepneš do DMs -- zrychlil jsem i
  krátkou toleranci, po které appka uzná, že ikonka Reels na obrazovce
  fakt není.
- **Nový graf "Kde trávíš čas" na Home tabu**, pod grafem posledních 7
  dní -- prstencový (ne výsečový) graf s barevnými úseky podle toho, kde
  v Instagramu trávíš čas, plus rozpis časů pod ním. Feed appka pozná
  spolehlivě (stejná ikonka dolní lišty, kterou už roky používá na
  klepnutí "zpět na feed", teď jen čte, jestli je zrovna aktivní).
  **DMs a Historky ale zatím appka neumí rozeznat** -- nemám k dispozici
  žádné ověřené resource ID pro tyhle obrazovky a nechci je hádat naslepo
  (viz pravidlo v CLAUDE.md), takže ten čas prozatím padá do "Ostatní".
  Až pošleš čerstvý log z Debug tabu, kde otevřeš DM vlákno a podíváš se
  na něčí historku, dopíšu detekci i pro tyhle dvě kategorie.
- **Swipe do Reels ze strany z DMs pořád obchází blokování** -- tohle
  jsem tenhle kolo neřešil, protože bych zase jen hádal na slepo v
  detekční logice Instagramu. Potřebuju k tomu stejný čerstvý log jako
  výše (ideálně v jednom logu -- otevři DM vlákno, swipni stranou do
  Reels, koukni na historku -- ať to vyřeším najednou).

## v1.13 -- vlastní ikonka appky, opravená pilulka po vyhození

- **Appka má konečně vlastní ikonku** místo systémového placeholderu --
  fialový obrys mozku s gradientem na tmavém pozadí, ručně překreslený
  podle fotky, co jsi poslal (jako obrázek v chatu se mi bohužel
  nepodařilo dostat k originálnímu souboru, takže to není 1:1 stejný
  obrázek, ale vizuálně by to mělo sedět -- gradient, styl, pozadí).
  Standardní adaptivní ikonka, takže se hezky přizpůsobí kulatému i
  hranatému stylu podle telefonu.
- **Pilulka "Zpět do feedu" po vyhození z Reels je teď v jazyce appky**
  (dřív byla natvrdo v češtině bez ohledu na nastavený jazyk).
- **Pilulka už neluže, když tě to nehodí na feed:** pokud appka najde
  a klepne na Home ikonku dole, napíše "Zpět do feedu" jako dřív. Pokud
  se to nepovede a musí použít záložní tlačítko zpět (a to tě může
  reálně poslat třeba zpátky do DM, ne na feed), napíše obecnější
  "Opuštěno Reels" místo aby lhala o cíli. Detekci samotnou, jestli jsi
  v DMs nebo feedu, jsem neřešil (žádné hádání resource ID) -- jen jsem
  appku donutil říkat pravdu o tom, co sama ví jistě.

## v1.12 -- restart appky, hezčí graf, serif font

Díky za potvrzení, že scrollování/vyhazování z Reels už funguje --
opravdu to vypadá, že to byla ta samá chyba se sebe-nulující session
(v1.10). Swipe do Reels ze strany necháno, jak jsi napsal, na
dosledování.

- **Nové tlačítko "Restart App"** úplně dole v Settings, červené.
  Tvrdě ukončí celý proces appky (včetně accessibility služby, co v
  něm běží) a appka se sama za chvíli znovu spustí -- rychlejší
  alternativa k restartu celého telefonu, kdyby appka zase přestala
  reagovat.
- **Zaoblené rohy sloupců** v grafu "Last 7 days".
- **Font appky přepnut na serif** (Android bohužel nemá skutečné Times
  New Roman zabalené -- je to komerční font od Monotype, ne součást
  Androidu -- takže appka používá vestavěný "serif" font, který má
  hodně podobný klasický vzhled).
- Ikonka appky s mozkem zatím čeká -- obrázek přišel jen jako náhled
  v chatu, ne jako příloha, takže se k němu nedostanu. Pošli ho prosím
  znovu jako soubor (stejně jako logy) a přidám ho hned příště.

## v1.11 -- oznámení doopravdy vyskočí, méně manuálního klikání

- **Oznámení o zapnutém blokování teď doopravdy vyskočí** (heads-up),
  ne že jen tiše sedí v roletě -- a klepnutím na něj se appka otevře
  rovnou na Home tabu. (Technická poznámka: aby se nová důležitost
  projevila i lidem, co appku měli nainstalovanou už na v1.10, muselo
  se přejít na nový notifikační kanál -- Android neumí důležitost
  existujícího kanálu změnit zpětně.)
- **Appka si teď o oprávnění na oznámení řekne sama při otevření**,
  nemusíš na Run/Stop, aby se o to přihlásila (platí pro Android 13+).
  Přístupnost a bateriové výjimky appka pořád nemůže udělit sama --
  to Android z bezpečnostních důvodů nedovoluje žádné appce -- ale
  aspoň tě teď při prvním otevření rovnou hodí na Setup záložku
  s přesným návodem, pokud služba ještě není zapnutá.
- **Overlay čtvereček zmizí rychleji** v DMs a kdekoliv, kde není
  vidět spodní lišta -- tolerance snížena z 3s na 400ms, protože
  hlavní příčina problikávání (viz níže) už je pryč a tak dlouhá
  tolerance nebyla potřeba.
- **Další obrana proti nulování session:** kromě vlastních událostí
  appky (opraveno v v1.10) appka teď ignoruje i accessibility události
  bez uvedeného balíčku (`packageName == null`) místo aby je brala
  jako "odešel jsi z Instagramu" -- stejná kategorie chyby, jen jiný
  spouštěč.
- Přidáno tiché ladicí logování při každé změně balíčku, ze kterého
  přichází accessibility událost -- pokud se příště appka zase
  "nezapne" a log bude prázdný, tohle by mělo ukázat, jestli appce
  vůbec nějaké události chodí, nebo ne.

**Swipe do Reels ze strany (z DMs) pořád obchází blokování** podle
poslední zprávy -- ale je dost možné, že to byl taky jen vedlejší
projev stejné chyby opravené v v1.10 (vynulovaná session = neomezené
reely). Otestuj to prosím znovu na týhle verzi, než do samotné detekce
začnu sahat naslepo -- pokud to pořád dělá, pošli mi log přesně z
momentu swipnutí do Reels ze strany.

## v1.10 -- konečně skutečná příčina blbnutí Reels detekce

Poslaný log (RbLog2) ukázal jasný vzorec: appka hlásila "Entered reels
viewer" znovu a znovu během pár desítek milisekund, bez odpovídajícího
"Left reels viewer" mezi tím -- a pokaždé hned po řádku "Overlay
placed". To byla stopa.

- **Skutečná příčina nekonzistentního "jednou pustí víc reelů, jednou
  míň":** accessibility služba není omezená jen na Instagram (schválně,
  viz v1.0 -- jinak by nevěděla, žes odešel jinam) -- ale to znamená, že
  jí chodí i vlastní události appky samotné (typicky když se náš
  čtvereček přesune nebo změní průhlednost, Android to nahlásí jako
  events). Appka takovou vlastní událost mylně brala jako "uživatel
  odešel z Instagramu" a potichu (bez logu) vynulovala celou session
  "jsem v Reels" -- klidně desetkrát za sekundu, jen tím, že přemístila
  vlastní overlay. Proto to bylo tak nahodilé: časování shody s reálným
  swipem bylo prakticky náhodné. Opraveno -- appka teď vlastní události
  ignoruje místo aby si na jejich základě myslela, žes odešel.
- **Čtvereček teď doopravdy blokuje klepnutí** (viz v1.9) -- v
  kombinaci s opravou výše by tohle mělo být výrazně stabilnější.

Tohle byla oprava přímo v našem kódu (na rozdíl od hádání o resource ID
Instagramu), takže tentokrát jsem si dovolil zasáhnout i bez dalšího
kola logů -- log z RbLog2 ale přesně ukázal, kde to bylo, díky za něj.
Pokud to i po týhle verzi zlobí, sem s dalším logem.

- **Trvalé oznámení při zapnutém blokování:** appka teď při zapnutí
  Run zobrazí notifikaci, kterou nejde odstranit tažením (zmizí, až
  vypneš Stop). Na Androidu 13+ appka při prvním zapnutí požádá o
  oprávnění na notifikace.
- **Hub appek: Instagram uprostřed** jako výchozí pořadí u nové
  instalace (TikTok/Instagram/Snapchat zleva doprava). Kdo má appku
  už nainstalovanou a přeuspořádal si pořadí sám, tomu se nic nemění.
- Ikonky appek (foťák/nota/duch) beze změny -- to už bylo přesně to,
  cos chtěl, jen jsi to možná ještě neviděl v novější verzi.

## v1.9 -- čtvereček fyzicky blokuje klepnutí, Home tab

- **Čtvereček přes Reels ikonku teď doopravdy blokuje klepnutí:**
  dosavadní overlay neměl nastavené `isClickable`, takže na dotyk
  nijak nereagoval -- v praxi to fungovalo nespolehlivě (systém ho
  někdy vzal jako "nic tam není" a klepnutí propustil na Instagram pod
  ním). Teď je explicitně klikací s prázdným click listenerem, takže
  dotyk se zaručeně zastaví na něm.
- **Overview tab je teď Home** (ikonka domečku). Karta se stavem a
  tlačítky Run/Stop se přestěhovala dovnitř Home tabu -- není vidět na
  Setup/Log/Languages, jen tady, jak jsi chtěl.
- **Klepnutí na appku v hubu (Instagram/TikTok/Snapchat) tě teď hodí
  rovnou na Home tab**, i když jsi byl na jiné záložce.

**Detekce Reels (kick-out při scrollu) pořád nefunguje spolehlivě.**
Do detekční logiky jsem znovu nesahal -- nic v tomhle kole se jí
netýká a bez dat bych jen hádal, což už se v historii projektu
nevyplatilo (viz starší verze). Potřebuju čerstvý log, který pokrývá
přesně tohle:
1. otevři Instagram, jdi do Reels,
2. scrolluj na druhý reel (mělo by tě to vyhodit zpět na Home feed),
3. pokud vyhodí/nevyhodí, hned potom otevři Reels Blocker -> menu ->
   Log a pošli mi ho -- ideálně i s poznámkou, jestli šlo o první
   otevření appky po restartu telefonu/appky, nebo o appku, co běžela
   už delší dobu.

## v1.8 -- opravy zpětné vazby z v1.7

- **Rozbitý návod v jiných jazycích:** body v Setup návodu (1. 2. 3. ...)
  se u přeložených jazyků (čeština a další) nezobrazovaly pod sebou --
  příčina byla technická chyba při generování překladů (do textu se
  dostaly syrové znaky nového řádku místo správně escapované sekvence),
  což Android při kompilaci stringů polámal. Opraveno ve všech 31
  jazykových souborech.
- **Čtvereček přes Reels pořád mizel moc často:** místo drobného
  zpomalení teď zůstává vidět mnohem déle -- schová se, až když ikonka
  Reels na obrazovce opravdu není (chaty/DM, celoobrazovkový přehrávač),
  ne při každé krátké výpadce detekce.
- **Zvýraznění vybraného jazyka** v seznamu Languages.
- **Barevné Run/Stop:** v action sheetu appky v hubu je teď "Run"
  zeleně a "Stop" červeně. Hlavní tlačítka Run/Stop nahoře navíc teď
  ukazují stav rovnou v textu -- aktivní appka má na svém tlačítku
  "Running"/"Stopped", to druhé zůstává "Run"/"Stop" jako akce.
- **Ikonky v bočním menu:** ozubené kolečko u Settings, dokument u Log,
  glóbus u Languages, sloupcový graf u Overview.
- **Stahování logu přepracováno:** místo sdílecího dialogu (co se na
  HyperOS chovalo jako "Sdílet", ne jako stažení) appka teď otevře
  systémové okno "Uložit jako" -- vybereš si přímo místo a appka tam
  soubor zapíše sama, žádné sdílení přes další appku.

**Detekce Reels pořád nefunguje spolehlivě** -- do samotné detekční
logiky jsem v tomhle kole vůbec nesahal (nic z výše uvedeného se jí
netýká), takže pokud nefunguje, potřebuju nový log z Debug/Log
záložky přímo z chvíle, kdy se to stalo, abych věděl, co se přesně
děje -- bez toho bych zase jen hádal.

## v1.7 -- jazyky, přepracovaný hub appek, export logu, nová animace

Log poslaný po v1.6 potvrdil, že problikávání se zmírnilo, ale úplně
nezmizelo -- overlay se pořád občas schová a zase ukáže (řeší se teď
plynulým prolnutím místo tvrdého zmizení, takže by to mělo být míň
vidět, i když se to stane). Detekci Reels jsem ale zase nechal na
pokoji bez čerstvého logu -- viz poznámka dole.

- **~30 jazyků appky:** vlevo nahoře v novém menu (šipka) přibyla
  položka Languages -- jde přepnout jazyk appky nezávisle na jazyce
  telefonu (čeština, slovenština, němčina, francouzština, španělština,
  italština, portugalština, nizozemština, polština, ruština,
  ukrajinština, dánština, finština, řečtina, maďarština, rumunština,
  švédština, chorvatština, bulharština, turečtina, vietnamština,
  thajština, arabština, hebrejština, hindština, indonéština, japonština,
  korejština, norština, čínština (zjednodušená) + angličtina jako
  výchozí). Seznam je řazený abecedně podle anglického názvu jazyka.
- **Hub appek přepracovaný:** Instagram/TikTok/Snapchat mají teď
  jednoduché ikonky místo textových zkratek (IG/TT/SC). Podržení appky
  už nerovnou spustí přesun -- otevře se malé menu s volbami Stats
  (skočí na přehled dané appky), Move (přesun, funguje jako dřív --
  klepni kam přesunout) a Run/Stop (rychlé zapnutí/vypnutí přímo
  odsud).
- **Log jde stáhnout jako .txt:** v menu -> Log přibylo tlačítko
  "Download as .txt", které log uloží/pošle přes systémové "Sdílet"
  (Soubory, e-mail, Disk...), ne jen kopírování do schránky.
- **Nová animace při vyhození z Reels:** místo tmavého záblesku přes
  celou obrazovku teď shora sjede malá pilulka s textem, bez tmavého
  podkladu -- méně rušivé.
- **Problikávání, pokračování:** overlay se teď plynule prolíná
  (fade) místo okamžitého zmizení/objevení, prodloužena i tolerance,
  než se vůbec schová (500ms -> 900ms). Přidáno i podrobnější logování
  (kdy a proč se overlay schovává), aby šlo případně dohledat skutečnou
  příčinu z dalšího logu.

**Známý, zatím neopravený problém:** podle zpětné vazby jde do Reels
swipnout stranou přímo z feedu nebo z DM, a appka to v některých
případech nezachytí (i kdyby overlay fungoval perfektně, jde tudy
obejít). Tohle se týká samotné detekční logiky (ne spolehlivosti), a
tu bez čerstvého logu z přesně tohohle scénáře neupravuju naslepo --
až budeš mít chuť, pošli log pořízený zrovna při swipnutí do Reels ze
strany (ne přes ikonku dole) a mrknu na to přesně.

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
  Settings → Apps → ScrollGuard → Battery again after any HyperOS
  update, since those settings sometimes reset.
- This only works on Android. iOS does not allow this kind of
  cross-app UI access without a jailbreak.
