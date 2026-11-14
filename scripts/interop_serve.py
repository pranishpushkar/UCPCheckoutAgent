"""Starts the pinned UCP sample merchant bound to 127.0.0.1 only (H1, notes/h1/h1-plan.md C1).

The sample's own `server.py` calls `uvicorn.run(app, host="0.0.0.0")`, so it listens on every interface. This launcher
imports the sample's `app` unchanged, sets the sample's absl flags the way its `main` would, and runs uvicorn on
127.0.0.1. Run it from the sample's `rest/python/server` directory with `uv run --frozen python <this file> ...`, only
through scripts/interop-spike.sh. It changes nothing in the sample.

Usage: interop_serve.py PRODUCTS_DB TRANSACTIONS_DB PORT SIMULATION_SECRET
"""
import sys

import uvicorn
from absl import flags

import config  # the sample's: defines the flags
import server  # the sample's: the FastAPI `app`

products_db, transactions_db, port, secret = sys.argv[1:5]
flags.FLAGS(["interop_serve", f"--products_db_path={products_db}", f"--transactions_db_path={transactions_db}",
             f"--port={port}", f"--simulation_secret={secret}"])
uvicorn.run(server.app, host="127.0.0.1", port=int(port), log_level="warning")
