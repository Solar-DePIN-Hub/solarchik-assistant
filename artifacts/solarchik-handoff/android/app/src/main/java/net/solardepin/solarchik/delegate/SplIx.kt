package net.solardepin.solarchik.delegate

import net.solardepin.solarchik.solana.Borsh
import net.solardepin.solarchik.solana.Ix
import net.solardepin.solarchik.solana.Meta
import org.sol4k.PublicKey

/** SPL Token (Tokenkeg) instructions used by the 1.1.0 delegated mode. USDC, SKR and JUP are all Tokenkeg mints. */
object SplIx {
    val TOKEN_PROGRAM = PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val ATA_PROGRAM = PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL") // Associated Token Account program
    val SYSTEM_PROGRAM = PublicKey("11111111111111111111111111111111")

    const val IX_REVOKE = 5
    const val IX_CLOSE_ACCOUNT = 9
    const val IX_TRANSFER_CHECKED = 12
    const val IX_APPROVE_CHECKED = 13

    fun ata(owner: PublicKey, mint: PublicKey): PublicKey = PublicKey.findProgramDerivedAddress(owner, mint).publicKey

    /** ApproveChecked: [owner] lets [delegate] move up to [amount] raw units from [source]. Replaces any earlier approval. */
    fun approveChecked(source: PublicKey, mint: PublicKey, delegate: PublicKey, owner: PublicKey, amount: Long, decimals: Int): Ix = Ix(
        TOKEN_PROGRAM,
        listOf(Meta(source, false, true), Meta(mint, false, false), Meta(delegate, false, false), Meta(owner, true, false)),
        Borsh().u8(IX_APPROVE_CHECKED).u64(amount).u8(decimals).toByteArray(),
    )

    /** Revoke: removes the delegate of [source] (signed by the owner). */
    fun revoke(source: PublicKey, owner: PublicKey): Ix = Ix(
        TOKEN_PROGRAM,
        listOf(Meta(source, false, true), Meta(owner, true, false)),
        Borsh().u8(IX_REVOKE).toByteArray(),
    )

    /** TransferChecked; [authority] is the owner or the approved delegate. */
    fun transferChecked(source: PublicKey, mint: PublicKey, dest: PublicKey, authority: PublicKey, amount: Long, decimals: Int): Ix = Ix(
        TOKEN_PROGRAM,
        listOf(Meta(source, false, true), Meta(mint, false, false), Meta(dest, false, true), Meta(authority, true, false)),
        Borsh().u8(IX_TRANSFER_CHECKED).u64(amount).u8(decimals).toByteArray(),
    )

    /** CloseAccount: an empty token account's rent goes to [dest]. */
    fun closeAccount(account: PublicKey, dest: PublicKey, owner: PublicKey): Ix = Ix(
        TOKEN_PROGRAM,
        listOf(Meta(account, false, true), Meta(dest, false, true), Meta(owner, true, false)),
        Borsh().u8(IX_CLOSE_ACCOUNT).toByteArray(),
    )

    /** Associated Token Account program CreateIdempotent (instruction 1). */
    fun createAtaIdempotent(payer: PublicKey, owner: PublicKey, mint: PublicKey): Ix = Ix(
        ATA_PROGRAM,
        listOf(
            Meta(payer, true, true), Meta(ata(owner, mint), false, true), Meta(owner, false, false),
            Meta(mint, false, false), Meta(SYSTEM_PROGRAM, false, false), Meta(TOKEN_PROGRAM, false, false),
        ),
        byteArrayOf(1),
    )
}
