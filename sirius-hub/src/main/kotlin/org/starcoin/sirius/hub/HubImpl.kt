package org.starcoin.sirius.hub

import com.google.common.base.Preconditions
import com.google.common.eventbus.EventBus
import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.starcoin.proto.Starcoin
import org.starcoin.proto.Starcoin.*
import org.starcoin.sirius.core.*
import org.starcoin.sirius.core.AMTreePathInternalNode
import org.starcoin.sirius.core.AMTreeProof
import org.starcoin.sirius.core.Update
import org.starcoin.sirius.core.UpdateData
import org.starcoin.sirius.crypto.CryptoKey
import org.starcoin.sirius.crypto.CryptoService
import org.starcoin.sirius.protocol.Chain
import org.starcoin.sirius.protocol.HubContract
import org.starcoin.sirius.protocol.QueryContractParameter
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class HubImpl<T : ChainTransaction>(
    private val hubKey: CryptoKey,
    private val blocksPerEon: Int,
    private val chain: Chain<T, Block<T>, HubContract>
) : Hub {

    private val contract = chain.getContract(QueryContractParameter(0))

    private var eonState: EonState

    private val hubAddress: Address

    private val logger = Logger.getLogger(Hub::class.java.name)

    private val eventBus: EventBus

    @Volatile
    private var ready: Boolean = false

    private val txReceipts = ConcurrentHashMap<Hash, CompletableFuture<Receipt>>()

    private val strategy: MaliciousStrategy

    init {
        //TODO
        eonState = EonState(0)
        this.eventBus = EventBus()
        this.hubAddress = hubKey.address
        this.strategy = MaliciousStrategy()
    }

    override val hubInfo: HubInfo
        get() {
            if (!this.ready) {
                return HubInfo(this.ready, this.blocksPerEon)
            }
            return HubInfo(
                ready,
                blocksPerEon,
                eonState.eon,
                stateRoot.toAMTreePathNode() as AMTreePathInternalNode,
                hubKey.keyPair.public
            )
        }

    override val stateRoot: AMTreeNode
        get() = this.eonState.state.root

    override var hubMaliciousFlag: EnumSet<Hub.MaliciousFlag>
        get() = EnumSet.copyOf(this.maliciousFlags)
        set(flags) {
            this.maliciousFlags.addAll(flags)
        }

    private var maliciousFlags: EnumSet<Hub.MaliciousFlag> = EnumSet.noneOf(Hub.MaliciousFlag::class.java)
    private val gang: ParticipantGang by lazy {
        val gang = ParticipantGang.random()
        val update = Update(UpdateData(currentEon().id))
        update.sign(gang.privateKey)
        registerParticipant(gang.participant, update)
        gang
    }

    override fun start() {
        //TODO
        //this.chain.watchTransactions()
        //connection.watchBlock { this.onBlock(it) }
    }

    fun getEonState(eon: Int): EonState? {
        var eonState = this.eonState
        if (eonState.eon < eon) {
            return null
        }
        if (eonState.eon == eon) {
            return eonState
        }
        while (eon <= eonState.eon) {
            if (eonState.eon == eon) {
                return eonState
            }
            eonState = eonState.previous ?: return null
        }
        return null
    }

    override fun registerParticipant(participant: Participant, initUpdate: Update): Update {
        this.checkReady()
        Preconditions.checkArgument(initUpdate.verifySig(participant.publicKey))
        if (this.getHubAccount(participant.address!!) != null) {
            throw StatusRuntimeException(Status.ALREADY_EXISTS)
        }
        initUpdate.signHub(this.hubKey)
        val account = HubAccount(participant.publicKey, initUpdate, 0)
        this.eonState.addAccount(account)
        return initUpdate
    }

    override fun deposit(participant: Address, amount: Long) {
        val account = this.eonState.getAccount(participant)
        account.get().addDeposit(amount)
    }

    override fun getHubAccount(address: Address): HubAccount? {
        return this.getHubAccount(this.eonState.eon, address)
    }

    override fun getHubAccount(eon: Int, address: Address): HubAccount? {
        this.checkReady()
        return this.getEonState(eon)!!.getAccount(address).orElse(null)
    }

    fun getHubAccount(predicate: (HubAccount) -> Boolean): HubAccount? {
        this.checkReady()
        return this.eonState.getAccount(predicate).orElse(null)
    }

    override fun transfer(transaction: OffchainTransaction, fromUpdate: Update, toUpdate: Update): Array<Update> {
        this.checkReady()
        Preconditions.checkArgument(transaction.amount > 0, "transaction amount should > 0")
        val from = this.getHubAccount(transaction.from!!)!!
        this.checkBalance(from, transaction.amount)
        this.processOffchainTransaction(transaction, fromUpdate, toUpdate)
        return arrayOf(fromUpdate, toUpdate)
    }

    private fun processOffchainTransaction(
        transaction: OffchainTransaction, fromUpdate: Update, toUpdate: Update
    ) {
        logger.info(
            "processOffchainTransaction from:" + transaction.from + ", to:" + transaction.to
        )
        val from = this.getHubAccount(transaction.from!!)!!
        val to = this.getHubAccount(transaction.to!!)!!
        strategy.processOffchainTransaction(
            {
                from.appendTransaction(transaction, fromUpdate)
                to.appendTransaction(transaction, toUpdate)
            },
            transaction
        )
        fromUpdate.signHub(this.hubKey)
        toUpdate.signHub(this.hubKey)
        this.fireEvent(HubEvent(HubEventType.NEW_UPDATE, from.address, fromUpdate))
        this.fireEvent(HubEvent(HubEventType.NEW_UPDATE, to.address, toUpdate))
    }

    private fun fireEvent(event: HubEvent<out SiriusObject>) {
        try {
            logger.info("fireEvent:$event")
            this.eventBus.post(event)
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private fun checkBalance(account: HubAccount, amount: Long) {
        Preconditions.checkState(account.balance >= amount)
    }

    fun checkIOU(iou: IOU, isSender: Boolean) {
        this.checkReady()
        val transaction = iou.transaction!!
        Preconditions.checkArgument(transaction.amount!! > 0, "transaction amount should > 0")
        val sender = this.getHubAccount(transaction.from!!)!!
        Preconditions.checkArgument(
            transaction.verify(sender.publicKey), "transaction verify fail."
        )
        val recipient = this.getHubAccount(transaction.to!!)!!

        this.checkBalance(sender, transaction.amount!!)
        if (isSender) {
            checkUpdate(sender, iou)
        } else {
            checkUpdate(recipient, iou)
        }
    }

    override fun sendNewTransfer(iou: IOU) {
        if (this.eonState.getIOUByFrom(iou.transaction!!.to!!) != null) {
            throw StatusRuntimeException(Status.ALREADY_EXISTS)
        }
        if (this.eonState.getIOUByTo(iou.transaction!!.to!!) != null) {
            throw StatusRuntimeException(Status.ALREADY_EXISTS)
        }
        this.checkIOU(iou, true)
        this.strategy.processSendNewTransaction(
            {
                this.eonState.addIOU(iou)
                this.fireEvent(
                    HubEvent(
                        HubEventType.NEW_TX, iou.transaction!!.to!!, iou.transaction!!
                    )
                )
            },
            iou
        )
    }

    override fun receiveNewTransfer(receiverIOU: IOU) {
        if (this.eonState.getIOUByTo(receiverIOU.transaction!!.to!!) == null) {
            throw StatusRuntimeException(Status.NOT_FOUND)
        }
        val iou = this.eonState.getIOUByFrom(receiverIOU.transaction.from) ?: throw StatusRuntimeException(
            Status.NOT_FOUND
        )
        this.checkIOU(receiverIOU, false)
        this.processOffchainTransaction(
            receiverIOU.transaction!!, iou.update!!, receiverIOU.update!!
        )
        this.eonState.removeIOU(receiverIOU)
    }

    override fun queryNewTransfer(address: Address): OffchainTransaction? {
        val iou = this.eonState.getIOUByTo(address)
        return if (iou == null) null else iou.transaction!!
    }

    private fun checkUpdate(account: HubAccount, iou: IOU) {
        Preconditions.checkState(
            iou.update!!.verifySig(account.publicKey), "Update signature miss match."
        )
        logger.info(
            String.format(
                "iou version %d,server version %d ",
                iou.update!!.version, account.update!!.version
            )
        )
        Preconditions.checkState(
            iou.update!!.version > account.update!!.version,
            "Update version should > previous version"
        )
        val txs = ArrayList(account.getTransactions())
        txs.add(iou.transaction)
        val merkleTree = MerkleTree(txs)
        Preconditions.checkState(
            iou.update!!.root!!.equals(merkleTree.hash()), "Merkle root miss match."
        )
    }

    override fun getProof(address: Address): AMTreeProof? {
        return this.getProof(this.eonState.eon, address)
    }

    override fun getProof(eon: Int, address: Address): AMTreeProof? {
        this.checkReady()
        val eonState = this.getEonState(eon) ?: return null
        return eonState.state.getMembershipProof(address)
    }

    override fun currentEon(): Eon {
        return Eon(eonState.eon, eonState.currentEpoch)
    }

    private fun checkReady() {
        Preconditions.checkState(this.ready, "Hub is not ready for service, please wait.")
    }

    override fun watch(listener: Hub.HubEventListener) {
        this.eventBus.register(listener)
    }

    override fun watch(address: Address): BlockingQueue<HubEvent<SiriusObject>> {
        val blockingQueue = ArrayBlockingQueue<HubEvent<SiriusObject>>(5, false)
        this.watch(Hub.HubEventListener { event ->
            if (event.isPublicEvent || event.address == address) {
                blockingQueue.offer(event)
            }
        })
        return blockingQueue
    }

    override fun watchByFilter(predicate: (HubEvent<SiriusObject>) -> Boolean): BlockingQueue<HubEvent<SiriusObject>> {
        val blockingQueue = ArrayBlockingQueue<HubEvent<SiriusObject>>(5, false)
        this.watch(Hub.HubEventListener { event ->
            if (predicate(event)) {
                blockingQueue.offer(event)
            }
        })
        return blockingQueue
    }

    private fun doCommit(): CompletableFuture<Receipt> {
        val hubRoot =
            HubRoot(this.eonState.state.root.toAMTreePathNode() as AMTreePathInternalNode, this.eonState.eon)
        logger.info("doCommit:" + hubRoot.toJSON())
        TODO()
//        val chainTransaction = ChainTransaction(
//            this.hubAddress,
//            Constants.CONTRACT_ADDRESS,
//            //TODO
//            "Commit",
//            hubRoot.toProto()
//        )
//        chainTransaction.sign(this.hubKeyPair)
//        this.connection.submitTransaction(chainTransaction)
//        val future = CompletableFuture<Receipt>()
//        this.txReceipts[chainTransaction.hash()] = future
//        return future
    }

    private fun processTransferDeliveryChallenge(challenge: Starcoin.OpenTransferDeliveryChallengeRequest) {
        val tx = OffchainTransaction.parseFromProtoMessage(challenge.transaction)

        val to = tx.to!!
        val accountProof = this.eonState.state.getMembershipProof(to)
        val previousAccount = this.eonState.previous!!.getAccount(to).get()

        var txProof: MerklePath? = null
        val txs = previousAccount.getTransactions()
        if (!txs.isEmpty()) {
            val merkleTree = MerkleTree(txs)
            txProof = merkleTree.getMembershipProof(tx.hash())
        }
        if (txProof != null) {
            val closeChallenge = CloseTransferDeliveryChallengeRequest.newBuilder()
                //TODO
//                .setBalancePath(accountProof?.path.toProto())
//                .setTransPath(txProof.toProto<ProtoMerklePath>())
//                .setUpdate(accountProof.leaf!!.account!!.update!!.toProto() as Starcoin.Update)
//                .setToUserPublicKey(
//                    ByteString.copyFrom(KeyPairUtil.encodePublicKey(previousAccount!!.publicKey!!))
//                )
                .build()

//            val chainTransaction = ChainTransaction(
//                this.hubAddress,
//                Constants.CONTRACT_ADDRESS,
//                "CloseTransferDeliveryChallenge",
//                //LiquidityContractServiceGrpc.getCloseTransferDeliveryChallengeMethod()
//                //    .getFullMethodName(),
//                closeChallenge
//            )
//            chainTransaction.sign(this.hubKeyPair)
//            this.connection.submitTransaction(chainTransaction)
        } else {
            logger.warning("Can not find tx Proof with challenge:" + challenge.toString())
        }
    }

    private fun processBalanceUpdateChallenge(challenge: Starcoin.ProtoBalanceUpdateChallenge) {
        val address = Address.getAddress(
            CryptoService.loadPublicKey(challenge.publicKey.toByteArray())
        )
        val proofPath = this.eonState.state.getMembershipProof(address)

        //TODO
        val proof = BalanceUpdateProof()//(proofPath.leaf!!.account!!.update!!, proofPath)
        val closeBalanceUpdateChallengeRequest = proof.toProto<Starcoin.ProtoBalanceUpdateProof>()

//        val chainTransaction = ChainTransaction(
//            this.hubAddress,
//            Constants.CONTRACT_ADDRESS,
//            "CloseBalanceUpdateChallenge",
//            //LiquidityContractServiceGrpc.getCloseBalanceUpdateChallengeMethod().getFullMethodName(),
//            closeBalanceUpdateChallengeRequest
//        )
//        chainTransaction.sign(this.hubKeyPair)
        //this.connection.submitTransaction(chainTransaction)
    }

    private fun processWithdrawal(withdrawal: Withdrawal) {
        this.strategy.processWithdrawal(
            {
                val blockAddress = withdrawal.address!!
                val amount = withdrawal.amount
                val hubAccount = this.eonState.getAccount(blockAddress).get()
                if (!hubAccount.addWithdraw(amount)) {
                    // signed update (e) or update (e − 1), τ (e − 1)
                    val cancelWithdrawalBuilder = CancelWithdrawalRequest.newBuilder()
                        .setParticipant(Participant(hubAccount.publicKey!!).toProto() as ProtoParticipant)
                        .setUpdate(hubAccount.update!!.toProto() as Starcoin.Update)
                    if (hubAccount.update!!.isSigned) {
                        val path = this.eonState.state.getMembershipProof(blockAddress)
                        //TODO
                        //cancelWithdrawalBuilder.setPath(path.toProto()).build()
                    }
//                    val chainTransaction = ChainTransaction(
//                        this.hubAddress,
//                        Constants.CONTRACT_ADDRESS,
//                        "CancelWithdrawal",
//                        //LiquidityContractServiceGrpc.getCancelWithdrawalMethod().getFullMethodName(),
//                        cancelWithdrawalBuilder.build()
//                    )
//                    chainTransaction.sign(this.hubKeyPair)
//                    this.connection.submitTransaction(chainTransaction)
//                    val future = CompletableFuture<Receipt>()
//                    this.txReceipts[chainTransaction.hash()] = future
//                    future.whenComplete { _, _ ->
//                        // TODO ensure cancel success.
//                        logger.info(
//                            ("CancelWithdrawal:"
//                                    + blockAddress
//                                    + ", amount:"
//                                    + amount
//                                    + ", result:"
//                                    + future.getNow(null))
//                        )

//                        val withdrawalStatus = WithdrawalStatus(WithdrawalStatusType.INIT, withdrawal)
//                        withdrawalStatus.cancel()
//                        this.fireEvent(
//                            HubEvent(HubEventType.WITHDRAWAL, blockAddress, withdrawalStatus)
//                        )
//                    }
                } else {
                    val withdrawalStatus = WithdrawalStatus(WithdrawalStatusType.INIT, withdrawal)
                    withdrawalStatus.pass()
                    this.fireEvent(HubEvent(HubEventType.WITHDRAWAL, blockAddress, withdrawalStatus))
                }
            },
            withdrawal
        )
    }

    private fun processDeposit(deposit: Deposit) {
        this.strategy.processDeposit(
            {
                val hubAccount = this.eonState.getAccount(deposit!!.address!!).get()
                hubAccount.addDeposit(deposit.amount)
                this.fireEvent(
                    HubEvent(
                        HubEventType.NEW_DEPOSIT,
                        deposit.address!!,
                        Deposit(deposit.address!!, deposit.amount)
                    )
                )
            },
            deposit
        )
    }

    override fun onBlock(blockInfo: Block<*>) {
        logger.info("onBlock:$blockInfo")
        val eon = Eon.calculateEon(blockInfo.height, this.blocksPerEon)
        var newEon = false
        if (this.eonState == null) {
            this.eonState = EonState(eon.id)
            newEon = true
        }
        this.eonState.setEpoch(eon.epoch)
        if (eon.id != this.eonState.eon) {
            val eonState = EonState(eon.id, this.eonState)
            this.eonState = eonState
            newEon = true
        }
        if (newEon) {
            val future = this.doCommit()
            // TODO only start new eon after commit success.
            future.whenComplete { receipt, throwable ->
                if (!receipt.success) {
                    // TODO
                    logger.severe("commit tx receipt is failure.")
                } else {
                    // TODO only
                    this.ready = true
                    this.fireEvent(
                        HubEvent(
                            HubEventType.NEW_HUB_ROOT,
                            HubRoot(
                                this.eonState.state.root.toAMTreePathNode() as AMTreePathInternalNode,
                                this.eonState.eon
                            )
                        )
                    )
                }
            }
        }
        // only process contract tx.
        blockInfo.filterTxByTo(Constants.CONTRACT_ADDRESS).stream().forEach { this.processTransaction(it) }
    }

    private fun processTransaction(tx: ChainTransaction) {
        val hash = tx.hash()
//        txReceipts[hash]?.complete(tx.receipt)
//        // default action is Deposit.
//        if (tx.action == null) {
//            val deposit = Deposit(tx.from!!, tx.amount!!)
//            logger.info("Deposit:" + deposit.toJSON())
//            this.processDeposit(deposit)
//        } else if (tx.action == "InitiateWithdrawal") {
//            val arguments = tx.getArguments(InitiateWithdrawalRequest::class.java)!!
//            //TODO
//            val withdrawal = Withdrawal.parseFromProtoMessage(arguments)
//            logger.info(tx.action + ":" + withdrawal.toString())
//            this.processWithdrawal(withdrawal)
//        } else if (tx.action == "OpenTransferDeliveryChallenge") {
//            //TODO
//            val arguments = tx.getArguments(Starcoin.OpenTransferDeliveryChallengeRequest::class.java)!!
//            logger.info(tx.action + ":" + arguments.toString())
//            this.processTransferDeliveryChallenge(arguments)
//        } else if (tx.action == "OpenBalanceUpdateChallenge") {
//            //TODO
//            val arguments = tx.getArguments(ProtoBalanceUpdateChallenge::class.java)!!
//            logger.info(tx.action + ":" + arguments.toString())
//            this.processBalanceUpdateChallenge(arguments)
//        }
    }

    override fun resetHubMaliciousFlag(): EnumSet<Hub.MaliciousFlag> {
        val result = this.hubMaliciousFlag
        this.maliciousFlags = EnumSet.noneOf(Hub.MaliciousFlag::class.java)
        return result
    }


    private inner class MaliciousStrategy {

        fun processDeposit(normalAction: () -> Unit, deposit: Deposit) {
            // steal deposit to a hub gang Participant
            if (maliciousFlags.contains(Hub.MaliciousFlag.STEAL_DEPOSIT)) {
                logger.info(
                    gang.participant.address.toString()
                            + " steal deposit from "
                            + deposit.address.toString()
                )
                val hubAccount = eonState.getAccount(gang.participant.address).get()
                hubAccount.addDeposit(deposit.amount)
            } else {
                normalAction()
            }
        }

        fun processWithdrawal(normalAction: () -> Unit, withdrawal: Withdrawal) {
            // steal withdrawal from a random user who has enough balance.
            if (maliciousFlags.contains(Hub.MaliciousFlag.STEAL_WITHDRAWAL)) {
                val hubAccount =
                    getHubAccount { account -> (account.address != withdrawal.address && account.balance >= withdrawal.amount) }
                if (hubAccount != null) {
                    hubAccount.addWithdraw(withdrawal.amount)
                    logger.info(
                        (withdrawal.address.toString()
                                + " steal withdrawal from "
                                + hubAccount.address.toString())
                    )
                } else {
                    normalAction()
                }
            } else {
                normalAction()
            }
        }

        fun processOffchainTransaction(normalAction: () -> Unit, tx: OffchainTransaction) {
            // steal transaction, not real update account's tx.
            if (maliciousFlags.contains(Hub.MaliciousFlag.STEAL_TRANSACTION)) {
                logger.info("steal transaction:" + tx.toJSON())
                // do nothing
            } else {
                normalAction()
            }
        }

        fun processSendNewTransaction(normalAction: () -> Unit, sendIOU: IOU) {
            if (maliciousFlags.contains(Hub.MaliciousFlag.STEAL_TRANSACTION_IOU)) {
                logger.info("steal transaction iou from:" + sendIOU.transaction!!.from)
                checkIOU(sendIOU, true)
                val tx = OffchainTransaction(
                    sendIOU.transaction.eon,
                    sendIOU.transaction.from,
                    gang.participant.address,
                    sendIOU.transaction.amount
                )

                val from = getHubAccount(sendIOU.transaction.from)!!

                val to = getHubAccount(gang.participant.address)!!
                val sendTxs = ArrayList(to.getTransactions())
                sendTxs.add(tx)
                val toUpdate = Update.newUpdate(
                    to.update.eon, to.update.version + 1, to.address, sendTxs
                )
                toUpdate.sign(gang.privateKey)

                val fromUpdate = sendIOU.update

                from.appendTransaction(sendIOU.transaction, fromUpdate)
                to.appendTransaction(tx, toUpdate)

                fromUpdate.signHub(hubKey)
                toUpdate.signHub(hubKey)
                // only notice from.
                fireEvent(HubEvent(HubEventType.NEW_UPDATE, from.address, fromUpdate))
            } else {
                normalAction()
            }
        }
    }
}
