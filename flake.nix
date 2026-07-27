{
  inputs = {
    nixpkgs.url = github:nixos/nixpkgs/nixpkgs-unstable;
  };
  outputs = { self, nixpkgs }:
    let
      forEachSystem = fn: nixpkgs.lib.genAttrs
        nixpkgs.lib.systems.flakeExposed
        (system: fn system nixpkgs.legacyPackages.${system});
      # A GraalVM 25.1.3 (aka "graal-25.1.3", bundling JDK 25.0.3, an
      # "Innovation" release from graalvm-ce-builds) devShell package was
      # prototyped here via graalvmPackages.buildGraalvm + a custom fetchurl,
      # since nixpkgs' graalvm-ce doesn't expose a version/src override point.
      # It built fine, but it's not wired up below: library/build.gradle.kts
      # had to roll the Maven GraalVM artifacts back to 25.0.0, because
      # org.graalvm.polyglot:ruby / org.graalvm.ruby:ruby (and the old
      # org.graalvm.polyglot:llvm coordinate) are not published past
      # 25.0.0/25.0.1 on Maven Central, and mixing truffle-api 25.1.3 with
      # truffleruby 25.0.0 crashes at runtime (Truffle DSL ABI mismatch). So
      # for now we stick with nixpkgs' stock graalvm-ce (25.0.2 as of this
      # writing), which is close enough to the Maven-pinned 25.0.0 for the
      # native-image tool to stay compatible. See TODO.md for the follow-up.
    in
    {
      devShell = forEachSystem
        (system: pkgs: pkgs.mkShell rec {
          packages = with pkgs; [
            graalvmPackages.graalvm-ce
            (gradle-packages.gradle_8.override {
              # Gradle 8's embedded Kotlin DSL compiler cannot parse the JDK 25
              # version string that ships with the current graalvm-ce, so run
              # the Gradle daemon itself on a JDK Gradle actually supports.
              # The project's own Java toolchain (see
              # buildlogic.kotlin-common-conventions.gradle.kts) still targets
              # a separate JDK resolved independently.
              java = jdk21;
            })
            llvmPackages.clang
            rustc
            tinygo
            # For docs/ (VitePress site, see .github/workflows/deploy-docs.yml):
            # `cd docs && bun install && bun run build`.
            bun
          ];
          # graalvm-ce's setup hook exports JAVA_HOME, which would otherwise
          # win over the JDK 21 baked into the wrapped `gradle` above (Gradle
          # honors JAVA_HOME ahead of its own wrapper default). Pin it
          # explicitly so `gradle`/`./gradlew` run on a JVM their embedded
          # Kotlin DSL compiler actually supports.
          JAVA_HOME = "${pkgs.jdk21}";
          # Picked up by gradle.properties' org.gradle.java.installations.fromEnv
          # so the project's Java toolchain (targeting GraalVM's JDK) resolves
          # to this Nix-provided GraalVM instead of Gradle trying to download
          # one, which isn't reliable in sandboxed/offline environments.
          GRAALVM_HOME = "${pkgs.graalvmPackages.graalvm-ce}";
        });
    };
}
