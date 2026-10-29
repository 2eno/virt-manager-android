package dev.virtmanager.android.ssh

import kotlinx.coroutines.CancellationException

/** One SSH hop of a connection. [id] identifies the saved profile it came from. */
class SshHop(val id: String, val name: String, val target: SshTarget, val verifier: HostKeyVerifier)

/** Connecting failed at [hop]; [isJump] tells whether it is a jump host or the final host. */
class SshHopException(val hop: SshHop, val isJump: Boolean, cause: Throwable) : Exception(cause.message, cause)

/** Connects through the jump hosts in [hops] (first to last) to the last one, like ProxyJump. */
object SshChain {
    suspend fun connect(hops: List<SshHop>, knownHosts: KnownHosts): SshSession =
        connect(hops, SshSession::close) { hop, via -> SshSession.connect(hop.target, knownHosts, via, hop.verifier) }

    /**
     * Connects each hop over the previous one with [connect]; on failure closes what is open with
     * [close]. Without jump hosts errors pass through unchanged. With them, an error names the hop
     * it belongs to; the jump host's refusal to open the tunnel is blamed on the jump host.
     */
    suspend fun <S : Any> connect(hops: List<SshHop>, close: (S) -> Unit, connect: suspend (SshHop, S?) -> S): S {
        require(hops.isNotEmpty())
        var via: S? = null
        for ((i, hop) in hops.withIndex()) {
            via = try {
                connect(hop, via)
            } catch (t: Throwable) {
                via?.let(close)
                if (hops.size == 1 || t is CancellationException) throw t
                val tunnel = generateSequence(t) { it.cause }.filterIsInstance<TunnelOpenException>().firstOrNull()
                if (tunnel != null && i > 0) throw SshHopException(hops[i - 1], isJump = true, tunnel)
                throw SshHopException(hop, isJump = i < hops.size - 1, t)
            }
        }
        return via!!
    }
}
