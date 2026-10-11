'use strict';
// The scaffolder: read a template out of this package and write it as a project.
//
// This is the whole of it. It was Java, and the Java version had pieces of machinery that exist
// only because a jar cannot do what a directory tree can — a generated manifest listing the files,
// an executable bit restored from that manifest, and a resource reader that treats a missing file
// as a build error. None of that is needed here: the templates are directories in this package, npm
// preserves the mode bits (verified, not assumed), and a missing file is ENOENT.
//
// What did *not* get simpler, and should not: the rename is still a single pass, the package is
// still written both ways in both places, and the carried framework is still found by the same
// rules. Those are about the product, not the language.

const fs = require('node:fs');
const path = require('node:path');

// ---------------------------------------------------------------- layouts

/*
 * Where things sit in the published package. Two levels up from this file is the package root:
 *
 *   npm/cli.js
 *   npm/src/scaffold.js      <- here
 *   npm/templates/<template>/            a template, copied in at pack time
 *     .gitignore.packed                   its ignore file, under the name npm keeps
 *   npm/lib/                 the framework, laid out as a Maven repository, staged at pack time
 *
 * `lib/` is not this file's directory and must not be. The generated pom carries
 * `file://${project.basedir}/.panamaview/lib`, so `lib` at the package root is the name Maven
 * resolves against — it is a contract with the generated project, not an internal convention.
 * Naming the JavaScript `lib/` would collide with it (it did, once, during the port).
 */
const PACKAGE_ROOT = path.join(__dirname, '..');
const TEMPLATES_DIR = path.join(PACKAGE_ROOT, 'templates');

// ---------------------------------------------------------------- templates

/**
 * Which templates exist, in the order they are offered.
 *
 * A description lives here rather than in the template because it has to be short enough to read in
 * a picker, and a template's README is the wrong shape for that. Adding a directory under
 * templates/ without adding it here is an error, not a silent omission — see loadCatalog.
 */
const REGISTRY = [
  { name: 'vanilla', summary: 'plain HTML, CSS and JS — no build step', needsNode: false },
  { name: 'vanilla-tailwind', summary: 'plain HTML styled with Tailwind', needsNode: false },
  { name: 'react', summary: 'React, built by Vite', needsNode: true },
  { name: 'vue', summary: 'Vue 3 with a history-mode router, built by Vite', needsNode: true },
  { name: 'angular', summary: 'Angular with a history-mode router', needsNode: true },
];

/*
 * npm refuses to pack a file named `.gitignore`, in any directory. It is in npm-packlist's list of
 * names that are always ignored, and `files` cannot override it — measured: a tarball whose `files`
 * names the path outright still comes back without it. Every other dotfile travels (a template's
 * .nvmrc would; npm's rule is that one name), so this is the whole of what the rule costs.
 *
 * It is not a detail to shrug at. A template's .gitignore is what keeps a generated project from
 * committing target/, node_modules/ and its own copy of the framework, and scripts/verify-scaffold.sh
 * fails a generated project that has none — while the package shipped without them would pass every
 * check made against the checkout, because the checkout has them. So npm/scripts/build-package.sh
 * stages the file under PACKED_IGNORE, which npm keeps, and the scaffolder writes it out under the
 * name the template uses.
 */
const REAL_IGNORE = '.gitignore';
const PACKED_IGNORE = '.gitignore.packed';
const REAL_TAIL = /(^|\/)\.gitignore$/;
const PACKED_TAIL = /(^|\/)\.gitignore\.packed$/;

/** The name a template file is stored under inside the package. */
function packedName(rel) { return rel.replace(REAL_TAIL, '$1' + PACKED_IGNORE); }

/** And the name the generated project gets for it, which is the template's own. */
function realName(rel) { return rel.replace(PACKED_TAIL, '$1' + REAL_IGNORE); }

/** Where a template file really is, given the name it will have in the generated project. */
function sourcePath(template, rel) {
  return path.join(TEMPLATES_DIR, template, packedName(rel));
}

/** Every file in a template, as paths relative to the template, sorted. */
function filesOf(name) {
  const root = path.join(TEMPLATES_DIR, name);
  const out = [];
  const walk = (dir, prefix) => {
    // sorted, so the write order is the same on every machine and two runs produce the same log
    const entries = fs.readdirSync(dir, { withFileTypes: true })
      .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
    for (const entry of entries) {
      const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full, rel);
      } else if (entry.isFile()) {
        out.push(realName(rel));
      } else {
        // A symlink in a template is not a file to copy: it would arrive as whatever it pointed at
        // on the machine that packed the tarball. The Java version refused these too.
        throw new Error(`templates/${name}/${rel} is not a regular file`);
      }
    }
  };
  walk(root, '');
  return out;
}

/**
 * The catalog, validated.
 *
 * A template directory with no description, or a description with no directory, means the two
 * sources have drifted. Both are silent failures: the first offers a template nobody can explain,
 * the second shows a menu entry that generates nothing. The Java version had the same check for the
 * same reason, and it is why every entry point loads the catalog through here.
 */
function loadCatalog() {
  /*
   * templates/ is staged, not source: it is copied into the package at pack time because npm's
   * `files` cannot reach outside the package directory, and the tracked templates live at the
   * repository root. So running this file out of a checkout finds nothing here until
   * `npm/scripts/build-package.sh` has run — which is a normal state to be in, and worth saying
   * plainly rather than letting a bare ENOENT out of readdirSync.
   */
  if (!fs.existsSync(TEMPLATES_DIR)) {
    throw new Error(`${TEMPLATES_DIR} does not exist. It is staged, not source: run`
      + ' npm/scripts/build-package.sh to copy the templates in. (The published package always has'
      + ' it; only a checkout needs the step.)');
  }

  const onDisk = fs.readdirSync(TEMPLATES_DIR, { withFileTypes: true })
    .filter((e) => e.isDirectory())
    .map((e) => e.name)
    .sort();

  const described = REGISTRY.map((t) => t.name).sort();
  const undescribed = onDisk.filter((n) => !described.includes(n));
  const missing = described.filter((n) => !onDisk.includes(n));
  if (undescribed.length || missing.length) {
    throw new Error('templates and descriptions disagree'
      + (undescribed.length ? `; templates with no description: ${undescribed}` : '')
      + (missing.length ? `; descriptions with no template: ${missing}` : ''));
  }

  return {
    version: require(path.join(PACKAGE_ROOT, 'package.json')).version,
    templates: REGISTRY.map((t) => ({ ...t, files: filesOf(t.name) })),
  };
}

// ---------------------------------------------------------------- the rename

/*
 * A template carries real values — com.example, myapp, My App — rather than placeholders, so that a
 * template stays a working project on its own: it can be copied out of the repository by hand, and
 * scripts/verify-templates.sh builds it straight from the working tree. The cost is that a generated
 * project has to rename these three exactly, everywhere they appear, and missing one produces code
 * that compiles and misbehaves.
 *
 * Substitution is a *single pass* over an alternation, not three sequential replaces. That is not
 * style: with sequential replaces, `--package com.example.myapp` would have the package rewritten by
 * the later `myapp` rule, silently yielding `com.example.<app>`. One pass cannot rewrite text it just
 * inserted.
 *
 * Both spellings of the package, in both places. A package is written with dots in code and with
 * slashes in a path — and a path can appear inside a *text* file: the templates' READMEs name
 * `src/main/java/com/example/Main.java`. Handling only the dot form in text left that one behind,
 * which is the kind of miss this whole single-pass design exists to prevent.
 */
const IN_TEXT = /com\.example|com\/example|myapp|My App/g;
const IN_PATH = /com\.example|com\/example/g;

function substitute(value, pattern, n) {
  return value.replace(pattern, (match) => {
    switch (match) {
      case 'com.example': return n.pkg;
      case 'com/example': return n.pkg.replace(/\./g, '/');
      case 'myapp': return n.app;
      default: return n.title;              // "My App"
    }
  });
}

const names = {
  text: (value, n) => substitute(value, IN_TEXT, n),
  path: (value, n) => substitute(value, IN_PATH, n),
};

/**
 * Derives what a project should be called from the directory it goes into, so the common case needs
 * no options at all.
 */
function namesForDirectory(directoryName, pkgOverride, titleOverride) {
  let app = directoryName.toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  if (app === '' || !/[a-z]/.test(app[0])) {
    // A Maven artifactId may start with a digit but a Java identifier may not, and one name has to
    // serve both. A directory called "2026-notes" should not produce a project that cannot compile.
    app = 'app' + (app === '' ? '' : '-' + app);
  }

  const pkg = pkgOverride != null ? pkgOverride : 'com.example.' + app.replace(/-/g, '');
  const title = titleOverride != null ? titleOverride : titleCase(directoryName);
  return { app, pkg, title };
}

function titleCase(value) {
  const words = value.split(/[^A-Za-z0-9]+/).filter(Boolean);
  if (words.length === 0) return 'My App';
  return words.map((w) => w[0].toUpperCase() + w.slice(1)).join(' ');
}

/*
 * Java keywords are Java's business, not Node's. A generator that emits code which fails to compile
 * has done worse than refusing: the error surfaces later, in a file the user did not write, about a
 * name they did not type. `record`, `sealed`, `permits`, `var` and `yield` are in the list because
 * this framework generates Java 25.
 */
const JAVA_KEYWORDS = new Set([
  'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'const',
  'continue', 'default', 'do', 'double', 'else', 'enum', 'extends', 'final', 'finally', 'float',
  'for', 'goto', 'if', 'implements', 'import', 'instanceof', 'int', 'interface', 'long', 'native',
  'new', 'package', 'private', 'protected', 'public', 'return', 'short', 'static', 'strictfp',
  'super', 'switch', 'synchronized', 'this', 'throw', 'throws', 'transient', 'try', 'void',
  'volatile', 'while', 'true', 'false', 'null', 'record', 'sealed', 'permits', 'var', 'yield',
]);

/** Rejects a package that would not compile, before writing anything. */
function checkPackage(pkg) {
  if (pkg == null || pkg.trim() === '') return 'package name is empty';
  for (const segment of pkg.split('.')) {
    if (segment === '') return `'${pkg}' has an empty segment`;
    if (!/^[A-Za-z_$]/.test(segment)) return `'${segment}' does not start with a letter, _ or $`;
    const bad = [...segment].slice(1).find((c) => !/[A-Za-z0-9_$]/.test(c));
    if (bad) return `'${segment}' contains '${bad}'`;
    if (JAVA_KEYWORDS.has(segment)) return `'${segment}' is a Java keyword`;
  }
  return null;
}

// ---------------------------------------------------------------- writing

/**
 * Refuses to fill a directory that already has something in it.
 *
 * `.git` is the exception: scaffolding into a fresh `git init` is ordinary, and refusing because of
 * a directory the user just made would be the tool being pedantic about something that is not its
 * business.
 */
function checkTarget(target, force) {
  if (force || !fs.existsSync(target) || !fs.statSync(target).isDirectory()) return;
  const occupied = fs.readdirSync(target)
    .filter((name) => name !== '.git' && name !== '.DS_Store')
    .sort();
  if (occupied.length === 0) return;
  throw new Error(`${target} is not empty (${occupied.slice(0, 5).join(', ')}`
    + `${occupied.length > 5 ? ', …' : ''}).\n`
    + '  Use --force to write into it anyway, or pick an empty directory.');
}

/*
 * Whether to treat a file as text is decided from the bytes, not from the extension: an extension
 * list has to be kept up to date, and the day it is not is the day a template adds a file type
 * nobody thought about.
 *
 * Node has no "is this valid UTF-8" call, but it has the same rule it would be implemented with:
 * decoding with a fatal decoder throws instead of substituting U+FFFD. A file that is decoded
 * wrongly is not silently accepted — anything wrongly treated as binary keeps its original tokens,
 * and verify-scaffold.sh fails if a single one survives in the generated project. So the cost of
 * guessing wrong here is a build failure, not a broken project.
 */
const FATAL_DECODER = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });

function isText(bytes) {
  if (bytes.includes(0)) return false;          // no text format contains NUL
  try {
    FATAL_DECODER.decode(bytes);
    return true;
  } catch {
    return false;
  }
}

/** Returns the number of files written. */
function write(template, target, n) {
  let written = 0;
  for (const rel of template.files) {
    const source = sourcePath(template.name, rel);
    const destination = path.join(target, names.path(rel, n));
    fs.mkdirSync(path.dirname(destination), { recursive: true });

    const bytes = fs.readFileSync(source);
    const out = isText(bytes) ? Buffer.from(names.text(bytes.toString('utf8'), n), 'utf8') : bytes;
    fs.writeFileSync(destination, out);

    /*
     * The mode is copied, not assumed. npm preserves it — verified by packing a tarball with an
     * executable in it and looking at what came out — so a template's own bits are the truth and
     * there is nothing to restore from a manifest. `run.sh` arriving 0644 is exactly the failure
     * this prevents: `./run.sh` would say "permission denied" in a project that otherwise looks
     * complete.
     */
    fs.chmodSync(destination, fs.statSync(source).mode & 0o777);

    written++;
  }
  return written;
}

// ------------------------------------------------------------------ the library it carries

/*
 * Where the carried framework goes inside a generated project. One directory, so that a project says
 * what it is carrying in one look, and so that removing it is one `rm -rf`.
 */
const CARRIED = '.panamaview';
const IGNORED = CARRIED + '/';

/*
 * The copy of the framework that travels with a packaged scaffolder.
 *
 * A generated project depends on io.github.lihuu:panamaview, which is not on Maven Central. A
 * scaffolder handed to someone through npm therefore has to carry the framework with it, or it
 * generates a project whose one dependency cannot be resolved — an error that lands in a file the
 * user did not write, about an artifact they never heard of. So the package ships the framework laid
 * out as a Maven repository, and the generated project is pointed at it with a file:// repository;
 * see bundle().
 *
 * *Nothing is searched for.* There are exactly three rules, in this order:
 *
 *   1. PANAMAVIEW_LIBRARY, if it is set and not empty. Set but not a library is a hard error, not a
 *      fall-through: someone who names a directory meant that directory.
 *   2. <package root>/lib — the layout this package ships.
 *   3. Nothing. No wiring, no complaint: this is the checkout, where the framework comes from a
 *      repository or from `mvn install`, and that is a supported way to work. The verification
 *      scripts depend on it staying quiet.
 *
 * The Java version needed a fourth and fifth rule, and a `toRealPath()` call, because it asked
 * `ProcessHandle` where the executable was invoked from and a package manager answers that with the
 * symlink rather than the file. Node does not have that problem: `__filename` is already the real
 * path (verified — a module reached through a symlink reports the file it resolves to). So a package
 * linked into a shared bin/ reports its own directory, which is what these rules want.
 *
 * Adding candidates to that list is how a "where is it?" question turns into a guess that sometimes
 * finds the wrong thing. A library is a directory with the framework's jar and pom inside it at the
 * version this scaffolder generates for — not merely a directory with the right name, which is what
 * a wrong guess would look like.
 */
const ENV = 'PANAMAVIEW_LIBRARY';

/*
 * The repository id a generated project gives it.
 *
 * Maven writes this id beside the artifact it resolved from, in _remote.repositories, which is what
 * turns "the bundled copy was used rather than one from this machine" into something that can be
 * checked instead of assumed. scripts/verify-scaffold-library.sh checks it against an empty local
 * repository.
 */
const REPOSITORY_ID = 'panamaview-bundled';

const GROUP_PATH = 'io/github/lihuu/panamaview';

function artifactsUnder(root, version) {
  return path.join(root, GROUP_PATH, version);
}

function holds(root, version) {
  const at = artifactsUnder(root, version);
  return fs.existsSync(path.join(at, `panamaview-${version}.jar`))
    && fs.existsSync(path.join(at, `panamaview-${version}.pom`));
}

/** Finds the library this scaffolder carries, or null if it carries none. */
function findLibrary(version) {
  const named = process.env[ENV];
  if (named != null && named.trim() !== '') {
    const root = path.resolve(named);
    if (!holds(root, version)) {
      throw new Error(`${ENV} is set to ${named}, which is not a panamaview library.`
        + ' Expected a Maven repository root holding\n'
        + `  ${path.join(GROUP_PATH, version)}/panamaview-${version}.jar\n`
        + `  ${path.join(GROUP_PATH, version)}/panamaview-${version}.pom\n`
        + `  Unset ${ENV} to generate a project that finds the framework the way this checkout does.`);
    }
    return root;
  }

  const beside = path.join(PACKAGE_ROOT, 'lib');
  if (holds(beside, version)) return beside;
  return null;
}

/**
 * Wires a generated project to the framework copy the package carries.
 *
 * Three things, all three needed. The copy, because the framework is not on Maven Central. The
 * repository that points at the copy, because without it Maven resolves the framework from wherever
 * the machine happens to have it — which is the failure this whole arrangement exists to remove, and
 * it is silent on a machine that has it. And the ignore entry, because otherwise every generated
 * project commits its own copy of a jar.
 */
function bundle(library, target) {
  copyTree(library, path.join(target, CARRIED, 'lib'));
  addRepository(path.join(target, 'pom.xml'));
  ignoreCarried(path.join(target, '.gitignore'));
}

/*
 * Copies the library into the project.
 *
 * The destination is emptied first rather than written over. A second run with --force would
 * otherwise merge into what the first one left, and a framework jar whose name changed between
 * versions would sit there for ever with nothing to notice it.
 */
function copyTree(source, destination) {
  const down = path.relative(source, destination);
  const up = path.relative(destination, source);
  // A source inside the destination, or the other way round, would have the delete below destroy
  // what is about to be copied. Nothing sensible asks for that, so it is refused rather than
  // half-handled.
  if (down === '' || (!down.startsWith('..') && !path.isAbsolute(down))
      || up === '' || (!up.startsWith('..') && !path.isAbsolute(up))) {
    throw new Error(`refusing to copy the library at ${source} to ${destination}:`
      + ' one of them contains the other');
  }

  fs.rmSync(destination, { recursive: true, force: true });
  fs.cpSync(source, destination, { recursive: true, preserveTimestamps: true });
}

/**
 * Declares the carried library as a repository in the generated pom.
 *
 * Before <dependencies>, next to the dependency it resolves. The url is built from ${project.basedir}
 * rather than from an absolute path: a project is generated in one place and built in another often
 * enough — moved, copied, checked out again — that a path frozen where the scaffolder happened to
 * run would be a project that stops building for no visible reason.
 */
function addRepository(pom) {
  const text = fs.readFileSync(pom, 'utf8');
  // A template's pom is written out fresh on every run, so a second run cannot accumulate blocks.
  // This is here for a template that arrives with one of its own.
  if (text.includes(`<id>${REPOSITORY_ID}</id>`)) return;

  const block = `  <!--
    The framework travels inside the project: it is not on Maven Central, so the copy this url
    points at is what makes ./run.sh work with no repository configured. The url is relative to
    the project rather than fixed at generation time, because projects get moved and copied. The
    id is what Maven records as the artifact's origin, so a build log naming it is saying this
    copy was the one used.
  -->
  <repositories>
    <repository>
      <id>${REPOSITORY_ID}</id>
      <url>file://\${project.basedir}/${CARRIED}/lib</url>
    </repository>
  </repositories>

`;

  const at = lineStartingWith(text, '<dependencies');
  if (at < 0) {
    throw new Error(`${pom} has no <dependencies> for the bundled repository to go before.`
      + ' Every template has one; a template without it has nothing for the carried framework'
      + ' to resolve.');
  }
  fs.writeFileSync(pom, text.slice(0, at) + block + text.slice(at), 'utf8');
}

/** The offset of the first line that begins with a tag, or -1 if there is none. */
function lineStartingWith(text, tag) {
  const lines = text.split('\n');
  let offset = 0;
  for (const line of lines) {
    // trimmed rather than a prefix test on the whole text: the poms are indented, and whatever
    // ending the template carries is not this function's business.
    if (line.trim().startsWith(tag)) return offset;
    offset += line.length + 1;
  }
  return -1;
}

/**
 * Adds the carried library to the generated project's .gitignore.
 *
 * Appended, never written over: a template's .gitignore is the project's own, and this entry is the
 * only thing the scaffolder has to add to it. A template with no .gitignore gets one, because a
 * carried framework is a directory, not a file, and committing it is not something to leave to
 * chance.
 */
function ignoreCarried(gitignore) {
  const text = fs.existsSync(gitignore) ? fs.readFileSync(gitignore, 'utf8') : '';
  if (text.split('\n').some((line) => line.trim() === IGNORED)) return;

  let out = text;
  if (out !== '' && !out.endsWith('\n')) out += '\n';
  out += '\n'
    + '# The framework this project was generated against. The scaffolder copies\n'
    + '# it in and can copy a newer one over it, so it is not the project\'s source.\n'
    + IGNORED + '\n';
  fs.writeFileSync(gitignore, out, 'utf8');
}

module.exports = {
  loadCatalog, namesForDirectory, checkPackage, checkTarget, write,
  findLibrary, bundle, CARRIED, REPOSITORY_ID, TEMPLATES_DIR, sourcePath,
};
