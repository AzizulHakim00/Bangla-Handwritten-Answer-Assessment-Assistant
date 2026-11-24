"""Verify a detached report bundle; install cryptography first. No files are extracted."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

parser = argparse.ArgumentParser()
parser.add_argument('bundle', type=Path)
parser.add_argument('--trusted-key', type=Path, help='Previously trusted public-key.pem from this school')
args = parser.parse_args()
with zipfile.ZipFile(args.bundle) as archive:
    required = ['approved-marks.pdf', 'manifest.json', 'manifest.sig', 'public-key.pem']
    if any(archive.getinfo(name).file_size > 50_000_000 for name in required):
        raise ValueError('Bundle entry exceeds verification size limit')
    pdf, manifest, signature, bundled_pem = (archive.read(name) for name in required)
pem = args.trusted_key.read_bytes() if args.trusted_key else bundled_pem
key = load_pem_public_key(pem)
if not isinstance(key, Ed25519PublicKey):
    raise ValueError('Expected an Ed25519 signing key')
key.verify(signature, manifest)
data = json.loads(manifest)
if data['format'] != 'assessment-report-v1' or hashlib.sha256(pdf).hexdigest() != data['pdfSha256']:
    raise ValueError('PDF checksum does not match the signed manifest')
print('PASS: manifest signature and PDF checksum verified.')
if not args.trusted_key:
    print('Integrity only: authenticate the school key independently and rerun with --trusted-key.')
