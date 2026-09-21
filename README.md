## Getting Started

Welcome to the VS Code Java world. Here is a guideline to help you get started to write Java code in Visual Studio Code.

## Folder Structure

The workspace contains two folders by default, where:


## PoliceCuffs

Ein Bukkit/Paper-Plugin für Verhaftungen.

- `/cuff <Spieler>` verhaftet einen Spieler und macht ihn unbeweglich.
- Der gleiche Befehl lässt einen bereits verhafteten Spieler wieder frei.
- Alternativ kann ein Polizist mit einer Leine auf einen Spieler rechtsklicken und die Leine mindestens 5 Sekunden halten.
- Die Leine muss während des gesamten Vorgangs in der Haupthand bleiben; der Abstand darf höchstens 5 Blöcke betragen.

## Installation

1. Mit einer passenden Bukkit- oder Paper-API kompilieren.
2. `plugin.yml` und die kompilierte `App.class` entsprechend der Paketstruktur in eine Plugin-JAR packen.
3. Die JAR in den `plugins`-Ordner des Servers kopieren und den Server starten.

Die Berechtigung `police.cuff` ist standardmäßig nur für Operatoren aktiviert.

## Polizei-Ränge

Die Ränge werden über Permissions vergeben:

- `police.rank.0` bis `police.rank.6`
- Rang 0 ist der niedrigste Rang.
- Rang 6 ist der höchste Rang.

Spieler mit mindestens einer dieser Permissions können `/duty` verwenden. Beim Dienstantritt erhalten sie eine Leine mit dem Namen `Cuffs`.

## Rangverwaltung

Polizei-Spieler können andere Spieler mit den folgenden Befehlen verwalten:

- `/rankup <Spieler>` erhöht den Polizei-Rang um 1.
- `/rankdown <Spieler>` verringert den Polizei-Rang um 1.

Nur Rang 6 darf standardmäßig andere Spieler befördern oder degradieren. Ein Operator kann zusätzlich die Permission `police.rank.leader` vergeben. Spieler mit dieser Leader-Permission dürfen untergeordnete Polizei-Ränge verwalten. Die Ränge werden dauerhaft anhand der UUID in `config.yml` gespeichert. Die Mitgliedschaft bleibt deshalb auch nach Logout und Server-Neustart erhalten. Dienststatus und Cuffs werden beim Verlassen entfernt und müssen nach dem Login mit `/duty` erneut aktiviert werden.

Beispiel mit LuckPerms:

`/lp user Spieler permission set police.rank.leader true`

Operatoren können Spieler unabhängig von bestehenden Polizei-Permissions aufnehmen oder direkt einen Rang setzen:

`/setrank <Spieler> <Rang 0-6>`

Operatoren können mit `/setleader <Spieler>` einem Rang-5- oder Rang-6-Spieler Leaderrechte geben oder sie wieder entziehen. Leader dürfen `/rankup`, `/rankdown`, `/invite` und `/uninvite` verwenden. Die Leaderrechte werden dauerhaft in `config.yml` gespeichert.

Rang 6 oder Leader können Spieler mit `/invite <Spieler>` einladen. Der eingeladene Spieler kann mit `/annehmen` beitreten und erhält Rang 0 (Auszubildener), oder die Einladung mit `/ablehnen` ablehnen. Offene Einladungen werden dauerhaft in `config.yml` gespeichert.

Operatoren können mit `/uninvite` selbst die Polizei-Fraktion verlassen. Polizei Rang 6 kann mit `/uninvite <Spieler>` andere Spieler entfernen. Dabei werden Dienst-Cuffs entfernt und der Spieler kann den Polizei-Dienst sowie den Polizei-Chat nicht mehr verwenden.

Jedes Polizei-Mitglied kann mit `/uncuff <Spieler>` die Handschellen eines verhafteten Spielers entfernen. Der verhaftete Spieler erhält beim Verhaften die Nachricht, dass er verhaftet wurde, und beim Freilassen eine Nachricht über die entfernten Handschellen.

## Polizei-Chat

Mit `/f <Nachricht>` oder `/policechat <Nachricht>` schreiben Polizei-Mitglieder in den eigenen Chat. Die Nachricht wird nur an online Polizei-Mitglieder gesendet.

Admins und Moderatoren können über das Ranks-Plugin mit `/aduty` den Admin-Dienst aktivieren. Währenddessen erhalten sie dort die Permission `ranks.teamchat` und können den Polizei-Chat mitlesen. Sie können dadurch nicht automatisch Polizei-Befehle verwenden oder in den Chat schreiben.

Im Polizei-Chat wird vor dem Namen automatisch der Rang angezeigt:

- Rang 0: Auszubildener
- Rang 1: Streifencop
- Rang 2: Deputy
- Rang 3: Detective
- Rang 4: Sergeant
- Rang 5: Chief
- Rang 6: Direktor

Spieler mit mindestens einem Wanted-Punkt erhalten einen roten Nametag über ihrem Spieler. Die Wanted-Markierung erscheint nicht im Polizei-Chat. Mit ProtocolLib sehen nur Polizei-Mitglieder und Staff im aktiven `/aduty` diesen roten Nametag.

## Wanted-System

Polizei-Mitglieder können mit `/wps <Spieler> <Grund>` Wanted-Punkte vergeben. Die Punkte werden in `config.yml` gespeichert und sind auf maximal 60 begrenzt. Mit `/wps <Spieler> info` wird der aktuelle Stand angezeigt. `/wps` ohne Argumente zeigt alle verfügbaren Gründe.

Polizei ab Rang 4 kann mit `/delwps <Spieler>` die Wanted-Akte löschen. Der betroffene Spieler erhält danach die Nachricht: `Deine Akte wurde von (Polizei Member) Gelöscht.`
