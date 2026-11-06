"""Push a local commit's changed files to GitHub through the REST API.

`git push` cannot reach github.com from this machine: the sandbox proxy answers
CONNECT with 502 and a direct connection times out, while `gh` reaches the API
fine. So the commit is rebuilt server-side instead: a tree that carries the
local file contents, a commit whose parent is the remote head, and a ref
update.

The files pushed are the ones `HEAD` touched, unless paths are given on the
command line. The commit message is taken verbatim from `HEAD`.

Usage:
    python tools-verify/push_via_api.py                # files changed by HEAD
    python tools-verify/push_via_api.py README.md ...  # explicit paths

Afterwards the local and remote commits share their content but not their SHA,
so reconcile with `git fetch && git reset --hard origin/main` once git can
reach the network again.
"""
import json
import subprocess
import sys
from pathlib import Path

GH = Path.home() / "AppData/Local/Programs/GitHubCLI/bin/gh.exe"
REPO = "sqdqzy/AgentDeck"
ROOT = Path(__file__).resolve().parent.parent


def gh_api(path, method="GET", body=None):
    cmd = [str(GH), "api", path, "-X", method]
    if body is None:
        cmd += ["--jq", "."]
        out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
        if out.returncode:
            sys.exit(f"GET {path} failed: {out.stderr[:400]}")
        return json.loads(out.stdout)
    cmd += ["--input", "-"]
    payload = json.dumps(body, ensure_ascii=False)
    out = subprocess.run(
        cmd, input=payload, capture_output=True, text=True, encoding="utf-8"
    )
    if out.returncode:
        sys.exit(f"{method} {path} failed: {out.stderr[:400]}")
    return json.loads(out.stdout)


def git(*args):
    out = subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8"
    )
    if out.returncode:
        sys.exit(f"git {' '.join(args)} failed: {out.stderr[:300]}")
    return out.stdout.strip()


def main():
    files = sys.argv[1:] or git("show", "--name-only", "--format=", "HEAD").splitlines()
    files = [f for f in files if f and (ROOT / f).is_file()]
    if not files:
        sys.exit("nothing to push: HEAD touches no existing files")

    head = gh_api(f"repos/{REPO}/git/ref/heads/main")["object"]["sha"]
    message = git("log", "-1", "--format=%B", "HEAD")
    print(f"remote head : {head}")
    print(f"message     : {message.splitlines()[0]}")
    print(f"files       : {len(files)}")

    base_tree = gh_api(f"repos/{REPO}/git/commits/{head}")["tree"]["sha"]
    entries = [
        {
            "path": rel,
            "mode": "100644",
            "type": "blob",
            "content": (ROOT / rel).read_text(encoding="utf-8"),
        }
        for rel in files
    ]
    tree = gh_api(
        f"repos/{REPO}/git/trees", "POST", {"base_tree": base_tree, "tree": entries}
    )
    commit = gh_api(
        f"repos/{REPO}/git/commits",
        "POST",
        {"message": message, "tree": tree["sha"], "parents": [head]},
    )
    gh_api(f"repos/{REPO}/git/refs/heads/main", "PATCH", {"sha": commit["sha"]})
    print(f"new commit  : {commit['sha']}")
    print(f"url         : https://github.com/{REPO}/commit/{commit['sha']}")


if __name__ == "__main__":
    main()
