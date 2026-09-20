# RoastEngine

A lightweight, open-source 3D game engine and modding platform written in Java by **CoffeBrewIA**.

- Java 17+, Gradle
- LWJGL 3 (GLFW windowing/input, OpenGL 3.3 core rendering, stb_easy_font for UI text)
- mod.io REST API integration for browsing, downloading and installing mods

## Running

```bash
gradle run          # or ./gradlew run after `gradle wrapper`
```

On macOS the `run` task automatically adds `-XstartOnFirstThread` (required by GLFW).

### Packaging a runnable app

```bash
./gradlew packageApp    # build/package/RoastEngine.app  - double-clickable
./gradlew packageDmg    # build/package/RoastEngine-1.0.0.dmg - to share
```

Built with the JDK's `jpackage`, which bundles a Java runtime, so players do **not** need Java installed. The app contains two launchers: **RoastEngine** (the game) and **RoastEngine Creator** (the editor), and the in-game Creator button starts the bundled one.

A plain fat jar is not an option on macOS: GLFW requires `-XstartOnFirstThread`, and a double-clicked jar cannot pass JVM flags.

The app version is separate from the project version (macOS rejects a leading zero): `./gradlew packageApp -PappVersion=1.2.0`.

**Where a packaged app keeps its data:** a double-clicked app has no useful working directory, so mods, config, screenshots and the player model live in **`~/RoastEngine/`**:

```
~/RoastEngine/
  mods/            installed mods; starter worlds are copied here on first run
  config/          settings and modio.properties
  logs/            game-latest.log / creator-latest.log (and -previous) - ask for these in bug reports
  screenshots/     F2 screenshots
```

**Release zips for itch.io:** `./gradlew packageRelease -PreleaseVersion=0.1.0` builds
`build/release/RoastEngine-<version>-macos-arm64.zip`, `-linux-x64.zip` and `-linux-arm64.zip`
(the Linux ones also run on Chromebooks). See [release/ITCH.md](release/ITCH.md) for uploading
and the text for the store page.

In a development checkout (`./gradlew run`) everything stays in the project folder as before.

## Application flow

1. **Loading splash**: OpenGL and input are set up, then in the background the app loads config, scans `mods/` and checks the mod.io connection.
2. **Main menu**: *Start Sandbox*, *Open RoastEngine Creator* (starts the editor in its own process), *Mod.io Configuration* (Game ID, API key, OAuth token, User ID) and a *Mod Browser* with download, update and play buttons.
3. **Sandbox**: sky-blue world, grid platform, free-fly camera.

| Sandbox control | Action |
|---|---|
| Mouse | Look around |
| W A S D | Walk |
| Space | Jump |
| Left Shift / Left Ctrl | Sprint |
| Esc | Release mouse, press again to return to the menu |

Press **F2** at any time to save a screenshot to `screenshots/`.

### Player model

You see your own body in first person: look down and there is a torso, legs and hands.

Drop a **`playermodel.glb`** in the game folder (`.gltf`, `.obj` and `.fbx` also work, and `assets/playermodel.glb` is checked too) and it replaces the built-in blocky body. The model is scaled automatically to the player's 1.8m height and stood on its feet, whatever units it was exported in. With no such file, the blocky fallback body is used.

| Property | Purpose |
|---|---|
| `-PplayerModel=/path/file.glb` | Load from somewhere else |
| `-PplayerModelYaw=180` | Rotate, if your model faces the wrong way |
| `-PplayerModelScale=1.1` | Fine-tune the size |
| `-PplayerModelOffset=0.40` | How far back the body stands (bigger = less of it in view) |
| `-Phands=always` / `-Phands=never` | Force the cube hands on or off (default: off for a custom model) |

For example: `./gradlew run -PplayerModelOffset=0.5 -PplayerModelYaw=180`.

Your own head is hidden by a shader clip that discards the model's geometry above eye level, so you see your body but never the inside of your own face.

The player is a grounded character, not a free camera: gravity applies, there is no flying, ledges up to 0.55m are stepped up automatically, and touching a "Kills You" object respawns you.

## Sound

Sound runs through OpenAL. There are no audio files: every effect and both music loops are
synthesised at startup (`audio/SoundBank.java`) - footsteps, the door creak, sips, the glass going
back on the bar, passing out, the janitor's whoosh, UI clicks, a 120 BPM club loop with a crowd
murmuring underneath, and a calm menu pad.

A mod can replace any of them by shipping an Ogg Vorbis file under that name:

```
assets/sounds/footstep.ogg   door.ogg   sip.ogg   glass.ogg   pass_out.ogg
              whoosh.ogg     click.ogg  music_club.ogg   music_menu.ogg
```

A world picks its background track with `"music": "music_club"` in `scene.json`.
Music slows and warps as you drink, and fades out as you pass out.

## Shaders (the RoastEngine Shader API)

Shaders are not built into the engine - they come from an **API mod**. The engine provides the
plumbing (an HDR scene buffer, a bloom chain and a full-screen pass); the API mod provides the
look as GLSL. To use them:

1. Download **RoastEngine Shader API** from the Mod Browser (or drop it into `mods/`).
2. Click **Enable** on its row in the Mod Browser.
3. In game: **Esc > Settings > Graphics > Enable Shaders**.

The shader API adds bloom on glowing materials, screen-space ambient occlusion, filmic (ACES)
tonemapping, cool-shadow / warm-highlight grading, a vignette and film grain - and when you are
drunk the image sways and the colour channels drift apart.

A shader pack is any API mod with this layout (source in `packs/roastengine-shader-api/`):

```
shaders/
  pack.json        {"name", "bloomPasses", "bloomThreshold", "uniforms": {...}}
  bright.frag      what glows       (uScene, uThreshold)
  blur.frag        separable blur   (uImage, uDirection)
  composite.frag   the final image  (uScene, uBloom, uDepth, uResolution, uNear, uFar,
                                     uTime, uProjection, uInverseProjection, uDrunk)
```

Every number under `"uniforms"` is passed to `composite.frag` by name, so a pack can expose
tuning values. A pack that fails to compile is reported and skipped; the game keeps running.

**Glowing materials:** set an emissive colour (`Ke` in an MTL, `emissiveFactor` in glTF). The
club's neon strips use it, and that is what the bloom picks up.

## Controls (the InputEdit API)

**Keyboard and mouse are built into the engine.** They always work, need no mod, and are never
switched off or replaced. Everything below is about adding *other* ways to play on top.

The engine knows eight actions and nothing else:

```
moveX  moveY  lookX  lookY   jump  sprint  interact  pause
```

The game only ever asks for those, so it does not care what moved the player. Controllers come
in through two layers, each its own zip:

| Layer | Type | What it is |
|---|---|---|
| **InputEdit API** (`packs/inputedit-api/`) | API | The vocabulary. Owns the axis and button *names*, and the defaults schemes inherit. |
| **A device mod** (e.g. `packs/xbox-controller/`) | Mod | One control scheme: which part of that device produces each action. |

Install and enable the API, then install and enable a device mod. Without the API, device mods
are ignored and the game runs on keyboard and mouse - it says so in **Esc > Settings > General**,
which also lists any controller it can see, so you can check a pad is detected.

### The API: `inputedit/api.json`

```json
{
  "name": "InputEdit API",
  "defaults": {"deadzone": 0.15, "lookSpeed": 900, "lookCurve": 2.0},
  "axes":    {"LEFT_X": 0, "LEFT_Y": 1, "RIGHT_X": 2, "RIGHT_Y": 3, "LEFT_TRIGGER": 4, "RIGHT_TRIGGER": 5},
  "buttons": {"A": 0, "CROSS": 0, "B": 1, "X": 2, "Y": 3, "LEFT_BUMPER": 4, "START": 7,
              "LEFT_THUMB": 9, "DPAD_UP": 11}
}
```

These are indices into GLFW's shared gamepad layout, which it applies to every pad it
recognises - so a name means a *position*, and the same scheme works on an Xbox, PlayStation or
8BitDo pad. The aliases (`CROSS` for `A`, `L1` for `LEFT_BUMPER`, `OPTIONS` for `START`) are in
the same table. Nothing here is compiled into the engine: a new name is an edit to this file, or
a newer version of this API mod.

### A device mod: `input/scheme.json`

```json
{
  "name": "Xbox Controller",
  "device": "gamepad",
  "deadzone": 0.18,
  "lookSpeed": 950,
  "lookCurve": 2.2,
  "axes": {
    "moveX": {"axis": "LEFT_X"},
    "moveY": {"axis": "LEFT_Y", "invert": true},
    "lookX": {"axis": "RIGHT_X"},
    "lookY": {"axis": "RIGHT_Y"}
  },
  "buttons": {
    "jump": "A",
    "sprint": ["LEFT_THUMB", "LEFT_BUMPER"],
    "interact": ["X", "RIGHT_BUMPER"],
    "pause": ["START", "BACK"]
  }
}
```

| Key | What it does |
|---|---|
| `device` | `gamepad` looks names up in the API's table. `joystick` reads a device raw, and every name is then a plain index - which is how you support hardware GLFW has never seen. |
| `deadzone` | Stick movement below this counts as centred. The rest of the range is rescaled, so the axis still reaches 1. |
| `lookSpeed` | Window points of camera movement per second at full stick deflection - a stick holds a position, but looking is a movement. |
| `lookCurve` | Above 1 makes small stick movements finer. |
| `axes` | `{"axis": "RIGHT_X", "invert": true, "scale": 1.0}`, or `{"positive": "DPAD_RIGHT", "negative": "DPAD_LEFT"}` to drive an axis from two buttons. |
| `buttons` | One name, or a list of names that all trigger the action. |

Schemes **add to** the keyboard rather than replacing it, and several can be enabled at once, so
a controller and the keyboard both work in the same session and unplugging the pad mid-game just
stops it contributing. A scheme that fails to load is reported and skipped; the game keeps
running. A controller can pause and resume, but menus are still mouse-driven.

The bundled **Xbox Controller** mod is the worked example: left stick walks, right stick looks,
A jumps, X or RB interacts, clicking the left stick sprints, Menu pauses.

## Multiplayer

**Multiplayer** on the main menu is a server list, starting with `server_1`. Opening it checks each
server without waking it (Asleep / Starting / Online 2/16 with the world being played).
**Join** (or **Wake & Join** when it's asleep) wakes it if needed, which takes about a minute,
and connects.

Then the lobby:

- **The first player in picks the world** and any content mods, from the ones they have installed.
- **Anyone joining meanwhile waits**, and if the picker leaves the choice passes on.
- **When the last player leaves,** the next person to join picks again.
- **Missing world or mods are downloaded from mod.io automatically.** A world that isn't on mod.io
  is marked "only on your computer" in the picker, because nobody else can get it.
- **Shaders and controllers** (API and device mods) stay each player's own and aren't part of the pick.

**Accounts:** multiplayer needs a **CoffeeBrew Interactive** account (main menu, under mod.io),
one account for every CoffeeBrew game. The account name is the in-game name. Ranks are normal,
**[Mod]**, **[Admin]** and **[Owner]**, set only in DynamoDB. Chat commands: `/msg <name> <text>`
(`/w`, `/tell`, `/pm`), `/r`, `/list`, `/help`; moderators and above also `/kick` and `/ban`
(every server), admins and above `/unban`. See
[Server/DEPLOY-AWS.md](Server/DEPLOY-AWS.md#7-coffeebrew-interactive-accounts). The service is
[Server/aws/accounts_lambda.py](Server/aws/accounts_lambda.py); `local_accounts.py` runs it locally
for testing.

In the game, everyone else walks around with name tags. **T** (or Enter) opens the chat, **/**
starts a command (`/list`), and holding **Tab** shows who's on. Doors and drinks stay local to
each player. Only positions and chat are shared.

```
Protocol/   the wire format (plain Java, shared by both sides)
Server/     the dedicated server: headless, no LWJGL, runs anywhere with Java 17+
```

- **The server list** is [multiplayer-defaults.properties](src/main/resources/multiplayer-defaults.properties):
  `server.N.name`, plus either `server.N.wakeUrl` (a server that sleeps) or `server.N.address`
  (one that's always on). Adding `server_2` is two lines.
- **Run a server locally:** `./gradlew :Server:server`. Settings are in `Server/run/server.properties`
  (name, motd, maxPlayers, `world` to fix the world instead of letting the first player pick,
  `idleShutdownMinutes`). Console commands: `list`, `say <msg>`, `kick <name>`, `stop`.
  Join it with the address field under the list.
- **Put it on AWS:** see [Server/DEPLOY-AWS.md](Server/DEPLOY-AWS.md). It runs on EC2 and sleeps
  when nobody plays: after `idleShutdownMinutes` (15 on the cloud machine) with nobody on, it powers
  the machine off. Joining wakes it through a small Lambda function
  ([Server/aws/wake_lambda.py](Server/aws/wake_lambda.py)), which also reports the current address,
  so nobody types an IP. It costs around $1-2 a month for occasional play.
- **Test without clicking:** `-Pjoin=server_1` (or `-Pjoin=localhost`) joins at once,
  `-PautoPick=<world folder>` picks that world, `-PplayerName=Ana` sets the name, and
  `-Pserver.1.wakeUrl=http://...` points server_1 at a stand-in wake-up link. Give a second window
  its own `-PconfigDir` (and `-PmodsDir` to test downloading).

How it works: each game sends its position 20 times a second; the server sends everyone one
snapshot of all players 20 times a second. Other players are drawn 100 ms in the past and blended
between snapshots, so network jitter doesn't show as stutter. The server trusts the positions it's
sent (there is no anti-cheat), limits names and chat, throttles chat floods, and drops
connections that go quiet for 15 seconds or fall too far behind.

## Hack clients (the Bypass API)

The engine can fly the player, walk through walls, see other players through them and so on - but
only when two mods say so:

```
Bypass API (an API mod)     bypass/api.json   which cheats are unlocked at all
Roast Hack (a mod)          hack/client.json  which of them appear in the menu, and on which keys
```

With the API switched off, every hack client switches off with it and the game behaves exactly as
it does with no mods. With both on, the client's key (Tab by default) opens its menu:

| Tab | Cheats |
|---|---|
| Movement | flight, noclip, infinite sprint, speed, jump height, flight speed |
| View | third person, freecam (your body stays put), field of view, teleport to where you look, save/go to a spot |
| World | never pass out, no fall damage, instant doors, see players through walls |
| Info | position/speed/FPS readout, ping graph |

The pieces:
[Cheat](src/main/java/net/coffeebrewia/roastengine/bypass/Cheat.java) is the list the engine can
do, [BypassApi](src/main/java/net/coffeebrewia/roastengine/bypass/BypassApi.java) unlocks them,
[HackClient](src/main/java/net/coffeebrewia/roastengine/bypass/HackClient.java) lays them out, and
[Cheats](src/main/java/net/coffeebrewia/roastengine/bypass/Cheats.java) holds what is on. Adding a
cheat means adding it to `Cheat.ALL`, handling it in the sandbox, and listing it in a newer
Bypass API - no change to any client.

Both mods live in [packs/](packs/); `./gradlew packMods` zips everything there into
`build/packs/`. Online, cheats work like any other movement: the server takes players at their
word about where they are, so others see you fly. Dev aids: `-PautoCheats=fly,fov=110` and
`-PopenCheatMenu`.

## External mods (installing a zip)

**Install Mod from File...** on the main menu installs any mod zip that never went through
mod.io - a hack client, or a world someone sent you. It's the same zip the Creator exports.
External mods have no mod.io id, so other players can't be sent them automatically; the
multiplayer world picker marks them "only on your computer".

## Mod types and enabling

| Type | What it does | Needs enabling? |
|---|---|---|
| **World** | A level you play | No - press **Play World** on its row |
| **API** | Engine features other content builds on - a shader pack, the InputEdit API | Yes |
| **Mod** | Content added on top: a world's extras, or a control scheme for the InputEdit API | Yes |

Enabled APIs and mods are remembered in `mods/.enabled-mods.txt`. The in-game **mods** counter
shows enabled APIs and mods only; worlds are not counted.

## Pause menu and settings

**Esc** opens the pause menu: **Resume**, **Settings**, **Main Menu**. Settings are saved to
`config/settings.properties`.

| Tab | Options |
|---|---|
| General | Master, music and effects volume; mouse sensitivity; field of view; which control schemes are live |
| Graphics | Show FPS; VSync (applies on restart); **Enable Shaders** |

**Enable Shaders** runs the enabled shader API (see above); without one installed and enabled
it has nothing to run, and the tab says so.

## Interactions

Scene objects can carry an `interaction` in `scene.json`:

```json
{"name": "Door", "asset": "door.obj", "interaction": "door", "label": "open the door"}
{"name": "Beer", "asset": "glass.obj", "interaction": "drink", "fill": 1.0, "label": "sip the beer"}
```

Walk within 2.6m and look roughly at it, and a prompt appears. **Doors** swing around the hinge -
model the door with its pivot on that edge. **Drinks** rise to the camera while you hold E; a mod
that ships `assets/liquid.obj` gets that mesh drawn inside the glass, scaled to the level left.
Materials with `d` below 1 in their MTL (or alpha in glTF) render see-through, which is how the
glass shows its contents.

The bundled **The Club** level (`tools/generate_club.py`) is a worked example: a neon hallway, a
door to open, guests, and a bar with four drinks.

## Installing and playing mods

In the **Mod Browser** each row has:

- **Download** — fetches the mod's current file, verifies its MD5, extracts it into `mods/<mod>/` and rescans.
- **Update** — shown when mod.io has a newer file than the installed one (the stored `modfileId` differs). The header also counts available updates and offers **Update All**.
- **Installed** — the installed copy is current.
- **Play World** — for world mods, loads that mod's scene straight into the sandbox.

## Playing mods

Any mod folder containing a `scene.json` (what RoastEngine Creator exports as a **World** or **Mod**) is loaded when you click **Start Sandbox**:

- Its objects are placed and rendered, using the models in that mod's own `assets/` folder.
- **Collision** is honoured in walk mode (press **F**): solid objects block you, ones marked *No Collision* don't.
- **Kills You** objects respawn you on touch.
- Several world mods stack; the first one that ships a scene sets the sky colour.
- With no world mods installed, the sandbox shows its built-in demo scene.

Scripts are shipped and listed but not executed yet.

To skip the menu while testing: `./gradlew run -Psandbox`. Add `-PdebugPhysics` to log the player's vertical state once a second.

**Note:** the Creator is launched with `./gradlew :Creator:creator` (or the main-menu button), never `run`.

## mod.io Connect (signing in)

Players never type a Game ID or API key. The game ships with its own identity in
`src/main/resources/modio-defaults.properties`, so signing in is the same flow console games use:

1. Main menu > **Mod.io Connect** > type your email > **Send Code**.
2. mod.io emails a **5-digit code** (it creates an account if you do not have one).
3. Type the code > **Connect**.

That exchanges the code for an OAuth access token (`POST /oauth/emailrequest`, then
`POST /oauth/emailexchange`), saves it, and the Mod Browser fills in. The token is remembered,
so the next launch is already signed in; **Sign Out** forgets it.

The shipped **API key is public and read-only** - mod.io issues these specifically to be
distributed inside game clients, and it cannot change anything. Writes need a player's own token.
If you would rather not have it in the repository, add
`src/main/resources/modio-defaults.properties` to `.gitignore`; the app then falls back to the
`MODIO_GAME_ID` / `MODIO_API_KEY` environment variables.

Settings are saved to `config/modio.properties` (or `~/RoastEngine/config/` in a packaged app).

## Mod icons

A mod may put an `icon.png` at the root of its folder, and the Mod Browser shows it beside the
mod's name. Anything `stb_image` reads works; a square around 256x256 is the right size. A mod
without one gets a tile with its initial instead, so every row is the same shape. Icons load
while the browser is on screen and are dropped when it closes.

## Mods folder

```
mods/
  .downloads/        temporary zip downloads
  .staging/          extraction area
  <mod-name-id>/
    mod.json         optional author manifest: {"name": "...", "version": "..."}
    roast-mod.json   written by RoastEngine for mods installed from mod.io
```

Downloads are checked against mod.io's MD5 hash. Each zip is extracted to a staging folder with zip-slip and size-limit protection, then moved into place. The folder is scanned at startup and after every install. Use `-Droastengine.modsDir=...` to change its location.

## RoastEngine Creator

The editor and mod authoring tool. It reuses the engine (window, input, renderer, UI) and lives in `Creator/`.

```bash
./gradlew :Creator:creator
```

(The Creator's launch task is called `creator`, not `run`: Gradle matches an unqualified task name in every project, so `./gradlew run` would start the game and then open the Creator as soon as the game closed.)

**Launcher:** name the project, pick its **type**, then create it - or open an existing folder. A project looks like:

```
MyProject/
  project.json   name, type and format version
  scene.json     placed objects and their configs (world and mod projects)
  assets/        imported .obj / .glb models
  scripts/       Python scripts objects can reference
  exports/       zips produced by File > Export as
```

### Project types

The type is chosen when the project is made, stored in `project.json`, and decides which editor opens.

| Type | Editor | What it adds |
|---|---|---|
| **World** | 3D viewport | A level you play. Objects, ground, sky. |
| **Mod** | 3D viewport | Everything a world has, plus per-object **animations** and **script parameters** - a mod is dropped into somebody else's world, so its objects have to do something once they land there. |
| **API** | Scripting, no viewport | Scripts other mods build on. No scene at all. |
| **Shaders** | Live shader preview | A shader pack: a preview scene seen through your GLSL, with tuning sliders. |

An export defaults to the matching mod type (a shader pack exports as an API mod, since that is
what the engine loads shaders from), and only World and Mod projects write a `scene.json`.

### World and Mod editor

Menu bar on top (File, Scripting), 3D viewport in the middle, inspector on the right, assets at the bottom.

| Action | How |
|---|---|
| Look around and move | Hold the **left mouse button** in the viewport, then WASD, Space/Shift, Ctrl to sprint |
| Select an object | Quick left click on it (a click shorter than 0.25s doesn't rotate the camera) |
| Import a model | Assets panel > **Import Model** (.obj, .glb, .gltf, .fbx) |
| Place an object | Select an asset, then **Add to Scene** — it lands in front of the camera |
| Move / rotate | The transform tools below, or type values in the inspector |
| Scale | Type a value in the inspector |
| Save | **Ctrl+S**, or File > Save |
| Delete an object | Delete key, or the inspector's Delete Object button |

**Transform tools** work like Blender's. Pick one from the viewport toolbar or with the keyboard,
then drag a coloured handle - **X red, Y green, Z blue** - to transform along that one axis.

| Key | Tool |
|---|---|
| **Q** | Select - clicking picks objects, dragging looks around |
| **G** | Move - three arrows on the object |
| **R** | Rotate - three rings around it |

| While dragging | |
|---|---|
| **Ctrl** | Snaps to 0.25 m / 15 degrees |
| **Esc** | Cancels and puts the object back |

Handles are picked in screen space, so a nearly edge-on ring is still easy to grab, and the gizmo
is scaled by its distance from the camera so it stays the same size on screen at any range. It
draws over the scene rather than inside it, so it is never buried in the object it belongs to.

### Mod projects: animation and behaviour

A mod's inspector has two extra sections. Both are plain data in `scene.json`, so the engine
plays them with no script engine involved.

**Animation** - a looping motion on top of the placed transform:

| Kind | What it does |
|---|---|
| **Spin** | Turns about the axis, `speed` degrees per second |
| **Swing** | Rocks back and forth by `amount` degrees |
| **Bob** | Slides up and down by `amount` metres, `speed` cycles per second |
| **Pulse** | Grows and shrinks by `amount` (a fraction of its size) |
| **Orbit** | Circles its placed position with radius `amount` |

`delay` staggers copies of the same object so they do not move in lockstep. Animation is visual:
**collision stays where you placed the object**, so an animated prop is decoration that moves,
not a platform that carries you.

**Script parameters** - name/value pairs handed to the object's script, so one script can drive
many objects with different settings.

### Shader editor

A new Shaders project starts with a complete, working pack in `shaders/` (a copy of the RoastEngine
Shader API), so it opens on a finished look to change rather than a blank screen.

- **Viewport** - a fixed preview room built to exercise every stage of a pack: plain surfaces for
  the grade, a wall and pillars for ambient occlusion, glowing strips and a glowing block for
  bloom. It renders through the same `PostProcessor` and `ShaderPack` as the game, so what you see
  is what players see. **Shaders: On/Off** flips between shaded and raw to compare.
- **Files** - `pack.json` and the three `.frag` files. **Open in Editor** hands one to your usual
  editor. Every file is watched: save it and the pack recompiles within half a second.
- **Compile errors** show underneath with the GLSL line number, and the **last pack that
  compiled stays on screen**, so a typo never blanks the view.
- **Tuning** - `bloomThreshold`, `bloomPasses` and every number under `"uniforms"` in pack.json
  become sliders that change the picture instantly, with no recompile. **Save Tuning** (Ctrl+S)
  writes them back to pack.json. **Add Tuning Value** adds a new one; declare
  `uniform float <name>;` in `composite.frag` to use it.

Export writes an API mod with `shaders/` inside - install it, enable it, and switch on
**Settings > Graphics > Enable Shaders**.

### API editor

An API has no scene, so it opens a scripting screen instead: the script list on the left, a
read-only preview with line numbers in the middle, and the **entry points** found in the selected
script along the bottom - the functions a dependent mod can call. The preview reloads whenever
the file changes on disk, so **Open in Editor** hands the file to your usual editor and the
changes show up here as you save. Export ships every script and no scene.

**Configs** (per object, in the inspector): objects collide by default.

| Config | Effect |
|---|---|
| **No Collision** | You walk straight through it |
| **Kills You** | Touching it respawns you |
| **Is Slippery** | You slide across it like ice - momentum builds slowly and carries you when you stop pressing |
| **Interaction** | `"door"` swings open when you press **E**; `"drink"` is raised to your mouth while you hold **E**, and its `fill` drops with each sip |
| **Script** | Attaches a `.py` file from the project's `scripts/` folder |

**Textures** come along automatically, *if the material actually has an image texture*. Blender's
glTF exporter only writes image textures - a material built from procedural shader nodes exports
with no texture at all and renders white. Bake such a material to an image texture before exporting.
The log names any material it finds without one:

```
[Models] mymap.glb: material 'Blueprint Paper Grid' has no image texture - it will render untextured
```

 A `.glb` normally embeds its images, and those are decoded straight out of the file; models that reference separate image files have them loaded from beside the model. One draw call per material, so multi-texture models work, and cut-out transparency (foliage, decals) is handled.

**Export** (File > Export as) asks for a file name, a mod type and a version, and writes `exports/<name>-<version>.zip`:

| Mod type | Contents |
|---|---|
| **World** | `mod.json`, `scene.json`, the models the scene uses, referenced scripts |
| **API** | `mod.json` and every script — no scene |
| **Mod** | Same as World, but meant to be added on top of an existing world |

That zip is what you upload as a mod file on mod.io.

Models are imported with Assimp. There is no lighting model yet, so a fixed directional light is baked into the vertex colours at import time and multiplied with the texture in the shader.

## Artwork

mod.io wants three images per mod. They live in `branding/` and are drawn by a script, so a
tweak means editing code rather than re-exporting from an image editor:

```bash
python3 tools/generate_mod_icons.py
```

| File | Size | Where mod.io uses it |
|---|---|---|
| `<mod>-icon-512x512.png` | 512x512 | square icon |
| `<mod>-logo-1280x720.png` | 1280x720 | the card image on the mod page (512x288 minimum) |
| `<mod>-header-400x100.png` | 400x100 | wordmark strip |

It also writes a 256x256 `icon.png` into each mod's own folder. That one is not branding - the
engine reads it and shows it in the Mod Browser, so it has to ship inside the mod's zip.

The script paints each piece into a base layer and an emissive layer, then runs the emissive
one through the same bright-pass, blur, add and filmic tonemap the shader API does - so the
artwork glows the way a frame out of the engine does. `branding/roastengine-*.png` and
`branding/modio-*.png` are the platform's own art and are not generated.

## Project layout

```
src/main/java/net/coffeebrewia/roastengine/
  Main.java                 entry point
  core/                     Engine (game loop), Window, GameState, StateManager, EngineConfig
  input/                    Input (GLFW callbacks -> polled state, mouse capture),
                            InputActions/InputAxis/InputButton (what the game reads),
                            Gamepads (controller snapshot),
                            InputEditApi (the API mod), ControlScheme (a device mod)
  render/                   ShaderProgram, Mesh, Primitives, Camera, Renderer2D, Color
  ui/                       Ui (immediate-mode widgets), TextField, Theme, ModIcons
  modding/                  ModConfig, ModIoClient, ModManager, ModInfo, LocalMod
  modding/scene/            Scene, SceneObject, ObjectAnimation (the shared scene.json format)
  render/model/             ModelAsset, ModelLoader (Assimp), ModelLibrary
  world/                    WorldLoader, LoadedWorld, WorldObject (mods -> playable world)
  multiplayer/              NetClient (the socket), MultiplayerSession, RemotePlayer
                            (interpolation), ChatBox, ServerList (the list; wakes sleeping servers)
  states/                   LoadingState, MainMenuState, MultiplayerMenuState (server list),
                            MultiplayerLobbyState (world pick, downloads), SandboxState
src/main/resources/shaders/ world.vert/.frag, ui.vert/.frag, ui_textured.frag

Creator/src/main/java/net/coffeebrewia/roastengine/creator/
  project/                  CreatorProject, ProjectType
  viewport/                 Gizmo (the move and rotate handles)
  states/                   LauncherState, EditorState (world/mod), ApiEditorState, ShaderEditorState
  export/                   ModExporter, ModType

Protocol/src/main/java/net/coffeebrewia/roastengine/net/
  Protocol.java             every multiplayer message, and how it is framed on the wire

Server/
  src/main/java/.../server/ RoastServer, ClientConnection, ServerConfig
  deploy/                   setup.sh, the systemd service and after-exit.sh (powers off when
                            idle), shipped inside the server zip
  aws/                      wake_lambda.py (the wake-up link), its IAM policy, and its tests
  DEPLOY-AWS.md             step-by-step EC2 + Lambda guide
```
