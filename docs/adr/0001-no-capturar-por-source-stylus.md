# 0001 · No capturar el lápiz por `setMotionEventSources(SOURCE_STYLUS)`

Fecha: 2026-11-06 · Estado: aceptada

## Contexto
En los paneles SMART (hardware CVTE, Android 15) se quería que el lápiz escribiera con Marcar Pro y el
dedo siguiera manejando las apps, capturando solo el lápiz con
`AccessibilityServiceInfo.setMotionEventSources(InputDevice.SOURCE_STYLUS)` (API 34+).

## Lo que se vio
En la SMART todos los toques llegan con la misma fuente (0x5002, táctil + stylus); el tipo de
herramienta sí distingue: dedo = FINGER, lápiz = STYLUS, borrador o palma = ERASER. Capturar «stylus»
se lleva también el dedo: el panel deja de responder a las apps hasta desinstalar la app.

## Decisión
No usar `setMotionEventSources` para separar lápiz y dedo. Si se retoma, la vía es capturar todo y
reenviar el dedo con `dispatchGesture` + `StrokeDescription.continueStroke` (funciona en el emulador
API 35; sin comprobar en el panel). Cualquier prueba de enrutado en un panel real: antes en el
emulador, con ADB a mano y una salida segura.
