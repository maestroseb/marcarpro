# Marcar Pro

App gratuita y libre para escribir encima de cualquier cosa en paneles interactivos
**ViewSonic ViewBoard** (y otros Android): navegador, libro digital, PDF, PC (Windows o
EducaAndOS) o HDMI.

**[Descargar](https://github.com/maestroseb/marcarpro/releases/latest)** ·
**[Página web](https://maestroseb.github.io/marcarpro/)** · la página vive en `docs/` (GitHub Pages).

Capa de anotación que escribe **por encima de cualquier app** en el ViewSonic ViewBoard,
al estilo de Smart Ink. Se toca el icono y se escribe: no hay pantalla de inicio.

| Qué toca la pantalla | Qué hace |
|---|---|
| ✍️ Lápiz | Escribe con la herramienta activa |
| 👆 Dedo | Usa las apps de debajo, o selecciona y mueve la tinta |
| ✊ Puño, palma o goma del lápiz | Borra solo la zona que toca |

En el panel infrarrojo todo llega como "dedo": lápiz, dedo y puño se distinguen por el
tamaño del contacto.

## Controles flotantes
- **Orbe** (verde azulado): un toque abre el anillo de herramientas — bolígrafo, fluorescente,
  borrador, ocultar tinta, tinta mágica (rosa neón, se desvanece sola) y formas. Un segundo
  toque sobre la herramienta activa abre su paleta (color y grosor, o tamaño de goma).
  Mantener pulsado = deshacer. Arrastrarlo a la **×** de abajo cierra la app.
- **Orbe de modo**: "el dedo usa las apps" ↔ "el dedo escribe" (varios alumnos a la vez, cada
  uno con su trazo y su borrador). Mantener pulsado = pausa.
- **Papelera**: mantener pulsada para borrar todo (se puede deshacer).

## Selección con el dedo
Primer toque: selecciona (tiradores para escalar y girar). Segundo toque: menú con Copiar,
Duplicar, color y Borrar. Arrastrar mueve; soltar con impulso lo lanza con rebote; pellizco
con dos dedos escala y gira. Tras Copiar, mantener pulsado un hueco pega.

## Tinta que se conserva
La pizarra se guarda sola y vuelve al abrir la app. Con el servicio de accesibilidad activo,
cada pestaña de Brave/Chrome tiene su propia tinta (solo se lee la barra de direcciones; las
direcciones no se guardan).

## Compilar y probar
Android Studio (AGP 8.13, Gradle 8.13, JDK 21). Por línea de comandos:
```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
```
Pruebas con varios dedos reales (emulador o panel), instalando antes los dos APK:
```bash
adb shell am instrument -w com.maestroseb.marcarpro.test/androidx.test.runner.AndroidJUnitRunner
```

## Instalar en el panel por ADB
```bash
PANEL=<IP-del-panel>:5555
adb -s $PANEL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $PANEL shell appops set com.maestroseb.marcarpro SYSTEM_ALERT_WINDOW allow
```
El servicio "Marcar Pro · finger to apps" se activa a mano en Ajustes › Accesibilidad.

## Pendiente consciente
`core-ktx 1.19` / `activity-ktx 1.13` y compileSdk 37 requieren AGP 9.1; se mantienen
`1.17.0` / `1.11.0` y compileSdk 36 para no romper la configuración actual.

## Licencia
Marcar Pro es software libre: puedes usarlo, estudiarlo, modificarlo y repartirlo bajo los
términos de la [GNU GPL v3](LICENSE). Si repartes una versión modificada, publica también su código.
