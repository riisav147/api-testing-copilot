import importlib.util
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile, ZIP_STORED

SPEC = importlib.util.spec_from_file_location('build_release', Path(__file__).parents[1] / 'build-release.py')
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ReleasePackagingTest(unittest.TestCase):
    def test_ui_assembly_preserves_executable_jar_libraries_and_exact_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = root / 'original.jar'
            with ZipFile(original, 'w') as jar:
                jar.writestr('BOOT-INF/lib/dependency.jar', b'library', compress_type=ZIP_STORED)
                jar.writestr('BOOT-INF/classes/static/index.html', b'old-index')
                jar.writestr('META-INF/MANIFEST.MF', b'Main-Class: example\n')
            assets = root / 'dist'
            (assets / 'assets').mkdir(parents=True)
            (assets / 'index.html').write_text('<script src="/assets/app.js"></script>')
            (assets / 'assets/app.js').write_text('app')
            release.bundle_jar(original, root / 'bundle.jar', assets)
            with ZipFile(root / 'bundle.jar') as jar:
                self.assertEqual(jar.getinfo('BOOT-INF/lib/dependency.jar').compress_type, ZIP_STORED)
                self.assertEqual(jar.read('BOOT-INF/lib/dependency.jar'), b'library')
                self.assertEqual(jar.read('BOOT-INF/classes/static/assets/app.js'), b'app')
                self.assertEqual(jar.namelist().count('BOOT-INF/classes/static/index.html'), 1)

    def test_assembly_requires_a_built_index_and_refuses_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'dist').mkdir()
            with ZipFile(root / 'source.jar', 'w'):
                pass
            with self.assertRaises(ValueError):
                release.bundle_jar(root / 'source.jar', root / 'out.jar', root / 'dist')
            (root / 'private').write_text('private')
            (root / 'linked').symlink_to(root / 'private')
            with self.assertRaises(ValueError):
                release.copy_file(root / 'linked', root / 'copied')

    def test_release_version_files_agree(self):
        self.assertEqual(release.versions(), '0.1.0-rc.1')


if __name__ == '__main__':
    unittest.main()
