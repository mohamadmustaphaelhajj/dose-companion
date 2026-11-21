"""Preserve the validated Git index so later camera edits stay out of the reminder commit."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def git(*args):
    return subprocess.check_output(["git", *args])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["capture", "verify", "restore-index"])
    args = parser.parse_args()
    root = Path(git("rev-parse", "--show-toplevel").decode().strip())
    metadata = root / git("rev-parse", "--git-path", "reminder-checkpoint.json").decode().strip()
    patch = metadata.with_suffix(".patch")
    head = git("rev-parse", "HEAD").decode().strip()

    if args.action == "capture":
        if metadata.exists():
            raise SystemExit("A checkpoint already exists; refusing to overwrite validated work.")
        diff = git("diff", "--cached", "--binary", "--full-index", "HEAD")
        if not diff:
            raise SystemExit("Stage only the validated reminders change before capturing it.")
        subprocess.run(["git", "diff", "--cached", "--check"], check=True)
        tree = git("write-tree").decode().strip()
        patch.write_bytes(diff)
        metadata.write_text(json.dumps({"base": head, "tree": tree,
            "patch_sha256": hashlib.sha256(diff).hexdigest(),
            "files": git("diff", "--cached", "--name-only", "-z").decode().rstrip("\0").split("\0")}, indent=2) + "\n")
        print("Captured the reminders tree and binary patch without making a commit:", tree)
        return

    saved = json.loads(metadata.read_text())
    if hashlib.sha256(patch.read_bytes()).hexdigest() != saved["patch_sha256"]:
        raise SystemExit("The checkpoint patch has changed; inspect it before proceeding.")
    if head != saved["base"]:
        raise SystemExit("HEAD has changed since validation; inspect the checkpoint before reusing it.")
    if args.action == "restore-index":
        # Replaces staged content only. All camera changes in working files remain intact.
        subprocess.run(["git", "read-tree", saved["tree"]], check=True)
    current = git("write-tree").decode().strip()
    if current != saved["tree"]:
        raise SystemExit("Staged content differs from the reminders snapshot. Use restore-index to restore "
                         "only staging; working files will be preserved.")
    print("The index matches the validated reminders snapshot. No commit has been made.")


if __name__ == "__main__":
    main()
