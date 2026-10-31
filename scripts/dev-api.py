#!/usr/bin/env python3
"""Disposable second development API for exercising approved external-target requests."""
import argparse
import hmac
import json
import math
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

products = {1: {"id": 1, "name": "Mechanical Keyboard", "price": 79}}

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass  # Never print request headers, credentials or bodies.

    def authorized(self):
        token = os.environ.get("DEV_API_TOKEN", "")
        supplied = self.headers.get("Authorization", "").removeprefix("Bearer ")
        if token and not hmac.compare_digest(supplied, token):
            self.reply(401, {"error": "Authentication required"})
            return False
        return True

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def do_HEAD(self):
        if self.authorized():
            self.reply(200, {})

    def do_GET(self):
        if not self.authorized():
            return
        path = self.path.split("?", 1)[0]
        if path == "/products":
            self.reply(200, list(products.values()))
        elif path.startswith("/products/") and path.rsplit("/", 1)[1].isdigit():
            product = products.get(int(path.rsplit("/", 1)[1]))
            self.reply(200 if product else 404, product or {"error": "Not found"})
        else:
            self.reply(404, {"error": "Not found"})

    def do_POST(self):
        if not self.authorized():
            return
        if self.path != "/products":
            self.reply(404, {"error": "Not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 20000:
                raise ValueError()
            data = json.loads(self.rfile.read(length))
            price = data.get("price")
            if not isinstance(data.get("name"), str) or not data["name"].strip() or isinstance(price, bool) or not isinstance(price, (int, float)) or not math.isfinite(price) or price < 0:
                raise ValueError()
        except (ValueError, TypeError, AttributeError):
            self.reply(400, {"error": "A name and a price of zero or more are required"})
            return
        product = {"id": max(products) + 1, "name": data["name"], "price": price}
        products[product["id"]] = product
        self.reply(201, product)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8081)
    args = parser.parse_args()
    print(f"Disposable development API on http://127.0.0.1:{args.port}; products reset on restart")
    HTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
