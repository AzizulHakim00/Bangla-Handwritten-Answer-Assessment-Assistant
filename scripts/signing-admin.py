"""Rotate or revoke a tenant's Ed25519 signer while the application is stopped."""
import argparse
import base64
import hashlib
import json
import re
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Private SCRIPT_STORAGE directory")
    parser.add_argument("tenant")
    parser.add_argument("operation", choices=("rotate", "revoke"))
    parser.add_argument("--key-id", help="Trusted key fingerprint to revoke")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", args.tenant):
        parser.error("Invalid tenant name")
    registry = args.directory / f".signing-{args.tenant}"
    active = args.directory / (".report-signing-keypair.json" if args.tenant == "school" else f".signing-key-{args.tenant}.json")
    if args.operation == "rotate":
        public_key = base64.b64decode(json.loads(active.read_bytes())["public"], validate=True)
        key_id = hashlib.sha256(public_key).hexdigest()
        registry.mkdir(mode=0o700, exist_ok=True)
        public = registry / f"{key_id}.pub"
        if public.exists() and public.read_bytes() != public_key:
            parser.error("Registered key does not match the active signer")
        if not public.exists():
            with public.open("xb") as file:
                file.write(public_key)
        destination = registry / f"{key_id}.retired-private.json"
        if destination.exists():
            parser.error("Retired key archive already exists")
        active.replace(destination)
        print(f"Retired {key_id}. The application generates a new key on its next signing operation.")
    else:
        key_id = args.key_id or ""
        if not re.fullmatch(r"[0-9a-f]{64}", key_id) or not (registry / f"{key_id}.pub").is_file():
            parser.error("Provide a key ID already registered for this tenant")
        (registry / f"{key_id}.revoked").touch(exist_ok=True)
        print(f"Revoked {key_id}.")


if __name__ == "__main__":
    main()
