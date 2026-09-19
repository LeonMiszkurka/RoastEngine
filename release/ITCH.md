# Releasing RoastEngine on itch.io

## 1. Build the downloads

```
cd /Volumes/Projects/RoastEngine/Game
./gradlew packageRelease -PreleaseVersion=0.1.0
```

This writes three zips into `build/release/`:

| File | Who it's for |
|---|---|
| `RoastEngine-0.1.0-macos-arm64.zip` | Macs with Apple chips (M1 and newer, 2020 onward) |
| `RoastEngine-0.1.0-linux-x64.zip` | Linux PCs, and Chromebooks with Intel/AMD chips |
| `RoastEngine-0.1.0-linux-arm64.zip` | ARM Linux, and Chromebooks with ARM chips (MediaTek, Snapdragon) |

Java is bundled in every zip, so players don't install anything. New players get **The Club** as a
starter world (`-PstarterMods=` changes this; the world must be in `./mods`). The game keeps
everything in `~/RoastEngine` and writes `~/RoastEngine/logs/game-latest.log`, which is the first
thing to ask for in a bug report.

For the next release, bump the number: `-PreleaseVersion=0.1.1`.

## 2. Create the itch.io page

itch.io is free. There's no subscription, and itch only takes a cut (10% by default, adjustable)
when someone pays.

1. Sign in at <https://itch.io>, then **Upload new project**.
2. **Kind of project:** Downloadable. **Classification:** Games.
3. **Pricing:** *No payments* or *$0 or donate*, whichever you prefer for a first release.
4. **Uploads:** add each zip, then tick its platform:
   - `macos-arm64` → **macOS**
   - `linux-x64` → **Linux**
   - `linux-arm64` → **Linux** (rename its display name to "Linux / ChromeOS (ARM)")
5. **Release status:** *In development*, which tells people it's early.
6. **Visibility:** start as **Draft**, download your own builds from the page to check them, then
   switch to **Public**.

Players with the **itch.io app** get *Play* and *RoastEngine Creator* buttons automatically (each
zip carries an `.itch.toml`).

## 3. Text to put on the page

Paste this into the description, under your own intro:

> **macOS:** after unzipping, the first time you open RoastEngine macOS will say it can't check it
> for malware (the game isn't registered with Apple). Open **System Settings > Privacy & Security**,
> scroll down and click **Open Anyway**. You only need to do this once. Macs with Intel chips
> aren't supported yet.
>
> **Linux:** unzip and run `RoastEngine/RoastEngine.sh`.
>
> **Chromebook:** turn on Linux (Settings > Advanced > Developers > Linux development environment),
> put the zip in *Linux files*, then in the Terminal app run
> `unzip RoastEngine-*-linux-*.zip` and `RoastEngine/RoastEngine.sh`. Pick the ARM download if your
> Chromebook has an ARM chip (Settings > About ChromeOS > Additional details). Needs OpenGL 3.3,
> which most Chromebooks from 2020 onward have.
>
> **Multiplayer:** Multiplayer > server_1. If it's asleep, joining wakes it in about a minute.
>
> **Found a bug?** Send `~/RoastEngine/logs/game-latest.log` along with what happened.

## 4. Building a community

- **Devlogs** (on the itch page) are how followers hear about updates. Post one per release.
- **Community** (turn on *Discussion board* in the page settings) gives players a place to report
  bugs and share mods.
- Share your mod.io page next to the itch page, so players know where mods live.

## Later: Steam, Xbox and Windows

- **Windows** is the obvious next build: most PC players are on Windows, and it's the same kind
  of zip as the Linux one.
- **Steam** charges a one-time $100 fee per game (the Steam Direct fee, not a subscription) and
  takes a share of sales. Steam wants Windows first. macOS and Linux (and the Steam Deck, which runs
  the Linux build) are extras.
- **Xbox** needs acceptance into the ID@Xbox programme, and the game rewritten for Xbox's own
  tools. Xbox can't run Java or OpenGL, so it's a big port rather than a new zip.
