# Playing RoastEngine in VR (Meta Quest 2)

VR runs the game on a **Windows PC** with the headset as the display, over **Quest Link** (cable)
or **Air Link** (wireless). The Quest is an Android device, so it cannot run this build on its own;
that would mean porting the whole engine to Android and OpenGL ES, which is a rewrite rather than
a mod. SteamVR headsets (Index, Vive, Reverb) work the same way.

**macOS cannot do VR at all**: Apple ships no OpenXR runtime, Meta has no Mac Link app, and LWJGL
has no macOS OpenXR build. The Mac version simply stays on the monitor.

## What you need

| | |
|---|---|
| A Windows PC | with a graphics card that can run the game at 72+ FPS |
| Meta Quest Link app (or SteamVR) | installed, running, and set as the **OpenXR runtime** |
| A Quest 2 | connected by Link cable or Air Link |
| The Windows build | `RoastEngine-<version>-windows-x64.zip` |
| The **VR API** mod | `bypass`-style API mod; VR only starts when it is switched on |

## Setting it up

1. Unzip the Windows build on the PC.
2. Start it once with **Play RoastEngine.bat** (flat), then **Install Mod from File...** and pick
   `vr-api-1.0.0.zip`. Switch it on in the mod list. Do the same with
   `quest2-controller-1.0.0.zip` if you want Touch controllers to work in the flat game too.
3. Put the headset on and make sure Link or Air Link is connected.
4. Run **Play in VR.bat**.

The game appears in the headset and the window mirrors it.

## Controls

| Control | Does |
|---|---|
| Left stick | Walk, in whatever direction you are looking |
| Right stick (flick) | Turn 30° at a time. Snap turning, because smooth turning makes people ill |
| Right trigger | Punch, and use things (doors, drinks) |
| Left grip | Grab |
| A | Jump |
| Menu | Pause |

**Your hands are your hands**: both controllers are tracked, and the hands in the game are wherever
you are holding them. No hand animation plays in VR - the punch animation is for the flat game.

## How it works

- [VrSystem](../src/main/java/net/coffeebrewia/roastengine/vr/VrSystem.java) opens an OpenXR
  session, makes one texture chain per eye and runs the frame loop.
- [VrInput](../src/main/java/net/coffeebrewia/roastengine/vr/VrInput.java) turns the Touch
  controllers into actions and tracks both hands.
- The sandbox draws the world once per eye, with the head's own view and projection.
- Without a runtime, a headset, or the VR API mod, the game runs flat and says why in the log.

## If it does not work

- **"Not starting in VR: no VR runtime is running"** - start the Meta Quest Link app (or SteamVR)
  first. In the Meta app, check **Settings > General > OpenXR Runtime** is set to Meta.
- **"no headset is connected or it is asleep"** - wake the headset and check Link shows connected.
- **The headset stays black but the window works** - another app may hold the runtime; close
  SteamVR or the Meta app, whichever you are not using.
- **Everything is doubled or swims** - report it with `%USERPROFILE%\RoastEngine\logs\game-latest.log`.
