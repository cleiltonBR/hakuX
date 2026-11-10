package com.rfandango.haku_x.data.store

/**
 * Sources backed by existing public game-file collections.
 *
 * A source is listed here only when it can discover real published files. Generic
 * object-storage services that require the project owner to upload games are not
 * presented as game servers.
 */
data class StoreProviderProfile(
  val id: String,
  val displayName: String,
  val hosts: Set<String>,
  val supportsPublicFiles: Boolean,
  val supportsHttpRange: Boolean,
  val freeEgressPolicy: String,
  val limitations: String,
  val recommendation: Int,
) {
  fun matches(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
    return hosts.any { host == it || host.endsWith(".$it") }
  }
}

object StoreProviderRegistry {
  /**
   * This is an availability/source order, not a speed ranking. Range support and
   * throughput must be measured against the exact file URL at runtime.
   */
  val providers: List<StoreProviderProfile> = listOf(
    StoreProviderProfile(
      id = "internet-archive",
      displayName = "Internet Archive",
      hosts = setOf("archive.org"),
      supportsPublicFiles = true,
      supportsHttpRange = false,
      freeEgressPolicy = "Não há garantia de velocidade ou disponibilidade; consultar as condições atuais do serviço.",
      limitations = "Cada item possui seu próprio manifesto de arquivos; nem todo resultado contém ISO, XISO ou ROM. A disponibilidade pode mudar.",
      recommendation = 1,
    ),
  )

  fun findForUrl(url: String): StoreProviderProfile? =
    providers.firstOrNull { it.matches(url) }

  fun orderedForStore(): List<StoreProviderProfile> =
    providers.sortedBy { it.recommendation }
}
