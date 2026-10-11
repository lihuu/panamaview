class Panamaview < Formula
  desc "Create a panamaview project: a macOS window driven from Java over the FFM API"
  homepage "https://github.com/lihuu/panamaview"
  # A git url rather than the source tarball, and not by preference: scaffold/pom.xml runs
  # scripts/embed-templates.sh to decide which files are templates with `git ls-files`, and that
  # script refuses to run outside a git checkout. archive/refs/tags/v0.2.0.tar.gz was downloaded and
  # unpacked to check: it has no .git, so the scaffolder cannot be built from it. A tag plus the
  # commit it names is the same pin the tarball's sha256 would have been, and a stronger one.
  #
  # The revision is not decoration -- `brew audit` refuses a git url that names a tag without one --
  # and it is the one version field in this repository that scripts/release.sh cannot move. A
  # formula here can only ever name a release that has already happened: the commit a release will
  # create does not exist yet while the release script is running. So this pin is updated by hand
  # when the file is copied into a tap, release.sh prints it in a section of its own -- unlike a
  # sample in a document, this is not history to leave alone -- and `install` below refuses to build
  # a scaffolder that cannot do the one thing this formula exists for. A stale pin is then a failure
  # with a message, not a silent bad install.
  url "https://github.com/lihuu/panamaview.git",
      using:    :git,
      tag:      "v0.2.0",
      revision: "37081c9ac2e96d56820a901b6089426f2c55e055"
  license "MIT"

  depends_on "graalvm" => :build
  depends_on "maven" => :build
  # native-image links against the system toolchain.
  depends_on xcode: :build

  # Both what is built and what it is built for are arm64: the framework talks to Cocoa and WebKit
  # through the FFM API, and the scaffolder is a Mach-O arm64 executable.
  depends_on arch: :arm64
  depends_on :macos

  def install
    # GraalVM's own home, not the `java` in the path: native-image is the reason for that build
    # dependency, and this formula installs its JDK under libexec with symlinks into the prefix.
    ENV["JAVA_HOME"] = (formula_opt_libexec("graalvm")/"graalvm.jdk/Contents/Home").to_s

    # The jar's name and the directory it is filed under are both the framework's version, and this
    # formula's version comes from the tag in the url. Reading the pom rather than trusting that
    # the two agree is what makes the paths below right rather than merely plausible.
    framework = File.read("pom.xml")[%r{<version>([^<]+)</version>}, 1]
    odie "pom.xml is at #{framework}, the formula is at #{version}" if framework != version

    # The framework: one jar and the pom that goes with it, which is all a generated project needs
    # in order to resolve it.
    system "mvn", "-q", "-B", "package", "-DskipTests"

    cd "scaffold" do
      # clean, not just package: build-native.sh counts the jars in target/ and refuses to run when
      # there are two of them.
      system "mvn", "-q", "-B", "clean", "package", "-DskipTests"

      # What this formula is for. The framework is not on Maven Central, so a scaffolder that does
      # not carry it beside itself generates a project whose one dependency cannot be resolved -- in
      # a file the user did not write, about an artifact they have never heard of. Releases before
      # the framework was bundled do exactly that, and pinning one is the mistake this catches.
      jar = "target/panamaview-scaffold-#{version}.jar"
      unless Utils.safe_popen_read("unzip", "-l", jar).include?("panamaview/scaffold/Library.class")
        odie <<~EOS
          This formula pins v#{version}, and the scaffolder in that release does not carry the
          framework beside itself. Projects it generates depend on io.github.lihuu:panamaview, which
          is not on Maven Central, and would fail to resolve it.

          Point the url above at the first release that bundles the framework, and set revision to
          that release's commit. See packaging/homebrew/panamaview.rb in the panamaview repository.
        EOS
      end

      # native-image probes the toolchain by running `cc -v`, and Homebrew's superenv puts a shim
      # named `cc` first on PATH. That shim refuses to answer unless HOMEBREW_RUBY_PATH is set, and
      # native-image runs it with a scrubbed environment, so the probe fails with "Unable to detect
      # supported DARWIN native software development toolchain" and the build stops. A real compiler
      # ahead of the shim is what it needs, and the xcode dependency above is what puts one there.
      # Measured both ways by hand: shim first fails, /usr/bin first produces the binary.
      with_env(PATH: "/usr/bin:#{ENV["PATH"]}") do
        system "./build-native.sh"
      end
      bin.install "target/panamaview"
    end

    # The framework laid out as a Maven repository root, in one of the two places the scaffolder
    # looks for it beside its own executable: <directory of the executable>/lib. It copies that
    # directory into every project it generates and points the generated pom at it, so the project
    # builds with no repository configured and with nothing of the framework on the machine.
    #
    # bin/lib rather than lib/, which is the other place it looks, and this is the one audit
    # complaint this formula accepts: `brew audit` reports "Non-executables were installed to bin",
    # naming this directory. It has to be here. The scaffolder copies the whole directory it finds
    # into every project it generates, and it only recognises a directory holding the framework at
    # io/github/lihuu/panamaview/<version>/ inside it -- so <prefix>/lib, which is every library in
    # the Homebrew prefix, would either not be recognised at all or copy all of them into the
    # generated project. Neither is a thing to trade for a style rule about bin/.
    repo = bin/"lib/io/github/lihuu/panamaview"/framework
    repo.install "target/panamaview-#{framework}.jar"
    repo.install "pom.xml" => "panamaview-#{framework}.pom"

    # Checksums, exactly as the npm package's build script writes them for its copy of this same
    # repository. Without them the first build of every generated project prints "Could not validate
    # integrity of download ...: no checksums available" for the jar and the pom -- measured, with
    # these two lines removed. A warning nobody can act on, in a file the user did not write, on the
    # first command they run.
    %W[panamaview-#{framework}.jar panamaview-#{framework}.pom].each do |artifact|
      (repo/"#{artifact}.sha1").write Digest::SHA1.file((repo/artifact).to_s).hexdigest
    end
  end

  # What Homebrew prints after installing, and in `brew info`. The requirements are stated here
  # rather than left to the project's README because the first thing anyone does with this is
  # generate a project and try to build it, and JDK 25 and Maven are what that needs.
  def caveats
    <<~EOS
      panamaview generates a Java project. Building and running one needs JDK 25 and Maven, which
      this formula does not install -- it needs Maven only to build itself:

        brew install openjdk maven
        cd my-app && ./run.sh

      Generating a project needs neither. The scaffolder is native, and what it writes is complete:
      the framework is installed beside it at
        #{opt_bin}/lib
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
