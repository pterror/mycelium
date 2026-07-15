{
  inputs = {
    nixpkgs.url = github:nixos/nixpkgs/nixpkgs-unstable;
  };
  outputs = { self, nixpkgs }:
    let
      forEachSystem = fn: nixpkgs.lib.genAttrs
        nixpkgs.lib.systems.flakeExposed
        (system: fn system nixpkgs.legacyPackages.${system});
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
