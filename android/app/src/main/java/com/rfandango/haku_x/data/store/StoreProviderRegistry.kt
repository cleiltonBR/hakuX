package com.rfandango.haku_x.data.store

/**
 * Hosting providers supported by the HakuX store's generic HTTPS mirror downloader.
 *
 * These are hosting platforms, not pre-populated game servers. A catalog maintainer
 * must publish an authorized file and put its public HTTPS URL in the game manifest.
 * The app probes each actual URL before presenting availability or speed information.
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
   * Ordering is a deployment preference based on published transfer policy, not a
   * measured speed ranking. Real speed depends on geography, routing and load.
   */
  val providers: List<StoreProviderProfile> = listOf(
    StoreProviderProfile(
      id = "cloudflare-r2",
      displayName = "Cloudflare R2",
      hosts = setOf("r2.dev"),
      supportsPublicFiles = true,
      supportsHttpRange = true,
      freeEgressPolicy = "Transferência de saída sem cobrança; camada Standard inclui 10 GB-mês grátis.",
      limitations = "Para produção, usar domínio personalizado. O endereço r2.dev é limitado e indicado para desenvolvimento.",
      recommendation = 1,
    ),
    StoreProviderProfile(
      id = "backblaze-b2",
      displayName = "Backblaze B2",
      hosts = setOf("backblazeb2.com"),
      supportsPublicFiles = true,
      supportsHttpRange = true,
      freeEgressPolicy = "Até 3 vezes o armazenamento médio mensal em downloads sem custo; excedentes podem ser cobrados.",
      limitations = "Buckets públicos continuam contabilizando downloads na conta do proprietário; excedentes e políticas da conta devem ser monitorados.",
      recommendation = 2,
    ),
    StoreProviderProfile(
      id = "github-releases",
      displayName = "GitHub Releases",
      hosts = setOf("github.com", "githubusercontent.com"),
      supportsPublicFiles = true,
      supportsHttpRange = true,
      freeEgressPolicy = "Sem quota publicada de largura de banda para assets de Releases.",
      limitations = "Cada asset precisa ter menos de 2 GiB; adequado para homebrew, demos e arquivos divididos em partes.",
      recommendation = 3,
    ),
  )

  fun findForUrl(url: String): StoreProviderProfile? =
    providers.firstOrNull { it.matches(url) }

  fun orderedForStore(): List<StoreProviderProfile> =
    providers.sortedBy { it.recommendation }
}
