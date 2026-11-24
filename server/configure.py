"""Create local-only server credentials. Never echo an API key or phone pairing token."""
import argparse
import getpass
import os
from pathlib import Path
import secrets

p = argparse.ArgumentParser()
p.add_argument('--prepare', action='store_true', help='Prepare phone pairing before adding the API key')
args = p.parse_args()
path = Path(__file__).with_name('.env')
values = {}
if path.exists():
    for line in path.read_text().splitlines():
        if '=' in line:
            key, value = line.split('=', 1); values[key] = value
values.setdefault('BRIDGE_TOKEN', secrets.token_urlsafe(32))
values.setdefault('OPENAI_MODEL', 'gpt-6.2-astra')
# Standard retention for this personal experiment was explicitly approved by the owner.
values.setdefault('RETENTION_POLICY', 'standard')
if not args.prepare:
    key = getpass.getpass('OpenAI project API key (hidden; stored only in server/.env): ').strip()
    if not key or '\n' in key or not key.isascii():
        raise SystemExit('No valid key supplied; configuration unchanged.')
    values['OPENAI_API_KEY'] = key
temporary = path.with_name(".env.partial")
fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
os.fchmod(fd, 0o600)
with os.fdopen(fd, 'w') as output:
    output.write(''.join(name + '=' + value + '\n' for name, value in values.items()))
    output.flush(); os.fsync(output.fileno())
os.replace(temporary, path)
print('Local configuration saved. No credentials printed. The bridge reads changes without restarting.')
print('The Android OpenAI key field does not exist: the API key stays on this Mac.')
