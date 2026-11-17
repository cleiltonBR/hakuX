package com.rfandango.haku_x.data.store

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RomulationXboxGame(val id:String,val title:String,val region:String,val sizeLabel:String,val detailsUrl:String)
data class RomulationXboxPage(val games:List<RomulationXboxGame>,val page:Int,val totalPages:Int)
data class RomulationDownload(val url:String,val fileName:String,val bytes:Long)

object RomulationXboxRepository {
 private const val BASE="https://www.romulation.org"
 fun search(query:String,page:Int=1):RomulationXboxPage {
  require(page>0)
  val q=query.trim()
  require(q.isEmpty() || q.length>=2) { "Digite ao menos 2 caracteres." }
  val suffix=if(q.isBlank()) "?page=$page" else "?search="+URLEncoder.encode(q,"UTF-8")+"&page=$page"
  val (html,finalUrl)=get("$BASE/roms/XBOX$suffix")
  val games=parseCatalog(html,finalUrl)
  val pages=Regex("""(?i)href=["'][^"']*[?&]page=(\d+)["']""").findAll(html).mapNotNull{it.groupValues[1].toIntOrNull()}.maxOrNull()?:page
  return RomulationXboxPage(games,page,maxOf(page,pages))
 }
 fun resolveDownload(game:RomulationXboxGame):RomulationDownload {
  val (html,finalUrl)=get(game.detailsUrl)
  val text=clean(html)
  require(Regex("(?i)Can Download\\s+Yes").containsMatchIn(text)) { "A RomUlation não indica este jogo como disponível." }
  val fileName=Regex("""(?i)Full Name\s+([^|]+?\.(?:7z|zip|rar|iso|xiso|cso|cci))""").find(text)?.groupValues?.get(1)?.trim() ?: error("Nome do arquivo não informado.")
  val sizeMatch=Regex("""(?i)Filesize\s+([0-9.]+)\s*(GB|MB|KB)""").find(text)
  val bytes=sizeMatch?.let { m -> (m.groupValues[1].toDoubleOrNull() ?: 0.0).let { n -> (n * when(m.groupValues[2].uppercase()) { "GB" -> 1073741824.0; "MB" -> 1048576.0; "KB" -> 1024.0; else -> 1.0 }).toLong() } } ?: 0L
  val link=Regex("""(?is)<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""").findAll(html)
   .firstOrNull{clean(it.groupValues[2]).contains("Download",true)}?.groupValues?.get(1)
   ?: error("A página não forneceu um link oficial de download.")
  val resolved=URL(URL(finalUrl),decode(link))
  require(resolved.protocol=="https" && resolved.host.equals("www.romulation.org",true)) { "Destino de download inválido." }
  return RomulationDownload(resolved.toString(),fileName,bytes)
 }
 private fun get(address:String):Pair<String,String>{
  val u=URL(address); require(u.protocol=="https" && u.host=="www.romulation.org")
  val c=u.openConnection() as HttpURLConnection
  c.connectTimeout=15000;c.readTimeout=30000;c.instanceFollowRedirects=true
  c.setRequestProperty("User-Agent","HakuX-Store/1.0 (Android)")
  c.setRequestProperty("Accept","text/html,application/xhtml+xml")
  try { val status=c.responseCode; require(status in 200..299){"RomUlation respondeu HTTP $status."}
   return c.inputStream.bufferedReader(Charsets.UTF_8).use{it.readText()} to c.url.toString()
  } finally { c.disconnect() }
 }
 internal fun parseCatalog(html:String,baseUrl:String="https://www.romulation.org/roms/XBOX"):List<RomulationXboxGame>{
  val out=linkedMapOf<String,RomulationXboxGame>()
  Regex("(?is)<tr\\b[^>]*>(.*?)</tr>").findAll(html).forEach{ rowMatch ->
   val row=rowMatch.groupValues[1]
   val a=Regex("""(?is)<a\b[^>]*href\s*=\s*["']([^"']*/rom/XBOX/[^"']+)["'][^>]*>(.*?)</a>""").find(row)?:return@forEach
   val href=decode(a.groupValues[1]);val title=clean(a.groupValues[2]);if(title.isBlank())return@forEach
   val txt=clean(row)
   val region=Regex("(?i)\\b(USA|Europe|Japan|World|Australia)\\b").find(txt)?.value?:"Região não informada"
   val size=Regex("(?i)\\b[0-9.]+\\s*(GB|MB|KB)\\b").find(txt)?.value?:"Tamanho não informado"
   val d=URL(URL(baseUrl),href);if(d.protocol!="https"||d.host!="www.romulation.org")return@forEach
   val id=d.path.substringAfterLast('/');out.putIfAbsent(id,RomulationXboxGame(id,title,region,size,d.toString()))
  }
  return out.values.toList()
 }
 private fun clean(s:String)=decode(s.replace(Regex("(?is)<[^>]+>")," ")).replace(Regex("\\s+")," ").trim()
 private fun decode(s:String)=s.replace("&amp;","&",true).replace("&#39;","\u0027",true).replace("&lt;","<",true).replace("&gt;",">",true).replace("&nbsp;"," ",true)
}