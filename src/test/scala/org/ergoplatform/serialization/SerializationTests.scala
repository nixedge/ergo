package org.ergoplatform.serialization

import org.ergoplatform.modifiers.ErgoNodeViewModifier
import org.ergoplatform.modifiers.history._
import org.ergoplatform.modifiers.mempool.{ErgoBoxSerializer, ErgoTransactionSerializer}
import org.ergoplatform.nodeView.history.ErgoSyncInfoSerializer
import org.ergoplatform.nodeView.state.{ErgoStateContext, ErgoStateContextSerializer}
import org.ergoplatform.nodeView.wallet.persistence.{PostponedBlockSerializer, RegistryIndexSerializer}
import org.ergoplatform.settings.{Constants, ErgoValidationSettings, ErgoValidationSettingsSerializer, ErgoValidationSettingsUpdateSerializer}
import org.ergoplatform.utils.ErgoPropertyTest
import org.ergoplatform.utils.generators.WalletGenerators
import org.scalacheck.Gen
import org.scalatest.Assertion
import scorex.core.serialization.ScorexSerializer

class SerializationTests extends ErgoPropertyTest with WalletGenerators with scorex.testkit.SerializationTests {

  def checkSerializationRoundtripAndSize[A <: ErgoNodeViewModifier](generator: Gen[A],
                                                                    serializer: ScorexSerializer[A]): Assertion = {
    forAll(generator) { b: A =>
      val recovered = serializer.parseBytes(serializer.toBytes(b))
      val bytes = serializer.toBytes(b)
      bytes shouldEqual serializer.toBytes(recovered)
    }
  }

  property("Serializers should be defined for all block sections") {
    val block = invalidErgoFullBlockGen.sample.get
    block.toSeq.foreach { s =>
      Constants.modifierSerializers.get(s.modifierTypeId) should not be None
    }
  }

  property("Header serialization") {
    val serializer = HeaderSerializer
    forAll(invalidHeaderGen) { b: Header =>
      val recovered = serializer.parseBytes(serializer.toBytes(b))
      recovered shouldBe b
      recovered.size shouldBe serializer.toBytes(b).length
    }
  }

  property("ErgoStateContext serialization") {
    val serializer = ErgoStateContextSerializer(votingSettings)
    forAll(ergoStateContextGen) { b: ErgoStateContext =>
      val recovered = serializer.parseBytes(serializer.toBytes(b))
      serializer.toBytes(b) shouldEqual serializer.toBytes(recovered)
      b.lastHeaders.length shouldBe recovered.lastHeaders.length
      b.lastHeaders shouldBe recovered.lastHeaders
    }
  }

  property("Extension serialization") {
    checkSerializationRoundtrip(extensionGen, ExtensionSerializer)
  }

  property("ErgoBox serialization") {
    checkSerializationRoundtrip(ergoBoxGen, ErgoBoxSerializer)
  }

  property("ErgoTransactionGen serialization") {
    checkSerializationRoundtripAndSize(invalidErgoTransactionGen, ErgoTransactionSerializer)
  }

  property("ErgoSyncInfo serialization") {
    checkSerializationRoundtrip(ergoSyncInfoGen, ErgoSyncInfoSerializer)
  }

  property("ErgoHeader serialization") {
    checkSerializationRoundtripAndSize(defaultHeaderGen, HeaderSerializer)
  }

  property("BlockTransactions serialization") {
    checkSerializationRoundtripAndSize(invalidBlockTransactionsGen, BlockTransactionsSerializer)
  }

  property("ADProofs serialization") {
    checkSerializationRoundtripAndSize(randomADProofsGen, ADProofSerializer)
  }

  property("ModeFeature serialization") {
    forAll(modeFeatureGen) { mf =>
      mf.serializer.parseBytes(mf.serializer.toBytes(mf)) shouldEqual mf
    }
  }

  property("ErgoValidationSettings serialization") {
    val serializer = ErgoValidationSettingsSerializer
    forAll(ergoValidationSettingsGen) { vs =>
      // to bytes / from bytes
      serializer.parseBytes(serializer.toBytes(vs)) shouldEqual vs
      // to extension / from extension
      ErgoValidationSettings.parseExtension(vs.toExtensionCandidate).get shouldEqual vs
    }
  }

  property("ErgoValidationSettingsUpdate serialization") {
    val serializer = ErgoValidationSettingsUpdateSerializer
    forAll(ergoValidationSettingsUpdateGen) { vs =>
      serializer.parseBytes(serializer.toBytes(vs)) shouldEqual vs
    }
  }

  property("RegistryIndex serialization") {
    forAll(registryIndexGen) { index =>
      RegistryIndexSerializer.parseBytes(RegistryIndexSerializer.toBytes(index)) shouldEqual index
    }
  }

  property("PostponedBlock serialization") {
    forAll(postponedBlockGen) { block =>
      PostponedBlockSerializer.parseBytes(PostponedBlockSerializer.toBytes(block)) shouldEqual block
    }
  }

}
