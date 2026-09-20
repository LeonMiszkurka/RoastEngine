"""
An in-memory stand-in for the few DynamoDB table calls the Lambdas use, so they can be tested -
and run locally - without AWS. Install it with install() before importing a Lambda.
"""

import sys
import types


class ConditionalCheckFailedException(Exception):
    pass


class FakeTable:
    def __init__(self, key):
        self.key = key
        self.items = {}
        self.meta = types.SimpleNamespace(client=types.SimpleNamespace(
            exceptions=types.SimpleNamespace(ConditionalCheckFailedException=ConditionalCheckFailedException)))

    def get_item(self, Key):
        item = self.items.get(Key[self.key])
        return {"Item": dict(item)} if item else {}

    def put_item(self, Item, ConditionExpression=None):
        if ConditionExpression and Item[self.key] in self.items:
            raise ConditionalCheckFailedException()
        self.items[Item[self.key]] = dict(Item)

    def delete_item(self, Key):
        self.items.pop(Key[self.key], None)

    def update_item(self, Key, UpdateExpression, ExpressionAttributeNames, ExpressionAttributeValues):
        item = self.items.setdefault(Key[self.key], {self.key: Key[self.key]})
        for part in UpdateExpression.removeprefix("SET ").split(", "):
            name, value = part.split(" = ")
            item[ExpressionAttributeNames[name]] = ExpressionAttributeValues[value]


TABLES = {"CoffeeBrewAccounts": FakeTable("username"), "CoffeeBrewSessions": FakeTable("tokenHash")}


def install():
    """Makes `import boto3` hand out the fake tables."""
    resource = types.SimpleNamespace(Table=lambda name: TABLES[name])
    sys.modules["boto3"] = types.SimpleNamespace(resource=lambda name: resource)
    return TABLES
