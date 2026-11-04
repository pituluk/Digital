const fs = require('node:fs/promises');
const path = require('node:path');

async function collectEntries(root, directory = '', result = { directories: [], files: [] }) {
  const entries = await fs.readdir(path.join(root, directory), { withFileTypes: true });

  for (const entry of entries) {
    const relativePath = path.posix.join(directory.split(path.sep).join('/'), entry.name);
    if (entry.isDirectory()) {
      result.directories.push(relativePath);
      await collectEntries(root, path.join(directory, entry.name), result);
    } else if (entry.isFile()) {
      result.files.push(relativePath);
    }
  }

  return result;
}

async function main() {
  const [libraryPath, manifestPath, sha] = process.argv.slice(2);
  if (!libraryPath || !manifestPath || !sha || !/^[a-f0-9]{40}$/i.test(sha)) {
    throw new Error('Usage: node scripts/create-lib-manifest.js <lib-directory> <manifest-file> <git-sha>');
  }

  const library = path.resolve(libraryPath);
  const manifest = await collectEntries(library);
  manifest.directories.sort();
  manifest.files.sort();
  manifest.sha = sha;

  const output = path.resolve(manifestPath);
  await fs.mkdir(path.dirname(output), { recursive: true });
  await fs.writeFile(output, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
