"""Verify the digest and Ed25519 signature of a report or rubric ZIP."""
import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import load_pem_public_key


def verify(bundle, trusted_key):
    with zipfile.ZipFile(bundle) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names):
            raise ValueError("Duplicate archive entry")
        artifacts = set(names) & {"approved-marks.pdf", "rubric.json"}
        if len(artifacts) != 1 or sum(item.file_size for item in archive.infolist()) > 20 * 1024 * 1024:
            raise ValueError("Invalid artifact or archive size")
        name = artifacts.pop()
        content = archive.read(name)
        manifest = archive.read("manifest.json")
        signature = archive.read("manifest.sig")
        key_bytes = trusted_key.read_bytes() if trusted_key else archive.read("public-key.pem")
    key = load_pem_public_key(key_bytes)
    if not isinstance(key, Ed25519PublicKey):
        raise ValueError("Expected an Ed25519 school public key")
    key.verify(signature, manifest)
    data = json.loads(manifest)
    formats = {"approved-marks.pdf": {"assessment-report-v1", "assessment-report-v2"}, "rubric.json": {"assessment-rubric-v1"}}
    if data["format"] not in formats[name]:
        raise ValueError("Unsupported manifest format")
    checksum = data.get("artifactSha256", data.get("pdfSha256"))
    if hashlib.sha256(content).hexdigest() != checksum:
        raise ValueError("Artifact checksum does not match the signed manifest")
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--trusted-key", type=Path, help="Institution's independently obtained public key in PEM format")
    args = parser.parse_args()
    try:
        metadata = verify(args.bundle, args.trusted_key)
    except (OSError, KeyError, ValueError, InvalidSignature, zipfile.BadZipFile) as error:
        sys.exit(f"Verification failed ({error.__class__.__name__}).")
    print("Valid signature and checksum." if args.trusted_key else "Checksum and signature match the included key. Issuer trust is unverified.")
    print(f"Purpose {metadata.get('purpose', 'MARK_SHEET')} · Issuer {metadata.get('issuer', 'legacy')}")
    print("Use the institutional Verify page for current revision and key revocation status.")


if __name__ == "__main__":
    main()
