package org.starcoin.sirius.core

import java.math.BigInteger

class Eon(val id: Int, val epoch: Epoch) {

    constructor(id: Long, epoch: Epoch) : this(id.toInt(), epoch)

    enum class Epoch {
        FIRST,
        SECOND,
        THIRD,
        LAST
    }

    companion object {

        fun calculateEon(blockHeight: Long, blocksPerEon: Int): Eon {
            return Eon(
                blockHeight / blocksPerEon.toLong(),
                Epoch.values()[(blockHeight % blocksPerEon / (blocksPerEon / 4)).toInt()]
            )
        }

        fun calculateEon(blockHeight: BigInteger, blocksPerEon: Int): Eon {
            return calculateEon(blockHeight.longValueExact(), blocksPerEon)
        }

        fun waitToEon(startBlockNumber: BigInteger, currentBlockNumber: BigInteger, blocksPerEon: Int, eon: Int): Int {
            return blocksPerEon * eon - (currentBlockNumber - startBlockNumber).intValueExact()
        }
    }
}
