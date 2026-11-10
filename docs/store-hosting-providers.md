# HakuX Store — fontes de jogos

A loja deve listar fontes que já disponibilizam arquivos de jogos. Serviços genéricos de
armazenamento que exigem que o mantenedor do HakuX envie os jogos não são tratados como
servidores de jogos e não aparecem no catálogo de fontes.

## Fonte integrada

| Fonte | Conteúdo descoberto | Acesso |
| --- | --- | --- |
| Internet Archive | Itens publicados com arquivos ISO, XISO, ROM, CSO, CCI, CHD, BIN/CUE, GDI e pacotes ZIP/7Z/RAR | Pesquisa pública, metadados do item e URL direta por arquivo |

A integração usa a Advanced Search API para localizar itens, a Metadata API para
inspecionar os arquivos realmente presentes e URLs diretas no endpoint de download.
Um resultado só entra no catálogo quando seu manifesto contém pelo menos um arquivo
com extensão reconhecida.

## Comportamento do HakuX

- Pesquisar pelo nome informado pelo usuário, sem carregar uma lista fictícia de jogos.
- Ler os arquivos publicados para cada item e mostrar cada download disponível.
- Exibir tamanho quando fornecido nos metadados.
- Não presumir suporte a HTTP Range: testar a URL real antes de habilitar retomada.
- Medir throughput em uma amostra limitada antes de classificar velocidade.
- Não confundir página do item com o endereço direto do arquivo.

## Próximas fontes

Novas fontes só devem ser adicionadas depois de confirmar que possuem catálogo consultável,
arquivos de jogos reais e links de download acessíveis. R2, B2 e GitHub Releases são
infraestrutura de hospedagem, não catálogos preenchidos, e por isso não são listados aqui.
