# Auditoria técnica do plugin RefDiff para Clojure

**Escopo e método.** Auditoria estática do estado do checkout em 28/10/2026, leitura dos testes/fixtures e execução das suítes Gradle do módulo Clojure e do núcleo. Nenhum arquivo-fonte foi alterado. Foi criado somente este relatório; o checkout já tinha alterações locais em `.DS_Store`, remoção de `sla.md` e `AGENTS.md` não rastreado, preservadas.

## 1. Resumo executivo

O módulo é um plugin funcional de comparação sintática para uma faixa pequena e explícita de Clojure: cria uma entidade `File` por arquivo, uma entidade `FunctionDeclaration` por aridade de `defn`/`defn-`, tokens posicionados e relações `USE` heurísticas. Ele se integra ao comparador TF-IDF compartilhado e tem fixtures sintéticas para renomeação, movimento, alteração de aridade, extração e inline. A suíte própria executou **33 testes, todos aprovados**.

Isso ainda não demonstra confiabilidade empírica para pesquisa. O plugin não representa a maioria das formas Clojure como entidades, não tem benchmark/precisão/recall próprios, não tem fixtures de commits reais, e os offsets são do `String` Java em unidades UTF-16, sem conversão para o formato do ARIT demonstrada. As chamadas `USE` são inferidas por leitura sintática limitada e alimentam diretamente extract/inline do núcleo; chamadas omitidas ou falsas podem afetar essas relações. A suíte compartilhada do núcleo terminou com **24 casos contados, 15 aprovados e 9 falhas de inicialização do JUnit ao descobrir classes auxiliares do matcher**, não falhas funcionais relatadas por métodos de teste.

**Maturidade observada:** protótipo testado com fixtures sintéticas; ainda não validado para resultados acadêmicos. O caminho de melhor custo-benefício é construir uma avaliação empírica Clojure pequena, anotada manualmente, que inclua associação ARIT–entidade, chamadas e resultados pós-mudança, antes de expandir a gramática ou afrouxar matching.

## 2. Arquitetura atual

| Parte | Evidência e funcionamento |
|---|---|
| Integração | `ClojurePlugin` implementa `LanguagePlugin` (`refdiff-clojure/src/main/java/refdiff/parsers/clojure/ClojurePlugin.java:23-31`). `RefDiff` constrói `CstComparator` com o plugin e usa seu filtro para extrair versões do Git (`refdiff-core/src/main/java/refdiff/core/RefDiff.java:31-34, 69-73`). |
| Extensões | `getAllowedFilesFilter()` inclui `.clj`, `.cljs`, `.cljc` (`ClojurePlugin.java:80-83`). As extensões passam pelo mesmo parser e não há seleção de plataforma para `.cljc`. |
| Dependências | `rewrite-clj:1.3.59` fornece parser/nós com preservação de sintaxe e metadados de posição; `org.clojure:clojure:1.11.4` fornece a API Java para chamar vars Clojure; `tools.reader:1.6.0` é declarada no build. O código consultado invoca diretamente rewrite-clj e a API Clojure; uso direto de `tools.reader` não foi encontrado (`refdiff-clojure/build.gradle:8-14`). |
| Parsing | `ClojureDeclarationExtractor` chama `rewrite-clj.parser/parse-string-all`; percorre filhos semânticos, removendo whitespace, comentários, vírgulas e `#_` da navegação de declarações (`ClojureDeclarationExtractor.java:56-99, 101-131, 490-519`). `RewriteCljTokenizer` percorre a árvore separadamente para criar tokens; não avalia forms nem usa `sexpr` (`RewriteCljTokenizer.java:20-24, 59-73`). |
| CST produzido | Um nó `File` por arquivo, com nós filhos `FunctionDeclaration`; relações `USE` entre declarações. Não se exporta a árvore completa do rewrite-clj ao núcleo (`ClojurePlugin.java:36-67`). |
| Matching | `CstComparator` calcula representações TF-IDF dos tokens, casa nomes/localização/similaridade/filhos, cria relações, e tenta extract/inline (`refdiff-core/src/main/java/refdiff/core/diff/CstComparator.java:132-148`). O plugin define o modelo de entidades e tokens, não algoritmos próprios de refatoração. |
| Runner | `runClojureCommit` recebe repo e commit, clona repositório bare e imprime descrições das relações de refatoração (`refdiff-clojure/src/main/java/refdiff/cli/RunClojureCommit.java:13-55`). |

### Localizações e entidades

`Location` guarda arquivo, offsets `[begin,end)`, linha inicial, offsets do corpo `[bodyBegin,bodyEnd)`; não guarda colunas nem linha final (`refdiff-core/src/main/java/refdiff/core/cst/Location.java:5-21`). `ClojureSourceText` normaliza CRLF, CRFF e CR para LF para interpretar posições do parser, mantendo mapa para offsets originais; também calcula a linha inicial (`ClojureSourceText.java:18-56, 74-117`). `ClojureDeclarationExtractor.addNode` mapeia declaração e corpo para offsets originais (`ClojureDeclarationExtractor.java:289-307`).

Uma `defn`/`defn-` de aridade única vira nó; multi-aridade vira um nó por vetor de parâmetros, com nomes locais `f/N` e `f/N+` para variádica (`ClojureDeclarationExtractor.java:196-245, 261-307`). Os nós de uma declaração multi-aridade compartilham o intervalo da forma externa, mas têm corpos e parâmetros distintos. Destructuring é mantido como texto original no nome do parâmetro, e a contagem de aridade conta bindings, sem `&` (`ClojureDeclarationExtractor.java:309-338`). `ns` é lido para nome e aliases; namespace de função é o símbolo da declaração seguido de ponto, enquanto namespace do nó-arquivo é o diretório do caminho (`ClojureDeclarationExtractor.java:109-114, 134-150`; `ClojurePlugin.java:70-78`).

O tokenizador usa metadados row/col/end-row/end-col do rewrite-clj, converte para offsets e separa tokens posicionados. Comentários viram tokens lexicais; strings são nós folha; regiões internas são divididas em grupos não brancos. Valida limites e sobreposição (`RewriteCljTokenizer.java:75-118, 145-163, 166-233`). Como o comparador compartilhado recupera tokens pelo intervalo da entidade/corpo (`CstRootHelper.java:200-219, 228-253`), esses intervalos definem o material que afeta matching.

## 3. Inventário de entidades e relações

| Elemento | Representação atual | Limites |
|---|---|---|
| Arquivo | `File`, intervalo de todo o arquivo e tokenização | Nome vem do basename; namespace do nó usa diretório, não namespace Clojure. |
| Namespace | Metadado textual aplicado às funções | Não há nó próprio; `ns` não é comparado como declaração independente. |
| Função nomeada | `FunctionDeclaration` para `defn` e `defn-` | Outras formas definidoras (`def`, `defmacro`, `defmulti`, `defmethod`) não são extraídas. |
| Aridade/parâmetros | `localName` inclui aridade; parâmetros viram `Parameter` | Cada aridade é nó separado; assinatura/corpo comparados pelo algoritmo compartilhado. Destructuring fica como texto e bindings têm resolução aproximada. |
| Função local/anônima | Nenhum nó próprio | Formas `fn` são percorridas para chamadas; corpos são atribuídos à entidade externa. |
| `USE` | Relação entre funções extraídas após varredura de chamadas | Uma relação por par caller/callee (não uma por local de chamada); sem localização de call-site; não inclui chamadas para destinos não extraídos. |
| Outras relações CST | Nenhuma relação Clojure própria além de `USE` | `SUBTYPE`/hierarquias não são extraídas para protocols, records ou types. |

Resolução: `ClojureUseResolver` indexa funções por nome. Símbolos qualificados usam prefixo literal ou alias `:as`/`:as-alias`; não qualificados preferem namespace do caller e fazem fallback global somente se a combinação nome/aridade for única (`ClojureUseResolver.java:27-68, 82-133`). Há testes sintéticos para recursão, chamadas qualificadas/alias, ambiguidade global, multi-aridade, variádica, chamada aninhada, shadowing simples de parâmetro/`let`, e chamadas em outro arquivo do mesmo namespace (`ClojurePluginTest.java:229-303`).

Limites observáveis do resolvedor: `argumentCount` é a quantidade de forms sintáticos depois do operador, não a aridade semântica em casos como `apply`; nomes locais são um conjunto, não ambientes léxicos com escopos aninhados; o parser de chamadas especializa apenas `let`/`let*`/`loop`/`loop*` e `fn`/`fn*` (`ClojureDeclarationExtractor.java:340-462`). `:refer`, `:rename`, macros importadas, var-deref, `case` de função, shadowing em outras formas e chamadas geradas por macros não são resolvidos de maneira geral. São limites de evidência sintática/resolução, não do matching TF-IDF.

## 4. Matriz de refatorações suportadas

“Fixture passou” refere-se à suíte sintética executada neste checkout; não equivale a validação em repositório real. Todas as classes de refatoração abaixo são produzidas pelo comparador compartilhado se as entidades e evidências necessárias estiverem disponíveis; a tabela descreve o caminho alcançável para o modelo Clojure atual.

| Categoria | Implementado/alcançável | Teste específico | Repositório real | Limites e classificação |
|---|---|---|---|---|
| Renomeação de função | Sim, `RENAME` por matching compartilhado. Há caminho especial por alta similaridade de corpo no mesmo local (`CstComparator.java:308-359, 383-395`). | Sim: rename simples, multi-aridade; casos negativos de candidato ambíguo, corpos diferentes e corpo trivial (`ClojureRefactoringDetectionTest.java:26-122`). | Não verificado. | Heurística compartilhada exige candidatos compatíveis; rename com corpo e localização muito alterados pode não casar. Multi-aridade coberta apenas preservando as duas aridades. |
| Movimento entre arquivos | Sim, relação `MOVE` alcançável entre nós `FunctionDeclaration`. | Sim: `moveFunctionFile` (`ClojureRefactoringDetectionTest.java:124-139`). | Não verificado. | Depende de similaridade/nome/assinatura e da identidade dos arquivos; fixture sintética simples. |
| Movimento entre namespaces | Sim, namespace da forma `ns` diferencia nós e matching pode emitir `MOVE`. | Sim: `moveFunctionNamespace` (`...ClojureRefactoringDetectionTest.java:141-153`). | Não verificado. | Só namespaces/formas extraídos; mudanças complexas em `ns`/aliases não são avaliadas. |
| Alteração de assinatura/aridade | Sim, `CHANGE_SIGNATURE` alcançável pela mesma localização e nome com assinatura diferente. | Sim: `changeArity` (`...ClojureRefactoringDetectionTest.java:155-166`). | Não verificado. | Casos de múltiplas aridades com adição/remoção seletiva e aridade variádica não estão testados como refatoração. |
| Extract Function | Sim, algoritmo compartilhado consulta `USE` pós-mudança e compara tokens removidos do caller (`CstComparator.java:414-455`). | Sim: fixture de extração (`...ClojureRefactoringDetectionTest.java:168-182`). | Não verificado. | Sem entidade para função local; dependente de chamada detectada e similaridade; movimento de extração só se relações e posição permitirem. |
| Inline Function | Sim, algoritmo compartilhado consulta `USE` pré-mudança (`CstComparator.java:458-484`). | Sim: fixture inline (`...ClojureRefactoringDetectionTest.java:184-198`). | Não verificado. | Exige relação anterior resolvida e caller casado; alias/resolução incompleta prejudica. |
| Movimento interno | O core tem `INTERNAL_MOVE` e `INTERNAL_MOVE_RENAME` (`CstComparator.java:396-408`), mas o plugin não extrai funções aninhadas como nós; para as entidades atuais não há caminho normal para isso. | Não. | Não verificado. | Teoricamente presente no core, não suportado de forma demonstrada pelo modelo Clojure. |
| Extract-and-move / abstrações | Há tipo/fluxo compartilhado genérico; `EXTRACT_MOVE` pode ser emitido no `matchExtract` (`CstComparator.java:440-447`). Não há entidades Clojure de abstração ou hierarquia. | Nenhuma fixture Clojure específica. | Não verificado. | Não declarar suporte a abstrações, protocolos ou types; falta extração de entidades e relações de tipo. |
| Mudanças em chamadas | Tokens do corpo refletem mudanças e `USE` pode alimentar extract/inline | Chamadas têm testes de resolução, mas não de “mudança de chamada” como refatoração identificada. | Não verificado. | Não há entidade de call-site nem refatoração específica de troca de chamada; relações são apenas evidência auxiliar. |
| Alteração do corpo sem assinatura | Sim, a mudança participa da similaridade e pode manter `SAME` ou quebrar match. | Fixtures negativas de rename com corpo diferente/trivial; não teste positivo dedicado de edição de corpo mantendo identidade. | Não verificado. | Resultado é matching de entidade, não categoria explícita de “edição local”. |
| Alterações em namespace/require | Namespace da declaração afeta identidade/contexto; aliases influenciam `USE`. | Teste de alias em chamadas; nenhum teste de detecção de mudança em `ns`/`:require`. | Não verificado. | `ns` não tem nó e não há classificação de mudança de require. |

## 5. Matriz de cobertura sintática da CST

O parser pode aceitar uma forma sem convertê-la em entidade. O AST interno completo do rewrite-clj é transitório; a CST exportada é bem mais estreita.

| Forma/recurso | Classificação da CST | Evidência/efeito provável |
|---|---|---|
| `ns` | Reconhecido parcialmente | Nome e aliases extraídos como metadados; sem entidade `Namespace` (`ClojureDeclarationExtractor.java:134-169`). |
| `defn`, `defn-` | Representado explicitamente | Entidades por aridade, parâmetros, corpo e namespace. |
| `def` | Não tratado como entidade | Apenas aparece em tokens; consta como especial na varredura, evitando classificar a cabeça como chamada (`...ClojureDeclarationExtractor.java:90-94`). |
| `defmacro`, `defmulti`, `defmethod` | Não tratado como entidade | Não constam em `DEFINITION_HEADS`; corpos/dispatch não são separados. |
| Funções multi-aridade | Representado explicitamente, parcialmente | Um nó por aridade; intervalo externo compartilhado. Teste cobre 1, 2 e variádica. |
| Variádica `&` | Reconhecido parcialmente | Aridade codificada como `N+`; `&` não é parâmetro. Sem semântica de `apply` no resolvedor. |
| `fn`, `fn*`, `#()` | `fn` reconhecido parcialmente; `#()` não foi confirmado | `fn` usado apenas para escopo aproximado de chamadas, sem entidade; reader shorthand não tem tratamento dedicado. |
| Função local/aninhada, `letfn` | Não representado como entidade | `letfn*` está na lista de formas especiais, mas não cria nó local nem escopo robusto. |
| Destructuring | Reconhecido parcialmente | Texto bruto guardado como parâmetro e nomes internos coletados recursivamente (`...ClojureDeclarationExtractor.java:309-338, 448-462`). |
| `let`, `loop` | Reconhecido parcialmente na busca de chamadas | Binding tratado em pares, aproximação para escopo; `for`, `doseq`, `if-let`, `when-let`, `binding` não têm handlers equivalentes. |
| `if`, `when`, `cond`, `case`, `try`, `do` etc. | Tratado como tokens; alguns heads reconhecidos como especiais | Formas continuam no corpo/tokenização; apenas cabeças da lista `SPECIAL_FORMS` evitam ser chamadas. Macros como `when`/`cond` não estão nessa lista. |
| Macros/formas especiais | Reconhecido parcialmente | Lista fixa de nomes; macroexpansão não ocorre. Cabeças de macros não listadas podem parecer chamadas e gerar `USE` falso. |
| Calls qualificadas/aliases | Reconhecido parcialmente | Símbolo com `/` e alias extraído de `:require` resolvido; `:refer`, renomeamentos e formas macro não gerais. |
| Recursão | Reconhecido em caso sintético | Teste cobre chamada recursiva direta. |
| Binding/shadowing | Reconhecido parcialmente | Sombreamento por nome em parâmetros e `let`; não lexical nem específico por escopo de forma (`...ClojureDeclarationExtractor.java:391-438`). Pode suprimir `USE` válida ou tratar nome local de forma inconsistente. |
| Protocolos, records, types | Não tratados como entidades/relações | Parser/tokenização podem preservar forms, mas não há extração de tipo/métodos/relações. |
| Comentários/metadados | Reconhecido parcialmente | Comentários ignorados na árvore de declarações e preservados como tokens; metadata é desembrulhada para reconhecer defn, mas pode pertencer ao intervalo da forma. Metadados específicos não modelados. |
| Reader conditionals e `.cljc` | Não verificado semanticamente | Filtro aceita `.cljc`; não há seleção `:clj`/`:cljs`, teste `.cljc` ou prova de posição/extração por branch. |
| `.cljs` | Aceito pelo filtro e parseado pelo mesmo caminho | Um teste só verifica aceitação/arquivo `.cljs`; cobertura de construções específicas não verificada. |
| Literais, regex, quote/syntax quote | Tratados pelo parser e tokens; sem semântica de execução | `quote` e `syntax-quote` são ignorados na busca de chamadas (`...ClojureDeclarationExtractor.java:96-98, 373-375`). Sem teste abrangente de literais/reader forms. |

`ClojureSourceTextTest` cobre LF, CRLF, CRFF, CR isolado, misturas e newline final; `ClojurePluginTest` verifica offsets e substring do corpo para CRLF/CR. Isso valida esses casos em fixtures, mas não valida colunas do ARIT, Unicode fora de BMP (Java offsets contam unidades UTF-16) nem a convenção de intervalo do ARIT. RefDiff usa intervalos exclusivos ao fim na recuperação de tokens; compatibilidade externa permanece não verificada.

## 6. Avaliação dos testes

### Cobertura encontrada

- Parser: extração de `defn`/`defn-`, multi-aridade/variádica, metadata, docstring, destructuring, função vazia, namespace ausente e intervalo do corpo.
- Tokenizador: forms, comentário, offsets de newline e não sobreposição.
- Relações: alias, qualificação, recursão, aridade, variádica, chamada aninhada, ambiguidade global, shadowing simples, mesmo namespace em outro arquivo.
- Refatorações: rename e negativas de rename, move entre arquivos/namespaces, mudança de aridade, extract e inline.
- Lacunas: sem `#()`, `.cljc`, `defmacro`/`defmethod`, locais como entidades, múltiplas candidatas complexas para extract/inline, movimentos concorrentes, falsos positivos reais, regressões documentadas como caso nomeado, teste de integração de commit real, assertivas de desempenho ou métricas.

### Execuções observadas

| Comando | Resultado |
|---|---|
| `./gradlew :refdiff-clojure:test --no-daemon` | **BUILD SUCCESSFUL**. 7 tarefas, 4 executadas/3 up-to-date. XML do Gradle registra 10 `ClojurePluginTest`, 10 `ClojureRefactoringDetectionTest`, 7 `ClojureSourceTextTest`, 6 `RewriteCljTokenizerTest`: total 33, zero falhas/erros. Uma advertência de compilação: enum desconhecido Jackson `JsonInclude.Include` ao compilar testes. |
| `./gradlew :refdiff-core:test --no-daemon` | **BUILD FAILED**. 24 casos contados, 9 falhas de inicialização ao JUnit 4 descobrir classes auxiliares internas de `CstDiffMatchers` como testes (`No runnable methods` ou construtor inválido); 15 testes utilitários/similaridade passaram. É uma falha da suíte compartilhada/configuração de descoberta, não uma falha funcional específica atribuída ao plugin Clojure. |

Não houve execução de toda a matriz de módulos. O problema do núcleo deve ser resolvido/isolado para que uma futura validação integrada não reporte uma suíte verde indevidamente.

## 7. Validação em repositórios reais

Existe infraestrutura executável: `RunClojureCommit` clona um repo bare para cache e chama `RefDiff.computeDiffForCommit`; `RefDiff.computeDiffForCommit` obtém pai e commit do Git com filtro do plugin (`refdiff-core/src/main/java/refdiff/core/RefDiff.java:49-73`). O Javadoc informa que commits com mais de um pai falham; o caminho suporta comparação de duas revisões e não análise isolada. O runner imprime quantidade e descrições, sem serialização estruturada, explicação de ausência de resultado ou associação com localização ARIT.

No estado inspecionado, os testes Clojure comparam diretórios sintéticos `v0`/`v1` (`ClojureRefactoringDetectionTest.java:200-230`). Não encontrei avaliação de commits reais pelo plugin Clojure, anotação manual própria ou cálculo de precisão, revocação/F1 para ele. Dados de avaliação no repositório incluem referências ao projeto Clojure e uma transformação de código Java do compilador Clojure; isso não demonstra validação de código `.clj` pelo plugin atual. Desempenho em repositórios Clojure, robustez a parsing parcial e compatibilidade de versões de Clojure permanecem não verificados.

Riscos operacionais inferidos do código: qualquer erro de parsing aborta o parse do conjunto, com arquivo no erro (`ClojurePlugin.java:42-51`); arquivos grandes exigem tokenizar e fazer parse completo duas vezes (uma em cada classe); cada linha é calculada varrendo o prefixo do source ao criar nó, potencialmente repetido para cada aridade (`ClojureSourceText.java:100-118`). Não foi feito benchmark.

## 8. Problemas conhecidos e riscos

**Reproduzidos no checkout:** a suíte de testes compartilhada falha por descoberta de classes auxiliares; a suíte Clojure passa. Não foram reproduzidos falsos matches em projetos Clojure reais.

**Riscos inferidos do código, ainda sem teste empírico:**

1. Entidades limitadas a `defn`/`defn-` deixam mudanças em vars, macros, multimétodos e funções locais fora do grafo; entidades omitidas também impedem rastrear extract/inline que usem esses destinos.
2. `visitListForCalls` trata todo head list-like não classificado como chamada, percorre argumentos, e pula somente alguns casos especiais. Macros não listadas/operadores funcionais podem virar `USE` falso; `apply` e binding complexo podem resultar em falso negativo ou aridade errada (`ClojureDeclarationExtractor.java:359-389`).
3. Conjunto global de nomes locais aproximado em `let`/`fn` não modela escopos lexicais completos; destructuring adiciona todos os tokens que parecem nomes. Pode suprimir referências reais ou confundir uma variável chamada como uma função.
4. `USE` não inclui posição da chamada e colapsa múltiplas chamadas em uma relação de mesmo par. A ausência de relação não distingue código alterado de chamada não resolvida.
5. Entidades de cada aridade compartilham intervalo total da forma. Isso é útil para localização de ocorrência, mas pode criar candidatas sobrepostas e ambiguidade ao associar smell a aridade. O pipeline precisa registrar múltiplas candidatas, não escolher arbitrariamente.
6. A localização de arquivo usa caminhos/offsets do parser; linha inicial conta newline do original. Colunas e convenção ARIT não são mapeadas, e offsets Java UTF-16 podem divergir de ferramentas que usam bytes/code points.
7. O matching compartilhado usa thresholds/similaridade e decisões de empate genéricas (`CstComparator.java:215-275, 308-359`). As fixtures Clojure são poucas; nenhum valor de precisão/recall permite calibrar erros. Os limiares não devem ser alterados só para aumentar detecções.
8. `.cljc` pode conter duas implementações condicionais; tratar forms de ambas como se coexistissem poderia contaminar entidades/tokens. Esse efeito não foi confirmado com fixture.

## 9. Adequação à pipeline de pesquisa

| Etapa | Adequação atual | Necessidade/risco |
|---|---|---|
| Comparar pai e commit removido | Adequado na API/runner para commit único não-merge | Capturar merge commits como não elegíveis ou escolher explicitamente pai. |
| Filtrar fonte Clojure | Parcialmente adequado | Filtro por extensão simples; validar layout, arquivos gerados e reader conditionals. |
| Associar ocorrência ARIT à entidade original | Parcial | Há arquivo, intervalo e linha inicial da entidade. Falta conversão para formato ARIT e desempate testado; multi-aridade compartilha intervalo. Usar contenção de intervalos compatíveis e registrar ambiguidade. |
| Rastrear entidade posterior | Parcial | Relações SAME/MOVE/RENAME/CHANGE_SIGNATURE e extract/inline do core podem ajudar; dependem do match e `USE`. Não comparar linha anterior com posterior. |
| Detectar extração/movimento | Parcial | Extract/inline sintéticos cobertos; movimento entre arquivos/namespaces coberto. Funções aninhadas, aliases parciais e código com mudanças maiores sem suporte avaliado. Inspecionar diff e código quando não houver relação. |
| Determinar estado posterior do smell | Não coberto pelo RefDiff | Exige executar ARIT pós-mudança, idealmente repo inteiro na validação inicial; RefDiff não decide se smell foi corrigido. |
| Estratégia e resultado separados | Não fornecido pelo plugin | Registrar transformação inferida, persistência ARIT, evidências e confiança em campos separados; revisão manual para casos incertos. |

O plugin fornece dados suficientes para uma primeira tentativa de mapeamento por arquivo e intervalo, não para uma associação conclusiva. A qualidade das colunas/intervalos ARIT é não verificada. Dados adicionais recomendados: SHA/parent selecionado, caminho normalizado, offsets e linhas/colunas em convenção explicitada, todos os nós candidatos, diff textual, chamadas com localizações, relações de matching com score e execução ARIT pós em todo o repo. Ausência de refatoração do RefDiff não deve virar “sem correção”; pode ser edição local, exclusão, mudança em entidade não modelada ou limitação do matching.

## 10. Plano de evolução priorizado

As prioridades abaixo são recomendações derivadas da evidência, não alterações feitas.

| Prioridade | Problema e evidência | Consequência | Mudança proposta e teste demonstrativo | Dependência/dificuldade |
|---|---|---|---|---|
| **P0** | Sem avaliação empírica Clojure; só fixtures sintéticas e runner sem métricas | Não há estimativa de erro para resultados acadêmicos | Montar amostra pequena de commits Clojure com mudanças anotadas manualmente; executar pai→commit, comparar precision/recall/F1 por categoria e registrar falsos positivos/negativos. | Dataset/curadoria; média. |
| **P0** | Compatibilidade ARIT↔`Location` não foi verificada; não há colunas e multi-aridades compartilham intervalos | Associação de smell pode cair em entidade errada/ambígua | Teste de integração com ocorrências ARIT conhecidas em vários arquivos, boundaries, UTF-8/UTF-16 e multi-aridade; formalizar convenção de intervalos e saída de ambiguidade. | Acesso a exemplos ARIT; média. |
| **P0** | Núcleo `:refdiff-core:test` falha ao descobrir classes internas do matcher (9 inicializações) | Validação compartilhada fica vermelha e ruído impede regressões confiáveis | Ajustar configuração de descoberta/exclusão das classes auxiliares ou separar utilitário; executar novamente módulo e núcleo. | Gradle/JUnit; baixa/média. |
| **P1** | `USE` sem local de chamada, aridade sintática simples e conjunto local aproximado; testes só cobrem casos selecionados | Extract/inline dependem de evidência incompleta ou incorreta | Adicionar localização do call-site; testes para `apply`, `letfn`, `if-let`, `:refer`, `:rename`, aliases concorrentes, shadowing aninhado e falsos `USE` em macros/valores funcionais. Manter resolução conservadora/ambígua. | Revisão do modelo de call-site; média/alta. |
| **P1** | Funções locais, macros, `defmulti`/`defmethod`, `def` não são entidades | Refatorações relevantes para smells não são rastreáveis | Priorizar formas observadas na amostra empírica; implementar entidades só para formas necessárias e adicionar fixtures positivas/negativas antes de ampliar. | Depende de P0; média/alta. |
| **P1** | Sem validação de localização contra ARIT; posição externa só linha inicial/offset Java | Risco de associações erradas sem sinal visível | Criar adaptador de localização explícito e relatório de candidatos, cobrindo limites, CRLF, Unicode, comentários e metadados; validar em ocorrência real. | Especificação do ARIT; média. |
| **P2** | `.cljc` passa pelo mesmo caminho sem ramo de plataforma ou teste | Entidades podem misturar variantes incompatíveis | Criar fixtures `#?`/`#?@` e decidir se analisar ambas com marca de branch, uma plataforma configurada ou sinalizar ambiguidade. | Definir uso da pesquisa; média. |
| **P2** | Fixtures de mudança de corpo e candidatos concorrentes são limitadas | Mudanças locais podem ser confundidas com rename/move ou desaparecer do matching | Adicionar testes de edição local, funções triviais, candidatos concorrentes, mudanças parciais e score; reportar resultado inconclusivo sem elevar thresholds globais. | Depende de amostra anotada; média. |
| **P3** | Duplo parse/tokenização e `lineNumber` com varredura por nó; sem benchmark | Custo em repos grandes desconhecido | Benchmark em repos Clojure reais, perfil de parser/tokenizador e otimização de line starts se for gargalo observado. | Dados de P0; baixa inicialmente. |
| **P3** | Runner só imprime descrições textuais | Difícil reproduzir e integrar a pipeline ARIT | Oferecer formato estruturado versionado com commit, paths, entidades, intervalos, score/ambiguidade e relações; testar round-trip/schema. | Esquema de pesquisa; média. |

## 11. Conclusão sobre maturidade

O plugin está além de um parser demonstrativo: integra-se ao pipeline RefDiff, extrai funções nomeadas por aridade, calcula posições verificadas em casos de line ending, constrói relações de uso conservadoras em cenários testados, e alcança cinco famílias de resultado do comparador compartilhado em fixtures. A implementação é pequena e explicável.

Para uso acadêmico, sua maturidade é **experimental**. Faltam validação real, mapeamento de localização provado com ARIT, calibração de matching e cobertura de formas Clojure que podem participar de correções de smells. Recomenda-se começar com amostra manual e protocolo de associação/ambiguidade, sem tratar o conjunto de refatorações detectadas como universo completo de correções.

### Respostas diretas

- **O que já faz bem?** Extrai `defn`/`defn-` com aridades, corpos e posições úteis; normaliza line endings; trata aliases e referências simples com fallback conservador; tem testes sintéticos claros de rename/move/signature/extract/inline.
- **Três maiores limitações:** (1) nenhuma validação empírica específica em commits de Clojure; (2) cobertura de entidades sintáticas restrita e resolução de chamadas aproximada, que afeta extract/inline; (3) compatibilidade e desempate ARIT↔intervalos ainda não verificados.
- **O que corrigir/verificar antes de resultados de pesquisa?** Validar associação a ocorrências ARIT reais incluindo ambiguidades; anotar e medir uma amostra de commits; validar falsos `USE`/misses nas construções usadas pela amostra; tratar ausência de refatoração detectada como resultado inconclusivo. Corrigir/filtrar a descoberta problemática do teste compartilhado para manter regressões confiáveis.
- **Próximo passo com melhor custo-benefício:** experimento-piloto anotado manualmente em uma amostra pequena de remoções de smell: verificar localização original ARIT↔entidade, executar o plugin, inspecionar diff, executar ARIT no commit posterior e rotular estratégia e resultado separadamente. Isso mede lacunas antes de ampliar parser ou matching.

## Referências principais

- `refdiff-clojure/build.gradle`
- `refdiff-clojure/src/main/java/refdiff/parsers/clojure/ClojurePlugin.java`
- `refdiff-clojure/src/main/java/refdiff/parsers/clojure/ClojureDeclarationExtractor.java`
- `refdiff-clojure/src/main/java/refdiff/parsers/clojure/ClojureUseResolver.java`
- `refdiff-clojure/src/main/java/refdiff/parsers/clojure/ClojureSourceText.java`
- `refdiff-clojure/src/main/java/refdiff/parsers/clojure/RewriteCljTokenizer.java`
- `refdiff-clojure/src/main/java/refdiff/cli/RunClojureCommit.java`
- `refdiff-clojure/src/test/java/refdiff/parsers/clojure/`
- `refdiff-core/src/main/java/refdiff/core/RefDiff.java`
- `refdiff-core/src/main/java/refdiff/core/diff/CstComparator.java`
- `refdiff-core/src/main/java/refdiff/core/diff/CstRootHelper.java`
- `refdiff-core/src/main/java/refdiff/core/cst/Location.java`
