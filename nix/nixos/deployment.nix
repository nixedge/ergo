let
  accessKeyId = "";
  ergo-projid = "";

in {
  network.description = "ergo-packet";
  resources.packetKeyPairs.ergo = {
    inherit accessKeyId;
    project = ergo-projid;
  };
  ergo-miner-1 = { config, pkgs, resources, ... }: {
    deployment.packet = {
      inherit accessKeyId;
      keyPair = resources.packetKeyPairs.ergo;
      facility = "dfw2";
      plan = "g2.large.x86";
      project = ergo-projid;
    };
    deployment.targetEnv = "packet";
    imports = [ ./ergo.nix ];
    services.ergo.enable = true;
  };
}
