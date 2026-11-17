package com.rfandango.haku_x.ui.store

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rfandango.haku_x.data.store.ArchiveXboxDownloads
import com.rfandango.haku_x.data.store.RomulationXboxGame
import com.rfandango.haku_x.data.store.RomulationXboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RomulationXboxStoreScreen() {
 val context=LocalContext.current;val scope=rememberCoroutineScope()
 var query by remember{mutableStateOf("")};var games by remember{mutableStateOf<List<RomulationXboxGame>>(emptyList())}
 var page by remember{mutableIntStateOf(1)};var pages by remember{mutableIntStateOf(1)}
 var loading by remember{mutableStateOf(false)};var busy by remember{mutableStateOf<String?>(null)}
 var message by remember{mutableStateOf("Carregando catálogo Xbox da RomUlation…")}
 fun load(reset:Boolean){
  if(loading)return
  scope.launch{
   loading=true
   try{
    val p=if(reset)1 else page+1
    val result=withContext(Dispatchers.IO){RomulationXboxRepository.search(query,p)}
    games=if(reset)result.games else (games+result.games).distinctBy{it.id}
    page=result.page;pages=result.totalPages
    message="${games.size} jogos carregados · página ${page}/${pages}"
   }catch(e:Exception){message=e.message?:"Falha ao consultar RomUlation."}
   finally{loading=false}
  }
 }
 LaunchedEffect(Unit){load(true)}
 Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("RomUlation · Xbox",color=Color.White,style=MaterialTheme.typography.titleLarge)
  Text("Catálogo integrado · downloads na fila interna HakuX",color=Color(0xFFB9C5BC),style=MaterialTheme.typography.labelSmall)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   OutlinedTextField(query,{query=it},Modifier.weight(1f),singleLine=true,label={Text("Pesquisar jogos")})
   Button(onClick={load(true)},enabled=!loading&&(query.isBlank()||query.trim().length>=2)){Text("Buscar")}
  }
  Text(message,color=Color(0xFFBFE8C5),style=MaterialTheme.typography.bodySmall)
  if(loading&&games.isEmpty()) CircularProgressIndicator()
  else LazyVerticalGrid(GridCells.Fixed(2),Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   items(games,key={it.id}){game->
    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF0B120E)),border=BorderStroke(1.dp,Color(0xFF31543A))){
     Column(Modifier.padding(9.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
      Text(game.title,color=Color.White,style=MaterialTheme.typography.titleSmall,minLines=2)
      Text("${game.region} · ${game.sizeLabel}",color=Color(0xFFB9C5BC),style=MaterialTheme.typography.labelSmall)
      Button(onClick={
       if(busy!=null)return@Button
       scope.launch{
        busy=game.id;message="Obtendo link oficial para ${game.title}…"
        try{
         val download=withContext(Dispatchers.IO){RomulationXboxRepository.resolveDownload(game)}
         ArchiveXboxDownloads.enqueueDirect(context,game.title,download.url,download.fileName,download.bytes,download.requestHeaders,download.detailsUrl)
         message="Download adicionado à fila interna do HakuX."
        }catch(e:Exception){message=e.message?:"Não foi possível iniciar o download."}
        finally{busy=null}
       }
      },enabled=busy==null,modifier=Modifier.fillMaxWidth()){Text(if(busy==game.id)"Preparando…" else "↓ Baixar")}
     }
    }
   }
   if(page<pages)item(span={androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)}){
    OutlinedButton(onClick={ load(false) },enabled=!loading,modifier=Modifier.fillMaxWidth()){Text("Carregar próxima página")}
   }
  }
 }
}