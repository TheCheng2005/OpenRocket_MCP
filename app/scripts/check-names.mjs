// Fails the build when two source files would be the same file on macOS or Windows, whose file systems ignore case:
// "Tour.svelte" and "tour.svelte.ts" are both what `import "./tour.svelte"` finds there, and the build breaks only on
// those systems.
import { readdirSync } from "node:fs";
import { join } from "node:path";

const clashes = [];
(function walk(dir) {
  const seen = new Map();
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, e.name);
    if (e.isDirectory()) {
      walk(path);
      continue;
    }
    const key = e.name.replace(/\.svelte\.(ts|js)$/, ".svelte").toLowerCase();
    if (seen.has(key)) clashes.push(`${seen.get(key)} and ${path}`);
    else seen.set(key, path);
  }
})("src");

if (clashes.length) {
  console.error("Files that are the same name on macOS and Windows (rename one):\n  " + clashes.join("\n  "));
  process.exit(1);
}
