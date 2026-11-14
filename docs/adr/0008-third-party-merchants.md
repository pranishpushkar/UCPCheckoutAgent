# ADR 0008: Third-party merchants and Python in CI (H1)

Status: accepted (H1 C10). The H1 plan sections 1, 7, 9 and 12, and docs/interop/spike.md, are the working notes.

## Decision

- **A merchant we did not write is the truth source; the lab merchant stays the fault target.** Our lab merchant lets the
  proxy inject faults and read ground truth at will, but its author is the agent's author. The official UCP sample
  (`Universal-Commerce-Protocol/samples`, `rest/python/server`) is a second reading of the specification by someone else.
  Rows that run against it check the agent against that reading; faults are still injected by the proxy in front of it.
- **Why the Python sample.** It is the only independent, runnable merchant with a pinned source, a REST binding and a
  record we can read (its `orders` table). The Node sample has the same fulfillment rule; the codelab merchants are
  copied by hand with no repository; a real store (Shopify) is H5. A container was rejected: it adds a base image and a build
  to the supply chain for no gain, and CI has Python through `uv`.
- **The pin and the lock.** `docs/interop/pin.txt` records the repository, the commit (`52319dff…`), the conformance suite
  commit, the `uv` version (0.13.7) and the sha256 of the sample's `pyproject.toml`. **`docs/interop/uv.lock` is our own
  lock file**: the sample commits none, so `uv sync --frozen` could not pin its 77 dependencies (1,396 hashes); we generated
  one with `uv lock` at the pinned commit and `scripts/interop-fetch.sh` copies it in, after checking HEAD equals the pin
  and the `pyproject.toml` hash. The dependency set is ours, not the sample authors'; a new pin means regenerating the lock
  and the hash together. A wrong commit or a drifted `pyproject.toml` fails the fetch (a planted wrong SHA shows it).
- **Where it runs.** Only through `scripts/interop-*` and `-Pinterop`, from the clone in `eval-harness/target/interop`,
  bound to 127.0.0.1, one instance per lane. `./mvnw verify` never runs it and needs no Python, `uv`, Docker or network
  (`NoInteropInVerifyTest`). `./mvnw clean` deletes the clone, so the fetch runs again before each `-Pinterop`.
- **The job's permissions.** `.github/workflows/interop.yml`: `permissions: contents: read`, no secrets, a 20-minute
  timeout, path filters plus `workflow_dispatch`, every action pinned by commit SHA, `uv` pinned by version.
- **Hardening (H1 C10b).** The job runs third-party Python in the same runner as our checkout. (1) `actions/checkout` has
  `persist-credentials: false`, so the job's `GITHUB_TOKEN` is not left in `.git/config` where the sample's code could read it
  (nothing after checkout needs it). (2) There is no Maven cache: `setup-java` has no `cache:`, because code that runs in the
  job could write into `~/.m2` and a saved cache could be restored by `ci.yml`. (3) The trigger is `pull_request`, never
  `pull_request_target`: a pull request from a fork gets a read-only token and no secrets, which is what this job wants.

## What the job can and cannot prove

It proves what the sample's own record shows: ground truth (the `orders` table) exists only where the harness shares a file
system with the merchant, as it does here. It claims I1 only through that table. It cannot prove anything about a
merchant it cannot read: a real store's orders are not visible to us, so a row there claims no I1. It does not prove
conformance (the conformance suite is a separate record in docs/conformance.md), and S5 against the sample records an
`ambiguity`: neither 2026-04-08 nor 2026-08-25 says whether complete must re-price.

## Real stores (DESIGN 26.5)

A checkout is created only at a development store the owner owns; catalog search is read-only; a secret lives in the Keychain
and the environment only and never in a file, an argument, an image or a log; scripts that use one have a `--dry-run` that shows
every call with the secret replaced. No job has a secret.

## Evidence that this was worth it

The first live row against the sample found a bug no fixture test had: `Redactor` rebuilt the checkout view without its
fulfillment part, so a selection update's answer looked like the create's and the selection never progressed (H1 C8a).
Earlier, building the wiring for the sample exposed `UcpHttpGateway.PAYMENT_HANDLER`, the lab's handler hard-coded as the
contract (H1 C3). Both were ours.
