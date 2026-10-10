package net.solardepin.solarchik.wallet

import android.net.Uri
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import com.solana.mobilewalletadapter.common.AssociationContract
import com.solana.mobilewalletadapter.common.WebSocketsTransportContract

/**
 * 1.2.2: checks the association intent the clientlib built against the MWA spec, so the diagnostics log
 * states it in words (a log retyped or read off a video can't be mistaken for what the app sent).
 * Spec: `solana-wallet:/v1/associate/local?association=<base64url P-256 public key>&port=<49152..65535>[&v=…]`;
 * the wallet hosts the WebSocket server, the app connects to ws://127.0.0.1:<port>/solana-wallet.
 */
object MwaUri {
    /** Problems with [u] as a local association URI; empty = matches the spec. */
    fun problems(u: Uri?): List<String> {
        if (u == null) return listOf("no uri")
        val out = mutableListOf<String>()
        if (u.scheme != AssociationContract.SCHEME_MOBILE_WALLET_ADAPTER) out += "scheme=" + u.scheme
        if (!u.authority.isNullOrEmpty()) out += "has a host '" + u.authority + "'"
        if (u.path != "/" + AssociationContract.LOCAL_PATH_SUFFIX) out += "path=" + u.path
        val names = u.queryParameterNames
        val extra = names - setOf(AssociationContract.PARAMETER_ASSOCIATION_TOKEN, AssociationContract.LOCAL_PARAMETER_PORT, AssociationContract.PARAMETER_PROTOCOL_VERSION)
        if (extra.isNotEmpty()) out += "unknown params " + extra.joinToString(",")
        val port = u.getQueryParameter(AssociationContract.LOCAL_PARAMETER_PORT)?.toIntOrNull()
        if (port == null || port !in WebSocketsTransportContract.WEBSOCKETS_LOCAL_PORT_MIN..WebSocketsTransportContract.WEBSOCKETS_LOCAL_PORT_MAX) out += "port=" + port
        val key = u.getQueryParameter(AssociationContract.PARAMETER_ASSOCIATION_TOKEN)
        val raw = runCatching { android.util.Base64.decode(key, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP) }.getOrNull()
        if (raw == null || raw.size != 65 || raw[0] != 0x04.toByte()) out += "association key is not a P-256 public key (" + (raw?.size ?: 0) + " bytes)"
        return out
    }

    /** One readable line: what the app sent, judged against the spec. */
    fun summary(u: Uri?): String {
        val p = problems(u)
        val shape = (u?.scheme ?: "?") + ":" + (u?.path ?: "") + "?" + (u?.queryParameterNames?.joinToString("&") { "$it=…" } ?: "")
        return (if (p.isEmpty()) "spec OK " else "NOT SPEC (" + p.joinToString("; ") + ") ") + shape
    }

    /** The WebSocket address the clientlib's scenario dials (wallet = server, app = client). */
    fun wsTarget(s: LocalAssociationScenario): String = runCatching {
        val f = LocalAssociationScenario::class.java.getDeclaredField("mWebSocketUri").apply { isAccessible = true }
        f.get(s).toString()
    }.getOrDefault("ws://" + WebSocketsTransportContract.WEBSOCKETS_LOCAL_HOST + ":" + s.port + WebSocketsTransportContract.WEBSOCKETS_LOCAL_PATH)

    const val SUBPROTOCOL = WebSocketsTransportContract.WEBSOCKETS_PROTOCOL
}
