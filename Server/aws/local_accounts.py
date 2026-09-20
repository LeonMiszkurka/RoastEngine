"""
Runs the account service on this computer with an in-memory database, for testing without AWS.

    python3 Server/aws/local_accounts.py --rank Leon=owner --rank Ana=moderator

Then start the game with -PaccountsUrl=http://127.0.0.1:8770/ and a server with
accountsUrl=http://127.0.0.1:8770/ and serverKey=local-test-key-please-change in server.properties.
Accounts vanish when this stops. --rank applies whenever that account exists (ranks on the real
service are only set in the DynamoDB console).
"""

import argparse
import http.server
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import fake_dynamo  # noqa: E402

TABLES = fake_dynamo.install()
os.environ.setdefault("SERVER_KEY", "local-test-key-please-change")
import accounts_lambda  # noqa: E402


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8770)
    parser.add_argument("--rank", action="append", default=[], help="name=rank, e.g. Leon=owner")
    args = parser.parse_args()
    ranks = dict(pair.split("=", 1) for pair in args.rank)

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            for name, rank in ranks.items():
                account = TABLES["CoffeeBrewAccounts"].items.get(name.lower())
                if account:
                    account["rank"] = rank
            body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8")
            result = accounts_lambda.lambda_handler({"body": body}, None)
            data = result["body"].encode("utf-8")
            self.send_response(result["statusCode"])
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, fmt, *values):
            pass

    print(f"Local accounts on http://127.0.0.1:{args.port}/  (server key: {os.environ['SERVER_KEY']})", flush=True)
    http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
