# Como contribuir

## Branches

| Branch | Para quê | Como recebe código |
|---|---|---|
| `main` | Produção | Só por PR vindo de `develop` (ou de `hotfix/*`), aprovado pelo responsável |
| `develop` | Testes e integração; é a branch padrão do repositório | Só por PR aprovado pelo responsável |
| branch do card | Trabalho de um card do quadro | Push de quem pegou o card |

Ninguém faz push direto em `main` ou `develop`; as duas também não podem ser apagadas nem reescritas (force push).

## Passo a passo

1. Pegue o card no quadro **SeniorInsight Kanban** e se atribua a ele.
2. No card, use **Development → Create a branch**. A branch nasce de `develop` com o número e o título do card (ex.: `84-hu-063-back-disparo-do-sos-e-estados`).
3. Faça os commits no padrão Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`).
4. Abra o PR para `develop`, preencha o modelo e cite o card: `Closes #84`, ou `Closes SeniorInsight-Project/<repositório>#84` se o card estiver em outro repositório.
5. O responsável ([CODEOWNERS](.github/CODEOWNERS)) revisa e aprova. Só depois da aprovação o PR pode ser mergeado; a branch do card é apagada automaticamente.
6. Quando `develop` estiver testada, o responsável abre o PR `develop → main` para publicar em produção.

## Correção urgente em produção

Crie `hotfix/<descrição>` a partir de `main`, abra o PR para `main` e, depois do merge, leve a mesma correção para `develop`.
