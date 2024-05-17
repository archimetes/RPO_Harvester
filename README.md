# RPO Harvester
Zberač záznamov z Registra právnických osôb a zoznamov vedených finančnou správou.

## Zdroje dát
Register právnických osôb - RPO: [ www.susr.sk]( https://www.susr.sk/wps/portal/ext/Databases/RPO%20-%20Register%20pr%C3%A1vnick%C3%BDch%20os%C3%B4b/!ut/p/z1/jY9NC8IwGIN_UjOrbh7fif3AoutGu9mL9CQFnR7E368Mr9blFniSEBbYwMIYX-kSn-k-xuvHn8L67E1T1XVBOBq-g227vRO2UdIXrJ-ArSS1LA1QGbmCJuXajeUcxFmYk8cPEeblM0DI1_csTAhZazvjPaRfCGheSBycA0T5BXIX_408bs4NSPoNNYHmVg!!/dz/d5/L0lDUmlTUSEhL3dHa0FKRnNBLzROV3FpQSEhL3Nr/ )

Exporty z online informačných zoznamov: [ www.financnasprava.sk]( https://www.financnasprava.sk/sk/elektronicke-sluzby/verejne-sluzby/zoznamy/exporty-z-online-informacnych )
obsahuje:
- Zoznam daňových subjektov registrovaných pre DPH
- Zoznam daňových subjektov registrovaných na daň z príjmov
- Zoznam daňových subjektov, ktorým bol určený index daňovej spoľahlivosti


## Príprava docker prostredia

Vytvorte si súbor .env s obsahom :
```
ES_STACK_VERSION=8.13.2
ES_PASSWORD=HaLqlG6ROA6rDg
KB_PASSWORD=f2jqXv6Q9tw6zz
KB_MEM_LIMIT=1073741824
ES_LICENSE=basic
ES_MEM_LIMIT=1073741824
ES_CLUSTER_NAME=rpoh
```
samozrejme tie heslá si vygenerujte vlastné

## Spustenie projektu

1. `mvn clean install`
2. `docker volume rm -f rpoharvest_dev-es01_data && docker volume create rpoharvest_dev-es01_data`
3. `docker compose up -d`

Po spustení dockera sa chvíľu čaká na inicializáciu Elasticu, po spustení sa vytvoria useri a až potom začne zber dát.
V logoch rpoh-harvester-1 môžete sledovať postup. Úplný zber dát trvá asi 50 minút.

Potom si môžeme otvoriť Kibanu http://localhost:5612/ a prihlásiť sa userom elastic a heslom z .env súboru.
V developer konzole Kibany si môžete vyskúšať hladanie:
```
GET rpo_actual/_search/template
{
  "id": "rpo_autocomplete", 
  "params": {
    "query_string": "Slove",
    "size": 10
  }
}
```

## Čo s tým ďalej?
Samotný harvester asi nie je potrebné spúšťať cez docker ale môže sa spúšťať pravidelne ako Java jar cez cron. Stačí raz za týždeň.
`java -jar target/RpoHarvester-1.0-SNAPSHOT.jar -e http://localhost9212 -u elastic -p ${ES_PASSWORD} all`

V Kibane si urobte Data view aby ste si mohli pozerať všetky dáta.

Ak chcete sprístupniť AJAX/JSON api pre web aplikáciu máme v dockeri ukážku nginx proxy nastavenia.
Z vonku prichádza anonymný používateľ, nginx pre niektoré URL a metódu POST prevolá Elastic s prednastaveným menom:heslom guest:welcome.
Cez kibanu si musíte vytvoriť takéhoto usera a priradiť mu rolu s príslušnými oprávneniami - čítanie indexu rpo_actual.
V dev prostredí to môže byť zabudovaná rola viewer. Na http://localhost:8080/ je minimalistická aplikácia, ktorá ukazuje
funkciu autocomplete. 
