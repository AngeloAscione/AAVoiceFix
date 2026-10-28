# .gitignore per un progetto con la squadra AI Company.
#
# Installa questo file accanto al .gitignore specifico del tuo stack
# (vedi templates/GITIGNORE_ANDROID.md per Android).

# Worktree isolati creati dal Tech Lead: sono repository git annidati.
# Senza questa regola, `git add .` li aggiunge all'indice come repository
# incorporati e i cloni del progetto risultano incompleti.
# Il .gitkeep resta tracciato per mantenere la directory nel repo.
.company/worktrees/*
!.company/worktrees/.gitkeep

# Board e artefatti di runtime: rigenerati dal protocollo, non versionati.
# Se vuoi tracciare i ticket, rimuovi questa riga.
.company/board/tickets/*.json
.company/board/handoffs/*.json
.company/board/packs/*.md
.company/escalations/*.md
.company/logs/*.json
