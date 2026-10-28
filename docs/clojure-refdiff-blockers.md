# Bloqueios para automatizar avaliação do RefDiff Clojure

**Objetivo deste documento.** Listar, com exemplos concretos, os principais bloqueios encontrados no plugin `refdiff-clojure` para quem pretende criar um script Python que rode automaticamente o miner/ARIT e o RefDiff. O foco é separar:

- bloqueios que impedem **rodar em lote**;
- bloqueios que produzem **resultado enganoso**;
- bloqueios que impedem **interpretar o resultado academicamente**.

**Contexto.** Análise baseada no estado do checkout em 28/10/2026, com `HEAD` em `37584d2 feat: adiciona runner de commits Clojure`. A suíte própria do módulo Clojure tem 33 testes aprovados; o fix do NPE de tokens sintéticos já está incluído no `HEAD`. Execuções manuais já foram feitas em `e5f2e29` e `fd86d23`, mas sem gabarito/oráculo.

> Assumindo neste documento que “miner” significa a ferramenta de detecção de code smells (ARIT) usada na pipeline de pesquisa.

---

## 1. Tabela-resumo dos bloqueios

| # | Bloqueio | O que significa | Exemplo concreto | Efeito em um script Python | O que fazer |
|---|---|---|---|---|---|
| **2** | Localização ARIT ≠ localização RefDiff | `Location` guarda arquivo, offsets de caractere e linha inicial, mas não coluna nem linha final (`refdiff-core/src/main/java/refdiff/core/cst/Location.java:5-21`). | O ARIT reporta “smell na linha 42, colunas 10–20”; o RefDiff reporta “função começa na linha 40, offsets 1200–1800”. | A ocorrência do ARIT pode não cair em nenhuma entidade ou cair na entidade errada. | Criar adaptador ARIT↔RefDiff e testar limites, CRLF, Unicode e convenção de intervalo. |
| **2b** | Multi-aridade compartilha intervalo | Cada aridade vira uma entidade, mas todas usam o intervalo externo do `defn` (`ClojureDeclarationExtractor.java:240-243, 289-303`). | `(defn f ([x] …) ([x y] …))`: a ocorrência na segunda aridade está contido nas duas entidades. | O script escolhe uma aridade arbitrariamente ou perde a ambiguidade. | Desempatar pelo intervalo do corpo ou registrar explicitamente “candidata ambígua”. |
| **3** | Erros de commit/parsing abortam a execução | Commits sem exatamente um pai falham (`GitHelper.java:162-164`), incluindo merge e commit raiz. Um erro de parsing em um arquivo aborta o parse do conjunto inteiro (`ClojurePlugin.java:49-51`). | Um merge ou um `.clj` com forma não suportada gera exceção no meio do lote. | Se o script olhar só a contagem de refatorações, pode registrar zero e concluir “não houve correção”. | Validar o pai antes; capturar exceção por commit; classificar `merge`, `raiz`, `erro_de_parse`; continuar o lote. |
| **4** | Suíte completa do core falha por descoberta de classes auxiliares | `:refdiff-core:test` descobre classes internas de `CstDiffMatchers` como testes. Elas não têm `@Test`, então o JUnit falha na inicialização. | 24 casos contados, 15 passando e 9 `initializationError`; não são falhas de lógica do RefDiff. | O Gradle termina com erro; CI/script que usa exit code pensa que o RefDiff está quebrado. | Excluir essas classes da descoberta, movê-las para fora do source de teste ou rodar com filtro explícito. |
| **5** | Sem oráculo para commits reais | Os testes Clojure são sintéticos; as execuções reais só mostram quantidades, sem rótulo de verdadeiro/falso. | `fd86d23` retorna 2 movimentos e `e5f2e29` retorna 6 refatorações, mas não se sabe se estão certos sem inspeção manual. | O script gera números, mas não precision/recall/F1. | Anotar manualmente uma amostra pequena; comparar com o resultado; calcular métricas depois. |
| **6** | Relações `USE` são aproximadas | Extract/inline dependem de “quem chama quem”. O plugin infere chamadas por sintaxe, de forma conservadora. | `(apply f args)` conta 2 argumentos, não a aridade real de `f`; `letfn`, `if-let`, `:refer` e `:rename` não são tratados de forma geral (`ClojureDeclarationExtractor.java:385-388`; `ClojureUseResolver.java:49-67`). | Extract/inline real pode não aparecer; macro ou valor funcional pode virar chamada falsa. | Adicionar localização de call-site, testar casos específicos e marcar baixa confiança; revisar extract/inline manualmente no piloto. |
| **7** | Entidades limitadas a `defn`/`defn-` | Só `defn` e `defn-` viram nós comparáveis (`ClojureDeclarationExtractor.java:78-80`). | `defmacro`, `def`, `defmulti`, `defmethod`, protocolos, records, types e funções locais em `letfn` não têm entidade. | Se a correção ocorrer nesses casos, o RefDiff vê “nenhuma refatoração”, mas isso não significa “nenhuma correção”. | Registrar “entidade não suportada”; expandir o parser só para formas que aparecerem na amostra real. |

---

## 2. Explicação detalhada

### 2.1 Localização: por que é um problema?

O pipeline previsto é:

1. o ARIT reporta uma ocorrência de smell com arquivo e localização;
2. o RefDiff extrai entidades do commit pai;
3. a ocorrência precisa ser associada a uma entidade candidata;
4. o RefDiff ajuda a rastrear a entidade no commit posterior;
5. o ARIT é executado de novo no código pós-mudança.

O passo 3 depende de comparar localizações. Hoje o RefDiff fornece:

- `file`;
- `begin` e `end` como offsets de caractere Java;
- `line` como linha inicial;
- `bodyBegin` e `bodyEnd` como offsets do corpo.

Não há coluna nem linha final. Além disso, offsets Java são em unidades UTF-16, o que pode divergir de ferramentas que usam bytes ou code points.

**Caso multi-aridade.** Para:

```clojure
(defn f
  ([x] (inc x))
  ([x y] (+ x y)))
```

o plugin cria duas entidades (`f/1` e `f/2`), mas ambas herdam o mesmo intervalo externo do `defn`. Um smell na segunda aridade está contido nos dois intervalos. A associação automática precisa de um critério de desempate — por exemplo, o intervalo do corpo — ou deve registrar ambiguidade.

### 2.2 Erros que abortam a execução

O `RunClojureCommit` chama `computeDiffForCommit`, que exige exatamente um pai. Portanto:

- commit de merge: falha;
- commit raiz (zero pais): falha;
- erro de parsing em qualquer arquivo `.clj`: falha o parse do conjunto inteiro.

Para um script em lote isso é crítico: uma exceção não capturada pode ser confundida com “zero refatorações”. A classificação correta é:

- `sem_pai_unico`;
- `erro_de_parse`;
- `repo_clone_falhou`;
- `ok`.

A presença ou ausência de refatoração só deve ser avaliada quando o status é `ok`.

### 2.3 Falha de descoberta da suíte do core

A classe `refdiff.test.util.CstDiffMatchers` é um utilitário de testes, não um teste. Ela contém classes internas públicas (`NodeQuery`, `RelationshipQuery`, etc.) e o JUnit 4 acaba tentando inicializá-las como suítes.

Isso produz 9 falhas de inicialização, por exemplo:

- `No runnable methods`;
- `Test class should have exactly one public zero-argument constructor`.

Os 15 testes reais do core passam. A falha é de configuração/descoberta de testes, não do algoritmo. Ainda assim, precisa ser corrigida ou contornada antes de automação/CI, porque o exit code do Gradle fica vermelho.

### 2.4 Oráculo ausente

“Oráculo” significa saber qual é a resposta certa para cada commit.

Hoje:

- os 33 testes Clojure usam fixtures sintéticas com respostas esperadas;
- as execuções em `e5f2e29` e `fd86d23` são reais, mas manuais e sem rótulo;
- não há conjunto de commits com “aqui houve extração verdadeira”, “aqui houve renomeação” etc.

Sem isso, o script pode até rodar, mas não produz métricas confiáveis de precisão ou recall. A comparação correta é:

```text
resultado RefDiff  ×  rótulo manual
```

e não apenas:

```text
quantidade de refatorações reportadas
```

### 2.5 Relações `USE`

Extract e inline do comparador compartilhado usam relações `USE`:

- extract: procura quem passa a chamar a função nova (`CstComparator.java:420`);
- inline: procura quem deixou de chamar a função removida (`CstComparator.java:461`).

O plugin infere essas chamadas por leitura sintática. A resolução é conservadora: só cria a relação quando há um candidato único por namespace/alias/aridade. Isso reduz falsos positivos, mas também pode criar falsos negativos.

Exemplos de limitações:

- `(apply f args)`: a aridade contada é sintática, não semântica;
- `letfn`, `if-let`, `when-let`, `for`, `doseq`: não têm handlers de escopo equivalentes a `let`/`fn`;
- `:refer` e `:rename` no `:require` não são resolvidos de forma geral;
- macros não listadas podem parecer chamadas.

Na prática, se um extract/inline não for detectado, o script não pode concluir que não houve extração; apenas que o RefDiff não encontrou evidência suficiente.

### 2.6 Cobertura de entidades

O plugin extrai apenas `defn` e `defn-`. Isso significa que mudanças nas seguintes formas não têm entidade rastreável:

- `defmacro`;
- `def`;
- `defmulti` e `defmethod`;
- protocolos, records e types;
- funções locais dentro de `letfn` ou outras formas aninhadas.

Se o ARIT reportar um smell em uma dessas formas e ele for corrigido, o RefDiff não terá entidade correspondente. O resultado “nenhuma refatoração detectada” é, nesse caso, apenas ausência de evidência — não ausência de correção.

---

## 3. Prioridade para o script

| Prioridade | Bloqueios | Por quê |
|---|---|---|
| **P0 — antes de confiar nos números** | 2/2b, 5 | Sem localização compatível e sem oráculo, o resultado não é interpretável. |
| **P0 — antes de rodar em lote/CI** | 3, 4 | Sem isso, o lote quebra ou retorna status enganoso. |
| **P1 — antes de medir extract/inline** | 6 | Extract/inline dependem de `USE` e hoje são a parte menos confiável. |
| **P1 — antes de generalizar a avaliação** | 7 | Sem entidades para macros/multimétodos etc., parte das correções fica invisível. |

---

## 4. Como o script Python deve tratar esses bloqueios

Um script inicial pode ser útil **como coletor de evidências**, desde que registre:

1. status do commit (`ok`, `merge`, `erro_de_parse`, `clone_falhou`);
2. commit e pai efetivamente comparados;
3. saída bruta do miner/ARIT antes e depois;
4. saída bruta do RefDiff;
5. localização do smell e entidades candidatas do commit pai;
6. ambiguidades de associação (por exemplo, multi-aridade);
7. rótulo manual, quando houver.

O script **não deve**:

- tratar “zero refatorações” como “não houve correção”;
- inferir precisão a partir da quantidade de relações reportadas;
- fazer parsing frágil da saída textual do runner sem um contrato estável;
- escolher silenciosamente uma entidade quando há múltiplas candidatas.

Para um script científico, a ordem recomendada é:

1. corrigir ou contornar os bloqueios 3 e 4;
2. criar saída estruturada (JSON) do RefDiff;
3. criar o adaptador de localização 2/2b;
4. montar uma amostra pequena de commits com oráculo manual 5;
5. medir erros e só então decidir se vale a pena expandir entidades 7 e `USE` 6.

---

## 5. Referências principais

- `refdiff-clojure/src/main/java/refdiff/cli/RunClojureCommit.java`
- `refdiff-clojure/src/main/java/refdiff/parsers/clojure/ClojurePlugin.java`
- `refdiff-clojure/src/main/java/refdiff/parsers/clojure/ClojureDeclarationExtractor.java`
- `refdiff-clojure/src/main/java/refdiff/parsers/clojure/ClojureUseResolver.java`
- `refdiff-core/src/main/java/refdiff/core/RefDiff.java`
- `refdiff-core/src/main/java/refdiff/core/io/GitHelper.java`
- `refdiff-core/src/main/java/refdiff/core/cst/Location.java`
- `refdiff-core/src/main/java/refdiff/core/diff/CstComparator.java`
- `refdiff-core/src/test/java/refdiff/test/util/CstDiffMatchers.java`
