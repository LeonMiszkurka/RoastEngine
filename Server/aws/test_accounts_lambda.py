"""Tests for accounts_lambda.py against an in-memory DynamoDB.

    python3 Server/aws/test_accounts_lambda.py
"""

import json
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import fake_dynamo  # noqa: E402

TABLES = fake_dynamo.install()
os.environ["SERVER_KEY"] = "test-server-key-0123456789"
import accounts_lambda  # noqa: E402

KEY = os.environ["SERVER_KEY"]


def call(**body):
    result = accounts_lambda.lambda_handler({"body": json.dumps(body)}, None)
    return json.loads(result["body"])


def set_rank(username, rank):
    TABLES["CoffeeBrewAccounts"].items[username.lower()]["rank"] = rank


class AccountsTest(unittest.TestCase):
    def setUp(self):
        for table in TABLES.values():
            table.items.clear()

    def register(self, name, password="correct horse"):
        reply = call(action="register", username=name, password=password)
        self.assertTrue(reply["ok"], reply)
        return reply["token"]

    def test_register_login_and_me(self):
        token = self.register("Leon")
        self.assertEqual(call(action="me", token=token)["account"]["username"], "Leon")
        self.assertEqual(call(action="me", token=token)["account"]["rank"], "normal")
        login = call(action="login", username="leon", password="correct horse")
        self.assertTrue(login["ok"])
        self.assertEqual(login["account"]["username"], "Leon")

    def test_passwords_are_not_stored_or_returned(self):
        self.register("Leon")
        stored = TABLES["CoffeeBrewAccounts"].items["leon"]
        self.assertNotIn("correct horse", json.dumps(stored))
        reply = call(action="login", username="Leon", password="correct horse")
        self.assertNotIn("passwordHash", json.dumps(reply))

    def test_bad_input_is_refused_with_a_message(self):
        self.assertIn("taken", (self.register("Leon") and call(action="register", username="LEON",
                                                               password="another pass"))["error"])
        self.assertFalse(call(action="register", username="no spaces", password="longenough")["ok"])
        self.assertFalse(call(action="register", username="Ann", password="short")["ok"])
        self.assertEqual(call(action="login", username="Leon", password="wrong one")["error"],
                         "Wrong username or password.")
        self.assertEqual(call(action="login", username="Nobody", password="wrong one")["error"],
                         "Wrong username or password.")
        self.assertFalse(call(action="nope")["ok"])

    def test_too_many_wrong_passwords_locks_the_account(self):
        self.register("Leon")
        for _ in range(accounts_lambda.MAX_FAILED_LOGINS):
            call(action="login", username="Leon", password="wrong one")
        self.assertIn("Too many", call(action="login", username="Leon", password="correct horse")["error"])

    def test_tickets_work_once_and_only_for_servers(self):
        token = self.register("Leon")
        ticket = call(action="ticket", token=token)["ticket"]
        self.assertFalse(call(action="redeem", serverKey="wrong", ticket=ticket)["ok"])
        self.assertEqual(call(action="redeem", serverKey=KEY, ticket=ticket)["account"]["username"], "Leon")
        self.assertFalse(call(action="redeem", serverKey=KEY, ticket=ticket)["ok"])  # used up
        # A session token is not a ticket.
        self.assertFalse(call(action="redeem", serverKey=KEY, ticket=token)["ok"])

    def test_expired_tickets_and_logged_out_sessions_fail(self):
        token = self.register("Leon")
        ticket = call(action="ticket", token=token)["ticket"]
        for item in TABLES["CoffeeBrewSessions"].items.values():
            if item["kind"] == "ticket":
                item["expiresAt"] = int(time.time()) - 1
        self.assertIn("expired", call(action="redeem", serverKey=KEY, ticket=ticket)["error"])
        call(action="logout", token=token)
        self.assertFalse(call(action="me", token=token)["ok"])

    def test_ban_rules_follow_rank(self):
        self.register("Owner1")
        self.register("Admin1")
        self.register("Mod1")
        self.register("Player1")
        set_rank("Owner1", "owner")
        set_rank("Admin1", "admin")
        set_rank("Mod1", "moderator")

        # Normal players can't ban; moderators can't ban their equals or the owner.
        self.assertFalse(call(action="ban", serverKey=KEY, by="Player1", target="Mod1")["ok"])
        self.assertFalse(call(action="ban", serverKey=KEY, by="Mod1", target="Owner1")["ok"])
        self.assertFalse(call(action="ban", serverKey=KEY, by="Mod1", target="Admin1")["ok"])
        # An admin outranks a moderator.
        self.assertTrue(call(action="ban", serverKey=KEY, by="Admin1", target="Mod1")["ok"])
        self.assertTrue(call(action="unban", serverKey=KEY, by="Admin1", target="Mod1")["ok"])
        # A moderator bans a player; only the owner can unban.
        self.assertTrue(call(action="ban", serverKey=KEY, by="Mod1", target="Player1", reason="spam")["ok"])
        ticket = call(action="ticket", token=call(action="login", username="Player1",
                                                   password="correct horse")["token"])["ticket"]
        account = call(action="redeem", serverKey=KEY, ticket=ticket)["account"]
        self.assertTrue(account["banned"])
        self.assertEqual(account["banReason"], "spam")
        # Moderators cannot unban; admins and owners can.
        self.assertFalse(call(action="unban", serverKey=KEY, by="Mod1", target="Player1")["ok"])
        self.assertTrue(call(action="unban", serverKey=KEY, by="Owner1", target="Player1")["ok"])
        # And nothing here can raise a rank.
        self.assertFalse(call(action="ban", serverKey="nope", by="Owner1", target="Player1")["ok"])

    def test_ranks_can_only_come_from_the_table(self):
        token = self.register("Sneaky")
        call(action="register", username="Sneaky2", password="12345678", rank="owner")
        self.assertEqual(call(action="me", token=token)["account"]["rank"], "normal")
        TABLES["CoffeeBrewAccounts"].items["sneaky"]["rank"] = "emperor"  # typo in the console
        self.assertEqual(call(action="me", token=token)["account"]["rank"], "normal")


if __name__ == "__main__":
    unittest.main()
