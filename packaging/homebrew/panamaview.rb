class Panamaview < Formula
  desc "Create a panamaview project: a macOS window driven from Java over the FFM API"
  homepage "https://github.com/lihuu/panamaview"
  # A git url rather than the source tarball, and not by preference: npm/scripts/build-package.sh
  # decides which files are a template with `git ls-files`, and it refuses to run outside a git
  # checkout. archive/refs/tags/v0.2.0.tar.gz was downloaded and unpacked to check: it has no .git, so
  # the package cannot be staged from it. A tag plus the commit it names is the same pin the tarball's
  # sha256 would have been, and a stronger one.
  #
  # The revision is not decoration -- `brew audit` refuses a git url that names a tag without one --
  # and it is the one version field in this repository that scripts/release.sh cannot move. A formula
  # here can only ever name a release that has already happened: the commit a release will create does
  # not exist yet while the release script is running. So this pin is updated by hand when the file is
  # copied into a tap, release.sh prints it in a section of its own -- unlike a sample in a document,
  # this is not history to leave alone. The pin below names v0.2.0, which predates the scaffolder being
  # JavaScript, so this formula does not install from it today: `install` says so, by name, rather than
  # failing on a missing file. A stale pin is a failure with a message either way.
  url "https://github.com/lihuu/panamaview.git",
      using:    :git,
      tag:      "v0.2.0",
      revision: "37081c9ac2e96d56820a901b6089426f2c55e055"
  license "MIT"

  # For the JDK the framework is built with, not for native-image: what is built here is a jar. The
  # scaffolder used to be built as a native executable by this formula, which is why it also wanted
  # the system toolchain; nothing in this `install` links one, so xcode is no longer a dependency.
  depends_on "graalvm" => :build
  depends_on "maven" => :build

  # What the scaffolder generates is a macOS application -- the framework talks to Cocoa and WebKit
  # through the FFM API -- and macOS on Apple Silicon is what this repository builds and verifies
  # against. The scaffolder itself would run anywhere Node does, which is why the npm package no longer
  # declares `os`/`cpu`; a formula is installed by the person about to use it, and what they will do
  # with it is generate that application.
  depends_on arch: :arm64
  depends_on :macos

  # The scaffolder is JavaScript; Node is what runs it.
  depends_on "node"

  def install
    # GraalVM's own home, not the `java` in the path: native-image is not the reason for this build
    # dependency, but this formula's JDK is, and this is where GraalVM puts it -- under libexec with
    # symlinks into the prefix.
    ENV["JAVA_HOME"] = (formula_opt_libexec("graalvm")/"graalvm.jdk/Contents/Home").to_s

    # The version is the framework's, and this formula's comes from the tag in the url. Reading the
    # pom rather than trusting that the two agree is what makes the paths below right rather than
    # merely plausible.
    framework = File.read("pom.xml")[%r{<version>([^<]+)</version>}, 1]
    odie "pom.xml is at #{framework}, the formula is at #{version}" if framework != version

    # Every pin from before the scaffolder became JavaScript names a release this formula cannot
    # install: npm/src/scaffold.js did not exist, and the command was a native binary the package
    # carried. Said here rather than left to Homebrew's missing-file error, because the pin is the one
    # thing this file is expected to get stale -- v0.2.0, the pin below, is exactly such a release, and
    # it is re-pointed by hand when this file is copied into a tap.
    unless (buildpath/"npm/src/scaffold.js").exist?
      odie <<~EOS
        This formula pins v#{version}, which predates the scaffolder being JavaScript: there is no
        npm/src/scaffold.js in it, and its package carried a native binary instead.

        Point the url above at the first release cut after that change, and set revision to that
        release's commit. See packaging/homebrew/panamaview.rb in the panamaview repository.
      EOS
    end

    # The framework, as one jar and the poms that go with it: all a generated project needs in order
    # to resolve it. The parent travels too, because the framework's pom inherits from it and Maven
    # reads the parent before it will read the pom that names it.
    system "mvn", "-q", "-B", "package", "-DskipTests"

    # The package, laid out the way npm lays it out and the way scripts/verify-scaffold-library.sh
    # tests it: cli.js, src/, templates/ and lib/ in one directory, with the command linked into bin/.
    # lib/ has to be beside cli.js and not somewhere else, because that is the one layout the
    # scaffolder looks in -- and the link is not a detail either: Node resolves a module's own
    # filename, so the CLI reached through a link in bin/ still finds this directory. That is how npm
    # installs it too, and the reason it works there is worth not undoing here.
    libexec.install "npm/cli.js", "npm/package.json", "npm/src"
    # The link in bin/ is followed to this file, so this file is what has to be executable.
    chmod 0755, libexec/"cli.js"

    # The templates, by git's reckoning rather than by `cp -R`. A checkout that has ever built a
    # frontend has a node_modules/ in it, which is part of no template and which the scaffolder refuses
    # outright -- node_modules/.bin is nothing but symlinks, and a symlink in a template is a file that
    # would arrive as whatever it pointed at on the packing machine. A fresh clone has none of that,
    # but the rule is the one npm/scripts/build-package.sh stages by, and it is better stated once than
    # right by accident.
    #
    # The rename is npm's doing rather than a choice: npm never packs a file named .gitignore in any
    # directory, so the package stages each template's as .gitignore.packed and the scaffolder writes it
    # out under the template's own name. Without it a generated project commits target/ and its own copy
    # of the framework.
    (libexec/"templates").mkpath
    Utils.safe_popen_read("git", "-C", buildpath.to_s, "ls-files", "--", "templates")
         .split("\n").each do |file|
      rel = file.delete_prefix("templates/").sub(/\A\.gitignore\z/, ".gitignore.packed")
      destination = libexec/"templates"/rel
      destination.dirname.mkpath
      cp buildpath/file, destination
    end

    # The framework laid out as a Maven repository root, which is what lib/ beside the CLI is for. The
    # scaffolder copies that whole directory into every project it generates and points the generated
    # pom at it, so the project builds with no repository configured and nothing of the framework on
    # the machine.
    repo = libexec/"lib/io/github/lihuu/panamaview"/framework
    repo.install "framework/target/panamaview-#{framework}.jar"
    repo.install "framework/pom.xml" => "panamaview-#{framework}.pom"
    parent = libexec/"lib/io/github/lihuu/panamaview-parent"/framework
    parent.install "pom.xml" => "panamaview-parent-#{framework}.pom"

    # Checksums, exactly as npm/scripts/build-package.sh writes them for its copy of this same
    # repository. Without them the first build of every generated project prints "Could not validate
    # integrity of download ...: no checksums available" for the jar and the pom -- measured, with
    # these lines removed. A warning nobody can act on, in a file the user did not write, on the first
    # command they run.
    [repo, parent].each do |directory|
      directory.children.each do |artifact|
        next unless artifact.file?

        (directory/"#{artifact.basename}.sha1").write Digest::SHA1.file(artifact.to_s).hexdigest
      end
    end

    # What this formula is for, and the check that a release which bundles no framework cannot get
    # past. The framework is not on Maven Central, so a scaffolder that does not carry it beside itself
    # generates a project whose one dependency cannot be resolved -- in a file the user did not write,
    # about an artifact they have never heard of. Releases before the framework was bundled do exactly
    # that. Running the command, rather than reading the directory, is deliberate: what has to be true
    # is that the CLI finds it, and that is a fact about the CLI rather than about the layout.
    probe = buildpath/"install-probe"
    system "node", (libexec/"cli.js").to_s, "create", probe.to_s, "--template", "vanilla"
    carried = probe/".panamaview/lib/io/github/lihuu/panamaview"/framework/"panamaview-#{framework}.jar"
    unless carried.exist?
      odie <<~EOS
        This formula pins v#{version}, and the command built from it does not find the framework
        beside itself. Projects it generates depend on io.github.lihuu:panamaview, which is not on
        Maven Central, and would fail to resolve it.

        Point the url above at the first release that bundles the framework, and set revision to that
        release's commit. See packaging/homebrew/panamaview.rb in the panamaview repository.
      EOS
    end

    bin.install_symlink libexec/"cli.js" => "panamaview"
  end

  # What Homebrew prints after installing, and in `brew info`. The requirements are stated here rather
  # than left to the project's README because the first thing anyone does with this is generate a
  # project and try to build it, and JDK 25 and Maven are what that needs.
  def caveats
    <<~EOS
      panamaview generates a Java project. Building and running one needs JDK 25 and Maven, which this
      formula does not install -- it needs Maven only to build the framework it carries:

        brew install openjdk maven
        cd my-app && ./run.sh

      Generating a project needs neither. The scaffolder is JavaScript, run by Node, which this formula
      does install, and what it writes is complete: the framework is installed beside it at
        #{opt_libexec}/lib
      and every project it generates copies that directory into itself, so no repository has to be
      configured and nothing is downloaded from one.

      macOS on Apple Silicon only.
    EOS
  end

  test do
    assert_match "panamaview #{version}", shell_output("#{bin}/panamaview --version")
    assert_match "vanilla", shell_output("#{bin}/panamaview templates")

    # The reason the framework is installed at all: a generated project finds it and carries it,
    # so it will build with nothing configured.
    system bin/"panamaview", "create", "test-project", "--template", "vanilla"
    assert_path_exists testpath/"test-project/.panamaview/lib/io/github/lihuu/panamaview/#{version}/panamaview-#{version}.jar"
  end
end
