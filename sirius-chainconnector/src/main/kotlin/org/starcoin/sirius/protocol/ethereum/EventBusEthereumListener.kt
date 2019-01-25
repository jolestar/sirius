package org.starcoin.sirius.protocol.ethereum

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import org.ethereum.core.BlockSummary
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.starcoin.sirius.channel.EventBus
import org.starcoin.sirius.core.Receipt
import org.starcoin.sirius.lang.hexToByteArray
import org.starcoin.sirius.lang.isZeroBytes
import org.starcoin.sirius.lang.toHEXString
import org.starcoin.sirius.protocol.EthereumTransaction
import org.starcoin.sirius.protocol.TransactionResult
import org.starcoin.sirius.util.WithLogging
import java.io.File
import java.math.BigInteger
import java.nio.charset.Charset

class EventBusEthereumListener : AbstractEthereumListener() {

    companion object : WithLogging()

    private val blockEventBus = EventBus<EthereumBlock>()
    private val txEventBus = EventBus<TransactionResult<EthereumTransaction>>()

    override fun onBlock(blockSummary: BlockSummary) {
        GlobalScope.launch {
            val block = EthereumBlock(blockSummary.block)
            blockEventBus.send(block)
            LOG.info("EventBusEthereumListener onBlock hash:${block.hash}, height:${block.height}, txs:${block.transactions.size}")
            blockSummary.block.transactionsList.forEachIndexed { index, it ->
                var ethereumTransaction = EthereumTransaction(it)
                val txReceipt = blockSummary.receipts[index]
                val transactionResult = TransactionResult(
                    ethereumTransaction, Receipt(
                        it.hash,
                        BigInteger.valueOf(index.toLong()),
                        blockSummary.block.hash,
                        BigInteger.valueOf(blockSummary.block.number),
                        null,
                        it.sender,
                        it.receiveAddress,
                        BigInteger.valueOf(blockSummary.block.header.gasUsed),
                        blockSummary.block.header.logsBloom.toHEXString(),
                        BigInteger.valueOf(0),
                        blockSummary.block.header.receiptsRoot.toHEXString(),
                        txReceipt.isTxStatusOK && txReceipt.isSuccessful && (txReceipt.executionResult.isEmpty() || !txReceipt.executionResult.isZeroBytes())
                    )
                )
                LOG.info("EventBusEthereumListener tx:${ethereumTransaction.hash()}")
                if (txReceipt.error != null && txReceipt.error.isNotEmpty()) {
                    LOG.warning("tx ${ethereumTransaction.hash()} error: ${txReceipt.error}")
                }
                for (log in txReceipt.logInfoList) {
                    LOG.fine("tx ${ethereumTransaction.hash()} log $log")
                }
                LOG.info("tx ${ethereumTransaction.hash()}  PostTxState ${txReceipt.postTxState.toHEXString()}")
                if (!transactionResult.receipt.status) {
                    LOG.warning("tx ${ethereumTransaction.hash()} isTxStatusOK: ${txReceipt.isTxStatusOK} isSuccessful: ${txReceipt.isSuccessful} executionResult: ${txReceipt.executionResult.toHEXString()}")
                    val trace = traceMap[ethereumTransaction.hash()]
                    if (trace != null) {
                        val file = File.createTempFile("trace", ".txt")
                        file.printWriter().use { out -> out.println(trace) }
                        LOG.warning("Write tx trace file to ${file.absolutePath}")
                        val parser = JSONParser()
                        val jsonObject = parser.parse(trace) as JSONObject
                        val result =
                            (jsonObject["result"] as String).hexToByteArray().toString(Charset.defaultCharset())
                        LOG.warning("tx ${ethereumTransaction.hash()} trace result $result")
                    }
                }
                txEventBus.send(transactionResult)
            }
        }
    }

    fun subscribeBlock(filter: (EthereumBlock) -> Boolean): ReceiveChannel<EthereumBlock> {
        return this.blockEventBus.subscribe(filter)
    }

    fun subscribeTx(filter: (TransactionResult<EthereumTransaction>) -> Boolean): ReceiveChannel<TransactionResult<EthereumTransaction>> {
        return this.txEventBus.subscribe(filter)
    }
}