# .gitignore per un progetto Android multi-modulo.
#
# Nota: `/build` (con slash iniziale) ignora SOLO la build della root.
# I moduli hanno la propria directory di build (`app/build/`), quindi serve
# il pattern `build/` senza slash: ignora build/ a qualsiasi profondita'.
# Il .gitignore standard di Android Studio ha questo difetto: committa
# centinaia di artefatti di compilazione.

*.iml
.gradle
/local.properties
/.idea
.DS_Store
/captures
.externalNativeBuild
.cxx
local.properties

# Artefatti di build in qualsiasi modulo
build/
