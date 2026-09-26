InputEdit API - the input API for RoastEngine
=============================================

Keyboard and mouse are built into the engine and always work. This API is what
lets anything ELSE control the game.

The engine knows eight actions and nothing more:

  moveX  moveY  lookX  lookY   jump  sprint  interact  pause

It also knows how to read a controller, because GLFW does. What it does NOT
know is what any button is called. That vocabulary is inputedit/api.json in
this mod: a plain name -> index table for axes and buttons, plus the defaults
a scheme inherits. Edit it, or ship a newer InputEdit API, and new names work
with no engine change.


A controller the game cannot read
---------------------------------

GLFW places a pad it recognises onto the shared layout, and a "gamepad" scheme
reads it from there. A pad it does NOT recognise arrives as a bare list of axes
and buttons, and no "gamepad" scheme can read it: the game says so in the log
and in Esc > Settings > General, and prints that pad's 32-character id.

  inputedit/
    mappings.txt

Add a line for that id to this file and the pad is placed onto the shared
layout like any other, so the Xbox Controller scheme (and every other) starts
working on it. The file is SDL's game controller database format; it ships with
instructions in its comments and nothing else, so it does no harm as it is.

First though, two things that are not a missing mapping:

  * The pad is not connected at all. The game only sees what macOS, Windows or
    Linux has already paired or plugged in - if the system does not list it,
    neither will the game.

  * The pad offers NO axes and NO buttons. An Xbox controller on a USB cable
    does this: over the cable it talks Microsoft's own protocol rather than the
    standard one, so there is nothing to read and no mapping can help. Connect
    it over Bluetooth instead - the same pad then speaks the standard layout and
    works straight away. The game says which of these it is.


Adding support for a device
---------------------------

A device is its own mod (type "mod", not "api") with one file:

  input/
    scheme.json

  {
    "name": "My Controller",
    "device": "gamepad",
    "axes":    {"moveX": {"axis": "LEFT_X"},
                "moveY": {"axis": "LEFT_Y", "invert": true}},
    "buttons": {"jump": "A", "pause": "START"}
  }

"device": "gamepad" uses GLFW's shared layout, so a name means a POSITION on
any pad GLFW recognises - one scheme covers Xbox, PlayStation and 8BitDo pads
alike. "device": "joystick" reads a device raw, and every name is then a plain
number, which is how you support something GLFW has never seen.

An axis can also come from two buttons:

  "moveX": {"positive": "DPAD_RIGHT", "negative": "DPAD_LEFT"}

Install and enable this API, then install and enable the device mod. Schemes
add to the keyboard rather than replacing it, and several can run at once.
Esc > Settings > General lists what is live.

Xbox Controller is the worked example.
