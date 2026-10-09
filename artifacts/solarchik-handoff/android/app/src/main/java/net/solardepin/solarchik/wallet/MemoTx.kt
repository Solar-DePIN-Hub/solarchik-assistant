package net.solardepin.solarchik.wallet

/** Unsigned legacy Solana tx: Memo program, fee payer is the connected MWA account. */
object MemoTx {
    /** The daily check-in memo (unchanged since the game app; streak logic reads it back). */
    fun clockMemo(day: String, meters: Int, streak: Int): String =
        "solarchik clock $day ${meters}m s$streak ${net.solardepin.solarchik.game.GameSave.dayModOf(day)}"

    private val MEMO = Base58.decode("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")

    fun build(payer: ByteArray, recentBlockhash: ByteArray, memo: String): ByteArray {
        val data = memo.encodeToByteArray()
        val msg = ArrayList<Byte>(128 + data.size)
        // header: 1 required sig, 0 readonly signed, 1 readonly unsigned (memo program)
        msg += 1
        msg += 0
        msg += 1
        compact(msg, 2)
        msg += payer.toList()
        msg += MEMO.toList()
        require(recentBlockhash.size == 32)
        msg += recentBlockhash.toList()
        compact(msg, 1) // 1 instruction
        msg += 1 // program id index
        compact(msg, 1)
        msg += 0 // account index: payer
        compact(msg, data.size)
        msg += data.toList()

        val out = ArrayList<Byte>(64 + 1 + msg.size)
        compact(out, 1) // 1 signature slot
        repeat(64) { out += 0 }
        out += msg
        return out.toByteArray()
    }

    private fun compact(buf: ArrayList<Byte>, n: Int) {
        var value = n
        while (true) {
            var elem = value and 0x7f
            value = value ushr 7
            if (value == 0) {
                buf += elem.toByte()
                return
            }
            buf += (elem or 0x80).toByte()
        }
    }
}
