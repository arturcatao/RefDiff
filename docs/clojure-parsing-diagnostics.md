# Diagnóstico de parsing — 2026-11-14

Base: 7330a2aefed454b93df4a5fabe2c548363f5eb2d, incluindo a correção de metadados.

Amostra real: cjfrisz/clojure-tco, commits d25af473218bfa383dd3a43b164b298d16a7a5dd e bf7a031c1e616718256009c2a19033c4ccdb89d8. Ambos falham ao ler src/clojure_tco/cps.clj da revisão bf7a031. A fonte termina dentro de `(defn T [expr] (match ...`, com essas formas sem fechamento. A causa do rewrite-clj é `Unexpected EOF. [at line 55, column 39]`. São duas comparações afetadas pelo mesmo arquivo histórico incompleto; não evidenciam uma construção válida sem suporte.

O runner antes retornava somente wrappers até `Could not parse Clojure source`. Agora mantém os campos existentes e acrescenta `causes`, lista ordenada da exceção externa à causa interna, com `exception_class` e `message`. Em falhas inesperadas também escreve o stack trace em stderr. A travessia usa identidade para evitar ciclos. O status continua `refdiff_failed`, exit code 1: melhorar o diagnóstico não transforma uma análise falha em análise concluída. Não houve mudança de sintaxe aceita, avaliação de código, reparação automática da fonte ou alteração do núcleo compartilhado.

## Validação observada

Compilação nova de núcleo/plugin/testes com `javac -proc:none -encoding UTF-8`, OpenJDK 25.0.2, rewrite-clj 1.3.59 e JUnit 4.11 do cache local. Classes em diretório isolado. Execução com `java -cp <classpath> org.junit.runner.JUnitCore <classes>`, a partir do diretório de cada módulo:

- Plugin: 52 testes aprovados, incluindo duas novas regressões de diagnóstico.
- Núcleo: 15 testes aprovados.
- Pipeline: `.venv/bin/python -m unittest tests.test_refdiff_and_pipeline -q`, quatro aprovados.
- Runner JSON normal nos dois commits reais: exit 1, JSON válido com cadeia completa até o EOF e stack trace em stderr, conforme esperado.

Os testes novos cobrem preservação dos campos antigos, cadeia de parsing, mensagem nula e ciclo de causas. A regressão existente de reader conditional inválido continua passando. Gradle e a suíte Python completa não foram executados.

Evidências no workspace da pipeline: `work/parsing-validation/diagnosis.log`, JSON/stderr nomeados pelos dois SHAs, `refdiff-clojure.log`, `refdiff-core.log`, e `before/build.json` / `after/build.json` com comandos completos e hashes das classes. O clone de reprodução é `work/parsing-clojure-tco.git`; nenhuma mineração ou execução de ARIT foi necessária.

## Limite da conclusão

O resumo da VM agrupa 104 falhas como parsing e fornece apenas um exemplo. A segunda comparação foi identificada nos manifestos históricos disponíveis. Não se demonstrou que todas as 104 falhas sejam fontes inválidas. Para continuar a classificação são necessários os identificadores e logs/JSON dos demais casos v03; a nova saída do runner permitirá distinguir causas em reproduções seletivas. Não houve reprocessamento do lote, alteração de deps.lock.json ou investigação dos objetos Git ausentes nesta etapa.
