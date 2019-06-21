{ config, pkgs, lib, options, ... }:
with lib;

let
  networkId = "testnet";
  ergo = pkgs.fetchurl {
    src = "https://github.com/ergoplatform/ergo/releases/download/v2.1.2/ergo-testnet-2.1.2.jar";
    sha256 = "0lgbmm1caikrds0wdqjl22wzr85d2hcvd0ppll3vbnfnk9dh6k6b";
  };

in
{
  options.services.ergo = {
    enable = mkEnableOption "ergo";
    toolkit = mkOption {
      type = types.enum [ "cuda" "opencl" ];
      description = "Cuda or opencl toolkit.";
      maxPower = mkOption {
        type = types.int;
        default = 115;
        description = "Miner max watt usage.";
      };
      bindAddress = mkOption {
        type = types.str;
        default = "0.0.0.0:9006";
        description = "Listening address of miner";
      };
      apiAddress = mkOption {
        type = types.str;
        default = "127.0.0.1:9052";
        description = "Host and port of API service";
      };
      stateDir = mkOption {
        type = types.str;
        default = "ergo";
        description = "State directory for miner under /var/lib";
      };
    };
  };

  config = mkIf cfg.enable {
    systemd.services.ergo = let
      configFile = writeText "ergo-config" ''
        ergo {
  # Directory to keep data
  directory = /var/lib/${cfg.stateDir}

  node {
    stateType = "utxo"
    verifyTransactions = true
    blocksToKeep = -1
    # Download PoPoW proof on node bootstrap
    PoPoWBootstrap = false
    minimalSuffix = 10
    mining = true
    offlineGeneration = false
    miningDelay = 5s
    keepVersions = 200
  }

  testing {
    # Whether to turn on transaction generator
    transactionGeneration = false

    # Max number of transactions generated per a new block received
    maxTransactionsPerBlock = 100
  }

  cache {
    modifiersCacheSize = 1000
    indexesCacheSize = 10000
  }

  chain {
    addressPrefix = 16
    monetary {
      fixedRate = 7500000000
      epochLength = 2160
      oneEpochReduction = 300000000
      afterGenesisStateDigestHex = "a8f724cef6f8a247a63fba1b713def858d97258f7cd5d7ed71489a474790db5501"
    }
    blockInterval = 2m
    epochLength = 256
    useLastEpochs = 8
    powScheme {
      powType = "equihash"
      n = 96 # used by Equihash
      k = 5  # used by Equihash
    }
  }

  wallet {
    # Seed the wallet private keys are derived from
    seed = "C3FAFMC27697FAF29E9887F977BB5994"
    dlogSecretsNumber = 4
    scanningInterval = 1s
  }
}
scorex {
  network {
    bindAddress = "${cfg.bindAddress}"
    maxInvObjects = 400
    nodeName = "ergo-testnet1"
    knownPeers = ["178.128.162.150:9006", "78.46.93.239:9006", "209.97.136.204:9006", "209.97.138.187:9006", "209.97.134.210:9006", "88.198.13.202:9006"]
    syncInterval = 15s
    syncStatusRefresh = 30s
    syncIntervalStable = 20s
    syncTimeout = 5s
    syncStatusRefreshStable = 1m
    deliveryTimeout = 8s
    maxDeliveryChecks = 2
    appVersion = 0.2.1
    agentName = "ergoref"
    maxModifiersCacheSize = 512
    maxPacketSize = 2048576
  }
  restApi {
    bindAddress = "${cfg.apiAddress}"
  }
        }

      '';
    in {
      path = [ pkgs.cudatoolkit ];
      description = "ergo mining service";
      after = [ "network.target" ];
      serviceConfig = {
        DynamicUser = true;
        StateDirectory = cfg.stateDir;
        ExecStartPost = optional (cfg.toolkit == "cuda") "+${getBin config.boot.kernelPackages.nvidia_x11}/bin/nvidia-smi -pl ${toString cfg.maxPower}";
      };
      environment = {
        LD_LIBRARY_PATH = "${config.boot.kernelPackages.nvidia_x11}/lib";
      };
      script = ''
        java -jar ${ergo} --${networkId} -c ${configFile}
      '';
    };

  };
}
