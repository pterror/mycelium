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
	    graalvm-ce
	    gradle
          ];
        });
    };
}
