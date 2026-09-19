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
