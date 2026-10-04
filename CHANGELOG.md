# Changelog

Registro de sesiones de trabajo sobre MotoTrack. Cada entrada lleva fecha y hora de la sesión, no de cada commit.

## 2026-09-29 18:11 — Inclinación: tope de plausibilidad, corrección dinámica en curva y GPS a más frecuencia

Analizada la ruta de hoy (De Colegio Alemán de Málaga a Urbanización El Coto, R1200RS) tras verse un pico de inclinación de 49,3° en pantalla — por encima de lo que homologa la moto (~47°).

- **`TrackingService.kt`**: `MAX_PLAUSIBLE_LEAN_DEG` bajado de 50° a 47°, para que coincida con el propio comentario del código (ya documentaba el tope real de la R1200RS pero el valor usado dejaba un hueco de 3° por el que se colaba ruido de orientación).
- **`TrackingService.kt`**: nueva **corrección dinámica** del offset de inclinación (`trackDynamicCorrection`), además de la primaria (en recto) y la secundaria (al pararse) que ya había. Durante una curva sostenida a velocidad de carretera, calcula el lean de un turno coordinado (`atan(v·ω/g)`, con `ω` la velocidad de guiñada del rumbo GPS) y lo compara con el lean crudo del giroscopio para ajustar el offset — el mismo principio que usa un avión o un dron para saber su inclinación sin depender de encontrar "la vertical". A diferencia de la calibración en recto, no le afecta el peralte de la vía.
  - Motivo: comparando el conteo de curvas (`CurveCounter`) de las últimas 4 salidas del mismo trayecto (ida y vuelta, misma moto) salía siempre muy escorado a la izquierda (2:1 o 3:1) en ambos sentidos — algo que no puede ser la carretera (al volver, las izquierdas de ida deberían ser derechas), y apunta a un cero del sensor desplazado por el peralte de los tramos usados para calibrar en recto.
- **`TrackingService.kt`**: petición de ubicación (`registerGps`) subida de 1 Hz / 1 m mínimo a 5 Hz / sin mínimo de distancia, para que la derivada del rumbo (necesaria para la corrección dinámica) no salga tan ruidosa a velocidad de tráfico. Pendiente de comprobar en el CSV qué cadencia entrega de verdad el chip GNSS del móvil.

**Pendiente para la próxima ruta:** confirmar en el CSV la cadencia real del GPS y revisar los logs de "Corrección dinámica" para ver si el offset converge hacia un valor distinto del de la calibración en recto (confirmaría el peralte como causa del sesgo).
