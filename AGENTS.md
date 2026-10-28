# AGENTS.md — RefDiff / Clojure

## 1. Contexto do projeto

Este repositório contém o RefDiff, uma ferramenta para detectar refatorações comparando versões de código. O trabalho atual inclui o módulo `refdiff-clojure`, que adapta a arquitetura do RefDiff para projetos Clojure.

O objetivo de pesquisa é estudar **como desenvolvedores corrigem ou tratam code smells em projetos Clojure**, e não apenas contar smells removidos. A pipeline pretende combinar:

- **ARIT**: identifica ocorrências de code smells e fornece regra, arquivo e localização no código.
- **Dataset de remoções**: identifica commits nos quais ocorrências de smells deixaram de ser detectadas.
- **RefDiff Clojure**: detecta transformações estruturais entre o commit pai e o commit analisado e fornece entidades e relações que podem ajudar a rastrear o código.
- **Análise pós-mudança**: executa ARIT no código posterior para investigar se o smell persiste na entidade original, em entidades relacionadas ou em outro local.

O RefDiff é uma fonte de evidências sobre mudanças estruturais. **Não presuma que toda remoção de smell corresponde a uma refatoração detectável**, nem que a ausência de uma refatoração detectada significa que nenhuma correção ocorreu.

## 2. Objetivo ao trabalhar neste repositório

Priorize um plugin Clojure correto, testável, explicável e empiricamente avaliável. A meta não é implementar todas as refatorações imagináveis antes de validar as atuais.

Ao investigar ou alterar `refdiff-clojure`:

1. Entenda primeiro a arquitetura compartilhada do RefDiff e os contratos das implementações existentes.
2. Verifique o código e os testes antes de concluir que uma funcionalidade existe ou está ausente.
3. Diferencie capacidades implementadas, capacidades cobertas por testes e capacidades validadas em repositórios reais.
4. Faça mudanças pequenas, justificadas e compatíveis com os outros módulos.
5. Acrescente testes de regressão para cada correção relevante.
6. Informe limitações remanescentes e evidências, sem alegar suporte que não foi demonstrado.

## 3. Regras para alterações

- Não reescreva grandes partes da arquitetura sem evidência concreta de que isso é necessário.
- Não altere o comportamento compartilhado do RefDiff para resolver um problema específico de Clojure sem avaliar os efeitos nos outros plugins.
- Antes de modificar classes compartilhadas, procure testes e implementações de Java, JavaScript e C, e explique o risco de regressão.
- Não aumente limiares globais de similaridade nem enfraqueça critérios de matching apenas para fazer um teste passar.
- Evite heurísticas permissivas que possam gerar falsos positivos. Quando uma heurística for necessária, limite seu escopo, documente sua justificativa e teste casos ambíguos.
- Não execute comandos destrutivos, não descarte alterações do usuário e não reverta mudanças preexistentes.
- Não faça commits, push ou alterações fora do escopo solicitado, a menos que o usuário peça explicitamente.
- Preserve as convenções de código e a estrutura de build já adotadas pelo repositório.
- Não invente resultados de testes, benchmarks ou experimentos. Se um comando não puder ser executado, informe o motivo.

## 4. Modelo de análise do plugin Clojure

### 4.1 Parsing e representação sintática

Verifique quais formas Clojure são reconhecidas e como são representadas. Não confunda conseguir fazer parse de uma forma com extrair dela uma entidade útil para comparação.

Considere, conforme aplicável:

- `ns`, `defn`, `defn-`, `defmacro`, `def`, `defmulti` e `defmethod`;
- funções com múltiplas aridades e aridade variádica;
- funções anônimas (`fn` e `#()`) e funções locais/aninhadas;
- parâmetros com destructuring;
- `let`, `loop`, formas de controle e bindings locais;
- chamadas qualificadas por namespace, aliases, recursão e shadowing;
- protocolos, records, types e multimétodos;
- comentários, metadados, reader conditionals e arquivos `.clj`, `.cljs` e `.cljc`.

Para cada item, indique se é representado explicitamente, parcialmente reconhecido, tratado como tokens/texto, não tratado ou ainda não verificado. Não amplie o escopo sem demonstrar que a capacidade é relevante.

### 4.2 Entidades, localizações e relações

Verifique:

- quais entidades são extraídas;
- como são identificadas e comparadas;
- como são calculados arquivo, linhas, colunas e intervalos;
- se offsets e posições permanecem corretos diante de CRLF/LF, comentários, strings, metadados e outras construções;
- quais relações `USE` ou equivalentes são criadas e quais limites têm;
- como referências locais, recursivas, entre namespaces e com aliases são resolvidas;
- como as entidades e relações alimentam o matching compartilhado do RefDiff.

Não presuma que os intervalos do RefDiff e do ARIT usam a mesma convenção de linhas/colunas. Se a pipeline depender dessa compatibilidade, verifique-a explicitamente.

### 4.3 Detecção de refatorações

Para cada tipo de refatoração, diferencie:

1. implementado no plugin;
2. coberto por teste específico;
3. validado com código real;
4. parcialmente suportado ou sujeito a limitações;
5. não suportado;
6. não verificado.

Investigue, quando aplicável, renomeação, movimentação entre arquivos ou namespaces, mudança de assinatura/aridade, extração, incorporação (*inline*), movimentação interna e alterações em corpos de funções.

Não conclua que um tipo é suportado apenas porque existe uma enumeração ou implementação genérica no núcleo do RefDiff. A evidência deve incluir o caminho real pelo qual o plugin chega ao resultado.

## 5. Uso na pesquisa de code smells

A pipeline pretendida é:

1. Selecionar do dataset uma ocorrência de smell removida, com repositório, commit, commit pai, regra e localização fornecida pelo ARIT.
2. Executar RefDiff comparando o commit pai com o commit em que o smell deixou de ser detectado.
3. Consultar as entidades extraídas pelo RefDiff no commit pai e associar a ocorrência do ARIT à entidade candidata, usando arquivo e intervalos de localização.
4. Usar os resultados de matching/refatoração do RefDiff para procurar entidades correspondentes ou relacionadas no commit posterior.
5. Inspecionar o diff e o código-fonte quando a transformação não for representada por uma refatoração detectada.
6. Executar ARIT no código posterior para verificar se o smell persiste na entidade original, em entidades de destino ou em outro local. Durante a validação inicial, considerar uma execução no repositório inteiro para medir se a busca localizada deixa escapar ocorrências.
7. Registrar evidências, estratégia inferida, resultado pós-mudança e confiança; encaminhar casos ambíguos para revisão manual.

### Requisitos para a associação ARIT ↔ RefDiff

- A localização do smell vem do ARIT; não tente inferi-la apenas a partir dos resultados do RefDiff.
- A associação com uma entidade ocorre **depois de o RefDiff extrair as entidades do commit pai**.
- Comece com arquivo e contenção do intervalo do smell pelo intervalo da entidade, se as convenções de localização forem compatíveis.
- Se houver várias entidades candidatas (por exemplo, funções aninhadas ou múltiplas aridades), não escolha silenciosamente uma correspondência arbitrária. Defina e teste um critério de desempate, ou registre ambiguidade.
- Mantenha separadas a associação à entidade original e a busca por entidades correspondentes no commit posterior.
- Não compare diretamente as linhas antigas com as novas: a edição pode deslocar as localizações.
- Uma refatoração detectada no mesmo commit não prova, por si só, que ela causou a remoção do smell.
- Ausência de resultado do RefDiff não é evidência suficiente de ausência de correção; mudanças locais no corpo podem não ser classificadas como refatoração.

### Separe estratégia e resultado

Não misture a transformação do código com o estado final do smell.

Exemplos provisórios de **estratégia**: extração, movimentação, renomeação, mudança de assinatura, edição local, exclusão do código ou desconhecida.

Exemplos provisórios de **resultado**: smell não detectado no código relacionado, smell persiste em código relacionado, código transformado com resultado inconclusivo, código sem destino rastreável ou indeterminado.

Essas categorias são hipóteses iniciais para avaliação e podem ser refinadas após inspeção manual de uma amostra. Não classifique automaticamente casos incertos como correções confirmadas.

## 6. Testes e validação

Ao fazer uma alteração:

- Adicione ou atualize testes pequenos e determinísticos para o comportamento esperado.
- Inclua casos positivos e negativos, além de cenários ambíguos quando relevantes.
- Para renomeações e matching, teste corpos idênticos, corpos diferentes, funções triviais, múltiplas aridades e candidatos concorrentes.
- Para localização, teste limites de intervalo, arquivos diferentes e convenções de linha/coluna.
- Para resolução de referências, teste aliases, nomes qualificados, recursão e shadowing, quando aplicável.
- Execute primeiro os testes específicos do módulo alterado e depois os testes compartilhados relevantes.
- Registre comandos e resultados observados, distinguindo falhas novas de falhas preexistentes.
- Quando possível, valide em commits reais com inspeção manual de uma amostra. Fixtures sintéticas não substituem avaliação empírica.

Não reporte apenas “testes passaram”: diga quais testes foram executados, quantos passaram/falharam e quais limitações ficaram sem cobertura.

## 7. Processo de trabalho esperado

Para tarefas de auditoria ou planejamento:

1. Inspecione os arquivos e o histórico/testes relevantes.
2. Produza um inventário baseado em evidências.
3. Identifique riscos e lacunas, priorizados por impacto na pesquisa.
4. Recomende a próxima mudança pequena com melhor custo-benefício.
5. Não altere o código, a menos que a tarefa solicite implementação.

Para tarefas de implementação:

1. Resuma o comportamento atual e o problema observado.
2. Apresente uma proposta mínima e os arquivos que pretende alterar.
3. Implemente apenas o escopo solicitado.
4. Adicione testes de regressão.
5. Execute os testes pertinentes.
6. Resuma arquivos alterados, evidências, resultados e limitações restantes.

Se a tarefa pedir somente análise, auditoria ou plano, **não implemente as recomendações**.

## 8. Formato de relatórios técnicos

Quando solicitado a auditar ou avaliar o plugin, produza um relatório Markdown com:

- resumo executivo;
- arquitetura e arquivos/métodos responsáveis;
- matriz de capacidades e refatorações;
- limitações da representação sintática;
- cobertura e resultados de testes;
- evidências de validação em projetos reais;
- riscos para a pipeline de pesquisa;
- plano priorizado de melhorias.

Sempre que possível, cite caminhos, classes, métodos, testes e linhas de código. Marque como “não verificado” tudo que não puder ser confirmado. Separe fatos observados, inferências e recomendações.

## 9. Princípio geral

O critério de sucesso não é maximizar a quantidade de refatorações reportadas. É produzir resultados rastreáveis, reproduzíveis e suficientemente precisos para apoiar uma análise acadêmica, deixando claras as limitações e os casos que exigem revisão manual.
