"""Build and install the pinned, source-corrected local speech engine."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def run(*args, **kwargs):
    return subprocess.run(list(args), check=True, **kwargs)


def output(*args):
    return subprocess.check_output(list(args), text=True).strip()


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--device', required=True)
parser.add_argument('--build', action='store_true', help='Build the pinned upstream source with the reviewed manifest correction.')
parser.add_argument('--replace-engine', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
spec = json.loads(Path(__file__).with_name('engine.json').read_text(encoding='utf8'))
folder = root / '.local' / 'voice'
folder.mkdir(parents=True, exist_ok=True)
source = folder / 'hayai-source'
apk = folder / 'vescurus-voice.apk'
record = folder / 'vescurus-voice-build.json'

if args.build:
    if not source.exists():
        run('git', 'clone', '--depth', '1', '--branch', 'v' + spec['version'], spec['sourceRepository'], str(source))
    if output('git', '-C', str(source), 'rev-parse', 'HEAD') != spec['sourceCommit']:
        raise SystemExit('Source revision mismatch; build stopped.')
    # Reset neither the checkout nor other work: apply only the two reviewed changes.
    manifest = source / 'app/src/main/AndroidManifest.xml'
    text = manifest.read_text(encoding='utf8')
    text = text.replace('android:exported="true"\n            android:permission="android.permission.BIND_TEXT_SERVICE"', 'android:exported="true"')
    if 'android:permission="android.permission.BIND_TEXT_SERVICE"' in text:
        raise SystemExit('Unexpected upstream service declaration; review required.')
    manifest.write_text(text, encoding='utf8')
    gradle = source / 'app/build.gradle.kts'
    text = gradle.read_text(encoding='utf8').replace('applicationId = "dev.ahmedmohamed.hayaitts"', 'applicationId = "' + spec['package'] + '"')
    if 'applicationId = "' + spec['package'] + '"' not in text:
        raise SystemExit('Unexpected application ID; review required.')
    gradle.write_text(text, encoding='utf8')
    allowed = {'app/build.gradle.kts', 'app/src/main/AndroidManifest.xml'}
    changed = set(output('git', '-C', str(source), 'diff', '--name-only', 'HEAD').splitlines())
    extra = output('git', '-C', str(source), 'ls-files', '--others', '--exclude-standard')
    if changed - allowed or extra:
        raise SystemExit('Unreviewed source changes found; use a clean pinned source checkout.')
    upstream_manifest = output('git', '-C', str(source), 'show', 'HEAD:app/src/main/AndroidManifest.xml')
    expected_manifest = upstream_manifest.replace('android:exported="true"\n            android:permission="android.permission.BIND_TEXT_SERVICE"', 'android:exported="true"')
    upstream_gradle = output('git', '-C', str(source), 'show', 'HEAD:app/build.gradle.kts')
    expected_gradle = upstream_gradle.replace('applicationId = "dev.ahmedmohamed.hayaitts"', 'applicationId = "' + spec['package'] + '"')
    if manifest.read_text(encoding='utf8').strip() != expected_manifest.strip() or gradle.read_text(encoding='utf8').strip() != expected_gradle.strip():
        raise SystemExit('Source patch differs from the reviewed correction; build stopped.')
    # Share only SDK location, never copy application credentials into the provider.
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if sdk:
        (source / 'local.properties').write_text('sdk.dir=' + Path(sdk).as_posix() + '\n', encoding='utf8')
    wrapper = source / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    run(str(wrapper), '-p', str(source), ':app:assembleDebug', '--no-daemon', '--max-workers=2')
    built = source / 'app/build/outputs/apk/debug/app-arm64-v8a-debug.apk'
    if not built.is_file():
        raise SystemExit('Expected arm64 debug APK was not produced.')
    shutil.copyfile(built, apk)
    record.write_text(json.dumps({'sourceCommit': spec['sourceCommit'], 'package': spec['package'], 'apkSha256': digest(apk), 'manifestSha256': digest(manifest), 'gradleSha256': digest(gradle)}, indent=2) + '\n', encoding='utf8')

if not apk.is_file() or not record.is_file():
    raise SystemExit('Source-built engine missing. Set JDK 21 and Android SDK, then rerun with --build. Upstream release APK is deliberately not used.')
proof = json.loads(record.read_text(encoding='utf8'))
if proof['sourceCommit'] != spec['sourceCommit'] or proof['package'] != spec['package'] or proof['apkSha256'] != digest(apk):
    raise SystemExit('Source-build record or APK checksum mismatch; installation stopped.')
base = ['adb', '-s', args.device]
installed_query = subprocess.run(base + ['shell', 'pm', 'path', spec['package']], capture_output=True, text=True)
if installed_query.returncode not in (0, 1):
    raise SystemExit('Could not inspect connected phone packages: ' + installed_query.stderr.strip())
paths = installed_query.stdout.strip().splitlines()
if paths:
    installed = next(x[len('package:'):] for x in paths if x.startswith('package:'))
    current = output(*base, 'shell', 'sha256sum', installed).split()[0]
    if current == proof['apkSha256']:
        print('The recorded source-built voice engine is already installed.')
        print(spec['modelSetup'])
        raise SystemExit(0)
    if not args.replace_engine:
        raise SystemExit('A different local voice build is installed. Review before using --replace-engine.')
run(*base, 'install', '-r', str(apk))
print('Source-built Hayai adapter installed without changing the default phone speech engine.')
print(spec['modelSetup'])
