# The sign by the door. It has opinions about how you treat the guests.


def on_start():
    self.near_distance = 4


def on_player_near():
    notice(pick([
        "House rules: mind the guests.",
        "House rules: the guests do not mind you.",
        "House rules: what goes on the floor stays on the floor.",
    ]))


def on_interact():
    say("The sign is bolted down. The guests are not.")
