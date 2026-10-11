#!/usr/bin/env node
'use strict';
// panamaview — create a panamaview project.
//
// This used to be a launcher: it handed its arguments to a native executable the package also
// carried, and the executable did the work. It is now the work itself. The reason is not
// preference — the executable was 13 MB of a per-platform build, for a job whose whole content is
// "copy a directory and rename three strings", and Node is already present in anyone who can
// install the package at all.
//
// The commands, options and messages are the same as the binary's were, deliberately: the
// packaging, the docs and the verification scripts all describe this surface, and a port is not a
// reason to change it.

const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');

const scaffold = require('./src/scaffold.js');

const OK = 0;
const FAILED = 1;
const USAGE = 2;

/** Thrown for anything the caller got wrong, as opposed to anything the filesystem did. */
class UsageError extends Error {}

async function main(argv) {
  const args = argv.slice(2);
  if (args.length === 0) {
    printHelp(null);
    return USAGE;
  }

  try {
    switch (args[0]) {
      case 'create': case 'new': return await create(args.slice(1));
      case 'templates': case 'list': return templates();
      case '--selftest': return selftest();
      case '--version': case '-V': case 'version': return version();
      case '--help': case '-h': case 'help': printHelp(null); return OK;
      default:
        printHelp(`unknown command: ${args[0]}`);
        return USAGE;
    }
  } catch (e) {
    if (e instanceof UsageError) {
      process.stderr.write(`panamaview: ${e.message}\n`);
      process.stderr.write("  run 'panamaview help' for usage\n");
      return USAGE;
    }
    process.stderr.write(`panamaview: ${e.message}\n`);
    return FAILED;
  }
}

// ------------------------------------------------------------------ commands

function version() {
  const catalog = scaffold.loadCatalog();
  process.stdout.write(`panamaview ${catalog.version}\n`);
  return OK;
}

function templates() {
  const catalog = scaffold.loadCatalog();
  process.stdout.write(`panamaview ${catalog.version}\n\n`);
  for (const t of catalog.templates) {
    const need = t.needsNode ? 'needs Node' : 'no Node';
    process.stdout.write(`  ${t.name.padEnd(17)} ${need.padEnd(13)} ${t.summary}\n`);
  }
  process.stdout.write('\n');
  process.stdout.write("  'needs Node' is about editing and building, not running: a released app\n");
  process.stdout.write('  is a native executable plus static files, with no Node anywhere.\n');
  return OK;
}

async function create(args) {
  if (args.length === 0) {
    throw new UsageError('create needs a directory: panamaview create <dir>');
  }

  const directory = args[0];
  let templateName = null;
  let pkgOverride = null;
  let titleOverride = null;
  let force = false;

  for (let i = 1; i < args.length; i++) {
    const arg = args[i];
    switch (arg) {
      case '-t': case '--template': templateName = value(args, ++i, arg); break;
      case '-p': case '--package': pkgOverride = value(args, ++i, arg); break;
      case '--title': titleOverride = value(args, ++i, arg); break;
      case '-f': case '--force': force = true; break;
      default: throw new UsageError(`unknown option: ${arg}`);
    }
  }

  const catalog = scaffold.loadCatalog();

  /*
   * Before anything is written. A PANAMAVIEW_LIBRARY that is set to something that is not a library
   * is a hard error, and it should not leave half a generated project behind — and a library that
   * is found is worth knowing about before the project exists, not after.
   */
  const library = scaffold.findLibrary(catalog.version);

  const target = path.resolve(directory);
  if (fs.existsSync(target) && !fs.statSync(target).isDirectory()) {
    throw new Error(`${target} exists and is not a directory`);
  }

  const template = templateName != null
    ? requireTemplate(catalog, templateName)
    : await chooseTemplate(catalog);

  // Defaults come from the directory name, so the common case needs no options. For "create ."
  // that is the directory the user is standing in, which is what they mean by "here".
  const stem = path.basename(target);
  const names = scaffold.namesForDirectory(stem, pkgOverride, titleOverride);

  const problem = scaffold.checkPackage(names.pkg);
  if (problem != null) {
    throw new UsageError(`bad package name: ${problem}`);
  }

  scaffold.checkTarget(target, force);
  const written = scaffold.write(template, target, names);
  if (library != null) {
    scaffold.bundle(library, target);
  }

  report(target, template, names, written, library);
  return OK;
}

function report(target, template, names, written, library) {
  const out = process.stdout;
  out.write(`creating ${target}\n`);
  out.write(`  template  ${template.name.padEnd(17)} ${template.summary}\n`);
  out.write(`  package   ${names.pkg}\n`);
  out.write(`  title     ${names.title}\n`);
  out.write(`  ${written} files written\n`);
  if (library != null) {
    // One line, always, when there is a library: the alternative is a project that does not build
    // for a reason nothing in its output mentions.
    out.write(`  library   ${library}\n`);
  }
  out.write('\nnext\n');
  out.write(`  cd ${target}\n`);
  // Built from the template's own file list, so a template that adds or drops a script cannot leave
  // this advice describing something that is not there.
  if (template.files.includes('dev.sh')) {
    const note = template.needsNode ? '   (needs Node)' : '';
    out.write(`  ${'./dev.sh'.padEnd(17)} dev server with hot reload${note}\n`);
  }
  out.write(`  ${'./run.sh'.padEnd(17)} production build, served from the app's own scheme\n`);
  out.write(`  ${'./build-native.sh'.padEnd(17)} → target/${names.app}\n`);
  if (library != null) {
    out.write('\n');
    out.write(`  The project carries its own copy of panamaview, in ${scaffold.CARRIED}/lib, so it\n`);
    out.write('  builds with nothing configured: ./run.sh is all it needs.\n');
  }
  if (template.files.includes('README.md')) {
    out.write('\n');
    out.write('  README.md has the rest.\n');
  }
}

function requireTemplate(catalog, name) {
  const template = catalog.templates.find((t) => t.name === name);
  if (template == null) {
    const known = catalog.templates.map((t) => t.name);
    throw new UsageError(`no template named '${name}'; try one of ${known.join(', ')}`);
  }
  return template;
}

/*
 * Asks, if there is anyone to ask.
 *
 * Without a terminal there is no question to put: reading from a pipe would consume whatever is
 * there and answer with it. So a non-interactive run gets the list of names and an error, which is
 * the only thing that can be acted on — and it is what CI exercises.
 *
 * `process.stdin.isTTY` rather than a read that comes back empty: this has to decide *before*
 * asking, so that a piped run fails with the names instead of hanging on a prompt nobody sees.
 */
async function chooseTemplate(catalog) {
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    const known = catalog.templates.map((t) => t.name);
    throw new UsageError(`no --template given and no terminal to ask on; `
      + `pass --template <${known.join('|')}>`);
  }

  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
  try {
    process.stdout.write('choose a template\n\n');
    catalog.templates.forEach((t, i) => {
      const need = t.needsNode ? 'needs Node' : 'no Node';
      process.stdout.write(`  ${i + 1}) ${t.name.padEnd(17)} ${need.padEnd(13)} ${t.summary}\n`);
    });
    process.stdout.write('\n');

    for (;;) {
      const line = await ask(rl, '> ');
      if (line === null) throw new UsageError('no template chosen');
      const answer = line.trim();
      const byName = catalog.templates.find((t) => t.name === answer);
      if (byName) return byName;
      const index = Number.parseInt(answer, 10);
      if (Number.isInteger(index) && index >= 1 && index <= catalog.templates.length) {
        return catalog.templates[index - 1];
      }
      process.stdout.write('  not one of the listed templates — a name or a number\n');
    }
  } finally {
    rl.close();
  }
}

/** One line from the terminal, or null at end of input. */
function ask(rl, prompt) {
  return new Promise((resolve) => rl.question(prompt, resolve));
}

/*
 * Checks that everything the package promises is actually present.
 *
 * This is the check for the failure that has no other symptom: a package published without some of
 * its templates still starts and still lists them, and only fails when someone runs create. The
 * files most likely to go missing are the ones whose names begin with a dot, because they look like
 * build noise and packaging tools drop them by default. So the selftest does not trust the file
 * list — it opens every file in it.
 */
function selftest() {
  const catalog = scaffold.loadCatalog();
  let files = 0;
  let executable = 0;
  for (const template of catalog.templates) {
    for (const rel of template.files) {
      // Through the module, not beside it: a template's ignore file is stored under a name npm
      // keeps, and scaffold.js is the one place that knows the two names are the same file.
      const full = scaffold.sourcePath(template.name, rel);
      const bytes = fs.readFileSync(full);
      if (bytes.length === 0) {
        throw new Error(`empty resource: ${template.name}/${rel}`);
      }
      if (rel.startsWith('.')) {
        process.stdout.write(`  dotfile present: ${template.name}/${rel} (${bytes.length} bytes)\n`);
      }
      files++;
      if (fs.statSync(full).mode & 0o111) executable++;
    }
  }
  process.stdout.write(`panamaview ${catalog.version}: ${catalog.templates.length} template(s), `
    + `${files} file(s), ${executable} executable\n`);
  return OK;
}

// ------------------------------------------------------------------ helpers

function value(args, index, option) {
  if (index >= args.length) throw new UsageError(`${option} needs a value`);
  return args[index];
}

function printHelp(problem) {
  const out = process.stdout;
  if (problem != null) {
    out.write(`panamaview: ${problem}\n\n`);
  }
  out.write(`panamaview — create a panamaview project

usage
  panamaview create <dir> [options]
  panamaview templates
  panamaview version | help

create options
  -t, --template <name>   which template to use; without it you are asked,
                          unless there is no terminal, in which case it is required
  -p, --package <pkg>     Java package for the app   [default: com.example.<dir>]
      --title <text>      window and page title      [default: from the directory]
  -f, --force             write into a directory that is not empty

examples
  panamaview templates
  panamaview create my-chat-app --template vue
  panamaview create . --template vanilla

A generated project builds with Maven and runs on the JVM:

  cd my-chat-app && ./run.sh

and produces a standalone executable with GraalVM:

  ./build-native.sh

Nothing generated at run time needs Node. Three of the five templates need it to
build, which 'panamaview templates' marks.
`);
}

/*
 * The exit code has to be set, not thrown: the caller is a shell (or npx), and `process.exitCode`
 * with a drained event loop is the one way to leave a status behind without truncating stdout that
 * has not been flushed yet.
 */
main(process.argv).then((code) => {
  process.exitCode = code;
}).catch((e) => {
  process.stderr.write(`panamaview: ${e.message}\n`);
  process.exitCode = FAILED;
});
