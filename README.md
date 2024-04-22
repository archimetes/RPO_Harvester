# RPO Harvester 
Zberač záznamov z Registra právnických osôb a zoznamov vedených finančnou správou.

## Zdroje dát
Register právnických osôb - RPO: [ www.susr.sk]( https://www.susr.sk/wps/portal/ext/Databases/RPO%20-%20Register%20pr%C3%A1vnick%C3%BDch%20os%C3%B4b/!ut/p/z1/jY9NC8IwGIN_UjOrbh7fif3AoutGu9mL9CQFnR7E368Mr9blFniSEBbYwMIYX-kSn-k-xuvHn8L67E1T1XVBOBq-g227vRO2UdIXrJ-ArSS1LA1QGbmCJuXajeUcxFmYk8cPEeblM0DI1_csTAhZazvjPaRfCGheSBycA0T5BXIX_408bs4NSPoNNYHmVg!!/dz/d5/L0lDUmlTUSEhL3dHa0FKRnNBLzROV3FpQSEhL3Nr/ ) 

Exporty z online informačných zoznamov: [ www.financnasprava.sk]( https://www.financnasprava.sk/sk/elektronicke-sluzby/verejne-sluzby/zoznamy/exporty-z-online-informacnych )
obsahuje:
- Zoznam daňových subjektov registrovaných pre DPH 
- Zoznam daňových subjektov registrovaných na daň z príjmov
- Zoznam daňových subjektov, ktorým bol určený index daňovej spoľahlivosti

## Spustenie projektu:

1. `mvn clean install`
2. `docker volume rm -f rpoharvest_dev-es01_data && docker volume create rpoharvest_dev-es01_data`
3. `docker compose up -d`

Pri spustení kontajneru sa v Elasticu vytvorí user elastic s heslom v konfiguračnom súbore .env 



