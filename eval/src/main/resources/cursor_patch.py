"""Asks a Cursor agent for a patch, reading the prompt on stdin and writing the reply on stdout.

The Kotlin harness owns Level 2; this exists only because the Cursor SDK is Python and TypeScript.

Two things here are load-bearing for the experiment rather than incidental:

* The agent runs in an empty directory. It is an agent, not a completion endpoint, so it can read
  and search files — and if it could read the repository it would retrieve its own context and the
  arms would all be measuring the same thing. An empty `cwd` leaves the pack in the prompt as the
  only code it can see.
* Settings sources stay inline. Ambient rules from this machine would be a variable nobody reading
  the results could account for.
"""

import os
import sys
import tempfile

from cursor_sdk import Agent, AgentOptions, LocalAgentOptions


def main() -> int:
    prompt = sys.stdin.read()
    key = os.environ.get("CURSOR_API_KEY")
    if not key:
        print("CURSOR_API_KEY is not set", file=sys.stderr)
        return 1

    with tempfile.TemporaryDirectory(prefix="jetpacker-l2-") as empty:
        # The SDK launches its bridge with `workspace=os.getcwd()`, not `local.cwd`.
        # Staying in the harness repo would let the agent search this project.
        os.chdir(empty)
        result = Agent.prompt(
            prompt,
            AgentOptions(
                api_key=key,
                model=os.environ.get("JETPACKER_MODEL", "composer-2.5"),
                local=LocalAgentOptions(cwd=empty, setting_sources=[]),
            ),
        )

    if result.status != "finished":
        print(f"run did not finish: {result.status}", file=sys.stderr)
        return 2

    sys.stdout.write(result.result or "")
    return 0


if __name__ == "__main__":
    sys.exit(main())
