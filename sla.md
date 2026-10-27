# Planejamento de escopo: threads no sackOS

**Disciplina:** Sistemas Operacionais  
**Equipe:** [nome 1] e [nome 2] (2 integrantes)  
**Máquina simulada:** ZEPA Machine  
**Branch base:** `sackOS-paginated`  
**Prazo:** fevereiro de 2027  
**Documento:** escopo, design inicial e divisão em entregas

---

## 1. Objetivo e escopo

### 1.1 Objetivo

Adicionar **threads de usuário** ao kernel `sackOS`: um processo passa a ter vários fluxos de
execução compartilhando a mesma memória virtual, com criação, encerramento e espera
(`thread_create`, `thread_exit`, `thread_join`) e com o ciclo de vida do processo ajustado para
múltiplas threads.

Semáforos são uma **extensão condicional**: só entram depois que o núcleo de threads estiver
estável (Seção 5).

### 1.2 Escopo comprometido

| Item | O que será entregue |
|---|---|
| Threads | `thread_create`, `thread_exit`, `thread_join`, `getTID` |
| Memória | threads compartilham a page table do processo; cada uma tem PC, SP e registradores próprios |
| Ciclo de vida | `fork` e `wait` apenas na thread líder; `exit` de qualquer thread encerra o processo; `kill`, `exit` e fault liberam as páginas uma única vez |
| Testes | suíte de integração recuperada e ampliada; programas de exemplo |
| Documentação | ABI, layout de memória e manual atualizados |

### 1.3 Escopo condicional

| Item | Condição |
|---|---|
| Semáforos | `sem_create`, `sem_wait`, `sem_post`, `sem_destroy`, por processo (`tgid`), somente após o checkpoint da Entrega 5 |

Se o checkpoint falhar, os semáforos são cortados e registrados como trabalho futuro. O projeto
continua completo com threads.

### 1.4 Fora do escopo

- Paralelismo real ou SMP; a máquina tem uma CPU.
- Prioridades, tempo real ou escalonamento por prioridade.
- `detach`, múltiplos joiners, cancelamento assíncrono.
- `fork` ou `wait` a partir de thread secundária.
- TLS, sinais, grupos de threads, semáforos de usuário.
- Alocação automática de pilha; o usuário informa o topo da pilha.
- Threads de kernel.

### 1.5 Premissas

- A thread líder é a dona da identidade do processo: `PID = tgid = TID da líder`.
- `getPID` retorna o líder; `getTID` retorna a thread atual.
- `exit` de qualquer thread encerra o processo; `thread_exit` da líder também encerra o processo.
- `thread_exit` de uma thread secundária encerra apenas essa thread.
- `fork` cria um filho com apenas a thread líder; as demais threads não são copiadas.
- Cada thread tem no máximo um joiner.
- A pilha é fornecida pelo usuário e cresce para baixo, com demand paging.
- O escalonamento continua round-robin preemptivo, sem paralelismo.

### 1.6 Limitações conhecidas

- `TID` é o índice do slot e pode ser reutilizado; não há geração de `TID`.
- Os 256 slots do PCB são compartilhados entre processos e threads.
- Não há guard page nas pilhas; o usuário deve fornecer uma pilha válida.
- Semáforos não são compartilhados entre processos nem herdados no `fork`.

---

## 2. Contexto e dificuldade

O kernel atual já tem paginação, MMU, `fork`, `wait`, `exit`, escalonador e uma TUI de depuração.
O `kernel.asm` tem cerca de 2.500 linhas e foi construído por uma equipe de 4–5 pessoas ao longo
de aproximadamente 2,5 meses.

Este projeto **não constrói um kernel novo**: ele modifica um kernel existente, em assembly, com
endereços fixos e um ciclo de vida de processos delicado. O volume de código novo é menor que o
do sackOS-paginated, mas o risco por linha alterada é maior.

| | sackOS-paginated | Este projeto |
|---|---|---|
| Equipe | 4–5 pessoas | 2 pessoas |
| Código | ~2.500 linhas de kernel | ~500–800 linhas adicionadas/alteradas |
| Natureza | construção do zero | modificação invasiva |
| Risco | espalhado | concentrado em ciclo de vida e bloqueio |
| Esforço total | maior | menor sem semáforos; comparável com semáforos |

**Leitura realista:** o núcleo de threads é menor que o sackOS; com semáforos, a dificuldade
prática fica comparável. A curva de aprendizado da dupla no kernel é o principal fator de risco e justifica
começar por uma etapa de estudo e baseline (E0).

---

## 3. Design inicial

### 3.1 Modelo

- Cada **thread** ocupa um slot da tabela de tarefas; o índice do slot é o `TID`.
- Todas as threads de um processo compartilham a **mesma page table**.
- Cada thread tem **contexto próprio**: `PC`, `SP` e registradores.
- O processo é representado pela thread líder; `tgid` é o `TID` da líder.
- Metadados ficam em uma tabela separada de 32 bytes por slot, sem alterar os offsets do PCB.

```
Processo (tgid = TID da líder)
  ├── thread líder  ─┐
  ├── thread 1       ├── compartilham a mesma page table
  └── thread 2      ─┘

pcb_vector[TID]  -> contexto, flags, pages_used
task_meta[TID]   -> kind, tgid, pt_phys, join, live_threads
```

### 3.2 Mapa de memória

| Região | Endereço | Observação |
|---|---|---|
| Globais do kernel | `0x7000`–`0x7024` | movidos de `0x2000`–`0x2024` |
| `pcb_vector` | `0x7024` | 256 slots de `0x300050` bytes |
| Kernel page table | `0x3000C024` | valor baixo `0xC024` |
| Bitmap de frames | `0x3010C024` | valor baixo `0xC024` |
| Metadados de tarefas | `0x3012C024` | valor baixo `0xC024` |
| Tabela de semáforos | `0x3012E024` | valor baixo `0xE024` |

Os valores baixos são construídos com soma:

- `0xC024 = 0x7024 + 0x5000`
- `0xE024 = 0x7024 + 0x7000`

**Por que `0x7000`:** a instrução `MV` usa imediato de 16 bits com sinal, então `0x10000` não é
carregável diretamente. `0x7000` deixa 28 KB para o código e mantém os endereços derivados
construíveis com somas.

### 3.3 Metadados de tarefa (32 bytes)

| Offset | Campo | Uso |
|---|---|---|
| 0 | `kind` | 0 = processo, 1 = thread |
| 4 | `tgid` | TID da thread líder |
| 8 | `pt_phys` | page table compartilhada |
| 12 | `join_target` | thread que esta thread vai aguardar |
| 16 | `join_status_addr` | endereço do status do join |
| 20 | `join_waiter` | thread bloqueada esperando esta thread |
| 24 | `live_threads` | threads vivas do processo (válido no metadado da líder) |
| 28 | `next_waiter` | fila de semáforo |

### 3.4 PCB e estados

Os offsets do PCB **não mudam**. Os campos usados são:

- `flags` (offset 72): bit 0 = mapeada; bits 3–4 = estado; bit 2 = `wait()` de processo.
- `pages_used` (offset 76): páginas do processo; válido no PCB da líder e liberado uma única vez
  no encerramento. Threads secundárias não liberam páginas.
- `TID` = índice do slot.

Estados do escalonador:

| Estado | Valor em `flags` | Significado |
|---|---|---|
| running | `0x00` | executando |
| ready | `0x08` | pronta |
| blocked | `0x10` | bloqueada em join ou semáforo |

### 3.5 Ciclo de vida

```
thread_create --> ready <--> running
                    ^          |
                    |          +-- quantum --> ready
                    |          +-- join/sem_wait --> blocked -- wakeup --> ready
                    |          +-- thread_exit --> dead
```

- **Criar:** alocar slot, inicializar `PC = entry`, `SP = stack_top`, `SR = 56`, `W9 = TID`,
  `flags = 0x09`, metadados com `tgid` e `pt_phys` do líder, e incrementar `live_threads` da
  líder. No `fork`, `live_threads` começa em 1.
- **Sair:** marcar morta, guardar status, acordar o joiner, decrementar `live_threads`; se for a
  líder, encerrar o processo.
- **Join:** se a thread alvo já morreu, copiar status e liberar slot; senão bloquear o chamador e
  registrar `join_target` e `join_status_addr`; um único joiner.
- **Encerrar processo:** marcar todas as threads mortas, acordar joiners e o `wait` do pai,
  liberar as páginas uma única vez e limpar os slots; tratar zumbis e órfãos.
- **Escalonar:** escolher a próxima tarefa pronta; `running` volta a `ready` no quantum;
  `blocked` volta a `ready` no wakeup; mortas não são escalonadas.

### 3.6 ABI de syscalls

| ID | Syscall | Argumentos | Retorno |
|---|---|---|---|
| 0 | `fork` | — | PID do filho / -1 |
| 1 | `wait` | W8 = endereço do status | PID / -1 |
| 2 | `exit` | W8 = status | não retorna |
| 3 | `getPID` | — | tgid (líder) |
| 4 | `rele` | W8 = quantum | 0 |
| 5 | `thread_create` | W8 = entry, W7 = topo da pilha | TID / -1 |
| 6 | `thread_join` | W8 = TID, W7 = endereço do status | 0 / -1 |
| 7 | `thread_exit` | W8 = status | não retorna |
| 8 | `getTID` | — | TID atual |
| 9 | `sem_create` | W8 = valor inicial | SID / -1 |
| 10 | `sem_wait` | W8 = SID | 0 / -1 |
| 11 | `sem_post` | W8 = SID | 0 / -1 |
| 12 | `sem_destroy` | W8 = SID | 0 / -1 |

Regras: retorno em `W9`; `-1` para erro; `TID` ou `SID` inválido retorna `-1`; `thread_join`
em si mesma, na thread líder ou em thread de outro `tgid` retorna `-1`; `fork` e `wait` só na
líder; semáforos só do próprio `tgid`. As syscalls 9–12 só entram se o checkpoint autorizar
semáforos.

Permissões por thread:

| Operação | Líder | Secundária |
|---|---|---|
| `fork` | sim | não, retorna `-1` |
| `wait` | sim | não, retorna `-1` |
| `exit` | sim, encerra o processo | sim, encerra o processo |
| `thread_create` | sim | sim |
| `thread_join` | sim, mas não pode juntar-se à líder | sim, mesmo `tgid` |
| `thread_exit` | sim, encerra o processo | sim, encerra só a thread |

### 3.7 Semáforos (condicional)

- 64 entradas de 16 bytes: `value`, `owner_tgid`, `head`, `tail`.
- `owner_tgid = -1` indica entrada livre.
- Fila FIFO de threads bloqueadas; `sem_post` acorda a primeira; `sem_destroy` acorda todas e
  invalida a entrada.
- `sem_wait` bloqueia se `value == 0`; decrementa caso contrário.
- Threads acordadas por `sem_destroy` retornam `-1`.
- Acesso de outro `tgid` retorna erro.
- Semáforos não são herdados no `fork`; ao encerrar o processo, as entradas do `tgid` são liberadas.

---

## 4. Entregas

### 4.1 Resumo

| Entrega | Foco | Depende de | Tamanho | Evidência |
|---|---|---|---|---|
| E0 | Preparação, baseline e testes | — | M | suíte roda; mapa do kernel |
| E1 | Relocação do layout | E0 | M | regressão idêntica |
| E2 | Tabela de tarefas e escalonador | E1 | M | metadados consistentes |
| E3 | `thread_create` e `getTID` | E2 | M | threads compartilham memória |
| E4 | `thread_exit` e `thread_join` | E3 | M | status e join corretos |
| E5 | Ciclo de vida do processo | E4 | G | kill/órfão sem vazamento |
| E6 | Semáforos: tabela e ciclo de vida | E5 | P | IDs e destroy |
| E7 | Semáforos: wait/post e bloqueio | E6 | M | contador e mutex |
| E8 | Exemplos, testes e documentação | todas | M | demo reproduzível |

### 4.2 Descrição das entregas

**E0 — Preparação, baseline e testes**
- Pode consumir 3–4 semanas por causa da curva de aprendizado no kernel.
- Criar `feat/threads` a partir de `sackOS-paginated`.
- Ler e mapear o ciclo de vida atual (`fork`, `wait`, `exit`, `kill`, escalonador).
- Rodar `fork_test.asm` e `jump_and_loop.asm`; registrar o baseline.
- Recuperar a suíte de integração do commit `381fb94` e adaptar ao estado atual.
- **Aceite:** kernel sobe, baseline passa, suíte executa e a dupla explica o ciclo de vida.

**E1 — Relocação do layout**
- Mover os globais de `0x2000`–`0x2024` para `0x7000`–`0x7024`.
- Atualizar `main_kernel.go`, TUI, suíte de testes (endereços fixos) e documentação.
- Construir os endereços derivados com somas.
- **Aceite:** regressão idêntica ao baseline; nenhuma referência antiga; boot com 2 GB e quantum 128.

**E2 — Tabela de tarefas e escalonador**
- Adicionar os metadados de 32 bytes e inicializá-los no setup.
- Fazer o escalonador usar `tgid` e `pt_phys`; manter `live_threads` atualizado.
- Criar helpers de alocação, liberação e busca da líder.
- **Aceite:** `fork`, `wait` e `exit` continuam funcionando; metadados coerentes; sem vazamento de slot.

**E3 — `thread_create` e `getTID`**
- Implementar as syscalls 5 e 8.
- Inicializar contexto, pilha, `W9 = TID`, `flags = 0x09` e metadados.
- `getPID` retorna a líder; `getTID` retorna a thread atual.
- **Aceite:** duas threads compartilham um global; TIDs distintos; mesmo PID; sem fault.

**E4 — `thread_exit` e `thread_join`**
- Implementar as syscalls 6 e 7.
- Exit marca morta, guarda status, acorda o joiner e decrementa `live_threads`.
- Join bloqueia ou copia o status; um único joiner; liberar o slot.
- **Aceite:** join recebe o status; join em thread morta retorna imediatamente; segundo join falha;
  exit da líder encerra o processo.

**E5 — Ciclo de vida do processo**
- Ajustar `kill_process`, `exit`, fault e OOM para N threads.
- Liberar páginas uma única vez; acordar joiners e o `wait` do pai.
- Tratar zumbis, órfãos e limpeza dos slots.
- **Aceite:** kill, exit e fault com 2–3 threads; bitmap estável; nenhum join ou wait trava;
  testes de órfão e zumbi passam.

**E6 — Semáforos: tabela e ciclo de vida (condicional)**
- Tabela de 64 entradas, `owner_tgid`, `head` e `tail`.
- `sem_create` e `sem_destroy`; validar `tgid`; liberar as entradas do `tgid` no encerramento do
  processo.
- **Aceite:** IDs válidos; acesso de outro `tgid` rejeitado; destroy acorda waiters.

**E7 — Semáforos: wait/post e bloqueio (condicional)**
- `sem_wait` e `sem_post` com fila FIFO e bloqueio/despertar.
- **Aceite:** contador com duas threads; mutex; destroy com waiters; sem deadlock nem wakeup perdido.

**E8 — Exemplos, testes e documentação**
- Atualizar a documentação a cada entrega; E8 apenas consolida o que já foi escrito.
- Programas: `threads_shared.asm`, `threads_join.asm`, `threads_kill.asm` e, se os semáforos
  entrarem, `sem_counter.asm` e `sem_mutex.asm`.
- Atualizar `specs.md`, `abstract_implementation.md`, README e configs de teste.
- **Aceite:** suíte completa verde; documentação compatível com a ABI; demo reproduzível.

---

## 5. Checkpoint e Plano B

O checkpoint acontece ao final da **E5**, antes de começar semáforos.

**Critérios para continuar (Go):**
- E5 e toda a regressão verdes.
- Nenhum vazamento de páginas ou slots.
- `join` e `wait` não travam.
- Testes de kill, órfão e zumbi passam.

**Se falhar (No-Go):**
- Cortar E6 e E7.
- Usar o tempo restante para estabilizar E0–E5 e concluir E8.
- Registrar semáforos como trabalho futuro na documentação.
- Se semáforos forem obrigatórios, renegociar prazo ou escopo com o professor; não prometer
  semáforos pela metade.

O checkpoint deve ocorrer até o **final de janeiro**, deixando fevereiro como buffer.

---

## 6. Cronograma

| Período | Foco | Marco |
|---|---|---|
| Outubro–meados de novembro | E0 | baseline e suíte rodando; confirmar semáforos com o professor |
| Meados de novembro–fim de novembro | E1 | relocação concluída e regressão verde |
| Dezembro | E2, E3 | metadados estáveis; threads criam e compartilham memória |
| Janeiro | E4, E5 | join, exit e ciclo de vida; checkpoint no fim do mês |
| Fevereiro | E6/E7 ou E8 | semáforos ou polimento; buffer até a entrega |

Dedicação considerada: 5–10 h por semana por pessoa. Com 5 h, o escopo comprometido já fica no
limite; o cenário confortável é 10 h por pessoa. Com 10 h, sobra folga para semáforos se o
checkpoint passar.

---

## 7. Riscos

| Risco | Impacto | Mitigação |
|---|---|---|
| Curva de aprendizado no kernel | alto | E0 com leitura dirigida, mapa do kernel e suíte de testes |
| Complexidade da E5 | alto | semântica simples (líder e um joiner), testes de invariantes e revisão em dupla |
| Debug em assembly | alto | testes de integração, TUI e commits pequenos |
| Erro na relocação | médio | busca por endereços antigos e regressão após E1 |
| Prazo curto | alto | checkpoint, Plano B e buffer em fevereiro |
| Semáforos obrigatórios | médio | confirmar com o professor até o fim de outubro |

---

## 8. Critérios de sucesso

- Demo: um programa cria duas threads, compartilha um contador, faz join e encerra corretamente.
- Regressão de `fork`, `wait` e `exit` verde.
- Testes de thread, kill, órfão e zumbi verdes.
- Sem vazamento de páginas ou slots.
- Documentação atualizada e compatível com a ABI.
- Se semáforos entrarem: contador e mutex funcionando.

---

## 9. Referências

- `sackOS/kernel.asm` — kernel e syscalls.
- `main_kernel.go` — escrita dos parâmetros do kernel.
- `machine/debug.go` e `tui.go` — endereços e comandos da TUI.
- `sackOS/specs.md` e `sackOS/abstract_implementation.md` — documentação a atualizar.
- `sackOS/tests/` (commit `381fb94`) — suíte de integração a recuperar.
- `asm/samples/fork_test.asm` e `asm/samples/jump_and_loop.asm` — baseline.
