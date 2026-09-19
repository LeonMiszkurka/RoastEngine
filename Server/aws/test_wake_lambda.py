"""Tests for wake_lambda.py with a fake EC2, so they run without AWS or boto3.

    python3 Server/aws/test_wake_lambda.py
"""

import json
import os
import sys
import types
import unittest

sys.path.insert(0, os.path.dirname(__file__))


class FakeEc2:
    def __init__(self):
        self.state = "stopped"
        self.address = None
        self.started = 0
        self.fail = False

    def describe_instances(self, InstanceIds):
        if self.fail:
            raise RuntimeError("AccessDenied")
        instance = {"State": {"Name": self.state}}
        if self.address:
            instance["PublicIpAddress"] = self.address
        return {"Reservations": [{"Instances": [instance]}]}

    def start_instances(self, InstanceIds):
        self.started += 1
        self.state = "pending"


fake = FakeEc2()
sys.modules["boto3"] = types.SimpleNamespace(client=lambda name: fake)
import wake_lambda  # noqa: E402  (after the fake boto3 is in place)


def call(status_only=False):
    event = {"queryStringParameters": {"action": "status"}} if status_only else {}
    result = wake_lambda.lambda_handler(event, None)
    return result["statusCode"], json.loads(result["body"])


class WakeTest(unittest.TestCase):
    def setUp(self):
        fake.__init__()
        os.environ["INSTANCE_ID"] = "i-0123"
        os.environ["PORT"] = "25570"

    def test_sleeping_server_is_started_once_then_reported_ready(self):
        self.assertEqual(call(), (200, {"status": "starting"}))
        self.assertEqual(fake.started, 1)
        self.assertEqual(call(), (200, {"status": "starting"}))  # pending: not started again
        self.assertEqual(fake.started, 1)
        fake.state, fake.address = "running", "3.120.45.67"
        self.assertEqual(call(), (200, {"status": "ready", "address": "3.120.45.67", "port": 25570}))

    def test_status_check_never_starts_the_server(self):
        self.assertEqual(call(status_only=True), (200, {"status": "asleep"}))
        self.assertEqual(fake.started, 0)
        fake.state, fake.address = "running", "3.120.45.67"
        self.assertEqual(call(status_only=True)[1]["status"], "ready")

    def test_running_without_an_address_yet_is_still_starting(self):
        fake.state = "running"
        self.assertEqual(call(), (200, {"status": "starting"}))

    def test_stopping_waits_instead_of_starting(self):
        fake.state = "stopping"
        self.assertEqual(call(), (200, {"status": "stopping"}))
        self.assertEqual(fake.started, 0)

    def test_setup_problems_are_explained(self):
        os.environ["INSTANCE_ID"] = ""
        self.assertEqual(call()[1]["status"], "error")
        os.environ["INSTANCE_ID"] = "i-0123"
        fake.state = "terminated"
        self.assertIn("terminated", call()[1]["message"])
        fake.fail = True
        self.assertEqual(call()[0], 500)


if __name__ == "__main__":
    unittest.main()
