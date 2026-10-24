#!/usr/bin/env python3
"""Run real-Paper assertions in a new loopback-only disposable flat world, twice.

Never reads production configuration/worlds and never installs anything on production.
Requires a built release/probe jar and a Paper jar supplied with --paper-jar.
"""
import argparse
import datetime
import json
from pathlib import Path
import shutil
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--paper-jar', required=True, type=Path)
parser.add_argument('--java', default='java')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
run = root / 'run' / ('verification-' + stamp)
plugins = run / 'plugins'
config = plugins / 'Paper-Chunk-Randomizer'
config.mkdir(parents=True)
release = max((root / 'build/libs').glob('Paper-Chunk-Randomizer-0.3.0-*.jar'), key=lambda p: p.stat().st_mtime)
shutil.copy2(release, plugins / release.name)
shutil.copy2(root / 'build/libs/RandomizerVerification.jar', plugins / 'RandomizerVerification.jar')
shutil.copy2(args.paper_jar, run / 'paper.jar')
(run / 'eula.txt').write_text('eula=true\n')
settings = json.dumps({'biome': 'minecraft:plains', 'layers': [
    {'block': 'minecraft:bedrock', 'height': 1},
    {'block': 'minecraft:stone', 'height': 96},
    {'block': 'minecraft:dirt', 'height': 3},
    {'block': 'minecraft:grass_block', 'height': 1},
]}, separators=(',', ':'))
(run / 'server.properties').write_text(
    'server-ip=127.0.0.1\nserver-port=25575\nonline-mode=true\nwhite-list=true\n'
    'enable-rcon=false\nenable-query=false\nenable-status=true\nmanagement-server-enabled=false\n'
    'view-distance=2\nsimulation-distance=2\nmax-players=1\nspawn-protection=0\n'
    'level-name=verification\nlevel-seed=230\nlevel-type=minecraft:flat\ngenerate-structures=false\n'
    'generator-settings=' + settings + '\n')
(config / 'config.yml').write_text(
    'debug-level: 0\neffects-enabled: false\nmax-mobs: 0\nenabled-worlds: []\n'
    'custom-blacklist: [STONE, DIRT, GRASS_BLOCK]\n'
    'performance:\n  max-block-visits-per-tick: 1024\n  max-millis-per-tick: 2.0\n  max-queued-chunks: 4\n  pause-above-mspt: 0\n')
print('Disposable verification directory:', run, flush=True)
for phase in (1, 2):
    print('Starting verification phase', phase, flush=True)
    with (run / f'console-phase{phase}.log').open('w') as log:
        process = subprocess.Popen([args.java, '-Xms512M', '-Xmx1536M', '-jar', 'paper.jar', 'nogui'],
                                   cwd=run, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
        started = time.monotonic()
        while process.poll() is None:
            if time.monotonic() - started > 240:
                process.stdin.write('stop\n'); process.stdin.flush()
                process.wait(timeout=45)
                raise SystemExit('Verification timed out; stopped gracefully. Inspect ' + str(run))
            time.sleep(1)
    result = run / f'probe-phase{phase}.json'
    if process.returncode != 0 or not result.exists():
        failure = run / 'probe-failure.txt'
        print(failure.read_text() if failure.exists() else 'Server did not reach the verification plugin.', flush=True)
        raise SystemExit('Verification failed; inspect ' + str(run / f'console-phase{phase}.log'))
    print(json.dumps(json.loads(result.read_text())), flush=True)
print('Both real-Paper verification phases passed.', flush=True)
