# Diagramas

Cada diagrama se guarda en dos formatos y una imagen:

| Archivo | Qué es |
|---|---|
| `*.json` | La fuente. Es lo que se edita. |
| `*.html` | El resultado, autónomo: se abre en cualquier navegador sin servidor ni dependencias. |
| `*.png` | Una captura, para poder incrustarla en el README —GitHub no renderiza HTML del repositorio—. |

Se generan con [archify](https://github.com/tt-a1i/archify):

```sh
node ~/.claude/skills/archify/bin/archify.mjs deliver architecture \
  docs/diagramas/job-forge-topologia.json \
  docs/diagramas/job-forge-topologia.html --quality showcase
```

**El HTML está versionado a sabiendas de que es un artefacto generado.** Lo
habitual es no commitear lo que se puede regenerar, y aquí se hace la excepción
por una razón concreta: quien clone este repositorio para leerlo —que es la
audiencia— no va a instalar una herramienta para ver un diagrama. El costo es que
`git diff` sobre ese archivo no dice nada útil y hay que acordarse de
regenerarlo cuando cambie el `.json`.
