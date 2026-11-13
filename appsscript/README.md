# «A mi Drive» · web de Apps Script

Marcar Pro guarda la pizarra en el Drive del docente con un QR. El PDF va **directamente del
panel al Drive del docente**: esta web solo pasa avisos cortos (no guarda ni mueve archivos), así
que quien la publica no carga con los documentos de nadie ni hace de cuello de botella.

Se publica **una vez**, con una cuenta del dominio educativo. Los docentes no publican nada.

## Publicarla (10 minutos)

1. Entra en **script.google.com** con la cuenta del dominio → **Proyecto nuevo**.
   Ponle de nombre `Marcar Pro`.
2. Borra lo que hay en `Código.gs` y pega el contenido de [`Code.gs`](Code.gs).
3. Pulsa **+** junto a «Archivos» → **HTML** → nombre `Guardar` → pega [`Guardar.html`](Guardar.html).
4. Pulsa la rueda **⚙ Configuración del proyecto** → marca **«Mostrar el archivo de manifiesto
   "appsscript.json" en el editor»**. Vuelve al editor (`< >`), abre `appsscript.json` y
   sustituye su contenido por el de [`appsscript.json`](appsscript.json). Guarda (💾).
5. Arriba, elige la función `carpeta_` y pulsa **Ejecutar**. Google pedirá permiso:
   **Revisar permisos** → tu cuenta → **Permitir**. (Solo es para dar los permisos; si después
   aparece un error en el registro, no importa.)
6. **Implementar → Nueva implementación** → ⚙ → **Aplicación web**:
   - Descripción: `panel`
   - Ejecutar como: **Yo**
   - Quién tiene acceso: **Cualquier usuario**
   → **Implementar** → copia la **URL de la aplicación web** (acaba en `/exec`).
7. Otra vez **Implementar → Nueva implementación** → **Aplicación web**:
   - Descripción: `docentes`
   - Ejecutar como: **Usuario que accede a la aplicación web**
   - Quién tiene acceso: **Cualquier usuario de** (tu dominio)
   → **Implementar** → copia también esta URL.
8. Pon las dos direcciones en `~/.marcarpro/drive.properties` del ordenador que compila la app
   (fuera del repositorio):

   ```properties
   panelUrl=https://script.google.com/macros/s/…/exec
   phoneUrl=https://script.google.com/a/macros/…/exec
   ```

**Importante:** al publicar una versión nueva, comprueba siempre que «docentes» sigue en
«Usuario que accede a la aplicación web» (puede quedarse en «Yo»). Si estuviera en «Yo», la web
se niega a guardar y lo avisa, para que ningún PDF acabe en el Drive de quien la publicó.

Si cambias el código, publica con **Implementar → Gestionar implementaciones → ✏ → Versión:
Nueva**, en las dos implementaciones, para que la URL no cambie.
