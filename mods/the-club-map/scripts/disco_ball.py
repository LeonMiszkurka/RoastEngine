# The disco ball over the dance floor.
#
# turn_speed and bob are script values on the object, so a second ball hung somewhere
# else can turn at its own speed without this script being copied.


def on_start():
    self.spin(param("turn_speed", 45))
    every(4, dip)


def dip():
    self.move_to(self.x, self.y - param("bob", 0.25), self.z, 2)
    after(2, lift)


def lift():
    self.move_to(self.x, self.y + param("bob", 0.25), self.z, 2)
