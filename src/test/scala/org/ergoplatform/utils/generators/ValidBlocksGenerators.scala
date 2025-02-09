package org.ergoplatform.utils.generators

import akka.actor.ActorRef
import io.iohk.iodb.ByteArrayWrapper
import org.ergoplatform.ErgoBox
import org.ergoplatform.local.ErgoMiner
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.modifiers.history.PoPowAlgos._
import org.ergoplatform.modifiers.history.{Extension, ExtensionCandidate, Header, PoPowAlgos}
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.state._
import org.ergoplatform.nodeView.state.wrapped.WrappedUtxoState
import org.ergoplatform.settings.{Algos, Constants, LaunchParameters}
import org.ergoplatform.utils.LoggingUtil
import org.scalatest.Matchers
import scorex.core.VersionTag
import scorex.crypto.authds.{ADDigest, ADKey}
import scorex.testkit.TestkitHelpers
import scorex.testkit.utils.FileUtils

import scala.annotation.tailrec
import scala.util.{Failure, Random, Try}

trait ValidBlocksGenerators
  extends TestkitHelpers with FileUtils with Matchers with ChainGenerator with ErgoTransactionGenerators {

  def createUtxoState(nodeViewHolderRef: Option[ActorRef] = None): (UtxoState, BoxHolder) = {
    val constants = StateConstants(nodeViewHolderRef, settings)
    createUtxoState(constants)
  }

  def createUtxoState(constants: StateConstants): (UtxoState, BoxHolder) = {
    ErgoState.generateGenesisUtxoState(createTempDir, constants)
  }

  def createUtxoState(bh: BoxHolder): UtxoState =
    UtxoState.fromBoxHolder(bh, None, createTempDir, stateConstants)

  def createDigestState(version: VersionTag, digest: ADDigest): DigestState =
    DigestState.create(Some(version), Some(digest), createTempDir, stateConstants)

  def validTransactionsFromBoxHolder(boxHolder: BoxHolder): (Seq[ErgoTransaction], BoxHolder) =
    validTransactionsFromBoxHolder(boxHolder, new Random)

  protected def validTransactionsFromBoxes(sizeLimit: Int,
                                           stateBoxesIn: Seq[ErgoBox],
                                           rnd: Random): (Seq[ErgoTransaction], Seq[ErgoBox]) = {
    validTransactionsFromBoxes(sizeLimit, stateBoxesIn, Seq(), rnd)
  }

  /** @param sizeLimit maximum transactions size in bytes */
  protected def validTransactionsFromBoxes(sizeLimit: Int,
                                           stateBoxesIn: Seq[ErgoBox],
                                           dataBoxesIn: Seq[ErgoBox],
                                           rnd: Random): (Seq[ErgoTransaction], Seq[ErgoBox]) = {
    var createdEmissionBox: Seq[ErgoBox] = Seq()

    @tailrec
    def loop(stateBoxes: Seq[ErgoBox],
             selfBoxes: Seq[ErgoBox],
             acc: Seq[ErgoTransaction],
             rnd: Random): (Seq[ErgoTransaction], Seq[ErgoBox]) = {

      def getDataBoxes(): IndexedSeq[ErgoBox] = {
        rnd.shuffle(dataBoxesIn ++ stateBoxesIn ++ selfBoxes).take(rnd.nextInt(10)).toIndexedSeq
      }

      val currentSize = acc.map(_.size).sum
      val averageSize = if (currentSize > 0) currentSize / acc.length else 1000
      val customTokens = (stateBoxes ++ selfBoxes).flatMap(_.additionalTokens.toArray)
      val customTokensNum = customTokens.map(ct => ByteArrayWrapper(ct._1)).toSet.size
      val issueNew = customTokensNum == 0

      stateBoxes.find(isEmissionBox) match {
        case Some(emissionBox) if currentSize < sizeLimit - averageSize =>
          // Extract money to anyoneCanSpend output and put emission to separate var to avoid it's double usage inside one block
          val currentHeight: Int = emissionBox.creationHeight.toInt
          val rewards = ErgoMiner.collectRewards(Some(emissionBox), currentHeight, Seq.empty, defaultMinerPk, emission)
          val outs = rewards.flatMap(_.outputs)
          val remainedBoxes = stateBoxes.filter(b => !isEmissionBox(b))
          createdEmissionBox = outs.filter(b => isEmissionBox(b))
          val newSelfBoxes = selfBoxes ++ outs.filter(b => !isEmissionBox(b))
          loop(remainedBoxes, newSelfBoxes, rewards ++ acc, rnd)

        case _ =>
          if (currentSize < sizeLimit - 2 * averageSize) {
            val (consumedSelfBoxes, remainedSelfBoxes) = selfBoxes.splitAt(Try(rnd.nextInt(selfBoxes.size) + 1).getOrElse(0))
            val (consumedBoxesFromState, remainedBoxes) = stateBoxes.splitAt(Try(rnd.nextInt(stateBoxes.size) + 1).getOrElse(0))
            // disable tokens generation to avoid situation with too many tokens
            val boxesToSpend = (consumedSelfBoxes ++ consumedBoxesFromState).toIndexedSeq
            val tx = validTransactionFromBoxes(boxesToSpend, rnd, issueNew, dataBoxes = getDataBoxes)
            tx.statelessValidity match {
              case Failure(e) =>
                log.warn(s"Failed to generate valid transaction: ${LoggingUtil.getReasonMsg(e)}")
                loop(stateBoxes, selfBoxes, acc, rnd)
              case _ =>
                loop(remainedBoxes, remainedSelfBoxes ++ tx.outputs, tx +: acc, rnd)
            }
          } else {
            // take all remaining boxes from state and return transactions set
            val (consumedSelfBoxes, remainedSelfBoxes) = selfBoxes.splitAt(1)

            val tx = validTransactionFromBoxes((consumedSelfBoxes ++ stateBoxes).toIndexedSeq, rnd, issueNew, dataBoxes = getDataBoxes)
            tx.statelessValidity match {
              case Failure(e) =>
                log.warn(s"Failed to generate valid transaction: ${LoggingUtil.getReasonMsg(e)}")
                loop(stateBoxes, selfBoxes, acc, rnd)
              case _ =>
                ((tx +: acc).reverse, remainedSelfBoxes ++ tx.outputs ++ createdEmissionBox)
            }
          }
      }
    }

    loop(stateBoxesIn, Seq.empty, Seq.empty, rnd)
  }

  /**
    * Simplified check that box is an emission box
    */
  private def isEmissionBox(box: ErgoBox): Boolean = box.proposition == genesisEmissionBox.proposition

  /** @param txSizeLimit maximum transactions size in bytes */
  def validTransactionsFromBoxHolder(boxHolder: BoxHolder,
                                     rnd: Random,
                                     txSizeLimit: Int = 10 * 1024): (Seq[ErgoTransaction], BoxHolder) = {
    val (emissionBox, boxHolderWithoutEmission) = boxHolder.take(b => isEmissionBox(b))
    val (_, bhWithoutGenesis) = boxHolderWithoutEmission.take(b => genesisBoxes.contains(b))
    val (regularBoxes, drainedBh) = bhWithoutGenesis.take(rnd.nextInt(txSizeLimit / 100) + 1)
    val boxes = emissionBox ++ regularBoxes
    val dataBoxes: Seq[ErgoBox] = Random.shuffle(boxHolder.boxes).take(rnd.nextInt(txSizeLimit / 100)).map(_._2).toSeq

    assert(boxes.nonEmpty, s"Was unable to take at least 1 box from box holder $boxHolder")
    val (txs, createdBoxes) = validTransactionsFromBoxes(txSizeLimit, boxes, dataBoxes, rnd)
    txs.foreach(_.statelessValidity.get)
    val bs = new BoxHolder(drainedBh.boxes ++ createdBoxes.map(b => ByteArrayWrapper(b.id) -> b))
    txs -> bs
  }


  def validTransactionsFromUtxoState(wus: WrappedUtxoState, rnd: Random = new Random): Seq[ErgoTransaction] = {
    val num = 1 + rnd.nextInt(10)

    val allBoxes = wus.takeBoxes(num + rnd.nextInt(100))
    val anyoneCanSpendBoxes = allBoxes.filter(_.ergoTree == Constants.TrueLeaf)
    val boxes = if (anyoneCanSpendBoxes.nonEmpty) anyoneCanSpendBoxes else allBoxes

    validTransactionsFromBoxes(num, boxes, rnd)._1
  }

  def validFullBlock(parentOpt: Option[ErgoFullBlock], utxoState: UtxoState, boxHolder: BoxHolder): ErgoFullBlock =
    validFullBlock(parentOpt, utxoState, boxHolder, new Random)


  def validFullBlock(parentOpt: Option[ErgoFullBlock], utxoState: UtxoState, boxHolder: BoxHolder, rnd: Random): ErgoFullBlock = {
    validFullBlock(parentOpt, utxoState, validTransactionsFromBoxHolder(boxHolder, rnd)._1)
  }

  def validFullBlockWithBoxHolder(parentOpt: Option[ErgoFullBlock],
                                  utxoState: UtxoState,
                                  boxHolder: BoxHolder,
                                  rnd: Random): (ErgoFullBlock, BoxHolder) = {
    val txsBh = validTransactionsFromBoxHolder(boxHolder, rnd)
    validFullBlock(parentOpt, utxoState, txsBh._1) -> txsBh._2
  }

  def validFullBlock(parentOpt: Option[ErgoFullBlock],
                     wrappedState: WrappedUtxoState): ErgoFullBlock = {
    validFullBlock(parentOpt, wrappedState, wrappedState.versionedBoxHolder)
  }

  def validFullBlock(parentOpt: Option[ErgoFullBlock],
                     wrappedState: WrappedUtxoState,
                     time: Long): ErgoFullBlock = {
    validFullBlock(
      parentOpt,
      wrappedState,
      validTransactionsFromBoxHolder(wrappedState.versionedBoxHolder, new Random())._1,
      Some(time)
    )
  }

  def validFullBlock(parentOpt: Option[ErgoFullBlock],
                     utxoState: UtxoState,
                     transactions: Seq[ErgoTransaction],
                     timeOpt: Option[Long] = None): ErgoFullBlock = {
    checkPayload(transactions, utxoState)

    val (adProofBytes, updStateDigest) = utxoState.proofsForTransactions(transactions).get

    val time = timeOpt.orElse(parentOpt.map(_.header.timestamp + 1)).getOrElse(timeProvider.time())
    val interlinks = parentOpt.toSeq.flatMap { block =>
      PoPowAlgos.updateInterlinks(block.header, PoPowAlgos.unpackInterlinks(block.extension.fields).get)
    }
    val extension: ExtensionCandidate = LaunchParameters.toExtensionCandidate ++ interlinksToExtension(interlinks) ++
      utxoState.stateContext.validationSettings.toExtensionCandidate
    val votes = Array.fill(3)(0: Byte)

    powScheme.proveBlock(parentOpt.map(_.header), Header.CurrentVersion, settings.chainSettings.initialNBits, updStateDigest, adProofBytes,
      transactions, time, extension, votes, defaultMinerSecretNumber).get
  }

  /**
    * Full block valid against state only - allows to create blocks form headers and state,
    * does not contain valid interlinks.
    */
  def statefulyValidFullBlock(wrappedState: WrappedUtxoState,
                              timeOpt: Option[Long] = None): ErgoFullBlock = {
    val parentOpt: Option[Header] = wrappedState.stateContext.lastHeaderOpt
    val parentExtensionOpt: Option[Extension] = wrappedState.stateContext.lastExtensionOpt
    val bh = wrappedState.versionedBoxHolder
    val transactions = validTransactionsFromBoxHolder(bh, new Random())._1

    checkPayload(transactions, wrappedState)

    val (adProofBytes, updStateDigest) = wrappedState.proofsForTransactions(transactions).get

    val time = timeOpt.orElse(parentOpt.map(_.timestamp + 1)).getOrElse(timeProvider.time())
    val interlinksExtension = interlinksToExtension(updateInterlinks(parentOpt, parentExtensionOpt))
    val extension: ExtensionCandidate = LaunchParameters.toExtensionCandidate ++ interlinksExtension
    val votes = Array.fill(3)(0: Byte)

    powScheme.proveBlock(parentOpt, Header.CurrentVersion, settings.chainSettings.initialNBits, updStateDigest,
      adProofBytes, transactions, time, extension, votes, defaultMinerSecretNumber).get
  }

  private def checkPayload(transactions: Seq[ErgoTransaction], us: UtxoState): Unit = {
    transactions.foreach(_.statelessValidity shouldBe 'success)
    transactions.nonEmpty shouldBe true
    ErgoState.boxChanges(transactions)._1.foreach { boxId: ADKey =>
      assert(us.boxById(boxId).isDefined, s"Box ${Algos.encode(boxId)} missed")
    }
  }

}
