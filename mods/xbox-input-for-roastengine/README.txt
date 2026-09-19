Xbox Controller - a control scheme for the InputEdit API
========================================================

Needs the InputEdit API installed and enabled. Enable this on top of it.

  Left stick        walk and strafe
  Right stick       look
  A                 jump
  X / RB            interact (open a door, sip a drink)
  Left stick click  sprint  (LB works too)
  Menu / View       pause

The whole mod is input/scheme.json. The engine knows the actions, the InputEdit
API knows the button names, and this file joins the two up.

"device": "gamepad" asks for GLFW's shared pad layout, which it applies to any
controller it recognises - so this works on a PlayStation or 8BitDo pad too,
with the names read positionally (A is the bottom face button).

Keyboard and mouse always stay live; a scheme adds to them.
