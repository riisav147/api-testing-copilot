#!/usr/bin/env python3
"""Build, verify and package an offline-at-runtime local release candidate. Requires JDK, Node and Python 3."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import urllib.request
from xml.etree import ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command, cwd=ROOT, expected=0):
    process = subprocess.run(command, cwd=cwd, check=False)
    if process.returncode != expected:
        raise RuntimeError(f"Command failed ({process.returncode}): {command[0]}")


def versions():
    version = (ROOT / 'VERSION').read_text().strip()
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-rc\.\d+)?', version):
        raise ValueError('Invalid release VERSION')
    pom = ET.parse(ROOT / 'pom.xml').getroot()
    if pom.find('{*}version').text != version or json.loads((ROOT / 'frontend/package.json').read_text())['version'] != version:
        raise ValueError('VERSION, pom.xml and frontend/package.json must agree')
    return version


def copy_file(source, destination):
    if source.is_symlink() or not source.is_file():
        raise ValueError('Release inputs must be ordinary files')
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)


def bundle_jar(source, output, frontend):
    with ZipFile(source) as original, ZipFile(output, 'w') as bundled:
        assets = {p.relative_to(frontend).as_posix(): p for p in frontend.rglob('*') if p.is_file()}
        if 'index.html' not in assets:
            raise ValueError('Build the frontend first')
        names = {'BOOT-INF/classes/static/' + name for name in assets}
        for item in original.infolist():
            if item.filename not in names:
                # Preserve nested library compression modes and Spring Boot classpath entries.
                bundled.writestr(item, original.read(item.filename))
        for name, path in sorted(assets.items()):
            if path.is_symlink():
                raise ValueError('Frontend assets cannot be symlinks')
            bundled.writestr('BOOT-INF/classes/static/' + name, path.read_bytes(), compress_type=ZIP_DEFLATED)


def smoke(bundle, version):
    with tempfile.TemporaryDirectory(prefix='copilot-release-smoke-') as directory:
        working = Path(directory)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        def request(base, path):
            with opener.open(base + path, timeout=5) as response:
                return response.read()
        def start(number):
            log = working / f'backend-{number}.log'
            stream = log.open('wb')
            # Bind localhost explicitly; isolated empty workspace, no provider key/config file reads.
            env = os.environ.copy()
            for name in ('OPENAI_API_KEY', 'SPRING_PROFILES_ACTIVE', 'SPRING_CONFIG_IMPORT', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
                env.pop(name, None)
            env['SPRING_CONFIG_LOCATION'] = 'classpath:/application.yml'
            process = subprocess.Popen(['java', '-jar', str(bundle / 'copilot.jar'), '--spring.profiles.active=demo', '--server.address=127.0.0.1', '--server.port=0', '--copilot.storage-url=jdbc:h2:file:./workspace'], cwd=working, stdout=stream, stderr=subprocess.STDOUT, env=env)
            try:
                end = time.monotonic() + 60
                while time.monotonic() < end:
                    if process.poll() is not None:
                        raise RuntimeError('Packaged backend exited during smoke check')
                    found = re.search(r'Tomcat started on port (\d+)', log.read_text(errors='replace'))
                    if found:
                        base = 'http://127.0.0.1:' + found.group(1)
                        if json.loads(request(base, '/actuator/health'))['status'] == 'UP':
                            return process, stream, base
                    time.sleep(0.2)
                raise RuntimeError('Packaged backend startup exceeded 60 seconds')
            except BaseException:
                stop(process, stream)
                raise
        def stop(process, stream):
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            stream.close()
        process, stream, base = start(1)
        try:
            info = json.loads(request(base, '/api/info'))
            if info['version'] != version or info['mode'] != 'demo':
                raise RuntimeError('Packaged version or mode differs')
            html = request(base, '/').decode()
            assets = re.findall(r'(?:src|href)="(/assets/[^"<>]+)"', html)
            if not assets or not all(request(base, path) for path in assets):
                raise RuntimeError('Bundled UI assets are missing')
            config = working / 'config.json'
            cli = bundle / 'scripts/copilot-ci.py'
            run(['python3', str(cli), '--server-url', base, 'prepare-demo', '--config', str(config)], cwd=working)
            run(['python3', str(cli), '--server-url', base, 'run', '--config', str(config), '--authorize-target', 'sandbox', '--output', str(working / 'results')], cwd=working, expected=1)
            result = json.loads((working / 'results/results.json').read_text())
            if [c['state'] for c in result['cases']] != ['PASSED', 'PASSED', 'FAILED']:
                raise RuntimeError('Known boundary fixture results differ')
            xml = ET.parse(working / 'results/junit.xml').getroot()
            if xml.get('failures') != '1':
                raise RuntimeError('Packaged JUnit results differ')
            projects = json.loads(request(base, '/api/projects'))
        finally:
            stop(process, stream)
        process, stream, base = start(2)
        try:
            restored = json.loads(request(base, '/api/projects/' + projects[0]['id']))
            if len(restored['executions']) != 3:
                raise RuntimeError('Workspace did not survive restart')
        finally:
            stop(process, stream)
    return {'singleJarUi': True, 'demoCli': True, 'junit': True, 'workspaceRestart': True, 'liveAi': 'NOT_RUN'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-dirty', action='store_true', help='Development verification only; manifest records dirty source')
    args = parser.parse_args()
    version = versions()
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT).strip())
    if dirty and not args.allow_dirty:
        raise ValueError('Commit source first, or use --allow-dirty for a labelled development artifact')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    # Install exactly the lockfile dependencies and verify all product layers before packaging.
    run(['npm', 'ci'], ROOT / 'frontend')
    run(['npm', 'test'], ROOT / 'frontend')
    run(['npm', 'run', 'build'], ROOT / 'frontend')
    run(['./mvnw', '-B', '-ntp', 'clean', 'verify'])
    run(['python3', '-m', 'unittest', 'discover', '-s', 'scripts/tests', '-v'])
    output = ROOT / 'target/releases'
    output.mkdir(parents=True, exist_ok=True)
    name = f'api-testing-copilot-{version}'
    with tempfile.TemporaryDirectory(prefix='copilot-package-') as temporary:
        bundle = Path(temporary) / name
        bundle.mkdir()
        bundle_jar(ROOT / f'target/api-testing-copilot-{version}.jar', bundle / 'copilot.jar', ROOT / 'frontend/dist')
        # Explicit allowlist: never copy .env, workspace, credentials, private notes or arbitrary repo content.
        files = {'VERSION': 'VERSION', 'docs/RELEASE.md': 'README.md', 'scripts/copilot-ci.py': 'scripts/copilot-ci.py', 'src/main/resources/static/examples/catalog.openapi.yaml': 'examples/catalog.openapi.yaml', 'docs/RELEASE-VERIFICATION.md': 'docs/RELEASE-VERIFICATION.md', 'scripts/dev-api.py': 'scripts/dev-api.py', 'docs/EVALUATION.md': 'docs/EVALUATION.md', 'docs/CI.md': 'docs/CI.md', 'docs/CONTRACT-CHANGES.md': 'docs/CONTRACT-CHANGES.md', 'target/evaluation/demo-results.json': 'evaluation/demo-results.json', 'src/test/resources/evaluation/corpus.json': 'evaluation/corpus.json'}
        for source, destination in files.items():
            copy_file(ROOT / source, bundle / destination)
        checks = smoke(bundle, version)
        if not args.allow_dirty and (subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT).strip() or subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip() != commit):
            raise ValueError('Source changed during release verification; rebuild from the intended clean commit')
        manifest = {'version': version, 'sourceCommit': commit, 'dirtySource': dirty, 'checks': checks, 'files': {p.relative_to(bundle).as_posix(): digest(p) for p in sorted(bundle.rglob('*')) if p.is_file()}}
        (bundle / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        (bundle / 'SHA256SUMS').write_text(''.join(f'{digest(p)}  {p.relative_to(bundle).as_posix()}\n' for p in sorted(bundle.rglob('*')) if p.is_file()))
        archive = output / f'{name}.zip'
        with ZipFile(archive, 'w', ZIP_DEFLATED) as zipfile:
            for path in sorted(bundle.rglob('*')):
                if path.is_file():
                    zipfile.write(path, name + '/' + path.relative_to(bundle).as_posix())
        archive.with_suffix('.zip.sha256').write_text(f'{digest(archive)}  {archive.name}\n')
    print(f'Verified release candidate: {archive}')


if __name__ == '__main__':
    main()
