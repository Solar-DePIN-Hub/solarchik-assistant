package net.solardepin.solarchik

import net.solardepin.solarchik.screen.CallActionRules
import net.solardepin.solarchik.wallet.Base58
import org.junit.Test
import org.sol4k.PublicKey
import java.io.File

/** Writes our exact transfer bytes for an independent decoder (node + @solana/web3.js) when solarchik.txdump is set. */
class TxBytesDumpTest {
    @Test fun dump() {
        val out = System.getProperty("solarchik.txdump") ?: return
        val bh = Base58.decode(System.getProperty("solarchik.blockhash") ?: Base58.encode(ByteArray(32) { 7 }))
        val me = PublicKey("8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic")
        val ira = PublicKey("HpEVVYWx2LiFANXAzfMy3yPTf61X1ZVDheYDYNmDJBwT")
        val sol = CallActionRules.paymentTx(me, ira, "SOL", CallActionRules.amountRaw("SOL", 0.0001), bh).serialize()
        val usdc = CallActionRules.paymentTx(me, ira, "USDC", CallActionRules.amountRaw("USDC", 0.1), bh).serialize()
        File(out).writeText(java.util.Base64.getEncoder().encodeToString(sol) + "\n" + java.util.Base64.getEncoder().encodeToString(usdc) + "\n")
    }
}
