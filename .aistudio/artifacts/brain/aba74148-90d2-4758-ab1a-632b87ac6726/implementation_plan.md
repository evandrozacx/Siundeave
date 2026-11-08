# Plano de Implementação: Tela Descobrir como Inicial, Reprodução por Gênero no YouTube com Fila Infinita e Reprodução por Pastas

## 1. Descobrir como Tela Inicial Padrão
- [x] Aba `discoverTab` definida como ativa por padrão no HTML (`<section id="discoverTab" class="tab-pane active">`).
- [x] Botão `navDiscoverBtn` definido como ativo na barra de navegação inferior (`<button class="bottom-nav-item active" data-tab="discoverTab" id="navDiscoverBtn">`).
- [x] Carregamento imediato dos lançamentos e novidades do YouTube na inicialização (`DOMContentLoaded`).

## 2. Reprodução Streaming do YouTube: Sequência Estrita por Gênero & Fila Infinita
- [x] Detecção inteligente de gênero musical (`detectTrackGenre`) para Gospel, Pagode, Sertanejo, Funk, Rock, Rap, Eletrônica, Pop, Internacional e Brasil.
- [x] Quando o player toca um vídeo de streaming do YouTube, a fila de reprodução é estritamente isolada para faixas daquele gênero (ex: Gospel toca apenas sequência Gospel; Pagode toca apenas Pagode).
- [x] **Fila Infinita e Contínua**: O tocador não para em uma música só. Sempre que se aproxima do fim da fila ou uma música termina (`yt_ended`), busca automaticamente mais faixas e clipes oficiais daquele gênero direto do YouTube (`fetchMoreYouTubeGenreTracks`), mantendo a reprodução contínua e ininterrupta.

## 3. Arquivos Locais / Pastas Internas Baixadas
- [x] **Modo Sequencial (Padrão / Sem Aleatório)**: Toca estritamente a sequência da pasta selecionada (`folder`), sem misturar com pastas de outros artistas ou álbuns.
- [x] **Modo Aleatório Ativado (`isShuffle`)**: Junta todas as pastas e faixas da biblioteca interna e toca totalmente aleatório (`allLocalTracks`).

## 4. Testes e Validação
- [x] Testes unitários atualizados em `ExampleUnitTest.kt` e aprovados com sucesso (`testDebugUnitTest`).
- [x] Compilação do applet concluída com êxito (`compile_applet`).
