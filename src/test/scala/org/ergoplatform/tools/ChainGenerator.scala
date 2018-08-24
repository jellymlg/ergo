package org.ergoplatform.tools

import java.io.File

import org.ergoplatform.mining.{CandidateBlock, EquihashPowScheme}
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.modifiers.history.{Extension, ExtensionCandidate, Header}
import org.ergoplatform.nodeView.history.ErgoHistory
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.settings._
import org.ergoplatform.utils.{ErgoTestHelpers, ValidBlocksGenerators}
import scorex.core.utils.ScorexLogging
import scorex.utils.Random

import scala.annotation.tailrec
import scala.concurrent.duration._

/**
  * Application object for chain generation.
  * Takes 2 parameters: start timestamp and path to history folder.
  * Generate blocks starting from start timestamp and until current time with expected block interval
  * between them, to ensure that difficulty does not change.
  */
object ChainGenerator extends App with ValidBlocksGenerators with ErgoTestHelpers with ScorexLogging {

  val n: Char = 96
  val k: Char = 5
  val pow = new EquihashPowScheme(n, k)
  val blockInterval = 2.minute

  val startTime = args.headOption.map(_.toLong).getOrElse(timeProvider.time - (blockInterval * 10).toMillis)
  val dir = if (args.length < 2) new File("/tmp/ergo/node1/data") else new File(args(1))
  dir.mkdirs()

  val history = {
    val miningDelay = 1.second
    val minimalSuffix = 2
    val nodeSettings: NodeConfigurationSettings = NodeConfigurationSettings(StateType.Utxo, verifyTransactions = true,
      -1, PoPoWBootstrap = false, minimalSuffix, mining = false, miningDelay, offlineGeneration = false, 200)
    val chainSettings = ChainSettings(blockInterval, 256, 8, pow,
      settings.chainSettings.monetary)
    val fullHistorySettings: ErgoSettings = ErgoSettings(dir.getAbsolutePath, chainSettings, settings.testingSettings,
      nodeSettings, CacheSettings.default, settings.scorexSettings)
    ErgoHistory.readOrGenerate(fullHistorySettings, timeProvider)
  }
  if (history.bestHeaderOpt.nonEmpty) {
    log.error(s"History at ${dir.getAbsoluteFile} already exists. Exiting.")
    System.exit(11)
  }

  var (state, boxHolder) = createUtxoState()

  def loop(last: Option[Header], acc: Seq[ErgoFullBlock]): Seq[ErgoFullBlock] = {
    val time: Long = last.map(_.timestamp + blockInterval.toMillis).getOrElse(startTime)
    if (time < timeProvider.time) {
      val txsBoxes = validTransactionsFromBoxHolder(boxHolder)
      val txs = txsBoxes._1
      boxHolder = txsBoxes._2

      @tailrec
      def generate(candidate: CandidateBlock): ErgoFullBlock = {
        log.info(s"Trying to prove block with parent ${candidate.parentOpt.map(_.encodedId)} and timestamp ${candidate.timestamp}")

        pow.proveBlock(candidate) match {
          case Some(fb) => fb
          case _ => generate(candidate.copy(extension = ExtensionCandidate(Seq(), Seq(Random.randomBytes(Extension.OptionalFieldKeySize) -> Array[Byte]()))))
        }
      }

      val (adProofBytes, updStateDigest) = state.proofsForTransactions(txs).get
      val candidate = new CandidateBlock(last, Constants.InitialNBits, updStateDigest, adProofBytes,
        txs, time, ExtensionCandidate(Seq(), Seq()))

      val block = generate(candidate)
      log.info(s"Block ${block.id} at height ${block.header.height} generated")
      state = state.applyModifier(block).get
      loop(Some(block.header), acc :+ block)
    } else {
      acc
    }
  }

  val chain = loop(None, Seq())
  log.info(s"Chain of length ${chain.length} generated")
  chain.foreach { block =>
    history.append(block.header).get
  }
  chain.foreach { block =>
    block.blockSections.foreach(s => if (!history.contains(s)) history.append(s).get)
  }
  history.bestHeaderOpt shouldBe history.bestFullBlockOpt.map(_.header)
  history.bestFullBlockOpt.get shouldBe chain.last
  log.info("History was generated successfully")
  System.exit(0)

}