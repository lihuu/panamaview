#!/usr/bin/env node
// The whole CLI: hand every argument to the scaffolder this package carries.
//
// JavaScript, not a shell script, because npm has to be able to run this on whatever it installed
// on. Dependency-free CommonJS, because `npx create-panamaview` should not spend a second fetching
// a CLI framework in order to pass argv through.
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');

const { version } = require('./package.json');

// Relative to this file, so the same line is correct under npx, a global install, and npm link.
const BINARY = path.join(__dirname, 'bin', 'panamaview');

// Mirrors the scaffolder's own `panamaview help`, which is the only list of options there is. The
// examples say npx because that is how this copy is usually reached, not because it is a different
// tool -- and they keep the `create` word, which is not optional: the scaffolder's first argument
// is its command, so `npx create-panamaview my-app` is an unknown command and exits 2. Every
// argument is forwarded unchanged, so the examples have to be the binary's, not the shorter thing
// a create-* package is usually allowed to accept.
const HELP = `panamaview — create a panamaview project

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
  npx create-panamaview create my-chat-app --template vue
  npx create-panamaview create . --template vanilla

The project is generated complete: it carries its own copy of the framework, so it builds with
Maven and nothing else configured. Building and running it needs JDK 25 and Maven, and this
package is macOS on Apple Silicon only.
`;

function die(lines) {
  process.stderr.write(`${lines.join('\n')}\n`);
  process.exit(1);
}

const argv = process.argv.slice(2);
const first = argv[0];

// Answered here rather than by the binary, because these two are what someone reaches for when the
// install is what has gone wrong. Everything else goes to the scaffolder untouched.
if (first === '--help' || first === '-h') {
  process.stdout.write(HELP);
  process.exit(0);
}
if (first === '--version' || first === '-V') {
  process.stdout.write(`panamaview ${version}\n`);
  process.exit(0);
}

try {
  fs.accessSync(BINARY, fs.constants.X_OK);
} catch {
  // Not a stack trace: nothing here is a bug in the caller's program, and a stack trace says it is.
  die([
    'create-panamaview: the scaffolder this package carries is missing or not executable:',
    `  ${BINARY}`,
    '',
    'The binary travels inside the package, so its absence means the install is incomplete rather',
    'than that something needs downloading. Reinstalling is usually enough:',
    '  npm install -g create-panamaview',
  ]);
}

// An explicit argv and no shell, so a project directory with a space in it needs no quoting from
// anyone. stdio is inherited so the scaffolder's own output and its template prompt are its own.
const child = spawn(BINARY, argv, { stdio: 'inherit' });

// Ctrl-C reaches the whole foreground process group, so the child has usually had this already;
// forwarding anyway is what makes `kill -INT <this pid>` work as well. A second one is someone who
// wants out now, and node would otherwise sit on the signal for as long as the child does.
const SIGNALS = { SIGHUP: 1, SIGINT: 2, SIGQUIT: 3, SIGTERM: 15 };
let interrupts = 0;
for (const signal of Object.keys(SIGNALS)) {
  process.on(signal, () => {
    if (signal === 'SIGINT' && ++interrupts > 1) process.exit(130);
    try {
      child.kill(signal);
    } catch {
      // Already gone. The exit handler below is what decides the status.
    }
  });
}

child.on('error', (error) => {
  die([`create-panamaview: could not run ${BINARY}: ${error.message}`]);
});

child.on('exit', (code, signal) => {
  // 128 + n is what a shell reports for a process killed by a signal, so a caller cannot tell this
  // apart from having run the scaffolder directly.
  if (signal !== null) process.exit(128 + (SIGNALS[signal] ?? 0));
  process.exit(code === null ? 1 : code);
});
