package org.starcoin.sirius.protocol.ethereum

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ImplicitReflectionSerializer
import org.ethereum.util.blockchain.EtherUtil
import org.junit.Assert
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.starcoin.sirius.core.*
import org.starcoin.sirius.crypto.CryptoKey
import org.starcoin.sirius.crypto.CryptoService
import org.starcoin.sirius.protocol.ContractConstructArgs
import org.starcoin.sirius.protocol.EthereumTransaction
import org.starcoin.sirius.protocol.TransactionResult
import org.starcoin.sirius.protocol.ethereum.contract.EthereumHubContract
import org.starcoin.sirius.util.MockUtils
import org.starcoin.sirius.util.WithLogging
import java.math.BigInteger
import kotlin.properties.Delegates

class InMemoryHubContractTest {

    companion object : WithLogging()

    private var chain: InMemoryChain by Delegates.notNull()
    private var contract: EthereumHubContract by Delegates.notNull()

    private var owner: EthereumAccount by Delegates.notNull()
    private var alice: EthereumAccount by Delegates.notNull()

    private var ownerChannel: Channel<TransactionResult<EthereumTransaction>> by Delegates.notNull()
    private var aliceChannel: Channel<TransactionResult<EthereumTransaction>> by Delegates.notNull()

    private var blocksPerEon = ContractConstructArgs.DEFAULT_ARG.blocksPerEon

    private var startBlockNumber: BigInteger by Delegates.notNull()

    @Before
    fun beforeTest() {
        chain = InMemoryChain(true)

        owner = EthereumAccount(CryptoService.generateCryptoKey())
        alice = EthereumAccount(CryptoService.generateCryptoKey())

        val amount = EtherUtil.convert(100000, EtherUtil.Unit.ETHER)
        this.sendEther(owner.address, amount)
        this.sendEther(alice.address, amount)

        this.contract = chain.deployContract(owner, ContractConstructArgs.DEFAULT_ARG)
        aliceChannel =
            chain.watchTransactions { it.tx.from == alice.address && it.tx.to == contract.contractAddress }
        ownerChannel =
            chain.watchTransactions { it.tx.from == owner.address && it.tx.to == contract.contractAddress }
        val hubInfo = this.contract.queryHubInfo(owner)
        startBlockNumber = hubInfo.startBlockNumber
    }

    fun sendEther(address: Address, amount: BigInteger) {
        chain.sb.sendEther(address.toBytes(), amount)
        chain.sb.createBlock()
        Assert.assertEquals(amount, chain.getBalance(address))
    }

    @Ignore
    @Test
    @ImplicitReflectionSerializer
    fun testCurrentEon() {
        Assert.assertEquals(contract.getCurrentEon(), 0)
    }

    fun deposit(alice: EthereumAccount, amount: BigInteger) {
        var ethereumTransaction = EthereumTransaction(
            contract.contractAddress, alice.getNonce(), 21000.toBigInteger(),
            210000.toBigInteger(), amount
        )

        chain.submitTransaction(alice, ethereumTransaction)

        //chain.sb.sendEther(alice.address.toBytes(), BigInteger.valueOf(1))
        chain.sb.createBlock()
    }

    @Test
    @ImplicitReflectionSerializer
    fun testDeposit() {

        var amount = EtherUtil.convert(100, EtherUtil.Unit.GWEI)

        deposit(alice, amount)

        runBlocking {
            var transaction = aliceChannel.receive()
            Assert.assertTrue(transaction.receipt.status)
            Assert.assertEquals(transaction.tx.from, alice.address)
            Assert.assertEquals(transaction.tx.to, contract.contractAddress)
            Assert.assertEquals(transaction.tx.amount, amount)
        }

        Assert.assertEquals(
            amount,
            chain.getBalance(contract.contractAddress)
        )

    }

    @Test
    @ImplicitReflectionSerializer
    fun testWithDrawal() {

        //chain.sb.withAccountBalance(alice.address.toBytes(), EtherUtil.convert(100000, EtherUtil.Unit.ETHER))
        //println(chain.sb.getBlockchain().getRepository().getBalance(alice.address.toBytes()))

        var amount = EtherUtil.convert(1000, EtherUtil.Unit.GWEI)
        deposit(alice, amount)

        runBlocking {
            var transaction = aliceChannel.receive()
            Assert.assertTrue(transaction.receipt.status)
            Assert.assertEquals(transaction.tx.from, alice.address)
            Assert.assertEquals(transaction.tx.to, contract.contractAddress)
            Assert.assertEquals(transaction.tx.amount, amount)
        }

        /**
        var hash=commitHubRoot(0,amount)

        println(chain.getNumber())
        println(contract.getCurrentEon())
        var transaction=chain.findTransaction(hash)
        Assert.assertEquals(transaction?.to,contract.contractAddress)
        Assert.assertEquals(transaction?.from,Address.wrap(chain.sb.sender.address))*/

        val eon = 1
        val path = newProof(alice.address, newUpdate(eon, 1, BigInteger.ZERO, alice), BigInteger.ZERO, amount)

        var contractAddr = contract.contractAddress

        //var owner = chain.sb.sender
        //chain.sb.sender = (alice as EthCryptoKey).ecKey

        amount = EtherUtil.convert(8, EtherUtil.Unit.GWEI)
        val withdrawal = Withdrawal(alice.address, path, amount)
        var hash = contract.initiateWithdrawal(alice, withdrawal)
        //TODO use feature to wait.
        Thread.sleep(500)
        var transaction = chain.findTransaction(hash)

        Assert.assertEquals(transaction?.from, alice.address)
        Assert.assertEquals(transaction?.to, contractAddr)

        //chain.sb.sender = owner

        amount = EtherUtil.convert(2, EtherUtil.Unit.ETHER)

        val update = newUpdate(eon, 2, amount, alice.key)
        val proof = newProof(alice.address, newUpdate(eon, 1, BigInteger.ZERO, alice), BigInteger.ZERO, amount)

        val cancel =
            CancelWithdrawal(alice.address, update, proof)
        hash = contract.cancelWithdrawal(owner, cancel)
        transaction = chain.findTransaction(hash)
        //TODO use feature to wait.
        Thread.sleep(500)
        Assert.assertEquals(transaction?.from, owner.address)
        Assert.assertEquals(transaction?.to, contractAddr)

    }

    private fun newPath(addr: Address, update: Update, offset: BigInteger, allotment: BigInteger): AMTreePath {
        val path = AMTreePath(update.eon, newLeaf(addr, update, offset, allotment))
        for (i in 0..MockUtils.nextInt(0, 10)) {
            path.append(AMTreePathInternalNode.mock())
        }

        return path
    }

    private fun newProof(addr: Address, update: Update, offset: BigInteger, allotment: BigInteger): AMTreeProof {
        return AMTreeProof(newPath(addr, update, offset, allotment), newLeaf(addr, update, offset, allotment))
    }

    private fun newLeaf(addr: Address, update: Update, offset: BigInteger, allotment: BigInteger): AMTreePathLeafNode {
        val nodeInfo = AMTreeLeafNodeInfo(addr.hash(), update)
        return AMTreePathLeafNode(nodeInfo, PathDirection.LEFT, offset, allotment)
    }

    private fun newUpdate(eon: Int, version: Long, sendAmount: BigInteger, callUser: EthereumAccount): Update {
        return newUpdate(eon, version, sendAmount, callUser.key)
    }

    private fun newUpdate(eon: Int, version: Long, sendAmount: BigInteger, callUser: CryptoKey): Update {
        val updateData = UpdateData(eon, version, sendAmount, 0.toBigInteger(), Hash.random())
        val update = Update(updateData)
        update.sign(callUser)
        update.signHub(callUser)
        return update
    }

    @Test
    @ImplicitReflectionSerializer
    fun testCommit() {

        val amount = EtherUtil.convert(1000, EtherUtil.Unit.GWEI)

        deposit(alice, amount)

        runBlocking {
            val txResult = aliceChannel.receive()
            Assert.assertTrue(txResult.receipt.status)
            Assert.assertEquals(txResult.tx.from, alice.address)
            Assert.assertEquals(txResult.tx.to, contract.contractAddress)
            Assert.assertEquals(txResult.tx.amount, amount)
        }

        Assert.assertEquals(
            amount,
            chain.getBalance(contract.contractAddress)
        )
        val eon = 1
        val root = newHubRoot(eon, amount)
        commitHubRoot(1, root)

        val root1 = contract.getLatestRoot(EthereumAccount.DUMMY_ACCOUNT)
        Assert.assertEquals(root, root1)
    }

    fun newHubRoot(eon: Int, amount: BigInteger): HubRoot {
        val info = AMTreeInternalNodeInfo(Hash.random(), amount, Hash.random())
        val node = AMTreePathInternalNode(info, PathDirection.ROOT, 0.toBigInteger(), amount)
        return HubRoot(node, eon)
    }

    fun waitToEon(eon: Int) {
        if (eon == 0) {
            return
        }
        val currentBlockNumber = chain.getBlockNumber()
        for (i in 0..Eon.waitToEon(startBlockNumber, currentBlockNumber, blocksPerEon, eon)) {
            chain.sb.createBlock()
        }
    }

    private fun commitHubRoot(eon: Int, amount: BigInteger): Hash {
        return commitHubRoot(eon, newHubRoot(eon, amount))
    }

    private fun commitHubRoot(eon: Int, root: HubRoot): Hash {
        LOG.info("current block height is :${chain.getBlockNumber()}, commit root: $root")
        waitToEon(eon)
        val hash = contract.commit(owner, root)
        runBlocking {
            val txResult = ownerChannel.receive()
            Assert.assertTrue(txResult.receipt.status)
            Assert.assertEquals(hash, txResult.tx.hash())
            val transaction = txResult.tx
            Assert.assertEquals(contract.contractAddress, transaction.to)
            Assert.assertEquals(owner.address, transaction.from)
        }
        return hash
    }

    @Test
    @ImplicitReflectionSerializer
    fun testHubInfo() {
        var ip = "192.168.0.0.1:80"
        contract.setHubIp(owner, ip)

        var hubInfo = contract.queryHubInfo(EthereumAccount.DUMMY_ACCOUNT)
        Assert.assertNotNull(hubInfo)
        Assert.assertEquals(hubInfo.hubAddress, ip)
    }

    @Test
    @ImplicitReflectionSerializer
    fun testBalanceUpdateChallenge() {

        //var transactions = List<EthereumTransaction>
        var amount = EtherUtil.convert(100, EtherUtil.Unit.GWEI)

        deposit(alice, amount)

        commitHubRoot(1, amount)

        //var owner = chain.sb.sender
        //chain.sb.sender = (alice as EthCryptoKey).ecKey

        val update1 = newUpdate(0, 1, BigInteger.ZERO, alice.key)//other
        val path = newPath(alice.address, update1, BigInteger.ZERO, amount)
        val update2 = newUpdate(0, 1, BigInteger.ZERO, alice.key)//mine
        val leaf2 = newLeaf(alice.address, update2, 1100.toBigInteger(), 1000.toBigInteger())
        val amtp = AMTreeProof(path, leaf2)
        val bup = BalanceUpdateProof(true, update2, true, amtp)
        val buc = BalanceUpdateChallenge(bup, alice.key.keyPair.public)

        var hash = contract.openBalanceUpdateChallenge(owner, buc)

        //TODO
        Thread.sleep(500)
        var transaction = chain.findTransaction(hash)
        Assert.assertNotNull(transaction)

        //chain.sb.sender = owner
        val update3 = newUpdate(0, 3, BigInteger.ZERO, alice)//other
        val update4 = newUpdate(0, 4, BigInteger.ZERO, alice)//mine
        val path3 = newPath(alice.address, update3, BigInteger.ZERO, 20.toBigInteger())
        val leaf3 = newLeaf(alice.address, update4, 1100.toBigInteger(), 1000.toBigInteger())
        val amtp2 = AMTreeProof(path, leaf3)
        val close = CloseBalanceUpdateChallenge(update4, amtp2)
        hash = contract.closeBalanceUpdateChallenge(alice, close)
        //TODO
        Thread.sleep(500)
        transaction = chain.findTransaction(hash)
        Assert.assertNotNull(transaction)

    }

    @Test
    @ImplicitReflectionSerializer
    fun testTransferChallenge() {

        //var transactions = List<EthereumTransaction>
        var amount = EtherUtil.convert(100, EtherUtil.Unit.GWEI)

        deposit(alice, amount)

        commitHubRoot(1, amount)

        //var owner = chain.sb.sender
        //chain.sb.sender = (alice as EthCryptoKey).ecKey

        val update = newUpdate(0, 1, BigInteger.ZERO, alice)
        val txData = OffchainTransactionData(0, alice.address, owner.address, 10, 1)
        val tx = OffchainTransaction(txData)
        tx.sign(alice.key)
        val open = TransferDeliveryChallenge(update, tx, MerklePath.mock())
        var hash = contract.openTransferDeliveryChallenge(alice, open)
        //TODO
        Thread.sleep(500)
        var transaction = chain.findTransaction(hash)
        Assert.assertNotNull(transaction)

        //chain.sb.sender = owner

        val update1 = newUpdate(0, 1, BigInteger.ZERO, alice)//other
        val path = newPath(alice.address, update1, BigInteger.ZERO, 200.toBigInteger())
        val update2 = newUpdate(0, 1, BigInteger.ZERO, alice)//mine
        val leaf2 = newLeaf(alice.address, update2, 1100.toBigInteger(), 1000.toBigInteger())
        val amtp = AMTreeProof(path, leaf2)
        val close =
            CloseTransferDeliveryChallenge(amtp, update2, MerklePath.mock(), alice.key.keyPair.public, Hash.of(tx))
        hash = contract.closeTransferDeliveryChallenge(owner, close)
        //TODO
        Thread.sleep(500)
        transaction = chain.findTransaction(hash)
        Assert.assertNotNull(transaction)
    }
}
